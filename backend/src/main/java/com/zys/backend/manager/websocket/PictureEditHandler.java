package com.zys.backend.manager.websocket;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.zys.backend.agent.service.EditLeaseService;
import com.zys.backend.constant.AgentConstant;
import com.zys.backend.manager.auth.SpaceUserAuthManager;
import com.zys.backend.manager.auth.model.SpaceUserPermissionConstant;
import com.zys.backend.manager.websocket.disruptor.PictureEditEventProducer;
import com.zys.backend.manager.websocket.model.PictureEditActionEnum;
import com.zys.backend.manager.websocket.model.PictureEditMessageTypeEnum;
import com.zys.backend.manager.websocket.model.PictureEditRequestMessage;
import com.zys.backend.manager.websocket.model.PictureEditResponseMessage;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.Space;
import com.zys.backend.model.entity.User;
import com.zys.backend.model.vo.UserVO;
import com.zys.backend.service.PictureService;
import com.zys.backend.service.SpaceService;
import com.zys.backend.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图片编辑 WebSocket 处理器
 */
@Component
@Slf4j
public class PictureEditHandler extends TextWebSocketHandler {

    @Resource
    private UserService userService;

    @Resource
    private PictureService pictureService;

    @Resource
    private SpaceService spaceService;

    @Resource
    private SpaceUserAuthManager spaceUserAuthManager;

    @Resource
    @Lazy
    private PictureEditEventProducer pictureEditEventProducer;

    @Resource
    private EditLeaseService editLeaseService;

    // 每张图片的编辑状态，key: pictureId, value: 当前正在编辑的用户 ID
    private final Map<Long, Long> pictureEditingUsers = new ConcurrentHashMap<>();

    // 保存所有连接的会话，key: pictureId, value: 用户会话集合
    private final Map<Long, Set<WebSocketSession>> pictureSessions = new ConcurrentHashMap<>();

