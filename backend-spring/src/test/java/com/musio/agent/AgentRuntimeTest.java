package com.musio.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeTest {
    @Test
    void distinguishesLocalWriteOnlyAndMixedReadWritePlans() {
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

    @Test
    void explicitLocalPlaylistConfirmationIsRecognized() {
        assertTrue(AgentRuntime.isLocalPlaylistConfirmationIntent("确认收藏", List.of()));
    }

    @Test
    void plainConfirmationRequiresRecentPlaylistContext() {
        assertFalse(AgentRuntime.isLocalPlaylistConfirmationIntent("确认", List.of()));

        List<ConversationHistoryMessage> history = List.of(new ConversationHistoryMessage(
                "assistant",
                "如果你想收藏这首歌，我可以加入 Musio 歌单。",
                Instant.now(),
                List.of()
        ));
        assertTrue(AgentRuntime.isLocalPlaylistConfirmationIntent("确认", history));
    }

    @Test
    void explicitNewSaveRequestIsNotTreatedAsConfirmationFallback() {
        assertFalse(AgentRuntime.isLocalPlaylistConfirmationIntent("帮我收藏李荣浩的不遗憾", List.of()));
    }
}
