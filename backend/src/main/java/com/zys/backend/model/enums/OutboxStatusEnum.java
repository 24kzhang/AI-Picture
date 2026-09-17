package com.zys.backend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 集成事件发件箱状态枚举类
 */
@Getter
public enum OutboxStatusEnum {

    PENDING("待处理", "pending"),
    PROCESSING("处理中", "processing"),
    SUCCEEDED("成功", "succeeded"),
    FAILED("失败", "failed");

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
        for (OutboxStatusEnum item : OutboxStatusEnum.values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        return null;
    }
}
