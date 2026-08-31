package com.zys.backend.api.aliyunai;

import com.zys.backend.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;
import com.zys.backend.api.aliyunai.model.CreateOutPaintingTaskResponse;
import com.zys.backend.api.aliyunai.model.GetOutPaintingTaskResponse;

/**
 * 阿里云图片编辑接口参数校验测试，不产生真实模型调用
 */
class AliYunAiApiTest {

    private AliYunAiApi aliYunAiApi;

    @BeforeEach
    void setUp() {
        aliYunAiApi = new AliYunAiApi();
        ReflectionTestUtils.setField(aliYunAiApi, "imageEditModel", "wanx2.1-imageedit");
    }

    @Test
    void shouldRejectMissingApiKey() {
        ReflectionTestUtils.setField(aliYunAiApi, "apiKey", "");
        assertThrows(BusinessException.class,
                () -> aliYunAiApi.createImageEditTask("https://example.com/image.png", "增强清晰度", 0.5D));
    }

    @Test
    void shouldRejectBlankPromptWithoutCallingModel() {
        ReflectionTestUtils.setField(aliYunAiApi, "apiKey", "test-key");
        assertThrows(BusinessException.class,
                () -> aliYunAiApi.createImageEditTask("https://example.com/image.png", " ", 0.5D));
    }

    @Test
    void shouldRejectInvalidStrengthWithoutCallingModel() {
        ReflectionTestUtils.setField(aliYunAiApi, "apiKey", "test-key");
        assertThrows(BusinessException.class,
                () -> aliYunAiApi.createImageEditTask("https://example.com/image.png", "增强清晰度", 1.2D));
    }

    @Test
    void shouldRouteQwenCreationAndPollingToLocalAdapter() {
        QwenImageEditor editor = mock(QwenImageEditor.class);
        ReflectionTestUtils.setField(aliYunAiApi, "qwenImageEditor", editor);
        ReflectionTestUtils.setField(aliYunAiApi, "apiKey", "test-key");
        ReflectionTestUtils.setField(aliYunAiApi, "imageEditModel", "qwen-image-2.0-pro");
        CreateOutPaintingTaskResponse created = new CreateOutPaintingTaskResponse();
        when(editor.submit("test-key", "qwen-image-2.0-pro", "https://example.com/image.png", "日落海滩")).thenReturn(created);
        assertSame(created, aliYunAiApi.createImageEditTask("https://example.com/image.png", " 日落海滩 ", null));
        GetOutPaintingTaskResponse result = new GetOutPaintingTaskResponse();
        when(editor.getTask("qwen-local-test")).thenReturn(result);
        assertSame(result, aliYunAiApi.getOutPaintingTask("qwen-local-test"));
    }

    @Test
    void shouldRejectNonFiniteStrength() {
        ReflectionTestUtils.setField(aliYunAiApi, "apiKey", "test-key");
        assertThrows(BusinessException.class,
                () -> aliYunAiApi.createImageEditTask("https://example.com/image.png", "增强清晰度", Double.NaN));
    }
}
