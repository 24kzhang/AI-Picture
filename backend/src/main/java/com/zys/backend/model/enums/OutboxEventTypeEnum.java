package com.zys.backend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 集成事件发件箱事件类型枚举类
 */
@Getter
public enum OutboxEventTypeEnum {

    THUMBNAIL_REBUILD("缩略图重建", "thumbnail_rebuild"),
    VECTOR_REBUILD("向量索引重建", "vector_rebuild"),
    ORPHAN_CLEANUP("孤儿对象清理", "orphan_cleanup");

    private final String text;

    private final String value;

    OutboxEventTypeEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的 value
     * @return 枚举值
     */
    public static OutboxEventTypeEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (OutboxEventTypeEnum item : OutboxEventTypeEnum.values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        return null;
    }
}
