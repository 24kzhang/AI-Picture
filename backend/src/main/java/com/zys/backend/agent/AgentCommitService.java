package com.zys.backend.agent;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zys.backend.agent.dto.AgentAssetDTO;
import com.zys.backend.agent.dto.AgentSessionDetailDTO;
import com.zys.backend.agent.vo.AgentSessionVO;
import com.zys.backend.agent.vo.PictureVersionVO;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.exception.ThrowUtils;
import com.zys.backend.manager.CosStorageManager;
import com.zys.backend.manager.auth.model.SpaceUserPermissionConstant;
import com.zys.backend.mapper.IntegrationOutboxMapper;
import com.zys.backend.mapper.PictureEditSessionMapper;
import com.zys.backend.mapper.PictureVersionMapper;
import com.zys.backend.model.dto.file.UploadPictureResult;
import com.zys.backend.model.entity.IntegrationOutbox;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.entity.PictureVersion;
import com.zys.backend.model.entity.Space;
import com.zys.backend.model.entity.User;
import com.zys.backend.model.enums.OutboxStatusEnum;
import com.zys.backend.model.enums.PictureEditSessionStatusEnum;
import com.zys.backend.model.enums.PictureVersionSourceEnum;
import com.zys.backend.service.PictureService;
import com.zys.backend.service.SpaceService;
import com.zys.backend.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent 结果提交事务：显式确认、乐观锁、版本历史与最终一致 Outbox。
 * <p>提交链路：权限与状态校验 → 下载最终草稿 → 复制到永久 COS 键 →
 * MySQL 事务（懒建初始版本、插入新版本、乐观锁更新图片、空间差额、会话置 COMMITTED、写 Outbox）。</p>
 */
@Slf4j
@Service
public class AgentCommitService {

    private static final int DOWNLOAD_TIMEOUT_MS = 30000;
    private static final int MAX_DOWNLOAD_BYTES = 20 * 1024 * 1024;

    @Resource
    private PictureService pictureService;

    @Resource
    private SpaceService spaceService;

    @Resource
    private UserService userService;

    @Resource
    private PictureEditSessionMapper editSessionMapper;

    @Resource
    private PictureVersionMapper pictureVersionMapper;

    @Resource
    private IntegrationOutboxMapper outboxMapper;

    @Resource
    private AgentPicturePermissionChecker permissionChecker;

    @Resource
    private RetouchAgentClient agentClient;

    @Resource
    private CosStorageManager cosStorageManager;

    @Resource
    private TransactionTemplate transactionTemplate;

    /**
     * 选定最终草稿，会话进入待提交状态
     */
    public AgentSessionVO setFinalAsset(Long sessionId, String assetId, User loginUser) {
        PictureEditSession record = loadSession(sessionId);
        checkOwner(record, loginUser);
        ThrowUtils.throwIf(assetId == null || assetId.isBlank(), ErrorCode.PARAMS_ERROR, "素材 id 不能为空");
        String status = record.getStatus();
        ThrowUtils.throwIf(!PictureEditSessionStatusEnum.ACTIVE.getValue().equals(status)
                        && !PictureEditSessionStatusEnum.READY_TO_COMMIT.getValue().equals(status),
                ErrorCode.OPERATION_ERROR, "会话不可用（状态：" + status + "）");

        // 验证素材属于当前会话
        AgentSessionDetailDTO detail = agentClient.getSession(record.getAgentSessionId(),
                buildContext(loginUser, record));
        boolean owned = assetId.equals(detail.getCurrentAssetId())
                || detail.getAssets().stream().anyMatch(asset -> assetId.equals(asset.getId()));
        ThrowUtils.throwIf(!owned, ErrorCode.PARAMS_ERROR, "素材不属于当前会话");

        record.setFinalAgentAssetId(assetId);
        record.setStatus(PictureEditSessionStatusEnum.READY_TO_COMMIT.getValue());
        record.setUpdateTime(new Date());
        editSessionMapper.updateById(record);
        return buildSkeletonVO(record);
    }

