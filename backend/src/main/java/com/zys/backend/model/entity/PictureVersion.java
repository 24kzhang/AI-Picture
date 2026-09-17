package com.zys.backend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 图片正式版本快照
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
     * 版本号，从 1 递增
     */
    private Integer versionNo;

    /**
     * 该版本图片 URL
     */
    private String url;

    /**
     * 该版本缩略图 URL
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
     * 图片格式
     */
    private String picFormat;

    /**
     * 图片主色调
     */
    private String picColor;

    /**
     * 版本来源：upload/quick_edit/agent/restore
     */
    private String source;

    /**
     * 来源 Agent 编辑会话 id
     */
    private Long sourceSessionId;

    /**
     * 来源 Agent 运行 id
     */
    private Long sourceRunId;

    /**
     * 提交人 id
     */
    private Long operatorId;

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
