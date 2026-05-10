package com.musio.agent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.loop.AgentLoopState;
import com.musio.agent.loop.AgentObservation;
import com.musio.agent.loop.AgentObservationStatus;
import com.musio.agent.recommendation.RecommendationCandidate;
import com.musio.agent.recommendation.RecommendationOrchestrator;
import com.musio.agent.recommendation.RecommendationResponse;
import com.musio.agent.recommendation.ResolvedRecommendation;
import com.musio.agent.recommendation.RecommendationSlot;
import com.musio.agent.recommendation.RecommendationSlotResult;
import com.musio.agent.recommendation.RecommendationSlots;
import com.musio.config.MusioConfig;
import com.musio.config.MusioConfigService;
import com.musio.model.AgentRecentRecommendedSong;
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
            "根据开放推荐、场景、风格、心境或结构化多目标请求生成具体歌曲候选，并在音乐源中精确匹配真实歌曲。",
            "{\"request\": string, \"count\": number, \"slots\": [{\"slotId\": string, \"targetType\": string, \"target\": string, \"count\": number}], \"excludedTitles\": string[]}",
            Set.of("request")
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
        String currentSignature = recommendationSignature(arguments == null ? Map.of() : arguments);
        if (state != null && state.observations().stream()
                .anyMatch(observation -> sameSuccessfulRecommendationRequest(state, observation, arguments == null ? Map.of() : arguments, currentSignature))) {
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
        List<RecommendationSlot> slots = requestedSlots(safeArguments, state);
        int count = slots.isEmpty()
                ? integer(safeArguments, "count", state == null ? 0 : state.requestedSongCount())
                : RecommendationSlots.totalCount(slots);
        List<String> excludedTitles = excludedTitles(safeArguments, state);
        RecommendationResponse response = slots.isEmpty()
                ? recommendationOrchestrator.recommend(
                        ai(),
                        request,
                        count,
                        excludedTitles,
                        state == null ? null : state.taskMemory()
                )
                : recommendationOrchestrator.recommend(
                        ai(),
                        request,
                        slots,
                        excludedTitles,
                        state == null ? null : state.taskMemory()
                );
        return Optional.of(writeResult(response, slots, count));
    }

    private MusioConfig.Ai ai() {
        return configService == null || configService.config() == null ? null : configService.config().ai();
    }

    private String writeResult(RecommendationResponse response, List<RecommendationSlot> requestedSlots, int requestedCount) {
        List<RecommendationSlot> slots = RecommendationSlots.normalize(requestedSlots);
        int requestedTotal = slots.isEmpty() ? Math.max(1, requestedCount <= 0 ? 5 : requestedCount) : RecommendationSlots.totalCount(slots);
        int resolvedTotal = response == null || response.songs() == null ? 0 : response.songs().size();
        List<RecommendationSlotResult> slotResults = slotResults(response, slots);
        boolean success = response != null && response.songs() != null && !response.songs().isEmpty();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("summary", response == null ? "推荐能力没有返回结果。" : safe(response.answerText()));
        result.put("message", response == null ? "推荐能力没有返回结果。" : safe(response.answerText()));
        result.put("requestedCount", requestedTotal);
        result.put("requestedTotal", requestedTotal);
        result.put("resolvedTotal", resolvedTotal);
        result.put("count", resolvedTotal);
        result.put("slotResults", slotResultMaps(slotResults));
        result.put("songs", songs(response));
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
            value.put("slotId", safe(item.slotId()));
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
            value.put("slotId", safe(candidate.slotId()));
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
        if (shouldAvoidRecentRecommendations(state)) {
            values.addAll(recentRecommendedTitles(state));
        }
        if (state != null) {
            state.observations().stream()
                    .filter(observation -> observation.status() == AgentObservationStatus.SUCCESS)
                    .filter(observation -> AgentCapabilityRegistry.RECOMMEND_SONGS.equals(observation.toolName()))
                    .flatMap(observation -> observation.songs().stream())
                    .map(Song::title)
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(values::add);
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .limit(20)
                .toList();
    }

    private boolean shouldAvoidRecentRecommendations(AgentLoopState state) {
        if (state == null || state.taskMemory() == null || state.taskMemory().recentRecommendedSongs().isEmpty()) {
            return false;
        }
        String request = normalizeText((state.goal() == null ? "" : state.goal().effectiveRequest()) + " " + state.userMessage());
        if (request.isBlank()) {
            return true;
        }
        if (containsAny(request, "经典", "代表作", "最火", "最有名", "刚才那首", "上一首", "这首", "同一首", "就要", "还是")) {
            return false;
        }
        return state.taskMemory().recentRecommendedSongs().stream()
                .filter(item -> item != null && !item.title().isBlank())
                .noneMatch(item -> request.contains(normalizeText(item.title())));
    }

    private List<String> recentRecommendedTitles(AgentLoopState state) {
        if (state == null || state.taskMemory() == null || state.taskMemory().recentRecommendedSongs().isEmpty()) {
            return List.of();
        }
        return state.taskMemory().recentRecommendedSongs().stream()
                .filter(item -> item != null && !item.title().isBlank())
                .map(AgentRecentRecommendedSong::title)
                .distinct()
                .limit(20)
                .toList();
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private List<RecommendationSlot> requestedSlots(Map<String, Object> arguments, AgentLoopState state) {
        List<RecommendationSlot> slots = RecommendationSlots.fromArgument(arguments.get("slots"));
        if (!slots.isEmpty()) {
            return slots;
        }
        if (state != null && state.goal() != null) {
            return RecommendationSlots.normalize(state.goal().recommendationSlots());
        }
        return List.of();
    }

    private List<Map<String, Object>> songs(RecommendationResponse response) {
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
            value.put("slotId", safe(item.slotId()));
            value.put("id", song.id());
            value.put("songId", song.id());
            value.put("provider", song.provider());
            value.put("title", song.title());
            value.put("artist", song.artists() == null || song.artists().isEmpty() ? "" : String.join(" / ", song.artists()));
            value.put("artists", song.artists() == null ? List.of() : song.artists());
            value.put("album", song.album());
            value.put("durationSeconds", song.durationSeconds());
            value.put("artworkUrl", song.artworkUrl());
            values.add(value);
        }
        return values;
    }

    private List<RecommendationSlotResult> slotResults(RecommendationResponse response, List<RecommendationSlot> requestedSlots) {
        List<RecommendationSlot> slots = RecommendationSlots.normalize(requestedSlots);
        if (slots.isEmpty()) {
            return List.of();
        }
        List<RecommendationSlotResult> results = new ArrayList<>();
        for (RecommendationSlot slot : slots) {
            int resolved = 0;
            if (response != null && response.result() != null && response.result().resolved() != null) {
                resolved = (int) response.result().resolved().stream()
                        .filter(item -> item != null && slot.slotId().equals(item.slotId()))
                        .count();
            }
            results.add(new RecommendationSlotResult(slot.slotId(), slot.count(), resolved));
        }
        return List.copyOf(results);
    }

    private List<Map<String, Object>> slotResultMaps(List<RecommendationSlotResult> slotResults) {
        if (slotResults == null || slotResults.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> values = new ArrayList<>();
        for (RecommendationSlotResult slotResult : slotResults) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("slotId", slotResult.slotId());
            value.put("requested", slotResult.requested());
            value.put("resolved", slotResult.resolved());
            values.add(value);
        }
        return List.copyOf(values);
    }

    private boolean sameSuccessfulRecommendationRequest(AgentLoopState state, AgentObservation observation, Map<String, Object> arguments, String currentSignature) {
        return observation != null
                && observation.status() == AgentObservationStatus.SUCCESS
                && AgentCapabilityRegistry.RECOMMEND_SONGS.equals(observation.toolName())
                && !currentSignature.isBlank()
                && currentSignature.equals(recommendationSignature(observation.arguments()))
                && recommendationSatisfied(state, observation, arguments);
    }

    private String recommendationSignature(Map<String, Object> arguments) {
        String request = normalizeText(text(arguments == null ? Map.of() : arguments, "request"));
        if (request.isBlank()) {
            return "";
        }
        List<String> excludedTitles = stringList(arguments == null ? null : arguments.get("excludedTitles")).stream()
                .map(this::normalizeText)
                .filter(value -> !value.isBlank())
                .sorted()
                .toList();
        List<RecommendationSlot> slots = RecommendationSlots.fromArgument(arguments == null ? null : arguments.get("slots"));
        return request + "|slots=" + RecommendationSlots.summary(slots) + "|excluded=" + String.join(",", excludedTitles);
    }

    private boolean recommendationSatisfied(AgentLoopState state, AgentObservation observation, Map<String, Object> arguments) {
        List<RecommendationSlot> slots = requestedSlots(arguments, state);
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(observation.resultJson().isBlank() ? "{}" : observation.resultJson());
            if (!slots.isEmpty()) {
                Map<String, Integer> resolvedBySlot = new LinkedHashMap<>();
                com.fasterxml.jackson.databind.JsonNode slotResults = root.path("slotResults");
                if (slotResults.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode slotResult : slotResults) {
                        String slotId = slotResult.path("slotId").asText("");
                        if (!slotId.isBlank()) {
                            resolvedBySlot.put(slotId, Math.max(resolvedBySlot.getOrDefault(slotId, 0), slotResult.path("resolved").asInt(0)));
                        }
                    }
                }
                if (resolvedBySlot.isEmpty()) {
                    return observation.songs().size() >= RecommendationSlots.totalCount(slots);
                }
                for (RecommendationSlot slot : slots) {
                    if (resolvedBySlot.getOrDefault(slot.slotId(), 0) < slot.count()) {
                        return false;
                    }
                }
                return true;
            }
            int requested = integer(arguments, "count", state == null ? 0 : state.requestedSongCount());
            int resolved = root.path("resolvedTotal").asInt(root.path("count").asInt(observation.songs().size()));
            return resolved >= requested;
        } catch (Exception ignored) {
            int requested = integer(arguments, "count", state == null ? 0 : state.requestedSongCount());
            return observation.songs().size() >= requested;
        }
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

    private String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
    }
}
