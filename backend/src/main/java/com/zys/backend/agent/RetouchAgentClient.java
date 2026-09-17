package com.zys.backend.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.zys.backend.agent.dto.AgentAssetDTO;
import com.zys.backend.agent.dto.AgentRequests;
import com.zys.backend.agent.dto.AgentRunDTO;
import com.zys.backend.agent.dto.AgentSessionDetailDTO;
import com.zys.backend.agent.dto.AgentTurnDTO;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 修图 Agent 内部客户端（Spring → FastAPI）。
 * 所有请求携带短时 HMAC 服务令牌；Agent 不可用时抛业务异常，不影响图库主体。
 */
@Slf4j
@Component
public class RetouchAgentClient {

    private static final String TOKEN_HEADER = "X-Gallery-Token";

    @Value("${gallery.retouch.service-url:http://127.0.0.1:7302}")
    private String serviceUrl;

    @Value("${gallery.retouch.token-secret:}")
    private String tokenSecret;

    @Value("${gallery.retouch.enabled:true}")
    private boolean enabled;

    @Value("${gallery.retouch.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${gallery.retouch.read-timeout-ms:60000}")
    private int readTimeoutMs;

    private RestTemplate restTemplate;

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 功能是否启用（AGENT_EDIT_ENABLED）
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Agent 健康检查；不可用返回 false
     */
    public boolean isAvailable() {
        if (!enabled) {
            return false;
        }
        try {
            JsonNode body = restTemplate.getForObject(serviceUrl + "/api/health", JsonNode.class);
            return body != null && "ok".equals(body.path("api").asText());
        } catch (Exception e) {
            log.warn("修图 Agent 健康检查失败：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 上传素材（multipart）
     */
    public AgentAssetDTO uploadAsset(byte[] data, String filename, String contentType, AgentCallContext context) {
        HttpHeaders headers = authHeaders(context);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ByteArrayResource file = new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", file);
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(form, headers);
        try {
            ResponseEntity<AgentAssetDTO> response = restTemplate.postForEntity(
                    serviceUrl + "/api/assets", entity, AgentAssetDTO.class);
            return response.getBody();
        } catch (RestClientResponseException e) {
            throw wrapAgentError(e);
        } catch (ResourceAccessException e) {
            throw unavailable();
        }
    }

    /**
     * 创建编辑会话
     */
    public AgentSessionDetailDTO createSession(String currentAssetId, List<String> wallAssetIds,
                                                String title, AgentCallContext context) {
        AgentRequests.SessionCreate payload = new AgentRequests.SessionCreate(
                currentAssetId,
                wallAssetIds == null ? Collections.emptyList() : wallAssetIds,
                title);
        return post("/api/sessions", payload, AgentSessionDetailDTO.class, context);
    }

    /**
     * 查询会话详情
     */
    public AgentSessionDetailDTO getSession(String agentSessionId, AgentCallContext context) {
        return get("/api/sessions/" + agentSessionId, AgentSessionDetailDTO.class, context);
    }

    /**
     * 调用工具（单步）
     */
    public Map<String, Object> invokeTool(String agentSessionId, String tool, Object params,
                                          AgentCallContext context) {
        AgentRequests.ToolInvoke payload = new AgentRequests.ToolInvoke(tool, params);
        return post("/api/sessions/" + agentSessionId + "/tools", payload, Map.class, context);
    }

    /**
     * 发送对话消息（自然语言，可能返回多步计划）
     */
    public AgentTurnDTO sendMessage(String agentSessionId, String text, AgentCallContext context) {
        AgentRequests.Message payload = new AgentRequests.Message(text);
        return post("/api/sessions/" + agentSessionId + "/messages", payload, AgentTurnDTO.class, context);
    }

    /**
     * 查询会话全部对话轮次（泛型安全：避免 List.class 反序列化成 Map）
     */
    public List<AgentTurnDTO> listMessages(String agentSessionId, AgentCallContext context) {
        try {
            HttpEntity<Void> entity = new HttpEntity<>(authHeaders(context));
            ResponseEntity<List<AgentTurnDTO>> response = restTemplate.exchange(
                    serviceUrl + "/api/sessions/" + agentSessionId + "/messages",
                    HttpMethod.GET, entity, new ParameterizedTypeReference<List<AgentTurnDTO>>() {
                    });
            List<AgentTurnDTO> body = response.getBody();
            return body == null ? Collections.emptyList() : body;
        } catch (RestClientResponseException e) {
            throw wrapAgentError(e);
        } catch (ResourceAccessException e) {
            throw unavailable();
        }
    }

    /**
     * 确认多步计划
     */
    public AgentTurnDTO confirmTurn(String agentSessionId, String turnId, AgentCallContext context) {
        return post("/api/sessions/" + agentSessionId + "/messages/" + turnId + "/confirm",
                null, AgentTurnDTO.class, context);
    }

    /**
     * 取消多步计划
     */
    public AgentTurnDTO cancelTurn(String agentSessionId, String turnId, AgentCallContext context) {
        return post("/api/sessions/" + agentSessionId + "/messages/" + turnId + "/cancel",
                null, AgentTurnDTO.class, context);
    }

    /**
     * 重试失败步骤
     */
    public AgentTurnDTO retryTurn(String agentSessionId, String turnId, AgentCallContext context) {
        return post("/api/sessions/" + agentSessionId + "/messages/" + turnId + "/retry",
                null, AgentTurnDTO.class, context);
    }

    /**
     * 撤销
     */
    public AgentSessionDetailDTO undo(String agentSessionId, AgentCallContext context) {
        return post("/api/sessions/" + agentSessionId + "/undo", null,
                AgentSessionDetailDTO.class, context);
    }

    /**
     * 重做
     */
    public AgentSessionDetailDTO redo(String agentSessionId, AgentCallContext context) {
        return post("/api/sessions/" + agentSessionId + "/redo", null,
                AgentSessionDetailDTO.class, context);
    }

    /**
     * 预热选区模型（SAM embedding）
     */
    public void prepareSelection(String agentSessionId, AgentCallContext context) {
        try {
            restTemplate.exchange(serviceUrl + "/api/sessions/" + agentSessionId + "/selection/prepare",
                    HttpMethod.POST, new HttpEntity<>(authHeaders(context)), Void.class);
        } catch (RestClientResponseException e) {
            throw wrapAgentError(e);
        } catch (ResourceAccessException e) {
            throw unavailable();
        }
    }

    /**
     * 设置选区（点选或笔刷）
     */
    public Map<String, Object> setSelection(String agentSessionId, AgentRequests.Select select,
                                            AgentCallContext context) {
        return post("/api/sessions/" + agentSessionId + "/selection", select, Map.class, context);
    }

    /**
     * 查询选区；无选区返回 null
     */
    public Map<String, Object> getSelection(String agentSessionId, AgentCallContext context) {
        return get("/api/sessions/" + agentSessionId + "/selection", Map.class, context);
    }

    /**
     * 清除选区
     */
    public void deleteSelection(String agentSessionId, AgentCallContext context) {
        try {
            restTemplate.exchange(serviceUrl + "/api/sessions/" + agentSessionId + "/selection",
                    HttpMethod.DELETE, new HttpEntity<>(authHeaders(context)), Void.class);
        } catch (RestClientResponseException e) {
            throw wrapAgentError(e);
        } catch (ResourceAccessException e) {
            throw unavailable();
        }
    }

    /**
     * 查询 Run 状态快照
     */
    public AgentRunDTO getRun(String runId, AgentCallContext context) {
        return get("/api/runs/" + runId, AgentRunDTO.class, context);
    }

    /**
     * 创建文生图任务（无会话，创作页入口）
     */
    public AgentRunDTO generateImage(Map<String, Object> payload, AgentCallContext context) {
        return post("/api/generations", payload, AgentRunDTO.class, context);
    }

    /**
     * 查询素材详情（含短时访问 URL）
     */
    public AgentAssetDTO getAsset(String assetId, AgentCallContext context) {
        return get("/api/assets/" + assetId, AgentAssetDTO.class, context);
    }

    /**
     * 提交批量处理任务
     */
    public AgentRunDTO createBatch(Map<String, Object> payload, AgentCallContext context) {
        return post("/api/batches", payload, AgentRunDTO.class, context);
    }

    /**
     * 下载批量任务产物 ZIP（Agent 侧为 GET）
     */
    public byte[] exportBatch(String batchRunId, AgentCallContext context) {
        try {
            HttpEntity<Void> entity = new HttpEntity<>(authHeaders(context));
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    serviceUrl + "/api/batches/" + batchRunId + "/export",
                    HttpMethod.GET, entity, byte[].class);
            byte[] body = response.getBody();
            return body == null ? new byte[0] : body;
        } catch (RestClientResponseException e) {
            throw wrapAgentError(e);
        } catch (ResourceAccessException e) {
            throw unavailable();
        }
    }

    /**
     * 下载会话导出 ZIP（Agent 侧为 POST，携带 asset_ids）
     */
    public byte[] exportSession(String agentSessionId, Map<String, Object> payload, AgentCallContext context) {
        return download("/api/sessions/" + agentSessionId + "/exports", context, payload);
    }

    private byte[] download(String path, AgentCallContext context) {
        return download(path, context, null);
    }

    private byte[] download(String path, AgentCallContext context, Object payload) {
        try {
            HttpEntity<Object> entity = new HttpEntity<>(payload, authHeaders(context));
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    serviceUrl + path, HttpMethod.POST, entity, byte[].class);
            byte[] body = response.getBody();
            return body == null ? new byte[0] : body;
        } catch (RestClientResponseException e) {
            throw wrapAgentError(e);
        } catch (ResourceAccessException e) {
            throw unavailable();
        }
    }

    private HttpHeaders authHeaders(AgentCallContext context) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(TOKEN_HEADER, AgentServiceTokenIssuer.issueToken(tokenSecret, context));
        return headers;
    }

