package com.zys.backend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Agent 工具任务
 * @TableName picture_tool_run
 */
@TableName(value = "picture_tool_run")
@Data
public class PictureToolRun {
    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属 Agent 运行 id
     */
    private Long agentRunId;

    /**
     * 编辑会话 id
     */
    private Long editSessionId;

    /**
     * 发起人 id
     */
    private Long userId;

    /**
     * 计划内步骤 id
     */
    private String stepId;

    /**
     * 工具名
     */
    private String tool;

    /**
     * 任务状态：pending/waiting/queued/running/succeeded/failed/canceled
     */
    private String status;

    /**
     * 进度百分比 0-100
     */
    private Integer progress;

    /**
     * 当前阶段描述
     */
    private String stage;

    /**
     * 工具入参 JSON
     */
    private String paramsJson;

    /**
     * 工具结果 JSON
     */
    private String resultJson;

    /**
     * 失败原因
     */
    private String errorMessage;

    /**
     * 重试次数
     */
    private Integer retries;

    /**
     * 开始执行时间
     */
    private Date startedAt;

    /**
     * 结束时间
     */
    private Date finishedAt;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;
}
