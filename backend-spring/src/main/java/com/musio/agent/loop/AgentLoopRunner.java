package com.musio.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.AgentToolExecutor;
import com.musio.agent.capability.AgentCapabilityExecutor;
import com.musio.agent.capability.AgentCapabilityManifest;
import com.musio.agent.capability.AgentCapabilityRegistry;
import com.musio.agent.capability.AgentCapabilityValidationResult;
import com.musio.agent.capability.MusioPlaylistCapabilityExecutor;
import com.musio.config.MusioConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
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
                return outcome(AgentLoopOutcomeType.COMPLETED, state, action.reason());
            }
            if (action.action() == AgentStepActionType.REQUEST_CONFIRMATION || action.action() == AgentStepActionType.UNSUPPORTED) {
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
        if (executedCalls.contains(callKey(action))) {
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
        if (state.requestedSongCount() == 1
                && state.capabilityManifest().allows(AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST)
                && successfulToolObserved(state, "get_hot_comments")
                && successfulToolObserved(state, AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST)) {
            return true;
        }
        return false;
    }

    private boolean successfulToolObserved(AgentLoopState state, String toolName) {
        return state.observations().stream()
                .anyMatch(observation -> observation.status() == AgentObservationStatus.SUCCESS && toolName.equals(observation.toolName()));
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
