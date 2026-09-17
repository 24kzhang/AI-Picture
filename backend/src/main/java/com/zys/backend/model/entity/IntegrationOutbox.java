package com.zys.backend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 集成事件发件箱（缩略图、向量重建、孤儿对象清理等最终一致事件）
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
     * 事件类型：thumbnail_rebuild/vector_rebuild/orphan_cleanup
     */
    private String eventType;

    /**
     * 业务 id（如 pictureId）
     */
    private String bizId;

    /**
     * 事件载荷 JSON
     */
    private String payloadJson;

    /**
     * 状态：pending/processing/succeeded/failed
     */
    private String status;

    /**
     * 重试次数
     */
    private Integer retries;

    /**
     * 失败原因
     */
    private String errorMessage;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 最近处理时间
     */
    private Date processTime;

    /**
     * 更新时间
     */
    private Date updateTime;
}
