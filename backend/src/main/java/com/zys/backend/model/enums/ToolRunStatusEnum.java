package com.zys.backend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * Agent 工具任务状态枚举类
 */
@Getter
public enum ToolRunStatusEnum {

    PENDING("待执行", "pending"),
    WAITING("等待前置步骤", "waiting"),
    QUEUED("已入队", "queued"),
    RUNNING("执行中", "running"),
    SUCCEEDED("成功", "succeeded"),
    FAILED("失败", "failed"),
    CANCELED("已取消", "canceled");

    private final String text;

    private final String value;

    ToolRunStatusEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的 value
     * @return 枚举值
     */
    public static ToolRunStatusEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (ToolRunStatusEnum item : ToolRunStatusEnum.values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        return null;
    }

    /**
     * 是否为终态（终态任务重复消费直接跳过）
     */
    public static boolean isTerminal(String value) {
        ToolRunStatusEnum item = getEnumByValue(value);
        return item == SUCCEEDED || item == FAILED || item == CANCELED;
    }

    /**
     * 是否可被取消（取消计划只取消非运行中步骤）
     */
    public static boolean isCancelable(String value) {
        ToolRunStatusEnum item = getEnumByValue(value);
        return item == PENDING || item == WAITING || item == QUEUED;
    }
}
