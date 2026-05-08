package com.musio.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.capability.AgentCapabilityRegistry;
import org.junit.jupiter.api.Test;

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
}
