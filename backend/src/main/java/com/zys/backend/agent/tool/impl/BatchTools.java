package com.zys.backend.agent.tool.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import com.zys.backend.agent.provider.EditImageRequest;
import com.zys.backend.agent.service.AgentAuthService;
import com.zys.backend.agent.tool.AgentTool;
import com.zys.backend.agent.tool.AgentToolException;
import com.zys.backend.agent.tool.ParamField;
import com.zys.backend.agent.tool.ToolRunContext;
import com.zys.backend.agent.tool.ToolRegistry;
import com.zys.backend.agent.tool.ToolSpec;
import com.zys.backend.mapper.PictureMapper;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.PictureAgentAsset;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.entity.User;
import com.zys.backend.model.enums.AgentAssetKindEnum;
import com.zys.backend.service.UserService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * batch_process 批量处理：对多张图执行同一套像素处理，结果全部上资产墙，
 * 成功结果可通过 ZIP 导出接口下载。
 */
@Component
public class BatchTools extends SessionToolBase implements AgentTool {

    private static final List<String> ALLOWED_OPERATIONS = Arrays.asList(
            "remove_background", "adjust_image", "upscale_image", "replace_background");

    @Resource
    private PictureMapper pictureMapper;

    @Resource
    private UserService userService;

    @Resource
    private AgentAuthService agentAuthService;

    @Resource
    @Lazy
    private ToolRegistry toolRegistry;

    @Override
    public List<ToolSpec> specs() {
        ParamField pictureIds = ParamField.array("pictureIds", ParamField.TYPE_STRING)
                .setRequired(true)
                .setMinItems(1)
                .setMaxItems(20)
                .setDescription("要处理的图片 id 列表，最多 20 张");
        ParamField operations = ParamField.array("operations", ParamField.TYPE_OBJECT)
                .setRequired(true)
                .setMinItems(1)
                .setMaxItems(4)
                .setDescription("要执行的处理步骤，每项 {tool, params}，"
                        + "tool 可选 remove_background/adjust_image/upscale_image/replace_background");
        return Collections.singletonList(new ToolSpec()
                .setName("batch_process")
                .setLabel("批量处理")
                .setDescription("对多张图执行同一套像素处理：去背景、换背景、调色、超分。"
                        + "不要用于单张精修，也不要做营销图、局部编辑或拆层。")
                .setQueued(true)
                .setSessionRequired(true)
                .setParams(Arrays.asList(pictureIds, operations))
                .setCrossValidator(this::crossCheck)
                .setHandler(this::handleBatch));
    }

    @SuppressWarnings("unchecked")
    private String crossCheck(Map<String, Object> params) {
        Object rawOps = params.get("operations");
        if (!(rawOps instanceof List)) {
            return "operations 必须是数组";
        }
        for (Object item : (List<Object>) rawOps) {
            if (!(item instanceof Map)) {
                return "operations 每项必须是 {tool, params} 对象";
            }
            Map<String, Object> op = (Map<String, Object>) item;
            String tool = op.get("tool") == null ? null : String.valueOf(op.get("tool"));
            if (tool == null || !ALLOWED_OPERATIONS.contains(tool)) {
                return "不支持的批量操作：" + tool + "，可选 " + ALLOWED_OPERATIONS;
            }
            if (!toolRegistry.contains(tool)) {
                return "工具未注册：" + tool;
            }
            Object opParams = op.get("params");
            if (opParams != null && !(opParams instanceof Map)) {
                return "operations.params 必须是对象";
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleBatch(ToolRunContext ctx, Map<String, Object> params) {
        PictureEditSession session = session(ctx);
        User loginUser = userService.getById(session.getUserId());
        if (loginUser == null) {
            throw new AgentToolException("会话用户不存在");
        }
        List<Object> rawIds = (List<Object>) params.get("pictureIds");
        List<Map<String, Object>> operations = (List<Map<String, Object>>) params.get("operations");

        List<PictureAgentAsset> assets = new ArrayList<>();
        List<Map<String, Object>> summary = new ArrayList<>();
        int done = 0;
        for (Object rawId : rawIds) {
            Long pictureId;
            try {
                pictureId = Long.valueOf(String.valueOf(rawId));
            } catch (NumberFormatException e) {
                throw new AgentToolException("非法图片 id：" + rawId);
            }
            Picture picture = pictureMapper.selectById(pictureId);
            if (picture == null) {
                throw new AgentToolException("图片不存在：" + pictureId);
            }
            agentAuthService.requireView(loginUser, picture);
            ctx.report(5 + 90 * done / rawIds.size(), "处理图片 " + (done + 1) + "/" + rawIds.size());
            byte[] bytes = loadPictureBytes(picture);
            String error = null;
            try {
                for (Map<String, Object> operation : operations) {
                    bytes = applyOperation(ctx, operation, bytes);
                }
            } catch (Exception e) {
                error = StrUtil.blankToDefault(e.getMessage(), "处理失败");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pictureId", String.valueOf(pictureId));
            item.put("pictureName", picture.getName());
            if (error == null) {
                PictureAgentAsset asset = save(session, AgentAssetKindEnum.DELIVERY, "batch_process", bytes);
                item.put("assetId", String.valueOf(asset.getId()));
                item.put("status", "succeeded");
                assets.add(asset);
            } else {
                item.put("status", "failed");
                item.put("errorMessage", error);
            }
            summary.add(item);
            done++;
        }
        ctx.report(95, "批量处理完成");
        Map<String, Object> result = ToolResults.wall(assets);
        result.put("variants", summary);
        return result;
    }

    private byte[] loadPictureBytes(Picture picture) {
        try {
            return storageService.load(picture.getUrl());
        } catch (Exception e) {
            try {
                return HttpUtil.downloadBytes(picture.getUrl());
            } catch (Exception downloadError) {
                throw new AgentToolException("图片读取失败：" + picture.getName());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private byte[] applyOperation(ToolRunContext ctx, Map<String, Object> operation, byte[] source) {
        String tool = String.valueOf(operation.get("tool"));
        Map<String, Object> opParams = operation.get("params") instanceof Map
                ? (Map<String, Object>) operation.get("params") : new LinkedHashMap<>();
        opParams = toolRegistry.validateParams(tool, opParams);
        switch (tool) {
            case "remove_background": {
                List<byte[]> outputs = imageProvider.edit(new EditImageRequest()
                        .setPrompt("去掉背景，只保留画面主体，背景区域填充纯白色")
                        .setImage(source)
                        .setCount(1), ctx.getReporter());
                return first(outputs);
            }
            case "adjust_image":
                return AgentImageOps.adjust(source, opParams);
            case "upscale_image": {
                int scale = opParams.get("scale") == null ? 2
                        : (int) Math.round(Double.parseDouble(String.valueOf(opParams.get("scale"))));
                return imageProvider.upscale(source, scale, ctx.getReporter());
            }
            case "replace_background": {
                List<byte[]> outputs = imageProvider.edit(new EditImageRequest()
                        .setPrompt("只替换背景，保持主体、光线和边缘不变。新背景：" + opParams.get("prompt"))
                        .setImage(source)
                        .setCount(1), ctx.getReporter());
                return first(outputs);
            }
            default:
                throw new AgentToolException("不支持的批量操作：" + tool);
        }
    }

    private byte[] first(List<byte[]> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            throw new AgentToolException("生成没有返回结果");
        }
        return outputs.get(0);
    }
}
