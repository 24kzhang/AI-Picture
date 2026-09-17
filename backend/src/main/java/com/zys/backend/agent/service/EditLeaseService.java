package com.zys.backend.agent.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.zys.backend.constant.AgentConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * QUICK 与 AGENT 统一编辑租约。
 *
 * <p>租约保存在 Redis：{@code gallery:picture:edit-lock:{pictureId}}，TTL 60 秒，
 * 前端每 20 秒续租。快捷编辑与 Agent 精修互斥，同一用户同一模式可重入续租。</p>
 */
@Slf4j
@Service
public class EditLeaseService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 尝试获取或续租编辑租约
     *
     * @return 获取成功返回租约信息；被他人持有时返回 null
     */
    public JSONObject tryAcquire(Long pictureId, String mode, Long userId, Long sessionId) {
        String key = key(pictureId);
        JSONObject lease = new JSONObject()
                .set("mode", mode)
                .set("userId", userId)
                .set("sessionId", sessionId)
                .set("acquiredAt", System.currentTimeMillis());
        String value = lease.toString();
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, value, AgentConstant.EDIT_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (Boolean.TRUE.equals(acquired)) {
            return lease;
        }
        JSONObject current = get(pictureId);
        if (current != null && isOwner(current, mode, userId, sessionId)) {
            renewInternal(key, current);
            return current;
        }
        return null;
    }

    /**
     * 续租，仅持有者可续
     *
     * @return 是否续租成功
     */
    public boolean renew(Long pictureId, String mode, Long userId, Long sessionId) {
        String key = key(pictureId);
        JSONObject current = get(pictureId);
        if (current == null) {
            return false;
        }
        if (!isOwner(current, mode, userId, sessionId)) {
            return false;
        }
        renewInternal(key, current);
        return true;
    }

    /**
     * 释放租约，仅持有者可释放
     */
    public boolean release(Long pictureId, String mode, Long userId, Long sessionId) {
        String key = key(pictureId);
        JSONObject current = get(pictureId);
        if (current == null) {
            return true;
        }
        if (!isOwner(current, mode, userId, sessionId)) {
            return false;
        }
        // 值仍属于自己才删除，避免误删他人租约
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value != null) {
            JSONObject latest = JSONUtil.parseObj(value);
            if (isOwner(latest, mode, userId, sessionId)) {
                stringRedisTemplate.delete(key);
            }
        }
        return true;
    }

    /**
     * 强制写入租约（仅用于会话创建成功后立即绑定 sessionId 的场景）
     */
    public void forceAcquire(Long pictureId, String mode, Long userId, Long sessionId) {
        JSONObject lease = new JSONObject()
                .set("mode", mode)
                .set("userId", userId)
                .set("sessionId", sessionId)
                .set("acquiredAt", System.currentTimeMillis());
        stringRedisTemplate.opsForValue().set(key(pictureId), lease.toString(),
                AgentConstant.EDIT_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 查询当前租约，不存在返回 null
     */
    public JSONObject get(Long pictureId) {
        String value = stringRedisTemplate.opsForValue().get(key(pictureId));
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return JSONUtil.parseObj(value);
        } catch (Exception e) {
            log.warn("编辑租约值解析失败，pictureId={}，value={}", pictureId, value);
            return null;
        }
    }

    /**
     * 租约是否被“他人或其他模式”持有
     */
    public boolean heldByOther(Long pictureId, String mode, Long userId, Long sessionId) {
        JSONObject current = get(pictureId);
        return current != null && !isOwner(current, mode, userId, sessionId);
    }

    private boolean isOwner(JSONObject lease, String mode, Long userId, Long sessionId) {
        if (!StrUtil.equals(lease.getStr("mode"), mode)) {
            return false;
        }
        if (!java.util.Objects.equals(lease.getLong("userId"), userId)) {
            return false;
        }
        Long leaseSessionId = lease.getLong("sessionId");
        if (sessionId == null || leaseSessionId == null) {
            return leaseSessionId == null && sessionId == null;
        }
        return leaseSessionId.equals(sessionId);
    }

    private void renewInternal(String key, JSONObject lease) {
        lease.set("acquiredAt", System.currentTimeMillis());
        stringRedisTemplate.opsForValue().set(key, lease.toString(),
                AgentConstant.EDIT_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
    }

    public static String key(Long pictureId) {
        return AgentConstant.EDIT_LOCK_KEY_PREFIX + pictureId;
    }
}
