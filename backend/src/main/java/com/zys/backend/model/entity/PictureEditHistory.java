package com.zys.backend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Agent 编辑撤销重做历史
 * @TableName picture_edit_history
 */
@TableName(value = "picture_edit_history")
@Data
public class PictureEditHistory {
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
     * 历史序号，会话内递增
     */
    private Integer seq;

    /**
     * 动作类型：工具名或 undo 标记
     */
    private String action;

    /**
     * 动作入参 JSON
     */
    private String paramsJson;

    /**
     * 动作结果 JSON（含回滚所需信息）
     */
    private String resultJson;

    /**
     * 创建时间
     */
    private Date createTime;
}
