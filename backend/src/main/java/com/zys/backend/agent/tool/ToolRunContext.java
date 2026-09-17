package com.zys.backend.agent.tool;

import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.entity.PictureToolRun;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 工具运行上下文。
 */
@Data
@Accessors(chain = true)
public class ToolRunContext {

    /**
     * 编辑会话，sessionRequired=false 的工具可能为 null
     */
    private PictureEditSession session;

    /**
     * 关联图片
     */
    private Picture picture;

    /**
     * 当前工具任务记录
     */
    private PictureToolRun toolRun;

    /**
     * 进度上报器
     */
    private ProgressReporter reporter;

    /**
     * 便捷上报入口
     */
    public void report(int progress, String stage) {
        if (reporter != null) {
            reporter.report(progress, stage);
        }
    }
}
