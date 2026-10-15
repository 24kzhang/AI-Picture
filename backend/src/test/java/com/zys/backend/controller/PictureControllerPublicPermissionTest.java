package com.zys.backend.controller;

import com.zys.backend.manager.auth.SpaceUserAuthManager;
import com.zys.backend.manager.auth.model.SpaceUserPermissionConstant;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.User;
import com.zys.backend.model.vo.PictureVO;
import com.zys.backend.service.PictureService;
import com.zys.backend.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PictureControllerPublicPermissionTest {
    @Test void publicPictureOwnerHasEditingEntrypointsButOtherUsersRemainViewers() {
        PictureController controller = new PictureController();
        PictureService pictures = mock(PictureService.class);
        UserService users = mock(UserService.class);
        SpaceUserAuthManager auth = mock(SpaceUserAuthManager.class);
        ReflectionTestUtils.setField(controller, "pictureService", pictures);
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "spaceUserAuthManager", auth);
        Picture picture = new Picture(); picture.setId(1L); picture.setUserId(10L);
        User owner = new User(); owner.setId(10L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(pictures.getById(1L)).thenReturn(picture);
        when(pictures.getPictureVO(picture, request)).thenAnswer(i -> new PictureVO());
        when(users.getLoginUser(request)).thenReturn(owner);
        when(auth.getPermissionList(null, owner)).thenReturn(Collections.singletonList(SpaceUserPermissionConstant.PICTURE_VIEW));
        assertTrue(controller.getPictureVOById(1L, request).getData().getPermissionList().contains(SpaceUserPermissionConstant.PICTURE_EDIT));
        assertTrue(controller.getPictureVOById(1L, request).getData().getPermissionList().contains(SpaceUserPermissionConstant.PICTURE_DELETE));
        picture.setUserId(11L);
        assertEquals(Collections.singletonList(SpaceUserPermissionConstant.PICTURE_VIEW), controller.getPictureVOById(1L, request).getData().getPermissionList());
    }
}
