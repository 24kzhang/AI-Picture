package com.zys.backend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 图片编辑运行记录（镜像 Agent Run 状态）
 * @TableName picture_edit_run
 */
@TableName(value = "picture_edit_run")
@Data
public class PictureEditRun {
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
     * Agent Run ID
     */
    private String agentRunId;

    /**
     * 运行类型：TOOL/PLAN/BATCH/EXPORT
     */
    private String runType;

    /**
     * 状态：pending/waiting/queued/running/succeeded/failed/canceled
     */
    private String status;

    /**
     * 进度百分比 0-100
     */
    private Integer progress;

    /**
     * 当前阶段说明
     */
    private String stage;

    /**
     * 多步计划 JSON
     */
    private String planJson;

    /**
     * 结果 JSON
     */
    private String resultJson;

    /**
     * 错误码
     */
    private String errorCode;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 完成时间
     */
    private Date completeTime;
}
