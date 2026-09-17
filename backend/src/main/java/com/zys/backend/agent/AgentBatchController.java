package com.zys.backend.agent;

import com.zys.backend.agent.dto.AgentApiRequests;
import com.zys.backend.agent.vo.AgentBatchVO;
import com.zys.backend.agent.vo.AgentExportVO;
import com.zys.backend.common.BaseResponse;
import com.zys.backend.common.ResultUtils;
import com.zys.backend.model.entity.User;
import com.zys.backend.service.UserService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * Agent 智能精修：批量任务与导出对外接口
 */
@RestController
public class AgentBatchController {

    @Resource
    private AgentBatchService batchService;

    @Resource
    private AgentExportService exportService;

    @Resource
    private AgentRunEventService runEventService;

    @Resource
    private UserService userService;

    /**
     * 创建批量任务（最多 20 张，逐张校验权限）
     */
    @PostMapping("/agent-batches")
    public BaseResponse<AgentBatchVO> createBatch(
            @RequestBody AgentApiRequests.BatchCreateRequest request,
            HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        return ResultUtils.success(batchService.createBatch(request, loginUser));
    }

    /**
     * 查询批量任务
     */
    @GetMapping("/agent-batches/{batchId}")
    public BaseResponse<AgentBatchVO> getBatch(
            @PathVariable("batchId") String batchId,
            HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        return ResultUtils.success(batchService.getBatch(batchId, loginUser));
    }

    /**
     * 订阅批量任务实时事件（SSE）
     */
    @GetMapping(value = "/agent-batches/{batchId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeBatchEvents(
            @PathVariable("batchId") String batchId,
            HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        batchService.loadMapping(batchId, loginUser);
        return runEventService.subscribeAgentRun(batchId,
                AgentCallContext.builder().userId(loginUser.getId()).build());
    }

    /**
     * 逐项确认：成功项替换原图，冲突/失败项单独上报
     */
    @PostMapping("/agent-batches/{batchId}/confirm")
    public BaseResponse<List<AgentBatchVO.AgentBatchItemVO>> confirmBatch(
            @PathVariable("batchId") String batchId,
            HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        return ResultUtils.success(batchService.confirmBatch(batchId, loginUser));
    }

    /**
     * 取消批量任务（不中断已完成结果，仅禁止确认）
     */
    @PostMapping("/agent-batches/{batchId}/cancel")
    public BaseResponse<Boolean> cancelBatch(
            @PathVariable("batchId") String batchId,
            HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        return ResultUtils.success(batchService.cancelBatch(batchId, loginUser));
    }

    /**
     * 创建导出凭证（会话或批量）
     */
    @PostMapping("/agent-exports")
    public BaseResponse<AgentExportVO> createExport(
            @RequestBody AgentApiRequests.ExportCreateRequest request,
            HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        return ResultUtils.success(exportService.createExport(request, loginUser));
    }

    /**
     * 下载导出 ZIP
     */
    @GetMapping("/agent-exports/{exportId}/download")
    public ResponseEntity<byte[]> downloadExport(
            @PathVariable("exportId") String exportId,
            HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        AgentExportService.ExportPayload payload = exportService.download(exportId, loginUser);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/zip")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + payload.getFilename())
                .body(payload.getData());
    }
}
