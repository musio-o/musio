package com.musio.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.capability.AgentCapabilityRegistry;
import com.musio.model.AgentTaskMemory;
import com.musio.model.MusicGeneStatus;
import com.musio.model.ProviderStatus;
import com.musio.model.ProviderType;
import com.musio.providers.ProviderStatusService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTurnPlannerTest {
    private final AgentTurnPlanner planner = new AgentTurnPlanner(null, new ObjectMapper());

    @Test
    void fallsBackToRespondOnlyWhenModelUnavailable() {
        AgentTurnPlan plan = planner.planTurn(null, "谢谢", List.of(), AgentTaskMemory.empty("local"));

        assertEquals(TurnDisposition.RESPOND_ONLY, plan.disposition());
        assertFalse(plan.usesTools());
        assertEquals("model_unavailable", plan.fallbackReason());
    }

    @Test
    void parsesRespondOnlyPlanWithoutToolCalls() {
        AgentTurnPlan plan = planner.parsePlan("""
                {
                  "disposition": "respond_only",
                  "taskType": "chat",
                  "contextMode": "new_task",
                  "effectiveRequest": "谢谢",
                  "memoryUse": {"usesTaskMemory": false, "usedFields": [], "reason": "普通感谢"},
                  "toolCalls": [],
                  "confidence": 0.94
                }
                """, "谢谢").orElseThrow();

        assertEquals(TurnDisposition.RESPOND_ONLY, plan.disposition());
        assertEquals("chat", plan.taskType());
        assertTrue(plan.toolCalls().isEmpty());
        assertFalse(plan.usesTools());
    }

    @Test
    void parsesCorrectionSearchPlanWithExplicitLimit() {
        AgentTurnPlan plan = planner.parsePlan("""
                {
                  "disposition": "use_tools",
                  "taskType": "search",
                  "contextMode": "correction",
                  "effectiveRequest": "把上一轮搜索关键词从左弦纠正为后弦，并搜索后弦的歌曲",
                  "memoryUse": {"usesTaskMemory": true, "usedFields": ["lastSearchKeyword"], "reason": "用户在纠正上一轮搜索关键词"},
                  "toolCalls": [
                    {"toolName": "search_songs", "arguments": {"keyword": "后弦", "limit": 8}}
                  ],
                  "confidence": 0.92
                }
                """, "是后弦哈哈哈").orElseThrow();

        assertEquals(TurnDisposition.USE_TOOLS, plan.disposition());
        assertTrue(plan.usesTools());
        assertEquals("search", plan.taskType());
        assertEquals("correction", plan.contextMode());
        assertEquals(1, plan.toolCalls().size());
        assertEquals("search_songs", plan.toolCalls().getFirst().toolName());
        assertEquals("后弦", plan.toolCalls().getFirst().arguments().get("keyword"));
        assertEquals(8, plan.toolCalls().getFirst().arguments().get("limit"));
        assertTrue(plan.memoryUse().usesTaskMemory());
        assertEquals(List.of("lastSearchKeyword"), plan.memoryUse().usedFields());
    }

    @Test
    void mapsCorrectionSearchPlanToLegacyTaskContext() {
        AgentTurnPlan plan = planner.parsePlan("""
                {
                  "disposition": "use_tools",
                  "taskType": "search",
                  "contextMode": "correction",
                  "effectiveRequest": "搜索后弦的歌曲",
                  "memoryUse": {"usesTaskMemory": true, "usedFields": ["lastSearchKeyword"], "reason": "纠正上一轮关键词"},
                  "toolCalls": [
                    {"toolName": "search_songs", "arguments": {"keyword": "后弦", "limit": 8}}
                  ],
                  "confidence": 0.92
                }
                """, "是后弦哈哈哈").orElseThrow();

        AgentTaskContext context = plan.toLegacyTaskContext("是后弦哈哈哈");

        assertTrue(context.agentTask());
        assertTrue(context.followUp());
        assertEquals("search", context.taskType());
        assertEquals("correction", context.contextMode());
        assertEquals("搜索后弦的歌曲", context.planningMessage());
        assertEquals("后弦", context.searchKeyword());
        assertEquals(8, context.searchLimit());
        assertTrue(context.memoryAccess().useLastSearchKeyword());
    }

    @Test
    void keepsRecommendationIntentWhenModelOutputsLegacyRecommendTool() {
        AgentTurnPlan plan = planner.parsePlan("""
                {
                  "disposition": "use_tools",
                  "taskType": "recommend",
                  "contextMode": "new_task",
                  "effectiveRequest": "给我推荐 5 首适合深夜写代码听的歌。",
                  "memoryUse": {"usesTaskMemory": false, "usedFields": [], "reason": "新的场景推荐任务"},
                  "toolCalls": [
                    {"toolName": "recommend_songs", "arguments": {"request": "给我推荐 5 首适合深夜写代码听的歌。", "count": 5}}
                  ],
                  "confidence": 0.92
                }
                """, "给我推荐 5 首适合深夜写代码听的歌。").orElseThrow();

        assertEquals(TurnDisposition.USE_TOOLS, plan.disposition());
        assertEquals("recommend", plan.taskType());
        assertTrue(plan.usesTools());
        assertTrue(plan.toolCalls().isEmpty());
        assertTrue(plan.requiredOutcomes().isEmpty());
        assertTrue(plan.toToolPlan().toolCalls().isEmpty());

        AgentTaskContext context = plan.toLegacyTaskContext("给我推荐 5 首适合深夜写代码听的歌。");

        assertTrue(context.agentTask());
        assertEquals("recommend", context.taskType());
        assertEquals(0, context.searchLimit());
        assertEquals("", context.searchKeyword());
    }

    @Test
    void parsesLocalMusioPlaylistAddPlanAsInternalTool() {
        AgentTurnPlan plan = planner.parsePlan("""
                {
                  "disposition": "use_tools",
                  "taskType": "playlist",
                  "contextMode": "refer_previous_song",
                  "effectiveRequest": "把上一轮第一首歌收藏到 Musio 默认歌单",
                  "memoryUse": {"usesTaskMemory": true, "usedFields": ["lastResultSongs"], "reason": "用户要收藏上一轮歌曲卡片"},
                  "toolCalls": [
                    {"toolName": "add_song_to_musio_playlist", "arguments": {"playlistId": "default", "songIndex": 1}}
                  ],
                  "confidence": 0.92
                }
                """, "帮我收藏第一首").orElseThrow();

        assertEquals(TurnDisposition.USE_TOOLS, plan.disposition());
        assertEquals("playlist", plan.taskType());
        assertTrue(plan.hasTool("add_song_to_musio_playlist"));
        assertTrue(plan.requiredOutcomes().isEmpty());
        assertTrue(plan.toToolPlan().toolCalls().isEmpty());
        assertEquals("default", plan.toolCalls().getFirst().arguments().get("playlistId"));
        assertEquals(1, plan.toolCalls().getFirst().arguments().get("songIndex"));
    }

    @Test
    void parsesRecommendationPlanWithoutToolHints() {
        AgentTurnPlan plan = planner.parsePlan("""
                {
                  "disposition": "use_tools",
                  "taskType": "recommend",
                  "contextMode": "new_task",
                  "effectiveRequest": "推荐适合深夜写代码听的歌",
                  "memoryUse": {"usesTaskMemory": false, "usedFields": [], "reason": "新的场景推荐任务"},
                  "toolCalls": [],
                  "confidence": 0.92
                }
                """, "推荐适合深夜写代码听的歌").orElseThrow();

        assertEquals(TurnDisposition.USE_TOOLS, plan.disposition());
        assertEquals("recommend", plan.taskType());
        assertTrue(plan.usesTools());
        assertTrue(plan.toolCalls().isEmpty());
        assertTrue(plan.requiredOutcomes().isEmpty());
        assertEquals("", plan.fallbackReason());
    }

    @Test
    void parsesRecommendationSlotsForMultiTargetRecommendation() {
        AgentTurnPlan plan = planner.parsePlan("""
                {
                  "disposition": "use_tools",
                  "taskType": "recommend",
                  "contextMode": "new_task",
                  "effectiveRequest": "推荐两首许嵩的歌和一首后弦的歌，并获取热评",
                  "memoryUse": {"usesTaskMemory": false, "usedFields": [], "reason": "新的复合推荐任务"},
                  "requiredOutcomes": ["recommendation", "comments"],
                  "recommendationSlots": [
                    {"slotId": "xusong", "targetType": "artist", "target": "许嵩", "count": 2},
                    {"slotId": "houxian", "targetType": "artist", "target": "后弦", "count": 1}
                  ],
                  "toolCalls": [],
                  "confidence": 0.92
                }
                """, "推荐两首许嵩的歌和一首后弦的歌，并获取热评").orElseThrow();

        assertEquals(TurnDisposition.USE_TOOLS, plan.disposition());
        assertEquals(2, plan.recommendationSlots().size());
        assertEquals("xusong", plan.recommendationSlots().getFirst().slotId());
        assertEquals(2, plan.recommendationSlots().getFirst().count());
        assertEquals("houxian", plan.recommendationSlots().get(1).slotId());
        assertEquals(1, plan.recommendationSlots().get(1).count());
    }

    @Test
    void parsesStructuredRequiredOutcomesForCompositeTask() {
        AgentTurnPlan plan = planner.parsePlan("""
                {
                  "disposition": "use_tools",
                  "taskType": "recommend",
                  "contextMode": "new_task",
                  "effectiveRequest": "推荐一首适合专注的歌，并获取热评",
                  "memoryUse": {"usesTaskMemory": false, "usedFields": [], "reason": "新的复合推荐任务"},
                  "requiredOutcomes": ["recommendation", "comments"],
                  "toolCalls": [],
                  "confidence": 0.92
                }
                """, "推荐一首适合专注的歌，并获取热评").orElseThrow();

        assertEquals(TurnDisposition.USE_TOOLS, plan.disposition());
        assertEquals("recommend", plan.taskType());
        assertEquals(List.of(AgentRequiredOutcome.RECOMMENDATION, AgentRequiredOutcome.COMMENTS), plan.requiredOutcomes());
    }

    @Test
    void turnPlannerMemoryPreviewDoesNotExposeLastSearchLimit() {
        AgentTaskMemory memory = new AgentTaskMemory(
                "local",
                "music-agent-task",
                "搜索周杰伦的另一首歌曲",
                "周杰伦",
                8,
                List.of(),
                List.of("简单爱", "告白气球"),
                List.of(),
                List.of(),
                null,
                "",
                List.of(),
                null,
                Instant.EPOCH
        );

        String preview = planner.turnPlannerTaskMemoryPreview(memory);

        assertTrue(preview.contains("lastSearchKeyword: 周杰伦"));
        assertTrue(preview.contains("lastResultSongTitles: 简单爱、告白气球"));
        assertFalse(preview.contains("lastSearchLimit"));
    }

    @Test
    void dropsUnknownToolButKeepsMusicLoopIntent() {
        AgentTurnPlan plan = planner.parsePlan("""
                {
                  "disposition": "use_tools",
                  "taskType": "search",
                  "contextMode": "new_task",
                  "effectiveRequest": "搜索后弦",
                  "memoryUse": {"usesTaskMemory": false, "usedFields": [], "reason": "新任务"},
                  "toolCalls": [
                    {"toolName": "delete_file", "arguments": {"path": "/tmp/x"}}
                  ],
                  "confidence": 0.91
                }
                """, "搜索后弦").orElseThrow();

        assertEquals(TurnDisposition.USE_TOOLS, plan.disposition());
        assertEquals("search", plan.taskType());
        assertTrue(plan.usesTools());
        assertTrue(plan.toolCalls().isEmpty());
        assertEquals("", plan.fallbackReason());
    }

    @Test
    void dropsSourceReadHintsWhenPolicyGateHidesSourceTools() {
        AgentCapabilityRegistry registry = new AgentCapabilityRegistry();
        AgentTurnPlanner gatedPlanner = new AgentTurnPlanner(
                null,
                new ObjectMapper(),
                registry,
                new AgentPolicyGate(registry, new StubProviderStatusService())
        );

        AgentTurnPlan plan = gatedPlanner.parsePlan("""
                {
                  "disposition": "use_tools",
                  "taskType": "search",
                  "contextMode": "new_task",
                  "effectiveRequest": "搜索周杰伦",
                  "memoryUse": {"usesTaskMemory": false, "usedFields": [], "reason": "新任务"},
                  "toolCalls": [
                    {"toolName": "search_songs", "arguments": {"keyword": "周杰伦", "limit": 1}}
                  ],
                  "confidence": 0.91
                }
                """, "搜索周杰伦").orElseThrow();

        assertEquals(TurnDisposition.USE_TOOLS, plan.disposition());
        assertTrue(plan.toolCalls().isEmpty());
    }

    @Test
    void dropsInvalidSearchHintButKeepsMusicLoopIntent() {
        AgentTurnPlan plan = planner.parsePlan("""
                {
                  "disposition": "use_tools",
                  "taskType": "search",
                  "contextMode": "new_task",
                  "effectiveRequest": "搜索歌曲",
                  "memoryUse": {"usesTaskMemory": false, "usedFields": [], "reason": "新任务"},
                  "toolCalls": [
                    {"toolName": "search_songs", "arguments": {"limit": 8}}
                  ],
                  "confidence": 0.91
                }
                """, "搜索歌曲").orElseThrow();

        assertEquals(TurnDisposition.USE_TOOLS, plan.disposition());
        assertEquals("search", plan.taskType());
        assertTrue(plan.usesTools());
        assertTrue(plan.toolCalls().isEmpty());
        assertEquals("", plan.fallbackReason());
    }

    @Test
    void dropsSearchHintWithoutLimitButKeepsMusicLoopIntent() {
        AgentTurnPlan plan = planner.parsePlan("""
                {
                  "disposition": "use_tools",
                  "taskType": "search",
                  "contextMode": "follow_up",
                  "effectiveRequest": "搜索周杰伦的另一首歌曲",
                  "memoryUse": {"usesTaskMemory": true, "usedFields": ["lastSearchKeyword"], "reason": "延续上一轮搜索目标"},
                  "toolCalls": [
                    {"toolName": "search_songs", "arguments": {"keyword": "周杰伦"}}
                  ],
                  "confidence": 0.91
                }
                """, "换一首").orElseThrow();

        assertEquals(TurnDisposition.USE_TOOLS, plan.disposition());
        assertEquals("search", plan.taskType());
        assertTrue(plan.usesTools());
        assertTrue(plan.toolCalls().isEmpty());
        assertEquals("", plan.fallbackReason());
    }

    @Test
    void lowConfidencePlanFallsBackToRespondOnly() {
        AgentTurnPlan plan = planner.parsePlan("""
                {
                  "disposition": "use_tools",
                  "taskType": "search",
                  "contextMode": "new_task",
                  "effectiveRequest": "搜索后弦",
                  "memoryUse": {"usesTaskMemory": false, "usedFields": [], "reason": "新任务"},
                  "toolCalls": [
                    {"toolName": "search_songs", "arguments": {"keyword": "后弦", "limit": 8}}
                  ],
                  "confidence": 0.2
                }
                """, "搜索后弦").orElseThrow();

        assertEquals(TurnDisposition.RESPOND_ONLY, plan.disposition());
        assertTrue(plan.toolCalls().isEmpty());
        assertEquals("low_confidence", plan.fallbackReason());
    }

    private static final class StubProviderStatusService extends ProviderStatusService {
        private StubProviderStatusService() {
            super(null, null, null, null);
        }

        @Override
        public ProviderStatus status(ProviderType provider) {
            return new ProviderStatus(
                    ProviderType.QQMUSIC,
                    "QQ 音乐",
                    true,
                    false,
                    false,
                    "QR_CODE",
                    "需要扫码登录 QQ 音乐。",
                    "NOT_LOGGED_IN",
                    "UNAVAILABLE",
                    MusicGeneStatus.unavailable(ProviderType.QQMUSIC, "需要扫码登录 QQ 音乐。")
            );
        }
    }

}
