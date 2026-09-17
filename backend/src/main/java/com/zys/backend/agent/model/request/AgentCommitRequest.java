package com.zys.backend.agent.model.request;

import lombok.Data;

import java.io.Serializable;

/**
 * 提交正式版本请求
 */
@Data
public class AgentCommitRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 最终草稿资产 id
     */
    private Long finalAssetId;

    /**
     * 乐观锁：期望的 picture.editVersion
     */
    private Long expectedEditVersion;
}
