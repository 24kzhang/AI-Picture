package com.zys.backend.controller;

import com.zys.backend.exception.BusinessException;
import com.zys.backend.manager.auth.SpaceUserAuthManager;
import com.zys.backend.model.entity.Space;
import com.zys.backend.model.entity.User;
import com.zys.backend.service.SpaceService;
import com.zys.backend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;
import javax.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VideoBridgeControllerTest {
    @InjectMocks VideoBridgeController controller;
    @Mock UserService userService;
    @Mock SpaceService spaceService;
    @Mock SpaceUserAuthManager authManager;
    @Mock HttpServletRequest request;
    @TempDir Path directory;
    final String key = String.join("", Collections.nCopies(64, "a"));

    @BeforeEach void configure() throws Exception {
        Path keyFile=directory.resolve("service.key");
        Files.write(keyFile,key.getBytes(StandardCharsets.UTF_8));
        ReflectionTestUtils.setField(controller,"keyFile",keyFile.toString());
    }
    User login() {
        User user=new User();user.setId(12L);
        when(request.getHeader("X-Video-Service-Key")).thenReturn(key);
        when(userService.getLoginUser(request)).thenReturn(user);
        return user;
    }
    @Test void rejectsInvalidServiceBeforeReadingSession() {
        when(request.getHeader("X-Video-Service-Key")).thenReturn("wrong");
        assertThrows(BusinessException.class,()->controller.authorize(new VideoBridgeController.Scope(),request));
        verifyNoInteractions(userService);
    }
    @Test void publicMutationRequiresOwner() {
        login();VideoBridgeController.Scope scope=new VideoBridgeController.Scope();scope.setOwnerId(13L);scope.setAction("edit");
        assertThrows(BusinessException.class,()->controller.authorize(scope,request));
    }
    @Test void privateVideoUsesExistingSpacePermissions() {
        User user=login();Space space=new Space();space.setId(100L);
        when(spaceService.getById(100L)).thenReturn(space);
        when(authManager.getPermissionList(space,user)).thenReturn(Collections.singletonList("picture:view"));
        VideoBridgeController.Scope scope=new VideoBridgeController.Scope();scope.setSpaceId(100L);
        assertEquals("12",controller.authorize(scope,request).getData().get("userId"));
        scope.setAction("edit");assertThrows(BusinessException.class,()->controller.authorize(scope,request));
    }
    @Test void storageTicketRejectsForeignScopeBeforeSigning() {
        login();VideoBridgeController.Scope scope=new VideoBridgeController.Scope();scope.setOwnerId(12L);scope.setMethod("GET");scope.setKey("videos/public-13/abc.mp4");
        assertThrows(BusinessException.class,()->controller.ticket(scope,request));
    }
}
