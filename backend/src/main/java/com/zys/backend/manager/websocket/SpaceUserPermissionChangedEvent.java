package com.zys.backend.manager.websocket;

/**
 * 空间成员被移除或权限变更后的协同会话撤权事件。
 *
 * <p>事件在成员事务提交后处理，避免回滚的修改提前关闭有效会话。</p>
 */
public final class SpaceUserPermissionChangedEvent {

    private final Long spaceId;
    private final Long userId;

    public SpaceUserPermissionChangedEvent(Long spaceId, Long userId) {
        this.spaceId = spaceId;
        this.userId = userId;
    }

    public Long getSpaceId() {
        return spaceId;
    }

    public Long getUserId() {
        return userId;
    }
}
