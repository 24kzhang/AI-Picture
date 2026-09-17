package com.zys.backend.agent.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zys.backend.agent.model.AgentRunEventVO;
import com.zys.backend.agent.model.PlannerOutcome;
import com.zys.backend.agent.model.vo.AgentAssetVO;
import com.zys.backend.agent.model.vo.AgentRunVO;
import com.zys.backend.agent.model.vo.AgentSessionVO;
import com.zys.backend.agent.model.vo.AgentToolVO;
import com.zys.backend.agent.tool.AgentToolException;
import com.zys.backend.agent.tool.ToolRegistry;
import com.zys.backend.agent.tool.ToolSpec;
import com.zys.backend.constant.AgentConstant;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.mapper.PictureAgentRunMapper;
import com.zys.backend.mapper.PictureEditSessionMapper;
import com.zys.backend.mapper.PictureMapper;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.PictureAgentAsset;
import com.zys.backend.model.entity.PictureAgentRun;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.entity.User;
import com.zys.backend.model.enums.AgentAssetKindEnum;
import com.zys.backend.model.enums.AgentRunStatusEnum;
import com.zys.backend.model.enums.EditSessionStatusEnum;
import com.zys.backend.model.enums.ToolRunStatusEnum;
import com.zys.backend.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent 会话编排：创建/复用会话、编辑租约、消息规划、工具直调、
 * 选区、撤销重做、候选采用与最终草稿选定。
 */
@Slf4j
@Service
public class AgentSessionService {

    private static final int SESSION_EXPIRE_HOURS = 72;
    private static final int RECENT_RUNS = 10;

    @Resource
    private PictureMapper pictureMapper;

    @Resource
    private PictureEditSessionMapper sessionMapper;

    @Resource
    private PictureAgentRunMapper agentRunMapper;

    @Resource
    private UserService userService;

    @Resource
    private AgentAuthService authService;

    @Resource
    private EditLeaseService editLeaseService;

    @Resource
    private AgentAssetService assetService;

    @Resource
    private LayerDocumentService documentService;

    @Resource
    private SelectionService selectionService;

    @Resource
    private AgentPlannerService plannerService;

    @Resource
    private PlanValidator planValidator;

    @Resource
    private PlanExecutor planExecutor;

    @Resource
    private ToolRunService toolRunService;

    @Resource
    private AgentRunSnapshotService snapshotService;

    @Resource
    private AgentEventPublisher eventPublisher;

    @Resource
    private ToolRegistry toolRegistry;

    // region 会话生命周期

    /**
     * 创建或复用编辑会话：校验权限、获取 AGENT 租约、初始化画布文档
     */
    public AgentSessionVO createOrReuse(Long pictureId, User loginUser) {
        Picture picture = requirePicture(pictureId);
        authService.requireEdit(loginUser, picture);

        PictureEditSession existing = findReusableSession(pictureId, loginUser.getId());
        if (existing != null) {
            renewLease(existing, loginUser);
            return getSnapshot(existing.getId(), loginUser);
        }
        // 租约互斥：快捷编辑或他人的 Agent 会话占用时拒绝
        if (editLeaseService.heldByOther(pictureId, AgentConstant.EDIT_LOCK_MODE_AGENT, loginUser.getId(), null)) {
            JSONObject holder = editLeaseService.get(pictureId);
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "图片正在被" + (holder != null && AgentConstant.EDIT_LOCK_MODE_QUICK.equals(holder.getStr("mode"))
                            ? "快捷编辑" : "其他用户") + "占用，请稍后再试");
        }

        PictureAgentAsset original = assetService.createReference(0L, pictureId, loginUser.getId(),
                AgentAssetKindEnum.ORIGINAL.getValue(), "session-init",
                picture.getUrl(), picture.getPicWidth(), picture.getPicHeight(), picture.getPicSize());

