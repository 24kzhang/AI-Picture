package com.zys.backend.agent;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zys.backend.agent.dto.AgentApiRequests;
import com.zys.backend.agent.dto.AgentAssetDTO;
import com.zys.backend.agent.dto.AgentRequests;
import com.zys.backend.agent.dto.AgentRunDTO;
import com.zys.backend.agent.dto.AgentSessionDetailDTO;
import com.zys.backend.agent.dto.AgentTurnDTO;
import com.zys.backend.agent.vo.AgentSessionVO;
import com.zys.backend.agent.vo.AgentTurnVO;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.exception.ThrowUtils;
import com.zys.backend.manager.CosStorageManager;
import com.zys.backend.manager.lease.EditLeaseService;
import com.zys.backend.mapper.PictureEditRunMapper;
import com.zys.backend.mapper.PictureEditSessionMapper;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.PictureEditRun;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.entity.User;
import com.zys.backend.model.enums.AgentRunStatusEnum;
import com.zys.backend.model.enums.PictureEditSessionStatusEnum;
import com.zys.backend.service.PictureService;
import com.zys.backend.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Agent 编辑会话编排：会话生命周期、消息、工具、选区与运行记录映射。
 */
@Slf4j
@Service
public class AgentEditSessionService {

    private static final String IDEMPOTENCY_KEY_PREFIX = "agent:session:idem:";
    private static final long IDEMPOTENCY_TTL_HOURS = 24;
    private static final long SESSION_TTL_DAYS = 7;
    private static final int RECENT_TURN_LIMIT = 20;

    @Resource
    private PictureService pictureService;

    @Resource
    private UserService userService;

    @Resource
    private PictureEditSessionMapper editSessionMapper;

    @Resource
    private PictureEditRunMapper editRunMapper;

    @Resource
    private RetouchAgentClient agentClient;

    @Resource
    private AgentPicturePermissionChecker permissionChecker;

    @Resource
    private CosStorageManager cosStorageManager;

    @Resource
    private EditLeaseService editLeaseService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 创建（或复用）编辑会话
     */
    public AgentSessionVO createSession(Long pictureId, String idempotencyKey, User loginUser) {
        ThrowUtils.throwIf(!agentClient.isEnabled(), ErrorCode.OPERATION_ERROR, "Agent 智能精修未开启");
        Picture picture = permissionChecker.checkPictureEditable(loginUser, pictureId);

        // 幂等键优先：同一键直接返回首次创建结果（并重取租约，保证可直接继续编辑）
        String idemValue = null;
        if (idempotencyKey != null && !idempotencyKey.trim().isEmpty()) {
            idemValue = stringRedisTemplate.opsForValue().get(idempotencyKeyOf(loginUser.getId(), idempotencyKey));
            if (idemValue != null) {
                PictureEditSession existing = editSessionMapper.selectById(Long.parseLong(idemValue));
                if (existing != null) {
                    editLeaseService.tryAcquire(pictureId, EditLeaseService.MODE_AGENT,
                            loginUser.getId(), String.valueOf(existing.getId()));
                    return buildSessionVO(existing, loginUser, false);
                }
            }
        }

        // 复用当前用户在同一图片上的活动会话（并重取租约：锁可能已过期）
        PictureEditSession active = findActiveSession(pictureId, loginUser.getId());
        if (active != null) {
            editLeaseService.tryAcquire(pictureId, EditLeaseService.MODE_AGENT,
                    loginUser.getId(), String.valueOf(active.getId()));
            return rememberIdempotency(active, idempotencyKey, loginUser.getId());
        }

        // 先落 DRAFT 会话记录（拿到会话 id 作为租约身份），再抢统一编辑租约
        PictureEditSession record = new PictureEditSession();
        String placeholderSessionId = java.util.UUID.randomUUID().toString();
        record.setAgentSessionId(placeholderSessionId);
        record.setPictureId(pictureId);
        record.setSpaceId(picture.getSpaceId());
        record.setUserId(loginUser.getId());
        record.setBaseEditVersion(picture.getEditVersion() == null ? 0L : picture.getEditVersion());
        record.setStatus(PictureEditSessionStatusEnum.DRAFT.getValue());
        Date now = new Date();
        record.setCreateTime(now);
        record.setUpdateTime(now);
        record.setExpireTime(new Date(now.getTime() + SESSION_TTL_DAYS * 24 * 3600 * 1000L));
        editSessionMapper.insert(record);

        EditLeaseService.Lease lease = editLeaseService.tryAcquire(pictureId,
                EditLeaseService.MODE_AGENT, loginUser.getId(), String.valueOf(record.getId()));
        if (lease == null) {
            record.setStatus(PictureEditSessionStatusEnum.CANCELED.getValue());
            record.setUpdateTime(new Date());
            editSessionMapper.updateById(record);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "另一位用户正在编辑该图片，请稍后再试");
        }

