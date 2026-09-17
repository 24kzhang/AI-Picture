package com.zys.backend.agent.dto;

import com.zys.backend.exception.ErrorCode;
import com.zys.backend.exception.ThrowUtils;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Agent 对外接口请求体
 */
public final class AgentApiRequests {

    private AgentApiRequests() {
    }

    /**
     * 发送对话消息
     */
    @Data
    public static class MessageRequest {

        private String text;

        public void validate() {
            String value = text == null ? "" : text.trim();
            ThrowUtils.throwIf(value.isEmpty(), ErrorCode.PARAMS_ERROR, "指令不能为空");
            ThrowUtils.throwIf(value.length() > 1000, ErrorCode.PARAMS_ERROR, "指令不能超过 1000 字");
            text = value;
        }
    }

    /**
     * 调用工具
     */
    @Data
    public static class ToolRequest {

        private String tool;

        private Map<String, Object> params;

        public void validate() {
            ThrowUtils.throwIf(tool == null || tool.trim().isEmpty(), ErrorCode.PARAMS_ERROR, "工具名不能为空");
            ThrowUtils.throwIf(tool.length() > 48, ErrorCode.PARAMS_ERROR, "工具名非法");
        }
    }

    /**
     * 创建选区
     */
    @Data
    public static class SelectionRequest {

        private Integer revision;

        private List<PointDTO> points;

        private List<List<PointDTO>> strokes;

        private Double radius;

        private Boolean append;

        public void validate() {
            ThrowUtils.throwIf(revision == null || revision < 1, ErrorCode.PARAMS_ERROR, "缺少画布修订号");
            boolean hasPoints = points != null && !points.isEmpty();
            boolean hasStrokes = strokes != null && !strokes.isEmpty();
            ThrowUtils.throwIf(!hasPoints && !hasStrokes, ErrorCode.PARAMS_ERROR, "请点选或涂抹选区");
        }
    }

    /**
     * 归一化坐标点
     */
    @Data
    public static class PointDTO {

        private Double x;

        private Double y;
    }

    /**
     * 选定最终草稿
     */
    @Data
    public static class FinalAssetRequest {

        private String assetId;
    }

    /**
     * 确认并替换原图
     */
    @Data
    public static class CommitRequest {

        private Long expectedEditVersion;
    }

    /**
     * 恢复历史版本
     */
    @Data
    public static class RestoreRequest {

        private Long expectedEditVersion;
    }

    /**
     * 创建批量处理任务
     */
    @Data
    public static class BatchCreateRequest {

        private List<Long> pictureIds;

        private List<String> operations;

        private List<String> formats;

        public void validate() {
            ThrowUtils.throwIf(pictureIds == null || pictureIds.isEmpty(),
                    ErrorCode.PARAMS_ERROR, "请选择要处理的图片");
            ThrowUtils.throwIf(pictureIds.size() > 20, ErrorCode.PARAMS_ERROR, "批量最多 20 张");
            ThrowUtils.throwIf(operations == null || operations.isEmpty(),
                    ErrorCode.PARAMS_ERROR, "请选择至少一个处理操作");
            ThrowUtils.throwIf(operations.size() > 6, ErrorCode.PARAMS_ERROR, "批量操作最多 6 步");
        }
    }

    /**
     * 创建导出任务
     */
    @Data
    public static class ExportCreateRequest {

        private Long sessionId;

        private String batchId;

        private List<String> assetIds;

        public void validate() {
            ThrowUtils.throwIf(sessionId == null && (batchId == null || batchId.isBlank()),
                    ErrorCode.PARAMS_ERROR, "请提供会话或批量任务");
        }
    }
}
