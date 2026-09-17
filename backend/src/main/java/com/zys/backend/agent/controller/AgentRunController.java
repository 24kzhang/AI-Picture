package com.zys.backend.agent.controller;

import com.zys.backend.agent.model.AgentRunEventVO;
import com.zys.backend.agent.model.vo.AgentRunVO;
import com.zys.backend.agent.service.AgentEventPublisher;
import com.zys.backend.agent.service.AgentRunSnapshotService;
import com.zys.backend.agent.service.AgentSessionService;
import com.zys.backend.agent.service.PlanExecutor;
import com.zys.backend.common.BaseResponse;
import com.zys.backend.common.ResultUtils;
import com.zys.backend.model.entity.PictureAgentRun;
import com.zys.backend.model.entity.User;
import com.zys.backend.service.UserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

/**
 * Agent 运行接口：快照、确认、取消、重试、SSE 事件流。
 */
@Slf4j
@RestController
@Api(tags = "Agent 运行管理")
public class AgentRunController {

    @Resource
    private PlanExecutor planExecutor;

    @Resource
    private AgentRunSnapshotService snapshotService;

    @Resource
    private AgentSessionService agentSessionService;

    @Resource
    private AgentEventPublisher eventPublisher;

    @Resource
    private UserService userService;

    @GetMapping("/agent-runs/{runId}")
    @ApiOperation(value = "获取运行快照")
    public BaseResponse<AgentRunVO> getRun(@PathVariable Long runId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        PictureAgentRun run = requireRunWithViewAuth(runId, loginUser);
        return ResultUtils.success(snapshotService.build(run));
    }

    @PostMapping("/agent-runs/{runId}/confirm")
    @ApiOperation(value = "确认多步计划")
    public BaseResponse<AgentRunVO> confirm(@PathVariable Long runId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        requireRunWithViewAuth(runId, loginUser);
        PictureAgentRun run = planExecutor.confirm(runId, loginUser);
        return ResultUtils.success(snapshotService.build(run));
    }

    @PostMapping("/agent-runs/{runId}/cancel")
    @ApiOperation(value = "取消计划")
    public BaseResponse<AgentRunVO> cancel(@PathVariable Long runId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        requireRunWithViewAuth(runId, loginUser);
        PictureAgentRun run = planExecutor.cancel(runId, loginUser);
        return ResultUtils.success(snapshotService.build(run));
    }

    @PostMapping("/agent-runs/{runId}/retry")
    @ApiOperation(value = "重试失败步骤")
    public BaseResponse<AgentRunVO> retry(@PathVariable Long runId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        requireRunWithViewAuth(runId, loginUser);
        PictureAgentRun run = planExecutor.retry(runId, loginUser);
        return ResultUtils.success(snapshotService.build(run));
    }

    /**
     * SSE 事件流：先返回快照首包，再实时推送进度。
     * 断线后前端应重新拉取快照再重连。
     */
    @GetMapping(value = "/agent-runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiOperation(value = "订阅运行事件（SSE）")
    public SseEmitter events(@PathVariable Long runId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        PictureAgentRun run = requireRunWithViewAuth(runId, loginUser);
        AgentRunVO snapshot = snapshotService.build(run);
        AgentRunEventVO first = new AgentRunEventVO();
        first.setEvent("snapshot");
        first.setRunId(run.getId());
        first.setStatus(run.getStatus());
        first.setPlan(snapshot.getPlan());
        first.setErrorMessage(run.getErrorMessage());
        return eventPublisher.subscribe(runId, first);
    }

    private PictureAgentRun requireRunWithViewAuth(Long runId, User loginUser) {
        PictureAgentRun run = planExecutor.requireRun(runId);
        agentSessionService.requireSession(run.getEditSessionId(), loginUser, false);
        return run;
    }
}