        // 源图上传到 Agent 并创建 Agent 会话；失败则释放租约并作废本地记录
        try {
            AgentCallContext context = buildContext(loginUser, picture);
            String assetId = uploadSourceAsset(picture, context);
            AgentSessionDetailDTO detail = agentClient.createSession(assetId, null,
                    picture.getName(), context);
            ThrowUtils.throwIf(detail == null || detail.getId() == null,
                    ErrorCode.OPERATION_ERROR, "创建修图会话失败");
            record.setAgentSessionId(detail.getId());
            record.setStatus(PictureEditSessionStatusEnum.ACTIVE.getValue());
            record.setUpdateTime(new Date());
            editSessionMapper.updateById(record);
        } catch (RuntimeException e) {
            editLeaseService.release(pictureId, lease.getLockToken());
            record.setStatus(PictureEditSessionStatusEnum.CANCELED.getValue());
            record.setUpdateTime(new Date());
            editSessionMapper.updateById(record);
            throw e;
        }

        return rememberIdempotency(record, idempotencyKey, loginUser.getId());
    }

    /**
     * 查询会话详情（所有者可写，其他有查看权限用户只读）
     */
    public AgentSessionVO getSession(Long sessionId, User loginUser) {
        PictureEditSession record = loadSession(sessionId);
        Picture picture = pictureService.getById(record.getPictureId());
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        boolean owner = record.getUserId().equals(loginUser.getId());
        if (!owner) {
            List<String> permissions = permissionChecker.permissionsOf(loginUser, picture);
            ThrowUtils.throwIf(!permissions.contains("picture:view"),
                    ErrorCode.NO_AUTH_ERROR, "无权查看该会话");
        }
        return buildSessionVO(record, loginUser, !owner);
    }

    /**
     * 取消会话
     */
    public Boolean cancelSession(Long sessionId, User loginUser) {
        PictureEditSession record = loadSession(sessionId);
        checkOwner(record, loginUser);
        String status = record.getStatus();
        ThrowUtils.throwIf(PictureEditSessionStatusEnum.COMMITTED.getValue().equals(status)
                        || PictureEditSessionStatusEnum.COMMITTING.getValue().equals(status),
                ErrorCode.OPERATION_ERROR, "会话已提交，无法取消");
        if (PictureEditSessionStatusEnum.CANCELED.getValue().equals(status)) {
            return true;
        }
        record.setStatus(PictureEditSessionStatusEnum.CANCELED.getValue());
        record.setUpdateTime(new Date());
        editSessionMapper.updateById(record);
        // 释放统一编辑租约
        editLeaseService.releaseForSession(record.getPictureId(), EditLeaseService.MODE_AGENT,
                record.getUserId(), String.valueOf(record.getId()));
        return true;
    }

    /**
     * 编辑租约心跳续租（前端每 20 秒调用）
     */
    public Boolean heartbeat(Long sessionId, User loginUser) {
        PictureEditSession record = loadSession(sessionId);
        checkOwner(record, loginUser);
        boolean renewed = editLeaseService.renewForSession(record.getPictureId(),
                EditLeaseService.MODE_AGENT, record.getUserId(), String.valueOf(record.getId()));
        ThrowUtils.throwIf(!renewed, ErrorCode.OPERATION_ERROR,
                "编辑租约已失效，请重新进入工作台");
        return true;
    }

    /**
     * 校验当前会话仍持有 Agent 编辑租约；锁丢失后禁止修改，任务结果保留为草稿
     */
    private void requireAgentLease(PictureEditSession record) {
        boolean held = editLeaseService.isHeldBy(record.getPictureId(),
                EditLeaseService.MODE_AGENT, record.getUserId(), String.valueOf(record.getId()));
        if (!held) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "编辑租约已失效，结果仅保留为草稿，请重新进入工作台");
        }
    }

    /**
     * 发送对话消息（可能产生多步计划）
     */
    public AgentTurnVO sendMessage(Long sessionId, String text, User loginUser) {
        PictureEditSession record = requireActiveSession(sessionId, loginUser);
        AgentCallContext context = buildContext(loginUser, record);
        AgentTurnDTO turn = agentClient.sendMessage(record.getAgentSessionId(), text, context);
        return upsertPlanRun(record, turn);
    }

    /**
     * 调用单步工具
     */
    public AgentTurnVO invokeTool(Long sessionId, String tool, Map<String, Object> params, User loginUser) {
        PictureEditSession record = requireActiveSession(sessionId, loginUser);
        AgentCallContext context = buildContext(loginUser, record);
        Map<String, Object> result = agentClient.invokeTool(record.getAgentSessionId(), tool,
                params == null ? Map.of() : params, context);
        AgentRunDTO run = extractRun(result);
        ThrowUtils.throwIf(run == null, ErrorCode.OPERATION_ERROR, "工具调用失败");
        return upsertToolRun(record, run);
    }

    /**
     * 确认多步计划
     */
    public AgentTurnVO confirmRun(Long runId, User loginUser) {
        return driveTurn(runId, loginUser, TurnAction.CONFIRM);
    }

    /**
     * 取消多步计划
     */
    public AgentTurnVO cancelRun(Long runId, User loginUser) {
        return driveTurn(runId, loginUser, TurnAction.CANCEL);
    }

    /**
     * 重试失败步骤
     */
    public AgentTurnVO retryRun(Long runId, User loginUser) {
        return driveTurn(runId, loginUser, TurnAction.RETRY);
    }

    /**
     * 查询运行快照（优先 Agent 实时状态）
     */
    public AgentTurnVO getRun(Long runId, User loginUser) {
        PictureEditRun run = loadRun(runId);
        PictureEditSession record = loadSession(run.getEditSessionId());
        checkOwner(record, loginUser);
        if (run.getAgentRunId() != null) {
            try {
                AgentRunDTO live = agentClient.getRun(run.getAgentRunId(),
                        buildContext(loginUser, record));
                if (live != null) {
                    syncRunStatus(run, live);
                    return toTurnVO(run, null);
                }
            } catch (BusinessException e) {
                log.warn("查询 Agent Run 实时状态失败，回退数据库快照：{}", e.getMessage());
            }
        }
        return toTurnVO(run, null);
    }

    /**
     * 预热选区模型
     */
    public Boolean prepareSelection(Long sessionId, User loginUser) {
        PictureEditSession record = requireActiveSession(sessionId, loginUser);
        agentClient.prepareSelection(record.getAgentSessionId(), buildContext(loginUser, record));
        return true;
    }

    /**
     * 设置选区
     */
    public Map<String, Object> setSelection(Long sessionId, AgentApiRequests.SelectionRequest request,
                                            User loginUser) {
        request.validate();
        PictureEditSession record = requireActiveSession(sessionId, loginUser);
        AgentRequests.Select select = toSelectRequest(request);
        return agentClient.setSelection(record.getAgentSessionId(), select, buildContext(loginUser, record));
    }

    /**
     * 查询选区
     */
    public Map<String, Object> getSelection(Long sessionId, User loginUser) {
        PictureEditSession record = requireActiveSession(sessionId, loginUser);
        return agentClient.getSelection(record.getAgentSessionId(), buildContext(loginUser, record));
    }

    /**
     * 清除选区
     */
    public Boolean deleteSelection(Long sessionId, User loginUser) {
        PictureEditSession record = requireActiveSession(sessionId, loginUser);
        agentClient.deleteSelection(record.getAgentSessionId(), buildContext(loginUser, record));
        return true;
    }

    /**
     * 撤销
     */
    public AgentSessionDetailDTO undo(Long sessionId, User loginUser) {
        PictureEditSession record = requireActiveSession(sessionId, loginUser);
        return agentClient.undo(record.getAgentSessionId(), buildContext(loginUser, record));
    }

    /**
     * 重做
     */
    public AgentSessionDetailDTO redo(Long sessionId, User loginUser) {
        PictureEditSession record = requireActiveSession(sessionId, loginUser);
        return agentClient.redo(record.getAgentSessionId(), buildContext(loginUser, record));
    }

    /**
     * 按 Agent Run ID 同步数据库运行状态（SSE 轮询回调）
     */
    public void syncRunByAgentRunId(PictureEditRun run, AgentRunDTO live) {
        syncRunStatus(run, live);
    }

    private enum TurnAction {
        CONFIRM, CANCEL, RETRY
    }

    private AgentTurnVO driveTurn(Long runId, User loginUser, TurnAction action) {
        PictureEditRun run = loadRun(runId);
        PictureEditSession record = loadSession(run.getEditSessionId());
        checkOwner(record, loginUser);
        requireAgentLease(record);
        ThrowUtils.throwIf(!"PLAN".equals(run.getRunType()),
                ErrorCode.OPERATION_ERROR, "仅多步计划支持确认/取消/重试");
        ThrowUtils.throwIf(run.getAgentRunId() == null, ErrorCode.NOT_FOUND_ERROR, "运行不存在");
        AgentCallContext context = buildContext(loginUser, record);
        String agentSessionId = record.getAgentSessionId();
        AgentTurnDTO turn;
        switch (action) {
            case CONFIRM:
                turn = agentClient.confirmTurn(agentSessionId, run.getAgentRunId(), context);
                break;
            case CANCEL:
                turn = agentClient.cancelTurn(agentSessionId, run.getAgentRunId(), context);
                break;
            default:
                turn = agentClient.retryTurn(agentSessionId, run.getAgentRunId(), context);
                break;
        }
        return updatePlanRunFromTurn(run, turn);
    }

    private AgentTurnVO upsertPlanRun(PictureEditSession record, AgentTurnDTO turn) {
        PictureEditRun existing = findRunByAgentRunId(turn.getId());
        PictureEditRun run = existing != null ? existing : new PictureEditRun();
        if (existing == null) {
            run.setEditSessionId(record.getId());
            run.setAgentRunId(turn.getId());
            run.setRunType("PLAN");
            run.setCreateTime(new Date());
        }
        applyTurnToRun(run, turn);
        if (existing == null) {
            editRunMapper.insert(run);
        } else {
            editRunMapper.updateById(run);
        }
        return toTurnVO(run, turn);
    }

    private AgentTurnVO updatePlanRunFromTurn(PictureEditRun run, AgentTurnDTO turn) {
        applyTurnToRun(run, turn);
        editRunMapper.updateById(run);
        return toTurnVO(run, turn);
    }

    private AgentTurnVO upsertToolRun(PictureEditSession record, AgentRunDTO agentRun) {
        PictureEditRun run = new PictureEditRun();
        run.setEditSessionId(record.getId());
        run.setAgentRunId(agentRun.getId());
        run.setRunType("TOOL");
        run.setStatus(agentRun.getStatus());
        run.setProgress(agentRun.getProgress());
        run.setStage(agentRun.getStage());
        run.setCreateTime(new Date());
        run.setUpdateTime(new Date());
        editRunMapper.insert(run);
        AgentTurnVO vo = new AgentTurnVO();
        vo.setId(run.getId());
        vo.setAgentRunId(run.getAgentRunId());
        vo.setRunType(run.getRunType());
        vo.setStatus(run.getStatus());
        vo.setProgress(run.getProgress());
        vo.setStage(run.getStage());
        vo.setCreateTime(run.getCreateTime());
        return vo;
    }

    private void applyTurnToRun(PictureEditRun run, AgentTurnDTO turn) {
        run.setStatus(turn.getStatus());
        run.setProgress(turn.needsConfirm() ? 0 : deriveProgress(turn));
        run.setStage(turn.getReply());
        run.setPlanJson(JSONUtil.toJsonStr(turn.getSteps()));
        run.setErrorCode(null);
        run.setErrorMessage(turn.getError());
        run.setUpdateTime(new Date());
        if (AgentRunStatusEnum.isTerminal(turn.getStatus())) {
            run.setCompleteTime(new Date());
        }
    }

    private void syncRunStatus(PictureEditRun run, AgentRunDTO live) {
        boolean changed = !java.util.Objects.equals(run.getStatus(), live.getStatus())
                || !java.util.Objects.equals(run.getProgress(), live.getProgress())
                || !java.util.Objects.equals(run.getStage(), live.getStage());
        if (!changed) {
            return;
        }
        run.setStatus(live.getStatus());
        run.setProgress(live.getProgress());
        run.setStage(live.getStage());
        run.setErrorMessage(live.getError());
        run.setUpdateTime(new Date());
        if (AgentRunStatusEnum.isTerminal(live.getStatus()) && run.getCompleteTime() == null) {
            run.setCompleteTime(new Date());
        }
        editRunMapper.updateById(run);
    }

    private AgentSessionVO buildSessionVO(PictureEditSession record, User loginUser, boolean readOnly) {
        AgentSessionVO vo = new AgentSessionVO();
        vo.setId(record.getId());
        vo.setAgentSessionId(record.getAgentSessionId());
        vo.setPictureId(record.getPictureId());
        vo.setSpaceId(record.getSpaceId());
        vo.setBaseEditVersion(record.getBaseEditVersion());
        vo.setStatus(record.getStatus());
        vo.setFinalAgentAssetId(record.getFinalAgentAssetId());
        vo.setCommittedVersionId(record.getCommittedVersionId());
        vo.setCreateTime(record.getCreateTime());
        vo.setUpdateTime(record.getUpdateTime());
        vo.setExpireTime(record.getExpireTime());
        vo.setReadOnly(readOnly);
        // 当前编辑租约（不含 lockToken）
        EditLeaseService.Lease lease = editLeaseService.current(record.getPictureId());
        if (lease != null) {
            AgentSessionVO.AgentLeaseVO leaseVO = new AgentSessionVO.AgentLeaseVO();
            leaseVO.setMode(lease.getMode());
            leaseVO.setUserId(lease.getUserId());
            leaseVO.setSessionId(lease.getSessionId());
            leaseVO.setAcquiredAt(lease.getAcquiredAt());
            vo.setLease(leaseVO);
        }
        // 画布快照与最近对话：Agent 不可用时返回数据库骨架
        try {
            AgentCallContext context = buildContext(loginUser, record);
            AgentSessionDetailDTO detail = agentClient.getSession(record.getAgentSessionId(), context);
            vo.setCanvas(detail);
            List<AgentTurnDTO> turns = agentClient.listMessages(record.getAgentSessionId(), context);
            if (turns != null && !turns.isEmpty()) {
                Map<String, PictureEditRun> runByAgentId = editRunMapper.selectList(
                                new QueryWrapper<PictureEditRun>()
                                        .eq("editSessionId", record.getId())
                                        .orderByDesc("createTime"))
                        .stream()
                        .filter(r -> r.getAgentRunId() != null)
                        .collect(Collectors.toMap(PictureEditRun::getAgentRunId, r -> r, (a, b) -> a));
                List<AgentTurnVO> turnVOs = new ArrayList<>();
                int limit = Math.min(turns.size(), RECENT_TURN_LIMIT);
                for (int i = turns.size() - limit; i < turns.size(); i++) {
                    AgentTurnDTO turn = turns.get(i);
                    PictureEditRun run = runByAgentId.get(turn.getId());
                    AgentTurnVO turnVO = toTurnVO(run, turn);
                    if (turnVO.getId() == null) {
                        // 数据库无映射时补建，保证前端拿到云图库 runId
                        turnVO = upsertPlanRun(record, turn);
                    }
                    turnVOs.add(turnVO);
                }
                vo.setTurns(turnVOs);
            }
        } catch (BusinessException e) {
            if (readOnly) {
                throw e;
            }
            log.warn("加载 Agent 会话快照失败，返回本地骨架：{}", e.getMessage());
        }
        return vo;
    }

    private AgentTurnVO toTurnVO(PictureEditRun run, AgentTurnDTO turn) {
        AgentTurnVO vo = new AgentTurnVO();
        if (run != null) {
            vo.setId(run.getId());
            vo.setAgentRunId(run.getAgentRunId());
            vo.setRunType(run.getRunType());
            vo.setStatus(run.getStatus());
            vo.setProgress(run.getProgress());
            vo.setCreateTime(run.getCreateTime());
        }
        if (turn != null) {
            if (vo.getAgentRunId() == null) {
                vo.setAgentRunId(turn.getId());
            }
            if (vo.getRunType() == null) {
                vo.setRunType("PLAN");
            }
            vo.setStatus(turn.getStatus());
            vo.setGoal(turn.getGoal());
            vo.setReply(turn.getReply());
            vo.setError(turn.getError());
            vo.setSteps(turn.getSteps());
            if (vo.getCreateTime() == null) {
                vo.setCreateTime(turn.getCreatedAt());
            }
        }
        return vo;
    }

    private String uploadSourceAsset(Picture picture, AgentCallContext context) {
        try (CosStorageManager.ManagedImageFile file = cosStorageManager.materialize(picture.getUrl())) {
            byte[] data = Files.readAllBytes(file.getPath());
            String filename = picture.getName() == null ? "source.png" : picture.getName();
            AgentAssetDTO asset = agentClient.uploadAsset(data, filename,
                    probeContentType(filename), context);
            ThrowUtils.throwIf(asset == null || asset.getId() == null,
                    ErrorCode.OPERATION_ERROR, "上传源图到修图服务失败");
            return asset.getId();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "读取源图失败：" + e.getMessage());
        }
    }

    private String probeContentType(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    private AgentSessionVO rememberIdempotency(PictureEditSession record, String idempotencyKey, Long userId) {
        if (idempotencyKey != null && !idempotencyKey.trim().isEmpty()) {
            stringRedisTemplate.opsForValue().set(
                    idempotencyKeyOf(userId, idempotencyKey),
                    String.valueOf(record.getId()),
                    IDEMPOTENCY_TTL_HOURS, TimeUnit.HOURS);
        }
        User loginUser = userService.getById(record.getUserId());
        return buildSessionVO(record, loginUser, false);
    }

    private PictureEditSession findActiveSession(Long pictureId, Long userId) {
        return editSessionMapper.selectOne(new QueryWrapper<PictureEditSession>()
                .eq("pictureId", pictureId)
                .eq("userId", userId)
                .eq("status", PictureEditSessionStatusEnum.ACTIVE.getValue())
                .orderByDesc("createTime")
                .last("limit 1"));
    }

    private PictureEditSession requireActiveSession(Long sessionId, User loginUser) {
        PictureEditSession record = loadSession(sessionId);
        checkOwner(record, loginUser);
        String status = record.getStatus();
        ThrowUtils.throwIf(!PictureEditSessionStatusEnum.ACTIVE.getValue().equals(status)
                        && !PictureEditSessionStatusEnum.READY_TO_COMMIT.getValue().equals(status),
                ErrorCode.OPERATION_ERROR, "会话不可用（状态：" + status + "）");
        requireAgentLease(record);
        return record;
    }

    private PictureEditSession loadSession(Long sessionId) {
        ThrowUtils.throwIf(sessionId == null || sessionId <= 0, ErrorCode.PARAMS_ERROR, "会话 id 非法");
        PictureEditSession record = editSessionMapper.selectById(sessionId);
        ThrowUtils.throwIf(record == null, ErrorCode.NOT_FOUND_ERROR, "编辑会话不存在");
        return record;
    }

    private PictureEditRun loadRun(Long runId) {
        ThrowUtils.throwIf(runId == null || runId <= 0, ErrorCode.PARAMS_ERROR, "运行 id 非法");
        PictureEditRun run = editRunMapper.selectById(runId);
        ThrowUtils.throwIf(run == null, ErrorCode.NOT_FOUND_ERROR, "运行记录不存在");
        return run;
    }

    private PictureEditRun findRunByAgentRunId(String agentRunId) {
        return editRunMapper.selectOne(new QueryWrapper<PictureEditRun>()
                .eq("agentRunId", agentRunId)
                .last("limit 1"));
    }

    private void checkOwner(PictureEditSession record, User loginUser) {
        if (!record.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "仅会话所有者可执行该操作");
        }
    }

    private AgentCallContext buildContext(User loginUser, Picture picture) {
        return AgentCallContext.builder()
                .userId(loginUser.getId())
                .pictureId(picture.getId())
                .spaceId(picture.getSpaceId())
                .permissions(permissionChecker.permissionsOf(loginUser, picture))
                .build();
    }

    private AgentCallContext buildContext(User loginUser, PictureEditSession record) {
        return AgentCallContext.builder()
                .userId(loginUser.getId())
                .pictureId(record.getPictureId())
                .spaceId(record.getSpaceId())
                .build();
    }

    private AgentRequests.Select toSelectRequest(AgentApiRequests.SelectionRequest request) {
        AgentRequests.Select select = new AgentRequests.Select();
        select.setRevision(request.getRevision());
        select.setPoints(request.getPoints() == null ? new ArrayList<>() : request.getPoints().stream()
                .map(p -> new AgentRequests.Point(p.getX(), p.getY()))
                .collect(Collectors.toList()));
        select.setStrokes(request.getStrokes() == null ? new ArrayList<>() : request.getStrokes().stream()
                .map(stroke -> stroke.stream()
                        .map(p -> new AgentRequests.Point(p.getX(), p.getY()))
                        .collect(Collectors.toList()))
                .collect(Collectors.toList()));
        select.setRadius(request.getRadius() == null ? 0.03 : request.getRadius());
        select.setAppend(request.getAppend() != null && request.getAppend());
        return select;
    }

    private String idempotencyKeyOf(Long userId, String key) {
        return IDEMPOTENCY_KEY_PREFIX + userId + ":" + key;
    }

    @SuppressWarnings("unchecked")
    private AgentRunDTO extractRun(Map<String, Object> result) {
        if (result == null) {
            return null;
        }
        Object run = result.get("run");
        if (run == null) {
            return null;
        }
        String json = JSONUtil.toJsonStr(run);
        return JSONUtil.toBean(json, AgentRunDTO.class);
    }

    private int deriveProgress(AgentTurnDTO turn) {
        if (turn.getSteps() == null || turn.getSteps().isEmpty()) {
            return 0;
        }
        long done = turn.getSteps().stream().filter(s -> "succeeded".equals(s.getStatus())).count();
        return (int) (done * 100 / turn.getSteps().size());
    }
}
