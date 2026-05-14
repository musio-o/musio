package com.musio.memory.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.AgentGoal;
import com.musio.agent.AgentRequiredOutcome;
import com.musio.agent.ConversationHistoryMessage;
import com.musio.model.AgentRecentRecommendedSong;
import com.musio.model.AgentTaskMemory;
import com.musio.model.AgentTaskRecommendationSlot;
import com.musio.model.PendingLocalPlaylistAdd;
import com.musio.model.ProviderType;
import com.musio.model.Song;
import com.musio.player.PlayerQueueService;
import com.musio.player.PlayerState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryContextPipelineTest {
    @Test
    void previousSongCommentsRouteReadsTaskMemoryAndReservesMusicCache() {
        MemoryReadPlan plan = new DeterministicMemoryGuard().requiredPlan(new MemoryRouteRequest(
                "local",
                "刚才那首看评论区怎么说",
                "comments",
                "refer_previous_song",
                "刚才那首看评论区怎么说",
                goal("comments", List.of(AgentRequiredOutcome.COMMENTS)),
                taskMemory(),
                List.of()
        ));

        assertTrue(has(plan, MemoryType.TASK_MEMORY, "lastTargetSong"));
        assertTrue(has(plan, MemoryType.TASK_MEMORY, "lastResultSongs"));
        assertTrue(has(plan, MemoryType.TASK_MEMORY, "lastRecommendationSlots"));
        assertFalse(has(plan, MemoryType.CURRENT_STATE, "currentPlayback"));
        assertTrue(has(plan, MemoryType.MUSIC_CACHE, "commentSummary"));
        assertTrue(has(plan, MemoryType.MUSIC_CACHE, "comments"));
    }

    @Test
    void explicitPlaybackReferenceReadsCurrentState() {
        MemoryReadPlan plan = new DeterministicMemoryGuard().requiredPlan(new MemoryRouteRequest(
                "local",
                "正在播放的这首看评论区怎么说",
                "comments",
                "refer_previous_song",
                "正在播放的这首看评论区怎么说",
                goal("comments", List.of(AgentRequiredOutcome.COMMENTS)),
                taskMemory(),
                List.of()
        ));

        assertTrue(has(plan, MemoryType.TASK_MEMORY, "lastResultSongs"));
        assertTrue(has(plan, MemoryType.CURRENT_STATE, "currentPlayback"));
    }

    @Test
    void queuePlaybackReferenceReadsQueueState() {
        MemoryReadPlan plan = new DeterministicMemoryGuard().requiredPlan(new MemoryRouteRequest(
                "local",
                "队列上一首是哪首",
                "playback",
                "refer_previous_song",
                "队列上一首是哪首",
                goal("playback", List.of(AgentRequiredOutcome.PLAYBACK)),
                taskMemory(),
                List.of()
        ));

        assertTrue(has(plan, MemoryType.TASK_MEMORY, "lastResultSongs"));
        assertTrue(has(plan, MemoryType.CURRENT_STATE, "queueState"));
    }

    @Test
    void recommendationRouteReadsProfileAndRecentRecommendationMemory() {
        MemoryReadPlan plan = new DeterministicMemoryGuard().requiredPlan(new MemoryRouteRequest(
                "local",
                "推荐几首适合晚上写代码的",
                "recommend",
                "new_task",
                "推荐几首适合晚上写代码的",
                goal("recommend", List.of(AgentRequiredOutcome.RECOMMENDATION)),
                taskMemory(),
                List.of()
        ));

        assertTrue(has(plan, MemoryType.PROFILE_MEMORY, "summary"));
        assertTrue(has(plan, MemoryType.PROFILE_MEMORY, "recommendationHints"));
        assertTrue(has(plan, MemoryType.TASK_MEMORY, "recentRecommendedSongs"));
        assertTrue(has(plan, MemoryType.BEHAVIOR_SUMMARY, "last7DaysSummary"));
    }

    @Test
    void validatorDropsInvalidFieldsPathsAndRawBehaviorReads() {
        MemoryReadPlan required = new MemoryReadPlan(List.of(new MemoryReadItem(
                MemoryType.TASK_MEMORY,
                List.of("lastResultSongs"),
                "",
                "session",
                90,
                5,
                "required"
        )), 1200);
        MemoryReadPlan dynamic = new MemoryReadPlan(List.of(
                new MemoryReadItem(MemoryType.PROFILE_MEMORY, List.of("summary", "rawSecrets"), "../../profile.json", "profile", 80, 1, "bad path"),
                new MemoryReadItem(MemoryType.BEHAVIOR_SUMMARY, List.of("rawEvents", "last7DaysSummary"), "events_2026.jsonl", "last_7_days", 70, 5, "bad raw"),
                new MemoryReadItem(MemoryType.PROFILE_MEMORY, List.of("summary"), "personalized recommendation", "profile", 80, 20, "ok")
        ), 5000);

        MemoryReadPlan validated = new MemoryReadPlanValidator().validate(required, dynamic);

        assertTrue(has(validated, MemoryType.TASK_MEMORY, "lastResultSongs"));
        assertTrue(has(validated, MemoryType.PROFILE_MEMORY, "summary"));
        assertFalse(has(validated, MemoryType.PROFILE_MEMORY, "rawSecrets"));
        assertFalse(has(validated, MemoryType.BEHAVIOR_SUMMARY, "rawEvents"));
        assertEquals(1600, validated.tokenBudget());
    }

    @Test
    void validatorKeepsDistinctQueriesForSameMemoryType() {
        MemoryReadPlan dynamic = new MemoryReadPlan(List.of(
                new MemoryReadItem(MemoryType.MUSIC_CACHE, List.of("comments"), "安静", "session", 70, 2, "读安静评论"),
                new MemoryReadItem(MemoryType.MUSIC_CACHE, List.of("commentSummary"), "安静", "session", 60, 1, "读安静评论摘要"),
                new MemoryReadItem(MemoryType.MUSIC_CACHE, List.of("lyricsSummary"), "晴天", "session", 65, 1, "读晴天歌词")
        ), 1200);

        MemoryReadPlan validated = new MemoryReadPlanValidator().validate(MemoryReadPlan.empty(), dynamic);
        List<MemoryReadItem> cacheItems = validated.items().stream()
                .filter(item -> item.type() == MemoryType.MUSIC_CACHE)
                .toList();

        assertEquals(2, cacheItems.size());
        assertTrue(cacheItems.stream().anyMatch(item -> "安静".equals(item.query())
                && item.fields().contains("comments")
                && item.fields().contains("commentSummary")));
        assertTrue(cacheItems.stream().anyMatch(item -> "晴天".equals(item.query())
                && item.fields().contains("lyricsSummary")));
    }

    @Test
    void serviceBuildsSmallPromptFromExistingTaskMemoryOnly() {
        MemoryContextService service = new MemoryContextService(
                new DeterministicMemoryGuard(),
                null,
                new MemoryReadPlanValidator(),
                new MemoryRetriever(null),
                new MemoryCompressor()
        );

        MemoryContextPackage context = service.build(null, new MemoryRouteRequest(
                "local",
                "换一首，刚才那首推荐过了",
                "recommend",
                "correction",
                "换一首，刚才那首推荐过了",
                goal("recommend", List.of(AgentRequiredOutcome.RECOMMENDATION)),
                taskMemory(),
                List.of()
        ));

        assertFalse(context.isEmpty());
        assertTrue(context.promptText().contains("[动态记忆上下文]"));
        assertTrue(context.promptText().contains("短期任务记忆"));
        assertTrue(context.promptText().contains("上轮结果歌曲"));
        assertTrue(context.promptText().contains("近期已推荐"));
        assertTrue(context.estimatedTokens() <= 1200);
    }

    @Test
    void retrieverReturnsCurrentPlaybackAndQueueState() {
        PlayerQueueService playerQueueService = new PlayerQueueService();
        Song previous = new Song("qqmusic:0", ProviderType.QQMUSIC, "夜曲", List.of("周杰伦"), "十一月的萧邦", 230, "");
        Song current = new Song("qqmusic:1", ProviderType.QQMUSIC, "安静", List.of("周杰伦"), "范特西", 260, "");
        Song next = new Song("qqmusic:2", ProviderType.QQMUSIC, "晴天", List.of("周杰伦"), "叶惠美", 269, "");
        playerQueueService.sync(new PlayerState(
                current,
                List.of(previous, current, next),
                1,
                false,
                42,
                260,
                PlayerState.PlaybackMode.REPEAT_ALL,
                "只剩下钢琴陪我谈了一天"
        ));
        MemoryRetriever retriever = new MemoryRetriever(null, null, null, null, null, playerQueueService);
        MemoryReadPlan plan = new MemoryReadPlan(List.of(new MemoryReadItem(
                MemoryType.CURRENT_STATE,
                List.of("currentPlayback", "queueState"),
                "",
                "current",
                90,
                5,
                "读取播放器状态"
        )), 1200);

        MemoryContextPackage context = new MemoryCompressor().compress(retriever.retrieve(null, plan), 1200);

        assertTrue(context.promptText().contains("当前播放状态"));
        assertTrue(context.promptText().contains("当前播放: 安静"));
        assertTrue(context.promptText().contains("状态=播放中"));
        assertTrue(context.promptText().contains("上一首=夜曲"));
        assertTrue(context.promptText().contains("下一首=晴天"));
    }

    @Test
    void serviceCallsLlmRouteForMusicTaskEvenWhenDeterministicItemsExist() {
        RecordingLlmMemoryRoutePlanner llmRoutePlanner = new RecordingLlmMemoryRoutePlanner(new MemoryReadPlan(List.of(
                new MemoryReadItem(MemoryType.PROFILE_MEMORY, List.of("favoriteArtists"), "commute recommendation", "profile", 75, 1, "动态补充通勤画像")
        ), 900));
        MemoryContextService service = new MemoryContextService(
                new DeterministicMemoryGuard(),
                llmRoutePlanner,
                new MemoryReadPlanValidator(),
                new MemoryRetriever(null),
                new MemoryCompressor()
        );

        service.build(null, new MemoryRouteRequest(
                "local",
                "推荐一首适合通勤听的歌，并看看热评",
                "recommend",
                "new_task",
                "推荐一首适合通勤听的歌，并看看热评",
                goal("recommend", List.of(AgentRequiredOutcome.RECOMMENDATION, AgentRequiredOutcome.COMMENTS)),
                taskMemory(),
                List.of()
        ));

        assertEquals(1, llmRoutePlanner.calls);
    }

    @Test
    void serviceSkipsLlmRouteForPlainChatWithoutRequiredOutcomes() {
        RecordingLlmMemoryRoutePlanner llmRoutePlanner = new RecordingLlmMemoryRoutePlanner(MemoryReadPlan.empty());
        MemoryContextService service = new MemoryContextService(
                new DeterministicMemoryGuard(),
                llmRoutePlanner,
                new MemoryReadPlanValidator(),
                new MemoryRetriever(null),
                new MemoryCompressor()
        );

        service.build(null, new MemoryRouteRequest(
                "local",
                "你好",
                "chat",
                "new_task",
                "你好",
                new AgentGoal("你好", "你好", "chat", "new_task", false, false, false, false, 0, List.of(), List.of()),
                AgentTaskMemory.empty("local"),
                List.of()
        ));

        assertEquals(0, llmRoutePlanner.calls);
    }

    @Test
    void llmRouteParserAcceptsStructuredJsonOnly() {
        LlmMemoryRoutePlanner planner = new LlmMemoryRoutePlanner(null, new ObjectMapper(), new MemoryReadPlanValidator());

        MemoryReadPlan plan = planner.parsePlan("""
                {
                  "items": [
                    {"type": "PROFILE_MEMORY", "fields": ["summary", "favoriteArtists"], "query": "personalized recommendation", "scope": "profile", "priority": 80, "limit": 1, "reason": "推荐需要画像"}
                  ],
                  "tokenBudget": 900,
                  "confidence": 0.88
                }
                """).orElseThrow();

        assertEquals(1, plan.items().size());
        assertEquals(MemoryType.PROFILE_MEMORY, plan.items().getFirst().type());
        assertEquals(List.of("summary", "favoriteArtists"), plan.items().getFirst().fields());
    }

    @Test
    void llmRoutePreviewIncludesControlledTaskMemoryAndRecentHistory() {
        LlmMemoryRoutePlanner planner = new LlmMemoryRoutePlanner(null, new ObjectMapper(), new MemoryReadPlanValidator());
        String taskPreview = planner.taskMemoryPreview(taskMemory());
        String historyPreview = planner.recentHistoryPreview(List.of(
                history("user", "第一条会被截断窗口排除"),
                history("assistant", "第二条"),
                history("user", "第三条"),
                history("assistant", "第四条"),
                history("user", "第五条"),
                history("assistant", "第六条"),
                new ConversationHistoryMessage("assistant", "刚推荐了安静", Instant.now(), List.of(new Song("qqmusic:1", ProviderType.QQMUSIC, "安静", List.of("周杰伦"), "范特西", 260, "")))
        ));

        assertTrue(taskPreview.contains("lastResultSongRefs"));
        assertTrue(taskPreview.contains("lastRecommendationSlots"));
        assertTrue(taskPreview.contains("recentRecommendedSongs"));
        assertFalse(taskPreview.contains("lastSearchLimit"));
        assertFalse(historyPreview.contains("第一条会被截断窗口排除"));
        assertTrue(historyPreview.contains("刚推荐了安静"));
        assertTrue(historyPreview.contains("songs: 安静"));
    }

    private boolean has(MemoryReadPlan plan, MemoryType type, String field) {
        return plan.items().stream().anyMatch(item -> item.type() == type && item.fields().contains(field));
    }

    private AgentGoal goal(String taskType, List<AgentRequiredOutcome> outcomes) {
        return new AgentGoal(
                "request",
                "request",
                taskType,
                "new_task",
                !outcomes.isEmpty(),
                !outcomes.isEmpty(),
                false,
                false,
                3,
                List.of(),
                outcomes
        );
    }

    private AgentTaskMemory taskMemory() {
        Song song = new Song("qqmusic:1", ProviderType.QQMUSIC, "安静", List.of("周杰伦"), "范特西", 260, "");
        return new AgentTaskMemory(
                "local",
                "music-agent-task",
                "推荐几首晚上写代码的歌",
                "",
                null,
                List.of(song),
                List.of("安静"),
                List.of("安静"),
                List.of(),
                song,
                "recommend",
                List.of("推荐返回 1 首歌曲"),
                List.of("RECOMMENDATION"),
                List.of(new AgentTaskRecommendationSlot("slot-1", "scene", "晚上写代码", 1, List.of("qqmusic:1"), List.of("安静"))),
                List.of("recommend_songs"),
                List.of(),
                List.of(new AgentRecentRecommendedSong("qqmusic:1", "安静", List.of("周杰伦"), "", "request", "适合专注", "run-1", "soft_avoid", Instant.now())),
                new PendingLocalPlaylistAdd("default", song, "加入歌单", Instant.now()),
                Instant.now()
        );
    }

    private ConversationHistoryMessage history(String role, String content) {
        return new ConversationHistoryMessage(role, content, Instant.now(), List.of());
    }

    private static final class RecordingLlmMemoryRoutePlanner extends LlmMemoryRoutePlanner {
        private final MemoryReadPlan plan;
        private int calls;

        private RecordingLlmMemoryRoutePlanner(MemoryReadPlan plan) {
            super(null, new ObjectMapper(), new MemoryReadPlanValidator());
            this.plan = plan;
        }

        @Override
        public MemoryReadPlan route(com.musio.config.MusioConfig.Ai ai, MemoryRouteRequest request) {
            calls += 1;
            return plan;
        }
    }
}
