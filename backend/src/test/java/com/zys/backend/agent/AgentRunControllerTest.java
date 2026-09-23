package com.zys.backend.agent;

import com.zys.backend.agent.controller.AgentRunController;
import com.zys.backend.agent.model.AgentRunEventVO;
import com.zys.backend.agent.model.PlanStep;
import com.zys.backend.agent.model.vo.AgentRunVO;
import com.zys.backend.agent.service.AgentEventPublisher;
import com.zys.backend.agent.service.AgentRunSnapshotService;
import com.zys.backend.agent.service.AgentSessionService;
import com.zys.backend.agent.service.PlanExecutor;
import com.zys.backend.common.BaseResponse;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.model.entity.PictureAgentRun;
import com.zys.backend.model.entity.User;
import com.zys.backend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

/**
 * 运行管理接口：鉴权、计划控制和 SSE 首包契约。
 */
@ExtendWith(MockitoExtension.class)
class AgentRunControllerTest {

    @InjectMocks
    private AgentRunController controller;

    @Mock
    private PlanExecutor planExecutor;

    @Mock
    private AgentRunSnapshotService snapshotService;

    @Mock
    private AgentSessionService agentSessionService;

    @Mock
    private AgentEventPublisher eventPublisher;

    @Mock
    private UserService userService;

    @Mock
    private HttpServletRequest request;

    private User user;
    private PictureAgentRun run;
    private AgentRunVO snapshot;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(7L);
        run = new PictureAgentRun();
        run.setId(101L);
        run.setEditSessionId(202L);
        run.setUserId(user.getId());
        run.setStatus("waiting");
        snapshot = new AgentRunVO();
        snapshot.setId(run.getId());
        snapshot.setStatus(run.getStatus());

        when(userService.getLoginUser(request)).thenReturn(user);
        when(planExecutor.requireRun(run.getId())).thenReturn(run);
        lenient().when(snapshotService.build(run)).thenReturn(snapshot);
    }

    @Test
    void getRunChecksViewPermissionAndReturnsSnapshot() {
        BaseResponse<AgentRunVO> response = controller.getRun(run.getId(), request);

        assertEquals(0, response.getCode());
        assertSame(snapshot, response.getData());
        verify(agentSessionService).requireSession(run.getEditSessionId(), user, false);
    }

    @Test
    void confirmDelegatesAfterViewAuthorization() {
        when(planExecutor.confirm(run.getId(), user)).thenReturn(run);

        BaseResponse<AgentRunVO> response = controller.confirm(run.getId(), request);

        assertSame(snapshot, response.getData());
        verify(agentSessionService).requireSession(run.getEditSessionId(), user, false);
        verify(planExecutor).confirm(run.getId(), user);
    }

    @Test
    void cancelDelegatesAfterViewAuthorization() {
        when(planExecutor.cancel(run.getId(), user)).thenReturn(run);

        BaseResponse<AgentRunVO> response = controller.cancel(run.getId(), request);

        assertSame(snapshot, response.getData());
        verify(agentSessionService).requireSession(run.getEditSessionId(), user, false);
        verify(planExecutor).cancel(run.getId(), user);
    }

    @Test
    void retryDelegatesAfterViewAuthorization() {
        run.setStatus("failed");
        snapshot.setStatus("failed");
        when(planExecutor.retry(run.getId(), user)).thenReturn(run);

        BaseResponse<AgentRunVO> response = controller.retry(run.getId(), request);

        assertSame(snapshot, response.getData());
        verify(agentSessionService).requireSession(run.getEditSessionId(), user, false);
        verify(planExecutor).retry(run.getId(), user);
    }

    @Test
    void eventsPublishesSnapshotAsFirstEvent() {
        PlanStep step = new PlanStep();
        step.setId("s1");
        snapshot.setPlan(Collections.singletonList(step));
        SseEmitter emitter = new SseEmitter();
        when(eventPublisher.subscribe(eq(run.getId()), any(AgentRunEventVO.class))).thenReturn(emitter);

        SseEmitter response = controller.events(run.getId(), request);

        assertSame(emitter, response);
        ArgumentCaptor<AgentRunEventVO> captor = ArgumentCaptor.forClass(AgentRunEventVO.class);
        verify(eventPublisher).subscribe(eq(run.getId()), captor.capture());
        AgentRunEventVO event = captor.getValue();
        assertEquals("snapshot", event.getEvent());
        assertEquals(run.getId(), event.getRunId());
        assertEquals("waiting", event.getStatus());
        assertSame(snapshot.getPlan(), event.getPlan());
    }

    @Test
    void authorizationFailurePreventsCancel() {
        BusinessException denied = new BusinessException(ErrorCode.NO_AUTH_ERROR, "无查看权限");
        org.mockito.Mockito.doThrow(denied)
                .when(agentSessionService).requireSession(run.getEditSessionId(), user, false);

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> controller.cancel(run.getId(), request));

        assertSame(denied, thrown);
        verify(planExecutor, never()).cancel(run.getId(), user);
    }
}