    /**
     * 确认并替换原图（乐观锁 + 幂等）
     */
    public PictureVersionVO commit(Long sessionId, Long expectedEditVersion, User loginUser) {
        PictureEditSession record = loadSession(sessionId);
        checkOwner(record, loginUser);

        // 幂等：已提交的会话直接返回首次提交结果
        if (PictureEditSessionStatusEnum.COMMITTED.getValue().equals(record.getStatus())) {
            PictureVersion committed = pictureVersionMapper.selectById(record.getCommittedVersionId());
            ThrowUtils.throwIf(committed == null, ErrorCode.OPERATION_ERROR, "已提交版本记录缺失");
            return toVersionVO(committed);
        }
        String status = record.getStatus();
        ThrowUtils.throwIf(!PictureEditSessionStatusEnum.ACTIVE.getValue().equals(status)
                        && !PictureEditSessionStatusEnum.READY_TO_COMMIT.getValue().equals(status),
                ErrorCode.OPERATION_ERROR, "会话不可用（状态：" + status + "）");
        ThrowUtils.throwIf(record.getFinalAgentAssetId() == null,
                ErrorCode.OPERATION_ERROR, "请先选定最终草稿");

        Picture picture = pictureService.getById(record.getPictureId());
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        List<String> permissions = permissionChecker.permissionsOf(loginUser, picture);
        ThrowUtils.throwIf(!permissions.contains(SpaceUserPermissionConstant.PICTURE_EDIT),
                ErrorCode.NO_AUTH_ERROR, "无图片编辑权限");

        // 版本冲突：保留草稿，会话标记冲突
        Long currentVersion = picture.getEditVersion() == null ? 0L : picture.getEditVersion();
        if (expectedEditVersion == null || !expectedEditVersion.equals(currentVersion)) {
            record.setStatus(PictureEditSessionStatusEnum.CONFLICT.getValue());
            record.setUpdateTime(new Date());
            editSessionMapper.updateById(record);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "图片已被他人更新，请刷新后重试");
        }

