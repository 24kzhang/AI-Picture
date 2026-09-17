package com.zys.backend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 图片正式版本快照（不可变）
 * @TableName picture_version
 */
@TableName(value = "picture_version")
@Data
public class PictureVersion {
    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 图片 id
     */
    private Long pictureId;

    /**
     * 版本号，同一图片单调递增
     */
    private Long versionNo;

    /**
     * 原图 URL
     */
    private String url;

    /**
     * 缩略图 URL
     */
    private String thumbnailUrl;

    /**
     * 图片体积（字节）
     */
    private Long picSize;

    /**
     * 图片宽度
     */
    private Integer picWidth;

    /**
     * 图片高度
     */
    private Integer picHeight;

    /**
     * 图片宽高比例
     */
    private Double picScale;

    /**
     * 图片格式
     */
    private String picFormat;

    /**
     * 图片主色调
     */
    private String picColor;

    /**
     * 附加元数据 JSON
     */
    private String metadataJson;

    /**
     * 来源：UPLOAD/QUICK_EDIT/AGENT/RESTORE
     */
    private String source;

    /**
     * 来源编辑会话 id
     */
    private Long sourceSessionId;

    /**
     * 来源编辑运行 id
     */
    private Long sourceRunId;

    /**
     * 操作人 id
     */
    private Long operatorId;

    /**
     * 创建时间
     */
    private Date createTime;
}
