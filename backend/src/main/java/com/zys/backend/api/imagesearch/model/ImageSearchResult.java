package com.zys.backend.api.imagesearch.model;

import lombok.Data;

/**
 * 图片搜索结果
 */
@Data
public class ImageSearchResult {

    /**
     * 本地图库图片 id。
     */
    private Long pictureId;

    /**
     * 图片名称。
     */
    private String name;

    /**
     * 缩略图地址
     */
    private String thumbUrl;

    /**
     * 匹配图片的原图 URL。
     */
    private String url;

    /**
     * 来源地址
     */
    private String fromUrl;

    /**
     * 余弦相似度。
     */
    private Double similarity;

    /**
     * 空间 id，为空表示公共图库。
     */
    private Long spaceId;

    /**
     * 公共图库、私人图库或多人图库。
     */
    private String scope;
}
