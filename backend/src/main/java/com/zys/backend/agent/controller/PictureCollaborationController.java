package com.zys.backend.agent.controller;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.zys.backend.agent.model.vo.AgentSessionVO;
import com.zys.backend.agent.service.PictureCollaborationService;
import com.zys.backend.common.BaseResponse;
import com.zys.backend.common.ResultUtils;
import com.zys.backend.service.UserService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
public class PictureCollaborationController {
    @Resource private PictureCollaborationService collaboration;
    @Resource private UserService users;
    @Resource private com.zys.backend.agent.service.PictureAnnotationService annotations;
    @GetMapping("/picture/{pictureId}/collaboration/annotations")
    public BaseResponse<JSONObject> annotations(@PathVariable Long pictureId,HttpServletRequest request) {
        return ResultUtils.success(annotations.view(pictureId,users.getLoginUser(request)));
    }
    @PostMapping("/picture/{pictureId}/collaboration/annotations/lease")
    public BaseResponse<JSONObject> annotationLease(@PathVariable Long pictureId,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        return ResultUtils.success(annotations.lease(pictureId,users.getLoginUser(request),JSONUtil.parseObj(body)));
    }
    @PostMapping("/picture/{pictureId}/collaboration/annotations/operations")
    public BaseResponse<JSONObject> annotationOperations(@PathVariable Long pictureId,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        return ResultUtils.success(annotations.operate(pictureId,users.getLoginUser(request),JSONUtil.parseObj(body)));
    }
    @GetMapping("/picture/{pictureId}/collaboration")
    public BaseResponse<AgentSessionVO> snapshot(@PathVariable Long pictureId,HttpServletRequest request) {
        return ResultUtils.success(collaboration.snapshot(pictureId,users.getLoginUser(request)));
    }
    @PostMapping("/picture/{pictureId}/collaboration/lease")
    public BaseResponse<AgentSessionVO> lease(@PathVariable Long pictureId,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        JSONObject value=JSONUtil.parseObj(body);
        return ResultUtils.success(collaboration.lease(pictureId,users.getLoginUser(request),value.getStr("action"),value,value.getLong("targetId")));
    }
    @PostMapping("/picture/{pictureId}/collaboration/canvas")
    public BaseResponse<AgentSessionVO> canvas(@PathVariable Long pictureId,@RequestPart("image") MultipartFile image,@RequestParam("state") String state,@RequestParam("context") String context,HttpServletRequest request) throws Exception {
        return ResultUtils.success(collaboration.saveCanvas(pictureId,users.getLoginUser(request),JSONUtil.parseObj(context),state,image.getBytes()));
    }
    @GetMapping("/picture/{pictureId}/collaboration/canvas")
    public ResponseEntity<byte[]> canvas(@PathVariable Long pictureId,HttpServletRequest request) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("Cache-Control","no-store").body(collaboration.canvas(pictureId,users.getLoginUser(request)));
    }
    @PostMapping("/picture/{pictureId}/collaboration/manual")
    public BaseResponse<AgentSessionVO> manual(@PathVariable Long pictureId,HttpServletRequest request) {
        return ResultUtils.success(collaboration.manual(pictureId,users.getLoginUser(request)));
    }
    @GetMapping("/picture/{pictureId}/collaboration/assets/{assetId}/content")
    public ResponseEntity<byte[]> asset(@PathVariable Long pictureId,@PathVariable Long assetId,HttpServletRequest request) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("Cache-Control","private, no-store").body(collaboration.assetContent(pictureId,assetId,users.getLoginUser(request)));
    }
}
