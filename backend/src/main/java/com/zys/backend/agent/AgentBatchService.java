package com.zys.backend.agent;

import cn.hutool.json.JSONUtil;
import com.zys.backend.agent.dto.AgentApiRequests;
import com.zys.backend.agent.dto.AgentAssetDTO;
import com.zys.backend.agent.dto.AgentRunDTO;
import com.zys.backend.agent.vo.AgentBatchVO;
import com.zys.backend.agent.vo.PictureVersionVO;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.exception.ThrowUtils;
import com.zys.backend.manager.CosStorageManager;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.User;
import com.zys.backend.model.enums.PictureVersionSourceEnum;
import com.zys.backend.service.PictureService;
import com.zys.backend.service.UserService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Agent 批量处理：创建批量任务、逐项确认替换原图、逐项失败隔离。
 * <p>映射关系存放 Redis（batchId → 图片/素材/基准版本），确认时逐张走乐观锁提交，
 * 冲突项保持草稿并单独上报，不允许把部分失败伪装成整体成功。</p>
 */
@Slf4j
@Service
public class AgentBatchService {

    private static final String KEY_PREFIX = "agent:batch:";
    private static final long TTL_DAYS = 7;
    private static final int MAX_BATCH = 20;
    private static final Map<String, Boolean> ALLOWED_OPERATIONS = new LinkedHashMap<>();

    static {
        ALLOWED_OPERATIONS.put("remove_background", true);
        ALLOWED_OPERATIONS.put("replace_background", true);
        ALLOWED_OPERATIONS.put("adjust_image", true);
        ALLOWED_OPERATIONS.put("upscale_image", true);
        ALLOWED_OPERATIONS.put("expand_canvas", true);
        ALLOWED_OPERATIONS.put("prepare_delivery_sizes", true);
    }

    @Resource
    private PictureService pictureService;

    @Resource
    private UserService userService;

    @Resource
    private RetouchAgentClient agentClient;

    @Resource
    private AgentPicturePermissionChecker permissionChecker;

    @Resource
    private AgentCommitService commitService;

    @Resource
    private CosStorageManager cosStorageManager;