    private <T> T post(String path, Object payload, Class<T> type, AgentCallContext context) {
        try {
            HttpEntity<Object> entity = new HttpEntity<>(payload, authHeaders(context));
            ResponseEntity<T> response = restTemplate.postForEntity(serviceUrl + path, entity, type);
            return response.getBody();
        } catch (RestClientResponseException e) {
            throw wrapAgentError(e);
        } catch (ResourceAccessException e) {
            throw unavailable();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T get(String path, Class<T> type, AgentCallContext context) {
        try {
            HttpEntity<Void> entity = new HttpEntity<>(authHeaders(context));
            ResponseEntity<T> response = restTemplate.exchange(
                    serviceUrl + path, HttpMethod.GET, entity, type);
            T body = response.getBody();
            if (type == Map.class) {
                return body == null ? (T) Collections.unmodifiableMap(new HashMap<>()) : body;
            }
            return body;
        } catch (RestClientResponseException e) {
            throw wrapAgentError(e);
        } catch (ResourceAccessException e) {
            throw unavailable();
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.OPERATION_ERROR, "修图服务暂不可用，请稍后重试");
    }

    private BusinessException wrapAgentError(RestClientResponseException e) {
        String detail = e.getResponseBodyAsString();
        if (e.getRawStatusCode() == 401 || e.getRawStatusCode() == 403) {
            return new BusinessException(ErrorCode.OPERATION_ERROR, "修图服务拒绝访问，请刷新后重试");
        }
        if (e.getRawStatusCode() == 404) {
            return new BusinessException(ErrorCode.NOT_FOUND_ERROR, "修图资源不存在或已被清理");
        }
        if (e.getRawStatusCode() == 409) {
            return new BusinessException(ErrorCode.OPERATION_ERROR, "画布已更新，请刷新后重试");
        }
        log.warn("修图 Agent 返回错误 {}：{}", e.getRawStatusCode(), detail);
        return new BusinessException(ErrorCode.OPERATION_ERROR, "修图服务处理失败：" + firstLine(detail));
    }

    private String firstLine(String text) {
        if (text == null) {
            return "未知错误";
        }
        int idx = text.indexOf('\n');
        String line = idx > 0 ? text.substring(0, idx) : text;
        return line.length() > 200 ? line.substring(0, 200) : line;
    }
}
