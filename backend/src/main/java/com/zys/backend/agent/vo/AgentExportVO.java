package com.zys.backend.agent.vo;

import lombok.Data;

/**
 * Agent 导出任务视图（两步式：先创建，再下载）
 */
@Data
public class AgentExportVO {

    /**
     * 导出任务 ID（Redis 中的短期凭证）
     */
    private String exportId;

    /**
     * 建议文件名
     */
    private String filename;

    /**
     * 下载地址（同源，需登录）
     */
    private String downloadUrl;
}
