package com.zys.backend.agent;

import cn.hutool.json.JSONUtil;
import com.zys.backend.agent.dto.AgentApiRequests;
import com.zys.backend.agent.dto.AgentAssetDTO;
import com.zys.backend.agent.dto.AgentRunDTO;
import com.zys.backend.agent.vo.AgentBatchVO;
import com.zys.backend.agent.vo.PictureVersionVO;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.manager.CosStorageManager;
import com.zys.backend.model.entity.Picture;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
class AgentBatchServiceTest {

    @Mock
    private PictureService pictureService;

    @Mock
    private UserService userService;

    @Mock
    private RetouchAgentClient agentClient;

    @Mock
    private AgentPicturePermissionChecker permissionChecker;

    @Mock
    private AgentCommitService commitService;

    @Mock
    private CosStorageManager cosStorageManager;

    @Mock
    private AgentMetrics agentMetrics;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private AgentBatchService service;

    private final User loginUser = new User();

    @BeforeEach
    void setUp() {
        loginUser.setId(2L);
        loginUser.setUserRole("user");
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(agentClient.isEnabled()).thenReturn(true);
        ReflectionTestUtils.setField(service, "publicBaseUrl", "http://localhost:8080");
        lenient().when(userService.isAdmin(any())).thenReturn(false);
    }

    private Picture picture(long id) {
        Picture picture = new Picture();
        picture.setId(id);
        picture.setUserId(2L);
        picture.setName("pic-" + id);
        picture.setUrl("https://cos.example/" + id + ".png");
        picture.setEditVersion(1L);
        return picture;
    }

    private AgentApiRequests.BatchCreateRequest request(List<Long> ids) {
        AgentApiRequests.BatchCreateRequest request = new AgentApiRequests.BatchCreateRequest();
        request.setPictureIds(ids);
        request.setOperations(new ArrayList<>(List.of("adjust_image")));
        request.setFormats(new ArrayList<>(List.of("png")));
        return request;
    }

    private void stubUploadAndSubmit() throws Exception {
        CosStorageManager.ManagedImageFile file = mock(CosStorageManager.ManagedImageFile.class);
        when(file.getPath()).thenReturn(Path.of("nul"));
        when(cosStorageManager.materialize(anyString())).thenReturn(file);
        AgentAssetDTO asset = new AgentAssetDTO();
        asset.setId("asset-1");
        when(agentClient.uploadAsset(any(), anyString(), anyString(), any())).thenReturn(asset);
        AgentRunDTO run = new AgentRunDTO();
        run.setId("batch-1");
        run.setStatus("queued");
        when(agentClient.createBatch(any(), any())).thenReturn(run);
    }

