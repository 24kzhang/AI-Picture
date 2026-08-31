package com.zys.backend.manager.vector.model;

import lombok.Data;

import java.util.List;

/**
 * 本地向量服务返回的重复向量分组。
 */
@Data
public class VectorDuplicateResponse {

    private Double threshold;

    private Integer vectorCount;

    private Integer groupCount;

    private Integer duplicateCount;

    private List<List<Long>> groups;
}
