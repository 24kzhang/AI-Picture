package com.zys.backend.agent.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import java.util.Locale;

/** Converts provider failures to actionable messages without exposing response bodies or credentials. */
public final class PlannerFailureMessage {
    private PlannerFailureMessage() {}

    public static String describe(int status, String body) {
        String code = "";
        try {
            JSONObject root = JSONUtil.parseObj(body);
            JSONObject error = root.getJSONObject("error");
            if (error != null) code = error.getStr("code", error.getStr("type", ""));
            else code = root.getStr("code", "");
        } catch (Exception ignored) { /* Non-JSON gateway responses use the HTTP status. */ }
        code = code.toLowerCase(Locale.ROOT);
        if (code.contains("arrearage") || code.contains("insufficient_quota"))
            return "规划模型账号欠费或额度不足，请充值或更换可用的模型配置";
        if (status == 401 || code.contains("invalidapikey") || code.contains("invalid_api_key"))
            return "规划模型密钥无效，请在管理页更新模型配置";
        if (status == 403) return "规划模型访问被拒绝，请检查账号权限和模型配置";
        if (status == 429) return "规划模型请求受限，请稍后重试";
        return "规划模型请求失败（HTTP " + status + "），请检查模型配置或稍后重试";
    }
}
