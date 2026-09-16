package com.zys.backend.agent.vo;

import lombok.Data;

import java.util.Date;

/**
 * 图片版本视图
 */
@Data
public class PictureVersionVO {

    /**
     * 版本 id
     */
    private Long id;

    /**
     * 图片 id
     */
    private Long pictureId;

    /**
     * 版本号
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
     * 宽度
     */
    private Integer picWidth;

    /**
     * 高度
     */
    private Integer picHeight;

    /**
     * 图片格式
     */
    private String picFormat;

    /**
     * 主色调
     */
    private String picColor;

    /**
     * 来源：UPLOAD/QUICK_EDIT/AGENT/RESTORE
     */
    private String source;

    /**
     * 操作人 id
     */
    private Long operatorId;

    /**
     * 创建时间
     */
    private Date createTime;
}
