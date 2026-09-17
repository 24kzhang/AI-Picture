package com.zys.backend.agent.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zys.backend.agent.tool.AgentToolException;
import com.zys.backend.mapper.PictureEditHistoryMapper;
import com.zys.backend.mapper.PictureEditSessionMapper;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.PictureAgentAsset;
import com.zys.backend.model.entity.PictureEditHistory;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.entity.PictureToolRun;
import com.zys.backend.model.enums.AgentAssetKindEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 画布文档（LayerDocument）维护：JSON 解析、图层定位、revision、
 * 历史快照（撤销重做）、拍平渲染。
 *
 * <p>文档结构：{width, height, layers:[{id,name,kind,assetId,assetUrl,storageKey,
 * x,y,width,height,scaleX,scaleY,rotation,opacity,visible,locked,text,fontSize,fill}]}，
 * layers 首个元素为最底层。</p>
 */
@Slf4j
@Service
public class LayerDocumentService {

    public static final String BASE_LAYER_ID = "base";
    public static final String KIND_IMAGE = "image";
    public static final String KIND_TEXT = "text";

    @Resource
    private PictureEditSessionMapper sessionMapper;

    @Resource
    private PictureEditHistoryMapper historyMapper;

    @Resource
    private SelectionService selectionService;

    @Resource
    private AgentAssetService assetService;

    // region 文档读写

    /**
     * 会话初始化文档：底图为锁定 base 图层，revision=1
     */
    public JSONObject initDocument(Picture picture, PictureAgentAsset baseAsset) {
        JSONObject document = new JSONObject();
        document.set("width", picture.getPicWidth() == null ? 0 : picture.getPicWidth());
        document.set("height", picture.getPicHeight() == null ? 0 : picture.getPicHeight());
        JSONArray layers = new JSONArray();
        layers.add(imageLayer(BASE_LAYER_ID, "底图", baseAsset, 0, 0,
                picture.getPicWidth(), picture.getPicHeight(), true));
        document.set("layers", layers);
        return document;
    }

    /**
     * 由资产构造图像图层节点
     */
    public JSONObject imageLayer(String id, String name, PictureAgentAsset asset,
                                 Number x, Number y, Number width, Number height, boolean locked) {
        JSONObject layer = new JSONObject();
        layer.set("id", id);
        layer.set("name", name);
        layer.set("kind", KIND_IMAGE);
        layer.set("assetId", asset == null ? null : String.valueOf(asset.getId()));
        layer.set("assetUrl", asset == null ? null : asset.getUrl());
        layer.set("storageKey", asset == null ? null : asset.getStorageKey());
        layer.set("x", num(x, 0));
        layer.set("y", num(y, 0));
        layer.set("width", width == null ? (asset == null || asset.getWidth() == null ? 0 : asset.getWidth()) : width.doubleValue());
        layer.set("height", height == null ? (asset == null || asset.getHeight() == null ? 0 : asset.getHeight()) : height.doubleValue());
        layer.set("scaleX", 1.0);
        layer.set("scaleY", 1.0);
        layer.set("rotation", 0.0);
        layer.set("opacity", 1.0);
        layer.set("visible", true);
        layer.set("locked", locked);
        return layer;
    }

    public JSONObject parseDocument(PictureEditSession session) {
        if (session == null || StrUtil.isBlank(session.getDocumentJson())) {
            return null;
        }
        try {
            return JSONUtil.parseObj(session.getDocumentJson());
        } catch (Exception e) {
            log.error("画布文档解析失败，sessionId={}", session.getId(), e);
            throw new AgentToolException("画布文档损坏，请重新创建会话");
        }
    }

    public JSONArray layers(JSONObject document) {
        JSONArray layers = document == null ? null : document.getJSONArray("layers");
        return layers == null ? new JSONArray() : layers;
    }

