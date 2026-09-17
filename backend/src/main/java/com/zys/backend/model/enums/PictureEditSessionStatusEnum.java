package com.zys.backend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 图片编辑会话状态枚举
 */
@Getter
public enum PictureEditSessionStatusEnum {

    DRAFT("草稿", "DRAFT"),
    ACTIVE("进行中", "ACTIVE"),
    READY_TO_COMMIT("待提交", "READY_TO_COMMIT"),
    COMMITTING("提交中", "COMMITTING"),
    COMMITTED("已提交", "COMMITTED"),
    CANCELED("已取消", "CANCELED"),
    EXPIRED("已过期", "EXPIRED"),
    CONFLICT("版本冲突", "CONFLICT");

    private final String text;

    private final String value;

    PictureEditSessionStatusEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的 value
     * @return 枚举值
     */
    public static PictureEditSessionStatusEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (PictureEditSessionStatusEnum anEnum : PictureEditSessionStatusEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}
