package com.zys.backend.agent.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zys.backend.agent.model.vo.PictureVersionVO;
import com.zys.backend.constant.AgentConstant;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.mapper.PictureEditSessionMapper;
import com.zys.backend.mapper.PictureMapper;
import com.zys.backend.mapper.PictureVersionMapper;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.PictureAgentAsset;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.entity.PictureVersion;
import com.zys.backend.model.entity.Space;
import com.zys.backend.model.entity.User;
import com.zys.backend.model.enums.AgentAssetKindEnum;
import com.zys.backend.model.enums.EditSessionStatusEnum;
import com.zys.backend.model.enums.PictureVersionSourceEnum;
import com.zys.backend.service.SpaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 版本提交服务：最终草稿显式提交为图片正式版本。
 *
 * <p>规则：Agent 结果默认只是草稿；覆盖正式图片必须携带 expectedEditVersion 走乐观锁；
 * 版本冲突时会话进入 CONFLICT，草稿保留；恢复历史版本是创建新版本而非回退版本号；
 * 提交事务失败后清理孤儿 COS 对象，清理失败写 Outbox 人工核查。</p>
 */
@Slf4j
@Service
public class AgentCommitService {

    @Resource
    private PictureMapper pictureMapper;

    @Resource
    private PictureVersionMapper versionMapper;

    @Resource
    private PictureEditSessionMapper sessionMapper;

    @Resource
    private SpaceService spaceService;

    @Resource
    private AgentAssetService assetService;

    @Resource
    private AgentStorageService storageService;

    @Resource
    private IntegrationOutboxService outboxService;

    @Resource
    private EditLeaseService editLeaseService;

    @Resource
    private AgentAuthService authService;

    @Resource
    private TransactionTemplate transactionTemplate;

