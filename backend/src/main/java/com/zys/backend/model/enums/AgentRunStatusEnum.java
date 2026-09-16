package com.zys.backend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 编辑运行状态枚举（与修图 Agent Run 状态保持一致）
 */
@Getter
public enum AgentRunStatusEnum {

    PENDING("等待规划", "pending"),
    WAITING("等待确认", "waiting"),
    QUEUED("排队中", "queued"),
    RUNNING("执行中", "running"),
    SUCCEEDED("已成功", "succeeded"),
    FAILED("已失败", "failed"),
    CANCELED("已取消", "canceled");

    private final String text;

    private final String value;

    AgentRunStatusEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的 value
     * @return 枚举值
     */
    public static AgentRunStatusEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (AgentRunStatusEnum anEnum : AgentRunStatusEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }

    /**
     * 是否终态
     */
    public static boolean isTerminal(String value) {
        return "succeeded".equals(value) || "failed".equals(value) || "canceled".equals(value);
    }
}
