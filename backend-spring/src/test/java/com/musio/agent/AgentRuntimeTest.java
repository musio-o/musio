package com.musio.agent;

import com.musio.agent.capability.AgentCapabilityRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeTest {
    private final AgentCapabilityRegistry capabilityRegistry = new AgentCapabilityRegistry();

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

    @Test
    void confirmationSaveBuildsDeterministicLocalPlaylistPlan() {
        AgentTurnPlan plan = AgentRuntime.directLocalPlaylistAddPlan("确认收藏", AgentTurnPlan.respondOnly("确认收藏", 0.8, ""), List.of());

        assertNotNull(plan);
        assertTrue(plan.hasOnlyLocalWriteTools());
        assertEquals("default", plan.toolCalls().getFirst().arguments().get("playlistId"));
        assertEquals(1, plan.toolCalls().getFirst().arguments().get("songIndex"));
    }

    @Test
    void plainConfirmationRequiresRecentPlaylistContext() {
        assertNull(AgentRuntime.directLocalPlaylistAddPlan("确认", AgentTurnPlan.respondOnly("确认", 0.8, ""), List.of()));

        List<ConversationHistoryMessage> history = List.of(new ConversationHistoryMessage(
                "assistant",
                "如果你想收藏这首歌，我可以加入 Musio 歌单。",
                Instant.now(),
                List.of()
        ));
        assertNotNull(AgentRuntime.directLocalPlaylistAddPlan("确认", AgentTurnPlan.respondOnly("确认", 0.8, ""), history));
    }

    @Test
    void localWriteOnlyPlanDoesNotBypassReadIntentFromUserText() {
        AgentTurnPlan addOnly = new AgentTurnPlan(
                TurnDisposition.USE_TOOLS,
                "playlist",
                "refer_previous_song",
                "推荐一首李荣浩的歌曲，获取热评并加入歌单",
                AgentTurnMemoryUse.none("不需要"),
                List.of(new AgentToolCall("add_song_to_musio_playlist", Map.of(
                        "playlistId", "default",
                        "songIndex", 1
                ))),
                0.9,
                ""
        );

        assertNull(AgentRuntime.directLocalPlaylistAddPlan(
                "帮我推荐一首李荣浩的歌曲，并获取最热门的评论，最后将这首歌加入歌单",
                addOnly,
                List.of()
        ));
    }

    @Test
    void explicitNewSaveRequestIsNotTreatedAsConfirmationFallback() {
        assertNull(AgentRuntime.directLocalPlaylistAddPlan(
                "帮我收藏李荣浩的不遗憾",
                AgentTurnPlan.respondOnly("帮我收藏李荣浩的不遗憾", 0.8, ""),
                List.of()
        ));
    }

    @Test
    void deterministicFallbackHandlesCompositeMusicRequestWhenPlannerRespondsOnly() {
        assertTrue(AgentRuntime.shouldUseDeterministicRecommendCommentPlaylistFallback(
                "帮我推荐一首华晨宇的歌，并获取最热门的评论，最后将这首歌加入歌单",
                AgentTurnPlan.respondOnly("帮我推荐一首华晨宇的歌，并获取最热门的评论，最后将这首歌加入歌单", 0.0, "invalid_turn_plan"),
                capabilityRegistry.manifest(true),
                1
        ));
    }

    @Test
    void deterministicFallbackRequiresPlaylistIntent() {
        assertFalse(AgentRuntime.shouldUseDeterministicRecommendCommentPlaylistFallback(
                "帮我推荐一首华晨宇的歌，并获取最热门的评论",
                AgentTurnPlan.respondOnly("帮我推荐一首华晨宇的歌，并获取最热门的评论", 0.0, "invalid_turn_plan"),
                capabilityRegistry.manifest(false),
                1
        ));
    }
}
