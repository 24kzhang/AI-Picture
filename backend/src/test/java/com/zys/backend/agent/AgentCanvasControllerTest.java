package com.zys.backend.agent;

import com.zys.backend.agent.controller.AgentCanvasController;
import com.zys.backend.agent.service.AgentSessionService;
import com.zys.backend.agent.service.LayerDocumentService;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.entity.User;
import com.zys.backend.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentCanvasControllerTest {
    @Test
    void previewRequiresSessionAccessAndReturnsFreshCanvas() {
        AgentCanvasController controller = new AgentCanvasController();
        UserService users = mock(UserService.class);
        AgentSessionService sessions = mock(AgentSessionService.class);
        LayerDocumentService documents = mock(LayerDocumentService.class);
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "sessionService", sessions);
        ReflectionTestUtils.setField(controller, "documentService", documents);
        MockHttpServletRequest request = new MockHttpServletRequest();
        User user = new User();
        PictureEditSession session = new PictureEditSession();
        byte[] canvas = {1, 2, 3};
        when(users.getLoginUser(request)).thenReturn(user);
        when(sessions.requireSession(9L, user, false)).thenReturn(session);
        when(documents.currentCanvasBytes(session)).thenReturn(canvas);
        assertArrayEquals(canvas, controller.canvas(9L, request).getBody());
        assertEquals("private, no-store", controller.canvas(9L, request).getHeaders().getFirst("Cache-Control"));
        when(sessions.requireSession(10L, user, false)).thenThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR));
        assertThrows(BusinessException.class, () -> controller.canvas(10L, request));
        verify(documents, times(2)).currentCanvasBytes(session);
        verifyNoMoreInteractions(documents);
    }
}
