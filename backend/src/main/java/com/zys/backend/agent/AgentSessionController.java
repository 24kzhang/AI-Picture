package com.zys.backend.agent;

import com.zys.backend.agent.dto.AgentApiRequests;
import com.zys.backend.agent.dto.AgentSessionDetailDTO;
import com.zys.backend.agent.vo.AgentSessionVO;
import com.zys.backend.agent.vo.AgentTurnVO;
import com.zys.backend.common.BaseResponse;
import com.zys.backend.common.ResultUtils;
import com.zys.backend.model.entity.User;
import com.zys.backend.service.UserService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Agent 智能精修：编辑会话对外接口
 */
@RestController
public class AgentSessionController {

    @Resource
    private AgentEditSessionService editSessionService;

    @Resource
    private UserService userService;

    /**
     * 创建（或复用）编辑会话，支持 Idempotency-Key 幂等
     */
    @PostMapping("/picture/{pictureId}/agent-sessions")
    public BaseResponse<AgentSessionVO> createSession(
            @PathVariable("pictureId") long pictureId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        AgentSessionVO vo = editSessionService.createSession(pictureId, idempotencyKey, loginUser);
        return ResultUtils.success(vo);
    }

    /**
     * 查询会话详情
     */
    @GetMapping("/agent-sessions/{sessionId}")
    public BaseResponse<AgentSessionVO> getSession(
            @PathVariable("sessionId") long sessionId,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(editSessionService.getSession(sessionId, loginUser));
    }

    /**
     * 取消会话
     */
    @PostMapping("/agent-sessions/{sessionId}/cancel")
    public BaseResponse<Boolean> cancelSession(
            @PathVariable("sessionId") long sessionId,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(editSessionService.cancelSession(sessionId, loginUser));
    }

    /**
     * 发送对话消息
     */
    @PostMapping("/agent-sessions/{sessionId}/messages")
    public BaseResponse<AgentTurnVO> sendMessage(
            @PathVariable("sessionId") long sessionId,
            @RequestBody AgentApiRequests.MessageRequest messageRequest,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        messageRequest.validate();
        return ResultUtils.success(
                editSessionService.sendMessage(sessionId, messageRequest.getText(), loginUser));
    }

    /**
     * 调用单步工具
     */
    @PostMapping("/agent-sessions/{sessionId}/tools")
    public BaseResponse<AgentTurnVO> invokeTool(
            @PathVariable("sessionId") long sessionId,
            @RequestBody AgentApiRequests.ToolRequest toolRequest,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        toolRequest.validate();
        return ResultUtils.success(
                editSessionService.invokeTool(sessionId, toolRequest.getTool(), toolRequest.getParams(), loginUser));
    }

    /**
     * 预热选区模型
     */
    @PostMapping("/agent-sessions/{sessionId}/selection/prepare")
    public BaseResponse<Boolean> prepareSelection(
            @PathVariable("sessionId") long sessionId,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(editSessionService.prepareSelection(sessionId, loginUser));
    }

    /**
     * 设置选区
     */
    @PostMapping("/agent-sessions/{sessionId}/selection")
    public BaseResponse<Map<String, Object>> setSelection(
            @PathVariable("sessionId") long sessionId,
            @RequestBody AgentApiRequests.SelectionRequest selectionRequest,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(
                editSessionService.setSelection(sessionId, selectionRequest, loginUser));
    }

    /**
     * 查询选区
     */
    @GetMapping("/agent-sessions/{sessionId}/selection")
    public BaseResponse<Map<String, Object>> getSelection(
            @PathVariable("sessionId") long sessionId,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(editSessionService.getSelection(sessionId, loginUser));
    }

    /**
     * 清除选区
     */
    @DeleteMapping("/agent-sessions/{sessionId}/selection")
    public BaseResponse<Boolean> deleteSelection(
            @PathVariable("sessionId") long sessionId,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(editSessionService.deleteSelection(sessionId, loginUser));
    }

    /**
     * 撤销
     */
    @PostMapping("/agent-sessions/{sessionId}/undo")
    public BaseResponse<AgentSessionDetailDTO> undo(
            @PathVariable("sessionId") long sessionId,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(editSessionService.undo(sessionId, loginUser));
    }

    /**
     * 重做
     */
    @PostMapping("/agent-sessions/{sessionId}/redo")
    public BaseResponse<AgentSessionDetailDTO> redo(
            @PathVariable("sessionId") long sessionId,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(editSessionService.redo(sessionId, loginUser));
    }
}
