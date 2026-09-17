package com.zys.backend.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 修图 Agent 会话详情出参（SessionDetailOut）
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentSessionDetailDTO {

    /**
     * 会话 ID（UUID）
     */
    private String id;

    /**
     * 会话标题
     */
    private String title;

    /**
     * 画布修订号（选区失效判定）
     */
    private Integer revision;

    /**
     * 撤销指针
     */
    @JsonProperty("history_seq")
    private Integer historySeq;

    /**
     * 原始素材 ID
     */
    @JsonProperty("original_asset_id")
    private String originalAssetId;

    /**
     * 当前素材 ID
     */
    @JsonProperty("current_asset_id")
    private String currentAssetId;

    /**
     * 创建时间
     */
    @JsonProperty("created_at")
    private Date createdAt;

    /**
     * 更新时间
     */
    @JsonProperty("updated_at")
    private Date updatedAt;

    /**
     * 图层文档（LayerDocument）
     */
    private Map<String, Object> document = new LinkedHashMap<>();

    /**
     * 上一版图层文档（对比用）
     */
    @JsonProperty("previous_document")
    private Map<String, Object> previousDocument;

    /**
     * 图片墙素材
     */
    private List<AgentAssetDTO> assets = new ArrayList<>();

    /**
     * 是否可撤销
     */
    @JsonProperty("can_undo")
    private Boolean canUndo;

    /**
     * 是否可重做
     */
    @JsonProperty("can_redo")
    private Boolean canRedo;
}
