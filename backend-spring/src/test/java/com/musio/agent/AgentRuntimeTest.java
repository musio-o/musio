package com.musio.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeTest {
    @Test
    void localWriteOnlyShortcutDoesNotApplyToMixedReadAndWritePlan() {
        AgentTurnPlan addOnly = new AgentTurnPlan(
                TurnDisposition.USE_TOOLS,
                "playlist",
                "refer_previous_song",
                "加入 Musio 歌单",
                AgentTurnMemoryUse.none("不需要"),
                List.of(new AgentToolCall("add_song_to_musio_playlist", Map.of(
                        "playlistId", "default",
                        "songId", "qqmusic:1"
                ))),
                0.9,
                ""
        );
        AgentTurnPlan addWithComments = new AgentTurnPlan(
                TurnDisposition.USE_TOOLS,
                "playlist",
                "refer_previous_song",
                "分享评论并加入 Musio 歌单",
                AgentTurnMemoryUse.none("不需要"),
                List.of(
                        new AgentToolCall("get_hot_comments", Map.of("songId", "qqmusic:1", "limit", 10)),
                        new AgentToolCall("add_song_to_musio_playlist", Map.of(
                                "playlistId", "default",
                                "songId", "qqmusic:1"
                        ))
                ),
                0.9,
                ""
        );

        assertTrue(addOnly.hasOnlyLocalWriteTools());
        assertTrue(addOnly.hasLocalWriteTools());
        assertFalse(addWithComments.hasOnlyLocalWriteTools());
        assertTrue(addWithComments.hasLocalWriteTools());
        assertFalse(addWithComments.readOnlyLoopToolCalls().isEmpty());
    }
}
