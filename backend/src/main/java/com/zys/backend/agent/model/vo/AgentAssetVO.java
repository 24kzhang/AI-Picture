package com.zys.backend.agent.model.vo;

import com.zys.backend.model.entity.PictureAgentAsset;
import lombok.Data;

import java.util.Date;

/**
 * 会话资产墙条目
 */
@Data
public class AgentAssetVO {

    private Long id;

    private Long editSessionId;

    private String kind;

    private String source;

    private String url;

    private String thumbnailUrl;

    private Integer width;

    private Integer height;

    private Long sizeBytes;

    private Integer position;

    private Date createTime;

    public static AgentAssetVO from(PictureAgentAsset asset) {
        AgentAssetVO vo = new AgentAssetVO();
        vo.setId(asset.getId());
        vo.setEditSessionId(asset.getEditSessionId());
        vo.setKind(asset.getKind());
        vo.setSource(asset.getSource());
        vo.setUrl(asset.getUrl());
        vo.setThumbnailUrl(asset.getThumbnailUrl());
        vo.setWidth(asset.getWidth());
        vo.setHeight(asset.getHeight());
        vo.setSizeBytes(asset.getSizeBytes());
        vo.setPosition(asset.getPosition());
        vo.setCreateTime(asset.getCreateTime());
        return vo;
    }
}
