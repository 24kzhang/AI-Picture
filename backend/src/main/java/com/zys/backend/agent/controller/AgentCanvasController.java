package com.zys.backend.agent.controller;

import com.zys.backend.agent.service.AgentSessionService;
import com.zys.backend.agent.service.LayerDocumentService;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.entity.User;
import com.zys.backend.service.UserService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

/** The preview and version submission use the same current-canvas rendering. */
@RestController
public class AgentCanvasController {
    @Resource private UserService userService;
    @Resource private AgentSessionService sessionService;
    @Resource private LayerDocumentService documentService;

    @GetMapping("/agent-sessions/{sessionId}/canvas")
    public ResponseEntity<byte[]> canvas(@PathVariable Long sessionId, HttpServletRequest request) {
        User user = userService.getLoginUser(request);
        PictureEditSession session = sessionService.requireSession(sessionId, user, false);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .header("Cache-Control", "private, no-store")
                .body(documentService.currentCanvasBytes(session));
    }
}
