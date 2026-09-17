package com.zys.backend.agent.tool.impl;

import cn.hutool.json.JSONObject;
import com.zys.backend.agent.provider.AgentImageProviderRouter;
import com.zys.backend.agent.service.AgentAssetService;
import com.zys.backend.agent.service.AgentStorageService;
import com.zys.backend.agent.service.LayerDocumentService;
import com.zys.backend.agent.service.SelectionService;
import com.zys.backend.agent.tool.AgentToolException;
import com.zys.backend.agent.tool.ToolRunContext;
import com.zys.backend.model.entity.PictureAgentAsset;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.enums.AgentAssetKindEnum;

import javax.annotation.Resource;

/**
 * 会话内工具的公共基类：取会话/文档/图层字节、保存资产等复用逻辑。
 */
public abstract class SessionToolBase {

    @Resource
    protected LayerDocumentService documentService;

    @Resource
    protected AgentAssetService assetService;

    @Resource
    protected SelectionService selectionService;

    @Resource
    protected AgentImageProviderRouter imageProvider;

    @Resource
    protected AgentStorageService storageService;

    protected PictureEditSession session(ToolRunContext ctx) {
        PictureEditSession session = ctx.getSession();
        if (session == null) {
            throw new AgentToolException("此工具需要在编辑会话中使用");
        }
        return session;
    }

    protected JSONObject document(ToolRunContext ctx) {
        JSONObject document = documentService.parseDocument(session(ctx));
        if (document == null) {
            throw new AgentToolException("画布文档缺失，请刷新会话");
        }
        return document;
    }

    /**
     * 读取图层当前图像字节
     */
    protected byte[] layerBytes(PictureEditSession session, JSONObject layer) {
        Long assetId = LayerDocumentService.parseAssetId(layer.get("assetId"));
        if (assetId != null) {
            PictureAgentAsset asset = assetService.getInSession(session.getId(), assetId);
            return assetService.loadBytes(asset);
        }
        String url = layer.getStr("assetUrl");
        if (url != null && !url.isEmpty()) {
            PictureAgentAsset stub = new PictureAgentAsset();
            stub.setEditSessionId(session.getId());
            stub.setUrl(url);
            return assetService.loadBytes(stub);
        }
        throw new AgentToolException("图层缺少图像内容");
    }

    /**
     * 当前画布整体字节（拍平）
     */
    protected byte[] canvasBytes(PictureEditSession session) {
        return documentService.currentCanvasBytes(session);
    }

    /**
     * 保存工具产出资产
     */
    protected PictureAgentAsset save(PictureEditSession session, AgentAssetKindEnum kind, String source, byte[] bytes) {
        return assetService.createFromBytes(session.getId(), session.getPictureId(), session.getUserId(),
                kind.getValue(), source, "png", bytes);
    }

    /**
     * 把新资产写回指定图层（保留位置与尺寸）
     */
    protected void rewriteLayer(JSONObject layer, PictureAgentAsset asset) {
        layer.set("assetId", String.valueOf(asset.getId()));
        layer.set("assetUrl", asset.getUrl());
        layer.set("storageKey", asset.getStorageKey());
    }
}
