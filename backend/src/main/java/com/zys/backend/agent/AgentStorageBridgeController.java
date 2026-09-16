package com.zys.backend.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.zys.backend.common.BaseResponse;
import com.zys.backend.common.ResultUtils;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.manager.CosStorageManager;
import com.zys.backend.model.entity.User;
import com.zys.backend.service.UserService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 修图 Agent 内部存储桥接接口。
 * Agent 不持有 COS 密钥，所有对象读写经本控制器签发的受限 URL 完成；
 * 请求携带 Agent→Spring 方向的 HMAC 服务令牌（X-Gallery-Token）。
 */
@Slf4j
@RestController
public class AgentStorageBridgeController {

    private static final String TOKEN_HEADER = "X-Gallery-Token";
    private static final String TEMP_KEY_PREFIX = "agent-temp/";
    private static final long MAX_UPLOAD_BYTES = 20L * 1024 * 1024;
    private static final int UPLOAD_URL_TTL_SECONDS = 600;
    private static final int DOWNLOAD_URL_TTL_SECONDS = 300;

    @Resource
    private CosStorageManager cosStorageManager;

    @Resource
    private UserService userService;

    @Value("${gallery.retouch.token-secret:}")
    private String tokenSecret;

    /**
     * 桥接健康检查（无需令牌）
     */
    @GetMapping("/agent-internal/health")
    public BaseResponse<Map<String, String>> health() {
        Map<String, String> status = new HashMap<>();
        status.put("status", "ok");
        return ResultUtils.success(status);
    }

    /**
     * 申请预签名上传 URL
     */
    @PostMapping("/agent-internal/storage/upload-request")
    public BaseResponse<Map<String, Object>> requestUpload(
            @RequestBody UploadRequest request,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        requireBridgeToken(token);
        if (request == null || request.getObjectKey() == null || request.getContentType() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数不完整");
        }
        String key = cosStorageManager.validateKeyPrefix(request.getObjectKey(), TEMP_KEY_PREFIX);
        if (!request.getContentType().startsWith("image/")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "仅允许图片类型");
        }
        if (request.getSizeBytes() != null
                && (request.getSizeBytes() <= 0 || request.getSizeBytes() > MAX_UPLOAD_BYTES)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片大小超出限制");
        }
        String url = cosStorageManager.presignedPutUrl(key, request.getContentType(), UPLOAD_URL_TTL_SECONDS);
        Map<String, Object> granted = new HashMap<>();
        granted.put("url", url);
        granted.put("object_key", key);
        return ResultUtils.success(granted);
    }

    /**
     * 申请预签名下载 URL
     */
    @PostMapping("/agent-internal/storage/download-request")
    public BaseResponse<Map<String, Object>> requestDownload(
            @RequestBody DownloadRequest request,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        requireBridgeToken(token);
        if (request == null || request.getObjectKey() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数不完整");
        }
        String key = request.getObjectKey();
        if (!key.startsWith(TEMP_KEY_PREFIX) && !key.startsWith("pictures/")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "COS 对象键前缀不合法");
        }
        if (key.contains("..")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "COS 对象键不合法");
        }
        String url = cosStorageManager.presignedGetUrl(key, DOWNLOAD_URL_TTL_SECONDS);
        Map<String, Object> granted = new HashMap<>();
        granted.put("url", url);
        return ResultUtils.success(granted);
    }

    /**
     * 登记上传完成的临时素材：校验前缀、存在性与大小
     */
    @PostMapping("/agent-internal/storage/register")
    public BaseResponse<Map<String, Object>> registerUpload(
            @RequestBody RegisterRequest request,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        requireBridgeToken(token);
        if (request == null || request.getObjectKey() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数不完整");
        }
        String key = cosStorageManager.validateKeyPrefix(request.getObjectKey(), TEMP_KEY_PREFIX);
        com.qcloud.cos.model.ObjectMetadata metadata = cosStorageManager.statObject(key);
        if (metadata == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "对象尚未上传");
        }
        if (request.getSizeBytes() != null && metadata.getContentLength() != request.getSizeBytes()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "对象大小与登记值不符");
        }
        log.info("登记 Agent 临时素材：key={}，size={}，sha256={}",
                key, metadata.getContentLength(), request.getSha256());
        Map<String, Object> result = new HashMap<>();
        result.put("status", "registered");
        return ResultUtils.success(result);
    }

    /**
     * 登记临时资源清理（立即删除）
     */
    @PostMapping("/agent-internal/storage/cleanup")
    public BaseResponse<Map<String, Object>> cleanup(
            @RequestBody CleanupRequest request,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        requireBridgeToken(token);
        int deleted = 0;
        if (request != null && request.getObjectKeys() != null) {
            for (String key : request.getObjectKeys()) {
                try {
                    cosStorageManager.deleteKeyQuietly(
                            cosStorageManager.validateKeyPrefix(key, TEMP_KEY_PREFIX));
                    deleted++;
                } catch (BusinessException e) {
                    log.warn("跳过非法清理键：{}", key);
                }
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("status", "scheduled");
        result.put("deleted", deleted);
        return ResultUtils.success(result);
    }

    /**
     * 素材访问入口：浏览器携带素材访问令牌，经登录校验后重定向到 COS 预签名 URL
     */
    @GetMapping("/agent-asset/{token}")
    public RedirectView viewAsset(@PathVariable("token") String token, HttpServletRequest request) {
        // 要求登录；令牌本身即短期访问凭证
        userService.getLoginUser(request);
        String objectKey = AgentServiceTokenVerifier.readAssetToken(token, tokenSecret);
        if (objectKey == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "素材链接无效或已过期");
        }
        if (!objectKey.startsWith(TEMP_KEY_PREFIX) && !objectKey.startsWith("pictures/")) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "素材链接无效");
        }
        String url = cosStorageManager.presignedGetUrl(objectKey, DOWNLOAD_URL_TTL_SECONDS);
        return new RedirectView(url);
    }

    private void requireBridgeToken(String token) {
        JsonNode payload = AgentServiceTokenVerifier.verifyServiceToken(token, tokenSecret);
        if (payload == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "桥接令牌无效或已过期");
        }
    }

    @Data
    public static class UploadRequest {

        private String objectKey;

        private String contentType;

        private Long sizeBytes;
    }

    @Data
    public static class DownloadRequest {

        private String objectKey;
    }

    @Data
    public static class RegisterRequest {

        private String objectKey;

        private String sha256;

        private String contentType;

        private Long sizeBytes;
    }

    @Data
    public static class CleanupRequest {

        private java.util.List<String> objectKeys;
    }
}