    /**
     * 定位目标图层：layerId 支持 id 或名字；缺省取最上层可见图像层
     */
    public JSONObject resolveTarget(JSONObject document, String layerIdOrName) {
        JSONArray layers = layers(document);
        if (StrUtil.isNotBlank(layerIdOrName)) {
            for (int i = layers.size() - 1; i >= 0; i--) {
                JSONObject layer = layers.getJSONObject(i);
                if (layerIdOrName.equals(layer.getStr("id")) || layerIdOrName.equals(layer.getStr("name"))) {
                    return layer;
                }
            }
            throw new AgentToolException("图层不存在：" + layerIdOrName);
        }
        for (int i = layers.size() - 1; i >= 0; i--) {
            JSONObject layer = layers.getJSONObject(i);
            if (KIND_IMAGE.equals(layer.getStr("kind"))
                    && Boolean.TRUE.equals(layer.getBool("visible", true))) {
                return layer;
            }
        }
        throw new AgentToolException("画布中没有可编辑的图像图层");
    }

    /**
     * 定位目标文字图层：缺省取最上层可见文字层
     */
    public JSONObject resolveTextTarget(JSONObject document, String layerIdOrName) {
        JSONArray layers = layers(document);
        if (StrUtil.isNotBlank(layerIdOrName)) {
            return resolveTarget(document, layerIdOrName);
        }
        for (int i = layers.size() - 1; i >= 0; i--) {
            JSONObject layer = layers.getJSONObject(i);
            if (KIND_TEXT.equals(layer.getStr("kind"))
                    && Boolean.TRUE.equals(layer.getBool("visible", true))) {
                return layer;
            }
        }
        throw new AgentToolException("画布中没有文字图层");
    }

    // endregion

    // region 应用工具结果与历史

    /**
     * 应用工具执行结果：结果含 document 或 adoptAssetId 时更新画布并记录历史。
     *
     * @return 是否变更了画布
     */
    public boolean applyToolResult(PictureEditSession session, PictureToolRun run, Map<String, Object> result) {
        if (result == null) {
            return false;
        }
        JSONObject documentAfter = null;
        Object rawDocument = result.get("document");
        if (rawDocument != null) {
            documentAfter = JSONUtil.parseObj(JSONUtil.toJsonStr(rawDocument));
        }
        PictureAgentAsset adopted = null;
        List<PictureAgentAsset> produced = loadProduced(session, result);
        Object adoptId = result.get("adoptAssetId");
        if (adoptId != null) {
            String adoptText = String.valueOf(adoptId);
            for (PictureAgentAsset asset : produced) {
                if (adoptText.equals(String.valueOf(asset.getId()))) {
                    adopted = asset;
                    break;
                }
            }
        }
        if (documentAfter == null && adopted == null) {
            return false;
        }
        JSONObject currentDocument = parseDocument(session);
        if (adopted != null && documentAfter == null) {
            documentAfter = adoptIntoDocument(currentDocument, adopted);
        }
        applyEdit(session, run.getTool(), run.getParamsJson(), documentAfter, adopted, run.getId());
        return true;
    }

