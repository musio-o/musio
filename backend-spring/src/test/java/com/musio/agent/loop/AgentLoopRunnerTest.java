package com.musio.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.AgentToolExecutor;
import com.musio.agent.trace.AgentTracePublisher;
import com.musio.config.MusioConfig;
import com.musio.events.AgentEventBus;
import com.musio.model.AgentTaskMemory;
import com.musio.model.Comment;
import com.musio.model.LoginStartResult;
import com.musio.model.LoginStatus;
import com.musio.model.Lyrics;
import com.musio.model.Playlist;
import com.musio.model.ProviderType;
import com.musio.model.Song;
import com.musio.model.SongDetail;
import com.musio.model.SongUrl;
import com.musio.model.UserProfile;
import com.musio.providers.MusicProvider;
import com.musio.providers.MusicProviderGateway;
import com.musio.tools.MusicReadTools;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopRunnerTest {
    @Test
    void stopsWhenPlannerReturnsFinalAnswer() {
        AgentLoopRunner runner = new AgentLoopRunner(
                new FinalAnswerPlanner(),
                null,
                new AgentObservationBuilder(new ObjectMapper()),
                new ObjectMapper()
        );
        AgentLoopState state = new AgentLoopState(
                "run-1",
                "local",
                "谢谢",
                List.of(),
                AgentTaskMemory.empty("local"),
                List.of(),
                0
        );

        AgentLoopEvidence evidence = runner.run(null, state);

        assertFalse(evidence.hasObservations());
    }

    @Test
    void executesSearchThenCommentsUsingObservedSongId() {
        AgentLoopRunner runner = new AgentLoopRunner(
                new SequencedPlanner(List.of(
                        new AgentStepAction(AgentStepActionType.TOOL_CALL, "search_songs", Map.of("keyword", "周杰伦", "limit", 1), "搜索周杰伦", 0.9, "先找歌"),
                        new AgentStepAction(AgentStepActionType.TOOL_CALL, "get_hot_comments", Map.of("songId", "qqmusic:0", "limit", 1), "读评论", 0.9, "使用 observation songId"),
                        AgentStepAction.finalAnswer("信息足够", 0.9)
                )),
                toolExecutor(),
                new AgentObservationBuilder(new ObjectMapper()),
                new ObjectMapper()
        );

        AgentLoopEvidence evidence = runner.run(null, new AgentLoopState(
                "run-1",
                "local",
                "搜索一首周杰伦的歌，然后分享感人评论",
                List.of(),
                AgentTaskMemory.empty("local"),
                List.of(),
                0
        ));

        assertEquals(2, evidence.observations().size());
        assertEquals("search_songs", evidence.observations().getFirst().toolName());
        assertEquals("get_hot_comments", evidence.observations().get(1).toolName());
        assertTrue(evidence.observations().get(1).resultJson().contains("整个故事都在下雨"));
    }

    @Test
    void rejectsCommentToolWithoutKnownSongIdThenAllowsSearch() {
        AgentLoopRunner runner = new AgentLoopRunner(
                new SequencedPlanner(List.of(
                        new AgentStepAction(AgentStepActionType.TOOL_CALL, "get_hot_comments", Map.of("songId", "qqmusic:missing", "limit", 1), "读评论", 0.9, "没有来源"),
                        new AgentStepAction(AgentStepActionType.TOOL_CALL, "search_songs", Map.of("keyword", "周杰伦", "limit", 1), "搜索周杰伦", 0.9, "改为先找歌"),
                        AgentStepAction.finalAnswer("结束", 0.9)
                )),
                toolExecutor(),
                new AgentObservationBuilder(new ObjectMapper()),
                new ObjectMapper()
        );

        AgentLoopEvidence evidence = runner.run(null, new AgentLoopState(
                "run-1",
                "local",
                "分享一条评论",
                List.of(),
                AgentTaskMemory.empty("local"),
                List.of(),
                0
        ));

        assertEquals(2, evidence.observations().size());
        assertEquals(AgentObservationStatus.SKIPPED, evidence.observations().getFirst().status());
        assertEquals("search_songs", evidence.observations().get(1).toolName());
    }

    @Test
    void rejectsDuplicateToolCall() {
        AgentStepAction search = new AgentStepAction(
                AgentStepActionType.TOOL_CALL,
                "search_songs",
                Map.of("keyword", "周杰伦", "limit", 1),
                "搜索周杰伦",
                0.9,
                "先找歌"
        );
        AgentLoopRunner runner = new AgentLoopRunner(
                new SequencedPlanner(List.of(search, search, AgentStepAction.finalAnswer("结束", 0.9))),
                toolExecutor(),
                new AgentObservationBuilder(new ObjectMapper()),
                new ObjectMapper()
        );

        AgentLoopEvidence evidence = runner.run(null, new AgentLoopState(
                "run-1",
                "local",
                "搜索周杰伦",
                List.of(),
                AgentTaskMemory.empty("local"),
                List.of(),
                0
        ));

        assertEquals(2, evidence.observations().size());
        assertEquals(AgentObservationStatus.SUCCESS, evidence.observations().getFirst().status());
        assertEquals(AgentObservationStatus.SKIPPED, evidence.observations().get(1).status());
        assertTrue(evidence.observations().get(1).resultJson().contains("duplicate_tool_call"));
    }

    @Test
    void executesInitialTurnActionBeforeStepPlanner() {
        AgentStepAction initialSearch = new AgentStepAction(
                AgentStepActionType.TOOL_CALL,
                "search_songs",
                Map.of("keyword", "周杰伦", "limit", 1),
                "执行已规划音乐能力",
                0.9,
                "turn_planner_seed"
        );
        AgentLoopRunner runner = new AgentLoopRunner(
                new SequencedPlanner(List.of(
                        new AgentStepAction(AgentStepActionType.TOOL_CALL, "get_hot_comments", Map.of("songId", "qqmusic:0", "limit", 1), "读评论", 0.9, "使用 observation songId"),
                        AgentStepAction.finalAnswer("信息足够", 0.9)
                )),
                toolExecutor(),
                new AgentObservationBuilder(new ObjectMapper()),
                new ObjectMapper()
        );

        AgentLoopEvidence evidence = runner.run(null, new AgentLoopState(
                "run-1",
                "local",
                "搜索一首周杰伦的歌，然后分享感人评论",
                List.of(),
                AgentTaskMemory.empty("local"),
                List.of(),
                0
        ), List.of(initialSearch));

        assertEquals(2, evidence.observations().size());
        assertEquals("search_songs", evidence.observations().getFirst().toolName());
        assertEquals(1, evidence.observations().getFirst().arguments().get("limit"));
        assertEquals("get_hot_comments", evidence.observations().get(1).toolName());
        assertEquals(1, evidence.songs().size());
    }

    @Test
    void rejectsRepeatedSearchKeywordWithDifferentLimit() {
        AgentStepAction initialSearch = new AgentStepAction(
                AgentStepActionType.TOOL_CALL,
                "search_songs",
                Map.of("keyword", "周杰伦", "limit", 1),
                "执行已规划音乐能力",
                0.9,
                "turn_planner_seed"
        );
        AgentLoopRunner runner = new AgentLoopRunner(
                new SequencedPlanner(List.of(
                        new AgentStepAction(AgentStepActionType.TOOL_CALL, "search_songs", Map.of("keyword", "周杰伦", "limit", 8), "搜索周杰伦", 0.9, "扩大候选"),
                        AgentStepAction.finalAnswer("结束", 0.9)
                )),
                toolExecutor(),
                new AgentObservationBuilder(new ObjectMapper()),
                new ObjectMapper()
        );

        AgentLoopEvidence evidence = runner.run(null, new AgentLoopState(
                "run-1",
                "local",
                "搜索一首周杰伦的歌，然后分享感人评论",
                List.of(),
                AgentTaskMemory.empty("local"),
                List.of(),
                0
        ), List.of(initialSearch));

        assertEquals(2, evidence.observations().size());
        assertEquals(AgentObservationStatus.SUCCESS, evidence.observations().getFirst().status());
        assertEquals(1, evidence.observations().getFirst().arguments().get("limit"));
        assertEquals(AgentObservationStatus.SKIPPED, evidence.observations().get(1).status());
        assertEquals(8, evidence.observations().get(1).arguments().get("limit"));
        assertTrue(evidence.observations().get(1).resultJson().contains("search_keyword_already_observed"));
        assertEquals(1, evidence.songs().size());
    }

    @Test
    void stopsAfterMaximumSteps() {
        AgentLoopRunner runner = new AgentLoopRunner(
                new CountingSearchPlanner(),
                toolExecutor(),
                new AgentObservationBuilder(new ObjectMapper()),
                new ObjectMapper()
        );

        AgentLoopEvidence evidence = runner.run(null, new AgentLoopState(
                "run-1",
                "local",
                "一直搜索",
                List.of(),
                AgentTaskMemory.empty("local"),
                List.of(),
                0
        ));

        assertEquals(5, evidence.observations().size());
        assertTrue(evidence.observations().stream().allMatch(observation -> observation.status() == AgentObservationStatus.SUCCESS));
    }

    @Test
    void allowsSongIdFromTaskMemoryForFollowUp() {
        Song targetSong = song();
        AgentTaskMemory memory = new AgentTaskMemory(
                "local",
                "music-agent-task",
                "搜索晴天",
                "晴天",
                1,
                List.of(targetSong),
                List.of(targetSong.title()),
                List.of(),
                List.of(),
                targetSong,
                "comments",
                List.of("get_hot_comments 成功，评论 1 条"),
                Instant.EPOCH
        );
        AgentLoopRunner runner = new AgentLoopRunner(
                new SequencedPlanner(List.of(
                        new AgentStepAction(AgentStepActionType.TOOL_CALL, "get_lyrics", Map.of("songId", "qqmusic:0"), "读歌词", 0.9, "使用任务记忆里的上一首歌"),
                        AgentStepAction.finalAnswer("信息足够", 0.9)
                )),
                toolExecutor(),
                new AgentObservationBuilder(new ObjectMapper()),
                new ObjectMapper()
        );

        AgentLoopEvidence evidence = runner.run(null, new AgentLoopState(
                "run-1",
                "local",
                "这首歌歌词呢",
                List.of(),
                memory,
                List.of(),
                0
        ));

        assertEquals(1, evidence.observations().size());
        assertEquals(AgentObservationStatus.SUCCESS, evidence.observations().getFirst().status());
        assertEquals("lyrics", evidence.completedTaskType());
        assertTrue(evidence.observations().getFirst().resultJson().contains("从前从前"));
    }

    @Test
    void recordsFailureObservationWhenToolFails() {
        AgentLoopRunner runner = new AgentLoopRunner(
                new SequencedPlanner(List.of(
                        new AgentStepAction(AgentStepActionType.TOOL_CALL, "get_hot_comments", Map.of("songId", "qqmusic:0", "limit", 1), "读评论", 0.9, "使用任务记忆里的上一首歌"),
                        AgentStepAction.finalAnswer("结束", 0.9)
                )),
                toolExecutor(new FailingCommentsProvider()),
                new AgentObservationBuilder(new ObjectMapper()),
                new ObjectMapper()
        );

        AgentLoopEvidence evidence = runner.run(null, new AgentLoopState(
                "run-1",
                "local",
                "换一条评论",
                List.of(),
                memoryWithTargetSong(),
                List.of(),
                0
        ));

        assertEquals(1, evidence.observations().size());
        assertEquals(AgentObservationStatus.FAILURE, evidence.observations().getFirst().status());
        assertTrue(evidence.observations().getFirst().resultJson().contains("QQ Music 500"));
    }

    @Test
    void keepsEmptyCommentResultAsEvidence() {
        AgentLoopRunner runner = new AgentLoopRunner(
                new SequencedPlanner(List.of(
                        new AgentStepAction(AgentStepActionType.TOOL_CALL, "get_hot_comments", Map.of("songId", "qqmusic:0", "limit", 1), "读评论", 0.9, "使用任务记忆里的上一首歌"),
                        AgentStepAction.finalAnswer("结束", 0.9)
                )),
                toolExecutor(new EmptyCommentsProvider()),
                new AgentObservationBuilder(new ObjectMapper()),
                new ObjectMapper()
        );

        AgentLoopEvidence evidence = runner.run(null, new AgentLoopState(
                "run-1",
                "local",
                "分享评论",
                List.of(),
                memoryWithTargetSong(),
                List.of(),
                0
        ));

        assertEquals(1, evidence.observations().size());
        assertEquals(AgentObservationStatus.SUCCESS, evidence.observations().getFirst().status());
        assertEquals("get_hot_comments 成功，评论 0 条", evidence.observations().getFirst().plannerSummary());
        assertTrue(evidence.observations().getFirst().resultJson().contains("\"comments\":[]"));
    }

    private static class FinalAnswerPlanner extends AgentStepPlanner {
        @Override
        public AgentStepAction nextAction(MusioConfig.Ai ai, AgentLoopState state) {
            return AgentStepAction.finalAnswer("信息足够，直接回答。", 0.9);
        }
    }

    private static class SequencedPlanner extends AgentStepPlanner {
        private final List<AgentStepAction> actions;
        private int index;

        private SequencedPlanner(List<AgentStepAction> actions) {
            this.actions = actions;
        }

        @Override
        public AgentStepAction nextAction(MusioConfig.Ai ai, AgentLoopState state) {
            return actions.get(Math.min(index++, actions.size() - 1));
        }
    }

    private static class CountingSearchPlanner extends AgentStepPlanner {
        private int index;

        @Override
        public AgentStepAction nextAction(MusioConfig.Ai ai, AgentLoopState state) {
            index++;
            return new AgentStepAction(
                    AgentStepActionType.TOOL_CALL,
                    "search_songs",
                    Map.of("keyword", "周杰伦 " + index, "limit", 1),
                    "搜索周杰伦",
                    0.9,
                    "继续搜索"
            );
        }
    }

    private AgentToolExecutor toolExecutor() {
        return toolExecutor(new FakeProvider());
    }

    private AgentToolExecutor toolExecutor(MusicProvider provider) {
        AgentEventBus eventBus = new AgentEventBus();
        return new AgentToolExecutor(new MusicReadTools(
                new MusicProviderGateway(List.of(provider)),
                null,
                eventBus,
                new ObjectMapper(),
                new AgentTracePublisher(eventBus),
                null
        ));
    }

    private AgentTaskMemory memoryWithTargetSong() {
        Song targetSong = song();
        return new AgentTaskMemory(
                "local",
                "music-agent-task",
                "搜索晴天",
                "晴天",
                1,
                List.of(targetSong),
                List.of(targetSong.title()),
                List.of(),
                List.of(),
                targetSong,
                "search",
                List.of("search_songs 成功，歌曲 1 首：晴天 id=qqmusic:0"),
                Instant.EPOCH
        );
    }

    private static Song song() {
        return new Song("qqmusic:0", ProviderType.QQMUSIC, "晴天", List.of("周杰伦"), "叶惠美", 269, null);
    }

    private static class FakeProvider implements MusicProvider {
        @Override
        public ProviderType type() {
            return ProviderType.QQMUSIC;
        }

        @Override
        public List<Song> searchSongs(String keyword, int limit) {
            return List.of(song());
        }

        @Override
        public List<Comment> getComments(String songId) {
            return List.of(new Comment("comment:1", songId, ProviderType.QQMUSIC, "Cheer", "虽然叫晴天，但整个故事都在下雨、", 251122, Instant.EPOCH));
        }

        @Override
        public SongDetail getSongDetail(String songId) {
            return new SongDetail(songId, ProviderType.QQMUSIC, "晴天", List.of("周杰伦"), "叶惠美", 269, null, null);
        }

        @Override
        public Lyrics getLyrics(String songId) {
            return new Lyrics(songId, ProviderType.QQMUSIC, "从前从前有个人爱你很久", null);
        }

        @Override
        public LoginStartResult startLogin() {
            throw new UnsupportedOperationException();
        }

        @Override
        public LoginStatus checkLogin(String loginId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserProfile getProfile(String userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Playlist> getPlaylists(String userId) {
            return List.of();
        }

        @Override
        public List<Song> getPlaylistSongs(String playlistId) {
            return List.of();
        }

        @Override
        public SongUrl getSongUrl(String songId) {
            throw new UnsupportedOperationException();
        }
    }

    private static class FailingCommentsProvider extends FakeProvider {
        @Override
        public List<Comment> getComments(String songId) {
            throw new RuntimeException("QQ Music 500");
        }
    }

    private static class EmptyCommentsProvider extends FakeProvider {
        @Override
        public List<Comment> getComments(String songId) {
            return List.of();
        }
    }
}
