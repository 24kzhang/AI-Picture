package com.zys.backend.manager.websocket;

import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.User;
import com.zys.backend.service.PictureService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PictureEditRevocationTest {

    @Test
    void permissionChangeClosesMatchingSpaceSession() throws Exception {
        PictureEditHandler handler = new PictureEditHandler();
        PictureService pictureService = mock(PictureService.class);
        ReflectionTestUtils.setField(handler, "pictureService", pictureService);

        User user = new User();
        user.setId(7L);
        Picture picture = new Picture();
        picture.setId(101L);
        picture.setSpaceId(55L);
        when(pictureService.getById(101L)).thenReturn(picture);

        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        attributes.put("user", user);
        attributes.put("pictureId", 101L);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);

        @SuppressWarnings("unchecked")
        Map<Long, Set<WebSocketSession>> sessions =
                (Map<Long, Set<WebSocketSession>>) ReflectionTestUtils.getField(handler, "pictureSessions");
        Set<WebSocketSession> pictureSessions = ConcurrentHashMap.newKeySet();
        pictureSessions.add(session);
        sessions.put(101L, pictureSessions);

        handler.onSpaceUserPermissionChanged(new SpaceUserPermissionChangedEvent(55L, 7L));

        verify(session).sendMessage(org.mockito.ArgumentMatchers.any());
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        assertTrue(pictureSessions.isEmpty());
    }
}