    /**
     * 记录一次画布编辑：revision+1、历史快照、旧选区失效
     */
    public void applyEdit(PictureEditSession session,
                          String action,
                          String paramsJson,
                          JSONObject documentAfter,
                          PictureAgentAsset currentAsset,
                          Long sourceRunId) {
        JSONObject documentBefore = parseDocument(session);
        Integer revisionBefore = session.getRevision();
        Long assetBefore = session.getCurrentAssetId();
        int nextSeq = (session.getHistorySeq() == null ? 0 : session.getHistorySeq()) + 1;

        // redo 分支存在时先截断
        historyMapper.delete(Wrappers.<PictureEditHistory>lambdaQuery()
                .eq(PictureEditHistory::getEditSessionId, session.getId())
                .gt(PictureEditHistory::getSeq, session.getHistorySeq() == null ? 0 : session.getHistorySeq()));

        PictureEditHistory history = new PictureEditHistory();
        history.setEditSessionId(session.getId());
        history.setSeq(nextSeq);
        history.setAction(StrUtil.blankToDefault(action, "edit"));
        history.setParamsJson(paramsJson);
        JSONObject snapshot = new JSONObject()
                .set("documentBefore", documentBefore)
                .set("assetBefore", assetBefore == null ? null : String.valueOf(assetBefore))
                .set("revisionBefore", revisionBefore)
                .set("documentAfter", documentAfter)
                .set("assetAfter", currentAsset == null ? null : String.valueOf(currentAsset.getId()))
                .set("sourceRunId", sourceRunId == null ? null : String.valueOf(sourceRunId));
        history.setResultJson(snapshot.toString());
        historyMapper.insert(history);

        PictureEditSession update = new PictureEditSession();
        update.setId(session.getId());
        update.setDocumentJson(documentAfter == null ? session.getDocumentJson() : documentAfter.toString());
        update.setRevision((session.getRevision() == null ? 1 : session.getRevision()) + 1);
        update.setHistorySeq(nextSeq);
        if (currentAsset != null) {
            update.setCurrentAssetId(currentAsset.getId());
        }
        sessionMapper.updateById(update);

        // 内存对象同步，供后续步骤使用
        if (documentAfter != null) {
            session.setDocumentJson(documentAfter.toString());
        }
        session.setRevision(update.getRevision());
        session.setHistorySeq(nextSeq);
        if (currentAsset != null) {
            session.setCurrentAssetId(currentAsset.getId());
        }
        selectionService.clear(session.getId());
    }

    /**
     * 把资产采用为画布内容：替换 base 层图像，画幅随资产尺寸变化
     */
    public JSONObject adoptIntoDocument(JSONObject document, PictureAgentAsset asset) {
        JSONObject next = document == null ? new JSONObject() : JSONUtil.parseObj(document.toString());
        int width = asset.getWidth() == null ? next.getInt("width", 0) : asset.getWidth();
        int height = asset.getHeight() == null ? next.getInt("height", 0) : asset.getHeight();
        next.set("width", width);
        next.set("height", height);
        JSONArray layers = new JSONArray();
        layers.add(imageLayer(BASE_LAYER_ID, "底图", asset, 0, 0, width, height, true));
        next.set("layers", layers);
        return next;
    }

    public boolean canUndo(PictureEditSession session) {
        return session.getHistorySeq() != null && session.getHistorySeq() > 0;
    }

    public boolean canRedo(PictureEditSession session) {
        if (session.getHistorySeq() == null) {
            return false;
        }
        Long count = historyMapper.selectCount(Wrappers.<PictureEditHistory>lambdaQuery()
                .eq(PictureEditHistory::getEditSessionId, session.getId())
                .eq(PictureEditHistory::getSeq, session.getHistorySeq() + 1));
        return count != null && count > 0;
    }

    /**
     * 撤销：回到上一条历史的 before 快照
     */
    public boolean undo(PictureEditSession session) {
        if (!canUndo(session)) {
            return false;
        }
        PictureEditHistory history = historyMapper.selectOne(Wrappers.<PictureEditHistory>lambdaQuery()
                .eq(PictureEditHistory::getEditSessionId, session.getId())
                .eq(PictureEditHistory::getSeq, session.getHistorySeq()));
        JSONObject snapshot = JSONUtil.parseObj(history.getResultJson());
        restoreSnapshot(session, snapshot, false);
        return true;
    }

    /**
     * 重做：前进到下一条历史的 after 快照
     */
    public boolean redo(PictureEditSession session) {
        if (!canRedo(session)) {
            return false;
        }
        PictureEditHistory history = historyMapper.selectOne(Wrappers.<PictureEditHistory>lambdaQuery()
                .eq(PictureEditHistory::getEditSessionId, session.getId())
                .eq(PictureEditHistory::getSeq, session.getHistorySeq() + 1));
        JSONObject snapshot = JSONUtil.parseObj(history.getResultJson());
        restoreSnapshot(session, snapshot, true);
        return true;
    }