        PictureEditSession session = new PictureEditSession();
        session.setPictureId(pictureId);
        session.setSpaceId(picture.getSpaceId());
        session.setUserId(loginUser.getId());
        session.setBaseEditVersion(picture.getEditVersion() == null ? 0L : picture.getEditVersion());
        session.setStatus(EditSessionStatusEnum.ACTIVE.getValue());
        session.setCurrentAssetId(original.getId());
        session.setRevision(1);
        session.setHistorySeq(0);
        Calendar expire = Calendar.getInstance();
        expire.add(Calendar.HOUR_OF_DAY, SESSION_EXPIRE_HOURS);
        session.setExpireTime(expire.getTime());
        session.setCreateTime(new Date());
        session.setEditTime(new Date());
        sessionMapper.insert(session);

        // 会话建立后再把资产挂到会话下并初始化文档（需要 sessionId）
        updateAssetSession(original.getId(), session.getId());

        JSONObject document = documentService.initDocument(picture, original);
        PictureEditSession docUpdate = new PictureEditSession();
        docUpdate.setId(session.getId());
        docUpdate.setDocumentJson(document.toString());
        sessionMapper.updateById(docUpdate);
        session.setDocumentJson(document.toString());

        editLeaseService.forceAcquire(pictureId, AgentConstant.EDIT_LOCK_MODE_AGENT,
                loginUser.getId(), session.getId());
        log.info("创建 Agent 编辑会话，sessionId={}，pictureId={}，userId={}，baseEditVersion={}",
                session.getId(), pictureId, loginUser.getId(), session.getBaseEditVersion());
        return getSnapshot(session.getId(), loginUser);
    }

    private void updateAssetSession(Long assetId, Long sessionId) {
        assetService.rebind(assetId, sessionId);
    }

    private PictureEditSession findReusableSession(Long pictureId, Long userId) {
        PictureEditSession session = sessionMapper.selectOne(Wrappers.<PictureEditSession>lambdaQuery()
                .eq(PictureEditSession::getPictureId, pictureId)
                .eq(PictureEditSession::getUserId, userId)
                .eq(PictureEditSession::getStatus, EditSessionStatusEnum.ACTIVE.getValue())
                .orderByDesc(PictureEditSession::getCreateTime)
                .last("limit 1"));
        if (session == null) {
            return null;
        }
        if (session.getExpireTime() != null && session.getExpireTime().before(new Date())) {
            PictureEditSession update = new PictureEditSession();
            update.setId(session.getId());
            update.setStatus(EditSessionStatusEnum.EXPIRED.getValue());
            sessionMapper.updateById(update);
            return null;
        }
        return session;
    }

    // endregion

    // region 租约

    public JSONObject renewLease(PictureEditSession session, User loginUser) {
        boolean renewed = editLeaseService.renew(session.getPictureId(),
                AgentConstant.EDIT_LOCK_MODE_AGENT, loginUser.getId(), session.getId());
        if (!renewed) {
            // 租约过期或被抢占，尝试重新获取
            JSONObject acquired = editLeaseService.tryAcquire(session.getPictureId(),
                    AgentConstant.EDIT_LOCK_MODE_AGENT, loginUser.getId(), session.getId());
            if (acquired == null) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "编辑租约已被其他编辑动作占用");
            }
            return acquired;
        }
        return editLeaseService.get(session.getPictureId());
    }

    public void releaseLease(Long sessionId, User loginUser) {
        PictureEditSession session = requireSession(sessionId, loginUser, true);
        editLeaseService.release(session.getPictureId(),
                AgentConstant.EDIT_LOCK_MODE_AGENT, loginUser.getId(), sessionId);
    }

    // endregion

    // region 消息与工具

    /**
     * 发送自然语言指令：规划 → 校验 → 单步直跑 / 多步等待确认 / 纯文本回复
     */
    public AgentRunVO sendMessage(Long sessionId, User loginUser, String message) {
        if (StrUtil.isBlank(message)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息不能为空");
        }
        PictureEditSession session = requireSession(sessionId, loginUser, true);
        requireActive(session);
        renewLease(session, loginUser);

        PictureAgentRun run = new PictureAgentRun();
        run.setEditSessionId(sessionId);
        run.setUserId(loginUser.getId());
        run.setRevision(session.getRevision());
        run.setGoal(message.trim());
        run.setStatus(AgentRunStatusEnum.PLANNING.getValue());
        agentRunMapper.insert(run);
        publishRunEvent(run, "progress", null);

        List<com.zys.backend.agent.model.PlanStep> steps;
        try {
            Picture picture = requirePicture(session.getPictureId());
            PlannerOutcome outcome = plannerService.plan(session, picture, run.getGoal());
            steps = planValidator.validate(outcome.getRawSteps(), session);
            PictureAgentRun planUpdate = new PictureAgentRun();
            planUpdate.setId(run.getId());
            planUpdate.setReply(outcome.getReply());
            planUpdate.setPlanJson(steps.isEmpty() ? null : JSONUtil.toJsonStr(steps));
            agentRunMapper.updateById(planUpdate);
            run.setReply(outcome.getReply());
            run.setPlanJson(planUpdate.getPlanJson());
        } catch (BusinessException | AgentToolException e) {
            failRun(run, e.getMessage());
            return snapshotService.build(run);
        } catch (Exception e) {
            log.error("规划失败，runId={}", run.getId(), e);
            failRun(run, "规划失败，请重试");
            return snapshotService.build(run);
        }

        if (steps.isEmpty()) {
            // 无工具调用：纯文本回复即完成
            agentRunMapper.update(null, Wrappers.<PictureAgentRun>lambdaUpdate()
                    .eq(PictureAgentRun::getId, run.getId())
                    .set(PictureAgentRun::getStatus, AgentRunStatusEnum.SUCCEEDED.getValue()));
            run.setStatus(AgentRunStatusEnum.SUCCEEDED.getValue());
            publishRunEvent(run, "finished", null);
            return snapshotService.build(run);
        }
        planExecutor.start(run, steps);
        // 重新读取最新状态返回
        PictureAgentRun latest = agentRunMapper.selectById(run.getId());
        return snapshotService.build(latest == null ? run : latest);
    }

    /**
     * 界面直调工具：等价于单步计划
     */
    public AgentRunVO submitTool(Long sessionId, User loginUser, String tool, java.util.Map<String, Object> params) {
        PictureEditSession session = requireSession(sessionId, loginUser, true);
        requireActive(session);
        renewLease(session, loginUser);
        ToolSpec spec = toolRegistry.specOf(tool);
        java.util.Map<String, Object> normalized = toolRegistry.validateParams(tool, params);

        com.zys.backend.agent.model.PlanStep step = new com.zys.backend.agent.model.PlanStep();
        step.setId("s1");
        step.setTool(spec.getName());
        step.setParams(normalized);
        step.setNeedsApproval(spec.isNeedsApproval());
        step.setStatus(ToolRunStatusEnum.PENDING.getValue());
        List<com.zys.backend.agent.model.PlanStep> steps = new ArrayList<>();
        steps.add(step);

        PictureAgentRun run = new PictureAgentRun();
        run.setEditSessionId(sessionId);
        run.setUserId(loginUser.getId());
        run.setRevision(session.getRevision());
        run.setGoal("直接调用：" + spec.labelFor(normalized));
        run.setStatus(AgentRunStatusEnum.RUNNING.getValue());
        run.setPlanJson(JSONUtil.toJsonStr(steps));
        agentRunMapper.insert(run);
        planExecutor.start(run, steps);
        PictureAgentRun latest = agentRunMapper.selectById(run.getId());
        return snapshotService.build(latest == null ? run : latest);
    }

    private void failRun(PictureAgentRun run, String message) {
        agentRunMapper.update(null, Wrappers.<PictureAgentRun>lambdaUpdate()
                .eq(PictureAgentRun::getId, run.getId())
                .set(PictureAgentRun::getStatus, AgentRunStatusEnum.FAILED.getValue())
                .set(PictureAgentRun::getErrorMessage, message));
        run.setStatus(AgentRunStatusEnum.FAILED.getValue());
        run.setErrorMessage(message);
        publishRunEvent(run, "finished", null);
    }

    private void publishRunEvent(PictureAgentRun run, String event, java.util.Map<String, Object> result) {
        AgentRunEventVO payload = new AgentRunEventVO();
        payload.setEvent(event);
        payload.setStatus(run.getStatus());
        payload.setResult(result);
        payload.setErrorMessage(run.getErrorMessage());
        eventPublisher.publish(run.getId(), payload);
    }

    // endregion

    // region 画布操作

    /**
     * 采用资产墙某张图为当前画布（切换候选图）
     */
    public AgentSessionVO adoptAsset(Long sessionId, User loginUser, Long assetId) {
        PictureEditSession session = requireSession(sessionId, loginUser, true);
        requireActive(session);
        PictureAgentAsset asset = assetService.getInSession(sessionId, assetId);
        if (AgentAssetKindEnum.ORIGINAL.getValue().equals(asset.getKind())) {
            // 原图也可作为画布回退
        }
        JSONObject document = documentService.adoptIntoDocument(documentService.parseDocument(session), asset);
        JSONObject params = new JSONObject().set("assetId", String.valueOf(assetId));
        documentService.applyEdit(session, "adopt_asset", params.toString(), document, asset, null);
        return getSnapshot(sessionId, loginUser);
    }

    /**
     * 撤销
     */
    public AgentSessionVO undo(Long sessionId, User loginUser) {
        PictureEditSession session = requireSession(sessionId, loginUser, true);
        requireActive(session);
        if (!documentService.undo(session)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "没有可撤销的操作");
        }
        return getSnapshot(sessionId, loginUser);
    }

    /**
     * 重做
     */
    public AgentSessionVO redo(Long sessionId, User loginUser) {
        PictureEditSession session = requireSession(sessionId, loginUser, true);
        requireActive(session);
        if (!documentService.redo(session)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "没有可重做的操作");
        }
        return getSnapshot(sessionId, loginUser);
    }

    /**
     * 选定最终草稿
     */
    public AgentSessionVO setFinalAsset(Long sessionId, User loginUser, Long assetId) {
        PictureEditSession session = requireSession(sessionId, loginUser, true);
        requireActive(session);
        PictureAgentAsset asset = assetService.getInSession(sessionId, assetId);
        PictureEditSession update = new PictureEditSession();
        update.setId(sessionId);
        update.setFinalAssetId(asset.getId());
        update.setEditTime(new Date());
        sessionMapper.updateById(update);
        return getSnapshot(sessionId, loginUser);
    }

    private PictureEditSession reload(Long sessionId) {
        PictureEditSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
        }
        return session;
    }

    // endregion

    // region 快照

    /**
     * 会话快照：刷新后恢复会话、计划、任务状态、图片墙
     */
    public AgentSessionVO getSnapshot(Long sessionId, User loginUser) {
        PictureEditSession session = requireSession(sessionId, loginUser, false);
        Picture picture = requirePicture(session.getPictureId());
        authService.requireView(loginUser, picture);

        AgentSessionVO vo = new AgentSessionVO();
        vo.setId(session.getId());
        vo.setPictureId(session.getPictureId());
        vo.setSpaceId(session.getSpaceId());
        vo.setUserId(session.getUserId());
        vo.setBaseEditVersion(session.getBaseEditVersion());
        vo.setStatus(session.getStatus());
        vo.setRevision(session.getRevision());
        vo.setHistorySeq(session.getHistorySeq());
        vo.setFinalAssetId(session.getFinalAssetId());
        vo.setCurrentAssetId(session.getCurrentAssetId());
        vo.setCommittedVersionId(session.getCommittedVersionId());
        vo.setExpireTime(session.getExpireTime());
        JSONObject document = documentService.parseDocument(session);
        vo.setDocument(document == null ? null : JSONUtil.toBean(document.toString(), java.util.Map.class));
        vo.setAssets(assetService.listVOBySession(sessionId, null));
        vo.setRuns(recentRuns(sessionId));

        List<AgentRunVO> runs = vo.getRuns();
        if (runs == null || runs.isEmpty()) {
            vo.setRuns(new ArrayList<>());
        }
        vo.setSelection(selectionSnapshot(session));
        vo.setLease(editLeaseService.get(session.getPictureId()));
        vo.setTools(toolRegistry.all().stream().map(AgentToolVO::from).collect(Collectors.toList()));
        vo.setCanUndo(documentService.canUndo(session));
        vo.setCanRedo(documentService.canRedo(session));
        return vo;
    }

    private java.util.Map<String, Object> selectionSnapshot(PictureEditSession session) {
        JSONObject selection = selectionService.getValidSelection(session);
        return selection == null ? null : JSONUtil.toBean(selection.toString(), java.util.Map.class);
    }

    private List<AgentRunVO> recentRuns(Long sessionId) {
        List<PictureAgentRun> runs = agentRunMapper.selectList(Wrappers.<PictureAgentRun>lambdaQuery()
                .eq(PictureAgentRun::getEditSessionId, sessionId)
                .orderByDesc(PictureAgentRun::getCreateTime)
                .last("limit " + RECENT_RUNS));
        List<AgentRunVO> result = new ArrayList<>();
        for (int i = runs.size() - 1; i >= 0; i--) {
            result.add(snapshotService.build(runs.get(i)));
        }
        return result;
    }

    // endregion

    // region 校验辅助

    public Picture requirePicture(Long pictureId) {
        Picture picture = pictureMapper.selectById(pictureId);
        if (picture == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        }
        return picture;
    }

    public PictureEditSession requireSession(Long sessionId, User loginUser, boolean needEdit) {
        PictureEditSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
        }
        Picture picture = requirePicture(session.getPictureId());
        if (needEdit) {
            authService.requireEdit(loginUser, picture);
            boolean owner = loginUser.getId().equals(session.getUserId());
            if (!owner && !userService.isAdmin(loginUser)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有会话创建者可以执行编辑操作");
            }
        } else {
            authService.requireView(loginUser, picture);
        }
        return session;
    }

    private void requireActive(PictureEditSession session) {
        if (EditSessionStatusEnum.isTerminal(session.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "会话已结束（" + session.getStatus() + "），请重新创建会话");
        }
        if (session.getExpireTime() != null && session.getExpireTime().before(new Date())) {
            PictureEditSession update = new PictureEditSession();
            update.setId(session.getId());
            update.setStatus(EditSessionStatusEnum.EXPIRED.getValue());
            sessionMapper.updateById(update);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "会话已过期，请重新创建会话");
        }
    }

    // endregion

    // region 选区

    /**
     * prepare：上传 mask 生成资产；随后 POST /selection 才生效
     */
    public PictureAgentAsset prepareSelectionMask(Long sessionId, User loginUser, byte[] maskBytes, String format) {
        PictureEditSession session = requireSession(sessionId, loginUser, true);
        requireActive(session);
        return selectionService.prepareMask(session, maskBytes, format);
    }

    public void saveSelection(Long sessionId, User loginUser, Long maskAssetId,
                              List<java.util.Map<String, Object>> markers, Integer clientRevision) {
        PictureEditSession session = requireSession(sessionId, loginUser, true);
        requireActive(session);
        if (clientRevision != null && !clientRevision.equals(session.getRevision())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "画布已更新，选区已失效，请重新选择");
        }
        if (maskAssetId != null) {
            assetService.getInSession(sessionId, maskAssetId);
        }
        if (maskAssetId == null && (markers == null || markers.isEmpty())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "选区至少需要 mask 或标记之一");
        }
        selectionService.put(session, maskAssetId, markers);
    }

    public JSONObject getSelection(Long sessionId, User loginUser) {
        PictureEditSession session = requireSession(sessionId, loginUser, false);
        return selectionService.getValidSelection(session);
    }

    public void clearSelection(Long sessionId, User loginUser) {
        PictureEditSession session = requireSession(sessionId, loginUser, true);
        selectionService.clear(session.getId());
    }

    // endregion

    /**
     * 供其他服务使用：读取未鉴权会话（内部调用，权限已由上层校验）
     */
    public PictureEditSession loadSession(Long sessionId) {
        return reload(sessionId);
    }

    /**
     * 工具描述列表（前端工具面板）
     */
    public List<AgentToolVO> toolInfos() {
        return toolRegistry.all().stream().map(AgentToolVO::from).collect(Collectors.toList());
    }

    /**
     * 兼容 JSONArray 输出的文档（预留）
     */
    JSONArray documentLayers(PictureEditSession session) {
        JSONObject document = documentService.parseDocument(session);
        return document == null ? new JSONArray() : documentService.layers(document);
    }
}
