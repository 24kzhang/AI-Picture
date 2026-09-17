package com.zys.backend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Agent 一轮运行（一次自然语言指令）
 * @TableName picture_agent_run
 */
@TableName(value = "picture_agent_run")
@Data
public class PictureAgentRun {
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
     * 发起人 id
     */
    private Long userId;

    /**
     * 发起时的画布 revision
     */
    private Integer revision;

    /**
     * 用户自然语言指令
     */
    private String goal;

    /**
     * Agent 文本回复
     */
    private String reply;

    /**
     * 工具调用计划 JSON
     */
    private String planJson;

    /**
     * 运行状态：planning/waiting/running/succeeded/failed/canceled
     */
    private String status;

    /**
     * 失败原因
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
}
