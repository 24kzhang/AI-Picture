package com.zys.backend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 图片编辑会话（云图库侧，映射修图 Agent 会话）
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
     * 修图 Agent 会话 ID（UUID）
     */
    private String agentSessionId;

    /**
     * 图片 id
     */
    private Long pictureId;

    /**
     * 空间 id；NULL 表示公共图库
     */
    private Long spaceId;

    /**
     * 发起用户 id
     */
    private Long userId;

    /**
     * 创建会话时的图片编辑版本号
     */
    private Long baseEditVersion;

    /**
     * 状态：DRAFT/ACTIVE/READY_TO_COMMIT/COMMITTING/COMMITTED/CANCELED/EXPIRED/CONFLICT
     */
    private String status;

    /**
     * 用户选定的最终 Agent 素材 ID
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
     * 会话过期时间
     */
    private Date expireTime;
}
