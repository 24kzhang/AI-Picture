package com.zys.backend.controller;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.region.Region;
import com.zys.backend.common.BaseResponse;
import com.zys.backend.common.ResultUtils;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.manager.CosStorageManager;
import com.zys.backend.manager.auth.SpaceUserAuthManager;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.Space;
import com.zys.backend.model.entity.User;
import com.zys.backend.service.PictureService;
import com.zys.backend.service.SpaceService;
import com.zys.backend.service.SystemSettingsService;
import com.zys.backend.service.UserService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;

/** 内网视频服务桥接。密钥与浏览器会话同时校验，COS 密钥不离开 Java。 */
@RestController
@RequestMapping("/internal/video")
public class VideoBridgeController {
    @Resource private UserService userService;
    @Resource private SpaceService spaceService;
    @Resource private PictureService pictureService;
    @Resource private SpaceUserAuthManager authManager;
    @Resource private SystemSettingsService settings;
    @Resource private CosStorageManager storage;
    @Value("${gallery.video.service-key-file:../runtime-data/video-service.key}")
    private String keyFile;

    @Data
    public static class Scope {
        private Long spaceId;
        private Long ownerId;
        private String action = "view";
        private String key;
        private String method;
        private Long targetId;
    }

    private User require(Scope scope, HttpServletRequest request) {
        String expected = System.getenv("VIDEO_SERVICE_KEY");
        if (expected == null || expected.isEmpty()) {
            try { expected = new String(Files.readAllBytes(Paths.get(keyFile)), StandardCharsets.UTF_8).trim(); }
            catch (Exception e) { throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "视频服务尚未配置"); }
        }
        String supplied = request.getHeader("X-Video-Service-Key");
        if (expected.length() < 32 || supplied == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无效服务身份");
        }
        User user = userService.getLoginUser(request);
        if (!Arrays.asList("view", "edit", "upload", "delete", "manage").contains(scope.getAction()))
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "无效操作");
        if (userService.isAdmin(user)) return user;
        if (scope.getSpaceId() == null) {
            if (!"view".equals(scope.getAction()) && scope.getOwnerId() != null && !user.getId().equals(scope.getOwnerId()))
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有创建者可以修改公开视频");
        } else {
            Space space = spaceService.getById(scope.getSpaceId());
            if (space == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "空间不存在");
            String permission = "manage".equals(scope.getAction()) ? "spaceUser:manage" : "picture:" + scope.getAction();
            if (!authManager.getPermissionList(space, user).contains(permission))
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "没有空间操作权限");
        }
        return user;
    }

    @PostMapping("/authorize")
    public BaseResponse<Map<String, Object>> authorize(@RequestBody Scope scope, HttpServletRequest request) {
        User user = require(scope, request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", user.getId().toString());
        result.put("userName", user.getUserName());
        result.put("admin", userService.isAdmin(user));
        result.put("scope", scope.getSpaceId() == null ? "public-" + (scope.getOwnerId() == null ? user.getId() : scope.getOwnerId()) : "space-" + scope.getSpaceId());
        return ResultUtils.success(result);
    }

    @PostMapping("/storage-ticket")
    public BaseResponse<Map<String, String>> ticket(@RequestBody Scope scope, HttpServletRequest request) {
        String method = scope.getMethod();
        if (!Arrays.asList("GET", "PUT", "DELETE").contains(method))
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "无效存储操作");
        scope.setAction("GET".equals(method) ? "view" : "DELETE".equals(method) ? "delete" : "upload");
        User user = require(scope, request);
        String prefix = "videos/" + (scope.getSpaceId() == null ? "public-" + (scope.getOwnerId() == null ? user.getId() : scope.getOwnerId()) : "space-" + scope.getSpaceId()) + "/";
        String key = scope.getKey();
        if (key == null || !key.startsWith(prefix) || !key.matches("videos/(space|public)-[0-9]+/[a-zA-Z0-9/-]+\\.[a-zA-Z0-9]+") || key.contains(".."))
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "非法对象路径");
        ClientConfig config = new ClientConfig(new Region(settings.getValue("cos.region")));
        config.setHttpProtocol(HttpProtocol.https);
        COSClient client = new COSClient(new BasicCOSCredentials(settings.getValue("cos.secretId"), settings.getValue("cos.secretKey")), config);
        try {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("url", client.generatePresignedUrl(settings.getValue("cos.bucket"), key,
                    new Date(System.currentTimeMillis() + 300_000), HttpMethodName.valueOf(method)).toString());
            result.put("key", key);
            return ResultUtils.success(result);
        } finally { client.shutdown(); }
    }

    @PostMapping("/handoff-target")
    public BaseResponse<Boolean> handoffTarget(@RequestBody Scope scope, HttpServletRequest request) {
        scope.setAction("edit"); require(scope, request);
        User target = scope.getTargetId() == null ? null : userService.getById(scope.getTargetId());
        if (target == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "接收成员不存在");
        if (scope.getSpaceId() == null) {
            if (!target.getId().equals(scope.getOwnerId()) && !userService.isAdmin(target))
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "接收成员不能编辑此项目");
        } else {
            Space space = spaceService.getById(scope.getSpaceId());
            if (space == null || !authManager.getPermissionList(space, target).contains("picture:edit"))
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "接收成员没有空间编辑权限");
        }
        return ResultUtils.success(true);
    }

    @GetMapping("/pictures/{id}")
    public void picture(@PathVariable Long id, HttpServletRequest request, HttpServletResponse response) throws Exception {
        Picture picture = pictureService.getById(id);
        if (picture == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        Scope scope = new Scope(); scope.setSpaceId(picture.getSpaceId()); scope.setOwnerId(picture.getUserId());
        User user = require(scope, request);
        if (picture.getSpaceId() == null && !Objects.equals(picture.getReviewStatus(), 1)
                && !user.getId().equals(picture.getUserId()) && !userService.isAdmin(user))
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "图片未通过审核");
        try (CosStorageManager.ManagedImageFile image = storage.materialize(picture.getUrl())) {
            response.setContentType("application/octet-stream");
            Files.copy(image.getPath(), response.getOutputStream());
        }
    }
}
