package com.zys.backend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * Outbox 事务消息状态枚举
 */
@Getter
public enum OutboxStatusEnum {

    PENDING("待处理", "PENDING"),
    PROCESSING("处理中", "PROCESSING"),
    SUCCEEDED("已成功", "SUCCEEDED"),
    FAILED("已失败", "FAILED");

    private final String text;

    private final String value;

    OutboxStatusEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的 value
     * @return 枚举值
     */
    public static OutboxStatusEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (OutboxStatusEnum anEnum : OutboxStatusEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}
