package com.musio.agent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.loop.AgentLoopState;
import com.musio.agent.loop.AgentObservation;
import com.musio.agent.loop.AgentObservationStatus;
import com.musio.agent.recommendation.RecommendationCandidate;
import com.musio.agent.recommendation.RecommendationOrchestrator;
import com.musio.agent.recommendation.RecommendationResponse;
import com.musio.agent.recommendation.RecommendationResult;
import com.musio.agent.recommendation.RecommendationSlot;
import com.musio.agent.recommendation.ResolvedRecommendation;
import com.musio.config.MusioConfig;
import com.musio.model.AgentRecentRecommendedSong;
import com.musio.model.AgentTaskMemory;
import com.musio.model.ProviderType;
import com.musio.model.Song;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommendationCapabilityHandlerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesRecommendSongsAsReadCapability() {
        RecommendationCapabilityHandler handler = new RecommendationCapabilityHandler(
                new StubRecommendationOrchestrator(),
                null,
                objectMapper
        );

        assertTrue(handler.supports(AgentCapabilityRegistry.RECOMMEND_SONGS));
        assertEquals(CapabilityEffect.READ, handler.capabilities().getFirst().effect());
    }

    @Test
    void normalizesAndValidatesRecommendArguments() {
        RecommendationCapabilityHandler handler = new RecommendationCapabilityHandler(
                new StubRecommendationOrchestrator(),
                null,
                objectMapper
        );

        Map<String, Object> arguments = handler.normalizeArguments(
                AgentCapabilityRegistry.RECOMMEND_SONGS,
                Map.of("request", " 深夜写代码 ", "count", 9),
                AgentCapabilityArgumentContext.stepPlanner(5)
        );

        assertEquals("深夜写代码", arguments.get("request"));
        assertEquals(9, arguments.get("count"));
        assertTrue(handler.validateArguments(
                AgentCapabilityRegistry.RECOMMEND_SONGS,
                arguments,
                AgentCapabilityArgumentContext.stepPlanner(5)
        ).valid());
    }

    @Test
    void normalizesSlotsAndDerivesCountFromSlotTotal() {
        RecommendationCapabilityHandler handler = new RecommendationCapabilityHandler(
                new StubRecommendationOrchestrator(),
                null,
                objectMapper
        );

        Map<String, Object> arguments = handler.normalizeArguments(
                AgentCapabilityRegistry.RECOMMEND_SONGS,
                Map.of(
                        "request", "推荐两首许嵩的歌和一首后弦的歌",
                        "slots", List.of(
                                Map.of("slotId", "xusong", "targetType", "artist", "target", "许嵩", "count", 2),
                                Map.of("slotId", "houxian", "targetType", "artist", "target", "后弦", "count", 1)
                        )
                ),
                AgentCapabilityArgumentContext.stepPlanner(1)
        );

        assertEquals(3, arguments.get("count"));
        assertEquals(2, ((List<?>) arguments.get("slots")).size());
        assertTrue(handler.validateArguments(
                AgentCapabilityRegistry.RECOMMEND_SONGS,
                arguments,
                AgentCapabilityArgumentContext.stepPlanner(1)
        ).valid());
    }

    @Test
    void executesRecommendationAndReturnsSongsForObservation() throws Exception {
        RecommendationCapabilityHandler handler = new RecommendationCapabilityHandler(
                new StubRecommendationOrchestrator(),
                null,
                objectMapper
        );

        String resultJson = handler.execute(
                new AgentLoopState(
                        "run-1",
                        "local",
                        "给我推荐 1 首适合深夜写代码听的歌",
                        List.of(),
                        AgentTaskMemory.empty("local"),
                        List.of(),
                        0
                ),
                AgentCapabilityRegistry.RECOMMEND_SONGS,
                Map.of("request", "适合深夜写代码", "count", 1)
        ).orElseThrow();

        var root = objectMapper.readTree(resultJson);
        assertTrue(root.path("success").asBoolean());
        assertEquals(1, root.path("songs").size());
        assertEquals("安静", root.path("songs").get(0).path("title").asText());
        assertEquals("钢琴和慢速旋律适合深夜专注。", root.path("recommendations").get(0).path("reason").asText());
    }

    @Test
    void executesMultiSlotRecommendationAndReturnsCoverage() throws Exception {
        RecommendationCapabilityHandler handler = new RecommendationCapabilityHandler(
                new StubRecommendationOrchestrator(),
                null,
                objectMapper
        );

        String resultJson = handler.execute(
                new AgentLoopState(
                        "run-1",
                        "local",
                        "推荐两首许嵩的歌和一首后弦的歌",
                        List.of(),
                        AgentTaskMemory.empty("local"),
                        List.of(),
                        0
                ),
                AgentCapabilityRegistry.RECOMMEND_SONGS,
                Map.of(
                        "request", "推荐两首许嵩的歌和一首后弦的歌",
                        "slots", List.of(
                                Map.of("slotId", "xusong", "targetType", "artist", "target", "许嵩", "count", 2),
                                Map.of("slotId", "houxian", "targetType", "artist", "target", "后弦", "count", 1)
                        )
                )
        ).orElseThrow();

        var root = objectMapper.readTree(resultJson);
        assertTrue(root.path("success").asBoolean());
        assertEquals(3, root.path("requestedTotal").asInt());
        assertEquals(3, root.path("resolvedTotal").asInt());
        assertEquals("xusong", root.path("songs").get(0).path("slotId").asText());
        assertEquals(2, root.path("slotResults").get(0).path("resolved").asInt());
        assertEquals(1, root.path("slotResults").get(1).path("resolved").asInt());
    }

    @Test
    void rejectsSameRecommendationRequestButAllowsDistinctRecommendationRequest() {
        RecommendationCapabilityHandler handler = new RecommendationCapabilityHandler(
                new StubRecommendationOrchestrator(),
                null,
                objectMapper
        );
        AgentLoopState state = new AgentLoopState(
                "run-1",
                "local",
                "推荐一首许嵩的歌，一首后弦的歌",
                List.of(),
                AgentTaskMemory.empty("local"),
                List.of(new AgentObservation(
                        "loop.step.1",
                        AgentCapabilityRegistry.RECOMMEND_SONGS,
                        Map.of("request", "推荐一首许嵩的歌", "count", 1),
                        AgentObservationStatus.SUCCESS,
                        "{\"success\":true}",
                        "已推荐许嵩",
                        List.of(new Song("qqmusic:xusong", ProviderType.QQMUSIC, "有何不可", List.of("许嵩"), "自定义", 240, null))
                )),
                1
        );

        assertFalse(handler.validate(
                state,
                AgentCapabilityRegistry.RECOMMEND_SONGS,
                Map.of("request", " 推荐一首许嵩的歌 ", "count", 1)
        ).valid());
        assertTrue(handler.validate(
                state,
                AgentCapabilityRegistry.RECOMMEND_SONGS,
                Map.of("request", "推荐一首后弦的歌", "count", 1)
        ).valid());
    }

    @Test
    void avoidsRecentRecommendationsByDefault() {
        StubRecommendationOrchestrator orchestrator = new StubRecommendationOrchestrator();
        RecommendationCapabilityHandler handler = new RecommendationCapabilityHandler(
                orchestrator,
                null,
                objectMapper
        );

        handler.execute(
                new AgentLoopState(
                        "run-1",
                        "local",
                        "再推荐一首周杰伦的歌",
                        List.of(),
                        memoryWithRecentRecommendation("安静"),
                        List.of(),
                        0
                ),
                AgentCapabilityRegistry.RECOMMEND_SONGS,
                Map.of("request", "再推荐一首周杰伦的歌", "count", 1)
        ).orElseThrow();

        assertTrue(orchestrator.lastAvoidSongTitles.contains("安静"));
    }

    @Test
    void allowsRecentRecommendationWhenUserExplicitlyAsksForClassic() {
        StubRecommendationOrchestrator orchestrator = new StubRecommendationOrchestrator();
        RecommendationCapabilityHandler handler = new RecommendationCapabilityHandler(
                orchestrator,
                null,
                objectMapper
        );

        handler.execute(
                new AgentLoopState(
                        "run-1",
                        "local",
                        "推荐一首周杰伦经典代表作",
                        List.of(),
                        memoryWithRecentRecommendation("安静"),
                        List.of(),
                        0
                ),
                AgentCapabilityRegistry.RECOMMEND_SONGS,
                Map.of("request", "推荐一首周杰伦经典代表作", "count", 1)
        ).orElseThrow();

        assertFalse(orchestrator.lastAvoidSongTitles.contains("安静"));
    }

    private AgentTaskMemory memoryWithRecentRecommendation(String title) {
        return new AgentTaskMemory(
                "local",
                "music-agent-task",
                "",
                "",
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new AgentRecentRecommendedSong("qqmusic:quiet", title, List.of("周杰伦"), "", "上一轮推荐", "", "run-0", "soft_avoid", Instant.now())),
                null,
                Instant.now()
        );
    }

    private static final class StubRecommendationOrchestrator extends RecommendationOrchestrator {
        private List<String> lastAvoidSongTitles = List.of();

        private StubRecommendationOrchestrator() {
            super(null, null, null, null);
        }

        @Override
        public RecommendationResponse recommend(
                MusioConfig.Ai ai,
                String userRequest,
                int requestedCount,
                List<String> avoidSongTitles,
                AgentTaskMemory taskMemory
        ) {
            lastAvoidSongTitles = avoidSongTitles == null ? List.of() : List.copyOf(avoidSongTitles);
            Song song = new Song("qqmusic:quiet", ProviderType.QQMUSIC, "安静", List.of("周杰伦"), "范特西", 334, null);
            ResolvedRecommendation resolved = new ResolvedRecommendation(song, "钢琴和慢速旋律适合深夜专注。", "安静 周杰伦");
            RecommendationResult result = new RecommendationResult(
                    List.of(resolved),
                    List.of(new RecommendationCandidate("不存在的歌", "不存在的歌手", "无法匹配。")),
                    "已可信匹配 1 首歌曲，未匹配 1 首。"
            );
            return new RecommendationResponse("已推荐《安静》。", List.of(song), result);
        }

        @Override
        public RecommendationResponse recommend(
                MusioConfig.Ai ai,
                String userRequest,
                List<RecommendationSlot> recommendationSlots,
                List<String> avoidSongTitles,
                AgentTaskMemory taskMemory
        ) {
            lastAvoidSongTitles = avoidSongTitles == null ? List.of() : List.copyOf(avoidSongTitles);
            Song one = new Song("qqmusic:x1", ProviderType.QQMUSIC, "断桥残雪", List.of("许嵩"), "自定义", 240, null);
            Song two = new Song("qqmusic:x2", ProviderType.QQMUSIC, "清明雨上", List.of("许嵩"), "自定义", 240, null);
            Song three = new Song("qqmusic:h1", ProviderType.QQMUSIC, "西厢", List.of("后弦"), "自定义", 240, null);
            RecommendationResult result = new RecommendationResult(
                    List.of(
                            new ResolvedRecommendation(one, "许嵩代表作。", "断桥残雪 许嵩", "xusong"),
                            new ResolvedRecommendation(two, "古风旋律。", "清明雨上 许嵩", "xusong"),
                            new ResolvedRecommendation(three, "后弦代表作。", "西厢 后弦", "houxian")
                    ),
                    List.of(),
                    "已可信匹配 3 首歌曲。"
            );
            return new RecommendationResponse("已推荐 3 首。", List.of(one, two, three), result, recommendationSlots);
        }
    }
}