    private void restoreSnapshot(PictureEditSession session, JSONObject snapshot, boolean forward) {
        JSONObject document = forward ? snapshot.getJSONObject("documentAfter") : snapshot.getJSONObject("documentBefore");
        String assetText = forward ? snapshot.getStr("assetAfter") : snapshot.getStr("assetBefore");
        Long assetId = StrUtil.isBlank(assetText) ? null : Long.valueOf(assetText);

        PictureEditSession update = new PictureEditSession();
        update.setId(session.getId());
        if (document != null) {
            update.setDocumentJson(document.toString());
            session.setDocumentJson(document.toString());
        }
        update.setCurrentAssetId(assetId);
        session.setCurrentAssetId(assetId);
        int nextRevision = (session.getRevision() == null ? 1 : session.getRevision()) + 1;
        int nextSeq = (session.getHistorySeq() == null ? 0 : session.getHistorySeq()) + (forward ? 1 : -1);
        update.setRevision(nextRevision);
        update.setHistorySeq(nextSeq);
        session.setRevision(nextRevision);
        session.setHistorySeq(nextSeq);
        sessionMapper.updateById(update);
        selectionService.clear(session.getId());
    }

    private List<PictureAgentAsset> loadProduced(PictureEditSession session, Map<String, Object> result) {
        List<PictureAgentAsset> produced = new ArrayList<>();
        Object rawIds = result.get("assetIds");
        if (!(rawIds instanceof List)) {
            return produced;
        }
        for (Object rawId : (List<?>) rawIds) {
            if (rawId == null) {
                continue;
            }
            try {
                PictureAgentAsset asset = assetService.getInSession(session.getId(), Long.valueOf(String.valueOf(rawId)));
                if (asset != null) {
                    produced.add(asset);
                }
            } catch (NumberFormatException e) {
                log.warn("忽略非法资产 id：{}", rawId);
            }
        }
        return produced;
    }

    // endregion

    // region 拍平渲染

    /**
     * 当前画布 PNG 字节：优先使用当前资产，否则按图层拍平渲染
     */
    public byte[] currentCanvasBytes(PictureEditSession session) {
        JSONObject document = parseDocument(session);
        if (document == null) {
            throw new AgentToolException("画布文档缺失");
        }
        // 单 base 图像层且无变换时直接取资产字节，避免二次有损编解码
        JSONArray layers = layers(document);
        if (layers.size() == 1) {
            JSONObject only = layers.getJSONObject(0);
            if (KIND_IMAGE.equals(only.getStr("kind"))
                    && Boolean.TRUE.equals(only.getBool("visible", true))
                    && Math.abs(only.getDouble("rotation", 0.0)) < 0.001
                    && Math.abs(only.getDouble("scaleX", 1.0) - 1.0) < 0.001
                    && Math.abs(only.getDouble("scaleY", 1.0) - 1.0) < 0.001) {
                Long assetId = parseAssetId(only.get("assetId"));
                if (assetId != null) {
                    PictureAgentAsset asset = assetService.getInSession(session.getId(), assetId);
                    return assetService.loadBytes(asset);
                }
            }
        }
        return flatten(session, document);
    }

