package com.zys.backend.agent.controller;

import cn.hutool.json.JSONObject;
import com.zys.backend.agent.model.request.AgentFinalAssetRequest;
import com.zys.backend.agent.model.request.AgentMessageRequest;
import com.zys.backend.agent.model.request.AgentSelectionRequest;
import com.zys.backend.agent.model.request.AgentToolRequest;
import com.zys.backend.agent.model.vo.AgentRunVO;
import com.zys.backend.agent.model.vo.AgentSessionVO;
import com.zys.backend.agent.model.vo.AgentToolVO;
import com.zys.backend.agent.service.AgentSessionService;
import com.zys.backend.common.BaseResponse;
import com.zys.backend.common.ResultUtils;
import com.zys.backend.model.entity.PictureAgentAsset;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.entity.User;
import com.zys.backend.service.UserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * Agent 会话接口：会话、消息、工具、选区、撤销重做、租约。
 * 版本提交相关接口见 {@code AgentVersionController}。
 */
@Slf4j
@RestController
@Api(tags = "Agent 会话管理")
public class AgentSessionController {

    @Resource
    private AgentSessionService agentSessionService;

    @Resource
    private UserService userService;

    @PostMapping("/picture/{pictureId}/agent-sessions")
    @ApiOperation(value = "创建或复用 Agent 编辑会话")
    public BaseResponse<AgentSessionVO> createSession(@PathVariable Long pictureId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(agentSessionService.createOrReuse(pictureId, loginUser));
    }

    @GetMapping("/agent-sessions/{sessionId}")
    @ApiOperation(value = "获取会话快照")
    public BaseResponse<AgentSessionVO> getSession(@PathVariable Long sessionId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(agentSessionService.getSnapshot(sessionId, loginUser));
    }

    @PostMapping("/agent-sessions/{sessionId}/messages")
    @ApiOperation(value = "发送自然语言指令")
    public BaseResponse<AgentRunVO> sendMessage(@PathVariable Long sessionId,
                                                @RequestBody AgentMessageRequest messageRequest,
                                                HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(agentSessionService.sendMessage(sessionId, loginUser,
                messageRequest == null ? null : messageRequest.getMessage()));
    }

    @PostMapping("/agent-sessions/{sessionId}/tools")
    @ApiOperation(value = "直接调用工具")
    public BaseResponse<AgentRunVO> submitTool(@PathVariable Long sessionId,
                                               @RequestBody AgentToolRequest toolRequest,
                                               HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(agentSessionService.submitTool(sessionId, loginUser,
                toolRequest.getTool(), toolRequest.getParams()));
    }

    @PostMapping("/agent-sessions/{sessionId}/selection/prepare")
    @ApiOperation(value = "上传选区 mask，生成 mask 资产")
    public BaseResponse<JSONObject> prepareSelection(@PathVariable Long sessionId,
                                                     @RequestPart("mask") MultipartFile maskFile,
                                                     @RequestParam(required = false) String format,
                                                     HttpServletRequest request) throws Exception {
        User loginUser = userService.getLoginUser(request);
        PictureAgentAsset asset = agentSessionService.prepareSelectionMask(sessionId, loginUser,
                maskFile.getBytes(), format);
        PictureEditSession session = agentSessionService.loadSession(sessionId);
        JSONObject result = new JSONObject()
                .set("maskAssetId", String.valueOf(asset.getId()))
                .set("maskUrl", asset.getUrl())
                .set("width", asset.getWidth())
                .set("height", asset.getHeight())
                .set("revision", session.getRevision());
        return ResultUtils.success(result);
    }

    @PostMapping("/agent-sessions/{sessionId}/selection")
    @ApiOperation(value = "保存选区")
    public BaseResponse<Boolean> saveSelection(@PathVariable Long sessionId,
                                               @RequestBody AgentSelectionRequest selectionRequest,
                                               HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        agentSessionService.saveSelection(sessionId, loginUser, selectionRequest.getMaskAssetId(),
                selectionRequest.getMarkers(), selectionRequest.getRevision());
        return ResultUtils.success(true);
    }

    @GetMapping("/agent-sessions/{sessionId}/selection")
    @ApiOperation(value = "查询当前选区")
    public BaseResponse<JSONObject> getSelection(@PathVariable Long sessionId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(agentSessionService.getSelection(sessionId, loginUser));
    }

    @DeleteMapping("/agent-sessions/{sessionId}/selection")
    @ApiOperation(value = "清除选区")
    public BaseResponse<Boolean> clearSelection(@PathVariable Long sessionId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        agentSessionService.clearSelection(sessionId, loginUser);
        return ResultUtils.success(true);
    }

    @PostMapping("/agent-sessions/{sessionId}/undo")
    @ApiOperation(value = "撤销")
    public BaseResponse<AgentSessionVO> undo(@PathVariable Long sessionId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(agentSessionService.undo(sessionId, loginUser));
    }

    @PostMapping("/agent-sessions/{sessionId}/redo")
    @ApiOperation(value = "重做")
    public BaseResponse<AgentSessionVO> redo(@PathVariable Long sessionId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(agentSessionService.redo(sessionId, loginUser));
    }

    @PostMapping("/agent-sessions/{sessionId}/assets/{assetId}/adopt")
    @ApiOperation(value = "采用资产墙图片为当前画布")
    public BaseResponse<AgentSessionVO> adoptAsset(@PathVariable Long sessionId,
                                                   @PathVariable Long assetId,
                                                   HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(agentSessionService.adoptAsset(sessionId, loginUser, assetId));
    }

    @PostMapping("/agent-sessions/{sessionId}/final-asset")
    @ApiOperation(value = "选定最终草稿")
    public BaseResponse<AgentSessionVO> setFinalAsset(@PathVariable Long sessionId,
                                                      @RequestBody AgentFinalAssetRequest finalAssetRequest,
                                                      HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(agentSessionService.setFinalAsset(sessionId, loginUser,
                finalAssetRequest.getAssetId()));
    }

    @PostMapping("/agent-sessions/{sessionId}/lease/renew")
    @ApiOperation(value = "续编辑租约（前端每 20 秒调用）")
    public BaseResponse<JSONObject> renewLease(@PathVariable Long sessionId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        PictureEditSession session = agentSessionService.requireSession(sessionId, loginUser, true);
        return ResultUtils.success(agentSessionService.renewLease(session, loginUser));
    }

    @PostMapping("/agent-sessions/{sessionId}/lease/release")
    @ApiOperation(value = "释放编辑租约")
    public BaseResponse<Boolean> releaseLease(@PathVariable Long sessionId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        agentSessionService.releaseLease(sessionId, loginUser);
        return ResultUtils.success(true);
    }

    @GetMapping("/agent/tools")
    @ApiOperation(value = "工具清单（前端工具面板）")
    public BaseResponse<List<AgentToolVO>> listTools() {
        return ResultUtils.success(agentSessionService.toolInfos());
    }
}
