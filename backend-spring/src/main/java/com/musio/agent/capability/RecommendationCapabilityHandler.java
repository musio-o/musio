package com.musio.agent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.loop.AgentLoopState;
import com.musio.agent.loop.AgentObservationStatus;
import com.musio.agent.recommendation.RecommendationCandidate;
import com.musio.agent.recommendation.RecommendationOrchestrator;
import com.musio.agent.recommendation.RecommendationResponse;
import com.musio.agent.recommendation.ResolvedRecommendation;
import com.musio.config.MusioConfig;
import com.musio.config.MusioConfigService;
import com.musio.model.Song;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@Order(-10)
public class RecommendationCapabilityHandler implements AgentCapabilityHandler {
    private static final AgentCapability CAPABILITY = new AgentCapability(
            AgentCapabilityRegistry.RECOMMEND_SONGS,
            CapabilityEffect.READ,
            "根据开放推荐、场景、风格或心境请求生成具体歌曲候选，并在音乐源中精确匹配真实歌曲。",
            "{\"request\": string, \"count\": number, \"excludedTitles\": string[]}",
            Set.of("request", "count")
    );

    private final RecommendationOrchestrator recommendationOrchestrator;
    private final MusioConfigService configService;
    private final ObjectMapper objectMapper;

    public RecommendationCapabilityHandler(
            RecommendationOrchestrator recommendationOrchestrator,
            MusioConfigService configService,
            ObjectMapper objectMapper
    ) {
        this.recommendationOrchestrator = recommendationOrchestrator;
        this.configService = configService;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper.findAndRegisterModules();
    }

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
        if (!supports(capabilityName)) {
            return arguments == null ? Map.of() : Map.copyOf(arguments);
        }
        return AgentCapabilityArgumentRules.normalizeKnownCapability(capabilityName, arguments, context);
    }

    @Override
    public AgentCapabilityValidationResult validateArguments(
            String capabilityName,
            Map<String, Object> arguments,
            AgentCapabilityArgumentContext context
    ) {
        if (!supports(capabilityName)) {
            return AgentCapabilityValidationResult.rejected("unknown_tool");
        }
        return AgentCapabilityArgumentRules.validateReadRequiredArguments(capabilityName, arguments == null ? Map.of() : arguments);
    }

    @Override
    public AgentCapabilityValidationResult validate(AgentLoopState state, String capabilityName, Map<String, Object> arguments) {
        AgentCapabilityValidationResult required = validateArguments(capabilityName, arguments, AgentCapabilityArgumentContext.defaultContext());
        if (!required.valid()) {
            return required;
        }
        if (state != null && state.observations().stream()
                .anyMatch(observation -> observation.status() == AgentObservationStatus.SUCCESS
                        && AgentCapabilityRegistry.RECOMMEND_SONGS.equals(observation.toolName()))) {
            return AgentCapabilityValidationResult.rejected("recommendation_already_observed");
        }
        return AgentCapabilityValidationResult.accepted();
    }

    @Override
    public Optional<String> execute(AgentLoopState state, String capabilityName, Map<String, Object> arguments) {
        if (!supports(capabilityName) || recommendationOrchestrator == null) {
            return Optional.empty();
        }
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        String request = text(safeArguments, "request");
        if (request.isBlank()) {
            request = state == null || state.goal() == null ? "" : state.goal().effectiveRequest();
        }
        if (request.isBlank()) {
            request = state == null ? "" : state.userMessage();
        }
        int count = integer(safeArguments, "count", state == null ? 0 : state.requestedSongCount());
        List<String> excludedTitles = excludedTitles(safeArguments, state);
        RecommendationResponse response = recommendationOrchestrator.recommend(
                ai(),
                request,
                count,
                excludedTitles,
                state == null ? null : state.taskMemory()
        );
        return Optional.of(writeResult(response, count));
    }

    private MusioConfig.Ai ai() {
        return configService == null || configService.config() == null ? null : configService.config().ai();
    }

    private String writeResult(RecommendationResponse response, int requestedCount) {
        boolean success = response != null && response.songs() != null && !response.songs().isEmpty();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("summary", response == null ? "推荐能力没有返回结果。" : safe(response.answerText()));
        result.put("message", response == null ? "推荐能力没有返回结果。" : safe(response.answerText()));
        result.put("requestedCount", Math.max(1, requestedCount <= 0 ? 5 : requestedCount));
        result.put("count", response == null || response.songs() == null ? 0 : response.songs().size());
        result.put("songs", response == null || response.songs() == null ? List.of() : response.songs());
        result.put("recommendations", recommendations(response));
        result.put("unresolved", unresolved(response));
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"success\":false,\"message\":\"recommendation_result_serialization_failed\"}";
        }
    }

    private List<Map<String, Object>> recommendations(RecommendationResponse response) {
        if (response == null || response.result() == null || response.result().resolved() == null) {
            return List.of();
        }
        List<Map<String, Object>> values = new ArrayList<>();
        for (ResolvedRecommendation item : response.result().resolved()) {
            if (item == null || item.song() == null) {
                continue;
            }
            Song song = item.song();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("songId", song.id());
            value.put("title", song.title());
            value.put("artists", song.artists() == null ? List.of() : song.artists());
            value.put("reason", safe(item.reason()));
            value.put("matchedQuery", safe(item.matchedQuery()));
            values.add(value);
        }
        return values;
    }

    private List<Map<String, Object>> unresolved(RecommendationResponse response) {
        if (response == null || response.result() == null || response.result().unresolved() == null) {
            return List.of();
        }
        List<Map<String, Object>> values = new ArrayList<>();
        for (RecommendationCandidate candidate : response.result().unresolved()) {
            if (candidate == null) {
                continue;
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("title", safe(candidate.title()));
            value.put("artist", safe(candidate.artist()));
            value.put("reason", safe(candidate.reason()));
            values.add(value);
        }
        return values;
    }

    private List<String> excludedTitles(Map<String, Object> arguments, AgentLoopState state) {
        List<String> values = new ArrayList<>(stringList(arguments.get("excludedTitles")));
        if (state != null && state.goal() != null) {
            values.addAll(state.goal().avoidSongTitles());
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .limit(20)
                .toList();
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value instanceof String text ? text.strip() : "";
    }

    private int integer(Map<String, Object> arguments, String key, int fallback) {
        Object value = arguments.get(key);
        if (value instanceof Number number) {
            return Math.max(1, Math.min(10, number.intValue()));
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Math.max(1, Math.min(10, Integer.parseInt(text.strip())));
            } catch (NumberFormatException ignored) {
                return fallback <= 0 ? 5 : Math.max(1, Math.min(10, fallback));
            }
        }
        return fallback <= 0 ? 5 : Math.max(1, Math.min(10, fallback));
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(item -> !item.isBlank())
                .map(String::strip)
                .toList();
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
