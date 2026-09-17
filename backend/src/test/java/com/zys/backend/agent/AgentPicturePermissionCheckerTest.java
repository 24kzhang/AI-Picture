package com.zys.backend.agent;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.manager.auth.SpaceUserAuthManager;
import com.zys.backend.manager.auth.model.SpaceUserPermissionConstant;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.Space;
import com.zys.backend.model.entity.SpaceUser;
import com.zys.backend.model.entity.User;
import com.zys.backend.model.enums.SpaceTypeEnum;
import com.zys.backend.service.PictureService;
import com.zys.backend.service.SpaceService;
import com.zys.backend.service.SpaceUserService;
import com.zys.backend.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentPicturePermissionCheckerTest {

    @Mock
    private UserService userService;

    @Mock
    private SpaceService spaceService;

    @Mock
    private SpaceUserService spaceUserService;

    @Mock
    private PictureService pictureService;

    @Mock
    private SpaceUserAuthManager spaceUserAuthManager;

    @InjectMocks
    private AgentPicturePermissionChecker checker;

    private final User owner = user(2L, "user");
    private final User other = user(3L, "user");
    private final User admin = user(1L, "admin");

    private User user(Long id, String role) {
        User user = new User();
        user.setId(id);
        user.setUserRole(role);
        return user;
    }

    private Picture picture(Long userId, Long spaceId) {
        Picture picture = new Picture();
        picture.setId(100L);
        picture.setUserId(userId);
        picture.setSpaceId(spaceId);
        return picture;
    }

    private void mockRoles() {
        lenient().when(spaceUserAuthManager.getPermissionsByRole("admin"))
                .thenReturn(Arrays.asList("picture:view", "picture:upload", "picture:edit", "picture:delete"));
        lenient().when(spaceUserAuthManager.getPermissionsByRole("editor"))
                .thenReturn(Arrays.asList("picture:view", "picture:upload", "picture:edit"));
        lenient().when(spaceUserAuthManager.getPermissionsByRole("viewer"))
                .thenReturn(List.of("picture:view"));
        lenient().when(userService.isAdmin(any()))
                .thenAnswer(inv -> "admin".equals(((User) inv.getArgument(0)).getUserRole()));
    }

    @Test
    void publicPictureOwnerCanEdit() {
        mockRoles();
        List<String> permissions = checker.permissionsOf(owner, picture(2L, null));
        assertTrue(permissions.contains(SpaceUserPermissionConstant.PICTURE_EDIT));
    }

    @Test
    void publicPictureOtherUserCanOnlyView() {
        mockRoles();
        List<String> permissions = checker.permissionsOf(other, picture(2L, null));
        assertFalse(permissions.contains(SpaceUserPermissionConstant.PICTURE_EDIT));
        assertTrue(permissions.contains(SpaceUserPermissionConstant.PICTURE_VIEW));
    }

    @Test
    void publicPictureAdminCanEdit() {
        mockRoles();
        List<String> permissions = checker.permissionsOf(admin, picture(2L, null));
        assertTrue(permissions.contains(SpaceUserPermissionConstant.PICTURE_EDIT));
    }

    @Test
    void privateSpaceOwnerCanEdit() {
        mockRoles();
        Space space = new Space();
        space.setId(1001L);
        space.setSpaceType(SpaceTypeEnum.PRIVATE.getValue());
        space.setUserId(2L);
        when(spaceService.getById(1001L)).thenReturn(space);
        assertTrue(checker.permissionsOf(owner, picture(2L, 1001L))
                .contains(SpaceUserPermissionConstant.PICTURE_EDIT));
    }

    @Test
    void privateSpaceOthersHaveNoPermission() {
        mockRoles();
        Space space = new Space();
        space.setId(1001L);
        space.setSpaceType(SpaceTypeEnum.PRIVATE.getValue());
        space.setUserId(2L);
        when(spaceService.getById(1001L)).thenReturn(space);
        assertTrue(checker.permissionsOf(other, picture(2L, 1001L)).isEmpty());
    }

    @Test
    void teamSpaceRoleDecidesPermission() {
        mockRoles();
        Space space = new Space();
        space.setId(1002L);
        space.setSpaceType(SpaceTypeEnum.TEAM.getValue());
        space.setUserId(2L);
        when(spaceService.getById(1002L)).thenReturn(space);

        @SuppressWarnings("unchecked")
        LambdaQueryChainWrapper<SpaceUser> wrapper = mock(LambdaQueryChainWrapper.class);
        when(spaceUserService.lambdaQuery()).thenReturn(wrapper);
        when(wrapper.eq(any(SFunction.class), any())).thenReturn(wrapper);
        SpaceUser editorRole = new SpaceUser();
        editorRole.setSpaceRole("editor");
        when(wrapper.one()).thenReturn(editorRole);

        assertTrue(checker.permissionsOf(other, picture(2L, 1002L))
                .contains(SpaceUserPermissionConstant.PICTURE_EDIT));
    }

    @Test
    void teamSpaceNonMemberHasNoPermission() {
        mockRoles();
        Space space = new Space();
        space.setId(1002L);
        space.setSpaceType(SpaceTypeEnum.TEAM.getValue());
        space.setUserId(2L);
        when(spaceService.getById(1002L)).thenReturn(space);

        @SuppressWarnings("unchecked")
        LambdaQueryChainWrapper<SpaceUser> wrapper = mock(LambdaQueryChainWrapper.class);
        when(spaceUserService.lambdaQuery()).thenReturn(wrapper);
        when(wrapper.eq(any(SFunction.class), any())).thenReturn(wrapper);
        when(wrapper.one()).thenReturn(null);

        assertTrue(checker.permissionsOf(other, picture(2L, 1002L)).isEmpty());
    }

    @Test
    void checkPictureEditableRejectsMissingPicture() {
        when(pictureService.getById(999L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> checker.checkPictureEditable(owner, 999L));
    }
}
