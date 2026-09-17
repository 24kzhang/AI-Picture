package com.zys.backend.agent;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.annotation.Resource;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * Agent 接口结构化日志：注入 requestId（MDC + 响应头），记录耗时与状态，
 * 并按状态码累计请求成功/失败指标。不记录请求体、Cookie 与签名 URL。
 */
@Slf4j
@Component
public class AgentRequestLogFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID = "requestId";

    @Resource
    private AgentMetrics agentMetrics;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || (!uri.contains("/agent-") && !uri.contains("/agent-asset"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put(REQUEST_ID, requestId);
        response.setHeader("X-Request-Id", requestId);
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = System.currentTimeMillis() - start;
            int status = response.getStatus();
            if (status >= 500) {
                agentMetrics.incrementAgentRequestFailure();
            } else {
                agentMetrics.incrementAgentRequestSuccess();
            }
            log.info("agent request uri={} method={} status={} durationMs={}",
                    request.getRequestURI(), request.getMethod(), status, durationMs);
            MDC.remove(REQUEST_ID);
        }
    }
}
