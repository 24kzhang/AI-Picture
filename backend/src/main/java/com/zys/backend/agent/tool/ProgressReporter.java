package com.zys.backend.agent.tool;

/**
 * 工具执行进度上报回调，实现方负责落库并发布 SSE 事件。
 */
public interface ProgressReporter {

    /**
     * 上报进度
     *
     * @param progress 百分比 0-100
     * @param stage    当前阶段描述
     */
    void report(int progress, String stage);
}