        // 懒建初始版本（V1 图片首次进入版本体系），必须先于版本号分配
        ensureInitialVersion(picture, loginUser.getId());
        // 下载最终草稿并验证可解码（版本号先分配，COS 键与版本记录保持一致）
        long versionNo = nextVersionNo(picture.getId());
        UploadPictureResult uploaded = downloadAndStoreVersion(record, picture, versionNo);
        try {
            return transactionTemplate.execute(txStatus -> {
                // 1. 插入新版本
                PictureVersion version = new PictureVersion();
                version.setPictureId(picture.getId());
                version.setVersionNo(versionNo);
                version.setUrl(uploaded.getUrl());
                version.setThumbnailUrl(uploaded.getThumbnailUrl());
                version.setPicSize(uploaded.getPicSize());
                version.setPicWidth(uploaded.getPicWidth());
                version.setPicHeight(uploaded.getPicHeight());
                version.setPicScale(uploaded.getPicScale());
                version.setPicFormat(uploaded.getPicFormat());
                version.setPicColor(uploaded.getPicColor());
                version.setSource(PictureVersionSourceEnum.AGENT.getValue());
                version.setSourceSessionId(record.getId());
                version.setOperatorId(loginUser.getId());
                version.setCreateTime(new Date());
                pictureVersionMapper.insert(version);
                // 2. 乐观锁更新图片
                LambdaUpdateWrapper<Picture> update = new LambdaUpdateWrapper<Picture>()
                        .eq(Picture::getId, picture.getId())
                        .eq(Picture::getEditVersion, currentVersion)
                        .set(Picture::getUrl, uploaded.getUrl())
                        .set(Picture::getThumbnailUrl, uploaded.getThumbnailUrl())
                        .set(Picture::getPicSize, uploaded.getPicSize())
                        .set(Picture::getPicWidth, uploaded.getPicWidth())
                        .set(Picture::getPicHeight, uploaded.getPicHeight())
                        .set(Picture::getPicScale, uploaded.getPicScale())
                        .set(Picture::getPicFormat, uploaded.getPicFormat())
                        .set(Picture::getPicColor, uploaded.getPicColor())
                        .set(Picture::getCurrentVersionId, version.getId())
                        .set(Picture::getEditTime, new Date())
                        .setSql("editVersion = editVersion + 1");
                if (!pictureService.update(update)) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "图片已被他人更新，请刷新后重试");
                }
                // 3. 空间容量差额
                if (picture.getSpaceId() != null) {
                    long diff = uploaded.getPicSize() - (picture.getPicSize() == null ? 0L : picture.getPicSize());
                    applySpaceSizeDelta(picture.getSpaceId(), diff);
                }
                // 4. 会话置 COMMITTED
                record.setStatus(PictureEditSessionStatusEnum.COMMITTED.getValue());
                record.setCommittedVersionId(version.getId());
                record.setUpdateTime(new Date());
                editSessionMapper.updateById(record);
                // 5. Outbox：版本事件 + 向量重建
                writeOutbox("PICTURE_VERSION_COMMITTED", picture.getId(), version, record, loginUser);
                writeOutbox("PICTURE_VECTOR_REINDEX_REQUIRED", picture.getId(), version, record, loginUser);
                return toVersionVO(version);
            });
        } catch (BusinessException conflict) {
            // 事务失败但 COS 已写入：孤儿对象清理
            cleanupOrphan(uploaded);
            throw conflict;
        } catch (RuntimeException e) {
            cleanupOrphan(uploaded);
            throw e;
        }
    }

    /**
     * 版本列表
     */
    public List<PictureVersionVO> listVersions(Long pictureId, User loginUser) {
        Picture picture = pictureService.getById(pictureId);
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        List<String> permissions = permissionChecker.permissionsOf(loginUser, picture);
        ThrowUtils.throwIf(!permissions.contains("picture:view"), ErrorCode.NO_AUTH_ERROR, "无权查看该图片");
        List<PictureVersion> versions = pictureVersionMapper.selectList(
                new QueryWrapper<PictureVersion>()
                        .eq("pictureId", pictureId)
                        .orderByDesc("versionNo"));
        return versions.stream().map(this::toVersionVO).collect(Collectors.toList());
    }

    /**
     * 恢复历史版本：创建新版本（不回退版本号），同样走权限与乐观锁
     */
    public PictureVersionVO restoreVersion(Long pictureId, Long versionId, Long expectedEditVersion,
                                           User loginUser) {
        Picture picture = permissionChecker.checkPictureEditable(loginUser, pictureId);
        PictureVersion source = pictureVersionMapper.selectById(versionId);
        ThrowUtils.throwIf(source == null || !pictureId.equals(source.getPictureId()),
                ErrorCode.NOT_FOUND_ERROR, "版本不存在");
        Long currentVersion = picture.getEditVersion() == null ? 0L : picture.getEditVersion();
        if (expectedEditVersion == null || !expectedEditVersion.equals(currentVersion)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "图片已被他人更新，请刷新后重试");
        }

        // 懒建初始版本后下载旧版本内容，复制到新版本目录
        ensureInitialVersion(picture, loginUser.getId());
        long versionNo = nextVersionNo(picture.getId());
        UploadPictureResult uploaded = downloadUrlAndStoreVersion(source.getUrl(), picture, versionNo);
        try {
            return transactionTemplate.execute(txStatus -> {
                PictureVersion version = new PictureVersion();
                version.setPictureId(picture.getId());
                version.setVersionNo(versionNo);
                version.setUrl(uploaded.getUrl());
                version.setThumbnailUrl(uploaded.getThumbnailUrl());
                version.setPicSize(uploaded.getPicSize());
                version.setPicWidth(uploaded.getPicWidth());
                version.setPicHeight(uploaded.getPicHeight());
                version.setPicScale(uploaded.getPicScale());
                version.setPicFormat(uploaded.getPicFormat());
                version.setPicColor(uploaded.getPicColor());
                version.setSource(PictureVersionSourceEnum.RESTORE.getValue());
                version.setOperatorId(loginUser.getId());
                version.setCreateTime(new Date());
                pictureVersionMapper.insert(version);
                LambdaUpdateWrapper<Picture> update = new LambdaUpdateWrapper<Picture>()
                        .eq(Picture::getId, picture.getId())
                        .eq(Picture::getEditVersion, currentVersion)
                        .set(Picture::getUrl, uploaded.getUrl())
                        .set(Picture::getThumbnailUrl, uploaded.getThumbnailUrl())
                        .set(Picture::getPicSize, uploaded.getPicSize())
                        .set(Picture::getPicWidth, uploaded.getPicWidth())
                        .set(Picture::getPicHeight, uploaded.getPicHeight())
                        .set(Picture::getPicScale, uploaded.getPicScale())
                        .set(Picture::getPicFormat, uploaded.getPicFormat())
                        .set(Picture::getPicColor, uploaded.getPicColor())
                        .set(Picture::getCurrentVersionId, version.getId())
                        .set(Picture::getEditTime, new Date())
                        .setSql("editVersion = editVersion + 1");
                if (!pictureService.update(update)) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "图片已被他人更新，请刷新后重试");
                }
                if (picture.getSpaceId() != null) {
                    long diff = uploaded.getPicSize() - (picture.getPicSize() == null ? 0L : picture.getPicSize());
                    applySpaceSizeDelta(picture.getSpaceId(), diff);
                }
                writeOutbox("PICTURE_VERSION_COMMITTED", picture.getId(), version, null, loginUser);
                writeOutbox("PICTURE_VECTOR_REINDEX_REQUIRED", picture.getId(), version, null, loginUser);
                return toVersionVO(version);
            });
        } catch (RuntimeException e) {
            cleanupOrphan(uploaded);
            throw e;
        }
    }

    /**
     * 下载 Agent 会话最终草稿，复制到永久版本目录
     */
    private UploadPictureResult downloadAndStoreVersion(PictureEditSession record, Picture picture,
                                                         long versionNo) {
        AgentCallContext context = AgentCallContext.builder().userId(record.getUserId()).build();
        AgentSessionDetailDTO detail = agentClient.getSession(record.getAgentSessionId(), context);
        AgentAssetDTO finalAsset = findAsset(detail, record.getFinalAgentAssetId());
        ThrowUtils.throwIf(finalAsset == null || finalAsset.getUrl() == null,
                ErrorCode.OPERATION_ERROR, "最终草稿不存在，请重试");
        return downloadUrlAndStoreVersion(finalAsset.getUrl(), picture, versionNo);
    }

    private UploadPictureResult downloadUrlAndStoreVersion(String assetUrl, Picture picture,
                                                            long versionNo) {
        Path tempFile = null;
        try {
            tempFile = downloadToTemp(assetUrl);
            String prefix = String.format("pictures/%s/%d/versions/%d",
                    picture.getSpaceId() == null ? "public" : String.valueOf(picture.getSpaceId()),
                    picture.getId(), versionNo);
            return cosStorageManager.storePicture(tempFile.toFile(), picture.getName(), prefix);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private Path downloadToTemp(String assetUrl) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(assetUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(DOWNLOAD_TIMEOUT_MS);
            connection.setReadTimeout(DOWNLOAD_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            ThrowUtils.throwIf(code != 200, ErrorCode.OPERATION_ERROR, "下载修图结果失败（HTTP " + code + "）");
            String suffix = ".png";
            String contentType = connection.getContentType();
            if (contentType != null && contentType.contains("jpeg")) {
                suffix = ".jpg";
            } else if (contentType != null && contentType.contains("webp")) {
                suffix = ".webp";
            }
            Path tempFile = Files.createTempFile("agent-commit-", suffix);
            try (InputStream input = connection.getInputStream()) {
                Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            long size = Files.size(tempFile);
            ThrowUtils.throwIf(size <= 0 || size > MAX_DOWNLOAD_BYTES,
                    ErrorCode.OPERATION_ERROR, "修图结果大小异常");
            return tempFile;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("下载 Agent 资产失败：{}", assetUrl, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "下载修图结果失败");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private AgentAssetDTO findAsset(AgentSessionDetailDTO detail, String assetId) {
        if (detail.getAssets() == null) {
            return null;
        }
        return detail.getAssets().stream()
                .filter(asset -> assetId.equals(asset.getId()))
                .findFirst()
                .orElse(null);
    }

    private void ensureInitialVersion(Picture picture, Long operatorId) {
        Long count = pictureVersionMapper.selectCount(
                new QueryWrapper<PictureVersion>().eq("pictureId", picture.getId()));
        if (count != null && count > 0) {
            return;
        }
        PictureVersion initial = new PictureVersion();
        initial.setPictureId(picture.getId());
        initial.setVersionNo(1L);
        initial.setUrl(picture.getUrl());
        initial.setThumbnailUrl(picture.getThumbnailUrl());
        initial.setPicSize(picture.getPicSize());
        initial.setPicWidth(picture.getPicWidth());
        initial.setPicHeight(picture.getPicHeight());
        initial.setPicScale(picture.getPicScale());
        initial.setPicFormat(picture.getPicFormat());
        initial.setPicColor(picture.getPicColor());
        initial.setSource(PictureVersionSourceEnum.UPLOAD.getValue());
        initial.setOperatorId(picture.getUserId() == null ? operatorId : picture.getUserId());
        initial.setCreateTime(new Date());
        pictureVersionMapper.insert(initial);
    }

    private long nextVersionNo(Long pictureId) {
        PictureVersion latest = pictureVersionMapper.selectOne(
                new QueryWrapper<PictureVersion>()
                        .eq("pictureId", pictureId)
                        .orderByDesc("versionNo")
                        .last("limit 1"));
        return latest == null ? 1L : latest.getVersionNo() + 1;
    }

    private void applySpaceSizeDelta(Long spaceId, long delta) {
        Space space = spaceService.getById(spaceId);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        if (delta > 0 && space.getMaxSize() != null && space.getMaxSize() > 0) {
            long total = space.getTotalSize() == null ? 0L : space.getTotalSize();
            ThrowUtils.throwIf(total + delta > space.getMaxSize(),
                    ErrorCode.OPERATION_ERROR, "空间容量不足，无法替换原图");
        }
        if (delta != 0) {
            spaceService.update(new LambdaUpdateWrapper<Space>()
                    .eq(Space::getId, spaceId)
                    .setSql(delta > 0
                            ? "totalSize = totalSize + " + delta
                            : "totalSize = totalSize - " + (-delta)));
        }
    }

    private void writeOutbox(String eventType, Long pictureId, PictureVersion version,
                             PictureEditSession record, User operator) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("pictureId", pictureId);
        payload.put("versionId", version.getId());
        payload.put("versionNo", version.getVersionNo());
        payload.put("url", version.getUrl());
        payload.put("source", version.getSource());
        if (record != null) {
            payload.put("editSessionId", record.getId());
        }
        IntegrationOutbox outbox = new IntegrationOutbox();
        outbox.setEventType(eventType);
        outbox.setAggregateId(pictureId);
        outbox.setPayload(JSONUtil.toJsonStr(payload));
        outbox.setStatus(OutboxStatusEnum.PENDING.getValue());
        outbox.setRetryCount(0);
        outbox.setCreateTime(new Date());
        outbox.setUpdateTime(new Date());
        outboxMapper.insert(outbox);
    }

    private void cleanupOrphan(UploadPictureResult uploaded) {
        try {
            cosStorageManager.deleteByUrl(uploaded.getUrl());
            cosStorageManager.deleteByUrl(uploaded.getThumbnailUrl());
            log.warn("提交失败，已清理孤儿对象：{}", uploaded.getUrl());
        } catch (Exception e) {
            log.error("孤儿对象清理失败，请人工核查：{}", uploaded.getUrl(), e);
        }
    }

    private PictureVersionVO toVersionVO(PictureVersion version) {
        PictureVersionVO vo = new PictureVersionVO();
        vo.setId(version.getId());
        vo.setPictureId(version.getPictureId());
        vo.setVersionNo(version.getVersionNo());
        vo.setUrl(version.getUrl());
        vo.setThumbnailUrl(version.getThumbnailUrl());
        vo.setPicSize(version.getPicSize());
        vo.setPicWidth(version.getPicWidth());
        vo.setPicHeight(version.getPicHeight());
        vo.setPicFormat(version.getPicFormat());
        vo.setPicColor(version.getPicColor());
        vo.setSource(version.getSource());
        vo.setOperatorId(version.getOperatorId());
        vo.setCreateTime(version.getCreateTime());
        return vo;
    }

    private AgentSessionVO buildSkeletonVO(PictureEditSession record) {
        AgentSessionVO vo = new AgentSessionVO();
        vo.setId(record.getId());
        vo.setAgentSessionId(record.getAgentSessionId());
        vo.setPictureId(record.getPictureId());
        vo.setSpaceId(record.getSpaceId());
        vo.setBaseEditVersion(record.getBaseEditVersion());
        vo.setStatus(record.getStatus());
        vo.setFinalAgentAssetId(record.getFinalAgentAssetId());
        vo.setCommittedVersionId(record.getCommittedVersionId());
        vo.setCreateTime(record.getCreateTime());
        vo.setUpdateTime(record.getUpdateTime());
        vo.setExpireTime(record.getExpireTime());
        vo.setReadOnly(false);
        vo.setTurns(new ArrayList<>());
        return vo;
    }

    private PictureEditSession loadSession(Long sessionId) {
        ThrowUtils.throwIf(sessionId == null || sessionId <= 0, ErrorCode.PARAMS_ERROR, "会话 id 非法");
        PictureEditSession record = editSessionMapper.selectById(sessionId);
        ThrowUtils.throwIf(record == null, ErrorCode.NOT_FOUND_ERROR, "编辑会话不存在");
        return record;
    }

    private void checkOwner(PictureEditSession record, User loginUser) {
        if (!record.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "仅会话所有者可执行该操作");
        }
    }

    private AgentCallContext buildContext(User loginUser, PictureEditSession record) {
        return AgentCallContext.builder()
                .userId(loginUser.getId())
                .pictureId(record.getPictureId())
                .spaceId(record.getSpaceId())
                .build();
    }
}
