package com.zys.backend.manager.websocket.model;

import lombok.Getter;

/**
 * 图片编辑动作枚举
 */
@Getter
public enum PictureEditActionEnum {

    ZOOM_IN("放大操作", "ZOOM_IN"),
    ZOOM_OUT("缩小操作", "ZOOM_OUT"),
    ROTATE_LEFT("左旋操作", "ROTATE_LEFT"),
    ROTATE_RIGHT("右旋操作", "ROTATE_RIGHT"),
    FLIP_HORIZONTAL("水平翻转", "FLIP_HORIZONTAL"),
    FLIP_VERTICAL("垂直翻转", "FLIP_VERTICAL"),
    BRUSH_STROKE("画笔绘制", "BRUSH_STROKE"),
    MOVE_STICKER("移动贴图", "MOVE_STICKER"),
    ADD_TEXT("添加文字", "ADD_TEXT"),
    ADD_STICKER("添加贴图", "ADD_STICKER"),
    SET_FILTER("调整滤镜", "SET_FILTER"),
    UNDO("撤销操作", "UNDO"),
    REDO("重做操作", "REDO"),
    RESET("重置图片", "RESET"),
    AI_EDIT("应用 AI 编辑结果", "AI_EDIT"),
    SYNC_STATE("同步编辑状态", "SYNC_STATE");

    private final String text;
    private final String value;

    PictureEditActionEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     */
    public static PictureEditActionEnum getEnumByValue(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        for (PictureEditActionEnum actionEnum : PictureEditActionEnum.values()) {
            if (actionEnum.value.equals(value)) {
                return actionEnum;
            }
        }
        return null;
    }
}