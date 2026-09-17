package com.zys.backend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 图片版本来源枚举
 */
@Getter
public enum PictureVersionSourceEnum {

    UPLOAD("直接上传", "UPLOAD"),
    QUICK_EDIT("快捷编辑", "QUICK_EDIT"),
    AGENT("Agent 精修", "AGENT"),
    RESTORE("版本恢复", "RESTORE");

    private final String text;

    private final String value;

    PictureVersionSourceEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的 value
     * @return 枚举值
     */
    public static PictureVersionSourceEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (PictureVersionSourceEnum anEnum : PictureVersionSourceEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}
