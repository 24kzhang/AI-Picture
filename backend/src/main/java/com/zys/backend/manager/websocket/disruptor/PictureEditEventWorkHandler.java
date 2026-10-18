package com.zys.backend.manager.websocket.disruptor;

import cn.hutool.json.JSONUtil;
import com.lmax.disruptor.WorkHandler;
import com.zys.backend.manager.websocket.PictureEditHandler;
import com.zys.backend.manager.websocket.model.PictureEditMessageTypeEnum;
import com.zys.backend.manager.websocket.model.PictureEditRequestMessage;
import com.zys.backend.manager.websocket.model.PictureEditResponseMessage;
import com.zys.backend.model.entity.User;
import com.zys.backend.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import javax.annotation.Resource;

/**
 * 图片编辑事件处理器（消费者）
 */
@Component
@Slf4j
public class PictureEditEventWorkHandler implements WorkHandler<PictureEditEvent> {

    @Resource
    private PictureEditHandler pictureEditHandler;

    @Resource
    private UserService userService;

    @Override
    public void onEvent(PictureEditEvent pictureEditEvent) throws Exception {
        if (pictureEditEvent == null || pictureEditEvent.getSession() == null
                || pictureEditEvent.getPictureEditRequestMessage() == null) {
            log.warn("忽略空的图片协同事件");
            return;
        }
        PictureEditRequestMessage pictureEditRequestMessage = pictureEditEvent.getPictureEditRequestMessage();
        WebSocketSession session = pictureEditEvent.getSession();
        User user = pictureEditEvent.getUser();
        Long pictureId = pictureEditEvent.getPictureId();
        try {
            PictureEditMessageTypeEnum pictureEditMessageTypeEnum =
                    PictureEditMessageTypeEnum.getEnumByValue(pictureEditRequestMessage.getType());
            if (pictureEditMessageTypeEnum == null) {
                sendError(session, user, "消息类型错误");
                return;
            }
            switch (pictureEditMessageTypeEnum) {
                case ENTER_EDIT:
                    pictureEditHandler.handleEnterEditMessage(pictureEditRequestMessage, session, user, pictureId);
                    break;
                case EXIT_EDIT:
                    pictureEditHandler.handleExitEditMessage(pictureEditRequestMessage, session, user, pictureId);
                    break;
                case EDIT_ACTION:
                    pictureEditHandler.handleEditActionMessage(pictureEditRequestMessage, session, user, pictureId);
                    break;
                default:
                    sendError(session, user, "消息类型错误");
                    break;
            }
        } catch (Exception e) {
            // 单条坏消息不能让 Disruptor 消费线程退出，后续协同事件必须继续消费。
            log.error("处理图片协同事件失败，pictureId={}，type={}", pictureId,
                    pictureEditRequestMessage.getType(), e);
            try {
                sendError(session, user, "图片协同操作失败，请重试");
            } catch (Exception sendError) {
                log.debug("发送图片协同错误消息失败", sendError);
            }
        }
    }

    private void sendError(WebSocketSession session, User user, String message) throws Exception {
        if (session == null || !session.isOpen()) {
            return;
        }
        PictureEditResponseMessage response = new PictureEditResponseMessage();
        response.setType(PictureEditMessageTypeEnum.ERROR.getValue());
        response.setMessage(message);
        if (user != null) {
            response.setUser(userService.getUserVO(user));
        }
        session.sendMessage(new TextMessage(JSONUtil.toJsonStr(response)));
    }
}
