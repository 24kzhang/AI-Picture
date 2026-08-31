package com.zys.backend.controller;

import com.zys.backend.common.BaseResponse;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.manager.auth.SpaceUserAuthManager;
import com.zys.backend.manager.auth.model.SpaceUserPermissionConstant;
import com.zys.backend.model.entity.Space;
import com.zys.backend.model.entity.User;
import com.zys.backend.model.vo.SpaceVO;
import com.zys.backend.service.SpaceService;
import com.zys.backend.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpaceControllerTest {

    @InjectMocks
    private SpaceController spaceController;

    @Mock
    private UserService userService;

    @Mock
    private SpaceService spaceService;

    @Mock
    private SpaceUserAuthManager spaceUserAuthManager;

    @Mock
    private HttpServletRequest request;

    @Test
    void shouldRejectSpaceMetadataWithoutViewPermission() {
        Space space = new Space();
        space.setId(1001L);
        User loginUser = new User();
        loginUser.setId(3L);

        when(spaceService.getById(1001L)).thenReturn(space);
        when(userService.getLoginUser(request)).thenReturn(loginUser);
        when(spaceUserAuthManager.getPermissionList(space, loginUser))
                .thenReturn(Collections.emptyList());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> spaceController.getSpaceVOById(1001L, request)
        );

        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
        verify(spaceService, never()).getSpaceVO(space, request);
    }

    @Test
    void shouldReturnSpaceMetadataWithViewPermission() {
        Space space = new Space();
        space.setId(1002L);
        User loginUser = new User();
        loginUser.setId(3L);
        SpaceVO spaceVO = new SpaceVO();

        when(spaceService.getById(1002L)).thenReturn(space);
        when(userService.getLoginUser(request)).thenReturn(loginUser);
        when(spaceUserAuthManager.getPermissionList(space, loginUser))
                .thenReturn(Collections.singletonList(SpaceUserPermissionConstant.PICTURE_VIEW));
        when(spaceService.getSpaceVO(space, request)).thenReturn(spaceVO);

        BaseResponse<SpaceVO> response = spaceController.getSpaceVOById(1002L, request);

        assertEquals(0, response.getCode());
        assertEquals(Collections.singletonList(SpaceUserPermissionConstant.PICTURE_VIEW),
                response.getData().getPermissionList());
    }
}
