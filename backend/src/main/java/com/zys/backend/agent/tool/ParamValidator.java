package com.zys.backend.agent.tool;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 工具参数校验器：按 ToolSpec 的字段声明校验并补齐默认值，
 * 界面直调工具与 Agent 规划走同一套规则。
 */
public final class ParamValidator {

    private ParamValidator() {
    }

    /**
     * 校验参数并返回归一化结果（含默认值，剔除未声明字段）
     */
    public static Map<String, Object> validate(ToolSpec spec, Map<String, Object> raw) {
        Map<String, Object> source = raw == null ? new LinkedHashMap<>() : raw;
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (ParamField field : spec.getParams()) {
            Object value = source.get(field.getName());
            if (value == null || (value instanceof String && StrUtil.isBlank((String) value))) {
                if (field.isRequired()) {
                    throw invalid(field.getName(), "必填参数缺失");
                }
                if (field.getDefaultValue() != null) {
                    normalized.put(field.getName(), field.getDefaultValue());
                }
                continue;
            }
            normalized.put(field.getName(), validateField(field, value));
        }
        if (spec.getCrossValidator() != null) {
            String error = spec.getCrossValidator().apply(normalized);
            if (StrUtil.isNotBlank(error)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, spec.getName() + "：" + error);
            }
        }
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private static Object validateField(ParamField field, Object value) {
        switch (field.getType()) {
            case ParamField.TYPE_STRING: {
                String text = String.valueOf(value);
                if (field.getMinLength() != null && text.length() < field.getMinLength()) {
                    throw invalid(field.getName(), "长度不能少于 " + field.getMinLength());
                }
                if (field.getMaxLength() != null && text.length() > field.getMaxLength()) {
                    throw invalid(field.getName(), "长度不能超过 " + field.getMaxLength());
                }
                if (field.getChoices() != null && !field.getChoices().contains(text)) {
                    throw invalid(field.getName(), "必须是 " + field.getChoices() + " 之一");
                }
                if (StrUtil.isNotBlank(field.getPattern()) && !Pattern.matches(field.getPattern(), text)) {
                    throw invalid(field.getName(), "格式不正确");
                }
                return text;
            }
            case ParamField.TYPE_NUMBER: {
                double number = toNumber(field, value).doubleValue();
                checkRange(field, number);
                return number;
            }
            case ParamField.TYPE_INTEGER: {
                int number = toNumber(field, value).intValue();
                checkRange(field, number);
                return number;
            }
            case ParamField.TYPE_BOOLEAN: {
                if (value instanceof Boolean) {
                    return value;
                }
                String text = String.valueOf(value);
                if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
                    return Boolean.valueOf(text);
                }
                throw invalid(field.getName(), "必须是布尔值");
            }
            case ParamField.TYPE_ARRAY: {
                if (!(value instanceof List)) {
                    throw invalid(field.getName(), "必须是数组");
                }
                List<Object> items = (List<Object>) value;
                if (field.getMinItems() != null && items.size() < field.getMinItems()) {
                    throw invalid(field.getName(), "元素不能少于 " + field.getMinItems() + " 个");
                }
                if (field.getMaxItems() != null && items.size() > field.getMaxItems()) {
                    throw invalid(field.getName(), "元素不能超过 " + field.getMaxItems() + " 个");
                }
                List<Object> result = new ArrayList<>();
                for (Object item : items) {
                    result.add(validateArrayItem(field, item));
                }
                return result;
            }
            case ParamField.TYPE_OBJECT: {
                if (!(value instanceof Map)) {
                    throw invalid(field.getName(), "必须是对象");
                }
                return value;
            }
            default:
                throw invalid(field.getName(), "未知的参数类型 " + field.getType());
        }
    }

    private static Object validateArrayItem(ParamField field, Object item) {
        String itemType = field.getItemType();
        if (itemType == null) {
            return item;
        }
        ParamField element = new ParamField()
                .setName(field.getName())
                .setType(itemType)
                .setChoices(field.getItemChoices());
        return validateField(element, item);
    }

    private static Number toNumber(ParamField field, Object value) {
        String text = String.valueOf(value);
        if (!NumberUtil.isNumber(text)) {
            throw invalid(field.getName(), "必须是数字");
        }
        return NumberUtil.toBigDecimal(text);
    }

    private static void checkRange(ParamField field, double value) {
        if (field.getMin() != null && value < field.getMin()) {
            throw invalid(field.getName(), "不能小于 " + field.getMin());
        }
        if (field.getMax() != null && value > field.getMax()) {
            throw invalid(field.getName(), "不能大于 " + field.getMax());
        }
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(ErrorCode.PARAMS_ERROR, field + "：" + message);
    }
}
