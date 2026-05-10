package com.musio.agent;

import com.musio.agent.capability.AgentCapabilityManifest;
import com.musio.agent.capability.AgentCapabilityRegistry;
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

    @Test
    void initialLocalPlaylistWriteUsesReadOnlyLoopManifest() {
        AgentCapabilityManifest manifest = new AgentCapabilityRegistry().manifest(true);

        AgentCapabilityManifest readOnly = AgentRuntime.readOnlyManifest(manifest);

        assertFalse(readOnly.allows(AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST));
        assertTrue(readOnly.allows(AgentCapabilityRegistry.RECOMMEND_SONGS));
    }

    @Test
    void initialLocalPlaylistWriteRequirementIsDeferredUntilConfirmation() {
        AgentGoal goal = new AgentGoal(
                "给我推荐一首许嵩的歌曲，并将其加入歌单",
                "给我推荐一首许嵩的歌曲，并将其加入歌单",
                "recommend",
                "new_task",
                true,
                true,
                true,
                false,
                1,
                List.of(),
                List.of(AgentRequiredOutcome.RECOMMENDATION, AgentRequiredOutcome.LOCAL_PLAYLIST_WRITE)
        );

        AgentGoal deferred = AgentRuntime.withoutLocalPlaylistWriteRequirement(goal);

        assertFalse(deferred.localWriteIntent());
        assertEquals(List.of(AgentRequiredOutcome.RECOMMENDATION), deferred.requiredOutcomes());
    }

    @Test
    void deferringLocalPlaylistWriteKeepsLyricsRequirement() {
        AgentGoal goal = new AgentGoal(
                "帮我推荐一首周杰伦的歌，将其加入歌单，最后再给我分享你觉得值得分享的歌词",
                "帮我推荐一首周杰伦的歌，将其加入歌单，最后再给我分享你觉得值得分享的歌词",
                "recommend",
                "new_task",
                true,
                true,
                true,
                false,
                1,
                List.of(),
                List.of(
                        AgentRequiredOutcome.RECOMMENDATION,
                        AgentRequiredOutcome.LYRICS,
                        AgentRequiredOutcome.LOCAL_PLAYLIST_WRITE
                )
        );

        AgentGoal deferred = AgentRuntime.withoutLocalPlaylistWriteRequirement(goal);

        assertFalse(deferred.localWriteIntent());
        assertEquals(
                List.of(AgentRequiredOutcome.RECOMMENDATION, AgentRequiredOutcome.LYRICS),
                deferred.requiredOutcomes()
        );
    }
}
