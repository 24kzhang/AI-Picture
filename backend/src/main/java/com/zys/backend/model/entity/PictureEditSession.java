package com.zys.backend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Agent 编辑会话
 * @TableName picture_edit_session
 */
@TableName(value = "picture_edit_session")
@Data
public class PictureEditSession {
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
     * 空间 id；NULL 表示公共图库
     */
    private Long spaceId;

    /**
     * 会话创建人 id
     */
    private Long userId;

    /**
     * 创建会话时的 picture.editVersion 快照
     */
    private Long baseEditVersion;

    /**
     * 会话状态：active/committed/conflict/expired/closed
     */
    private String status;

    /**
     * 用户选定的最终草稿资产 id
     */
    private Long finalAssetId;

    /**
     * 当前画布资产 id
     */
    private Long currentAssetId;

    /**
     * 画布文档版本号
     */
    private Integer revision;

    /**
     * 撤销重做历史序号
     */
    private Integer historySeq;

    /**
     * 画布文档 JSON（图层、尺寸等）
     */
    private String documentJson;

    /**
     * 已提交的正式版本 id
     */
    private Long committedVersionId;

    /**
     * 会话过期时间
     */
    private Date expireTime;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 编辑时间
     */
    private Date editTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 是否删除
     */
    @TableLogic
    private Integer isDelete;
}
