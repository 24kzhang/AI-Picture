package com.zys.backend.agent.tool;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 工具参数的声明式字段定义，同时用于服务端校验与规划模型函数签名生成。
 */
@Data
@Accessors(chain = true)
public class ParamField {

    public static final String TYPE_STRING = "string";
    public static final String TYPE_NUMBER = "number";
    public static final String TYPE_INTEGER = "integer";
    public static final String TYPE_BOOLEAN = "boolean";
    public static final String TYPE_ARRAY = "array";
    public static final String TYPE_OBJECT = "object";

    /**
     * 参数名
     */
    private String name;

    /**
     * 类型：string/number/integer/boolean/array/object
     */
    private String type = TYPE_STRING;

    /**
     * 是否必填
     */
    private boolean required;

    /**
     * 默认值，缺省时自动补入
     */
    private Object defaultValue;

    /**
     * 数值下限
     */
    private Double min;

    /**
     * 数值上限
     */
    private Double max;

    /**
     * 字符串最小长度
     */
    private Integer minLength;

    /**
     * 字符串最大长度
     */
    private Integer maxLength;

    /**
     * 枚举可选值
     */
    private List<Object> choices;

    /**
     * 正则约束（如颜色 #RRGGBB）
     */
    private String pattern;

    /**
     * 数组元素类型
     */
    private String itemType;

    /**
     * 数组元素可选值
     */
    private List<Object> itemChoices;

    /**
     * 数组最小长度
     */
    private Integer minItems;

    /**
     * 数组最大长度
     */
    private Integer maxItems;

    /**
     * 给规划模型看的说明
     */
    private String description;

    /**
     * 是否对模型隐藏（由服务端从上下文填入，如 maskAssetId、revision）
     */
    private boolean agentHidden;

    public static ParamField string(String name) {
        return new ParamField().setName(name).setType(TYPE_STRING);
    }

    public static ParamField number(String name) {
        return new ParamField().setName(name).setType(TYPE_NUMBER);
    }

    public static ParamField integer(String name) {
        return new ParamField().setName(name).setType(TYPE_INTEGER);
    }

    public static ParamField bool(String name) {
        return new ParamField().setName(name).setType(TYPE_BOOLEAN);
    }

    public static ParamField array(String name, String itemType) {
        return new ParamField().setName(name).setType(TYPE_ARRAY).setItemType(itemType);
    }

    public static ParamField object(String name) {
        return new ParamField().setName(name).setType(TYPE_OBJECT);
    }
}
