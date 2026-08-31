package com.zys.backend.manager.vector.model;

import lombok.Data;

/**
 * Chroma 返回的单条向量检索结果。
 */
@Data
public class VectorSearchItem {

    private Long pictureId;

    private Double score;
}
