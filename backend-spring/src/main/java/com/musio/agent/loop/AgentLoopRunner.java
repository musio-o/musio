package com.musio.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.AgentToolExecutor;
import com.musio.agent.capability.AgentCapabilityManifest;
import com.musio.agent.capability.AgentCapabilityRegistry;
import com.musio.agent.capability.MusioPlaylistCapabilityExecutor;
import com.musio.config.MusioConfig;
import com.musio.model.AgentTaskMemory;
import com.musio.model.Song;
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
    private final AgentToolExecutor toolExecutor;
    private final AgentObservationBuilder observationBuilder;
    private final ObjectMapper objectMapper;
    private final AgentCapabilityRegistry capabilityRegistry;
    private final MusioPlaylistCapabilityExecutor musioPlaylistCapabilityExecutor;

    public AgentLoopRunner(
            AgentStepPlanner stepPlanner,
            AgentToolExecutor toolExecutor,
            AgentObservationBuilder observationBuilder,
            ObjectMapper objectMapper
    ) {
        this(stepPlanner, toolExecutor, observationBuilder, objectMapper, new AgentCapabilityRegistry(), null);
    }

    @Autowired
    public AgentLoopRunner(
            AgentStepPlanner stepPlanner,
            AgentToolExecutor toolExecutor,
            AgentObservationBuilder observationBuilder,
            ObjectMapper objectMapper,
            AgentCapabilityRegistry capabilityRegistry,
            MusioPlaylistCapabilityExecutor musioPlaylistCapabilityExecutor
    ) {
        this.stepPlanner = stepPlanner;
        this.toolExecutor = toolExecutor;
        this.observationBuilder = observationBuilder;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.capabilityRegistry = capabilityRegistry == null ? new AgentCapabilityRegistry() : capabilityRegistry;
        this.musioPlaylistCapabilityExecutor = musioPlaylistCapabilityExecutor;
    }

    public AgentLoopEvidence run(MusioConfig.Ai ai, AgentLoopState initialState) {
        return run(ai, initialState, List.of());
    }

    public AgentLoopEvidence run(MusioConfig.Ai ai, AgentLoopState initialState, List<AgentStepAction> initialActions) {
        AgentLoopState state = initialState;
        Set<String> executedCalls = new LinkedHashSet<>();
        int step = 0;
        for (AgentStepAction action : safeInitialActions(initialActions)) {
            if (step >= MAX_STEPS) {
                return evidence(state);
            }
            state = executeToolAction(state, step, action, executedCalls);
            if (shouldFinishAfterTool(state, action)) {
                return evidence(state);
            }
            step++;
        }
        for (; step < MAX_STEPS; step++) {
            AgentStepAction action = stepPlanner.nextAction(ai, state);
            if (action.action() == AgentStepActionType.FINAL_ANSWER) {
                return evidence(state);
            }
            if (action.action() == AgentStepActionType.REQUEST_CONFIRMATION || action.action() == AgentStepActionType.UNSUPPORTED) {
                return evidence(state);
            }
            if (action.action() != AgentStepActionType.TOOL_CALL) {
                state = appendSkipped(state, step, action, "unsupported_action");
                continue;
            }
            state = executeToolAction(state, step, action, executedCalls);
            if (shouldFinishAfterTool(state, action)) {
                return evidence(state);
            }
        }
        return evidence(state);
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
        if (AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST.equals(action.toolName()) && musioPlaylistCapabilityExecutor == null) {
            return ValidationResult.rejected("tool_not_executable");
        }
        if (executedCalls.contains(callKey(action))) {
            return ValidationResult.rejected("duplicate_tool_call");
        }
        if ("search_songs".equals(action.toolName()) && searchedKeywords(state).contains(normalizedKeyword(text(action.arguments(), "keyword")))) {
            return ValidationResult.rejected("search_keyword_already_observed");
        }
        if (requiresSongId(action.toolName()) && !knownSongIds(state).contains(text(action.arguments(), "songId"))) {
            return ValidationResult.rejected("song_id_not_observed");
        }
        if ("get_playlist_songs".equals(action.toolName()) && !knownPlaylistIds(state).contains(text(action.arguments(), "playlistId"))) {
            return ValidationResult.rejected("playlist_id_not_observed");
        }
        return ValidationResult.accepted();
    }

    private String executeTool(AgentLoopState state, AgentStepAction action) {
        if (AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST.equals(action.toolName())) {
            return musioPlaylistCapabilityExecutor.executeAddSongToMusioPlaylist(state, action.arguments());
        }
        return toolExecutor.executeTool(action.toolName(), action.arguments())
                .orElseGet(() -> failureJson("tool_not_executable"));
    }

    private boolean shouldFinishAfterTool(AgentLoopState state, AgentStepAction action) {
        if (state == null || action == null) {
            return false;
        }
        if (AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST.equals(action.toolName())) {
            return true;
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

    private Set<String> knownSongIds(AgentLoopState state) {
        Set<String> ids = new LinkedHashSet<>();
        String userMessage = state.userMessage() == null ? "" : state.userMessage();
        AgentTaskMemory memory = state.taskMemory();
        if (memory != null && memory.lastResultSongs() != null) {
            for (Song song : memory.lastResultSongs()) {
                addSongId(ids, song);
            }
        }
        if (memory != null) {
            addSongId(ids, memory.lastTargetSong());
        }
        for (AgentObservation observation : state.observations()) {
            for (Song song : observation.songs()) {
                addSongId(ids, song);
            }
            ids.addAll(songIdsFromResultJson(observation.resultJson()));
        }
        ids.addAll(providerIdsInText(userMessage));
        return ids;
    }

    private Set<String> searchedKeywords(AgentLoopState state) {
        Set<String> keywords = new LinkedHashSet<>();
        if (state == null || state.observations() == null) {
            return keywords;
        }
        for (AgentObservation observation : state.observations()) {
            if (observation.status() != AgentObservationStatus.SUCCESS || !"search_songs".equals(observation.toolName())) {
                continue;
            }
            String keyword = normalizedKeyword(text(observation.arguments(), "keyword"));
            if (!keyword.isBlank()) {
                keywords.add(keyword);
            }
        }
        return keywords;
    }

    private Set<String> knownPlaylistIds(AgentLoopState state) {
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(providerIdsInText(state.userMessage() == null ? "" : state.userMessage()));
        for (AgentObservation observation : state.observations()) {
            ids.addAll(playlistIdsFromResultJson(observation.resultJson()));
        }
        return ids;
    }

    private Set<String> providerIdsInText(String value) {
        Set<String> ids = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return ids;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("qqmusic:[A-Za-z0-9:_-]+").matcher(value);
        while (matcher.find()) {
            ids.add(matcher.group());
        }
        return ids;
    }

    private Set<String> songIdsFromResultJson(String resultJson) {
        Set<String> ids = new LinkedHashSet<>();
        try {
            JsonNode root = objectMapper.readTree(resultJson == null ? "{}" : resultJson);
            JsonNode songs = root.path("songs");
            if (songs.isArray()) {
                for (JsonNode song : songs) {
                    addTextId(ids, song.path("id").asText(""));
                }
            }
            JsonNode song = root.path("song");
            if (song.isObject()) {
                addTextId(ids, song.path("id").asText(""));
            }
            JsonNode lyrics = root.path("lyrics");
            if (lyrics.isObject()) {
                addTextId(ids, lyrics.path("songId").asText(""));
            }
            JsonNode comments = root.path("comments");
            if (comments.isArray()) {
                for (JsonNode comment : comments) {
                    addTextId(ids, comment.path("songId").asText(""));
                }
            }
        } catch (Exception ignored) {
            return Set.of();
        }
        return ids;
    }

    private Set<String> playlistIdsFromResultJson(String resultJson) {
        Set<String> ids = new LinkedHashSet<>();
        try {
            JsonNode root = objectMapper.readTree(resultJson == null ? "{}" : resultJson);
            JsonNode playlists = root.path("playlists");
            if (playlists.isArray()) {
                for (JsonNode playlist : playlists) {
                    addTextId(ids, playlist.path("id").asText(""));
                }
            }
        } catch (Exception ignored) {
            return Set.of();
        }
        return ids;
    }

    private void addSongId(Set<String> ids, Song song) {
        if (song != null) {
            addTextId(ids, song.id());
        }
    }

    private void addTextId(Set<String> ids, String value) {
        if (value != null && value.startsWith("qqmusic:")) {
            ids.add(value);
        }
    }

    private boolean requiresSongId(String toolName) {
        return "get_song_detail".equals(toolName) || "get_lyrics".equals(toolName) || "get_hot_comments".equals(toolName);
    }

    private String stepId(int step) {
        return "loop.step." + (step + 1);
    }

    private String callKey(AgentStepAction action) {
        return action.toolName() + "|" + new java.util.TreeMap<>(action.arguments()).toString();
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value instanceof String text ? text.strip() : "";
    }

    private String normalizedKeyword(String value) {
        return value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
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
