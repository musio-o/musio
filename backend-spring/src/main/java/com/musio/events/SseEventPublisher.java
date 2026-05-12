package com.musio.events;

import com.musio.model.AgentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SseEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(SseEventPublisher.class);

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter create(String runId) {
        // 0L 表示服务端不主动设置超时时间，连接生命周期由终止事件或客户端断开决定。
        SseEmitter emitter = newEmitter();
        emitters.put(runId, emitter);
        // 无论正常完成、超时还是异常断开，都要移除 emitter，避免后续继续向失效连接写事件。
        emitter.onCompletion(() -> emitters.remove(runId));
        emitter.onTimeout(() -> emitters.remove(runId));
        emitter.onError(error -> emitters.remove(runId));
        publish(runId, AgentEvent.of("connected", Map.of("runId", runId)));
        return emitter;
    }

    public boolean publish(String runId, AgentEvent event) {
        SseEmitter emitter = emitters.get(runId);
        if (emitter == null) {
            return false;
        }

        try {
            emitter.send(SseEmitter.event()
                    .name(event.type())
                    .data(event));
            if ("done".equals(event.type()) || "agent_error".equals(event.type())) {
                // 终止事件发送成功后立即关闭 SSE 长连接，本轮 run 的事件流到此结束。
                emitter.complete();
                emitters.remove(runId);
            }
            return true;
        } catch (Exception e) {
            log.warn(
                    "Failed to publish SSE event for run {} type {}: {}",
                    runId,
                    event.type(),
                    e.toString()
            );
            completeWithError(emitter, e);
            emitters.remove(runId);
            return false;
        }
    }

    protected SseEmitter newEmitter() {
        return new SseEmitter(0L);
    }

    boolean hasEmitter(String runId) {
        return emitters.containsKey(runId);
    }

    private void completeWithError(SseEmitter emitter, Exception error) {
        try {
            emitter.completeWithError(error);
        } catch (Exception closeError) {
            log.debug("Ignoring SSE emitter close failure: {}", closeError.toString());
        }
    }
}
