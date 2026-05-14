package com.musio.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentGoalNormalizerTest {
    @Test
    void derivesRecommendationOutcomeFromTaskTypeWhenPlannerOmittedOutcomes() {
        AgentTurnPlan turnPlan = new AgentTurnPlan(
                TurnDisposition.USE_TOOLS,
                "recommend",
                "new_task",
                "推荐适合专注的音乐",
                AgentTurnMemoryUse.none("新任务"),
                List.of(),
                List.of(),
                0.9,
                ""
        );

        assertEquals(List.of(AgentRequiredOutcome.RECOMMENDATION), AgentGoalNormalizer.requiredOutcomes(turnPlan, null));
    }

    @Test
    void addsTaskTypeOutcomeBeforePlannerDeclaredCompositeOutcomes() {
        AgentTurnPlan turnPlan = new AgentTurnPlan(
                TurnDisposition.USE_TOOLS,
                "recommend",
                "new_task",
                "推荐一首歌并获取热评",
                AgentTurnMemoryUse.none("新任务"),
                List.of(),
                List.of(AgentRequiredOutcome.COMMENTS),
                0.9,
                ""
        );

        assertEquals(
                List.of(AgentRequiredOutcome.RECOMMENDATION, AgentRequiredOutcome.COMMENTS),
                AgentGoalNormalizer.requiredOutcomes(turnPlan, null)
        );
    }

    @Test
    void derivesWriteOutcomeFromToolHint() {
        AgentTurnPlan turnPlan = new AgentTurnPlan(
                TurnDisposition.USE_TOOLS,
                "playlist",
                "refer_previous_song",
                "收藏第一首",
                AgentTurnMemoryUse.none("收藏"),
                List.of(new AgentToolCall("add_song_to_musio_playlist", Map.of("songIndex", 1))),
                List.of(),
                0.9,
                ""
        );

        assertEquals(
                List.of(AgentRequiredOutcome.PLAYLIST, AgentRequiredOutcome.LOCAL_PLAYLIST_WRITE),
                AgentGoalNormalizer.requiredOutcomes(turnPlan, null)
        );
    }

    @Test
    void profileToolHintDoesNotBecomeCompletionRequirementForCommentsTask() {
        AgentTurnPlan turnPlan = new AgentTurnPlan(
                TurnDisposition.USE_TOOLS,
                "comments",
                "new_task",
                "获取正在播放的歌曲的热门评论",
                AgentTurnMemoryUse.none("新任务"),
                List.of(new AgentToolCall("get_user_music_profile", Map.of())),
                List.of(AgentRequiredOutcome.COMMENTS),
                0.9,
                ""
        );

        assertEquals(
                List.of(AgentRequiredOutcome.COMMENTS),
                AgentGoalNormalizer.requiredOutcomes(turnPlan, null, "正在播放的这首看评论区怎么说")
        );
    }

    @Test
    void readToolHintsDoNotExpandHardCompletionRequirements() {
        AgentTurnPlan turnPlan = new AgentTurnPlan(
                TurnDisposition.USE_TOOLS,
                "comments",
                "new_task",
                "获取正在播放的歌曲的热门评论",
                AgentTurnMemoryUse.none("新任务"),
                List.of(
                        new AgentToolCall("search_songs", Map.of("keyword", "后弦", "limit", 1)),
                        new AgentToolCall("get_lyrics", Map.of("songId", "qqmusic:1")),
                        new AgentToolCall("get_song_detail", Map.of("songId", "qqmusic:1")),
                        new AgentToolCall("get_playlist_songs", Map.of("playlistId", "qqmusic:playlist:1"))
                ),
                List.of(AgentRequiredOutcome.COMMENTS),
                0.9,
                ""
        );

        assertEquals(
                List.of(AgentRequiredOutcome.COMMENTS),
                AgentGoalNormalizer.requiredOutcomes(turnPlan, null, "正在播放的这首看评论区怎么说")
        );
    }

    @Test
    void plannerDeclaredProfileOutcomeIsStillPreserved() {
        AgentTurnPlan turnPlan = new AgentTurnPlan(
                TurnDisposition.USE_TOOLS,
                "comments",
                "new_task",
                "获取评论并结合音乐画像",
                AgentTurnMemoryUse.none("新任务"),
                List.of(),
                List.of(AgentRequiredOutcome.COMMENTS, AgentRequiredOutcome.PROFILE),
                0.9,
                ""
        );

        assertEquals(
                List.of(AgentRequiredOutcome.COMMENTS, AgentRequiredOutcome.PROFILE),
                AgentGoalNormalizer.requiredOutcomes(turnPlan, null, "正在播放的这首看评论区怎么说")
        );
    }

    @Test
    void outcomeSourceSummaryShowsHardOutcomeBoundary() {
        AgentTurnPlan turnPlan = new AgentTurnPlan(
                TurnDisposition.USE_TOOLS,
                "comments",
                "new_task",
                "获取正在播放的歌曲的热门评论",
                AgentTurnMemoryUse.none("新任务"),
                List.of(new AgentToolCall("get_user_music_profile", Map.of())),
                List.of(),
                0.9,
                ""
        );

        assertEquals(
                "[COMMENTS=turn_plan.taskType]",
                AgentGoalNormalizer.requiredOutcomeSourceSummary(turnPlan, null, "正在播放的这首看评论区怎么说")
        );
    }

    @Test
    void repairsExplicitCompositeRecommendationLyricsAndLocalWriteIntentFromUserMessage() {
        AgentTurnPlan turnPlan = new AgentTurnPlan(
                TurnDisposition.USE_TOOLS,
                "playlist",
                "follow_up",
                "加入 Musio 歌单",
                AgentTurnMemoryUse.none("误判成上一轮收藏"),
                List.of(new AgentToolCall("add_song_to_musio_playlist", Map.of("songIndex", 1))),
                List.of(AgentRequiredOutcome.LOCAL_PLAYLIST_WRITE),
                0.9,
                ""
        );

        String userMessage = "帮我推荐一首周杰伦的歌，将其加入歌单，最后再给我分享你觉得值得分享的歌词";

        assertEquals(
                List.of(AgentRequiredOutcome.RECOMMENDATION, AgentRequiredOutcome.LYRICS, AgentRequiredOutcome.LOCAL_PLAYLIST_WRITE),
                AgentGoalNormalizer.requiredOutcomes(turnPlan, null, userMessage)
        );

        var slots = AgentGoalNormalizer.recommendationSlots(turnPlan, null, userMessage);
        assertEquals(1, slots.size());
        assertEquals("周杰伦", slots.getFirst().target());
        assertEquals(1, slots.getFirst().count());
    }

    @Test
    void plainLocalPlaylistConfirmationDoesNotCreateRecommendationOrLyricsOutcome() {
        AgentTurnPlan turnPlan = AgentTurnPlan.respondOnly("确认收藏", 0.9, "");

        assertEquals(
                List.of(),
                AgentGoalNormalizer.requiredOutcomes(turnPlan, null, "确认收藏")
        );
    }

    @Test
    void explicitCompositeRecommendationBecomesNewRecommendationGoal() {
        AgentTurnPlan turnPlan = new AgentTurnPlan(
                TurnDisposition.USE_TOOLS,
                "playlist",
                "follow_up",
                "加入 Musio 歌单",
                AgentTurnMemoryUse.none("误判成上一轮收藏"),
                List.of(new AgentToolCall("add_song_to_musio_playlist", Map.of("songIndex", 1))),
                List.of(AgentRequiredOutcome.LOCAL_PLAYLIST_WRITE),
                0.9,
                ""
        );
        String userMessage = "帮我推荐一首周杰伦的歌，将其加入歌单，最后再给我分享你觉得值得分享的歌词";
        AgentTaskContext taskContext = turnPlan.toLegacyTaskContext(userMessage);
        var slots = AgentGoalNormalizer.recommendationSlots(turnPlan, taskContext, userMessage);

        AgentGoal goal = AgentGoal.from(userMessage, turnPlan, taskContext, 1, slots);

        assertEquals("recommend", goal.taskType());
        assertEquals("new_task", goal.contextMode());
        assertEquals(List.of(AgentRequiredOutcome.RECOMMENDATION, AgentRequiredOutcome.LYRICS, AgentRequiredOutcome.LOCAL_PLAYLIST_WRITE), goal.requiredOutcomes());
        assertTrue(goal.musicTask());
        assertTrue(goal.localWriteIntent());
    }

    @Test
    void derivesRecommendationSlotsFromRepeatedArtistCounts() {
        AgentTurnPlan turnPlan = new AgentTurnPlan(
                TurnDisposition.USE_TOOLS,
                "recommend",
                "new_task",
                "推荐一首许嵩的歌，再推荐一首许嵩的歌，一首后弦的歌，并获取热评、歌词、加入歌单",
                AgentTurnMemoryUse.none("新任务"),
                List.of(),
                List.of(AgentRequiredOutcome.RECOMMENDATION, AgentRequiredOutcome.COMMENTS, AgentRequiredOutcome.LYRICS, AgentRequiredOutcome.LOCAL_PLAYLIST_WRITE),
                0.9,
                ""
        );

        var slots = AgentGoalNormalizer.recommendationSlots(turnPlan, null, turnPlan.effectiveRequest());

        assertEquals(2, slots.size());
        assertEquals("许嵩", slots.get(0).target());
        assertEquals(2, slots.get(0).count());
        assertEquals("后弦", slots.get(1).target());
        assertEquals(1, slots.get(1).count());
    }
}
