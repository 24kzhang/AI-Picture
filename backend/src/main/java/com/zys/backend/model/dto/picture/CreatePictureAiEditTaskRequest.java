package com.zys.backend.model.dto.picture;

import lombok.Data;

import java.io.Serializable;

/**
 * 创建 AI 图片编辑任务请求
 */
@Data
public class CreatePictureAiEditTaskRequest implements Serializable {

    /**
     * 图片 ID
     */
    private Long pictureId;

    /**
     * 自然语言编辑指令
     */
    private String prompt;

    /**
     * 编辑强度，范围 0 到 1
     */
    private Double strength;

    private static final long serialVersionUID = 1L;
}
