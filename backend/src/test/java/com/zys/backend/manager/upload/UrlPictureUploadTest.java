package com.zys.backend.manager.upload;

import com.zys.backend.api.aliyunai.QwenImageEditor;
import com.zys.backend.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UrlPictureUploadTest {
    @Test
    void onlyRegisteredModelOutputMayUsePrivateAddress() {
        UrlPictureUpload upload = new UrlPictureUpload();
        QwenImageEditor editor = mock(QwenImageEditor.class);
        ReflectionTestUtils.setField(upload, "qwenImageEditor", editor);
        String output = "http://172.18.20.44:30066/qwen-image/outputs/result.png";
        when(editor.isGeneratedOutputUrl(output)).thenReturn(true);
        assertDoesNotThrow(() -> upload.validPicture(output));
        assertThrows(BusinessException.class, () -> upload.validPicture(output + "?redirect=1"));
        assertThrows(BusinessException.class, () -> upload.validPicture("http://172.18.20.44:30066/api/status"));
        assertThrows(BusinessException.class, () -> upload.validPicture("http://127.0.0.1/secret"));
    }
}
