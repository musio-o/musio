package com.musio.agent;

import com.musio.events.AgentEventBus;
import com.musio.events.SseEventPublisher;
import com.musio.memory.AgentTaskMemoryService;
import com.musio.model.AgentEvent;
import com.musio.model.ChatRequest;
import com.musio.model.ChatRunResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunServiceTest {
    @Test
    void unsubscribesRunWhenSsePublishFails() {
        AgentRuntime agentRuntime = mock(AgentRuntime.class);
        SseEventPublisher eventPublisher = mock(SseEventPublisher.class);
        AgentEventBus eventBus = new AgentEventBus();
        AgentRunService service = new AgentRunService(
                agentRuntime,
                eventPublisher,
                eventBus,
                mock(ConversationHistoryService.class),
                mock(AgentTaskMemoryService.class),
                mock(ConfirmationService.class)
        );

        try {
            ChatRunResponse response = service.startRun(new ChatRequest("local", "推荐一首歌"));
            String runId = response.runId();
            when(eventPublisher.create(runId)).thenReturn(new SseEmitter(0L));
            when(eventPublisher.publish(eq(runId), any(AgentEvent.class))).thenReturn(false);

            service.connect(runId);
            clearInvocations(eventPublisher);

            eventBus.publish(runId, AgentEvent.of("trace_step", Map.of("runId", runId)));
            eventBus.publish(runId, AgentEvent.of("trace_step", Map.of("runId", runId)));

            verify(eventPublisher, times(1)).publish(eq(runId), any(AgentEvent.class));
        } finally {
            service.shutdown();
        }
    }
}
