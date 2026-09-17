package com.zys.backend.agent.provider;

import cn.hutool.core.util.StrUtil;
import com.zys.backend.agent.tool.ProgressReporter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * Provider 路由：auto 模式下配置了 AI Key 走 DashScope，否则走 Mock；
 * 也可通过 agent.image.provider=dashscope|mock 强制指定。
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
        if ("dashscope".equals(mode)) {
            return dashScopeProvider;
        }
        return StrUtil.isNotBlank(aiApiKey) ? dashScopeProvider : mockProvider;
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
