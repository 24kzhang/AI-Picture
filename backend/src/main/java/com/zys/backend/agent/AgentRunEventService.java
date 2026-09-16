package com.zys.backend.agent;

import com.zys.backend.agent.dto.AgentRunDTO;
import com.zys.backend.agent.vo.AgentTurnVO;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.mapper.PictureEditRunMapper;
import com.zys.backend.model.entity.PictureEditRun;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.entity.User;
import com.zys.backend.model.enums.AgentRunStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Agent Run 实时事件：SSE 轮询 Agent 快照并推送。
 * 建连先发数据库快照，之后按固定间隔拉取 Agent 实时状态，
 * 状态变化才推送；空闲发心跳；终态后补一次快照并结束。
 * Agent 不可用时不伪造成功状态，仅记录日志并停止推送。
 */
@Slf4j
@Service
public class AgentRunEventService {

    private static final long POLL_INTERVAL_MS = 1500;
    private static final long HEARTBEAT_INTERVAL_MS = 15000;
    private static final long EMITTER_TIMEOUT_MS = 10 * 60 * 1000L;

    @Resource
    private RetouchAgentClient agentClient;

    @Resource
    private AgentEditSessionService editSessionService;

    @Resource
    private PictureEditRunMapper editRunMapper;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread thread = new Thread(r, "agent-run-events");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * runId -> 活跃 emitter 数（同 run 多端刷新场景）
     */
    private final Map<Long, Integer> activeRunCount = new ConcurrentHashMap<>();

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * 建立 SSE 订阅
     */
    public SseEmitter subscribe(PictureEditRun run, PictureEditSession session, User loginUser) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        long runId = run.getId();
        activeRunCount.merge(runId, 1, Integer::sum);

        // 1. 数据库快照先行
        AgentTurnVO snapshot = editSessionService.getRun(runId, loginUser);
        try {
            emitter.send(SseEmitter.event()
                    .id(nextEventId(runId))
                    .name("snapshot")
                    .data(toEventData(run, snapshot)));
        } catch (IOException e) {
            cleanup(emitter, runId);
            return emitter;
        }

        // 2. 定时拉取 Agent 实时状态
        String fingerprint = fingerprint(run);
        ScheduledFuture<?> poller = scheduler.scheduleWithFixedDelay(
                () -> pollOnce(emitter, runId, fingerprint, session, loginUser),
                POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        // 3. 心跳
        ScheduledFuture<?> heartbeat = scheduler.scheduleWithFixedDelay(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException e) {
                // 由 pollOnce 的异常路径统一清理
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);

        emitter.onCompletion(() -> cancel(poller, heartbeat, runId));
        emitter.onTimeout(() -> {
            cancel(poller, heartbeat, runId);
            emitter.complete();
        });
        emitter.onError(t -> cancel(poller, heartbeat, runId));
        return emitter;
    }

    private void pollOnce(SseEmitter emitter, long runId, String lastFingerprint,
                          PictureEditSession session, User loginUser) {
        try {
            PictureEditRun run = editRunMapper.selectById(runId);
            if (run == null) {
                emitter.complete();
                return;
            }
            boolean terminal = AgentRunStatusEnum.isTerminal(run.getStatus());
            if (terminal) {
                // 终态：再补一次快照后结束
                sendUpdate(emitter, runId, run, null);
                emitter.complete();
                return;
            }
            if (run.getAgentRunId() != null) {
                AgentCallContext context = AgentCallContext.builder()
                        .userId(loginUser.getId())
                        .pictureId(session.getPictureId())
                        .spaceId(session.getSpaceId())
                        .build();
                AgentRunDTO live = agentClient.getRun(run.getAgentRunId(), context);
                if (live != null) {
                    editSessionService.syncRunByAgentRunId(run, live);
                }
            }
            String fingerprint = fingerprint(run);
            if (!Objects.equals(lastFingerprint, fingerprint)) {
                sendUpdate(emitter, runId, run, null);
            }
        } catch (BusinessException e) {
            // Agent 不可用：保留连接等待恢复，仅记录
            log.debug("轮询 Agent Run {} 失败：{}", runId, e.getMessage());
        } catch (Exception e) {
            log.warn("SSE 轮询异常：{}", e.getMessage());
            emitter.completeWithError(e);
        }
    }

    private void sendUpdate(SseEmitter emitter, long runId, PictureEditRun run, Object extra) {
        try {
            emitter.send(SseEmitter.event()
                    .id(nextEventId(runId))
                    .name("progress")
                    .data(toEventData(run, null)));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private Map<String, Object> toEventData(PictureEditRun run, AgentTurnVO snapshot) {
        Map<String, Object> data = new HashMap<>();
        data.put("eventId", nextEventId(run.getId()));
        data.put("runId", run.getId());
        data.put("agentRunId", run.getAgentRunId());
        data.put("status", run.getStatus());
        data.put("stage", run.getStage());
        data.put("progress", run.getProgress());
        data.put("message", run.getErrorMessage());
        data.put("timestamp", System.currentTimeMillis());
        if (snapshot != null) {
            data.put("goal", snapshot.getGoal());
            data.put("reply", snapshot.getReply());
            data.put("steps", snapshot.getSteps());
        }
        return data;
    }

    private String fingerprint(PictureEditRun run) {
        return run.getStatus() + ":" + run.getProgress() + ":"
                + (run.getStage() == null ? "" : run.getStage().hashCode());
    }

    private String nextEventId(long runId) {
        return runId + "-" + System.currentTimeMillis();
    }

    private void cancel(ScheduledFuture<?> poller, ScheduledFuture<?> heartbeat, long runId) {
        poller.cancel(false);
        heartbeat.cancel(false);
        activeRunCount.computeIfPresent(runId, (k, v) -> v == 1 ? null : v - 1);
    }

    private void cleanup(SseEmitter emitter, long runId) {
        activeRunCount.computeIfPresent(runId, (k, v) -> v == 1 ? null : v - 1);
        emitter.complete();
    }
}
