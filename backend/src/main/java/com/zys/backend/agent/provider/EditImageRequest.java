package com.zys.backend.agent.provider;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 图像编辑请求（Provider 无关）
 */
@Data
@Accessors(chain = true)
public class EditImageRequest {

    /**
     * 编辑指令
     */
    private String prompt;

    /**
     * 输入图片字节
     */
    private byte[] image;

    /**
     * 希望产生的候选数量，默认 1
     */
    private int count = 1;

    /**
     * 反向提示词
     */
    private String negativePrompt;

    /**
     * 目标宽度（可空）
     */
    private Integer width;

    /**
     * 目标高度（可空）
     */
    private Integer height;

    /**
     * 编辑强度 0-1（可空）
     */
    private Double strength;
}
