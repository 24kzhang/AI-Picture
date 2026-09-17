package com.zys.backend.agent.model.request;

import lombok.Data;

import java.io.Serializable;

/**
 * 选定最终草稿请求
 */
@Data
public class AgentFinalAssetRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 资产 id
     */
    private Long assetId;
}
