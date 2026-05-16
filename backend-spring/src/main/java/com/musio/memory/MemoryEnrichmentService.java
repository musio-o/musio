package com.musio.memory;

import com.musio.agent.AgentRunContext;
import com.musio.agent.capability.AgentCapabilityRegistry;
import com.musio.agent.loop.AgentLoopEvidence;
import com.musio.agent.loop.AgentObservationStatus;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class MemoryEnrichmentService {
    private static final Logger log = LoggerFactory.getLogger(MemoryEnrichmentService.class);
    private static final int MAX_DEDUPE_KEYS = 1000;
    private static final List<String> FEEDBACK_SIGNALS = List.of(
            "喜欢", "不喜欢", "讨厌", "别放", "不要听", "不想听", "太吵", "太炸",
            "多来点", "就这种", "这个不错", "这首", "这类"
    );
    private static final Set<String> MUSIC_TOOLS = Set.of(
            AgentCapabilityRegistry.RECOMMEND_SONGS,
            "search_songs",
            "get_hot_comments",
            "get_lyrics",
            "get_song_detail",
            AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST
    );

    private final LlmMemoryEnricher enricher;
    private final PreferenceStore preferenceStore;
    private final ConversationSummaryStore conversationSummaryStore;
    private final MusicCacheStore musicCacheStore;
    private final ExecutorService queueExecutor;
    private final ExecutorService modelExecutor;
    private final Set<String> submittedKeys = ConcurrentHashMap.newKeySet();
    private final boolean enabled;
    private final long timeoutMs;

    @Autowired
    public MemoryEnrichmentService(
            LlmMemoryEnricher enricher,
            PreferenceStore preferenceStore,
            ConversationSummaryStore conversationSummaryStore,
            MusicCacheStore musicCacheStore,
            Environment environment
    ) {
        this(
                enricher,
                preferenceStore,
                conversationSummaryStore,
                musicCacheStore,
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "musio-memory-enrichment");
                    thread.setDaemon(true);
                    return thread;
                }),
                Executors.newCachedThreadPool(runnable -> {
                    Thread thread = new Thread(runnable, "musio-memory-enrichment-model");
                    thread.setDaemon(true);
                    return thread;
                }),
                environment == null || environment.getProperty("musio.memory.llm-enrichment.enabled", Boolean.class, true),
                environment == null ? 15000L : environment.getProperty("musio.memory.llm-enrichment.timeout-ms", Long.class, 15000L)
        );
    }

    MemoryEnrichmentService(
            LlmMemoryEnricher enricher,
            PreferenceStore preferenceStore,
            ConversationSummaryStore conversationSummaryStore,
            MusicCacheStore musicCacheStore,
            ExecutorService queueExecutor,
            ExecutorService modelExecutor,
            boolean enabled,
            long timeoutMs
    ) {
        this.enricher = enricher;
        this.preferenceStore = preferenceStore;
        this.conversationSummaryStore = conversationSummaryStore;
        this.musicCacheStore = musicCacheStore;
        this.queueExecutor = queueExecutor;
        this.modelExecutor = modelExecutor;
        this.enabled = enabled;
        this.timeoutMs = Math.max(1L, timeoutMs);
    }

    public void submit(MemoryWriteRequest request) {
        if (!enabled || enricher == null || !shouldEnrich(request)) {
            return;
        }
        String key = dedupeKey(request);
        if (!submittedKeys.add(key)) {
            return;
        }
        if (submittedKeys.size() > MAX_DEDUPE_KEYS) {
            submittedKeys.clear();
            submittedKeys.add(key);
        }
        String runId = AgentRunContext.runId().orElse("");
        String userId = AgentRunContext.userId().orElse(request.userId());
        queueExecutor.submit(() -> runJob(request, runId, userId));
    }

    boolean shouldEnrich(MemoryWriteRequest request) {
        if (request == null) {
            return false;
        }
        if (request.goal() != null && request.goal().musicTask()) {
            return true;
        }
        AgentLoopEvidence evidence = request.loopEvidence();
        if (evidence != null) {
            if (evidence.hasObservations() || evidence.targetSong() != null || !evidence.songs().isEmpty()) {
                return true;
            }
            boolean hasMusicTool = evidence.observations().stream()
                    .filter(observation -> observation.status() == AgentObservationStatus.SUCCESS)
                    .anyMatch(observation -> MUSIC_TOOLS.contains(observation.toolName()));
            if (hasMusicTool) {
                return true;
            }
        }
        String normalized = normalize(request.userMessage());
        return FEEDBACK_SIGNALS.stream().anyMatch(normalized::contains);
    }

    void process(MemoryWriteRequest request) {
        process(request, "", request == null ? "" : request.userId());
    }

    void process(MemoryWriteRequest request, String runId, String userId) {
        if (request == null || enricher == null) {
            return;
        }
        Future<MemoryEnrichmentResult> future = modelExecutor.submit(() -> {
            setRunContext(runId, userId);
            try {
                return enricher.enrich(request);
            } finally {
                AgentRunContext.clear();
            }
        });
        try {
            MemoryEnrichmentResult result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            writeResult(request, result);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Memory enrichment timed out for user {}", request.userId());
        } catch (Exception e) {
            future.cancel(true);
            log.warn("Memory enrichment job failed for user {}", request.userId(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        queueExecutor.shutdownNow();
        modelExecutor.shutdownNow();
    }

    private void runJob(MemoryWriteRequest request, String runId, String userId) {
        setRunContext(runId, userId);
        try {
            process(request, runId, userId);
        } finally {
            AgentRunContext.clear();
        }
    }

    private void setRunContext(String runId, String userId) {
        if (runId != null && !runId.isBlank()) {
            AgentRunContext.setRunId(runId);
        }
        if (userId != null && !userId.isBlank()) {
            AgentRunContext.setUserId(userId);
        }
    }

    private void writeResult(MemoryWriteRequest request, MemoryEnrichmentResult result) {
        if (result == null || result.isEmpty()) {
            return;
        }
        Instant occurredAt = request.occurredAt();
        for (LlmPreferenceCandidate candidate : result.preferenceCandidates()) {
            if (!candidate.writable() || preferenceStore == null) {
                continue;
            }
            preferenceStore.addCandidate(new PreferenceCandidate(
                    "",
                    request.userId(),
                    candidate.polarity(),
                    candidate.name(),
                    candidate.label(),
                    candidate.confidenceDelta(),
                    candidate.evidence().isBlank() ? request.userMessage() : candidate.evidence(),
                    "session".equals(candidate.scope()) ? "llm_session_feedback" : "llm_explicit_feedback",
                    occurredAt
            ));
        }
        if (conversationSummaryStore != null && !result.conversationSummary().isEmpty()) {
            conversationSummaryStore.upsert(new ConversationSummary(
                    "",
                    request.userId(),
                    result.conversationSummary().summary(),
                    result.conversationSummary().keywords(),
                    occurredAt
            ));
        }
        if (musicCacheStore != null) {
            for (LlmMusicInsight insight : result.musicInsights()) {
                if (!insight.writable()) {
                    continue;
                }
                musicCacheStore.upsert(new MusicCacheEntry(
                        "",
                        request.userId(),
                        "llmMusicInsight",
                        insight.songId(),
                        insight.title(),
                        insight.artist(),
                        insight.content(),
                        insight.evidence(),
                        occurredAt
                ));
            }
        }
    }

    private String dedupeKey(MemoryWriteRequest request) {
        return request.userId() + ":" + request.occurredAt() + ":" + sha256(request.userMessage()) + ":" + sha256(request.finalAnswer());
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString((value == null ? "" : value).hashCode());
        }
    }

    private String normalize(String value) {
        return (value == null ? "" : value).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
