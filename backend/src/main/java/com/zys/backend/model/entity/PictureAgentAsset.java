package com.zys.backend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Agent 会话资产墙
 * @TableName picture_agent_asset
 */
@TableName(value = "picture_agent_asset")
@Data
public class PictureAgentAsset {
    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 编辑会话 id
     */
    private Long editSessionId;

    /**
     * 图片 id
     */
    private Long pictureId;

    /**
     * 产出人 id
     */
    private Long userId;

    /**
     * 资产类型：original/candidate/marketing/mask/delivery
     */
    private String kind;

    /**
     * 产出来源工具名或操作
     */
    private String source;

    /**
     * 资产 URL
     */
    private String url;

    /**
     * 缩略图 URL
     */
    private String thumbnailUrl;

    /**
     * COS 对象 key
     */
    private String storageKey;

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
    private Long sizeBytes;

    /**
     * 资产墙排序位置
     */
    private Integer position;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 是否删除
     */
    @TableLogic
    private Integer isDelete;
}
