package com.zys.backend.manager.vector;

import cn.hutool.core.collection.CollUtil;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.manager.CosStorageManager;
import com.zys.backend.manager.vector.model.VectorDuplicateResponse;
import com.zys.backend.manager.vector.model.VectorSearchItem;
import com.zys.backend.manager.vector.model.VectorSearchResponse;
import com.zys.backend.model.entity.Picture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Java 后端与百炼 Embedding/Chroma 服务之间的客户端。
 */
@Slf4j
@Component
public class VectorClient {

    @Value("${gallery.vector.enabled:true}")
    private boolean enabled;

    @Value("${gallery.vector.service-url}")
    private String serviceUrl;

    @Value("${gallery.vector.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${gallery.vector.read-timeout-ms:120000}")
    private int readTimeoutMs;

    @Resource
    private CosStorageManager cosStorageManager;

    private RestTemplate restTemplate;

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        restTemplate = new RestTemplate(factory);
        serviceUrl = serviceUrl.replaceAll("/+$", "");
    }

    public void upsert(Picture picture) {
        if (!enabled) {
            return;
        }
        try (CosStorageManager.ManagedImageFile imageFile =
                     cosStorageManager.materialize(picture.getUrl())) {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("image", new FileSystemResource(imageFile.getPath().toFile()));
            body.add("pictureId", String.valueOf(picture.getId()));
            body.add("spaceId", String.valueOf(picture.getSpaceId() == null ? 0L : picture.getSpaceId()));
            postMultipart("/vectors/upsert", body, Map.class);
        }
    }

    public List<VectorSearchItem> search(String imageUrl, List<Long> allowedSpaceIds, int limit) {
        if (!enabled) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "向量检索服务未启用");
        }
        if (imageUrl == null || CollUtil.isEmpty(allowedSpaceIds)) {
            return Collections.emptyList();
        }
        try (CosStorageManager.ManagedImageFile imageFile =
                     cosStorageManager.materialize(imageUrl)) {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("image", new FileSystemResource(imageFile.getPath().toFile()));
            body.add("allowedSpaceIds", allowedSpaceIds.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(",")));
            body.add("limit", String.valueOf(Math.max(limit, 1)));
            VectorSearchResponse response = postMultipart(
                    "/vectors/search",
                    body,
                    VectorSearchResponse.class
            );
            return response == null || response.getItems() == null
                    ? new ArrayList<>()
                    : response.getItems();
        }
    }

    public void delete(long pictureId) {
        if (!enabled) {
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("pictureId", pictureId);
        try {
            restTemplate.postForEntity(serviceUrl + "/vectors/delete", body, Map.class);
        } catch (RestClientException e) {
            log.warn("删除图片向量失败，pictureId={}", pictureId, e);
        }
    }

    public VectorDuplicateResponse findDuplicates(double threshold) {
        if (!enabled) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "向量检索服务未启用");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("threshold", threshold);
        try {
            ResponseEntity<VectorDuplicateResponse> response = restTemplate.postForEntity(
                    serviceUrl + "/vectors/duplicates",
                    body,
                    VectorDuplicateResponse.class
            );
            return response.getBody();
        } catch (RestClientException e) {
            log.error("读取重复图片向量失败", e);
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "无法读取重复图片向量，请检查 vector-service"
            );
        }
    }

    public boolean isAvailable() {
        if (!enabled) {
            return false;
        }
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(serviceUrl + "/health", Map.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException e) {
            return false;
        }
    }

    private <T> T postMultipart(String path, MultiValueMap<String, Object> body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        try {
            ResponseEntity<T> response = restTemplate.postForEntity(
                    serviceUrl + path,
                    new HttpEntity<>(body, headers),
                    responseType
            );
            return response.getBody();
        } catch (RestClientException e) {
            log.error("调用本地向量服务失败：{}", path, e);
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "图片向量服务不可用，请检查百炼密钥和 vector-service"
            );
        }
    }
}
