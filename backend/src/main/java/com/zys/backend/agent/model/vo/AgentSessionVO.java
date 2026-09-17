package com.zys.backend.agent.model.vo;

import lombok.Data;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 会话快照，页面刷新后恢复用
 */
@Data
public class AgentSessionVO {

    private Long id;

    private Long pictureId;

    private Long spaceId;

    private Long userId;

    private Long baseEditVersion;

    private String status;

    private Integer revision;

    private Integer historySeq;

    private Long finalAssetId;

    private Long currentAssetId;

    private Long committedVersionId;

    private Date expireTime;

    /**
     * 画布文档 JSON 反序列化结果
     */
    private Map<String, Object> document;

    /**
     * 资产墙
     */
    private List<AgentAssetVO> assets;

    /**
     * 最近运行列表（含计划与任务状态）
     */
    private List<AgentRunVO> runs;

    /**
     * 当前选区（可能为空）
     */
    private Map<String, Object> selection;

    /**
     * 编辑租约信息（可能为空）
     */
    private Map<String, Object> lease;

    /**
     * 可用工具
     */
    private List<AgentToolVO> tools;

    /**
     * 是否可撤销/重做
     */
    private Boolean canUndo;

    private Boolean canRedo;
}
