package com.zys.backend.agent.provider;

import cn.hutool.core.util.StrUtil;
import com.zys.backend.agent.tool.ProgressReporter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * Provider 路由：auto 模式使用服务器部署的 Qwen-Image；mock 仅用于本地演示。
 * 兼容旧配置中的 dashscope 值，但实际请求同样进入 Qwen-Image 服务器。
 */
@Slf4j
@Component
public class AgentImageProviderRouter implements AgentImageProvider {

    @Value("${agent.image.provider:auto}")
    private String providerMode;

    @Value("${aliYunAi.apiKey:}")
    private String aiApiKey;

    @Resource(name = "dashScopeAgentImageProvider")
    private AgentImageProvider dashScopeProvider;

    @Resource(name = "mockAgentImageProvider")
    private AgentImageProvider mockProvider;

    /**
     * 当前生效的 Provider
     */
    public AgentImageProvider current() {
        String mode = StrUtil.blankToDefault(providerMode, "auto").trim().toLowerCase();
        if ("mock".equals(mode)) {
            return mockProvider;
        }
        if ("dashscope".equals(mode) || "qwen-server".equals(mode) || "qwen-image-server".equals(mode)) {
            return dashScopeProvider;
        }
        return dashScopeProvider;
    }

    @Override
    public String name() {
        return current().name();
    }

    @Override
    public List<byte[]> edit(EditImageRequest request, ProgressReporter reporter) {
        return current().edit(request, reporter);
    }

    @Override
    public byte[] upscale(byte[] image, int scale, ProgressReporter reporter) {
        return current().upscale(image, scale, reporter);
    }
}
