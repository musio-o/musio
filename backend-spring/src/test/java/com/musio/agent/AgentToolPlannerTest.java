package com.musio.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolPlannerTest {
    private final AgentToolPlanner planner = new AgentToolPlanner(null, new ObjectMapper());

    @Test
    void parsesPlannedSongDetailAndCommentsCalls() {
        AgentToolPlan plan = planner.parsePlan("""
                {
                  "toolCalls": [
                    {"toolName": "get_song_detail", "arguments": {"songId": "qqmusic:0039MnYb0qxYhV"}},
                    {"toolName": "get_hot_comments", "arguments": {"songId": "qqmusic:0039MnYb0qxYhV", "limit": 10}}
                  ],
                  "confidence": 0.92
                }
                """).orElseThrow();

        assertEquals(2, plan.toolCalls().size());
        assertEquals("get_song_detail", plan.toolCalls().getFirst().toolName());
        assertEquals("qqmusic:0039MnYb0qxYhV", plan.toolCalls().getFirst().arguments().get("songId"));
        assertEquals("get_hot_comments", plan.toolCalls().get(1).toolName());
        assertEquals(10, plan.toolCalls().get(1).arguments().get("limit"));
    }

    @Test
    void parsesSearchCallWithExcludedTitles() {
        AgentToolPlan plan = planner.parsePlan("""
                {
                  "toolCalls": [
                    {"toolName": "search_songs", "arguments": {"keyword": "周杰伦", "limit": 1, "excludedTitles": ["晴天"]}}
                  ],
                  "confidence": 0.9
                }
                """).orElseThrow();

        assertEquals(1, plan.toolCalls().size());
        assertEquals("search_songs", plan.toolCalls().getFirst().toolName());
        assertEquals("周杰伦", plan.toolCalls().getFirst().arguments().get("keyword"));
        assertEquals(java.util.List.of("晴天"), plan.toolCalls().getFirst().arguments().get("excludedTitles"));
    }

    @Test
    void parsesMultipleRecommendationSearchCalls() {
        AgentToolPlan plan = planner.parsePlan("""
                {
                  "toolCalls": [
                    {"toolName": "search_songs", "arguments": {"keyword": "黑夜问白天 林俊杰", "limit": 1}},
                    {"toolName": "search_songs", "arguments": {"keyword": "枫 周杰伦", "limit": 1}},
                    {"toolName": "search_songs", "arguments": {"keyword": "不要说话 陈奕迅", "limit": 1}},
                    {"toolName": "search_songs", "arguments": {"keyword": "寂寞的季节 陶喆", "limit": 1}},
                    {"toolName": "search_songs", "arguments": {"keyword": "安静 周杰伦", "limit": 1}}
                  ],
                  "confidence": 0.91
                }
                """).orElseThrow();

        assertEquals(5, plan.toolCalls().size());
        assertEquals("黑夜问白天 林俊杰", plan.toolCalls().getFirst().arguments().get("keyword"));
        assertEquals("安静 周杰伦", plan.toolCalls().get(4).arguments().get("keyword"));
    }

    @Test
    void preservesSceneKeywordSearchCalls() {
        AgentToolPlan plan = planner.parsePlan("""
                {
                  "toolCalls": [
                    {"toolName": "search_songs", "arguments": {"keyword": "深夜写代码", "limit": 5}}
                  ],
                  "confidence": 0.91
                }
                """).orElseThrow();

        assertEquals(1, plan.toolCalls().size());
        assertEquals("深夜写代码", plan.toolCalls().getFirst().arguments().get("keyword"));
        assertEquals(5, plan.toolCalls().getFirst().arguments().get("limit"));
    }

    @Test
    void parsesAllRegisteredReadOnlyMusicTools() {
        AgentToolPlan plan = planner.parsePlan("""
                {
                  "toolCalls": [
                    {"toolName": "search_songs", "arguments": {"keyword": "周杰伦", "limit": 1}},
                    {"toolName": "get_user_music_profile", "arguments": {}},
                    {"toolName": "get_song_detail", "arguments": {"songId": "qqmusic:1"}},
                    {"toolName": "get_lyrics", "arguments": {"songId": "qqmusic:1"}},
                    {"toolName": "get_hot_comments", "arguments": {"songId": "qqmusic:1", "limit": 3}},
                    {"toolName": "get_user_playlists", "arguments": {"limit": 5}},
                    {"toolName": "get_playlist_songs", "arguments": {"playlistId": "qqmusic:playlist:1", "limit": 10}}
                  ],
                  "confidence": 0.93
                }
                """).orElseThrow();

        assertEquals(java.util.List.of(
                "search_songs",
                "get_user_music_profile",
                "get_song_detail",
                "get_lyrics",
                "get_hot_comments",
                "get_user_playlists",
                "get_playlist_songs"
        ), plan.toolCalls().stream().map(AgentToolCall::toolName).toList());
    }

    @Test
    void fallsBackToSearchSongsForSearchTaskWhenModelPlannerUnavailable() {
        AgentTaskContext context = AgentTaskContext.agent(
                "给我推荐李荣浩的不遗憾",
                "搜索 李荣浩《不遗憾》",
                "李荣浩 不遗憾",
                0,
                false,
                java.util.List.of(),
                0.92,
                "test",
                "search",
                "",
                "不遗憾",
                "new_task",
                AgentTaskMemoryAccess.none("测试")
        );

        AgentToolPlan plan = planner.plan(null, context, "无");

        assertEquals(1, plan.toolCalls().size());
        assertEquals("search_songs", plan.toolCalls().getFirst().toolName());
        assertEquals("李荣浩 不遗憾", plan.toolCalls().getFirst().arguments().get("keyword"));
        assertEquals(5, plan.toolCalls().getFirst().arguments().get("limit"));
    }

    @Test
    void doesNotFallbackToSearchSongsForPlaybackTaskWithSearchKeyword() {
        AgentTaskContext context = AgentTaskContext.agent(
                "放几首周杰伦的",
                "放几首周杰伦的",
                "周杰伦",
                0,
                false,
                java.util.List.of(),
                0.90,
                "test",
                "playback",
                "",
                "",
                "new_task",
                AgentTaskMemoryAccess.none("测试")
        );

        AgentToolPlan plan = planner.plan(null, context, "无");

        assertTrue(plan.toolCalls().isEmpty());
    }

    @Test
    void dropsSongToolsWithoutSongId() {
        AgentToolPlan plan = planner.parsePlan("""
                {"toolCalls":[{"toolName":"get_hot_comments","arguments":{"limit":10}}],"confidence":0.9}
                """).orElseThrow();

        assertTrue(plan.toolCalls().isEmpty());
    }

    @Test
    void rejectsLowConfidencePlan() {
        assertTrue(planner.parsePlan("""
                {"toolCalls":[{"toolName":"get_user_playlists","arguments":{"limit":20}}],"confidence":0.2}
                """).isEmpty());
    }
}
