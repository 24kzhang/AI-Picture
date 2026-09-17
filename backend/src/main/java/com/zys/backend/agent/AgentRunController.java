package com.zys.backend.agent;

import com.zys.backend.agent.vo.AgentTurnVO;
import com.zys.backend.common.BaseResponse;
import com.zys.backend.common.ResultUtils;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.exception.ThrowUtils;
import com.zys.backend.mapper.PictureEditRunMapper;
import com.zys.backend.mapper.PictureEditSessionMapper;
import com.zys.backend.model.entity.PictureEditRun;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.entity.User;
import com.zys.backend.service.UserService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

/**
 * Agent 智能精修：运行记录与实时事件对外接口
 */
@RestController
@RequestMapping("/agent-runs")
public class AgentRunController {

    @Resource
    private AgentEditSessionService editSessionService;

    @Resource
    private AgentRunEventService runEventService;

    @Resource
    private UserService userService;

    @Resource
    private PictureEditRunMapper editRunMapper;

    @Resource
    private PictureEditSessionMapper editSessionMapper;

    /**
     * 查询运行快照
     */
    @GetMapping("/{runId}")
    public BaseResponse<AgentTurnVO> getRun(
            @PathVariable("runId") long runId,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(editSessionService.getRun(runId, loginUser));
    }

    /**
     * 确认多步计划（幂等：重复确认返回当前状态）
     */
    @PostMapping("/{runId}/confirm")
    public BaseResponse<AgentTurnVO> confirmRun(
            @PathVariable("runId") long runId,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(editSessionService.confirmRun(runId, loginUser));
    }

    /**
     * 取消多步计划
     */
    @PostMapping("/{runId}/cancel")
    public BaseResponse<AgentTurnVO> cancelRun(
            @PathVariable("runId") long runId,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(editSessionService.cancelRun(runId, loginUser));
    }

    /**
     * 重试失败步骤
     */
    @PostMapping("/{runId}/retry")
    public BaseResponse<AgentTurnVO> retryRun(
            @PathVariable("runId") long runId,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(editSessionService.retryRun(runId, loginUser));
    }

    /**
     * 订阅运行实时事件（SSE）
     */
    @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeEvents(
            @PathVariable("runId") long runId,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        PictureEditRun run = editRunMapper.selectById(runId);
        ThrowUtils.throwIf(run == null, ErrorCode.NOT_FOUND_ERROR, "运行记录不存在");
        PictureEditSession session = editSessionMapper.selectById(run.getEditSessionId());
        ThrowUtils.throwIf(session == null, ErrorCode.NOT_FOUND_ERROR, "编辑会话不存在");
        if (!session.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "仅会话所有者可订阅运行事件");
        }
        return runEventService.subscribe(run, session, loginUser);
    }
}
