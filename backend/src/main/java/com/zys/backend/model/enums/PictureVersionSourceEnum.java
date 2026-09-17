package com.zys.backend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 图片正式版本来源枚举类
 */
@Getter
public enum PictureVersionSourceEnum {

    UPLOAD("上传", "upload"),
    QUICK_EDIT("快捷编辑", "quick_edit"),
    AGENT("Agent 精修", "agent"),
    RESTORE("恢复历史版本", "restore");

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
        for (PictureVersionSourceEnum item : PictureVersionSourceEnum.values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        return null;
    }
}