    /**
     * 连接建立成功
     *
     * @param session
     * @throws Exception
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        super.afterConnectionEstablished(session);
        // 保存会话到集合中
        User user = (User) session.getAttributes().get("user");
        Long pictureId = (Long) session.getAttributes().get("pictureId");
        if (user == null || pictureId == null || !ensureCurrentPermission(session, false)) {
            return;
        }
        pictureSessions.putIfAbsent(pictureId, ConcurrentHashMap.newKeySet());
        pictureSessions.get(pictureId).add(session);
        // 构造响应，发送加入编辑的消息通知
        PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
        pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.INFO.getValue());
        String message = String.format("用户 %s 加入编辑", user.getUserName());
        pictureEditResponseMessage.setMessage(message);
        pictureEditResponseMessage.setUser(userService.getUserVO(user));
        // 广播给所有用户
        broadcastToPicture(pictureId, pictureEditResponseMessage);
    }

    /**
     * 收到前端发送的消息，根据消息类别处理消息
     *
     * @param session
     * @param message
     * @throws Exception
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        super.handleTextMessage(session, message);
        // 获取消息内容，将 JSON 转换为 PictureEditRequestMessage
        PictureEditRequestMessage pictureEditRequestMessage;
        try {
            pictureEditRequestMessage = JSONUtil.toBean(message.getPayload(), PictureEditRequestMessage.class);
        } catch (Exception e) {
            sendError(session, "消息格式错误");
            return;
        }
        // 从 Session 属性中获取到公共参数
        User user = (User) session.getAttributes().get("user");
        Long pictureId = (Long) session.getAttributes().get("pictureId");
        if (!ensureCurrentPermission(session, false)) {
            return;
        }
        // 根据消息类型处理消息（生产消息到 Disruptor 环形队列中）
        pictureEditEventProducer.publishEvent(pictureEditRequestMessage, session, user, pictureId);
    }

    /**
     * 进入编辑状态
     *
     * @param pictureEditRequestMessage
     * @param session
     * @param user
     * @param pictureId
     */
    public void handleEnterEditMessage(PictureEditRequestMessage pictureEditRequestMessage, WebSocketSession session, User user, Long pictureId) throws IOException {
        // 观看者无需查询数据库即可得到明确反馈；真正的编辑请求仍会在下面重新核验权限。
        if (!Boolean.TRUE.equals(session.getAttributes().get("canEdit"))) {
            sendError(session, "你可以观看当前工作台，但没有图片编辑权限");
            return;
        }
        // 快速失败提示；最终是否占用租约仍由下面的原子 tryAcquire 决定。
        if (editLeaseService.heldByOther(pictureId, AgentConstant.EDIT_LOCK_MODE_QUICK, user.getId(), null)) {
            JSONObject holder = editLeaseService.get(pictureId);
            String mode = holder == null ? null : holder.getStr("mode");
            sendError(session, AgentConstant.EDIT_LOCK_MODE_AGENT.equals(mode)
                    ? "图片正在进行 Agent 精修，暂时无法进入快捷编辑"
                    : "图片正在被其他用户编辑，暂时无法进入快捷编辑");
            return;
        }
        if (!ensureCurrentPermission(session, true)) {
            return;
        }
        // 统一编辑租约：一次原子获取同时完成“检查 + 写入”，避免两个窗口先后检查后互相覆盖租约。
        if (editLeaseService.tryAcquire(pictureId, AgentConstant.EDIT_LOCK_MODE_QUICK, user.getId(), null) == null) {
            JSONObject holder = editLeaseService.get(pictureId);
            String mode = holder == null ? null : holder.getStr("mode");
            sendError(session, AgentConstant.EDIT_LOCK_MODE_AGENT.equals(mode)
                    ? "图片正在进行 Agent 精修，暂时无法进入快捷编辑"
                    : "图片正在被其他用户编辑，暂时无法进入快捷编辑");
            return;
        }
        // 没有用户正在编辑该图片，才能进入编辑
        Long currentEditor = pictureEditingUsers.putIfAbsent(pictureId, user.getId());
        if (currentEditor != null && !currentEditor.equals(user.getId())) {
            // Redis 租约可能已过期而本机内存仍保留旧连接；不能让新租约与本机状态分叉。
            editLeaseService.release(pictureId, AgentConstant.EDIT_LOCK_MODE_QUICK, user.getId(), null);
            sendError(session, "图片正在被其他用户编辑，暂时无法进入快捷编辑");
            return;
        }
        if (currentEditor == null) {
            // 构造响应，发送加入编辑的消息通知
            PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
            pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.ENTER_EDIT.getValue());
            String message = String.format("用户 %s 开始编辑图片", user.getUserName());
            pictureEditResponseMessage.setMessage(message);
            pictureEditResponseMessage.setUser(userService.getUserVO(user));
            // 广播给所有用户
            broadcastToPicture(pictureId, pictureEditResponseMessage);
        }
    }

    /**
     * 处理编辑操作
     *
     * @param pictureEditRequestMessage
     * @param session
     * @param user
     * @param pictureId
     */
    public void handleEditActionMessage(PictureEditRequestMessage pictureEditRequestMessage, WebSocketSession session, User user, Long pictureId) throws IOException {
        if (!Boolean.TRUE.equals(session.getAttributes().get("canEdit"))) {
            sendError(session, "你可以观看当前工作台，但没有图片编辑权限");
            return;
        }
        if (!ensureCurrentPermission(session, true)) {
            return;
        }
        // 正在编辑的用户
        Long editingUserId = pictureEditingUsers.get(pictureId);
        String editAction = pictureEditRequestMessage.getEditAction();
        PictureEditActionEnum actionEnum = PictureEditActionEnum.getEnumByValue(editAction);
        if (actionEnum == null) {
            log.error("无效的编辑动作");
            return;
        }
        // 确认是当前的编辑者
        if (editingUserId != null && editingUserId.equals(user.getId())) {
            // 编辑动作即视为续租，避免 60 秒 TTL 到期被 Agent 抢占
            if (!editLeaseService.renew(pictureId, AgentConstant.EDIT_LOCK_MODE_QUICK, user.getId(), null)) {
                releaseEditing(pictureId, user, session, "图片编辑租约已失效，请重新获取编辑权");
                return;
            }
            // 构造响应，发送具体操作的通知
            PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
            pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.EDIT_ACTION.getValue());
            String message = String.format("%s 执行 %s", user.getUserName(), actionEnum.getText());
            pictureEditResponseMessage.setMessage(message);
            pictureEditResponseMessage.setEditAction(editAction);
            pictureEditResponseMessage.setEditData(pictureEditRequestMessage.getEditData());
            pictureEditResponseMessage.setUser(userService.getUserVO(user));
            // 广播给除了当前客户端之外的其他用户，否则会造成重复编辑
            broadcastToPicture(pictureId, pictureEditResponseMessage, session);
        }
    }


    /**
     * 退出编辑状态
     *
     * @param pictureEditRequestMessage
     * @param session
     * @param user
     * @param pictureId
     */
    public void handleExitEditMessage(PictureEditRequestMessage pictureEditRequestMessage, WebSocketSession session, User user, Long pictureId) throws IOException {
        if (user == null || pictureId == null) {
            return;
        }
        // 正在编辑的用户
        Long editingUserId = pictureEditingUsers.get(pictureId);
        // 确认是当前的编辑者
        if (editingUserId != null && editingUserId.equals(user.getId())) {
            // 移除用户正在编辑该图片
            pictureEditingUsers.remove(pictureId);
            // 释放统一编辑租约
            editLeaseService.release(pictureId, AgentConstant.EDIT_LOCK_MODE_QUICK, user.getId(), null);
            // 构造响应，发送退出编辑的消息通知
            PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
            pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.EXIT_EDIT.getValue());
            String message = String.format("用户 %s 退出编辑图片", user.getUserName());
            pictureEditResponseMessage.setMessage(message);
            pictureEditResponseMessage.setUser(userService.getUserVO(user));
            broadcastToPicture(pictureId, pictureEditResponseMessage);
        }
    }

    /**
     * 关闭连接
     *
     * @param session
     * @param status
     * @throws Exception
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        super.afterConnectionClosed(session, status);
        // 从 Session 属性中获取到公共参数
        User user = (User) session.getAttributes().get("user");
        Long pictureId = (Long) session.getAttributes().get("pictureId");
        // 移除当前用户的编辑状态
        if (user != null && pictureId != null) {
            handleExitEditMessage(null, session, user, pictureId);
        }
        // 删除会话
        Set<WebSocketSession> sessionSet = pictureId == null ? null : pictureSessions.get(pictureId);
        if (sessionSet != null) {
            sessionSet.remove(session);
            if (sessionSet.isEmpty()) {
            pictureSessions.remove(pictureId, sessionSet);
            }
        }
        // 通知其他用户，该用户已经离开编辑
        PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
        pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.INFO.getValue());
        if (user == null || pictureId == null) {
            return;
        }
        String message = String.format("用户 %s 离开编辑", user.getUserName());
        pictureEditResponseMessage.setMessage(message);
        pictureEditResponseMessage.setUser(userService.getUserVO(user));
        broadcastToPicture(pictureId, pictureEditResponseMessage);
    }

    /**
     * 获取当前打开工作台的用户，按用户 ID 去重。
     */
    private List<UserVO> getViewerUsers(Long pictureId) {
        Map<Long, UserVO> viewerMap = new LinkedHashMap<>();
        if (pictureId == null) {
            return new ArrayList<>();
        }
        Set<WebSocketSession> sessionSet = pictureSessions.get(pictureId);
        if (CollUtil.isEmpty(sessionSet)) {
            return new ArrayList<>();
        }
        Picture picture = pictureService.getById(pictureId);
        for (WebSocketSession session : new ArrayList<>(sessionSet)) {
            if (!session.isOpen()) {
                continue;
            }
            User viewer = (User) session.getAttributes().get("user");
            User currentViewer = viewer == null ? null : userService.getById(viewer.getId());
            if (currentViewer != null && currentViewer.getId() != null
                    && hasPermission(picture, currentViewer, SpaceUserPermissionConstant.PICTURE_VIEW)) {
                session.getAttributes().put("user", currentViewer);
                viewerMap.putIfAbsent(currentViewer.getId(), userService.getUserVO(currentViewer));
            } else {
                revokeSession(session, "你已失去该图片的查看权限");
            }
        }
        return new ArrayList<>(viewerMap.values());
    }

    /**
     * 获取当前编辑者，供后加入的观看用户恢复准确状态。
     */
    private UserVO getEditingUser(Long pictureId) {
        JSONObject lease = editLeaseService.get(pictureId);
        if (lease != null && AgentConstant.EDIT_LOCK_MODE_AGENT.equals(lease.getStr("mode"))) {
            User holder = userService.getById(lease.getLong("userId"));
            return holder == null ? null : userService.getUserVO(holder);
        }
        Long editingUserId = pictureEditingUsers.get(pictureId);
        if (editingUserId == null) {
            return null;
        }
        Set<WebSocketSession> sessionSet = pictureSessions.get(pictureId);
        if (CollUtil.isNotEmpty(sessionSet)) {
            for (WebSocketSession session : new ArrayList<>(sessionSet)) {
                User viewer = (User) session.getAttributes().get("user");
                User currentViewer = viewer == null ? null : userService.getById(viewer.getId());
                Picture picture = pictureService.getById(pictureId);
                if (currentViewer != null && editingUserId.equals(currentViewer.getId())
                        && hasPermission(picture, currentViewer, SpaceUserPermissionConstant.PICTURE_EDIT)) {
                    return userService.getUserVO(currentViewer);
                }
            }
        }
        return null;
    }

    private void sendError(WebSocketSession session, String message) throws IOException {
        PictureEditResponseMessage responseMessage = new PictureEditResponseMessage();
        responseMessage.setType(PictureEditMessageTypeEnum.ERROR.getValue());
        responseMessage.setMessage(message);
        ObjectMapper objectMapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        objectMapper.registerModule(module);
        if (session.isOpen()) {
            synchronized (session) { if (session.isOpen()) session.sendMessage(new TextMessage(objectMapper.writeValueAsString(responseMessage))); }
        }
    }

    /** 每条消息都重新查询图片和空间成员关系，避免握手时的权限快照在撤权后继续生效。 */
    private boolean ensureCurrentPermission(WebSocketSession session, boolean requireEdit) throws IOException {
        if (session == null || !session.isOpen()) {
            return false;
        }
        User oldUser = (User) session.getAttributes().get("user");
        Long pictureId = (Long) session.getAttributes().get("pictureId");
        if (oldUser == null || pictureId == null) {
            revokeSession(session, "协同会话信息已失效");
            return false;
        }
        User currentUser = userService.getById(oldUser.getId());
        Picture picture = pictureService.getById(pictureId);
        boolean canView = currentUser != null && picture != null
                && hasPermission(picture, currentUser, SpaceUserPermissionConstant.PICTURE_VIEW);
        if (!canView) {
            revokeSession(session, "你已失去该图片的查看权限");
            return false;
        }
        boolean canEdit = hasPermission(picture, currentUser, SpaceUserPermissionConstant.PICTURE_EDIT);
        session.getAttributes().put("user", currentUser);
        session.getAttributes().put("canEdit", canEdit);
        if (requireEdit && !canEdit) {
            if (oldUser.getId().equals(pictureEditingUsers.get(pictureId))) {
                releaseEditing(pictureId, currentUser, session, null);
            }
            return false;
        }
        return true;
    }

    private boolean hasPermission(Picture picture, User user, String permission) {
        if (picture == null || user == null) {
            return false;
        }
        Long spaceId = picture.getSpaceId();
        Space space = spaceId == null ? null : spaceService.getById(spaceId);
        return spaceUserAuthManager.getPermissionList(space, user).contains(permission);
    }

    private void releaseEditing(Long pictureId, User user, WebSocketSession session, String error) throws IOException {
        if (pictureId == null || user == null || !user.getId().equals(pictureEditingUsers.get(pictureId))) {
            if (error != null && session != null) {
                sendError(session, error);
            }
            return;
        }
        pictureEditingUsers.remove(pictureId, user.getId());
        editLeaseService.release(pictureId, AgentConstant.EDIT_LOCK_MODE_QUICK, user.getId(), null);
        if (error != null && session != null) {
            sendError(session, error);
        }
    }

    private void revokeSession(WebSocketSession session, String message) {
        if (session == null) {
            return;
        }
        Long pictureId = (Long) session.getAttributes().get("pictureId");
        User user = (User) session.getAttributes().get("user");
        try {
            releaseEditing(pictureId, user, session, message);
        } catch (IOException e) {
            log.debug("发送协同权限失效提示失败", e);
        }
        Set<WebSocketSession> set = pictureId == null ? null : pictureSessions.get(pictureId);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) {
                pictureSessions.remove(pictureId, set);
            }
        }
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.POLICY_VIOLATION);
            }
        } catch (IOException e) {
            log.debug("关闭失效协同会话失败", e);
        }
    }

    private void attachPresence(Long pictureId, PictureEditResponseMessage responseMessage) {
        responseMessage.setViewers(getViewerUsers(pictureId));
        responseMessage.setEditingUser(getEditingUser(pictureId));
    }

    /**
     * 广播给该图片的所有用户（支持排除掉某个 Session）
     *
     * @param pictureId
     * @param pictureEditResponseMessage
     * @param excludeSession
     */
    private void broadcastToPicture(Long pictureId, PictureEditResponseMessage pictureEditResponseMessage, WebSocketSession excludeSession) throws IOException {
        if (pictureId == null || pictureEditResponseMessage == null) {
            return;
        }
        Set<WebSocketSession> sessionSet = pictureSessions.get(pictureId);
        if (CollUtil.isNotEmpty(sessionSet)) {
            attachPresence(pictureId, pictureEditResponseMessage);
            // 创建 ObjectMapper
            ObjectMapper objectMapper = new ObjectMapper();
            // 配置序列化：将 Long 类型转为 String，解决丢失精度问题
            SimpleModule module = new SimpleModule();
            module.addSerializer(Long.class, ToStringSerializer.instance);
            module.addSerializer(Long.TYPE, ToStringSerializer.instance); // 支持 long 基本类型
            objectMapper.registerModule(module);
            // 序列化为 JSON 字符串
            String message = objectMapper.writeValueAsString(pictureEditResponseMessage);
            TextMessage textMessage = new TextMessage(message);
            Picture picture = pictureService.getById(pictureId);
            for (WebSocketSession session : new ArrayList<>(sessionSet)) {
                // 排除掉的 session 不发送
                if (excludeSession != null && session.equals(excludeSession)) {
                    continue;
                }
                User viewer = (User) session.getAttributes().get("user");
                User currentViewer = viewer == null ? null : userService.getById(viewer.getId());
                if (session.isOpen() && hasPermission(picture, currentViewer, SpaceUserPermissionConstant.PICTURE_VIEW)) {
                    synchronized (session) { if (session.isOpen()) session.sendMessage(textMessage); }
                } else if (session.isOpen()) {
                    revokeSession(session, "你已失去该图片的查看权限");
                }
            }
        }
    }

    /**
     * 广播给该图片的所有用户
     *
     * @param pictureId
     * @param pictureEditResponseMessage
     */
    private void broadcastToPicture(Long pictureId, PictureEditResponseMessage pictureEditResponseMessage) throws IOException {
        broadcastToPicture(pictureId, pictureEditResponseMessage, null);
    }
    @org.springframework.context.event.EventListener
    public void onCollaborationChanged(com.zys.backend.agent.model.PictureCollaborationChanged event) {
        PictureEditResponseMessage response = new PictureEditResponseMessage();
        response.setType("COLLABORATION_CHANGED");
        try { broadcastToPicture(event.getPictureId(), response); }
        catch (IOException e) { log.debug("协同通知失败，客户端将通过快照恢复", e); }
    }

    /**
     * 成员移除或降权的事务提交后立即关闭该成员在目标空间内的图片协同连接。
     * 关闭动作放在提交阶段，避免事务回滚时误伤仍有效的会话。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSpaceUserPermissionChanged(SpaceUserPermissionChangedEvent event) {
        if (event == null || event.getSpaceId() == null || event.getUserId() == null) {
            return;
        }
        String message = "空间成员权限已变更，请重新进入图片协同";
        for (Set<WebSocketSession> sessionSet : new ArrayList<>(pictureSessions.values())) {
            for (WebSocketSession session : new ArrayList<>(sessionSet)) {
                User user = (User) session.getAttributes().get("user");
                Long pictureId = (Long) session.getAttributes().get("pictureId");
                if (user == null || !event.getUserId().equals(user.getId()) || pictureId == null) {
                    continue;
                }
                Picture picture = pictureService.getById(pictureId);
                if (picture != null && event.getSpaceId().equals(picture.getSpaceId())) {
                    revokeSession(session, message);
                }
            }
        }
    }
}
















