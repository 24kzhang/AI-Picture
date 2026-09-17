package com.zys.backend.agent;

import cn.hutool.json.JSONUtil;
import com.zys.backend.agent.dto.AgentApiRequests;
import com.zys.backend.agent.vo.AgentExportVO;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.exception.ThrowUtils;
import com.zys.backend.mapper.PictureEditSessionMapper;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.entity.User;
import com.zys.backend.service.UserService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Agent 导出：会话产物与批量产物 ZIP 导出（两步式：创建凭证 → 下载）。
 * 下载时校验登录、归属与凭证有效期，实际 ZIP 由 Agent 生成后经云图库转发。
 */
@Slf4j
@Service
public class AgentExportService {

    private static final String KEY_PREFIX = "agent:export:";
    private static final long TTL_MINUTES = 30;
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Resource
    private RetouchAgentClient agentClient;

    @Resource
    private UserService userService;

    @Resource
    private PictureEditSessionMapper editSessionMapper;

    @Resource
    private AgentBatchService batchService;

    @Resource
    private AgentMetrics agentMetrics;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${gallery.retouch.public-base-url:http://localhost:8080}")
    private String publicBaseUrl;

    /**
     * 创建导出凭证
     */
    public AgentExportVO createExport(AgentApiRequests.ExportCreateRequest request, User loginUser) {
        ThrowUtils.throwIf(!agentClient.isEnabled(), ErrorCode.OPERATION_ERROR, "Agent 智能精修未开启");
        request.validate();
        ExportTicket ticket = new ExportTicket();
        ticket.setUserId(loginUser.getId());
        ticket.setAssetIds(request.getAssetIds() == null ? new ArrayList<>() : request.getAssetIds());
        if (request.getSessionId() != null) {
            PictureEditSession session = editSessionMapper.selectById(request.getSessionId());
            ThrowUtils.throwIf(session == null, ErrorCode.NOT_FOUND_ERROR, "编辑会话不存在");
            if (!session.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "仅会话所有者可导出");
            }
            ticket.setType("SESSION");
            ticket.setTargetId(session.getAgentSessionId());
        } else {
            // 归属校验在 loadMapping 内完成
            batchService.loadMapping(request.getBatchId(), loginUser);
            ticket.setType("BATCH");
            ticket.setTargetId(request.getBatchId());
        }
        String exportId = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue().set(KEY_PREFIX + exportId,
                JSONUtil.toJsonStr(ticket), TTL_MINUTES, TimeUnit.MINUTES);

        AgentExportVO vo = new AgentExportVO();
        vo.setExportId(exportId);
        vo.setFilename("agent-export-" + LocalDateTime.now().format(FILE_STAMP) + ".zip");
        vo.setDownloadUrl(publicBaseUrl + "/api/agent-exports/" + exportId + "/download");
        return vo;
    }

    /**
     * 下载导出 ZIP（校验登录与归属）
     */
    public ExportPayload download(String exportId, User loginUser) {
        ThrowUtils.throwIf(exportId == null || exportId.isBlank(), ErrorCode.PARAMS_ERROR, "缺少导出 ID");
        String json = stringRedisTemplate.opsForValue().get(KEY_PREFIX + exportId);
        ThrowUtils.throwIf(json == null, ErrorCode.NOT_FOUND_ERROR, "导出链接无效或已过期");
        ExportTicket ticket = JSONUtil.toBean(json, ExportTicket.class);
        if (!ticket.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权下载该导出文件");
        }
        AgentCallContext context = AgentCallContext.builder().userId(loginUser.getId()).build();
        byte[] data;
        if ("SESSION".equals(ticket.getType())) {
            data = agentClient.exportSession(ticket.getTargetId(),
                    java.util.Map.of("asset_ids", ticket.getAssetIds()), context);
        } else {
            data = agentClient.exportBatch(ticket.getTargetId(), context);
        }
        ThrowUtils.throwIf(data == null || data.length == 0,
                ErrorCode.OPERATION_ERROR, "导出内容为空，请确认已有可用产物");
        agentMetrics.incrementExport();
        ExportPayload payload = new ExportPayload();
        payload.setData(data);
        payload.setFilename("agent-export-" + LocalDateTime.now().format(FILE_STAMP) + ".zip");
        return payload;
    }

    /**
     * 导出凭证（Redis）
     */
    @Data
    public static class ExportTicket {

        private Long userId;

        /**
         * SESSION 或 BATCH
         */
        private String type;

        /**
         * Agent 会话 id 或批量运行 id
         */
        private String targetId;

        private List<String> assetIds = new ArrayList<>();
    }

    /**
     * 下载负载
     */
    @Data
    public static class ExportPayload {

        private byte[] data;

        private String filename;
    }
}
