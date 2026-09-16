package com.zys.backend.manager.lease;

import cn.hutool.json.JSONUtil;
import com.zys.backend.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EditLeaseServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private EditLeaseService leaseService;

    private static final Long PICTURE_ID = 100L;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private String leaseJson(String mode, Long userId, String sessionId) {
        EditLeaseService.Lease lease = new EditLeaseService.Lease();
        lease.setMode(mode);
        lease.setUserId(userId);
        lease.setSessionId(sessionId);
        lease.setLockToken("token-abc");
        lease.setAcquiredAt(System.currentTimeMillis());
        return JSONUtil.toJsonStr(lease);
    }

    @Test
    void acquireSucceedsWhenFree() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        EditLeaseService.Lease lease = leaseService.tryAcquire(
                PICTURE_ID, EditLeaseService.MODE_AGENT, 2L, "55");

        assertNotNull(lease);
        assertEquals(EditLeaseService.MODE_AGENT, lease.getMode());
        assertEquals(2L, lease.getUserId());
        assertEquals("55", lease.getSessionId());
        assertNotNull(lease.getLockToken());
    }

    @Test
    void acquireFailsWhenHeldByOther() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);
        when(valueOperations.get(anyString()))
                .thenReturn(leaseJson(EditLeaseService.MODE_QUICK, 9L, "ws-1"));

        assertNull(leaseService.tryAcquire(PICTURE_ID, EditLeaseService.MODE_AGENT, 2L, "55"));
    }

    @Test
    void acquireFailsWhenSameUserOtherMode() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);
        when(valueOperations.get(anyString()))
                .thenReturn(leaseJson(EditLeaseService.MODE_QUICK, 2L, "ws-1"));

        assertNull(leaseService.tryAcquire(PICTURE_ID, EditLeaseService.MODE_AGENT, 2L, "55"));
    }

    @Test
    void sameUserSameModeTakesOver() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);
        when(valueOperations.get(anyString()))
                .thenReturn(leaseJson(EditLeaseService.MODE_AGENT, 2L, "55"));

        EditLeaseService.Lease lease = leaseService.tryAcquire(
                PICTURE_ID, EditLeaseService.MODE_AGENT, 2L, "55");

        assertNotNull(lease);
        // 接管刷新：写入新值
        verify(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void renewUsesAtomicScript() {
        when(stringRedisTemplate.execute(any(RedisScript.class), any(), any()))
                .thenReturn(1L);

        assertTrue(leaseService.renew(PICTURE_ID, "token-abc"));

        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), any(), args.capture(), args.capture());
    }

    @Test
    void renewFailsWhenTokenMismatch() {
        when(stringRedisTemplate.execute(any(RedisScript.class), any(), any()))
                .thenReturn(0L);

        assertFalse(leaseService.renew(PICTURE_ID, "wrong-token"));
    }

    @Test
    void renewForSessionChecksHolder() {
        when(valueOperations.get(anyString()))
                .thenReturn(leaseJson(EditLeaseService.MODE_AGENT, 2L, "55"));
        when(stringRedisTemplate.execute(any(RedisScript.class), any(), any()))
                .thenReturn(1L);

        assertTrue(leaseService.renewForSession(
                PICTURE_ID, EditLeaseService.MODE_AGENT, 2L, "55"));
        assertFalse(leaseService.renewForSession(
                PICTURE_ID, EditLeaseService.MODE_AGENT, 2L, "other-session"));
        assertFalse(leaseService.renewForSession(
                PICTURE_ID, EditLeaseService.MODE_QUICK, 2L, "55"));
    }

    @Test
    void releaseForSessionOnlyHolder() {
        when(valueOperations.get(anyString()))
                .thenReturn(leaseJson(EditLeaseService.MODE_AGENT, 2L, "55"));
        when(stringRedisTemplate.execute(any(RedisScript.class), any(), any()))
                .thenReturn(1L);

        assertTrue(leaseService.releaseForSession(
                PICTURE_ID, EditLeaseService.MODE_AGENT, 2L, "55"));
        assertFalse(leaseService.releaseForSession(
                PICTURE_ID, EditLeaseService.MODE_AGENT, 3L, "55"));
    }

    @Test
    void isHeldByMatchesFullIdentity() {
        when(valueOperations.get(anyString()))
                .thenReturn(leaseJson(EditLeaseService.MODE_AGENT, 2L, "55"));

        assertTrue(leaseService.isHeldBy(PICTURE_ID, EditLeaseService.MODE_AGENT, 2L, "55"));
        assertFalse(leaseService.isHeldBy(PICTURE_ID, EditLeaseService.MODE_AGENT, 2L, "56"));
    }

    @Test
    void blockIfHeldByOtherThrowsWithModeSpecificMessage() {
        when(valueOperations.get(anyString()))
                .thenReturn(leaseJson(EditLeaseService.MODE_AGENT, 9L, "77"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> leaseService.blockIfHeldByOther(PICTURE_ID, 2L));
        assertTrue(e.getMessage().contains("Agent 精修"));
    }

    @Test
    void blockIfHeldByOtherAllowsSameUser() {
        when(valueOperations.get(anyString()))
                .thenReturn(leaseJson(EditLeaseService.MODE_AGENT, 2L, "55"));

        leaseService.blockIfHeldByOther(PICTURE_ID, 2L);
    }

    @Test
    void blockIfHeldByOtherAllowsWhenFree() {
        when(valueOperations.get(anyString())).thenReturn(null);

        leaseService.blockIfHeldByOther(PICTURE_ID, 2L);
    }

    @Test
    void corruptedLeaseValueTreatedAsFree() {
        when(valueOperations.get(anyString())).thenReturn("not-json{{{");

        assertNull(leaseService.current(PICTURE_ID));
        leaseService.blockIfHeldByOther(PICTURE_ID, 2L);
    }

    @Test
    void leaseKeyFollowsConvention() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        leaseService.tryAcquire(PICTURE_ID, EditLeaseService.MODE_AGENT, 2L, "55");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(key.capture(), anyString(), eq(60L), eq(TimeUnit.SECONDS));
        assertEquals("gallery:picture:edit-lock:100", key.getValue());
    }
}
