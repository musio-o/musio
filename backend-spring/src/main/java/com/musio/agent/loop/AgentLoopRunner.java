package com.musio.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.AgentToolExecutor;
import com.musio.agent.AgentRequiredOutcome;
import com.musio.agent.capability.AgentCapabilityExecutor;
import com.musio.agent.capability.AgentCapabilityManifest;
import com.musio.agent.capability.AgentCapabilityRegistry;
import com.musio.agent.capability.AgentCapabilityValidationResult;
import com.musio.agent.capability.MusioPlaylistCapabilityExecutor;
import com.musio.agent.recommendation.RecommendationSlot;
import com.musio.agent.recommendation.RecommendationSlots;
import com.musio.config.MusioConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AgentLoopRunner {
    private static final Logger log = LoggerFactory.getLogger(AgentLoopRunner.class);
    private static final int MAX_STEPS = 5;

    private final AgentStepPlanner stepPlanner;
    private final AgentCapabilityExecutor capabilityExecutor;
    private final AgentObservationBuilder observationBuilder;
    private final ObjectMapper objectMapper;
    private final AgentCapabilityRegistry capabilityRegistry;

    public AgentLoopRunner(
            AgentStepPlanner stepPlanner,
            AgentToolExecutor toolExecutor,
            AgentObservationBuilder observationBuilder,
            ObjectMapper objectMapper
    ) {
        this(stepPlanner, observationBuilder, objectMapper, new AgentCapabilityRegistry(), new AgentCapabilityExecutor(toolExecutor, null));
    }

    public AgentLoopRunner(
            AgentStepPlanner stepPlanner,
            AgentToolExecutor toolExecutor,
            AgentObservationBuilder observationBuilder,
            ObjectMapper objectMapper,
            AgentCapabilityRegistry capabilityRegistry,
            MusioPlaylistCapabilityExecutor musioPlaylistCapabilityExecutor
    ) {
        this(stepPlanner, observationBuilder, objectMapper, capabilityRegistry, new AgentCapabilityExecutor(toolExecutor, musioPlaylistCapabilityExecutor));
    }

    @Autowired
    public AgentLoopRunner(
            AgentStepPlanner stepPlanner,
            AgentObservationBuilder observationBuilder,
            ObjectMapper objectMapper,
            AgentCapabilityRegistry capabilityRegistry,
            AgentCapabilityExecutor capabilityExecutor
    ) {
        this.stepPlanner = stepPlanner;
        this.capabilityExecutor = capabilityExecutor;
        this.observationBuilder = observationBuilder;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.capabilityRegistry = capabilityRegistry == null ? new AgentCapabilityRegistry() : capabilityRegistry;
    }

    public AgentLoopEvidence run(MusioConfig.Ai ai, AgentLoopState initialState) {
        return run(ai, initialState, List.of());
    }

    public AgentLoopEvidence run(MusioConfig.Ai ai, AgentLoopState initialState, List<AgentStepAction> initialActions) {
        return runOutcome(ai, initialState, initialActions).evidence();
    }

    public AgentLoopOutcome runOutcome(MusioConfig.Ai ai, AgentLoopState initialState) {
        return runOutcome(ai, initialState, List.of());
    }

    public AgentLoopOutcome runOutcome(MusioConfig.Ai ai, AgentLoopState initialState, List<AgentStepAction> initialActions) {
        AgentLoopState state = initialState;
        Set<String> executedCalls = new LinkedHashSet<>();
        int step = 0;
        for (AgentStepAction action : safeInitialActions(initialActions)) {
            if (step >= MAX_STEPS) {
                return outcome(AgentLoopOutcomeType.MAX_STEPS, state, "max_steps");
            }
            state = executeToolAction(state, step, action, executedCalls);
            if (shouldFinishAfterTool(state, action)) {
                return outcome(AgentLoopOutcomeType.COMPLETED, state, "tool_completion");
            }
            step++;
        }
        for (; step < MAX_STEPS; step++) {
            AgentStepAction action = stepPlanner.nextAction(ai, state);
            if (action.action() == AgentStepActionType.FINAL_ANSWER) {
                AgentStepAction recoveryAction = recoveryActionForMissingReadOutcome(state);
                if (recoveryAction != null) {
                    state = executeToolAction(state, step, recoveryAction, executedCalls);
                    if (shouldFinishAfterTool(state, recoveryAction)) {
                        return outcome(AgentLoopOutcomeType.COMPLETED, state, "tool_completion");
                    }
                    continue;
                }
                return outcome(AgentLoopOutcomeType.COMPLETED, state, action.reason());
            }
            if (action.action() == AgentStepActionType.REQUEST_CONFIRMATION || action.action() == AgentStepActionType.UNSUPPORTED) {
                AgentStepAction recoveryAction = recoveryActionForMissingReadOutcome(state);
                if (recoveryAction != null) {
                    state = executeToolAction(state, step, recoveryAction, executedCalls);
                    if (shouldFinishAfterTool(state, recoveryAction)) {
                        return outcome(AgentLoopOutcomeType.COMPLETED, state, "tool_completion");
                    }
                    continue;
                }
                AgentLoopOutcomeType type = action.action() == AgentStepActionType.REQUEST_CONFIRMATION
                        ? AgentLoopOutcomeType.NEEDS_CONFIRMATION
                        : AgentLoopOutcomeType.UNSUPPORTED;
                return outcome(type, state, action.reason());
            }
            if (action.action() != AgentStepActionType.TOOL_CALL) {
                state = appendSkipped(state, step, action, "unsupported_action");
                continue;
            }
            state = executeToolAction(state, step, action, executedCalls);
            if (shouldFinishAfterTool(state, action)) {
                return outcome(AgentLoopOutcomeType.COMPLETED, state, "tool_completion");
            }
        }
        return outcome(AgentLoopOutcomeType.MAX_STEPS, state, "max_steps");
    }

    private AgentStepAction recoveryActionForMissingReadOutcome(AgentLoopState state) {
        if (state == null || state.goal() == null || state.goal().requiredOutcomes().isEmpty()) {
            return null;
        }
        AgentCapabilityManifest manifest = manifestFor(state);
        for (AgentRequiredOutcome outcome : state.goal().requiredOutcomes()) {
            if (requiredOutcomeSatisfied(state, outcome)) {
                continue;
            }
            AgentStepAction action = recoveryActionForOutcome(state, manifest, outcome);
            if (action != null) {
                return action;
            }
        }
        return null;
    }

    private AgentStepAction recoveryActionForOutcome(AgentLoopState state, AgentCapabilityManifest manifest, AgentRequiredOutcome outcome) {
        return switch (outcome) {
            case LYRICS -> readBySongIdsAction(state, manifest, "get_lyrics", "读取歌词", "required_outcome_recovery");
            case COMMENTS -> readBySongIdsAction(state, manifest, "get_hot_comments", "读取热门评论", "required_outcome_recovery");
            case DETAIL -> readDetailAction(state, manifest);
            case RECOMMENDATION -> recommendAction(state, manifest);
            default -> null;
        };
    }

    private AgentStepAction readBySongIdsAction(AgentLoopState state, AgentCapabilityManifest manifest, String toolName, String publicActivity, String reason) {
        if (manifest == null || !manifest.allows(toolName) || successfulToolObserved(state, toolName)) {
            return null;
        }
        List<String> songIds = observedSongIds(state);
        if (songIds.isEmpty()) {
            return null;
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        if ("get_hot_comments".equals(toolName)) {
            arguments.put("limit", 1);
        }
        if (songIds.size() == 1) {
            arguments.put("songId", songIds.getFirst());
        } else {
            arguments.put("songIds", songIds);
        }
        return new AgentStepAction(AgentStepActionType.TOOL_CALL, toolName, arguments, publicActivity, 1.0, reason);
    }

    private AgentStepAction readDetailAction(AgentLoopState state, AgentCapabilityManifest manifest) {
        if (manifest == null || !manifest.allows("get_song_detail") || successfulToolObserved(state, "get_song_detail")) {
            return null;
        }
        List<String> songIds = observedSongIds(state);
        if (songIds.isEmpty()) {
            return null;
        }
        return new AgentStepAction(
                AgentStepActionType.TOOL_CALL,
                "get_song_detail",
                Map.of("songId", songIds.getFirst()),
                "读取歌曲详情",
                1.0,
                "required_outcome_recovery"
        );
    }

    private AgentStepAction recommendAction(AgentLoopState state, AgentCapabilityManifest manifest) {
        if (manifest == null || !manifest.allows(AgentCapabilityRegistry.RECOMMEND_SONGS)) {
            return null;
        }
        if (recommendationSatisfied(state, new AgentStepAction(AgentStepActionType.TOOL_CALL, AgentCapabilityRegistry.RECOMMEND_SONGS, Map.of(), "", 1.0, ""))) {
            return null;
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("request", state.goal().effectiveRequest());
        int count = state.goal().recommendationTotalCount() > 0 ? state.goal().recommendationTotalCount() : Math.max(1, state.requestedSongCount());
        arguments.put("count", count);
        List<Map<String, Object>> slots = RecommendationSlots.toArgument(state.goal().recommendationSlots());
        if (!slots.isEmpty()) {
            arguments.put("slots", slots);
        }
        if (!state.goal().avoidSongTitles().isEmpty()) {
            arguments.put("excludedTitles", state.goal().avoidSongTitles());
        }
        return new AgentStepAction(
                AgentStepActionType.TOOL_CALL,
                AgentCapabilityRegistry.RECOMMEND_SONGS,
                arguments,
                "生成推荐",
                1.0,
                "required_outcome_recovery"
        );
    }

    private List<String> observedSongIds(AgentLoopState state) {
        if (state == null) {
            return List.of();
        }
        LinkedHashSet<String> songIds = new LinkedHashSet<>();
        if (state.observations() != null) {
            state.observations().stream()
                    .filter(observation -> observation.status() == AgentObservationStatus.SUCCESS)
                    .flatMap(observation -> observation.songs().stream())
                    .filter(song -> song != null && song.id() != null && !song.id().isBlank())
                    .map(song -> song.id().strip())
                    .forEach(songIds::add);
        }
        if (state.taskMemory() != null) {
            if (state.taskMemory().lastTargetSong() != null && state.taskMemory().lastTargetSong().id() != null && !state.taskMemory().lastTargetSong().id().isBlank()) {
                songIds.add(state.taskMemory().lastTargetSong().id().strip());
            }
            state.taskMemory().lastResultSongs().stream()
                    .filter(song -> song != null && song.id() != null && !song.id().isBlank())
                    .map(song -> song.id().strip())
                    .forEach(songIds::add);
        }
        return List.copyOf(songIds);
    }

    private List<AgentStepAction> safeInitialActions(List<AgentStepAction> initialActions) {
        if (initialActions == null || initialActions.isEmpty()) {
            return List.of();
        }
        return initialActions.stream()
                .filter(action -> action != null && action.action() == AgentStepActionType.TOOL_CALL)
                .limit(1)
                .toList();
    }

    private AgentLoopState executeToolAction(AgentLoopState state, int step, AgentStepAction action, Set<String> executedCalls) {
        ValidationResult validation = validate(action, state, executedCalls);
        if (!validation.valid()) {
            return appendSkipped(state, step, action, validation.reason());
        }
        String resultJson = executeTool(state, action);
        AgentObservation observation = observationBuilder.build(stepId(step), action.toolName(), action.arguments(), resultJson);
        executedCalls.add(callKey(action));
        return state.withObservation(observation);
    }

    private ValidationResult validate(AgentStepAction action, AgentLoopState state, Set<String> executedCalls) {
        AgentCapabilityManifest manifest = manifestFor(state);
        if (!manifest.allows(action.toolName())) {
            return ValidationResult.rejected("unknown_tool");
        }
        if (capabilityExecutor == null || !capabilityExecutor.canExecute(action.toolName())) {
            return ValidationResult.rejected("tool_not_executable");
        }
        if (executedCalls.contains(callKey(action)) && !recommendationCallStillNeedsWork(state, action)) {
            return ValidationResult.rejected("duplicate_tool_call");
        }
        AgentCapabilityValidationResult capabilityValidation = capabilityExecutor.validate(state, action.toolName(), action.arguments());
        if (!capabilityValidation.valid()) {
            return ValidationResult.rejected(capabilityValidation.reason());
        }
        return ValidationResult.accepted();
    }

    private String executeTool(AgentLoopState state, AgentStepAction action) {
        return capabilityExecutor.execute(state, action.toolName(), action.arguments())
                .orElseGet(() -> failureJson("tool_not_executable"));
    }

    private boolean shouldFinishAfterTool(AgentLoopState state, AgentStepAction action) {
        if (state == null || action == null) {
            return false;
        }
        if (pureRecommendationSatisfied(state, action)) {
            return true;
        }
        if (requiredOutcomesSatisfied(state)) {
            return true;
        }
        if (state.requestedSongCount() == 1
                && state.capabilityManifest().allows(AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST)
                && successfulToolObserved(state, "get_hot_comments")
                && successfulLocalPlaylistWriteObserved(state)) {
            return true;
        }
        return false;
    }

    private boolean requiredOutcomesSatisfied(AgentLoopState state) {
        if (state == null || state.goal() == null || state.goal().requiredOutcomes().isEmpty()) {
            return false;
        }
        for (AgentRequiredOutcome outcome : state.goal().requiredOutcomes()) {
            if (!requiredOutcomeSatisfied(state, outcome)) {
                return false;
            }
        }
        return true;
    }

    private boolean requiredOutcomeSatisfied(AgentLoopState state, AgentRequiredOutcome outcome) {
        return switch (outcome) {
            case RECOMMENDATION -> recommendationSatisfied(state, new AgentStepAction(
                    AgentStepActionType.TOOL_CALL,
                    AgentCapabilityRegistry.RECOMMEND_SONGS,
                    Map.of(),
                    "",
                    1.0,
                    "outcome_verification"
            ));
            case SEARCH -> successfulToolObserved(state, "search_songs");
            case COMMENTS -> successfulToolObserved(state, "get_hot_comments");
            case LYRICS -> successfulToolObserved(state, "get_lyrics");
            case DETAIL -> successfulToolObserved(state, "get_song_detail");
            case PLAYLIST -> successfulToolObserved(state, "get_user_playlists")
                    || successfulToolObserved(state, "get_playlist_songs")
                    || successfulLocalPlaylistWriteObserved(state);
            case PROFILE -> successfulToolObserved(state, "get_user_music_profile");
            case PLAYBACK -> false;
            case LOCAL_PLAYLIST_WRITE -> successfulLocalPlaylistWriteObserved(state);
            case ACCOUNT_WRITE -> false;
        };
    }

    private boolean pureRecommendationSatisfied(AgentLoopState state, AgentStepAction action) {
        if (!AgentCapabilityRegistry.RECOMMEND_SONGS.equals(action.toolName())
                || state.goal() == null
                || !"recommend".equals(state.goal().taskType())) {
            return false;
        }
        if (!state.goal().requiredOutcomes().equals(List.of(AgentRequiredOutcome.RECOMMENDATION))) {
            return false;
        }
        return recommendationSatisfied(state, action);
    }

    private AgentObservation lastSuccessfulObservation(AgentLoopState state, String toolName) {
        for (int index = state.observations().size() - 1; index >= 0; index--) {
            AgentObservation observation = state.observations().get(index);
            if (observation.status() == AgentObservationStatus.SUCCESS && toolName.equals(observation.toolName())) {
                return observation;
            }
        }
        return null;
    }

    private boolean successfulToolObserved(AgentLoopState state, String toolName) {
        return state.observations().stream()
                .anyMatch(observation -> observation.status() == AgentObservationStatus.SUCCESS && toolName.equals(observation.toolName()));
    }

    private boolean successfulLocalPlaylistWriteObserved(AgentLoopState state) {
        if (state == null || state.observations() == null) {
            return false;
        }
        for (AgentObservation observation : state.observations()) {
            if (observation.status() != AgentObservationStatus.SUCCESS
                    || !AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST.equals(observation.toolName())) {
                continue;
            }
            if (localPlaylistWriteComplete(observation)) {
                return true;
            }
        }
        return false;
    }

    private boolean localPlaylistWriteComplete(AgentObservation observation) {
        if (observation == null || observation.resultJson() == null || observation.resultJson().isBlank()) {
            return true;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(observation.resultJson());
            if (!root.path("success").asBoolean(false)) {
                return false;
            }
            int requested = root.path("requestedCount").isNumber()
                    ? root.path("requestedCount").asInt()
                    : root.hasNonNull("songId") ? 1 : 0;
            int resolved = root.path("count").isNumber()
                    ? root.path("count").asInt()
                    : root.hasNonNull("songId") ? 1 : 0;
            int unresolved = root.path("unresolvedCount").asInt(0);
            return requested <= 0 || (resolved >= requested && unresolved <= 0);
        } catch (Exception ignored) {
            return true;
        }
    }

    private boolean recommendationCallStillNeedsWork(AgentLoopState state, AgentStepAction action) {
        if (action == null || !AgentCapabilityRegistry.RECOMMEND_SONGS.equals(action.toolName())) {
            return false;
        }
        return !recommendationSatisfied(state, action);
    }

    private boolean recommendationSatisfied(AgentLoopState state, AgentStepAction action) {
        if (state == null || action == null || !AgentCapabilityRegistry.RECOMMEND_SONGS.equals(action.toolName())) {
            return false;
        }
        List<RecommendationSlot> slots = recommendationSlots(state, action);
        if (!slots.isEmpty()) {
            Map<String, Integer> resolvedBySlot = new LinkedHashMap<>();
            for (AgentObservation observation : state.observations()) {
                if (observation.status() != AgentObservationStatus.SUCCESS || !AgentCapabilityRegistry.RECOMMEND_SONGS.equals(observation.toolName())) {
                    continue;
                }
                Map<String, Integer> observationSlots = resolvedSlots(observation);
                for (Map.Entry<String, Integer> entry : observationSlots.entrySet()) {
                    resolvedBySlot.merge(entry.getKey(), entry.getValue(), Integer::sum);
                }
            }
            if (resolvedBySlot.isEmpty()) {
                return successfulRecommendationSongIds(state).size() >= RecommendationSlots.totalCount(slots);
            }
            for (RecommendationSlot slot : slots) {
                if (resolvedBySlot.getOrDefault(slot.slotId(), 0) < slot.count()) {
                    return false;
                }
            }
            return true;
        }
        int requiredCount = requestedRecommendationCount(state, action);
        return successfulRecommendationSongIds(state).size() >= requiredCount;
    }

    private List<RecommendationSlot> recommendationSlots(AgentLoopState state, AgentStepAction action) {
        List<RecommendationSlot> slots = RecommendationSlots.fromArgument(action.arguments() == null ? null : action.arguments().get("slots"));
        if (!slots.isEmpty()) {
            return slots;
        }
        if (state != null && state.goal() != null) {
            return RecommendationSlots.normalize(state.goal().recommendationSlots());
        }
        return List.of();
    }

    private int requestedRecommendationCount(AgentLoopState state, AgentStepAction action) {
        Integer actionCount = integer(action.arguments() == null ? null : action.arguments().get("count"));
        if (actionCount != null) {
            return Math.max(1, actionCount);
        }
        if (state != null && state.goal() != null && state.goal().recommendationTotalCount() > 0) {
            return state.goal().recommendationTotalCount();
        }
        return state == null || state.requestedSongCount() <= 0 ? 1 : state.requestedSongCount();
    }

    private Set<String> successfulRecommendationSongIds(AgentLoopState state) {
        Set<String> songIds = new LinkedHashSet<>();
        if (state == null || state.observations() == null) {
            return songIds;
        }
        for (AgentObservation observation : state.observations()) {
            if (observation.status() != AgentObservationStatus.SUCCESS || !AgentCapabilityRegistry.RECOMMEND_SONGS.equals(observation.toolName())) {
                continue;
            }
            observation.songs().stream()
                    .filter(song -> song != null && song.id() != null && !song.id().isBlank())
                    .map(song -> song.id().strip())
                    .forEach(songIds::add);
        }
        return songIds;
    }

    private Map<String, Integer> resolvedSlots(AgentObservation observation) {
        if (observation == null || observation.resultJson().isBlank()) {
            return Map.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(observation.resultJson());
            com.fasterxml.jackson.databind.JsonNode slotResults = root.path("slotResults");
            if (!slotResults.isArray()) {
                return Map.of();
            }
            Map<String, Integer> values = new LinkedHashMap<>();
            for (com.fasterxml.jackson.databind.JsonNode slotResult : slotResults) {
                String slotId = slotResult.path("slotId").asText("");
                int resolved = slotResult.path("resolved").asInt(0);
                if (!slotId.isBlank() && resolved > 0) {
                    values.put(slotId, resolved);
                }
            }
            return values;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.strip());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private AgentCapabilityManifest manifestFor(AgentLoopState state) {
        AgentCapabilityManifest manifest = state == null ? null : state.capabilityManifest();
        return manifest == null || manifest.isEmpty() ? capabilityRegistry.readManifest() : manifest;
    }

    private AgentLoopState appendSkipped(AgentLoopState state, int step, AgentStepAction action, String reason) {
        log.info(
                "AGENT_LOOP_STEP_REJECTED runId={} userId={} step={} toolName={} reason={}",
                state.runId(),
                state.userId(),
                step + 1,
                action.toolName(),
                reason
        );
        AgentObservation observation = new AgentObservation(
                stepId(step),
                action.toolName(),
                action.arguments(),
                AgentObservationStatus.SKIPPED,
                failureJson(reason),
                action.toolName() + " 被拒绝：" + reason,
                List.of()
        );
        return state.withObservation(observation);
    }

    private AgentLoopEvidence evidence(AgentLoopState state) {
        return observationBuilder.evidence(state == null ? List.of() : state.observations(), completedTaskType(state == null ? List.of() : state.observations()));
    }

    private AgentLoopOutcome outcome(AgentLoopOutcomeType type, AgentLoopState state, String reason) {
        return new AgentLoopOutcome(type, state, evidence(state), reason);
    }

    private String completedTaskType(List<AgentObservation> observations) {
        if (observations == null || observations.isEmpty()) {
            return "";
        }
        for (int index = observations.size() - 1; index >= 0; index--) {
            AgentObservation observation = observations.get(index);
            if (observation.status() != AgentObservationStatus.SUCCESS) {
                continue;
            }
            return switch (observation.toolName()) {
                case AgentCapabilityRegistry.RECOMMEND_SONGS -> "recommend";
                case "get_hot_comments" -> "comments";
                case "get_lyrics" -> "lyrics";
                case "get_song_detail" -> "detail";
                case "get_user_playlists", "get_playlist_songs", "add_song_to_musio_playlist" -> "playlist";
                case "get_user_music_profile" -> "profile";
                case "search_songs" -> "search";
                default -> "";
            };
        }
        return "";
    }

    private String stepId(int step) {
        return "loop.step." + (step + 1);
    }

    private String callKey(AgentStepAction action) {
        return action.toolName() + "|" + new java.util.TreeMap<>(action.arguments()).toString();
    }

    private String failureJson(String message) {
        return "{\"success\":false,\"message\":\"" + escapeJson(message) + "\"}";
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record ValidationResult(boolean valid, String reason) {
        static ValidationResult accepted() {
            return new ValidationResult(true, "");
        }

        static ValidationResult rejected(String reason) {
            return new ValidationResult(false, reason);
        }
    }
}
