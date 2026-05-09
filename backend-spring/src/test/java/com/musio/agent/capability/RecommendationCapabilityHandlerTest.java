package com.musio.agent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.loop.AgentLoopState;
import com.musio.agent.recommendation.RecommendationCandidate;
import com.musio.agent.recommendation.RecommendationOrchestrator;
import com.musio.agent.recommendation.RecommendationResponse;
import com.musio.agent.recommendation.RecommendationResult;
import com.musio.agent.recommendation.ResolvedRecommendation;
import com.musio.config.MusioConfig;
import com.musio.model.AgentTaskMemory;
import com.musio.model.ProviderType;
import com.musio.model.Song;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(5, arguments.get("count"));
        assertTrue(handler.validateArguments(
                AgentCapabilityRegistry.RECOMMEND_SONGS,
                arguments,
                AgentCapabilityArgumentContext.stepPlanner(5)
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

    private static final class StubRecommendationOrchestrator extends RecommendationOrchestrator {
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
            Song song = new Song("qqmusic:quiet", ProviderType.QQMUSIC, "安静", List.of("周杰伦"), "范特西", 334, null);
            ResolvedRecommendation resolved = new ResolvedRecommendation(song, "钢琴和慢速旋律适合深夜专注。", "安静 周杰伦");
            RecommendationResult result = new RecommendationResult(
                    List.of(resolved),
                    List.of(new RecommendationCandidate("不存在的歌", "不存在的歌手", "无法匹配。")),
                    "已可信匹配 1 首歌曲，未匹配 1 首。"
            );
            return new RecommendationResponse("已推荐《安静》。", List.of(song), result);
        }
    }
}
