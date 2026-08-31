package com.zys.backend.model.dto.picture;

import lombok.Data;

import java.io.Serializable;

/**
 * 以图搜图请求
 */
@Data
public class SearchPictureByPictureRequest implements Serializable {

    /**
     * 图片 id
     */
    private Long pictureId;

    /**
     * 私人或多人图库检索时，是否同时检索公共图库。
     */
    private Boolean includePublic = false;

    /**
     * 返回结果数量，范围 1 - 30。
     */
    private Integer limit = 12;

    private static final long serialVersionUID = 1L;
}