    /**
     * 按图层顺序拍平为 PNG（白底）
     */
    public byte[] flatten(PictureEditSession session, JSONObject document) {
        int width = Math.max(1, document.getInt("width", 1));
        int height = Math.max(1, document.getInt("height", 1));
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = canvas.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        for (Object raw : layers(document)) {
            JSONObject layer = (JSONObject) raw;
            if (!Boolean.TRUE.equals(layer.getBool("visible", true))) {
                continue;
            }
            float opacity = layer.getFloat("opacity", 1.0f);
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, opacity))));
            if (KIND_IMAGE.equals(layer.getStr("kind"))) {
                drawImageLayer(session, graphics, layer);
            } else if (KIND_TEXT.equals(layer.getStr("kind"))) {
                drawTextLayer(graphics, layer);
            }
        }
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.dispose();
        return toPng(canvas);
    }

    private void drawImageLayer(PictureEditSession session, Graphics2D graphics, JSONObject layer) {
        BufferedImage image = loadLayerImage(session, layer);
        if (image == null) {
            return;
        }
        double x = layer.getDouble("x", 0.0);
        double y = layer.getDouble("y", 0.0);
        double layerWidth = layer.getDouble("width", (double) image.getWidth());
        double layerHeight = layer.getDouble("height", (double) image.getHeight());
        double scaleX = layer.getDouble("scaleX", 1.0);
        double scaleY = layer.getDouble("scaleY", 1.0);
        double rotation = Math.toRadians(layer.getDouble("rotation", 0.0));
        AffineTransform previous = graphics.getTransform();
        graphics.translate(x + layerWidth / 2, y + layerHeight / 2);
        graphics.rotate(rotation);
        graphics.scale(scaleX, scaleY);
        graphics.drawImage(image, (int) Math.round(-layerWidth / 2), (int) Math.round(-layerHeight / 2),
                (int) Math.round(layerWidth), (int) Math.round(layerHeight), null);
        graphics.setTransform(previous);
    }

    private BufferedImage loadLayerImage(PictureEditSession session, JSONObject layer) {
        try {
            Long assetId = parseAssetId(layer.get("assetId"));
            if (assetId != null) {
                PictureAgentAsset asset = assetService.getInSession(session.getId(), assetId);
                return ImageIO.read(new ByteArrayInputStream(assetService.loadBytes(asset)));
            }
            String url = layer.getStr("assetUrl");
            if (StrUtil.isNotBlank(url)) {
                return ImageIO.read(new ByteArrayInputStream(assetService.loadBytes(
                        urlAsset(session, url))));
            }
        } catch (Exception e) {
            log.warn("图层图片加载失败，sessionId={}，layerId={}", session.getId(), layer.getStr("id"), e);
        }
        return null;
    }

    private PictureAgentAsset urlAsset(PictureEditSession session, String url) {
        PictureAgentAsset asset = new PictureAgentAsset();
        asset.setEditSessionId(session.getId());
        asset.setUrl(url);
        return asset;
    }

    private void drawTextLayer(Graphics2D graphics, JSONObject layer) {
        String text = layer.getStr("text", "");
        if (StrUtil.isBlank(text)) {
            return;
        }
        int fontSize = (int) Math.round(layer.getDouble("fontSize", 24.0));
        Color color = parseColor(layer.getStr("fill", "#000000"));
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(8, fontSize)));
        graphics.setColor(color);
        int x = (int) Math.round(layer.getDouble("x", 0.0));
        int y = (int) Math.round(layer.getDouble("y", 0.0)) + Math.max(8, fontSize);
        graphics.drawString(text, x, y);
    }

    public static Color parseColor(String hex) {
        try {
            String value = hex == null ? "#000000" : hex.trim().replace("#", "");
            if (value.length() == 3) {
                value = "" + value.charAt(0) + value.charAt(0)
                        + value.charAt(1) + value.charAt(1)
                        + value.charAt(2) + value.charAt(2);
            }
            return new Color(Integer.parseInt(value, 16));
        } catch (Exception e) {
            return Color.BLACK;
        }
    }

    public static byte[] toPng(BufferedImage image) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (Exception e) {
            throw new AgentToolException("画布渲染失败", e);
        }
    }

    private static double num(Number value, double fallback) {
        return value == null ? fallback : value.doubleValue();
    }

    public static Long parseAssetId(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw);
        if (StrUtil.isBlank(text) || "null".equals(text)) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // endregion
}
