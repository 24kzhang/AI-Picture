package com.zys.backend.agent.model.request;

import lombok.Data;

import java.io.Serializable;

/**
 * 恢复历史版本请求（恢复会创建一个新版本）
 */
@Data
public class VersionRestoreRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 乐观锁：期望的 picture.editVersion
     */
    private Long expectedEditVersion;
}
