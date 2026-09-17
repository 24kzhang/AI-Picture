package com.zys.backend.agent.tool.impl;

import cn.hutool.json.JSONObject;
import com.zys.backend.model.entity.PictureAgentAsset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具结果构造助手，键名与执行外壳的结果回写协议一致：
 * assetIds/adoptAssetId/document/variants。
 */
public final class ToolResults {

    private ToolResults() {
    }

    /**
     * 只上资产墙的结果
     */
    public static Map<String, Object> wall(List<PictureAgentAsset> assets) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assetIds", assetIds(assets));
        return result;
    }

    /**
     * 资产墙 + 自动采用第一张为画布内容
     */
    public static Map<String, Object> wallAdoptFirst(List<PictureAgentAsset> assets) {
        Map<String, Object> result = wall(assets);
        if (!assets.isEmpty()) {
            result.put("adoptAssetId", String.valueOf(assets.get(0).getId()));
        }
        return result;
    }

    /**
     * 画布文档变更结果（同步文档工具）
     */
    public static Map<String, Object> document(JSONObject document) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document", document);
        return result;
    }

    /**
     * 画布文档变更 + 新资产
     */
    public static Map<String, Object> documentWithAdopt(JSONObject document, PictureAgentAsset adopted) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document", document);
        List<String> ids = new ArrayList<>();
        if (adopted != null) {
            ids.add(String.valueOf(adopted.getId()));
            result.put("adoptAssetId", String.valueOf(adopted.getId()));
        }
        result.put("assetIds", ids);
        return result;
    }

    /**
     * 资产墙 + 变体标签（比例、尺寸）
     */
    public static Map<String, Object> wallWithVariants(List<PictureAgentAsset> assets, List<Map<String, Object>> variants) {
        Map<String, Object> result = wall(assets);
        result.put("variants", variants);
        return result;
    }

    public static List<String> assetIds(List<PictureAgentAsset> assets) {
        List<String> ids = new ArrayList<>();
        for (PictureAgentAsset asset : assets) {
            ids.add(String.valueOf(asset.getId()));
        }
        return ids;
    }
}
