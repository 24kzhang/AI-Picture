package com.zys.backend.service;

import cn.hutool.core.collection.CollUtil;
import com.zys.backend.api.imagesearch.model.ImageSearchResult;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.exception.ThrowUtils;
import com.zys.backend.manager.auth.SpaceUserAuthManager;
import com.zys.backend.manager.auth.model.SpaceUserPermissionConstant;
import com.zys.backend.manager.vector.VectorClient;
import com.zys.backend.manager.vector.model.VectorSearchItem;
import com.zys.backend.model.dto.picture.SearchPictureByPictureRequest;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.Space;
import com.zys.backend.model.entity.User;
import com.zys.backend.model.enums.PictureReviewStatusEnum;
import com.zys.backend.model.enums.SpaceTypeEnum;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 基于百炼视觉 Embedding 和 Chroma 的权限隔离以图搜图。
 */
@Service
public class LocalImageSearchService {

    private static final int DEFAULT_LIMIT = 12;
    private static final int MAX_LIMIT = 30;

    @Resource
    private PictureService pictureService;

    @Resource
    private SpaceService spaceService;

    @Resource
    private SpaceUserAuthManager spaceUserAuthManager;

    @Resource
    private VectorClient vectorClient;

    public List<ImageSearchResult> search(SearchPictureByPictureRequest request, User loginUser) {
        Long pictureId = request.getPictureId();
        ThrowUtils.throwIf(pictureId == null || pictureId <= 0,
                ErrorCode.PARAMS_ERROR, "图片 id 不合法");
        Picture queryPicture = pictureService.getById(pictureId);
        ThrowUtils.throwIf(queryPicture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");

        Space sourceSpace = null;
        List<Long> allowedSpaceIds = new ArrayList<>();
        if (queryPicture.getSpaceId() == null) {
            allowedSpaceIds.add(0L);
        } else {
            sourceSpace = spaceService.getById(queryPicture.getSpaceId());
            ThrowUtils.throwIf(sourceSpace == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
            boolean canView = spaceUserAuthManager.getPermissionList(sourceSpace, loginUser)
                    .contains(SpaceUserPermissionConstant.PICTURE_VIEW);
            ThrowUtils.throwIf(!canView, ErrorCode.NO_AUTH_ERROR, "没有该图库的查看权限");
            allowedSpaceIds.add(sourceSpace.getId());
            if (Boolean.TRUE.equals(request.getIncludePublic())) {
                allowedSpaceIds.add(0L);
            }
        }

        int limit = request.getLimit() == null
                ? DEFAULT_LIMIT
                : Math.max(1, Math.min(MAX_LIMIT, request.getLimit()));
        List<VectorSearchItem> vectorItems = vectorClient.search(
                queryPicture.getUrl(), allowedSpaceIds, limit * 4);
        if (CollUtil.isEmpty(vectorItems)) {
            return Collections.emptyList();
        }

        List<Long> ids = vectorItems.stream()
                .map(VectorSearchItem::getPictureId)
                .filter(id -> !pictureId.equals(id))
                .distinct()
                .collect(Collectors.toList());
        if (CollUtil.isEmpty(ids)) {
            return Collections.emptyList();
        }
        Map<Long, Picture> pictureMap = pictureService.listByIds(ids).stream()
                .collect(Collectors.toMap(Picture::getId, picture -> picture));
        Map<Long, Double> scoreMap = vectorItems.stream()
                .collect(Collectors.toMap(
                        VectorSearchItem::getPictureId,
                        VectorSearchItem::getScore,
                        (left, right) -> Math.max(left, right),
                        LinkedHashMap::new
                ));

        List<ImageSearchResult> results = new ArrayList<>();
        for (Long id : ids) {
            Picture candidate = pictureMap.get(id);
            if (!isCandidateVisible(candidate, queryPicture, request)) {
                continue;
            }
            ImageSearchResult result = new ImageSearchResult();
            result.setPictureId(candidate.getId());
            result.setName(candidate.getName());
            result.setThumbUrl(candidate.getThumbnailUrl() == null
                    ? candidate.getUrl()
                    : candidate.getThumbnailUrl());
            result.setUrl(candidate.getUrl());
            result.setFromUrl("/picture/" + candidate.getId());
            result.setSimilarity(scoreMap.get(id));
            result.setSpaceId(candidate.getSpaceId());
            result.setScope(resolveScope(candidate, sourceSpace));
            results.add(result);
            if (results.size() >= limit) {
                break;
            }
        }
        return results;
    }

    public int reindexAll() {
        int count = 0;
        for (Picture picture : pictureService.list()) {
            try {
                vectorClient.upsert(picture);
                count++;
            } catch (BusinessException ignored) {
                // 单张历史图片失效不应中断其余图片的索引重建。
            }
        }
        return count;
    }

    public boolean isVectorServiceAvailable() {
        return vectorClient.isAvailable();
    }

    private boolean isCandidateVisible(Picture candidate,
                                       Picture queryPicture,
                                       SearchPictureByPictureRequest request) {
        if (candidate == null || candidate.getId().equals(queryPicture.getId())) {
            return false;
        }
        if (candidate.getSpaceId() == null) {
            return Objects.equals(PictureReviewStatusEnum.PASS.getValue(), candidate.getReviewStatus())
                    && (queryPicture.getSpaceId() == null || Boolean.TRUE.equals(request.getIncludePublic()));
        }
        return queryPicture.getSpaceId() != null
                && queryPicture.getSpaceId().equals(candidate.getSpaceId());
    }

    private String resolveScope(Picture picture, Space sourceSpace) {
        if (picture.getSpaceId() == null) {
            return "公共图库";
        }
        if (sourceSpace != null
                && SpaceTypeEnum.TEAM.getValue() == sourceSpace.getSpaceType()) {
            return "多人图库";
        }
        return "私人图库";
    }
}
