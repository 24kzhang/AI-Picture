package com.zys.backend.manager.websocket;

import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import com.zys.backend.agent.service.EditLeaseService;
import com.zys.backend.constant.AgentConstant;
import com.zys.backend.manager.websocket.model.PictureEditActionEnum;
import com.zys.backend.manager.websocket.model.PictureEditRequestMessage;
import com.zys.backend.manager.websocket.model.PictureEditResponseMessage;
import com.zys.backend.model.entity.User;
import com.zys.backend.model.vo.UserVO;
import com.zys.backend.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PictureEditProtocolTest {

    @Test
    void shouldRecognizeNewEditorActions() {
        assertNotNull(PictureEditActionEnum.getEnumByValue("BRUSH_STROKE"));
        assertNotNull(PictureEditActionEnum.getEnumByValue("ADD_STICKER"));
        assertNotNull(PictureEditActionEnum.getEnumByValue("MOVE_STICKER"));
        assertNotNull(PictureEditActionEnum.getEnumByValue("AI_EDIT"));
        assertNotNull(PictureEditActionEnum.getEnumByValue("SYNC_STATE"));
    }

    @Test
    void shouldDeserializeCollaborativeCanvasState() {
        Map<String, Object> state = new HashMap<>();
        state.put("scale", 1.2D);
        state.put("strokes", Collections.singletonList(Collections.singletonMap("color", "#ff5b45")));
        Map<String, Object> editData = Collections.singletonMap("state", state);
        PictureEditRequestMessage message = new PictureEditRequestMessage(
                "EDIT_ACTION", "BRUSH_STROKE", editData
        );

        PictureEditRequestMessage decoded = JSONUtil.toBean(JSONUtil.toJsonStr(message), PictureEditRequestMessage.class);
        assertEquals("BRUSH_STROKE", decoded.getEditAction());
        assertNotNull(decoded.getEditData().get("state"));
    }

    @Test
    void shouldSerializeViewerAndEditorPresence() {
        UserVO viewer = new UserVO();
        viewer.setId(1001L);
        viewer.setUserName("观看用户");

        PictureEditResponseMessage response = new PictureEditResponseMessage();
        response.setType("INFO");
        response.setViewers(List.of(viewer));
        response.setEditingUser(viewer);

        String json = JSONUtil.toJsonStr(response);
        assertTrue(json.contains("viewers"));
        assertTrue(json.contains("editingUser"));
        assertTrue(json.contains("观看用户"));
    }

    @Test
    void shouldKeepViewerConnectedButRejectEditRequest() throws Exception {
        PictureEditHandler handler = new PictureEditHandler();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(Collections.singletonMap("canEdit", false));
        when(session.isOpen()).thenReturn(true);

        handler.handleEnterEditMessage(null, session, null, 1001L);

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(messageCaptor.capture());
        String payload = messageCaptor.getValue().getPayload();
        assertTrue(payload.contains("ERROR"));
        assertTrue(payload.contains("没有图片编辑权限"));
    }

    @Test
    void shouldRejectQuickEditWhenAgentLeaseIsHeld() throws Exception {
        PictureEditHandler handler = new PictureEditHandler();
        EditLeaseService leaseService = mock(EditLeaseService.class);
        UserService userService = mock(UserService.class);
        ReflectionTestUtils.setField(handler, "editLeaseService", leaseService);
        ReflectionTestUtils.setField(handler, "userService", userService);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(Collections.singletonMap("canEdit", true));
        when(session.isOpen()).thenReturn(true);
        User user = new User();
        user.setId(7L);
        user.setUserName("Alice");
        when(leaseService.heldByOther(1001L, AgentConstant.EDIT_LOCK_MODE_QUICK, 7L, null))
                .thenReturn(true);
        when(leaseService.get(1001L)).thenReturn(new JSONObject().set("mode", AgentConstant.EDIT_LOCK_MODE_AGENT));

        handler.handleEnterEditMessage(null, session, user, 1001L);

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(messageCaptor.capture());
        assertTrue(messageCaptor.getValue().getPayload().contains("Agent 精修"));
        verify(leaseService, never()).forceAcquire(1001L, AgentConstant.EDIT_LOCK_MODE_QUICK, 7L, null);
    }
}
