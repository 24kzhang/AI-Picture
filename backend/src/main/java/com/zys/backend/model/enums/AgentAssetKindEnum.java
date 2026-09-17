package com.zys.backend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * Agent 会话资产类型枚举类
 */
@Getter
public enum AgentAssetKindEnum {

    ORIGINAL("原图资产", "original"),
    CANDIDATE("候选草稿", "candidate"),
    MARKETING("营销图", "marketing"),
    MASK("选区蒙版", "mask"),
    DELIVERY("交付多尺寸", "delivery");

    private final String text;

    private final String value;

    AgentAssetKindEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的 value
     * @return 枚举值
     */
    public static AgentAssetKindEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (AgentAssetKindEnum item : AgentAssetKindEnum.values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        return null;
    }
}
