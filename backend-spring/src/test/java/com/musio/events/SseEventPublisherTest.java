package com.musio.events;

import com.musio.model.AgentEvent;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SseEventPublisherTest {
    @Test
    void removesEmitterWhenSendThrowsRuntimeException() {
        FailingEmitter emitter = new FailingEmitter();
        SseEventPublisher publisher = new TestSseEventPublisher(emitter);

        publisher.create("run-1");

        assertFalse(publisher.hasEmitter("run-1"));
        assertInstanceOf(IllegalStateException.class, emitter.completedError);
        assertFalse(publisher.publish("run-1", AgentEvent.of("trace_step", Map.of("runId", "run-1"))));
    }

    private static final class TestSseEventPublisher extends SseEventPublisher {
        private final SseEmitter emitter;

        private TestSseEventPublisher(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        protected SseEmitter newEmitter() {
            return emitter;
        }
    }

    private static final class FailingEmitter extends SseEmitter {
        private Throwable completedError;

        private FailingEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new IllegalStateException("connection reset");
        }

        @Override
        public void completeWithError(Throwable ex) {
            completedError = ex;
        }
    }
}
