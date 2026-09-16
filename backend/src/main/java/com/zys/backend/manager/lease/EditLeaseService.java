package com.zys.backend.manager.lease;

import cn.hutool.json.JSONUtil;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 统一编辑租约：快捷编辑（QUICK）与 Agent 精修（AGENT）共用一把 Redis 锁。
 * <p>锁内容含 mode/userId/sessionId/lockToken/acquiredAt；TTL 60 秒，
 * 持有方需周期性续租。锁只是交互保护，最终覆盖门槛仍是 MySQL editVersion。</p>
 */
@Slf4j
@Service
public class EditLeaseService {

    public static final String MODE_QUICK = "QUICK";
    public static final String MODE_AGENT = "AGENT";

    private static final String KEY_PREFIX = "gallery:picture:edit-lock:";
    private static final long TTL_SECONDS = 60;

    /**
     * 续租：值中 lockToken 匹配才延长 TTL（原子）
     */
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('get', KEYS[1]) "
                    + "if v and cjson.decode(v).lockToken == ARGV[1] "
                    + "then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    /**
     * 释放：值中 lockToken 匹配才删除（原子）
     */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('get', KEYS[1]) "
                    + "if v and cjson.decode(v).lockToken == ARGV[1] "
                    + "then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 租约内容
     */
    @Data
    public static class Lease {

        private String mode;

        private Long userId;

        private String sessionId;

        private String lockToken;

        private Long acquiredAt;
    }

    /**
     * 尝试获取租约；被他人（或同用户其他模式）持有时返回 null。
     * 同用户同模式重复获取视为接管刷新。
     */
    public Lease tryAcquire(Long pictureId, String mode, Long userId, String sessionId) {
        String key = keyOf(pictureId);
        Lease lease = new Lease();
        lease.setMode(mode);
        lease.setUserId(userId);
        lease.setSessionId(sessionId);
        lease.setLockToken(UUID.randomUUID().toString());
        lease.setAcquiredAt(System.currentTimeMillis());
        String value = JSONUtil.toJsonStr(lease);
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, value, TTL_SECONDS, TimeUnit.SECONDS);
        if (Boolean.TRUE.equals(acquired)) {
            return lease;
        }
        Lease current = current(pictureId);
        if (current != null && Objects.equals(current.getUserId(), userId)
                && Objects.equals(current.getMode(), mode)) {
            // 同用户同模式：接管刷新（新 token 生效）
            stringRedisTemplate.opsForValue().set(key, value, TTL_SECONDS, TimeUnit.SECONDS);
            return lease;
        }
        return null;
    }

    /**
     * 读取当前租约；无锁或值损坏返回 null
     */
    public Lease current(Long pictureId) {
        String value = stringRedisTemplate.opsForValue().get(keyOf(pictureId));
        if (value == null) {
            return null;
        }
        try {
            return JSONUtil.toBean(value, Lease.class);
        } catch (Exception e) {
            log.warn("编辑租约值解析失败，key={}：{}", pictureId, e.getMessage());
            return null;
        }
    }

    /**
     * 按 lockToken 续租
     */
    public boolean renew(Long pictureId, String lockToken) {
        Long result = stringRedisTemplate.execute(RENEW_SCRIPT,
                Collections.singletonList(keyOf(pictureId)),
                lockToken, String.valueOf(TTL_SECONDS * 1000));
        return result != null && result > 0;
    }

    /**
     * 按会话身份续租（服务端心跳）
     */
    public boolean renewForSession(Long pictureId, String mode, Long userId, String sessionId) {
        Lease current = current(pictureId);
        if (!isSameHolder(current, mode, userId, sessionId)) {
            return false;
        }
        return renew(pictureId, current.getLockToken());
    }

    /**
     * 按 lockToken 释放
     */
    public boolean release(Long pictureId, String lockToken) {
        Long result = stringRedisTemplate.execute(RELEASE_SCRIPT,
                Collections.singletonList(keyOf(pictureId)), lockToken);
        return result != null && result > 0;
    }

    /**
     * 按会话身份释放
     */
    public boolean releaseForSession(Long pictureId, String mode, Long userId, String sessionId) {
        Lease current = current(pictureId);
        if (!isSameHolder(current, mode, userId, sessionId)) {
            return false;
        }
        return release(pictureId, current.getLockToken());
    }

    /**
     * 判断租约是否由指定会话持有
     */
    public boolean isHeldBy(Long pictureId, String mode, Long userId, String sessionId) {
        return isSameHolder(current(pictureId), mode, userId, sessionId);
    }

    /**
     * 租约被他人持有时抛出业务异常（快捷保存与 Agent 精修互斥）
     */
    public void blockIfHeldByOther(Long pictureId, Long userId) {
        Lease current = current(pictureId);
        if (current == null || Objects.equals(current.getUserId(), userId)) {
            return;
        }
        String reason = MODE_AGENT.equals(current.getMode())
                ? "另一位用户正在使用 Agent 精修该图片，请稍后再试"
                : "另一位用户正在编辑该图片，请稍后再试";
        throw new BusinessException(ErrorCode.OPERATION_ERROR, reason);
    }

    private boolean isSameHolder(Lease lease, String mode, Long userId, String sessionId) {
        return lease != null
                && Objects.equals(lease.getMode(), mode)
                && Objects.equals(lease.getUserId(), userId)
                && Objects.equals(lease.getSessionId(), sessionId);
    }

    private String keyOf(Long pictureId) {
        return KEY_PREFIX + pictureId;
    }
}
