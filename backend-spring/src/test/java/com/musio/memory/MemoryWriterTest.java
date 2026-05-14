package com.musio.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void writesBehaviorPreferenceCacheAndConversationSummaryAfterTurn() {
        Stores stores = stores();
        MemoryWriter writer = new MemoryWriter(
                stores.behaviorEventStore(),
                stores.preferenceStore(),
                stores.musicCacheStore(),
                stores.conversationSummaryStore(),
                new ObjectMapper()
        );
        Instant now = Instant.parse("2026-05-13T10:00:00Z");
        Song song = new Song("qqmusic:1", ProviderType.QQMUSIC, "安静", List.of("周杰伦"), "范特西", 260, "");
        AgentObservation comments = new AgentObservation(
                "loop.step.1",
                "get_hot_comments",
                Map.of("songId", song.id()),
                AgentObservationStatus.SUCCESS,
                "{\"success\":true,\"summary\":\"评论都说这首歌很治愈，适合慢下来。\"}",
                "读取热门评论",
                List.of(song)
        );

        MemoryWritePlan plan = writer.writeAfterTurn(new MemoryWriteRequest(
                "local",
                "今天有点累，别太吵，推荐一首再看看评论",
                goal(),
                MemoryContextPackage.empty(),
                new AgentLoopEvidence(List.of(comments), List.of(song), "comments", song, List.of("读取评论")),
                "这首《安静》更稳一点。",
                now
        ));

        assertTrue(plan.behaviorEvents().stream().anyMatch(event -> "session_preference".equals(event.type())));
        assertTrue(plan.behaviorEvents().stream().anyMatch(event -> "comments_read".equals(event.type())));
        assertTrue(plan.preferenceCandidates().stream().anyMatch(candidate -> "too_noisy".equals(candidate.name())
                && candidate.confidenceDelta() <= 0.15));
        assertTrue(plan.musicCacheEntries().stream().anyMatch(entry -> "comments".equals(entry.cacheType())
                && entry.content().contains("\"summary\"")));
        assertTrue(plan.musicCacheEntries().stream().anyMatch(entry -> "commentSummary".equals(entry.cacheType())
                && entry.content().equals("评论都说这首歌很治愈，适合慢下来。")));
        assertFalse(plan.conversationSummaries().isEmpty());

        assertTrue(stores.behaviorEventStore().recent("local", now.minusSeconds(1), 20).stream()
                .anyMatch(event -> "comments_read".equals(event.type())));
        assertTrue(stores.musicCacheStore().search("local", List.of("comments"), "治愈", 5).stream()
                .anyMatch(entry -> entry.content().contains("治愈")));
        assertTrue(stores.musicCacheStore().search("local", List.of("commentSummary"), "治愈", 5).stream()
                .anyMatch(entry -> "commentSummary".equals(entry.cacheType())
                        && entry.content().contains("治愈")));
        assertTrue(stores.conversationSummaryStore().search("local", "别太吵", 5).stream()
                .anyMatch(summary -> summary.summary().contains("别太吵")));

        List<PreferenceItem> items = new PreferenceAggregator(stores.preferenceStore()).aggregate("local", now.plusSeconds(1));
        assertTrue(items.stream().anyMatch(item -> item.key().contains("too_noisy")
                && item.confidence() > 0
                && item.confidence() <= 0.1));
    }

    private AgentGoal goal() {
        return new AgentGoal(
                "今天有点累，别太吵，推荐一首再看看评论",
                "推荐一首不太吵的歌并读取评论",
                "recommend",
                "new_task",
                true,
                true,
                false,
                false,
                1,
                List.of(),
                List.of(AgentRequiredOutcome.RECOMMENDATION, AgentRequiredOutcome.COMMENTS)
        );
    }

    private Stores stores() {
        SQLiteMemoryDatabase database = new SQLiteMemoryDatabase(tempDir.resolve("memory.sqlite"));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        return new Stores(
                new BehaviorEventStore(database, objectMapper),
                new PreferenceStore(database),
                new MusicCacheStore(database),
                new ConversationSummaryStore(database, objectMapper)
        );
    }

    private record Stores(
            BehaviorEventStore behaviorEventStore,
            PreferenceStore preferenceStore,
            MusicCacheStore musicCacheStore,
            ConversationSummaryStore conversationSummaryStore
    ) {
    }
}
