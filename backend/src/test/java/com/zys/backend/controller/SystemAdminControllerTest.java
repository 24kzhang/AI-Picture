package com.zys.backend.controller;

import com.zys.backend.annotation.AuthCheck;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SystemAdminControllerTest {

    @Test
    void localAdminEndpointsShouldNotRequireBusinessLogin() {
        RequestMapping mapping = SystemAdminController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[]{"/local-admin"}, mapping.value());
        for (Method method : SystemAdminController.class.getDeclaredMethods()) {
            assertNull(method.getAnnotation(AuthCheck.class), method.getName());
        }
    }
}
