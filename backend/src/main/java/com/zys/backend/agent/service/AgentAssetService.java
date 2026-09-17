package com.zys.backend.agent.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zys.backend.agent.model.vo.AgentAssetVO;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.mapper.PictureAgentAssetMapper;
import com.zys.backend.model.entity.PictureAgentAsset;
import com.zys.backend.model.enums.AgentAssetKindEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话资产墙：候选图、营销图、mask、多尺寸交付资产的写库与查询。
 */
@Slf4j
@Service
public class AgentAssetService {

    @Resource
    private PictureAgentAssetMapper assetMapper;

    @Resource
    private AgentStorageService storageService;

    /**
     * 由字节流创建资产（写 COS + 缩略图 + 落库）
     */
    public PictureAgentAsset createFromBytes(Long sessionId,
                                             Long pictureId,
                                             Long userId,
                                             String kind,
                                             String source,
                                             String format,
                                             byte[] data) {
        StoredObject stored = storageService.storeSessionAsset(sessionId, kind, format, data);
        return createRecord(sessionId, pictureId, userId, kind, source, stored);
    }

    /**
     * 由已有存储对象创建资产记录（不重复上传）
     */
    public PictureAgentAsset createRecord(Long sessionId,
                                          Long pictureId,
                                          Long userId,
                                          String kind,
                                          String source,
                                          StoredObject stored) {
        PictureAgentAsset asset = new PictureAgentAsset();
        asset.setEditSessionId(sessionId);
        asset.setPictureId(pictureId);
        asset.setUserId(userId);
        asset.setKind(kind);
        asset.setSource(source);
        asset.setUrl(stored.getUrl());
        asset.setThumbnailUrl(stored.getThumbnailUrl());
        asset.setStorageKey(stored.getStorageKey());
        asset.setWidth(stored.getWidth());
        asset.setHeight(stored.getHeight());
        asset.setSizeBytes(stored.getSizeBytes());
        Long count = assetMapper.selectCount(Wrappers.<PictureAgentAsset>lambdaQuery()
                .eq(PictureAgentAsset::getEditSessionId, sessionId));
        asset.setPosition(count == null ? 0 : count.intValue());
        assetMapper.insert(asset);
        return asset;
    }

    /**
     * 创建引用型资产（原图直接引用 picture.url，不复制存储）
     */
    public PictureAgentAsset createReference(Long sessionId,
                                             Long pictureId,
                                             Long userId,
                                             String kind,
                                             String source,
                                             String url,
                                             Integer width,
                                             Integer height,
                                             Long sizeBytes) {
        PictureAgentAsset asset = new PictureAgentAsset();
        asset.setEditSessionId(sessionId);
        asset.setPictureId(pictureId);
        asset.setUserId(userId);
        asset.setKind(kind);
        asset.setSource(source);
        asset.setUrl(url);
        asset.setWidth(width);
        asset.setHeight(height);
        asset.setSizeBytes(sizeBytes);
        asset.setPosition(0);
        assetMapper.insert(asset);
        return asset;
    }

    /**
     * 会话建立前产生的资产，补充绑定会话 id
     */
    public void rebind(Long assetId, Long sessionId) {
        PictureAgentAsset update = new PictureAgentAsset();
        update.setId(assetId);
        update.setEditSessionId(sessionId);
        assetMapper.updateById(update);
    }

    /**
     * 查询会话资产，可按类型过滤
     */
    public List<PictureAgentAsset> listBySession(Long sessionId, String kind) {
        return assetMapper.selectList(Wrappers.<PictureAgentAsset>lambdaQuery()
                .eq(PictureAgentAsset::getEditSessionId, sessionId)
                .eq(StrUtil.isNotBlank(kind), PictureAgentAsset::getKind, kind)
                .orderByAsc(PictureAgentAsset::getPosition)
                .orderByAsc(PictureAgentAsset::getCreateTime));
    }

    public List<AgentAssetVO> listVOBySession(Long sessionId, String kind) {
        return listBySession(sessionId, kind).stream()
                .map(AgentAssetVO::from)
                .collect(Collectors.toList());
    }

    /**
     * 取资产并校验归属会话
     */
    public PictureAgentAsset getInSession(Long sessionId, Long assetId) {
        if (assetId == null) {
            return null;
        }
        PictureAgentAsset asset = assetMapper.selectById(assetId);
        if (asset == null || !sessionId.equals(asset.getEditSessionId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "资产不存在或不属于当前会话");
        }
        return asset;
    }

    /**
     * 读取资产图片字节；无存储 key 的外部 URL 走 HTTP 下载
     */
    public byte[] loadBytes(PictureAgentAsset asset) {
        if (StrUtil.isNotBlank(asset.getStorageKey())) {
            return storageService.load(asset.getStorageKey());
        }
        if (storageService.isExternalUrl(asset.getUrl())) {
            return cn.hutool.http.HttpUtil.downloadBytes(asset.getUrl());
        }
        return storageService.load(asset.getUrl());
    }

    public static boolean isValidKind(String kind) {
        return AgentAssetKindEnum.getEnumByValue(kind) != null;
    }
}
