package com.musio.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.capability.AgentCapability;
import com.musio.agent.capability.AgentCapabilityArgumentContext;
import com.musio.agent.capability.AgentCapabilityHandler;
import com.musio.agent.capability.AgentCapabilityManifest;
import com.musio.agent.capability.AgentCapabilityRegistry;
import com.musio.agent.capability.AgentCapabilityValidationResult;
import com.musio.agent.capability.CapabilityEffect;
import com.musio.memory.context.MemoryContextPackage;
import com.musio.memory.context.MemoryEvidence;
import com.musio.memory.context.MemoryType;
import com.musio.model.AgentTaskMemory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentStepPlannerTest {
    private final AgentStepPlanner planner = new AgentStepPlanner(null, new ObjectMapper());

    @Test
    void parsesSearchToolCall() {
        AgentStepAction action = planner.parseAction("""
                {
                  "action": "tool_call",
                  "toolName": "search_songs",
                  "arguments": {"keyword": "周杰伦", "limit": 1},
                  "publicActivity": "搜索周杰伦的歌曲",
                  "confidence": 0.91,
                  "reason": "需要先找到目标歌曲"
                }
                """).orElseThrow();

        assertEquals(AgentStepActionType.TOOL_CALL, action.action());
        assertEquals("search_songs", action.toolName());
        assertEquals("周杰伦", action.arguments().get("keyword"));
        assertEquals(1, action.arguments().get("limit"));
    }

    @Test
    void clampsSearchLimitToRequestedSongCount() {
        AgentStepAction action = planner.parseAction("""
                {
                  "action": "tool_call",
                  "toolName": "search_songs",
                  "arguments": {"keyword": "李荣浩", "limit": 5},
                  "publicActivity": "搜索李荣浩",
                  "confidence": 0.91,
                  "reason": "用户只要求一首"
                }
                """, new AgentCapabilityRegistry().readManifest(), 1).orElseThrow();

        assertEquals("search_songs", action.toolName());
        assertEquals(1, action.arguments().get("limit"));
    }

    @Test
    void parsesRecommendToolCallWithoutClampingToRequestedSongCount() {
        AgentStepAction action = planner.parseAction("""
                {
                  "action": "tool_call",
                  "toolName": "recommend_songs",
                  "arguments": {"request": "推荐适合深夜学习和写代码听的歌", "count": 8},
                  "publicActivity": "生成推荐候选并精确匹配歌曲",
                  "confidence": 0.91,
                  "reason": "开放场景推荐应先生成具体候选"
                }
                """, new AgentCapabilityRegistry().readManifest(), 5).orElseThrow();

        assertEquals(AgentStepActionType.TOOL_CALL, action.action());
        assertEquals(AgentCapabilityRegistry.RECOMMEND_SONGS, action.toolName());
        assertEquals("推荐适合深夜学习和写代码听的歌", action.arguments().get("request"));
        assertEquals(8, action.arguments().get("count"));
    }

    @Test
    void parsesCommentToolCallWithDefaultLimit() {
        AgentStepAction action = planner.parseAction("""
                {
                  "action": "tool_call",
                  "toolName": "get_hot_comments",
                  "arguments": {"songId": "qqmusic:1"},
                  "publicActivity": "读取热门评论",
                  "confidence": 0.93,
                  "reason": "上一条 observation 已提供 songId"
                }
                """).orElseThrow();

        assertEquals(AgentStepActionType.TOOL_CALL, action.action());
        assertEquals("get_hot_comments", action.toolName());
        assertEquals("qqmusic:1", action.arguments().get("songId"));
        assertEquals(10, action.arguments().get("limit"));
    }

    @Test
    void parsesBatchCommentToolCallWithSongIds() {
        AgentStepAction action = planner.parseAction("""
                {
                  "action": "tool_call",
                  "toolName": "get_hot_comments",
                  "arguments": {"songIds": ["qqmusic:1", "qqmusic:2"], "limit": 1},
                  "publicActivity": "读取多首歌的热门评论",
                  "confidence": 0.93,
                  "reason": "用户要多首歌的热评"
                }
                """).orElseThrow();

        assertEquals("get_hot_comments", action.toolName());
        assertEquals(java.util.List.of("qqmusic:1", "qqmusic:2"), action.arguments().get("songIds"));
        assertEquals(1, action.arguments().get("limit"));
    }

    @Test
    void parsesToolCallUsingCapabilityProvidedArgumentRules() {
        AgentCapabilityRegistry registry = new AgentCapabilityRegistry(List.of(new DemoCapabilityHandler()));
        AgentStepPlanner customPlanner = new AgentStepPlanner(null, new ObjectMapper(), registry);

        AgentStepAction action = customPlanner.parseAction("""
                {
                  "action": "tool_call",
                  "toolName": "demo_capability",
                  "arguments": {"raw": "  normalized value  "},
                  "publicActivity": "执行测试能力",
                  "confidence": 0.91,
                  "reason": "测试 capability 参数规则"
                }
                """, registry.readManifest()).orElseThrow();

        assertEquals(AgentStepActionType.TOOL_CALL, action.action());
        assertEquals("demo_capability", action.toolName());
        assertEquals("normalized value", action.arguments().get("value"));
    }

    @Test
    void parsesFinalAnswerAction() {
        AgentStepAction action = planner.parseAction("""
                {
                  "action": "final_answer",
                  "toolName": "",
                  "arguments": {},
                  "publicActivity": "整理回答",
                  "confidence": 0.9,
                  "reason": "信息足够"
                }
                """).orElseThrow();

        assertEquals(AgentStepActionType.FINAL_ANSWER, action.action());
        assertTrue(action.arguments().isEmpty());
    }

    @Test
    void memoryContextPreviewExposesCurrentPlaybackToPlannerPrompt() {
        AgentLoopState state = new AgentLoopState(
                "run-1",
                "local",
                "正在播放的这首看评论区怎么说",
                List.of(),
                AgentTaskMemory.empty("local"),
                List.of(),
                0,
                null,
                0,
                null,
                new MemoryContextPackage(
                        "[动态记忆上下文]\n[当前播放状态]\n- 当前播放: 给你宇宙 id=qqmusic:current",
                        List.of(new MemoryEvidence(MemoryType.CURRENT_STATE, "qqmusic:current", "当前播放: 给你宇宙 id=qqmusic:current", 0.9, 0.85, "test", Instant.EPOCH)),
                        32
                )
        );

        String preview = planner.memoryContextPreview(state);

        assertTrue(preview.contains("当前播放状态"));
        assertTrue(preview.contains("qqmusic:current"));
    }

    @Test
    void dropsUnknownTool() {
        assertTrue(planner.parseAction("""
                {
                  "action": "tool_call",
                  "toolName": "delete_file",
                  "arguments": {"path": "/tmp/x"},
                  "confidence": 0.9
                }
                """).isEmpty());
    }

    @Test
    void doesNotFallbackToReadManifestWhenExplicitManifestIsEmpty() {
        assertTrue(planner.parseAction("""
                {
                  "action": "tool_call",
                  "toolName": "search_songs",
                  "arguments": {"keyword": "周杰伦", "limit": 1},
                  "confidence": 0.9
                }
                """, AgentCapabilityManifest.empty()).isEmpty());
    }

    @Test
    void parsesLocalPlaylistWriteWhenManifestAllowsIt() {
        AgentStepAction action = planner.parseAction("""
                {
                  "action": "tool_call",
                  "toolName": "add_song_to_musio_playlist",
                  "arguments": {"playlistId": "default", "songIndex": 1},
                  "publicActivity": "收藏第一首歌",
                  "confidence": 0.92,
                  "reason": "用户明确要求收藏"
                }
                """, new AgentCapabilityRegistry().manifest(true)).orElseThrow();

        assertEquals(AgentStepActionType.TOOL_CALL, action.action());
        assertEquals("add_song_to_musio_playlist", action.toolName());
        assertEquals(1, action.arguments().get("songIndex"));
    }

    @Test
    void parsesBatchLocalPlaylistWriteWhenManifestAllowsIt() {
        AgentStepAction action = planner.parseAction("""
                {
                  "action": "tool_call",
                  "toolName": "add_song_to_musio_playlist",
                  "arguments": {"playlistId": "default", "songIds": ["qqmusic:1", "qqmusic:2"]},
                  "publicActivity": "收藏这些歌",
                  "confidence": 0.92,
                  "reason": "用户明确要求批量收藏"
                }
                """, new AgentCapabilityRegistry().manifest(true)).orElseThrow();

        assertEquals(AgentStepActionType.TOOL_CALL, action.action());
        assertEquals("add_song_to_musio_playlist", action.toolName());
        assertEquals(java.util.List.of("qqmusic:1", "qqmusic:2"), action.arguments().get("songIds"));
        assertEquals("qqmusic:1", action.arguments().get("songId"));
    }

    @Test
    void dropsLocalPlaylistWriteWhenManifestDoesNotAllowIt() {
        assertTrue(planner.parseAction("""
                {
                  "action": "tool_call",
                  "toolName": "add_song_to_musio_playlist",
                  "arguments": {"songIndex": 1},
                  "confidence": 0.92
                }
                """).isEmpty());
    }

    private static class DemoCapabilityHandler implements AgentCapabilityHandler {
        private static final AgentCapability CAPABILITY = new AgentCapability(
                "demo_capability",
                CapabilityEffect.READ,
                "测试 capability handler 参数规范化。",
                "{\"raw\": string}",
                Set.of("value")
        );

        @Override
        public List<AgentCapability> capabilities() {
            return List.of(CAPABILITY);
        }

        @Override
        public boolean supports(String capabilityName) {
            return CAPABILITY.name().equals(capabilityName);
        }

        @Override
        public Map<String, Object> normalizeArguments(
                String capabilityName,
                Map<String, Object> arguments,
                AgentCapabilityArgumentContext context
        ) {
            Object raw = arguments == null ? null : arguments.get("raw");
            return raw instanceof String value ? Map.of("value", value.strip()) : Map.of();
        }

        @Override
        public AgentCapabilityValidationResult validateArguments(
                String capabilityName,
                Map<String, Object> arguments,
                AgentCapabilityArgumentContext context
        ) {
            Object value = arguments == null ? null : arguments.get("value");
            return value instanceof String text && !text.isBlank()
                    ? AgentCapabilityValidationResult.accepted()
                    : AgentCapabilityValidationResult.rejected("missing_required_argument");
        }

        @Override
        public Optional<String> execute(AgentLoopState state, String capabilityName, Map<String, Object> arguments) {
            return Optional.empty();
        }
    }
}
