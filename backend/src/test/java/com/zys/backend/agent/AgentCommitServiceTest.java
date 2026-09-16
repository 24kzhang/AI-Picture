package com.zys.backend.agent;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zys.backend.agent.dto.AgentAssetDTO;
import com.zys.backend.agent.dto.AgentSessionDetailDTO;
import com.zys.backend.agent.vo.PictureVersionVO;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.manager.CosStorageManager;
import com.zys.backend.mapper.IntegrationOutboxMapper;
import com.zys.backend.mapper.PictureEditSessionMapper;
import com.zys.backend.mapper.PictureVersionMapper;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.entity.PictureVersion;
import com.zys.backend.model.entity.User;
import com.zys.backend.service.PictureService;
import com.zys.backend.service.SpaceService;
import com.zys.backend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentCommitServiceTest {

    @Mock
    private PictureService pictureService;

    @Mock
    private SpaceService spaceService;

    @Mock
    private UserService userService;

    @Mock
    private PictureEditSessionMapper editSessionMapper;

    @Mock
    private PictureVersionMapper pictureVersionMapper;

    @Mock
    private IntegrationOutboxMapper outboxMapper;

    @Mock
    private AgentPicturePermissionChecker permissionChecker;

    @Mock
    private RetouchAgentClient agentClient;

    @Mock
    private CosStorageManager cosStorageManager;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private AgentCommitService service;

    private final User loginUser = new User();

    @BeforeEach
    void setUp() {
        loginUser.setId(2L);
        loginUser.setUserRole("user");
    }

    private PictureEditSession session(String status, Long editVersionBase) {
        PictureEditSession record = new PictureEditSession();
        record.setId(55L);
        record.setAgentSessionId("agent-uuid-1");
        record.setPictureId(100L);
        record.setUserId(2L);
        record.setStatus(status);
        record.setBaseEditVersion(editVersionBase);
        record.setFinalAgentAssetId("asset-final");
        return record;
    }

    private Picture picture(long editVersion) {
        Picture picture = new Picture();
        picture.setId(100L);
        picture.setUserId(2L);
        picture.setEditVersion(editVersion);
        picture.setPicSize(1000L);
        return picture;
    }

    @Test
    void commitIsIdempotentForCommittedSession() {
        PictureEditSession record = session("COMMITTED", 3L);
        record.setCommittedVersionId(9L);
        when(editSessionMapper.selectById(55L)).thenReturn(record);
        PictureVersion committed = new PictureVersion();
        committed.setId(9L);
        committed.setVersionNo(2L);
        committed.setSource("AGENT");
        when(pictureVersionMapper.selectById(9L)).thenReturn(committed);

        PictureVersionVO vo = service.commit(55L, 3L, loginUser);

        assertEquals(9L, vo.getId());
        assertEquals(2L, vo.getVersionNo());
        // 幂等路径不触碰图片、事务与 COS
        verify(pictureService, never()).getById(anyLong());
        verify(transactionTemplate, never()).execute(any());
        verify(cosStorageManager, never()).storePicture(any(), any(), any());
    }

    @Test
    void commitRejectsVersionConflictAndKeepsDraft() {
        PictureEditSession record = session("READY_TO_COMMIT", 3L);
        when(editSessionMapper.selectById(55L)).thenReturn(record);
        when(pictureService.getById(100L)).thenReturn(picture(4L));
        when(permissionChecker.permissionsOf(any(), any()))
                .thenReturn(List.of("picture:view", "picture:edit"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.commit(55L, 3L, loginUser));

        assertEquals("图片已被他人更新，请刷新后重试", e.getMessage());
        // 会话标记冲突，但图片未被修改
        assertEquals("CONFLICT", record.getStatus());
        verify(editSessionMapper).updateById(record);
        verify(transactionTemplate, never()).execute(any());
        verify(cosStorageManager, never()).storePicture(any(), any(), any());
    }

    @Test
    void commitRequiresFinalAsset() {
        PictureEditSession record = session("ACTIVE", 3L);
        record.setFinalAgentAssetId(null);
        when(editSessionMapper.selectById(55L)).thenReturn(record);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.commit(55L, 3L, loginUser));

        assertEquals("请先选定最终草稿", e.getMessage());
    }

    @Test
    void commitRejectsNonOwner() {
        PictureEditSession record = session("READY_TO_COMMIT", 3L);
        record.setUserId(9L);
        when(editSessionMapper.selectById(55L)).thenReturn(record);
        when(userService.isAdmin(loginUser)).thenReturn(false);

        assertThrows(BusinessException.class, () -> service.commit(55L, 3L, loginUser));
    }

    @Test
    void commitRejectsCanceledSession() {
        PictureEditSession record = session("CANCELED", 3L);
        when(editSessionMapper.selectById(55L)).thenReturn(record);

        assertThrows(BusinessException.class, () -> service.commit(55L, 3L, loginUser));
    }

    @Test
    void setFinalAssetRejectsForeignAsset() {
        PictureEditSession record = session("ACTIVE", 3L);
        record.setFinalAgentAssetId(null);
        when(editSessionMapper.selectById(55L)).thenReturn(record);
        AgentSessionDetailDTO detail = new AgentSessionDetailDTO();
        detail.setCurrentAssetId("asset-other");
        AgentAssetDTO asset = new AgentAssetDTO();
        asset.setId("asset-other");
        detail.setAssets(List.of(asset));
        when(agentClient.getSession(any(), any())).thenReturn(detail);

        assertThrows(BusinessException.class,
                () -> service.setFinalAsset(55L, "asset-not-mine", loginUser));
    }

    @Test
    void setFinalAssetMarksReadyToCommit() {
        PictureEditSession record = session("ACTIVE", 3L);
        record.setFinalAgentAssetId(null);
        when(editSessionMapper.selectById(55L)).thenReturn(record);
        AgentSessionDetailDTO detail = new AgentSessionDetailDTO();
        detail.setCurrentAssetId("asset-final");
        AgentAssetDTO asset = new AgentAssetDTO();
        asset.setId("asset-final");
        detail.setAssets(List.of(asset));
        when(agentClient.getSession(any(), any())).thenReturn(detail);

        service.setFinalAsset(55L, "asset-final", loginUser);

        assertEquals("asset-final", record.getFinalAgentAssetId());
        assertEquals("READY_TO_COMMIT", record.getStatus());
        verify(editSessionMapper).updateById(record);
    }

    @Test
    void restoreRejectsVersionOfOtherPicture() {
        when(permissionChecker.checkPictureEditable(loginUser, 100L)).thenReturn(picture(1L));
        PictureVersion foreign = new PictureVersion();
        foreign.setId(7L);
        foreign.setPictureId(999L);
        when(pictureVersionMapper.selectById(7L)).thenReturn(foreign);

        assertThrows(BusinessException.class,
                () -> service.restoreVersion(100L, 7L, 1L, loginUser));
    }

    @Test
    void restoreRejectsStaleExpectedVersion() {
        when(permissionChecker.checkPictureEditable(loginUser, 100L)).thenReturn(picture(5L));
        PictureVersion version = new PictureVersion();
        version.setId(7L);
        version.setPictureId(100L);
        when(pictureVersionMapper.selectById(7L)).thenReturn(version);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.restoreVersion(100L, 7L, 3L, loginUser));

        assertEquals("图片已被他人更新，请刷新后重试", e.getMessage());
    }

    @Test
    void listVersionsRequiresViewPermission() {
        when(pictureService.getById(100L)).thenReturn(picture(0L));
        when(permissionChecker.permissionsOf(any(), any())).thenReturn(new ArrayList<>());

        assertThrows(BusinessException.class, () -> service.listVersions(100L, loginUser));
    }

    @Test
    void listVersionsReturnsOrderedRows() {
        when(pictureService.getById(100L)).thenReturn(picture(0L));
        when(permissionChecker.permissionsOf(any(), any())).thenReturn(List.of("picture:view"));
        PictureVersion v2 = new PictureVersion();
        v2.setId(2L);
        v2.setVersionNo(2L);
        PictureVersion v1 = new PictureVersion();
        v1.setId(1L);
        v1.setVersionNo(1L);
        when(pictureVersionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(v2, v1));

        List<PictureVersionVO> versions = service.listVersions(100L, loginUser);

        assertEquals(2, versions.size());
        assertEquals(2L, versions.get(0).getVersionNo());
    }
}
