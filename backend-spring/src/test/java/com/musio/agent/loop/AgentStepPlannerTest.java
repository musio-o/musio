package com.musio.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
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
}
