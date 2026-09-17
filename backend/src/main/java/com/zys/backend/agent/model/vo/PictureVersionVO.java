package com.zys.backend.agent.model.vo;

import com.zys.backend.model.entity.PictureVersion;
import lombok.Data;

import java.util.Date;

/**
 * 图片正式版本
 */
@Data
public class PictureVersionVO {

    private Long id;

    private Long pictureId;

    private Integer versionNo;

    private String url;

    private String thumbnailUrl;

    private Long picSize;

    private Integer picWidth;

    private Integer picHeight;

    private String picFormat;

    private String picColor;

    private String source;

    private Long sourceSessionId;

    private Long operatorId;

    private String operatorName;

    private Date createTime;

    public static PictureVersionVO from(PictureVersion version) {
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
        vo.setSourceSessionId(version.getSourceSessionId());
        vo.setOperatorId(version.getOperatorId());
        vo.setCreateTime(version.getCreateTime());
        return vo;
    }
}
