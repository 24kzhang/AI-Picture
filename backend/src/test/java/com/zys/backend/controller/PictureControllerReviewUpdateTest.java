package com.zys.backend.controller;

import com.zys.backend.model.dto.picture.PictureUpdateRequest;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.User;
import com.zys.backend.service.PictureService;
import com.zys.backend.service.UserService;
import com.zys.backend.service.impl.PictureServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PictureControllerReviewUpdateTest {
    @Test void administratorUpdatesPersistTheNewAiReviewQueueState() {
        PictureController controller = new PictureController();
        PictureService pictures = mock(PictureService.class);
        UserService users = mock(UserService.class);
        ReflectionTestUtils.setField(controller, "pictureService", pictures);
        ReflectionTestUtils.setField(controller, "userService", users);
        Picture existing = new Picture(); existing.setId(1L); existing.setReviewStatus(1);
        User admin = new User(); admin.setId(7L); admin.setUserRole("admin");
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(pictures.getById(1L)).thenReturn(existing);
        when(users.getLoginUser(request)).thenReturn(admin);
        doAnswer(call -> {
            new PictureServiceImpl().fillReviewParams(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(pictures).fillReviewParams(any(Picture.class), eq(admin));
        when(pictures.update(any(Picture.class), any())).thenReturn(true);
        PictureUpdateRequest input = new PictureUpdateRequest(); input.setId(1L); input.setName("咖啡豆商品图");
        input.setExpectedEditVersion(0L);

        assertTrue(controller.updatePicture(input, request).getData());

        ArgumentCaptor<Picture> saved = ArgumentCaptor.forClass(Picture.class);
        verify(pictures).update(saved.capture(), any());
        assertEquals(1L, saved.getValue().getEditVersion());
        assertEquals(1L, saved.getValue().getId());
        assertEquals("咖啡豆商品图", saved.getValue().getName());
        assertEquals(0, saved.getValue().getReviewStatus());
        assertEquals(0L, saved.getValue().getReviewerId());
        assertEquals("等待AI审核", saved.getValue().getReviewMessage());
        assertNotNull(saved.getValue().getReviewTime());
    }
}
