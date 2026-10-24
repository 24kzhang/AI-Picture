package com.zys.backend.service;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.manager.auth.SpaceUserAuthManager;
import com.zys.backend.manager.auth.model.SpaceUserPermissionConstant;
import com.zys.backend.model.entity.Space;
import com.zys.backend.model.entity.User;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PictureGenerationServiceTest {
    private PictureGenerationService service;private JdbcTemplate jdbc;private SpaceUserAuthManager permissions;private ThreadPoolExecutor executor;private User user;private UserService users;
    @BeforeEach void setup(){
        service=new PictureGenerationService();jdbc=mock(JdbcTemplate.class);permissions=mock(SpaceUserAuthManager.class);executor=mock(ThreadPoolExecutor.class);
        SpaceService spaces=mock(SpaceService.class);users=mock(UserService.class);Space space=new Space();space.setId(8L);space.setMaxCount(50L);space.setMaxSize(10000000L);space.setTotalCount(0L);space.setTotalSize(0L);when(spaces.getById(8L)).thenReturn(space);
        ReflectionTestUtils.setField(service,"jdbc",jdbc);ReflectionTestUtils.setField(service,"permissions",permissions);ReflectionTestUtils.setField(service,"spaces",spaces);ReflectionTestUtils.setField(service,"users",users);ReflectionTestUtils.setField(service,"executor",executor);
        user=new User();user.setId(7L);when(permissions.getPermissionList(any(),eq(user))).thenReturn(Collections.singletonList(SpaceUserPermissionConstant.PICTURE_UPLOAD));
    }
    @Test void viewerCannotSubmitGeneration(){when(permissions.getPermissionList(any(),eq(user))).thenReturn(Collections.singletonList(SpaceUserPermissionConstant.PICTURE_VIEW));assertThrows(BusinessException.class,()->service.create(user,8L,"真实街景",1024,1024,UUID.randomUUID().toString()));verify(executor,never()).execute(any());verifyNoInteractions(jdbc);}
    @Test void invalidDimensionsAreRejectedBeforeSubmitting(){assertThrows(BusinessException.class,()->service.create(user,8L,"真实街景",2049,1024,UUID.randomUUID().toString()));verify(executor,never()).execute(any());}
    @Test void replayReturnsExistingTaskWithoutAnotherModelSubmission(){
        String key=UUID.randomUUID().toString();Map<String,Object> record=new HashMap<>();record.put("id",UUID.randomUUID().toString());record.put("status","RUNNING");record.put("digest",cn.hutool.crypto.digest.DigestUtil.sha256Hex("8:真实街景:1024:1024"));
        when(jdbc.queryForList(anyString(),eq(7L),eq(key))).thenReturn(Collections.singletonList(record));
        SpaceService spaces=(SpaceService)ReflectionTestUtils.getField(service,"spaces");Space full=spaces.getById(8L);full.setTotalCount(full.getMaxCount());
        assertEquals(record.get("id"),service.create(user,8L,"真实街景",1024,1024,key).get("id"));verify(executor,never()).execute(any());
        assertThrows(BusinessException.class,()->service.create(user,8L,"不同内容",1024,1024,key));
    }
    @Test void anotherUserCannotReadGeneratedTask(){Map<String,Object> record=new HashMap<>();record.put("user_id",9L);when(jdbc.queryForList(anyString(),eq("task"))).thenReturn(Collections.singletonList(record));assertThrows(BusinessException.class,()->service.snapshot("task",user));}
    private Map<String,Object> queued() {
        Map<String,Object> record=new HashMap<>();record.put("user_id",7L);record.put("space_id",8L);record.put("prompt","真实街景");record.put("width",1024);record.put("height",1024);record.put("source_url","http://model/qwen-image/outputs/result.png");record.put("lease_epoch",1L);
        when(jdbc.queryForList(anyString(),eq("task"))).thenReturn(Collections.singletonList(record));when(users.getById(7L)).thenReturn(user);
        when(jdbc.update(startsWith("UPDATE picture_generation_task SET status=?"),any(),any(),any(),any())).thenReturn(1);
        when(jdbc.update(startsWith("UPDATE picture_generation_task SET status='TRANSFER'"),any(),any(),any(),any(),any())).thenReturn(1);return record;
    }
    @Test void submissionDisconnectIsUnknownInsteadOfAutomaticRegeneration(){
        queued();com.zys.backend.api.aliyunai.QwenImageGenerator provider=mock(com.zys.backend.api.aliyunai.QwenImageGenerator.class);ReflectionTestUtils.setField(service,"generator",provider);
        when(provider.generate(anyString(),anyInt(),anyInt())).thenThrow(new RuntimeException("timeout"));
        ReflectionTestUtils.invokeMethod(service,"execute","task",false);
        verify(jdbc).update(eq("UPDATE picture_generation_task SET status=?,error_message=?,worker_id=NULL,lease_until=NULL WHERE id=? AND worker_id=? AND lease_epoch=?"),eq("UNKNOWN"),contains("不会自动重复生成"),eq("task"),anyString(),eq(1L));verify(provider,times(1)).generate(anyString(),anyInt(),anyInt());verify(executor,never()).execute(any());
    }
    @Test void receiptSurvivesCosFailureAndTransferRetryDoesNotGenerateAgain(){
        queued();com.zys.backend.api.aliyunai.QwenImageGenerator provider=mock(com.zys.backend.api.aliyunai.QwenImageGenerator.class);com.zys.backend.agent.service.AgentStorageService storage=mock(com.zys.backend.agent.service.AgentStorageService.class);
        ReflectionTestUtils.setField(service,"generator",provider);ReflectionTestUtils.setField(service,"storage",storage);
        when(provider.generate(anyString(),anyInt(),anyInt())).thenReturn(new com.zys.backend.api.aliyunai.QwenImageGenerator.Receipt("model-receipt","http://model/qwen-image/outputs/result.png"));when(provider.download(anyString())).thenReturn(new byte[]{1});when(storage.storeSessionAsset(anyLong(),anyString(),anyString(),any())).thenThrow(new RuntimeException("COS unavailable"));
        ReflectionTestUtils.invokeMethod(service,"execute","task",false);
        verify(jdbc).update(eq("UPDATE picture_generation_task SET status='TRANSFER',model_id=?,source_url=?,lease_until=DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 30 MINUTE) WHERE id=? AND worker_id=? AND lease_epoch=?"),eq("model-receipt"),anyString(),eq("task"),anyString(),eq(1L));
        verify(jdbc).update(eq("UPDATE picture_generation_task SET status=?,error_message=?,worker_id=NULL,lease_until=NULL WHERE id=? AND worker_id=? AND lease_epoch=?"),eq("OUTPUT_PENDING"),contains("可重试转存"),eq("task"),anyString(),eq(1L));
        ReflectionTestUtils.invokeMethod(service,"execute","task",true);verify(provider,times(1)).generate(anyString(),anyInt(),anyInt());verify(provider,times(2)).download(anyString());
    }
    @Test void fullExecutorKeepsTaskQueuedForRetry(){
        doThrow(new java.util.concurrent.RejectedExecutionException()).when(executor).execute(any());
        ReflectionTestUtils.invokeMethod(service,"dispatch","task",false);
        verify(jdbc,never()).update(contains("status=?"),eq("FAILED"),eq("task"));
        service.stop();
    }
}
