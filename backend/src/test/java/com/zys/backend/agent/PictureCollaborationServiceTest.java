package com.zys.backend.agent;

import cn.hutool.json.JSONObject;
import com.zys.backend.agent.service.*;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.model.entity.*;
import com.zys.backend.service.UserService;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import org.springframework.web.context.request.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PictureCollaborationServiceTest {
    private PictureCollaborationService service;
    private JdbcTemplate jdbc;
    private AgentSessionService sessions;
    private EditLeaseService leases;
    private LayerDocumentService documents;
    private AgentAuthService auth;
    private PictureEditSession session;
    private User user;
    private Map<String,Object> record, runContext;
    private final String client="d7fc3182-645c-4028-84ca-13c32511e3ba";

    @BeforeEach void setup() {
        service=new PictureCollaborationService(); jdbc=mock(JdbcTemplate.class); sessions=mock(AgentSessionService.class);
        leases=mock(EditLeaseService.class); documents=mock(LayerDocumentService.class); auth=mock(AgentAuthService.class);
        ReflectionTestUtils.setField(service,"jdbc",jdbc); ReflectionTestUtils.setField(service,"sessions",sessions);
        ReflectionTestUtils.setField(service,"leases",leases); ReflectionTestUtils.setField(service,"documents",documents);
        ReflectionTestUtils.setField(service,"auth",auth); ReflectionTestUtils.setField(service,"publisher",mock(ApplicationEventPublisher.class));
        UserService users=mock(UserService.class); ReflectionTestUtils.setField(service,"users",users);
        TransactionTemplate tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv->{TransactionCallback<?> cb=inv.getArgument(0);return cb.doInTransaction(null);});
        ReflectionTestUtils.setField(service,"transaction",tx);
        user=new User();user.setId(7L); when(users.getById(7L)).thenReturn(user);
        session=new PictureEditSession();session.setId(11L);session.setPictureId(99L);session.setRevision(5);session.setStatus("active");
        when(sessions.loadSession(11L)).thenReturn(session); when(sessions.requirePicture(99L)).thenReturn(new Picture());
        record=new HashMap<>();record.put("session_id",11L);record.put("editor_id",7L);record.put("client_id",client);record.put("epoch",3L);record.put("lease_until",System.currentTimeMillis()+60000);
        runContext=new HashMap<>();runContext.put("user_id",7L);runContext.put("client_id",client);runContext.put("epoch",3L);
        when(jdbc.queryForList(anyString(),anyLong())).thenAnswer(inv->{String sql=inv.getArgument(0);return Collections.singletonList(sql.contains("picture_collaboration_run")?runContext:record);});
        when(leases.get(99L)).thenReturn(new JSONObject().set("mode","AGENT").set("userId","7").set("sessionId","11"));
        header(client,3,5);
    }
    @AfterEach void cleanup(){RequestContextHolder.resetRequestAttributes();}
    private void header(String id,long epoch,int revision) {
        MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader("X-Picture-Collaboration",new JSONObject().set("clientId",id).set("epoch",epoch).set("expectedRevision",revision).toString());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    private PictureToolRun tool(){PictureToolRun run=new PictureToolRun();run.setEditSessionId(11L);run.setAgentRunId(22L);run.setUserId(7L);return run;}
    @Test void currentBrowserMayEdit(){assertDoesNotThrow(()->service.requireEditor(session,user));}
    @Test void sameAccountOtherBrowserCannotEdit(){header(UUID.randomUUID().toString(),3,5);assertThrows(BusinessException.class,()->service.requireEditor(session,user));}
    @Test void oldEpochCannotEdit(){header(client,2,5);assertThrows(BusinessException.class,()->service.requireEditor(session,user));}
    @Test void staleRevisionCannotEdit(){header(client,3,4);assertThrows(BusinessException.class,()->service.requireEditor(session,user));}
    @Test void expiredSqlLeaseCannotEdit(){record.put("lease_until",0L);assertThrows(BusinessException.class,()->service.requireEditor(session,user));}
    @Test void lostRedisLeaseCannotEdit(){when(leases.get(99L)).thenReturn(null);assertThrows(BusinessException.class,()->service.requireEditor(session,user));}
    @Test void replacedDraftCannotEdit(){record.put("session_id",12L);assertThrows(BusinessException.class,()->service.requireEditor(session,user));}
    @Test void backgroundResultWithNewRevisionRemainsPending(){Map<String,Object> result=new HashMap<>();service.applyToolResult(tool(),4,result);assertEquals(true,result.get("pending"));verify(documents,never()).applyToolResult(any(),any(),any());}
    @Test void resultFromPreviousEditorEpochRemainsPending(){runContext.put("epoch",2L);Map<String,Object> result=new HashMap<>();service.applyToolResult(tool(),5,result);assertEquals(false,result.get("applied"));verify(documents,never()).applyToolResult(any(),any(),any());}
    @Test void validBackgroundResultIsApplied(){when(documents.applyToolResult(any(),any(),any())).thenReturn(true);Map<String,Object> result=new HashMap<>();service.applyToolResult(tool(),5,result);assertEquals(true,result.get("applied"));}
    @Test void queuedJobFromOldEditorStopsBeforeModel(){runContext.put("epoch",2L);assertThrows(BusinessException.class,()->service.checkToolStart(tool(),session));}
}
