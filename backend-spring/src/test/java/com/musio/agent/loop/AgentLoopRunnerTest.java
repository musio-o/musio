package com.musio.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.AgentGoal;
import com.musio.agent.AgentRunContext;
import com.musio.agent.AgentRequiredOutcome;
import com.musio.agent.AgentToolExecutor;
import com.musio.agent.capability.AgentCapability;
import com.musio.agent.capability.AgentCapabilityArgumentContext;
import com.musio.agent.capability.AgentCapabilityExecutor;
import com.musio.agent.capability.AgentCapabilityHandler;
import com.musio.agent.capability.AgentCapabilityRegistry;
import com.musio.agent.capability.AgentCapabilityValidationResult;
import com.musio.agent.capability.CapabilityEffect;
import com.musio.agent.capability.MusicReadCapabilityHandler;
import com.musio.agent.capability.MusioPlaylistCapabilityExecutor;
import com.musio.agent.capability.MusioPlaylistCapabilityHandler;
import com.musio.agent.trace.AgentTracePublisher;
import com.musio.config.MusioConfig;
import com.musio.events.AgentEventBus;
import com.musio.model.AgentEvent;
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
import java.util.Optional;
import java.util.Set;

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
    void outcomePreservesTerminalReason() {
        AgentLoopRunner runner = new AgentLoopRunner(
                new SequencedPlanner(List.of(
                        new AgentStepAction(AgentStepActionType.REQUEST_CONFIRMATION, "", Map.of(), "请求确认", 0.9, "account_write_requires_confirmation")
                )),
                null,
                new AgentObservationBuilder(new ObjectMapper()),
                new ObjectMapper()
        );

        AgentLoopOutcome outcome = runner.runOutcome(null, new AgentLoopState(
                "run-1",
                "local",
                "收藏到 QQ 音乐账号",
                List.of(),
                AgentTaskMemory.empty("local"),
                List.of(),
                0
        ));

        assertEquals(AgentLoopOutcomeType.NEEDS_CONFIRMATION, outcome.type());
        assertEquals("account_write_requires_confirmation", outcome.reason());
        assertFalse(outcome.hasObservations());
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
    void publishesLoopProgressWhenTraceIsEnabled() {
        AgentEventBus eventBus = new AgentEventBus();
        AgentTracePublisher tracePublisher = new AgentTracePublisher(eventBus);
        List<AgentEvent> events = new java.util.ArrayList<>();
        eventBus.subscribe("run-trace", events::add);
        AgentLoopRunner runner = new AgentLoopRunner(
                new SequencedPlanner(List.of(
                        new AgentStepAction(AgentStepActionType.TOOL_CALL, "search_songs", Map.of("keyword", "周杰伦", "limit", 1), "搜索周杰伦", 0.9, "先找歌"),
                        AgentStepAction.finalAnswer("信息足够", 0.9)
                )),
                new AgentObservationBuilder(new ObjectMapper()),
                new ObjectMapper(),
                new AgentCapabilityRegistry(),
                new AgentCapabilityExecutor(toolExecutor(), null),
                tracePublisher
        );

        AgentRunContext.setRunId("run-trace");
        AgentRunContext.setTraceEnabled(true);
        try {
            runner.runOutcome(null, new AgentLoopState(
                    "run-trace",
                    "local",
                    "搜索周杰伦",
                    List.of(),
                    AgentTaskMemory.empty("local"),
                    List.of(),
                    0
            ));
        } finally {
            AgentRunContext.clear();
        }

        assertTrue(events.stream().anyMatch(event -> "trace_step".equals(event.type())
                && "loop.step.1".equals(event.data().get("stepId"))
                && "running".equals(event.data().get("status"))));
        assertTrue(events.stream().anyMatch(event -> "trace_step".equals(event.type())
                && "loop.step.1".equals(event.data().get("stepId"))
                && "done".equals(event.data().get("status"))));
        assertTrue(events.stream().anyMatch(event -> "trace_step".equals(event.type())
                && "loop.finish".equals(event.data().get("stepId"))));
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
    void rejectsToolCallMissingCapabilityRequiredArgument() {
        AgentLoopRunner runner = new AgentLoopRunner(
                new SequencedPlanner(List.of(
                        new AgentStepAction(AgentStepActionType.TOOL_CALL, "search_songs", Map.of("keyword", "周杰伦"), "搜索周杰伦", 0.9, "缺少 limit"),
                        AgentStepAction.finalAnswer("结束", 0.9)
                )),
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

        assertEquals(1, evidence.observations().size());
        assertEquals(AgentObservationStatus.SKIPPED, evidence.observations().getFirst().status());
        assertTrue(evidence.observations().getFirst().resultJson().contains("missing_required_argument"));
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
                null,
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

    @Test
    void rejectsSingleCommentReadAlreadyCoveredByBatchObservation() {
        AgentLoopRunner runner = new AgentLoopRunner(
                new SequencedPlanner(List.of(
                        new AgentStepAction(AgentStepActionType.TOOL_CALL, "get_hot_comments", Map.of("songId", "qqmusic:0", "limit", 1), "重复读评论", 0.9, "已被批量读取"),
                        AgentStepAction.finalAnswer("结束", 0.9)
                )),
                toolExecutor(),
                new AgentObservationBuilder(new ObjectMapper()),
                new ObjectMapper()
        );
        AgentObservation batchComments = new AgentObservation(
                "loop.step.1",
                "get_hot_comments",
                Map.of("songIds", List.of("qqmusic:0", "qqmusic:1"), "limit", 1),
                AgentObservationStatus.SUCCESS,
                """
                        {"success":true,"requestedCount":2,"count":1,"commentResults":[{"songId":"qqmusic:0","success":true,"count":1,"comments":[{"id":"comment:1","songId":"qqmusic:0","provider":"QQMUSIC","authorName":"Cheer","text":"虽然叫晴天，但整个故事都在下雨、","likedCount":251122,"createdAt":"1970-01-01T00:00:00Z"}]},{"songId":"qqmusic:1","success":true,"count":0,"comments":[]}],"comments":[{"id":"comment:1","songId":"qqmusic:0","provider":"QQMUSIC","authorName":"Cheer","text":"虽然叫晴天，但整个故事都在下雨、","likedCount":251122,"createdAt":"1970-01-01T00:00:00Z"}]}
                        """,
                "get_hot_comments 成功，歌曲 2 首，评论 1 条",
                List.of()
        );

        AgentLoopEvidence evidence = runner.run(null, new AgentLoopState(
                "run-1",
                "local",
                "再读评论",
                List.of(),
                AgentTaskMemory.empty("local"),
                List.of(batchComments),
                1
        ));

        assertEquals(2, evidence.observations().size());
        assertEquals(AgentObservationStatus.SKIPPED, evidence.observations().get(1).status());
        assertTrue(evidence.observations().get(1).resultJson().contains("tool_result_already_observed"));
    }

    @Test
    void rejectsLocalPlaylistWriteWhenManifestDoesNotAllowIt() {
        AgentLoopRunner runner = new AgentLoopRunner(
                new SequencedPlanner(List.of(
                        new AgentStepAction(AgentStepActionType.TOOL_CALL, "add_song_to_musio_playlist", Map.of("songIndex", 1), "收藏第一首歌", 0.9, "用户要求收藏"),
                        AgentStepAction.finalAnswer("结束", 0.9)
                )),
                toolExecutor(),
                new AgentObservationBuilder(new ObjectMapper()),
                new ObjectMapper()
        );

        AgentLoopEvidence evidence = runner.run(null, new AgentLoopState(
                "run-1",
                "local",
                "收藏第一首歌",
                List.of(),
                AgentTaskMemory.empty("local"),
                List.of(),
                0
        ));

        assertEquals(1, evidence.observations().size());
        assertEquals(AgentObservationStatus.SKIPPED, evidence.observations().getFirst().status());
        assertTrue(evidence.observations().getFirst().resultJson().contains("unknown_tool"));
    }

    @Test
    void recognizesLocalPlaylistWriteAsPlaylistTaskWhenManifestAllowsIt() {
        AgentLoopRunner runner = new AgentLoopRunner(
                new SequencedPlanner(List.of(
                        new AgentStepAction(AgentStepActionType.TOOL_CALL, "add_song_to_musio_playlist", Map.of("songIndex", 1), "收藏第一首歌", 0.9, "用户要求收藏"),
                        AgentStepAction.finalAnswer("结束", 0.9)
                )),
                toolExecutor(),
                new AgentObservationBuilder(new ObjectMapper()),
                new ObjectMapper(),
                new AgentCapabilityRegistry(),
                null
        );

        AgentLoopEvidence evidence = runner.run(null, new AgentLoopState(
                "run-1",
                "local",
                "收藏第一首歌",
                List.of(),
                AgentTaskMemory.empty("local"),
                List.of(),
                0,
                new AgentCapabilityRegistry().manifest(true)
        ));

        assertEquals(1, evidence.observations().size());
        assertEquals(AgentObservationStatus.SKIPPED, evidence.observations().getFirst().status());
        assertTrue(evidence.observations().getFirst().resultJson().contains("tool_not_executable"));
    }

    @Test
    void executesLocalPlaylistWriteWhenExecutorIsInjected() {
        MusioPlaylistCapabilityExecutor playlistExecutor = new StubPlaylistCapabilityExecutor("""
                {"success":true,"summary":"已帮你收藏到 Musio 歌单：晴天 - 周杰伦。","playlistId":"default","song":{"id":"qqmusic:0","provider":"QQMUSIC","title":"晴天","artists":["周杰伦"],"album":"叶惠美","durationSeconds":269,"artworkUrl":null}}
                """);
        AgentLoopRunner runner = new AgentLoopRunner(
                new SequencedPlanner(List.of(
                        new AgentStepAction(AgentStepActionType.TOOL_CALL, "add_song_to_musio_playlist", Map.of("songId", "qqmusic:0"), "收藏歌曲", 0.9, "用户要求收藏"),
                        AgentStepAction.finalAnswer("结束", 0.9)
                )),
                toolExecutor(),
                new AgentObservationBuilder(new ObjectMapper()),
                new ObjectMapper(),
                new AgentCapabilityRegistry(),
                playlistExecutor
        );

        AgentLoopEvidence evidence = runner.run(null, new AgentLoopState(
                "run-1",
                "local",
                "把这首歌加入歌单",
                List.of(),
                AgentTaskMemory.empty("local"),
                List.of(),
                0,
                new AgentCapabilityRegistry().manifest(true),
                1
        ));

        assertEquals(1, evidence.observations().size());
        assertEquals(AgentObservationStatus.SUCCESS, evidence.observations().getFirst().status());
        assertEquals("playlist", evidence.completedTaskType());
        assertEquals(1, evidence.songs().size());
    }

    @Test
    void executesRecommendationCapabilityThroughUnifiedHandlerPath() {
        AgentCapabilityHandler recommendationHandler = new StubRecommendationCapabilityHandler();
        AgentCapabilityRegistry registry = new AgentCapabilityRegistry(List.of(recommendationHandler));
        AgentLoopRunner runner = new AgentLoopRunner(
                new SequencedPlanner(List.of(
                        new AgentStepAction(AgentStepActionType.TOOL_CALL, "recommend_songs", Map.of("request", "深夜写代码", "count", 1), "生成推荐", 0.9, "开放推荐"),
                        AgentStepAction.finalAnswer("结束", 0.9)
                )),
                new AgentObservationBuilder(new ObjectMapper()),
                new ObjectMapper(),
                registry,
                new AgentCapabilityExecutor(List.of(recommendationHandler))
        );

        AgentLoopEvidence evidence = runner.run(null, new AgentLoopState(
                "run-1",
                "local",
                "推荐一首适合深夜写代码听的歌",
                List.of(),
                AgentTaskMemory.empty("local"),
                List.of(),
                0,
                registry.readManifest(),
                1
        ));

        assertEquals(1, evidence.observations().size());
        assertEquals(AgentObservationStatus.SUCCESS, evidence.observations().getFirst().status());
        assertEquals("recommend", evidence.completedTaskType());
        assertEquals("安静", evidence.songs().getFirst().title());
    }

    @Test
    void completesPureRecommendationAfterRecommendSongsSatisfiesRequestedCount() {
        AgentCapabilityHandler recommendationHandler = new StubRecommendationCapabilityHandler();
        AgentCapabilityRegistry registry = new AgentCapabilityRegistry(List.of(recommendationHandler));
        AgentLoopRunner runner = new AgentLoopRunner(
                new SequencedPlanner(List.of(
                        new AgentStepAction(AgentStepActionType.TOOL_CALL, "recommend_songs", Map.of("request", "提升专注力", "count", 1), "生成推荐", 0.9, "开放推荐"),
                        new AgentStepAction(AgentStepActionType.TOOL_CALL, "recommend_songs", Map.of("request", "提升专注力", "count", 1), "重复推荐", 0.9, "不应执行")
                )),
                new AgentObservationBuilder(new ObjectMapper()),
                new ObjectMapper(),
                registry,
                new AgentCapabilityExecutor(List.of(recommendationHandler))
        );

        AgentLoopOutcome outcome = runner.runOutcome(null, new AgentLoopState(
                "run-1",
                "local",
                "推荐一首适合提升专注力的音乐",
                List.of(),
                AgentTaskMemory.empty("local"),
                List.of(),
                0,
                registry.readManifest(),
                1,
                new AgentGoal(
                        "推荐一首适合提升专注力的音乐",
                        "推荐一首适合提升专注力的音乐",
                        "recommend",
                        "new_task",
                        true,
                        true,
                        false,
                        false,
                        1,
                        List.of(),
                        List.of(AgentRequiredOutcome.RECOMMENDATION)
                )
        ));

        assertEquals(AgentLoopOutcomeType.COMPLETED, outcome.type());
        assertEquals("tool_completion", outcome.reason());
        assertEquals(1, outcome.evidence().observations().size());
        assertEquals("recommend_songs", outcome.evidence().observations().getFirst().toolName());
        assertEquals("安静", outcome.evidence().songs().getFirst().title());
    }

    @Test
    void doesNotAutoCompleteRecommendationWhenUserAlsoRequestsComments() {
        AgentCapabilityHandler recommendationHandler = new StubRecommendationCapabilityHandler();
        AgentCapabilityRegistry registry = new AgentCapabilityRegistry(List.of(recommendationHandler));
        TrackingPlanner planner = new TrackingPlanner(List.of(
                new AgentStepAction(AgentStepActionType.TOOL_CALL, "recommend_songs", Map.of("request", "提升专注力", "count", 1), "生成推荐", 0.9, "开放推荐"),
                AgentStepAction.finalAnswer("用户还要求热评，继续交给 planner 决策", 0.9)
        ));
        AgentLoopRunner runner = new AgentLoopRunner(
                planner,
                new AgentObservationBuilder(new ObjectMapper()),
                new ObjectMapper(),
                registry,
                new AgentCapabilityExecutor(List.of(recommendationHandler))
        );

        AgentLoopOutcome outcome = runner.runOutcome(null, new AgentLoopState(
                "run-1",
                "local",
                "推荐一首适合提升专注力的音乐，并获取热评",
                List.of(),
                AgentTaskMemory.empty("local"),
                List.of(),
                0,
                registry.readManifest(),
                1,
                new AgentGoal(
                        "推荐一首适合提升专注力的音乐，并获取热评",
                        "推荐一首适合提升专注力的音乐，并获取热评",
                        "recommend",
                        "new_task",
                        true,
                        true,
                        false,
                        false,
                        1,
                        List.of(),
                        List.of(AgentRequiredOutcome.RECOMMENDATION, AgentRequiredOutcome.COMMENTS)
                )
        ));

        assertEquals(2, planner.calls());
        assertEquals(AgentLoopOutcomeType.COMPLETED, outcome.type());
        assertEquals("用户还要求热评，继续交给 planner 决策", outcome.reason());
        assertEquals(1, outcome.evidence().observations().size());
        assertEquals(AgentObservationStatus.SUCCESS, outcome.evidence().observations().getFirst().status());
    }

    @Test
    void readsLyricsBeforeAcceptingConfirmationRequestForDeferredLocalWrite() {
        AgentCapabilityHandler recommendationHandler = new StubRecommendationCapabilityHandler();
        MusicReadCapabilityHandler readHandler = musicReadCapabilityHandler();
        AgentCapabilityRegistry registry = new AgentCapabilityRegistry(List.of(recommendationHandler, readHandler));
        AgentLoopRunner runner = new AgentLoopRunner(
                new SequencedPlanner(List.of(
                        new AgentStepAction(AgentStepActionType.TOOL_CALL, "recommend_songs", Map.of("request", "推荐一首周杰伦的歌", "count", 1), "生成推荐", 0.9, "开放推荐"),
                        new AgentStepAction(AgentStepActionType.REQUEST_CONFIRMATION, "", Map.of(), "请求收藏确认", 0.9, "planner_too_early_confirmation")
                )),
                new AgentObservationBuilder(new ObjectMapper()),
                new ObjectMapper(),
                registry,
                new AgentCapabilityExecutor(List.of(recommendationHandler, readHandler))
        );

        AgentLoopOutcome outcome = runner.runOutcome(null, new AgentLoopState(
                "run-1",
                "local",
                "推荐一首周杰伦的歌，将其加入歌单，最后分享歌词",
                List.of(),
                AgentTaskMemory.empty("local"),
                List.of(),
                0,
                registry.readManifest(),
                1,
                new AgentGoal(
                        "推荐一首周杰伦的歌，将其加入歌单，最后分享歌词",
                        "推荐一首周杰伦的歌，将其加入歌单，最后分享歌词",
                        "recommend",
                        "new_task",
                        true,
                        true,
                        false,
                        false,
                        1,
                        List.of(),
                        List.of(AgentRequiredOutcome.RECOMMENDATION, AgentRequiredOutcome.LYRICS)
                )
        ));

        assertEquals(AgentLoopOutcomeType.COMPLETED, outcome.type());
        assertEquals("tool_completion", outcome.reason());
        assertEquals(2, outcome.evidence().observations().size());
        assertEquals("recommend_songs", outcome.evidence().observations().getFirst().toolName());
        assertEquals("get_lyrics", outcome.evidence().observations().get(1).toolName());
        assertEquals(AgentObservationStatus.SUCCESS, outcome.evidence().observations().get(1).status());
        assertTrue(outcome.evidence().observations().get(1).resultJson().contains("从前从前"));
    }

    @Test
    void allowsRepeatedRecommendationCallWhenSlotCoverageIsIncomplete() {
        AgentCapabilityHandler recommendationHandler = new PartialThenCompleteRecommendationCapabilityHandler();
        AgentCapabilityRegistry registry = new AgentCapabilityRegistry(List.of(recommendationHandler));
        AgentStepAction action = new AgentStepAction(
                AgentStepActionType.TOOL_CALL,
                "recommend_songs",
                Map.of(
                        "request", "推荐两首许嵩的歌和一首后弦的歌",
                        "count", 3,
                        "slots", List.of(
                                Map.of("slotId", "xusong", "targetType", "artist", "target", "许嵩", "count", 2),
                                Map.of("slotId", "houxian", "targetType", "artist", "target", "后弦", "count", 1)
                        )
                ),
                "生成推荐",
                0.9,
                "开放推荐"
        );
        AgentLoopRunner runner = new AgentLoopRunner(
                new SequencedPlanner(List.of(action, action, AgentStepAction.finalAnswer("结束", 0.9))),
                new AgentObservationBuilder(new ObjectMapper()),
                new ObjectMapper(),
                registry,
                new AgentCapabilityExecutor(List.of(recommendationHandler))
        );

        AgentLoopEvidence evidence = runner.run(null, new AgentLoopState(
                "run-1",
                "local",
                "推荐两首许嵩的歌和一首后弦的歌",
                List.of(),
                AgentTaskMemory.empty("local"),
                List.of(),
                0,
                registry.readManifest(),
                3,
                new AgentGoal(
                        "推荐两首许嵩的歌和一首后弦的歌",
                        "推荐两首许嵩的歌和一首后弦的歌",
                        "recommend",
                        "new_task",
                        true,
                        true,
                        false,
                        false,
                        3,
                        List.of(
                                new com.musio.agent.recommendation.RecommendationSlot("xusong", "artist", "许嵩", 2),
                                new com.musio.agent.recommendation.RecommendationSlot("houxian", "artist", "后弦", 1)
                        ),
                        List.of(),
                        List.of(AgentRequiredOutcome.RECOMMENDATION)
                )
        ));

        assertEquals(2, evidence.observations().size());
        assertEquals(AgentObservationStatus.SUCCESS, evidence.observations().getFirst().status());
        assertEquals(AgentObservationStatus.SUCCESS, evidence.observations().get(1).status());
        assertFalse(evidence.observations().get(1).resultJson().contains("duplicate_tool_call"));
        assertEquals(3, evidence.songs().size());
    }

    @Test
    void completesCompositeRecommendationAfterAllRequiredOutcomesDespiteSkippedDuplicate() {
        AgentCapabilityHandler recommendationHandler = new StubRecommendationCapabilityHandler();
        MusioPlaylistCapabilityExecutor playlistExecutor = new StubPlaylistCapabilityExecutor("""
                {"success":true,"summary":"已帮你收藏到 Musio 歌单：安静 - 周杰伦。","playlistId":"default","song":{"id":"qqmusic:quiet","provider":"QQMUSIC","title":"安静","artists":["周杰伦"],"album":"范特西","durationSeconds":334,"artworkUrl":null}}
                """);
        MusicReadCapabilityHandler readHandler = musicReadCapabilityHandler();
        MusioPlaylistCapabilityHandler playlistHandler = new MusioPlaylistCapabilityHandler(playlistExecutor);
        AgentCapabilityRegistry registry = new AgentCapabilityRegistry(List.of(recommendationHandler, readHandler, playlistHandler));
        AgentStepAction comments = new AgentStepAction(AgentStepActionType.TOOL_CALL, "get_hot_comments", Map.of("songId", "qqmusic:quiet", "limit", 1), "读评论", 0.9, "用户要热评");
        AgentLoopRunner runner = new AgentLoopRunner(
                new SequencedPlanner(List.of(
                        new AgentStepAction(AgentStepActionType.TOOL_CALL, "recommend_songs", Map.of("request", "推荐一首歌", "count", 1), "生成推荐", 0.9, "开放推荐"),
                        comments,
                        new AgentStepAction(AgentStepActionType.TOOL_CALL, "get_lyrics", Map.of("songId", "qqmusic:quiet"), "读歌词", 0.9, "用户要歌词"),
                        comments,
                        new AgentStepAction(AgentStepActionType.TOOL_CALL, "add_song_to_musio_playlist", Map.of("songId", "qqmusic:quiet"), "收藏歌曲", 0.9, "用户要加入歌单")
                )),
                new AgentObservationBuilder(new ObjectMapper()),
                new ObjectMapper(),
                registry,
                new AgentCapabilityExecutor(List.of(recommendationHandler, readHandler, playlistHandler))
        );

        AgentLoopOutcome outcome = runner.runOutcome(null, new AgentLoopState(
                "run-1",
                "local",
                "推荐一首歌，获取最热门评论和歌词，并加入歌单",
                List.of(),
                AgentTaskMemory.empty("local"),
                List.of(),
                0,
                registry.manifest(true),
                1,
                new AgentGoal(
                        "推荐一首歌，获取最热门评论和歌词，并加入歌单",
                        "推荐一首歌，获取最热门评论和歌词，并加入歌单",
                        "recommend",
                        "new_task",
                        true,
                        true,
                        true,
                        false,
                        1,
                        List.of(),
                        List.of(AgentRequiredOutcome.RECOMMENDATION, AgentRequiredOutcome.COMMENTS, AgentRequiredOutcome.LYRICS, AgentRequiredOutcome.LOCAL_PLAYLIST_WRITE)
                )
        ));

        assertEquals(AgentLoopOutcomeType.COMPLETED, outcome.type());
        assertEquals("tool_completion", outcome.reason());
        assertEquals(5, outcome.evidence().observations().size());
        assertEquals(AgentObservationStatus.SKIPPED, outcome.evidence().observations().get(3).status());
        assertTrue(outcome.evidence().observations().get(3).resultJson().contains("duplicate_tool_call"));
        assertEquals(AgentObservationStatus.SUCCESS, outcome.evidence().observations().get(4).status());
    }

    private static class StubPlaylistCapabilityExecutor extends MusioPlaylistCapabilityExecutor {
        private final String resultJson;

        private StubPlaylistCapabilityExecutor(String resultJson) {
            super(null, null, null, null, new ObjectMapper());
            this.resultJson = resultJson;
        }

        @Override
        public String executeAddSongToMusioPlaylist(AgentLoopState state, Map<String, Object> arguments) {
            return resultJson;
        }
    }

    private static class StubRecommendationCapabilityHandler implements AgentCapabilityHandler {
        private static final AgentCapability CAPABILITY = new AgentCapability(
                "recommend_songs",
                CapabilityEffect.READ,
                "测试推荐能力。",
                "{\"request\": string, \"count\": number}",
                Set.of("request", "count")
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
            return arguments == null ? Map.of() : arguments;
        }

        @Override
        public AgentCapabilityValidationResult validateArguments(
                String capabilityName,
                Map<String, Object> arguments,
                AgentCapabilityArgumentContext context
        ) {
            return AgentCapabilityValidationResult.accepted();
        }

        @Override
        public Optional<String> execute(AgentLoopState state, String capabilityName, Map<String, Object> arguments) {
            return Optional.of("""
                    {"success":true,"summary":"已生成并匹配 1 首推荐歌曲。","songs":[{"id":"qqmusic:quiet","provider":"QQMUSIC","title":"安静","artists":["周杰伦"],"album":"范特西","durationSeconds":334,"artworkUrl":null}],"recommendations":[{"songId":"qqmusic:quiet","title":"安静","artists":["周杰伦"],"reason":"钢琴和慢速旋律适合深夜专注。","matchedQuery":"安静 周杰伦"}]}
                    """);
        }
    }

    private static class PartialThenCompleteRecommendationCapabilityHandler implements AgentCapabilityHandler {
        private static final AgentCapability CAPABILITY = new AgentCapability(
                "recommend_songs",
                CapabilityEffect.READ,
                "测试推荐能力。",
                "{\"request\": string, \"count\": number, \"slots\": []}",
                Set.of("request")
        );
        private int calls;

        @Override
        public List<AgentCapability> capabilities() {
            return List.of(CAPABILITY);
        }

        @Override
        public boolean supports(String capabilityName) {
            return CAPABILITY.name().equals(capabilityName);
        }

        @Override
        public AgentCapabilityValidationResult validateArguments(
                String capabilityName,
                Map<String, Object> arguments,
                AgentCapabilityArgumentContext context
        ) {
            return AgentCapabilityValidationResult.accepted();
        }

        @Override
        public Optional<String> execute(AgentLoopState state, String capabilityName, Map<String, Object> arguments) {
            calls++;
            if (calls == 1) {
                return Optional.of("""
                        {"success":true,"requestedTotal":3,"resolvedTotal":1,"slotResults":[{"slotId":"xusong","requested":2,"resolved":1},{"slotId":"houxian","requested":1,"resolved":0}],"songs":[{"slotId":"xusong","id":"qqmusic:x1","provider":"QQMUSIC","title":"断桥残雪","artists":["许嵩"],"album":"自定义","durationSeconds":240,"artworkUrl":null}]}
                        """);
            }
            return Optional.of("""
                    {"success":true,"requestedTotal":3,"resolvedTotal":2,"slotResults":[{"slotId":"xusong","requested":2,"resolved":1},{"slotId":"houxian","requested":1,"resolved":1}],"songs":[{"slotId":"xusong","id":"qqmusic:x2","provider":"QQMUSIC","title":"清明雨上","artists":["许嵩"],"album":"自定义","durationSeconds":240,"artworkUrl":null},{"slotId":"houxian","id":"qqmusic:h1","provider":"QQMUSIC","title":"西厢","artists":["后弦"],"album":"自定义","durationSeconds":240,"artworkUrl":null}]}
                    """);
        }
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

    private static class TrackingPlanner extends SequencedPlanner {
        private int calls;

        private TrackingPlanner(List<AgentStepAction> actions) {
            super(actions);
        }

        @Override
        public AgentStepAction nextAction(MusioConfig.Ai ai, AgentLoopState state) {
            calls++;
            return super.nextAction(ai, state);
        }

        int calls() {
            return calls;
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
        return new AgentToolExecutor(musicReadTools(provider));
    }

    private MusicReadCapabilityHandler musicReadCapabilityHandler() {
        return new MusicReadCapabilityHandler(musicReadTools(new FakeProvider()));
    }

    private MusicReadTools musicReadTools(MusicProvider provider) {
        AgentEventBus eventBus = new AgentEventBus();
        return new MusicReadTools(
                new MusicProviderGateway(List.of(provider)),
                null,
                eventBus,
                new ObjectMapper(),
                new AgentTracePublisher(eventBus),
                null
        );
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
                null,
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
