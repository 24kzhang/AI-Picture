package com.zys.backend.agent;

import cn.hutool.json.JSONObject;
import com.zys.backend.agent.service.EditLeaseService;
import com.zys.backend.constant.AgentConstant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EditLeaseService：QUICK 与 AGENT 互斥、续租、释放、过期（TTL 语义）
 */
class EditLeaseServiceTest {

    private static final String KEY = AgentConstant.EDIT_LOCK_KEY_PREFIX + 100L;

    private EditLeaseService leaseService;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        leaseService = new EditLeaseService();
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ReflectionTestUtils.setField(leaseService, "stringRedisTemplate", redisTemplate);
    }

    @Test
    void acquireSucceedsWhenNoLease() {
        when(valueOperations.setIfAbsent(eq(KEY), anyString(), eq(AgentConstant.EDIT_LOCK_TTL_SECONDS), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        JSONObject lease = leaseService.tryAcquire(100L, AgentConstant.EDIT_LOCK_MODE_AGENT, 2L, 9L);
        assertNotNull(lease);
        assertEquals(AgentConstant.EDIT_LOCK_MODE_AGENT, lease.getStr("mode"));
        assertEquals(Long.valueOf(2L), lease.getLong("userId"));
        assertEquals(Long.valueOf(9L), lease.getLong("sessionId"));
    }

    @Test
    void acquireFailsWhenHeldByOtherUserOrMode() {
        when(valueOperations.setIfAbsent(eq(KEY), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);
        JSONObject holder = new JSONObject()
                .set("mode", AgentConstant.EDIT_LOCK_MODE_QUICK)
                .set("userId", 3L)
                .set("sessionId", (Object) null);
        when(valueOperations.get(KEY)).thenReturn(holder.toString());

        // Agent 模式尝试获取被 QUICK 持有的租约 → 互斥失败
        assertNull(leaseService.tryAcquire(100L, AgentConstant.EDIT_LOCK_MODE_AGENT, 2L, 9L));
        assertTrue(leaseService.heldByOther(100L, AgentConstant.EDIT_LOCK_MODE_AGENT, 2L, 9L));
        // 持有者本人 QUICK 模式可重入
        assertFalse(leaseService.heldByOther(100L, AgentConstant.EDIT_LOCK_MODE_QUICK, 3L, null));
    }

    @Test
    void acquireRefreshesForSameOwner() {
        when(valueOperations.setIfAbsent(eq(KEY), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);
        JSONObject holder = new JSONObject()
                .set("mode", AgentConstant.EDIT_LOCK_MODE_AGENT)
                .set("userId", 2L)
                .set("sessionId", 9L);
        when(valueOperations.get(KEY)).thenReturn(holder.toString());
        JSONObject lease = leaseService.tryAcquire(100L, AgentConstant.EDIT_LOCK_MODE_AGENT, 2L, 9L);
        assertNotNull(lease);
        verify(valueOperations).set(eq(KEY), anyString(),
                eq(AgentConstant.EDIT_LOCK_TTL_SECONDS), eq(TimeUnit.SECONDS));
    }

    @Test
    void renewOnlyByOwner() {
        JSONObject holder = new JSONObject()
                .set("mode", AgentConstant.EDIT_LOCK_MODE_AGENT)
                .set("userId", 2L)
                .set("sessionId", 9L);
        when(valueOperations.get(KEY)).thenReturn(holder.toString());

        assertTrue(leaseService.renew(100L, AgentConstant.EDIT_LOCK_MODE_AGENT, 2L, 9L));
        verify(valueOperations).set(eq(KEY), anyString(),
                eq(AgentConstant.EDIT_LOCK_TTL_SECONDS), eq(TimeUnit.SECONDS));

        // 其他人无法续租，也不再写 key
        assertFalse(leaseService.renew(100L, AgentConstant.EDIT_LOCK_MODE_AGENT, 77L, 9L));
        assertFalse(leaseService.renew(100L, AgentConstant.EDIT_LOCK_MODE_QUICK, 2L, 9L));
    }

    @Test
    void renewFailsWhenLeaseExpired() {
        when(valueOperations.get(KEY)).thenReturn(null);
        assertFalse(leaseService.renew(100L, AgentConstant.EDIT_LOCK_MODE_AGENT, 2L, 9L));
        // 过期后释放返回成功（幂等）
        assertTrue(leaseService.release(100L, AgentConstant.EDIT_LOCK_MODE_AGENT, 2L, 9L));
        verify(redisTemplate, never()).delete(KEY);
    }

    @Test
    void releaseOnlyByOwner() {
        JSONObject holder = new JSONObject()
                .set("mode", AgentConstant.EDIT_LOCK_MODE_AGENT)
                .set("userId", 2L)
                .set("sessionId", 9L);
        when(valueOperations.get(KEY)).thenReturn(holder.toString());

        assertFalse(leaseService.release(100L, AgentConstant.EDIT_LOCK_MODE_AGENT, 77L, 9L));
        verify(redisTemplate, never()).delete(any(String.class));

        assertTrue(leaseService.release(100L, AgentConstant.EDIT_LOCK_MODE_AGENT, 2L, 9L));
        verify(redisTemplate).delete(KEY);
    }
}
