package com.zys.backend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * Agent 运行状态枚举类
 */
@Getter
public enum AgentRunStatusEnum {

    PLANNING("规划中", "planning"),
    WAITING("等待确认", "waiting"),
    RUNNING("执行中", "running"),
    SUCCEEDED("成功", "succeeded"),
    FAILED("失败", "failed"),
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
        for (AgentRunStatusEnum item : AgentRunStatusEnum.values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        return null;
    }

    /**
     * 是否为终态
     */
    public static boolean isTerminal(String value) {
        AgentRunStatusEnum item = getEnumByValue(value);
        return item == SUCCEEDED || item == FAILED || item == CANCELED;
    }
}
