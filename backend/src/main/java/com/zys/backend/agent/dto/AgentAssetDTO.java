package com.zys.backend.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Date;

/**
 * 修图 Agent 素材出参（AgentAssetOut）
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentAssetDTO {

    /**
     * 素材 ID（UUID）
     */
    private String id;

    /**
     * 类型：original/generated/subject/background/mask/marketing/export
     */
    private String kind;

    /**
     * 来源：upload/generate/tool
     */
    private String source;

    /**
     * 图片格式
     */
    @JsonProperty("image_format")
    private String imageFormat;

    /**
     * 宽度
     */
    private Integer width;

    /**
     * 高度
     */
    private Integer height;

    /**
     * 体积（字节）
     */
    @JsonProperty("size_bytes")
    private Long sizeBytes;

    /**
     * 是否含透明通道
     */
    @JsonProperty("has_alpha")
    private Boolean hasAlpha;

    /**
     * 创建时间
     */
    @JsonProperty("created_at")
    private Date createdAt;

    /**
     * 短时访问 URL
     */
    private String url;
}
