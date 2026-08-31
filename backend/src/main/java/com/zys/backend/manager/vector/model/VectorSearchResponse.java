package com.zys.backend.manager.vector.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Python 向量服务的检索响应。
 */
@Data
public class VectorSearchResponse {

    private List<VectorSearchItem> items = new ArrayList<>();
}
