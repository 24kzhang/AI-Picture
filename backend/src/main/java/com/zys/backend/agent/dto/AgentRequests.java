package com.zys.backend.agent.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 修图 Agent 请求入参集合（snake_case 与 Agent API 对齐）
 */
public final class AgentRequests {

    private AgentRequests() {
    }

    /**
     * 创建编辑会话
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class SessionCreate {

        private String currentAssetId;

        private List<String> assetIds = new ArrayList<>();

        private String title;

        public SessionCreate(String currentAssetId) {
            this(currentAssetId, new ArrayList<>(), null);
        }
    }

    /**
     * 调用工具
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolInvoke {

        private String tool;

        private Object params = new LinkedHashMap<>();
    }

    /**
     * 发送对话消息
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {

        private String text;
    }

    /**
     * 点选坐标（归一化 0-1）
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Point {

        private Double x;

        private Double y;
    }

    /**
     * 创建选区
     */
    @Data
    @NoArgsConstructor
    public static class Select {

        private Integer revision;

        private List<Point> points = new ArrayList<>();

        private List<List<Point>> strokes = new ArrayList<>();

        private Double radius = 0.03;

        private Boolean append = false;
    }
}