    /**
     * 提交最终草稿为正式版本
     */
    public PictureVersionVO commit(PictureEditSession session, Long finalAssetId,
                                   Long expectedEditVersion, User loginUser) {
        // 幂等：已提交过直接返回既有版本
        if (EditSessionStatusEnum.COMMITTED.getValue().equals(session.getStatus())
                && session.getCommittedVersionId() != null) {
            PictureVersion existing = versionMapper.selectById(session.getCommittedVersionId());
            if (existing != null) {
                return PictureVersionVO.from(existing);
            }
        }
        if (!EditSessionStatusEnum.ACTIVE.getValue().equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "会话已结束，无法提交");
        }
        Long assetId = finalAssetId != null ? finalAssetId : session.getFinalAssetId();
        if (assetId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请先选定最终草稿");
        }
        if (expectedEditVersion == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "缺少 expectedEditVersion，无法提交");
        }
        PictureAgentAsset asset = assetService.getInSession(session.getId(), assetId);
        if (AgentAssetKindEnum.MASK.getValue().equals(asset.getKind())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "mask 资产不能作为正式版本");
        }
        Picture picture = pictureMapper.selectById(session.getPictureId());
        if (picture == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        }
        long currentVersion = picture.getEditVersion() == null ? 0L : picture.getEditVersion();
        if (expectedEditVersion != currentVersion) {
            markConflict(session.getId());
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "图片正式版本已被他人更新，会话进入冲突状态；草稿已保留，请重新创建会话后再提交");
        }

        byte[] bytes = assetService.loadBytes(asset);
        // 空间容量预检，避免无谓的 COS 写入
        checkSpaceCapacity(picture.getSpaceId(), picture.getPicSize(), bytes.length);
        StoredObject stored = storageService.storeVersion(picture.getId(),
                detectFormat(bytes, StrUtil.subAfter(asset.getUrl(), '.', true)), bytes);

        try {
            PictureVersion version = transactionTemplate.execute(status -> {
                PictureVersion created = insertVersion(picture.getId(), stored,
                        PictureVersionSourceEnum.AGENT.getValue(), session.getId(), null, loginUser.getId());
                boolean updated = pictureMapper.update(null, Wrappers.<Picture>lambdaUpdate()
                        .eq(Picture::getId, picture.getId())
                        .eq(Picture::getEditVersion, expectedEditVersion)
                        .set(Picture::getUrl, stored.getUrl())
                        .set(Picture::getThumbnailUrl, stored.getThumbnailUrl())
                        .set(Picture::getPicSize, stored.getSizeBytes())
                        .set(Picture::getPicWidth, stored.getWidth())
                        .set(Picture::getPicHeight, stored.getHeight())
                        .set(Picture::getPicScale, scaleOf(stored.getWidth(), stored.getHeight()))
                        .set(Picture::getPicFormat, stored.getFormat())
                        .set(Picture::getEditVersion, expectedEditVersion + 1)
                        .set(Picture::getCurrentVersionId, created.getId())
                        .set(Picture::getEditTime, new Date())) > 0;
                if (!updated) {
                    // 乐观锁失败：事务回滚，外层负责清理孤儿对象
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "VERSION_CONFLICT");
                }
                applySpaceDelta(picture.getSpaceId(), picture.getPicSize(), stored.getSizeBytes());
                PictureEditSession sessionUpdate = new PictureEditSession();
                sessionUpdate.setId(session.getId());
                sessionUpdate.setStatus(EditSessionStatusEnum.COMMITTED.getValue());
                sessionUpdate.setCommittedVersionId(created.getId());
                sessionUpdate.setFinalAssetId(asset.getId());
                sessionUpdate.setEditTime(new Date());
                sessionMapper.updateById(sessionUpdate);
                outboxService.createVectorRebuild(picture.getId());
                return created;
            });
            editLeaseService.release(session.getPictureId(),
                    AgentConstant.EDIT_LOCK_MODE_AGENT, loginUser.getId(), session.getId());
            return PictureVersionVO.from(version);
        } catch (BusinessException e) {
            if ("VERSION_CONFLICT".equals(e.getMessage())) {
                markConflict(session.getId());
                cleanupOrphan(stored, "版本冲突回滚");
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        "图片正式版本已被他人更新，会话进入冲突状态；草稿已保留");
            }
            cleanupOrphan(stored, "提交事务失败");
            throw e;
        } catch (Exception e) {
            log.error("提交正式版本失败，sessionId={}", session.getId(), e);
            cleanupOrphan(stored, "提交事务异常");
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "提交正式版本失败，请重试");
        }
    }

    /**
     * 恢复历史版本：基于旧版本内容创建一个新版本
     */
    public PictureVersionVO restore(Picture picture, Long versionId, Long expectedEditVersion, User loginUser) {
        authService.requireEdit(loginUser, picture);
        if (expectedEditVersion == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "缺少 expectedEditVersion，无法恢复");
        }
        PictureVersion source = versionMapper.selectById(versionId);
        if (source == null || !picture.getId().equals(source.getPictureId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "版本不存在");
        }
        long currentVersion = picture.getEditVersion() == null ? 0L : picture.getEditVersion();
        if (expectedEditVersion != currentVersion) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "图片正式版本已被他人更新，请刷新后重试");
        }
        byte[] bytes = storageService.load(source.getUrl());
        checkSpaceCapacity(picture.getSpaceId(), picture.getPicSize(), bytes.length);
        StoredObject stored = storageService.storeVersion(picture.getId(),
                detectFormat(bytes, StrUtil.subAfter(source.getUrl(), '.', true)), bytes);
        try {
            PictureVersion version = transactionTemplate.execute(status -> {
                PictureVersion created = insertVersion(picture.getId(), stored,
                        PictureVersionSourceEnum.RESTORE.getValue(), source.getSourceSessionId(),
                        source.getId(), loginUser.getId());
                boolean updated = pictureMapper.update(null, Wrappers.<Picture>lambdaUpdate()
                        .eq(Picture::getId, picture.getId())
                        .eq(Picture::getEditVersion, expectedEditVersion)
                        .set(Picture::getUrl, stored.getUrl())
                        .set(Picture::getThumbnailUrl, stored.getThumbnailUrl())
                        .set(Picture::getPicSize, stored.getSizeBytes())
                        .set(Picture::getPicWidth, stored.getWidth())
                        .set(Picture::getPicHeight, stored.getHeight())
                        .set(Picture::getPicScale, scaleOf(stored.getWidth(), stored.getHeight()))
                        .set(Picture::getPicFormat, stored.getFormat())
                        .set(Picture::getEditVersion, expectedEditVersion + 1)
                        .set(Picture::getCurrentVersionId, created.getId())
                        .set(Picture::getEditTime, new Date())) > 0;
                if (!updated) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "VERSION_CONFLICT");
                }
                applySpaceDelta(picture.getSpaceId(), picture.getPicSize(), stored.getSizeBytes());
                outboxService.createVectorRebuild(picture.getId());
                return created;
            });
            return PictureVersionVO.from(version);
        } catch (BusinessException e) {
            cleanupOrphan(stored, "VERSION_CONFLICT".equals(e.getMessage()) ? "恢复版本冲突回滚" : "恢复事务失败");
            if ("VERSION_CONFLICT".equals(e.getMessage())) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "图片正式版本已被他人更新，请刷新后重试");
            }
            throw e;
        } catch (Exception e) {
            log.error("恢复版本失败，pictureId={}，versionId={}", picture.getId(), versionId, e);
            cleanupOrphan(stored, "恢复事务异常");
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "恢复版本失败，请重试");
        }
    }

    /**
     * 版本列表（新→旧）
     */
    public List<PictureVersionVO> listVersions(Picture picture) {
        List<PictureVersion> versions = versionMapper.selectList(Wrappers.<PictureVersion>lambdaQuery()
                .eq(PictureVersion::getPictureId, picture.getId())
                .orderByDesc(PictureVersion::getVersionNo));
        List<PictureVersionVO> result = new ArrayList<>();
        for (PictureVersion version : versions) {
            result.add(PictureVersionVO.from(version));
        }
        return result;
    }

    private PictureVersion insertVersion(Long pictureId, StoredObject stored, String source,
                                         Long sessionId, Long sourceVersionOrRunId, Long operatorId) {
        PictureVersion latest = versionMapper.selectOne(Wrappers.<PictureVersion>lambdaQuery()
                .eq(PictureVersion::getPictureId, pictureId)
                .orderByDesc(PictureVersion::getVersionNo)
                .last("limit 1"));
        int nextNo = (latest == null || latest.getVersionNo() == null ? 0 : latest.getVersionNo()) + 1;
        PictureVersion version = new PictureVersion();
        version.setPictureId(pictureId);
        version.setVersionNo(nextNo);
        version.setUrl(stored.getUrl());
        version.setThumbnailUrl(stored.getThumbnailUrl());
        version.setPicSize(stored.getSizeBytes());
        version.setPicWidth(stored.getWidth());
        version.setPicHeight(stored.getHeight());
        version.setPicFormat(stored.getFormat());
        version.setSource(source);
        version.setSourceSessionId(sessionId);
        if (PictureVersionSourceEnum.RESTORE.getValue().equals(source)) {
            // restore 场景记录来源版本 id，便于追溯
            version.setSourceRunId(null);
        } else {
            version.setSourceRunId(sourceVersionOrRunId);
        }
        version.setOperatorId(operatorId);
        version.setCreateTime(new Date());
        versionMapper.insert(version);
        return version;
    }

    private void checkSpaceCapacity(Long spaceId, Long oldSize, long newSize) {
        if (spaceId == null) {
            return;
        }
        Space space = spaceService.getById(spaceId);
        if (space == null) {
            return;
        }
        long oldBytes = oldSize == null ? 0L : oldSize;
        long total = (space.getTotalSize() == null ? 0L : space.getTotalSize()) - oldBytes + newSize;
        if (space.getMaxSize() != null && space.getMaxSize() > 0 && total > space.getMaxSize()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "空间容量不足，提交后总大小将超出上限");
        }
    }

    private void applySpaceDelta(Long spaceId, Long oldSize, long newSize) {
        if (spaceId == null) {
            return;
        }
        long delta = newSize - (oldSize == null ? 0L : oldSize);
        spaceService.lambdaUpdate()
                .eq(Space::getId, spaceId)
                .setSql("totalSize = GREATEST(totalSize + (" + delta + "), 0)")
                .update();
    }

    private void markConflict(Long sessionId) {
        PictureEditSession update = new PictureEditSession();
        update.setId(sessionId);
        update.setStatus(EditSessionStatusEnum.CONFLICT.getValue());
        update.setEditTime(new Date());
        sessionMapper.updateById(update);
    }

    /**
     * 清理事务失败后的孤儿正式对象；清理失败写 Outbox 人工核查
     */
    private void cleanupOrphan(StoredObject stored, String reason) {
        if (stored == null) {
            return;
        }
        boolean ok = storageService.deleteQuietly(stored.getStorageKey());
        if (!ok) {
            outboxService.createOrphanCleanup(stored.getStorageKey(), reason);
        }
    }

    private Double scaleOf(Integer width, Integer height) {
        if (width == null || height == null || height == 0) {
            return null;
        }
        return Math.round(width * 100.0 / height) / 100.0;
    }

    private String detectFormat(byte[] bytes, String fallback) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image != null) {
                String[] formatNames = ImageIO.getReaderFormatNames();
                // PNG/JPG 场景下用魔数判断更稳妥
                if (bytes.length > 3 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
                    return "png";
                }
                if (bytes.length > 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
                    return "jpg";
                }
                for (String name : formatNames) {
                    if ("webp".equalsIgnoreCase(name)) {
                        // 无法进一步区分时按扩展名兜底
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
            // 检测失败走扩展名兜底
        }
        return StrUtil.blankToDefault(fallback, "png");
    }
}
