package com.zys.backend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

@Getter
public enum UserRoleEnum {
    USER("用户", "user"),

    VIP("VIP", "vip"),

    ADMIN("管理员", "admin");


    private final String text;

    private final String value;

    UserRoleEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据value 获取枚举对象
     *
     * @param value 枚举值的 value
     * @return 枚举对象
     */
    public static UserRoleEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (UserRoleEnum enumItem : UserRoleEnum.values()) {
            if (enumItem.getValue().equals(value)) {
                return enumItem;
            }
        }
        /**
         * 如果枚举的 value 有成千上万，需要改为使用 HashMap 存储，提高查询效率
         */

        return null;
    }
}
