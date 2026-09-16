package com.zys.backend.agent;

import com.zys.backend.agent.dto.AgentSessionDetailDTO;
import com.zys.backend.agent.dto.AgentTurnDTO;
import com.zys.backend.agent.vo.AgentSessionVO;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.manager.CosStorageManager;
import com.zys.backend.mapper.PictureEditRunMapper;
import com.zys.backend.mapper.PictureEditSessionMapper;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.PictureEditRun;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.entity.User;
import com.zys.backend.service.PictureService;
import com.zys.backend.service.UserService;
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

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentEditSessionServiceTest {

    @Mock
    private PictureService pictureService;

    @Mock
    private UserService userService;

    @Mock
    private PictureEditSessionMapper editSessionMapper;

    @Mock
    private PictureEditRunMapper editRunMapper;

    @Mock
    private RetouchAgentClient agentClient;

    @Mock
    private AgentPicturePermissionChecker permissionChecker;

    @Mock
    private CosStorageManager cosStorageManager;

    @Mock
    private com.zys.backend.manager.lease.EditLeaseService editLeaseService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private AgentEditSessionService service;

    private final User loginUser = new User();

    @BeforeEach
    void setUp() {
        loginUser.setId(2L);
        loginUser.setUserRole("user");
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(agentClient.isEnabled()).thenReturn(true);
        // 默认持有 Agent 租约
        com.zys.backend.manager.lease.EditLeaseService.Lease lease =
                new com.zys.backend.manager.lease.EditLeaseService.Lease();
        lease.setMode("AGENT");
        lease.setUserId(2L);
        lease.setLockToken("token-1");
        lenient().when(editLeaseService.tryAcquire(anyLong(), any(), anyLong(), any()))
                .thenReturn(lease);
        lenient().when(editLeaseService.isHeldBy(anyLong(), any(), anyLong(), any()))
                .thenReturn(true);
        lenient().when(editLeaseService.current(anyLong())).thenReturn(lease);
    }

    private Picture picture() {
        Picture picture = new Picture();
        picture.setId(100L);
        picture.setUserId(2L);
        picture.setSpaceId(null);
        picture.setName("demo.png");
        picture.setUrl("https://cos.example/demo.png");
        picture.setEditVersion(3L);
        return picture;
    }

    @Test
    void createSessionRejectsWhenDisabled() {
        when(agentClient.isEnabled()).thenReturn(false);
        assertThrows(BusinessException.class,
                () -> service.createSession(100L, null, loginUser));
    }

    @Test
    void createSessionReusesActiveSession() {
        Picture picture = picture();
        when(permissionChecker.checkPictureEditable(loginUser, 100L)).thenReturn(picture);
        PictureEditSession active = new PictureEditSession();
        active.setId(55L);
        active.setAgentSessionId("agent-uuid-1");
        active.setPictureId(100L);
        active.setUserId(2L);
        active.setStatus("ACTIVE");
        active.setBaseEditVersion(3L);
        when(editSessionMapper.selectOne(any())).thenReturn(active);
        when(userService.getById(2L)).thenReturn(loginUser);
        AgentSessionDetailDTO detail = new AgentSessionDetailDTO();
        detail.setId("agent-uuid-1");
        when(agentClient.getSession(eq("agent-uuid-1"), any())).thenReturn(detail);
        when(agentClient.listMessages(eq("agent-uuid-1"), any())).thenReturn(List.of());

        AgentSessionVO vo = service.createSession(100L, null, loginUser);

        assertEquals(55L, vo.getId());
        verify(agentClient, never()).uploadAsset(any(), any(), any(), any());
        verify(editSessionMapper, never()).insert(any(PictureEditSession.class));
    }

    @Test
    void createSessionReturnsIdempotentResult() {
        Picture picture = picture();
        when(permissionChecker.checkPictureEditable(loginUser, 100L)).thenReturn(picture);
        PictureEditSession existing = new PictureEditSession();
        existing.setId(77L);
        existing.setAgentSessionId("agent-uuid-2");
        existing.setPictureId(100L);
        existing.setUserId(2L);
        existing.setStatus("ACTIVE");
        when(valueOperations.get(anyString())).thenReturn("77");
        when(editSessionMapper.selectById(77L)).thenReturn(existing);
        when(userService.getById(2L)).thenReturn(loginUser);
        when(agentClient.getSession(eq("agent-uuid-2"), any())).thenReturn(new AgentSessionDetailDTO());
        when(agentClient.listMessages(eq("agent-uuid-2"), any())).thenReturn(List.of());

        AgentSessionVO vo = service.createSession(100L, "key-1", loginUser);

        assertEquals(77L, vo.getId());
        // 幂等命中直接返回首次创建的会话，不再新建
        verify(editSessionMapper, never()).insert(any(PictureEditSession.class));
        verify(agentClient, never()).uploadAsset(any(), any(), any(), any());
    }

    @Test
    void createSessionCreatesNewWhenNoneActive() throws Exception {
        Picture picture = picture();
        when(permissionChecker.checkPictureEditable(loginUser, 100L)).thenReturn(picture);
        when(editSessionMapper.selectOne(any())).thenReturn(null);
        CosStorageManager.ManagedImageFile file = mock(CosStorageManager.ManagedImageFile.class);
        when(file.getPath()).thenReturn(Path.of("nul"));
        when(cosStorageManager.materialize(picture.getUrl())).thenReturn(file);
        com.zys.backend.agent.dto.AgentAssetDTO asset =
                new com.zys.backend.agent.dto.AgentAssetDTO();
        asset.setId("asset-1");
        when(agentClient.uploadAsset(any(), anyString(), anyString(), any())).thenReturn(asset);
        AgentSessionDetailDTO detail = new AgentSessionDetailDTO();
        detail.setId("agent-session-new");
        when(agentClient.createSession(eq("asset-1"), any(), anyString(), any())).thenReturn(detail);
        when(userService.getById(2L)).thenReturn(loginUser);
        when(agentClient.getSession(eq("agent-session-new"), any())).thenReturn(new AgentSessionDetailDTO());
        when(agentClient.listMessages(eq("agent-session-new"), any())).thenReturn(List.of());

        AgentSessionVO vo = service.createSession(100L, null, loginUser);

        ArgumentCaptor<PictureEditSession> captor = ArgumentCaptor.forClass(PictureEditSession.class);
        verify(editSessionMapper).insert(captor.capture());
        PictureEditSession inserted = captor.getValue();
        assertEquals("agent-session-new", inserted.getAgentSessionId());
        assertEquals(3L, inserted.getBaseEditVersion());
        assertEquals("ACTIVE", inserted.getStatus());
        assertNotNull(vo);
    }

    @Test
    void sendMessageCreatesPlanRun() {
        PictureEditSession record = new PictureEditSession();
        record.setId(55L);
        record.setAgentSessionId("agent-uuid-1");
        record.setPictureId(100L);
        record.setUserId(2L);
        record.setStatus("ACTIVE");
        when(editSessionMapper.selectById(55L)).thenReturn(record);
        AgentTurnDTO turn = new AgentTurnDTO();
        turn.setId("turn-1");
        turn.setStatus("waiting");
        turn.setGoal("换背景");
        turn.setReply("请确认计划");
        when(agentClient.sendMessage(eq("agent-uuid-1"), eq("换背景"), any())).thenReturn(turn);

        com.zys.backend.agent.vo.AgentTurnVO vo = service.sendMessage(55L, "换背景", loginUser);

        ArgumentCaptor<PictureEditRun> captor = ArgumentCaptor.forClass(PictureEditRun.class);
        verify(editRunMapper).insert(captor.capture());
        assertEquals("PLAN", captor.getValue().getRunType());
        assertEquals("turn-1", captor.getValue().getAgentRunId());
        assertEquals("waiting", vo.getStatus());
    }

    @Test
    void sendMessageRejectsNonOwner() {
        PictureEditSession record = new PictureEditSession();
        record.setId(55L);
        record.setAgentSessionId("agent-uuid-1");
        record.setPictureId(100L);
        record.setUserId(9L);
        record.setStatus("ACTIVE");
        when(editSessionMapper.selectById(55L)).thenReturn(record);
        when(userService.isAdmin(loginUser)).thenReturn(false);

        assertThrows(BusinessException.class, () -> service.sendMessage(55L, "hi", loginUser));
    }

    @Test
    void cancelSessionGuardsCommitted() {
        PictureEditSession record = new PictureEditSession();
        record.setId(55L);
        record.setUserId(2L);
        record.setStatus("COMMITTED");
        when(editSessionMapper.selectById(55L)).thenReturn(record);

        assertThrows(BusinessException.class, () -> service.cancelSession(55L, loginUser));
    }
}
