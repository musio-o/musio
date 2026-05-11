package com.musio.agent;

import com.musio.events.AgentEventBus;
import com.musio.events.SseEventPublisher;
import com.musio.memory.AgentTaskMemoryService;
import com.musio.model.AgentEvent;
import com.musio.model.ChatConfirmation;
import com.musio.model.ChatHistoryMessage;
import com.musio.model.ChatRequest;
import com.musio.model.ChatRunResponse;
import com.musio.model.AgentTaskMemory;
import com.musio.model.PendingConfirmation;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class AgentRunService {
    private final AgentRuntime agentRuntime;
    private final SseEventPublisher eventPublisher;
    private final AgentEventBus eventBus;
    private final ConversationHistoryService conversationHistoryService;
    private final AgentTaskMemoryService taskMemoryService;
    private final ConfirmationService confirmationService;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final Map<String, ChatRequest> pendingRuns = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> runningRuns = new ConcurrentHashMap<>();

    public AgentRunService(
            AgentRuntime agentRuntime,
            SseEventPublisher eventPublisher,
            AgentEventBus eventBus,
            ConversationHistoryService conversationHistoryService,
            AgentTaskMemoryService taskMemoryService,
            ConfirmationService confirmationService
    ) {
        this.agentRuntime = agentRuntime;
        this.eventPublisher = eventPublisher;
        this.eventBus = eventBus;
        this.conversationHistoryService = conversationHistoryService;
        this.taskMemoryService = taskMemoryService;
        this.confirmationService = confirmationService;
    }

    public ChatRunResponse startRun(ChatRequest request) {
        String runId = UUID.randomUUID().toString();
        pendingRuns.put(runId, request);
        return new ChatRunResponse(runId, "created", "Agent run created.");
    }

    public SseEmitter connect(String runId) {
        // 每个 runId 对应一条 SSE 连接，创建后会先向前端发送 connected 事件。
        SseEmitter emitter = eventPublisher.create(runId);
        ChatRequest request = pendingRuns.get(runId);
        if (request == null) {
            // runId 不存在通常表示任务已启动过、已结束，或前端传入了错误的 runId。
            eventPublisher.publish(runId, AgentEvent.of("agent_error", Map.of(
                    "runId", runId,
                    "message", "Agent run not found or already started."
            )));
            return emitter;
        }

        // 订阅本轮 run 的内存事件总线，将 Agent 执行过程中的 token、工具、歌曲卡片等事件转发到 SSE。
        eventBus.subscribe(runId, event -> {
            eventPublisher.publish(runId, event);
            if (isTerminal(event)) {
                // done / agent_error 是终止事件，发送后释放本轮订阅和任务索引，避免内存残留。
                eventBus.unsubscribe(runId);
                pendingRuns.remove(runId);
                runningRuns.remove(runId);
            }
        });

        // 同一个 runId 只能启动一次后台 Agent 任务；重复连接不会重复提交执行。
        runningRuns.computeIfAbsent(runId, id -> executorService.submit(() -> agentRuntime.start(id, request)));
        return emitter;
    }

    public ChatRunResponse confirm(String runId, PendingConfirmation confirmation) {
        boolean accepted = confirmationService.confirm(runId, confirmation);
        return new ChatRunResponse(runId, accepted ? "confirmed" : "not_waiting", accepted ? "Confirmation accepted." : "No active confirmation is waiting.");
    }

    public ChatRunResponse cancel(String runId) {
        pendingRuns.remove(runId);
        Future<?> task = runningRuns.remove(runId);
        if (task != null) {
            task.cancel(true);
        }
        confirmationService.clear(runId);
        eventBus.publish(runId, AgentEvent.of("done", Map.of("runId", runId, "state", "cancelled")));
        eventBus.unsubscribe(runId);
        return new ChatRunResponse(runId, "cancelled", "Agent run cancelled.");
    }

    public ChatRunResponse clearHistory(String userId) {
        String normalizedUserId = conversationHistoryService.normalizeUserId(userId);
        conversationHistoryService.clear(normalizedUserId);
        return new ChatRunResponse(normalizedUserId, "cleared", "Conversation history cleared.");
    }

    public List<ChatHistoryMessage> history(String userId) {
        String normalizedUserId = conversationHistoryService.normalizeUserId(userId);
        AgentTaskMemory taskMemory = taskMemoryService.read(normalizedUserId);
        return conversationHistoryService.load(normalizedUserId).stream()
                .map(message -> new ChatHistoryMessage(
                        message.role(),
                        message.content(),
                        message.createdAt(),
                        message.songs(),
                        activeConfirmation(message.confirmation(), taskMemory)
                ))
                .toList();
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }

    private boolean isTerminal(AgentEvent event) {
        return "done".equals(event.type()) || "agent_error".equals(event.type());
    }

    private ChatConfirmation activeConfirmation(ChatConfirmation confirmation, AgentTaskMemory taskMemory) {
        if (confirmation == null || taskMemory == null || taskMemory.pendingLocalPlaylistAdd() == null) {
            return null;
        }
        if (!"local_playlist_add".equals(confirmation.type())) {
            return null;
        }
        List<String> pendingSongIds = taskMemory.pendingLocalPlaylistAdd().songs().stream()
                .map(song -> song.id() == null ? "" : song.id())
                .filter(id -> !id.isBlank())
                .toList();
        List<String> confirmationSongIds = confirmation.songs().stream()
                .map(song -> song.id() == null ? "" : song.id())
                .filter(id -> !id.isBlank())
                .toList();
        return !pendingSongIds.isEmpty() && !confirmationSongIds.isEmpty() && pendingSongIds.containsAll(confirmationSongIds) ? confirmation : null;
    }
}