    @Test
    void createRejectsTooManyPictures() {
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= 21; i++) {
            ids.add(i);
        }
        AgentApiRequests.BatchCreateRequest request = request(ids);
        assertThrows(BusinessException.class, () -> service.createBatch(request, loginUser));
    }

    @Test
    void createRejectsUnknownOperation() {
        AgentApiRequests.BatchCreateRequest request = request(List.of(100L));
        request.setOperations(new ArrayList<>(List.of("not_a_tool")));
        assertThrows(BusinessException.class, () -> service.createBatch(request, loginUser));
    }

    @Test
    void createChecksPermissionForEveryPicture() {
        when(permissionChecker.checkPictureEditable(loginUser, 100L)).thenReturn(picture(100L));
        when(permissionChecker.checkPictureEditable(loginUser, 200L))
                .thenThrow(new BusinessException(com.zys.backend.exception.ErrorCode.NO_AUTH_ERROR, "无权限"));

        assertThrows(BusinessException.class,
                () -> service.createBatch(request(List.of(100L, 200L)), loginUser));
        verify(agentClient, never()).uploadAsset(any(), anyString(), anyString(), any());
    }

    @Test
    void createStoresMappingAndReturnsRun() throws Exception {
        when(permissionChecker.checkPictureEditable(loginUser, 100L)).thenReturn(picture(100L));
        when(permissionChecker.permissionsOf(any(), any())).thenReturn(new ArrayList<>());
        stubUploadAndSubmit();

        AgentBatchVO vo = service.createBatch(request(List.of(100L)), loginUser);

        assertEquals("batch-1", vo.getBatchId());
        assertEquals(1, vo.getItems().size());
        assertEquals(100L, vo.getItems().get(0).getPictureId());
        assertEquals(1L, vo.getItems().get(0).getBaseEditVersion());
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("agent:batch:batch-1"), json.capture(), anyLong(), any());
        assertTrue(json.getValue().contains("pictureId"));
    }

    @Test
    void confirmRejectsUnfinishedRun() {
        AgentBatchService.BatchMapping mapping = new AgentBatchService.BatchMapping();
        mapping.setUserId(2L);
        when(valueOperations.get("agent:batch:batch-1")).thenReturn(JSONUtil.toJsonStr(mapping));
        AgentRunDTO run = new AgentRunDTO();
        run.setId("batch-1");
        run.setStatus("running");
        when(agentClient.getRun(eq("batch-1"), any())).thenReturn(run);

        assertThrows(BusinessException.class, () -> service.confirmBatch("batch-1", loginUser));
    }

    @Test
    void confirmRejectsCanceledBatch() {
        AgentBatchService.BatchMapping mapping = new AgentBatchService.BatchMapping();
        mapping.setUserId(2L);
        mapping.setCanceled(true);
        when(valueOperations.get("agent:batch:batch-1")).thenReturn(JSONUtil.toJsonStr(mapping));

        assertThrows(BusinessException.class, () -> service.confirmBatch("batch-1", loginUser));
    }

    @Test
    void confirmReportsPerItemResultsWithoutFakingSuccess() {
        AgentBatchService.BatchMapping mapping = new AgentBatchService.BatchMapping();
        mapping.setUserId(2L);
        AgentBatchService.BatchItem okItem = new AgentBatchService.BatchItem();
        okItem.setPictureId(100L);
        okItem.setPictureName("a");
        okItem.setAgentAssetId("asset-ok");
        okItem.setBaseEditVersion(1L);
        AgentBatchService.BatchItem conflictItem = new AgentBatchService.BatchItem();
        conflictItem.setPictureId(200L);
        conflictItem.setPictureName("b");
        conflictItem.setAgentAssetId("asset-conflict");
        conflictItem.setBaseEditVersion(1L);
        AgentBatchService.BatchItem failedItem = new AgentBatchService.BatchItem();
        failedItem.setPictureId(300L);
        failedItem.setPictureName("c");
        failedItem.setAgentAssetId("asset-failed");
        failedItem.setBaseEditVersion(1L);
        mapping.setItems(new ArrayList<>(Arrays.asList(okItem, conflictItem, failedItem)));
        when(valueOperations.get("agent:batch:batch-1")).thenReturn(JSONUtil.toJsonStr(mapping));

        Map<String, Object> itemOk = new HashMap<>();
        itemOk.put("source_id", "asset-ok");
        itemOk.put("status", "succeeded");
        itemOk.put("output_ids", new ArrayList<>(List.of("out-1")));
        Map<String, Object> itemConflict = new HashMap<>();
        itemConflict.put("source_id", "asset-conflict");
        itemConflict.put("status", "succeeded");
        itemConflict.put("output_ids", new ArrayList<>(List.of("out-2")));
        Map<String, Object> itemFail = new HashMap<>();
        itemFail.put("source_id", "asset-failed");
        itemFail.put("status", "failed");
        itemFail.put("error", "抠图失败");
        itemFail.put("output_ids", new ArrayList<>());
        AgentRunDTO run = new AgentRunDTO();
        run.setId("batch-1");
        run.setStatus("succeeded");
        run.setResult(new HashMap<>(Map.of("items",
                new ArrayList<>(Arrays.asList(itemOk, itemConflict, itemFail)))));
        when(agentClient.getRun(eq("batch-1"), any())).thenReturn(run);

        Picture picture100 = picture(100L);
        Picture picture200 = picture(200L);
        // 图片在批量创建后被他人更新：基准 1 与当前 2 不一致 → 冲突
        picture200.setEditVersion(2L);
        when(pictureService.getById(100L)).thenReturn(picture100);
        when(pictureService.getById(200L)).thenReturn(picture200);
        AgentAssetDTO outAsset = new AgentAssetDTO();
        outAsset.setId("out-1");
        outAsset.setUrl("http://localhost:8080/api/agent-asset/token");
        when(agentClient.getAsset(eq("out-1"), any())).thenReturn(outAsset);
        PictureVersionVO version = new PictureVersionVO();
        version.setId(9L);
        version.setVersionNo(2L);
        when(commitService.commitAssetVersion(eq(picture100), anyString(), any(), any(), any(), anyLong(), any()))
                .thenReturn(version);

        List<AgentBatchVO.AgentBatchItemVO> results = service.confirmBatch("batch-1", loginUser);

        assertEquals(3, results.size());
        assertEquals("succeeded", results.get(0).getStatus());
        assertEquals(9L, results.get(0).getVersionId());
        assertEquals("conflict", results.get(1).getStatus());
        assertEquals("failed", results.get(2).getStatus());
        assertEquals("抠图失败", results.get(2).getError());
    }

    @Test
    void mappingRequiresOwner() {
        AgentBatchService.BatchMapping mapping = new AgentBatchService.BatchMapping();
        mapping.setUserId(99L);
        when(valueOperations.get("agent:batch:batch-1")).thenReturn(JSONUtil.toJsonStr(mapping));

        assertThrows(BusinessException.class, () -> service.loadMapping("batch-1", loginUser));
    }
}
