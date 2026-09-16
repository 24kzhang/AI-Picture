package com.zys.backend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 集成事务消息 Outbox（最终一致：缩略图、向量索引等外部副作用）
 * @TableName integration_outbox
 */
@TableName(value = "integration_outbox")
@Data
public class IntegrationOutbox {
    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 事件类型：PICTURE_VERSION_COMMITTED/PICTURE_THUMBNAIL_REQUIRED/PICTURE_VECTOR_REINDEX_REQUIRED
     */
    private String eventType;

    /**
     * 聚合根 id（如 pictureId）
     */
    private Long aggregateId;

    /**
     * 事件负载 JSON
     */
    private String payload;

    /**
     * 状态：PENDING/PROCESSING/SUCCEEDED/FAILED
     */
    private String status;

    /**
     * 已重试次数
     */
    private Integer retryCount;

    /**
     * 下次重试时间
     */
    private Date nextRetryTime;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;
}