    @Resource
    private AgentMetrics agentMetrics;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${gallery.retouch.public-base-url:http://localhost:8080}")
    private String publicBaseUrl;

    /**
     * 创建批量任务：逐张校验权限、上传源图、提交 Agent 批量运行
     */
    public AgentBatchVO createBatch(AgentApiRequests.BatchCreateRequest request, User loginUser) {
        ThrowUtils.throwIf(!agentClient.isEnabled(), ErrorCode.OPERATION_ERROR, "Agent 智能精修未开启");
        request.validate();
        List<Long> pictureIds = request.getPictureIds();
        ThrowUtils.throwIf(pictureIds.size() > MAX_BATCH, ErrorCode.PARAMS_ERROR,
                "批量最多 " + MAX_BATCH + " 张");
        for (String operation : request.getOperations()) {
            ThrowUtils.throwIf(!ALLOWED_OPERATIONS.containsKey(operation), ErrorCode.PARAMS_ERROR,
                    "不支持的批量操作：" + operation);
        }
        String formats = request.getFormats() == null || request.getFormats().isEmpty()
                ? "png" : String.join(",", request.getFormats());

        // 逐张校验权限（不能只检查第一张）
        List<Picture> pictures = new ArrayList<>();
        for (Long pictureId : pictureIds) {
            pictures.add(permissionChecker.checkPictureEditable(loginUser, pictureId));
        }

        // 逐张上传源图，建立 Agent 素材 → 云图库图片映射
        BatchMapping mapping = new BatchMapping();
        mapping.setUserId(loginUser.getId());
        mapping.setCreatedAt(System.currentTimeMillis());
        List<String> assetIds = new ArrayList<>();
        AgentCallContext context = AgentCallContext.builder()
                .userId(loginUser.getId())
                .spaceId(pictures.get(0).getSpaceId())
                .permissions(permissionChecker.permissionsOf(loginUser, pictures.get(0)))
                .build();
        for (Picture picture : pictures) {
            String assetId = uploadSourceAsset(picture, context);
            assetIds.add(assetId);
            BatchItem item = new BatchItem();
            item.setPictureId(picture.getId());
            item.setPictureName(picture.getName());
            item.setAgentAssetId(assetId);
            item.setBaseEditVersion(picture.getEditVersion() == null ? 0L : picture.getEditVersion());
            mapping.getItems().add(item);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("asset_ids", assetIds);
        // Agent 批量接口要求 operations 为 [{tool, params}] 结构
        List<Map<String, Object>> operations = new ArrayList<>();
        for (String operation : request.getOperations()) {
            Map<String, Object> operationPayload = new HashMap<>();
            operationPayload.put("tool", operation);
            operationPayload.put("params", new HashMap<String, Object>());
            operations.add(operationPayload);
        }
        payload.put("operations", operations);
        payload.put("formats", formats.split(","));
        AgentRunDTO run = agentClient.createBatch(payload, context);
        ThrowUtils.throwIf(run == null || run.getId() == null,
                ErrorCode.OPERATION_ERROR, "创建批量任务失败");

        stringRedisTemplate.opsForValue().set(KEY_PREFIX + run.getId(),
                JSONUtil.toJsonStr(mapping), TTL_DAYS, TimeUnit.DAYS);
        return toVO(run, mapping, null);
    }

    /**
     * 查询批量任务状态
     */
    public AgentBatchVO getBatch(String batchId, User loginUser) {
        BatchMapping mapping = loadMapping(batchId, loginUser);
        AgentRunDTO run = agentClient.getRun(batchId, contextOf(loginUser, mapping));
        AgentBatchVO vo = toVO(run, mapping, null);
        vo.setCanceled(Boolean.TRUE.equals(mapping.getCanceled()));
        return vo;
    }

    /**
     * 逐项确认：成功项替换原图，冲突/失败项单独上报
     */
    public List<AgentBatchVO.AgentBatchItemVO> confirmBatch(String batchId, User loginUser) {
        BatchMapping mapping = loadMapping(batchId, loginUser);
        ThrowUtils.throwIf(Boolean.TRUE.equals(mapping.getCanceled()),
                ErrorCode.OPERATION_ERROR, "批量任务已取消，无法确认");
        AgentCallContext context = contextOf(loginUser, mapping);
        AgentRunDTO run = agentClient.getRun(batchId, context);
        ThrowUtils.throwIf(run == null, ErrorCode.NOT_FOUND_ERROR, "批量任务不存在");
        if ("failed".equals(run.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "批量任务整体失败，无可提交结果");
        }
        ThrowUtils.throwIf(!"succeeded".equals(run.getStatus()),
                ErrorCode.OPERATION_ERROR, "批量任务尚未完成，当前状态：" + run.getStatus());

        Map<String, Map<String, Object>> agentItems = indexAgentItems(run);
        List<AgentBatchVO.AgentBatchItemVO> results = new ArrayList<>();
        for (BatchItem item : mapping.getItems()) {
            AgentBatchVO.AgentBatchItemVO itemResult =
                    confirmItem(item, agentItems.get(item.getAgentAssetId()), context, loginUser);
            agentMetrics.recordBatchItem(itemResult.getStatus());
            results.add(itemResult);
        }
        return results;
    }

    /**
     * 取消批量任务：标记取消并禁止确认。
     * Agent 侧运行无法中途中断，已完成的结果仍保留为草稿。
     */
    public Boolean cancelBatch(String batchId, User loginUser) {
        BatchMapping mapping = loadMapping(batchId, loginUser);
        mapping.setCanceled(true);
        stringRedisTemplate.opsForValue().set(KEY_PREFIX + batchId,
                JSONUtil.toJsonStr(mapping), TTL_DAYS, TimeUnit.DAYS);
        return true;
    }

    /**
     * 读取批量映射（含归属校验）
     */
    public BatchMapping loadMapping(String batchId, User loginUser) {
        ThrowUtils.throwIf(batchId == null || batchId.isBlank(), ErrorCode.PARAMS_ERROR, "缺少批量任务 ID");
        String json = stringRedisTemplate.opsForValue().get(KEY_PREFIX + batchId);
        ThrowUtils.throwIf(json == null, ErrorCode.NOT_FOUND_ERROR, "批量任务不存在或已过期");
        BatchMapping mapping = JSONUtil.toBean(json, BatchMapping.class);
        if (!mapping.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "仅任务创建者可操作该批量任务");
        }
        return mapping;
    }

    private AgentBatchVO.AgentBatchItemVO confirmItem(BatchItem item,
                                                      Map<String, Object> agentItem,
                                                      AgentCallContext context,
                                                      User loginUser) {
        AgentBatchVO.AgentBatchItemVO vo = new AgentBatchVO.AgentBatchItemVO();
        vo.setPictureId(item.getPictureId());
        vo.setPictureName(item.getPictureName());
        vo.setSourceAssetId(item.getAgentAssetId());
        vo.setBaseEditVersion(item.getBaseEditVersion());
        if (agentItem == null) {
            vo.setStatus("failed");
            vo.setError("批量结果缺少该图片的处理产物");
            return vo;
        }
        String status = String.valueOf(agentItem.get("status"));
        @SuppressWarnings("unchecked")
        List<String> outputIds = (List<String>) agentItem.getOrDefault("output_ids", new ArrayList<>());
        vo.setOutputAssetIds(outputIds);
        if (!"succeeded".equals(status) || outputIds.isEmpty()) {
            vo.setStatus("failed");
            vo.setError(agentItem.get("error") == null ? "处理失败" : String.valueOf(agentItem.get("error")));
            return vo;
        }
        try {
            Picture picture = pictureService.getById(item.getPictureId());
            ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
            // 版本冲突：与创建批量任务时的基准版本比对（保持草稿，单独上报）
            Long currentVersion = picture.getEditVersion() == null ? 0L : picture.getEditVersion();
            if (!currentVersion.equals(item.getBaseEditVersion())) {
                vo.setStatus("conflict");
                vo.setError("图片已被他人更新，请刷新后重试");
                return vo;
            }
            AgentAssetDTO asset = agentClient.getAsset(outputIds.get(0), context);
            ThrowUtils.throwIf(asset == null || asset.getUrl() == null,
                    ErrorCode.OPERATION_ERROR, "产物地址不可用");
            PictureVersionVO version = commitService.commitAssetVersion(picture, asset.getUrl(),
                    PictureVersionSourceEnum.AGENT, null, null, item.getBaseEditVersion(), loginUser);
            vo.setStatus("succeeded");
            vo.setVersionId(version.getId());
            vo.setVersionNo(version.getVersionNo());
            vo.getOutputUrls().add(asset.getUrl());
            return vo;
        } catch (BusinessException e) {
            vo.setStatus("failed");
            vo.setError(e.getMessage());
            return vo;
        } catch (Exception e) {
            log.warn("批量确认单项失败，pictureId={}：{}", item.getPictureId(), e.getMessage());
            vo.setStatus("failed");
            vo.setError("提交失败：" + e.getMessage());
            return vo;
        }
    }

    private Map<String, Map<String, Object>> indexAgentItems(AgentRunDTO run) {
        Map<String, Map<String, Object>> index = new HashMap<>();
        Object raw = run.getResult() == null ? null : run.getResult().get("items");
        if (!(raw instanceof List)) {
            return index;
        }
        for (Object entry : (List<?>) raw) {
            if (!(entry instanceof Map)) {
                continue;
            }
            Map<?, ?> map = (Map<?, ?>) entry;
            Object sourceId = map.get("source_id");
            if (sourceId == null) {
                continue;
            }
            Map<String, Object> typed = new HashMap<>();
            for (Map.Entry<?, ?> field : map.entrySet()) {
                typed.put(String.valueOf(field.getKey()), field.getValue());
            }
            index.put(String.valueOf(sourceId), typed);
        }
        return index;
    }

    private AgentBatchVO toVO(AgentRunDTO run, BatchMapping mapping, List<AgentBatchVO.AgentBatchItemVO> results) {
        AgentBatchVO vo = new AgentBatchVO();
        if (run != null) {
            vo.setBatchId(run.getId());
            vo.setStatus(run.getStatus());
            vo.setProgress(run.getProgress());
            vo.setStage(run.getStage());
            vo.setError(run.getError());
        }
        vo.setCanceled(Boolean.TRUE.equals(mapping.getCanceled()));
        if (results != null) {
            vo.setItems(results);
            return vo;
        }
        Map<String, Map<String, Object>> agentItems = run == null ? Map.of() : indexAgentItems(run);
        for (BatchItem item : mapping.getItems()) {
            AgentBatchVO.AgentBatchItemVO itemVO = new AgentBatchVO.AgentBatchItemVO();
            itemVO.setPictureId(item.getPictureId());
            itemVO.setPictureName(item.getPictureName());
            itemVO.setSourceAssetId(item.getAgentAssetId());
            itemVO.setBaseEditVersion(item.getBaseEditVersion());
            Map<String, Object> agentItem = agentItems.get(item.getAgentAssetId());
            if (agentItem == null) {
                itemVO.setStatus("pending");
            } else {
                itemVO.setStatus(String.valueOf(agentItem.get("status")));
                itemVO.setError(agentItem.get("error") == null ? null : String.valueOf(agentItem.get("error")));
                @SuppressWarnings("unchecked")
                List<String> outputs = (List<String>) agentItem.getOrDefault("output_ids", new ArrayList<>());
                itemVO.setOutputAssetIds(outputs);
            }
            vo.getItems().add(itemVO);
        }
        return vo;
    }

    private String uploadSourceAsset(Picture picture, AgentCallContext context) {
        try (CosStorageManager.ManagedImageFile file = cosStorageManager.materialize(picture.getUrl())) {
            byte[] data = Files.readAllBytes(file.getPath());
            String filename = picture.getName() == null ? "source.png" : picture.getName();
            AgentAssetDTO asset = agentClient.uploadAsset(data, filename, probeContentType(filename), context);
            ThrowUtils.throwIf(asset == null || asset.getId() == null,
                    ErrorCode.OPERATION_ERROR, "上传源图失败：" + picture.getName());
            return asset.getId();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "读取源图失败：" + e.getMessage());
        }
    }

    private String probeContentType(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    private AgentCallContext contextOf(User loginUser, BatchMapping mapping) {
        return AgentCallContext.builder()
                .userId(loginUser.getId())
                .requestId("batch-" + mapping.getCreatedAt())
                .build();
    }

    /**
     * 批量映射（Redis）
     */
    @Data
    public static class BatchMapping {

        private Long userId;

        private Long createdAt;

        private Boolean canceled = false;

        private List<BatchItem> items = new ArrayList<>();
    }

    /**
     * 批量单项映射
     */
    @Data
    public static class BatchItem {

        private Long pictureId;

        private String pictureName;

        private String agentAssetId;

        private Long baseEditVersion;
    }
}
