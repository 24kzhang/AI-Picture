package com.zys.backend.manager.auth;

import com.zys.backend.manager.auth.model.SpaceUserPermissionConstant;
import com.zys.backend.model.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static com.zys.backend.constant.UserConstant.USER_LOGIN_STATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StpInterfaceImplTest {

    @AfterEach
    void clearRequestAndSession() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void jsonContentTypeWithCharsetStillLoadsSpaceContext() {
        StpInterfaceImpl implementation = new StpInterfaceImpl();
        ReflectionTestUtils.setField(implementation, "contextPath", "/api");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/json;charset=UTF-8");
        request.setContent("{\"spaceId\":55}".getBytes(StandardCharsets.UTF_8));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        SpaceUserAuthContext context = ReflectionTestUtils.invokeMethod(
                implementation, "getAuthContextByRequest");

        assertEquals(55L, context.getSpaceId());
    }

    @Test
    void missingContextDoesNotGrantEvenAdministratorReadPermission() {
        StpInterfaceImpl implementation = new StpInterfaceImpl();
        User user = new User();
        user.setId(7001L);
        user.setUserRole("admin");
        StpKit.SPACE.getSessionByLoginId(7001L).set(USER_LOGIN_STATE, user);
        SpaceUserAuthManager authManager = mock(SpaceUserAuthManager.class);
        when(authManager.getPermissionsByRole("admin"))
                .thenReturn(Collections.singletonList(SpaceUserPermissionConstant.PICTURE_EDIT));
        ReflectionTestUtils.setField(implementation, "spaceUserAuthManager", authManager);
        RequestContextHolder.resetRequestAttributes();

        assertTrue(implementation.getPermissionList(7001L, StpKit.SPACE_TYPE).isEmpty());
    }
}
