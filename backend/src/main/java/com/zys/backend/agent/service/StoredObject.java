package com.zys.backend.agent.service;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 存储写入结果
 */
@Data
@Accessors(chain = true)
public class StoredObject {

    /**
     * 可访问 URL
     */
    private String url;

    /**
     * COS 对象 key
     */
    private String storageKey;

    /**
     * 缩略图 URL（图片类资产）
     */
    private String thumbnailUrl;

    /**
     * 字节数
     */
    private long sizeBytes;

    /**
     * 图片宽度
     */
    private Integer width;

    /**
     * 图片高度
     */
    private Integer height;

    /**
     * 格式
     */
    private String format;
}
