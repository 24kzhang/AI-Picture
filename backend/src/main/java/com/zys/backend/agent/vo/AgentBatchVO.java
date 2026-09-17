package com.zys.backend.agent.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 批量任务视图
 */
@Data
public class AgentBatchVO {

    /**
     * 批量任务 ID（即 Agent Run ID）
     */
    private String batchId;

    /**
     * 运行状态：pending/queued/running/succeeded/failed/canceled
     */
    private String status;

    /**
     * 进度百分比
     */
    private Integer progress;

    /**
     * 阶段说明
     */
    private String stage;

    /**
     * 错误信息
     */
    private String error;

    /**
     * 逐项明细
     */
    private List<AgentBatchItemVO> items = new ArrayList<>();

    /**
     * 是否已取消（取消后禁止确认）
     */
    private Boolean canceled;

    /**
     * 逐项明细
     */
    @Data
    public static class AgentBatchItemVO {

        /**
         * 云图库图片 id
         */
        private Long pictureId;

        /**
         * 图片名称
         */
        private String pictureName;

        /**
         * Agent 素材 id（源图）
         */
        private String sourceAssetId;

        /**
         * 提交前的图片编辑版本号（乐观锁基准）
         */
        private Long baseEditVersion;

        /**
         * 项状态：pending/running/succeeded/failed/canceled/conflict
         */
        private String status;

        /**
         * 错误信息
         */
        private String error;

        /**
         * 产物素材 id
         */
        private List<String> outputAssetIds = new ArrayList<>();

        /**
         * 产物预览地址（短时有效）
         */
        private List<String> outputUrls = new ArrayList<>();

        /**
         * 提交后的版本 id（成功替换时有值）
         */
        private Long versionId;

        /**
         * 提交后的版本号
         */
        private Long versionNo;
    }
}
