package com.musio.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.AgentRunContext;
import com.musio.agent.AgentGoal;
import com.musio.agent.AgentRequiredOutcome;
import com.musio.agent.loop.AgentLoopEvidence;
import com.musio.agent.loop.AgentObservation;
import com.musio.agent.loop.AgentObservationStatus;
import com.musio.memory.context.MemoryContextPackage;
import com.musio.model.ProviderType;
import com.musio.model.Song;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryEnrichmentServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldEnrichSkipsNonMusicGreeting() {
        MemoryEnrichmentService service = service(new StubEnricher(MemoryEnrichmentResult.empty()), null);

        assertFalse(service.shouldEnrich(request("你好", null, AgentLoopEvidence.empty())));
    }

    @Test
    void shouldEnrichAcceptsMusicTaskEvidenceAndFeedbackSignals() {
        MemoryEnrichmentService service = service(new StubEnricher(MemoryEnrichmentResult.empty()), null);
        Song song = new Song("qqmusic:1", ProviderType.QQMUSIC, "安静", List.of("周杰伦"), "", 0, "");
        AgentObservation observation = new AgentObservation(
                "step",
                "get_hot_comments",
                Map.of("songId", song.id()),
                AgentObservationStatus.SUCCESS,
                "{\"summary\":\"治愈\"}",
                "读取评论",
                List.of(song)
        );

        assertTrue(service.shouldEnrich(request("推荐一首歌", goal(), AgentLoopEvidence.empty())));
        assertTrue(service.shouldEnrich(request("你好", null, new AgentLoopEvidence(List.of(observation), List.of(song), "comments", song, List.of("读取评论")))));
        assertTrue(service.shouldEnrich(request("我不喜欢太吵的", null, AgentLoopEvidence.empty())));
    }

    @Test
    void submitWritesResultAndDedupesSameTurn() {
        Stores stores = stores();
        StubEnricher enricher = new StubEnricher(new MemoryEnrichmentResult(
                List.of(
                        new LlmPreferenceCandidate("negative", "too_noisy", "不想听太吵", 0.2, "long_term", "别太吵"),
                        new LlmPreferenceCandidate("positive", "ignore_this", "忽略", 0.2, "ignore", "寒暄")
                ),
                new LlmConversationSummary("用户表达不想听太吵的歌。", List.of("安静", "偏好")),
                List.of(new LlmMusicInsight("llmMusicInsight", "qqmusic:1", "安静", "周杰伦", "评论认为适合慢下来。", "comments")),
                0.9
        ));
        MemoryEnrichmentService service = service(enricher, stores);
        Instant now = Instant.parse("2026-05-13T10:00:00Z");
        MemoryWriteRequest request = new MemoryWriteRequest(
                "local",
                "今天别太吵",
                goal(),
                MemoryContextPackage.empty(),
                AgentLoopEvidence.empty(),
                "好的",
                now
        );

        service.submit(request);
        service.submit(request);

        assertEquals(1, enricher.calls.get());
        List<PreferenceCandidate> candidates = stores.preferenceStore().candidates("local", now.minusSeconds(1), 10);
        assertEquals(1, candidates.size());
        assertEquals("llm_explicit_feedback", candidates.getFirst().source());
        assertTrue(stores.conversationSummaryStore().search("local", "太吵", 5).stream()
                .anyMatch(summary -> summary.summary().contains("太吵")));
        assertTrue(stores.musicCacheStore().search("local", List.of("llmMusicInsight"), "慢下来", 5).stream()
                .anyMatch(entry -> "qqmusic:1".equals(entry.songId())));
    }

    @Test
    void submitDoesNotPropagateEnricherFailure() {
        StubEnricher enricher = new StubEnricher(MemoryEnrichmentResult.empty());
        enricher.fail = true;
        MemoryEnrichmentService service = service(enricher, null);

        assertDoesNotThrow(() -> service.submit(request("我不喜欢太吵的", null, AgentLoopEvidence.empty())));
        assertEquals(1, enricher.calls.get());
    }

    @Test
    void submitPropagatesRunContextIntoAsyncEnricher() {
        StubEnricher enricher = new StubEnricher(MemoryEnrichmentResult.empty());
        MemoryEnrichmentService service = service(enricher, null);

        AgentRunContext.setRunId("run-123");
        AgentRunContext.setUserId("local");
        try {
            service.submit(request("我很喜欢这个场景的歌", null, AgentLoopEvidence.empty()));
        } finally {
            AgentRunContext.clear();
        }

        assertEquals("run-123", enricher.capturedRunId);
        assertEquals("local", enricher.capturedUserId);
    }

    private MemoryEnrichmentService service(StubEnricher enricher, Stores stores) {
        SameThreadExecutorService queueExecutor = new SameThreadExecutorService();
        SameThreadExecutorService modelExecutor = new SameThreadExecutorService();
        return new MemoryEnrichmentService(
                enricher,
                stores == null ? null : stores.preferenceStore(),
                stores == null ? null : stores.conversationSummaryStore(),
                stores == null ? null : stores.musicCacheStore(),
                queueExecutor,
                modelExecutor,
                true,
                15000
        );
    }

    private MemoryWriteRequest request(String message, AgentGoal goal, AgentLoopEvidence evidence) {
        return new MemoryWriteRequest(
                "local",
                message,
                goal,
                MemoryContextPackage.empty(),
                evidence,
                "",
                Instant.parse("2026-05-13T10:00:00Z")
        );
    }

    private AgentGoal goal() {
        return new AgentGoal(
                "推荐一首歌",
                "推荐一首歌",
                "recommend",
                "new_task",
                true,
                true,
                false,
                false,
                1,
                List.of(),
                List.of(AgentRequiredOutcome.RECOMMENDATION)
        );
    }

    private Stores stores() {
        SQLiteMemoryDatabase database = new SQLiteMemoryDatabase(tempDir.resolve("memory.sqlite"));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        return new Stores(
                new PreferenceStore(database),
                new MusicCacheStore(database),
                new ConversationSummaryStore(database, objectMapper)
        );
    }

    private record Stores(
            PreferenceStore preferenceStore,
            MusicCacheStore musicCacheStore,
            ConversationSummaryStore conversationSummaryStore
    ) {
    }

    private static class StubEnricher extends LlmMemoryEnricher {
        private final MemoryEnrichmentResult result;
        private final AtomicInteger calls = new AtomicInteger();
        private String capturedRunId = "";
        private String capturedUserId = "";
        private boolean fail;

        private StubEnricher(MemoryEnrichmentResult result) {
            super(null, null, new ObjectMapper());
            this.result = result;
        }

        @Override
        public MemoryEnrichmentResult enrich(MemoryWriteRequest request) {
            calls.incrementAndGet();
            capturedRunId = AgentRunContext.runId().orElse("");
            capturedUserId = AgentRunContext.userId().orElse("");
            if (fail) {
                throw new IllegalStateException("boom");
            }
            return result;
        }
    }

    private static class SameThreadExecutorService extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}
