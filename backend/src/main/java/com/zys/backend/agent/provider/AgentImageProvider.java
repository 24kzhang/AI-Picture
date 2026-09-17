package com.zys.backend.agent.provider;

import com.zys.backend.agent.tool.ProgressReporter;

import java.util.List;

/**
 * 图片编辑 Provider 抽象：DashScope 真实调用与 Mock 本地模拟共用接口。
 */
public interface AgentImageProvider {

    /**
     * Provider 名称
     */
    String name();

    /**
     * 按指令编辑图片，返回 1..count 张结果字节
     */
    List<byte[]> edit(EditImageRequest request, ProgressReporter reporter);

    /**
     * 提升分辨率
     *
     * @param scale 放大倍数 2/4
     */
    byte[] upscale(byte[] image, int scale, ProgressReporter reporter);
}
