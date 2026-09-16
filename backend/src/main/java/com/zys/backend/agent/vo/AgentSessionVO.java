package com.zys.backend.agent.vo;

import com.zys.backend.agent.dto.AgentSessionDetailDTO;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Agent 编辑会话视图（含画布快照与最近对话）
 */
@Data
public class AgentSessionVO {

    /**
     * 云图库编辑会话 id
     */
    private Long id;

    /**
     * 修图 Agent 会话 id
     */
    private String agentSessionId;

    /**
     * 图片 id
     */
    private Long pictureId;

    /**
     * 空间 id；公共图库为 null
     */
    private Long spaceId;

    /**
     * 创建会话时的图片编辑版本号
     */
    private Long baseEditVersion;

    /**
     * 会话状态
     */
    private String status;

    /**
     * 用户选定的最终 Agent 素材 id
     */
    private String finalAgentAssetId;

    /**
     * 提交后的图片版本 id
     */
    private Long committedVersionId;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 过期时间
     */
    private Date expireTime;

    /**
     * 当前用户是否只读（非会话所有者）
     */
    private Boolean readOnly;

    /**
     * 统一编辑租约信息；无人持有时为 null
     */
    private AgentLeaseVO lease;

    /**
     * 画布快照（图层文档、图片墙、修订号、可撤销/重做）
     */
    private AgentSessionDetailDTO canvas;

    /**
     * 最近对话轮次
     */
    private List<AgentTurnVO> turns = new ArrayList<>();

    /**
     * 编辑租约视图（不含 lockToken）
     */
    @Data
    public static class AgentLeaseVO {

        /**
         * 租约模式：QUICK/AGENT
         */
        private String mode;

        /**
         * 持有者用户 id
         */
        private Long userId;

        /**
         * 持有者会话标识
         */
        private String sessionId;

        /**
         * 获取时间戳（毫秒）
         */
        private Long acquiredAt;
    }
}
