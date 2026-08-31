package com.zys.backend.model.dto.picture;


import lombok.Data;

@Data
public class PictureUploadRequest {

    /**
     * 图片 id ，用于修改
     */
    private Long id;

    /**
     * 图片文件 url ，用于上传
     */
    private String fileUrl;

    /**
     * 图片名称
     */
    private String picName;

    /**
     * 空间 id
     */
    private Long spaceId;


}
