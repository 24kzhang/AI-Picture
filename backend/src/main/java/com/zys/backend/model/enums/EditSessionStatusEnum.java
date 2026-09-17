package com.zys.backend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * Agent 编辑会话状态枚举类
 */
@Getter
public enum EditSessionStatusEnum {

    ACTIVE("进行中", "active"),
    COMMITTED("已提交", "committed"),
    CONFLICT("版本冲突", "conflict"),
    EXPIRED("已过期", "expired"),
    CLOSED("已关闭", "closed");

    private final String text;

    private final String value;

    EditSessionStatusEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的 value
     * @return 枚举值
     */
    public static EditSessionStatusEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (EditSessionStatusEnum item : EditSessionStatusEnum.values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        return null;
    }

    /**
     * 是否为终态（不允许继续编辑）
     */
    public static boolean isTerminal(String value) {
        EditSessionStatusEnum item = getEnumByValue(value);
        return item == COMMITTED || item == CONFLICT || item == EXPIRED || item == CLOSED;
    }
}
