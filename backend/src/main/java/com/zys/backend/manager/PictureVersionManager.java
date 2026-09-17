package com.zys.backend.manager;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zys.backend.mapper.PictureVersionMapper;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.PictureVersion;
import com.zys.backend.model.enums.PictureVersionSourceEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Date;

/**
 * 图片版本管理器：懒建初始版本、版本号分配与版本快照落库。
 * 供 Agent 提交事务与快捷编辑保存共用，保证版本历史完整。
 */
@Slf4j
@Component
public class PictureVersionManager {

    @Resource
    private PictureVersionMapper pictureVersionMapper;

    /**
     * V1 图片首次进入版本体系时，按当前内容懒创建初始版本（versionNo=1）
     */
    public void ensureInitialVersion(Picture picture) {
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
        initial.setOperatorId(picture.getUserId());
        initial.setCreateTime(picture.getCreateTime() == null ? new Date() : picture.getCreateTime());
        pictureVersionMapper.insert(initial);
    }

    /**
     * 分配下一个版本号（同一图片单调递增，恢复也不回退）
     */
    public long nextVersionNo(Long pictureId) {
        PictureVersion latest = pictureVersionMapper.selectOne(
                new QueryWrapper<PictureVersion>()
                        .eq("pictureId", pictureId)
                        .orderByDesc("versionNo")
                        .last("limit 1"));
        return latest == null ? 1L : latest.getVersionNo() + 1;
    }

    /**
     * 写入新版本快照
     *
     * @param picture 携带新图片属性（url/缩略图/尺寸等）的实体
     */
    public PictureVersion recordVersion(Picture picture, long versionNo,
                                        PictureVersionSourceEnum source,
                                        Long sourceSessionId, Long sourceRunId, Long operatorId) {
        PictureVersion version = new PictureVersion();
        version.setPictureId(picture.getId());
        version.setVersionNo(versionNo);
        version.setUrl(picture.getUrl());
        version.setThumbnailUrl(picture.getThumbnailUrl());
        version.setPicSize(picture.getPicSize());
        version.setPicWidth(picture.getPicWidth());
        version.setPicHeight(picture.getPicHeight());
        version.setPicScale(picture.getPicScale());
        version.setPicFormat(picture.getPicFormat());
        version.setPicColor(picture.getPicColor());
        version.setSource(source.getValue());
        version.setSourceSessionId(sourceSessionId);
        version.setSourceRunId(sourceRunId);
        version.setOperatorId(operatorId);
        version.setCreateTime(new Date());
        pictureVersionMapper.insert(version);
        return version;
    }
}
