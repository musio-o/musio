package com.musio.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.musio.agent.AgentRunContext;
import com.musio.agent.AgentToolExecutor;
import com.musio.agent.AgentRequiredOutcome;
import com.musio.agent.ConfirmationService;
import com.musio.agent.capability.AgentCapabilityExecutor;
import com.musio.agent.capability.AgentCapabilityManifest;
import com.musio.agent.capability.AgentCapabilityRegistry;
import com.musio.agent.capability.AgentCapabilityValidationResult;
import com.musio.agent.capability.MusioPlaylistCapabilityExecutor;
import com.musio.agent.recommendation.RecommendationSlot;
import com.musio.agent.recommendation.RecommendationSlots;
import com.musio.agent.trace.AgentTracePublisher;
import com.musio.config.MusioConfig;
import com.musio.events.AgentEventBus;
import com.musio.memory.context.MemoryEvidence;
import com.musio.memory.context.MemoryType;
import com.musio.model.AgentEvent;
import com.musio.model.ChatConfirmation;
import com.musio.model.PendingConfirmation;
import com.musio.model.Song;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AgentLoopRunner {
    private static final Logger log = LoggerFactory.getLogger(AgentLoopRunner.class);
    private static final int MAX_STEPS = 5;
    private static final int CONFIRMATION_TIMEOUT_SECONDS = 300;

    private final AgentStepPlanner stepPlanner;
    private final AgentCapabilityExecutor capabilityExecutor;
    private final AgentObservationBuilder observationBuilder;
    private final ObjectMapper objectMapper;
    private final AgentCapabilityRegistry capabilityRegistry;
    private final AgentTracePublisher tracePublisher;
    private final AgentEventBus eventBus;
    private final ConfirmationService confirmationService;

    public AgentLoopRunner(
            AgentStepPlanner stepPlanner,
            AgentToolExecutor toolExecutor,
            AgentObservationBuilder observationBuilder,
            ObjectMapper objectMapper
    ) {
        this(stepPlanner, observationBuilder, objectMapper, new AgentCapabilityRegistry(), new AgentCapabilityExecutor(toolExecutor, null), null, null, null);
    }

    public AgentLoopRunner(
            AgentStepPlanner stepPlanner,
            AgentToolExecutor toolExecutor,
            AgentObservationBuilder observationBuilder,
            ObjectMapper objectMapper,
            AgentCapabilityRegistry capabilityRegistry,
            MusioPlaylistCapabilityExecutor musioPlaylistCapabilityExecutor
    ) {
        this(stepPlanner, observationBuilder, objectMapper, capabilityRegistry, new AgentCapabilityExecutor(toolExecutor, musioPlaylistCapabilityExecutor), null, null, null);
    }

    @Autowired
    public AgentLoopRunner(
            AgentStepPlanner stepPlanner,
            AgentObservationBuilder observationBuilder,
            ObjectMapper objectMapper,
            AgentCapabilityRegistry capabilityRegistry,
            AgentCapabilityExecutor capabilityExecutor,
            AgentTracePublisher tracePublisher,
            AgentEventBus eventBus,
            ConfirmationService confirmationService
    ) {
        this.stepPlanner = stepPlanner;
        this.capabilityExecutor = capabilityExecutor;
        this.observationBuilder = observationBuilder;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.capabilityRegistry = capabilityRegistry == null ? new AgentCapabilityRegistry() : capabilityRegistry;
        this.tracePublisher = tracePublisher;
        this.eventBus = eventBus;
        this.confirmationService = confirmationService;
    }

    public AgentLoopRunner(
            AgentStepPlanner stepPlanner,
            AgentObservationBuilder observationBuilder,
            ObjectMapper objectMapper,
            AgentCapabilityRegistry capabilityRegistry,
            AgentCapabilityExecutor capabilityExecutor
    ) {
        this(stepPlanner, observationBuilder, objectMapper, capabilityRegistry, capabilityExecutor, null, null, null);
    }

    public AgentLoopRunner(
            AgentStepPlanner stepPlanner,
            AgentObservationBuilder observationBuilder,
            ObjectMapper objectMapper,
            AgentCapabilityRegistry capabilityRegistry,
            AgentCapabilityExecutor capabilityExecutor,
            AgentTracePublisher tracePublisher
    ) {
        this(stepPlanner, observationBuilder, objectMapper, capabilityRegistry, capabilityExecutor, tracePublisher, null, null);
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
        AgentLoopState state = withMemoryCacheObservations(initialState);
        if (requiredOutcomesSatisfied(state)) {
            return outcome(AgentLoopOutcomeType.COMPLETED, state, "memory_cache_completion");
        }
        Set<String> executedCalls = new LinkedHashSet<>();
        int step = 0;
        for (AgentStepAction action : safeInitialActions(initialActions)) {
            if (step >= MAX_STEPS) {
                return outcome(AgentLoopOutcomeType.MAX_STEPS, state, "max_steps");
            }
            publishLoopAction(state, step, action);
            state = executeToolAction(state, step, action, executedCalls);
            if (shouldFinishAfterTool(state, action)) {
                return outcome(AgentLoopOutcomeType.COMPLETED, state, "tool_completion");
            }
            step++;
        }
        for (; step < MAX_STEPS; step++) {
            publishLoopThinking(state, step);
            AgentStepAction action = safeAction(stepPlanner.nextAction(ai, state));
            action = normalizeReadAction(state, action);
            publishLoopAction(state, step, action);
            if (action.action() == AgentStepActionType.FINAL_ANSWER) {
                AgentStepAction recoveryAction = recoveryActionForMissingReadOutcome(state);
                if (recoveryAction != null) {
                    publishLoopAction(state, step, recoveryAction);
                    state = executeToolAction(state, step, recoveryAction, executedCalls);
                    if (shouldFinishAfterTool(state, recoveryAction)) {
                        return outcome(AgentLoopOutcomeType.COMPLETED, state, "tool_completion");
                    }
                    continue;
                }
                return outcome(AgentLoopOutcomeType.COMPLETED, state, action.reason());
            }
            if (action.action() == AgentStepActionType.REQUEST_CONFIRMATION || action.action() == AgentStepActionType.UNSUPPORTED) {
                if (action.action() == AgentStepActionType.REQUEST_CONFIRMATION) {
                    AgentStepAction localWriteAction = localPlaylistWriteRecoveryAction(state);
                    if (localWriteAction != null) {
                        publishLoopAction(state, step, localWriteAction);
                        state = executeToolAction(state, step, localWriteAction, executedCalls);
                        if (shouldFinishAfterTool(state, localWriteAction)) {
                            return outcome(AgentLoopOutcomeType.COMPLETED, state, "tool_completion");
                        }
                        continue;
                    }
                }
                AgentStepAction recoveryAction = recoveryActionForMissingReadOutcome(state);
                if (recoveryAction != null) {
                    publishLoopAction(state, step, recoveryAction);
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
            action = normalizeReadAction(state, action);
            state = executeToolAction(state, step, action, executedCalls);
            if (shouldFinishAfterTool(state, action)) {
                return outcome(AgentLoopOutcomeType.COMPLETED, state, "tool_completion");
            }
        }
        return outcome(AgentLoopOutcomeType.MAX_STEPS, state, "max_steps");
    }

    private AgentStepAction safeAction(AgentStepAction action) {
        return action == null ? AgentStepAction.finalAnswer("step_planner_returned_null", 0.0) : action;
    }

    private AgentLoopState withMemoryCacheObservations(AgentLoopState state) {
        if (!needsCommentCacheObservation(state)) {
            return state;
        }
        List<AgentObservation> observations = commentCacheObservations(state);
        if (!observations.isEmpty()) {
            log.info(
                    "AGENT_LOOP_MEMORY_CACHE_HIT runId={} userId={} outcome=COMMENTS songIds={}",
                    state.runId(),
                    state.userId(),
                    observations.stream()
                            .map(observation -> observation.arguments().get("songId"))
                            .toList()
            );
        }
        AgentLoopState next = state;
        for (AgentObservation observation : observations) {
            next = next.withObservation(observation);
        }
        return next;
    }

    private boolean needsCommentCacheObservation(AgentLoopState state) {
        if (state == null || state.goal() == null || state.memoryContext() == null || state.memoryContext().evidence().isEmpty()) {
            return false;
        }
        return state.goal().requiredOutcomes().contains(AgentRequiredOutcome.COMMENTS)
                || "comments".equals(state.goal().taskType());
    }

    private List<AgentObservation> commentCacheObservations(AgentLoopState state) {
        List<String> targetIds = readTargetSongIds(state);
        Set<String> readIds = successfulReadSongIds(state, "get_hot_comments");
        List<AgentObservation> observations = new ArrayList<>();
        for (MemoryEvidence evidence : state.memoryContext().evidence()) {
            if (!isCommentCacheEvidence(evidence)) {
                continue;
            }
            String summary = commentCacheSummary(evidence.text());
            if (summary.isBlank()) {
                continue;
            }
            String songId = cacheEvidenceSongId(evidence);
            if (songId.isBlank() && targetIds.size() == 1) {
                songId = targetIds.getFirst();
            }
            if (songId.isBlank()) {
                continue;
            }
            if (!targetIds.isEmpty() && !targetIds.contains(songId)) {
                continue;
            }
            if (readIds.contains(songId)) {
                continue;
            }
            observations.add(commentCacheObservation(songId, summary, evidence, observations.size()));
            readIds.add(songId);
        }
        return List.copyOf(observations);
    }

    private boolean isCommentCacheEvidence(MemoryEvidence evidence) {
        return evidence != null
                && evidence.type() == MemoryType.MUSIC_CACHE
                && evidence.text() != null
                && evidence.text().contains("评论缓存");
    }

    private AgentObservation commentCacheObservation(String songId, String summary, MemoryEvidence evidence, int index) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("songId", songId);
        arguments.put("limit", 10);
        return new AgentObservation(
                "memory.cache.comments." + (index + 1),
                "get_hot_comments",
                arguments,
                AgentObservationStatus.SUCCESS,
                commentCacheResultJson(songId, summary, evidence),
                "get_hot_comments 命中评论缓存 songId=" + songId,
                List.of()
        );
    }

    private String commentCacheResultJson(String songId, String summary, MemoryEvidence evidence) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("source", "memory_cache");
        result.put("songId", songId);
        result.put("summary", summary);
        result.put("cachedAt", evidence.updatedAt().toString());
        if (!evidence.evidence().isBlank()) {
            result.put("evidence", evidence.evidence());
        }
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception ignored) {
            return "{\"success\":true,\"source\":\"memory_cache\",\"songId\":\""
                    + escapeJson(songId)
                    + "\",\"summary\":\""
                    + escapeJson(summary)
                    + "\"}";
        }
    }

    private String commentCacheSummary(String text) {
        String body = text == null ? "" : text.strip();
        int newline = body.indexOf('\n');
        if (newline >= 0 && body.substring(0, newline).contains("评论缓存")) {
            body = body.substring(newline + 1).strip();
        }
        if (body.startsWith("{")) {
            return commentSummaryFromJson(body);
        }
        return body;
    }

    private String commentSummaryFromJson(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String summary = root.path("summary").asText("");
            if (!summary.isBlank()) {
                return summary.strip();
            }
            String message = root.path("message").asText("");
            if (!message.isBlank()) {
                return message.strip();
            }
            List<String> comments = new ArrayList<>();
            collectCommentTexts(root.path("comments"), comments);
            JsonNode commentResults = root.path("commentResults");
            if (commentResults.isArray()) {
                for (JsonNode item : commentResults) {
                    collectCommentTexts(item.path("comments"), comments);
                }
            }
            if (!comments.isEmpty()) {
                return "评论摘录：" + String.join("；", comments.stream().limit(5).toList());
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private void collectCommentTexts(JsonNode commentsNode, List<String> target) {
        if (commentsNode == null || !commentsNode.isArray() || target == null) {
            return;
        }
        for (JsonNode comment : commentsNode) {
            String text = comment.path("text").asText("");
            if (!text.isBlank()) {
                target.add(text.strip());
            }
        }
    }

    private String cacheEvidenceSongId(MemoryEvidence evidence) {
        if (evidence == null) {
            return "";
        }
        String sourceId = evidence.sourceId() == null ? "" : evidence.sourceId().strip();
        if (sourceId.contains(":")) {
            return sourceId;
        }
        String text = evidence.text() == null ? "" : evidence.text();
        String jsonSongId = extractJsonString(text, "\"songId\"");
        if (!jsonSongId.isBlank()) {
            return jsonSongId;
        }
        int idStart = text.indexOf("id=");
        if (idStart < 0) {
            return "";
        }
        idStart += 3;
        int end = idStart;
        while (end < text.length()) {
            char ch = text.charAt(end);
            if (Character.isWhitespace(ch) || ch == '；' || ch == '，' || ch == ',' || ch == '\n') {
                break;
            }
            end++;
        }
        return text.substring(idStart, end).strip();
    }

    private String extractJsonString(String text, String key) {
        if (text == null || key == null || key.isBlank()) {
            return "";
        }
        int keyIndex = text.indexOf(key);
        if (keyIndex < 0) {
            return "";
        }
        int colon = text.indexOf(':', keyIndex + key.length());
        if (colon < 0) {
            return "";
        }
        int quoteStart = text.indexOf('"', colon + 1);
        if (quoteStart < 0) {
            return "";
        }
        int quoteEnd = text.indexOf('"', quoteStart + 1);
        if (quoteEnd <= quoteStart) {
            return "";
        }
        return text.substring(quoteStart + 1, quoteEnd).strip();
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
            case LOCAL_PLAYLIST_WRITE -> localPlaylistWriteRecoveryAction(state);
            default -> null;
        };
    }

    private AgentStepAction normalizeReadAction(AgentLoopState state, AgentStepAction action) {
        if (action == null || action.action() != AgentStepActionType.TOOL_CALL) {
            return action;
        }
        List<String> playerStateIds = explicitPlayerStateReadTargetSongIds(state);
        if (!playerStateIds.isEmpty() && isReadableSongTool(action.toolName())) {
            return withReadSongIds(action, playerStateIds);
        }
        if (!isBatchReadableTool(action.toolName())) {
            return action;
        }
        List<String> unreadTargetIds = unreadReadTargetSongIds(state, action.toolName());
        if (unreadTargetIds.size() <= 1) {
            return action;
        }
        List<String> requestedIds = requestedSongIds(action);
        if (!requestedIds.isEmpty() && requestedIds.containsAll(unreadTargetIds)) {
            return action;
        }
        Map<String, Object> arguments = new LinkedHashMap<>(action.arguments() == null ? Map.of() : action.arguments());
        arguments.put("songIds", unreadTargetIds);
        arguments.remove("songId");
        if ("get_hot_comments".equals(action.toolName()) && !arguments.containsKey("limit")) {
            arguments.put("limit", 10);
        }
        return new AgentStepAction(
                action.action(),
                action.toolName(),
                arguments,
                action.publicActivity(),
                action.confidence(),
                action.reason()
        );
    }

    private AgentStepAction withReadSongIds(AgentStepAction action, List<String> songIds) {
        if (action == null || songIds == null || songIds.isEmpty()) {
            return action;
        }
        Map<String, Object> arguments = new LinkedHashMap<>(action.arguments() == null ? Map.of() : action.arguments());
        if (isBatchReadableTool(action.toolName()) && songIds.size() > 1) {
            arguments.put("songIds", songIds);
            arguments.remove("songId");
        } else {
            arguments.put("songId", songIds.getFirst());
            arguments.remove("songIds");
        }
        if ("get_hot_comments".equals(action.toolName()) && !arguments.containsKey("limit")) {
            arguments.put("limit", 10);
        }
        return new AgentStepAction(
                action.action(),
                action.toolName(),
                arguments,
                action.publicActivity(),
                action.confidence(),
                action.reason()
        );
    }

    private boolean isReadableSongTool(String toolName) {
        return "get_hot_comments".equals(toolName) || "get_lyrics".equals(toolName) || "get_song_detail".equals(toolName);
    }

    private boolean isBatchReadableTool(String toolName) {
        return "get_hot_comments".equals(toolName) || "get_lyrics".equals(toolName);
    }

    private AgentStepAction localPlaylistWriteRecoveryAction(AgentLoopState state) {
        if (state == null || state.goal() == null || !state.goal().requiredOutcomes().contains(AgentRequiredOutcome.LOCAL_PLAYLIST_WRITE)) {
            return null;
        }
        if (requiredOutcomeSatisfied(state, AgentRequiredOutcome.LOCAL_PLAYLIST_WRITE)) {
            return null;
        }
        if (localPlaylistWriteDeclinedObserved(state)) {
            return null;
        }
        AgentCapabilityManifest manifest = manifestFor(state);
        if (manifest == null || !manifest.allows(AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST)) {
            return null;
        }
        List<String> songIds = localPlaylistWriteTargetSongIds(state, null);
        if (songIds.isEmpty()) {
            return null;
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("playlistId", "default");
        if (songIds.size() == 1) {
            arguments.put("songId", songIds.getFirst());
        } else {
            arguments.put("songIds", songIds);
        }
        return new AgentStepAction(
                AgentStepActionType.TOOL_CALL,
                AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST,
                arguments,
                "收藏到 Musio 歌单",
                1.0,
                "required_outcome_recovery"
        );
    }

    private boolean localPlaylistWriteDeclinedObserved(AgentLoopState state) {
        if (state == null || state.observations() == null) {
            return false;
        }
        return state.observations().stream()
                .anyMatch(observation -> observation.status() == AgentObservationStatus.SKIPPED
                        && AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST.equals(observation.toolName())
                        && observation.resultJson() != null
                        && observation.resultJson().contains("confirmation_"));
    }

    private AgentStepAction readBySongIdsAction(AgentLoopState state, AgentCapabilityManifest manifest, String toolName, String publicActivity, String reason) {
        if (manifest == null || !manifest.allows(toolName) || readOutcomeSatisfied(state, toolName)) {
            return null;
        }
        List<String> songIds = unreadReadTargetSongIds(state, toolName);
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
        if (AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST.equals(action.toolName()) && !preConfirmedLocalPlaylistWrite(action)) {
            ConfirmationDecision decision = confirmLocalPlaylistWrite(state, step, action);
            if (!decision.approved()) {
                return appendSkipped(state, step, action, decision.reason());
            }
            action = decision.action();
        }
        ValidationResult validation = validate(action, state, executedCalls);
        if (!validation.valid()) {
            return appendSkipped(state, step, action, validation.reason());
        }
        String resultJson = executeTool(state, action);
        AgentObservation observation = observationBuilder.build(stepId(step), action.toolName(), action.arguments(), resultJson);
        executedCalls.add(callKey(action));
        publishLoopObservation(state, step, observation);
        return state.withObservation(observation);
    }

    private boolean preConfirmedLocalPlaylistWrite(AgentStepAction action) {
        return action != null && "pending_local_playlist_confirmation".equals(action.reason());
    }

    private ConfirmationDecision confirmLocalPlaylistWrite(AgentLoopState state, int step, AgentStepAction action) {
        if (eventBus == null || confirmationService == null || state == null || state.runId().isBlank()) {
            return ConfirmationDecision.approved(action);
        }
        action = targetedLocalPlaylistWriteAction(state, action);
        String actionId = "%s:%s".formatted(AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST, stepId(step));
        ChatConfirmation confirmation = localPlaylistConfirmation(actionId, state, action);
        log.info(
                "AGENT_CONFIRMATION_REQUEST runId={} userId={} actionId={} toolName={} songIds={}",
                state.runId(),
                state.userId(),
                actionId,
                action.toolName(),
                confirmation.songs().stream().map(Song::id).toList()
        );
        confirmationService.prepare(state.runId(), actionId);
        eventBus.publish(state.runId(), AgentEvent.of("confirmation_request", Map.of(
                "runId", state.runId(),
                "confirmation", confirmation
        )));
        PendingConfirmation result = confirmationService.await(state.runId(), actionId, CONFIRMATION_TIMEOUT_SECONDS);
        if (result == null || !result.approved()) {
            return ConfirmationDecision.rejected(confirmationRejectReason(result));
        }
        return ConfirmationDecision.approved(new AgentStepAction(
                action.action(),
                action.toolName(),
                confirmedArguments(action.arguments(), result),
                action.publicActivity(),
                action.confidence(),
                action.reason()
        ));
    }

    private ChatConfirmation localPlaylistConfirmation(String actionId, AgentLoopState state, AgentStepAction action) {
        List<Song> songs = confirmationSongs(state, action);
        String title = songs.size() > 1 ? "选择要收藏的歌曲" : "收藏到 Musio 歌单";
        String description = songs.size() > 1
                ? "已为你准备 %s 首待加入本地 Musio 默认歌单的歌曲。".formatted(songs.size())
                : songs.isEmpty() ? "确认后会加入本地 Musio 默认歌单。" : "将《%s》加入本地 Musio 默认歌单。".formatted(songs.getFirst().title());
        return new ChatConfirmation(
                actionId,
                "local_playlist_add",
                title,
                description,
                "确认收藏",
                "取消收藏",
                songs.isEmpty() ? null : songs.getFirst(),
                songs,
                songs.size() > 1 ? "multiple" : "single",
                songs.stream().map(Song::id).toList()
        );
    }

    private List<Song> confirmationSongs(AgentLoopState state, AgentStepAction action) {
        Map<String, Song> songsById = new LinkedHashMap<>();
        if (state != null && state.observations() != null) {
            for (AgentObservation observation : state.observations()) {
                if (observation.status() == AgentObservationStatus.SUCCESS) {
                    observation.songs().forEach(song -> addSongCandidate(songsById, song));
                }
            }
        }
        List<String> requestedIds = localPlaylistWriteTargetSongIds(state, action);
        if (!requestedIds.isEmpty()) {
            return requestedIds.stream()
                    .map(songsById::get)
                    .filter(song -> song != null)
                    .toList();
        }
        return List.copyOf(songsById.values());
    }

    private List<String> requestedSongIds(AgentStepAction action) {
        if (action == null || action.arguments() == null) {
            return List.of();
        }
        Object songIds = action.arguments().get("songIds");
        if (songIds instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(id -> !id.isBlank())
                    .map(String::strip)
                    .toList();
        }
        Object songId = action.arguments().get("songId");
        if (songId instanceof String id && !id.isBlank()) {
            return List.of(id.strip());
        }
        return List.of();
    }

    private AgentStepAction targetedLocalPlaylistWriteAction(AgentLoopState state, AgentStepAction action) {
        List<String> targetSongIds = localPlaylistWriteTargetSongIds(state, action);
        if (action == null || targetSongIds.isEmpty()) {
            return action;
        }
        Map<String, Object> arguments = new LinkedHashMap<>(action.arguments());
        if (targetSongIds.size() == 1) {
            arguments.put("songId", targetSongIds.getFirst());
            arguments.remove("songIds");
        } else {
            arguments.put("songIds", targetSongIds);
            arguments.remove("songId");
        }
        return new AgentStepAction(
                action.action(),
                action.toolName(),
                arguments,
                action.publicActivity(),
                action.confidence(),
                action.reason()
        );
    }

    private List<String> localPlaylistWriteTargetSongIds(AgentLoopState state, AgentStepAction action) {
        List<String> explicitIds = requestedSongIds(action);
        if (explicitIds.isEmpty()) {
            explicitIds = requestedSongIdsByIndex(state, action);
        }
        List<String> scopedIds = scopedLocalPlaylistWriteSongIds(state);
        if (!explicitIds.isEmpty()) {
            if (scopedIds.isEmpty() || localPlaylistWriteAllTargetsRequested(state)) {
                return explicitIds;
            }
            List<String> intersection = explicitIds.stream()
                    .filter(scopedIds::contains)
                    .toList();
            return intersection.isEmpty() ? scopedIds : intersection;
        }
        if (!scopedIds.isEmpty()) {
            return scopedIds;
        }
        List<String> observedIds = observedSongIds(state);
        int fallbackCount = localPlaylistWriteFallbackCount(state, observedIds.size());
        return observedIds.stream()
                .limit(fallbackCount <= 0 ? 1 : fallbackCount)
                .toList();
    }

    private List<String> scopedLocalPlaylistWriteSongIds(AgentLoopState state) {
        if (state == null || state.goal() == null) {
            return List.of();
        }
        List<String> observedIds = observedSongIds(state);
        if (observedIds.isEmpty()) {
            return List.of();
        }
        if (localPlaylistWriteAllTargetsRequested(state)) {
            return observedIds;
        }
        List<RecommendationSlot> slots = RecommendationSlots.normalize(state.goal().recommendationSlots());
        if (slots.isEmpty()) {
            int fallbackCount = localPlaylistWriteFallbackCount(state, observedIds.size());
            return observedIds.stream().limit(fallbackCount <= 0 ? 1 : fallbackCount).toList();
        }
        List<RecommendationSlot> targetSlots = localPlaylistWriteTargetSlots(state, slots);
        if (targetSlots.isEmpty()) {
            return List.of();
        }
        Map<String, List<String>> songIdsBySlot = recommendationSongIdsBySlot(state);
        LinkedHashSet<String> targetIds = new LinkedHashSet<>();
        for (RecommendationSlot slot : targetSlots) {
            List<String> slotSongIds = songIdsBySlot.getOrDefault(slot.slotId(), List.of());
            slotSongIds.stream()
                    .limit(slot.count())
                    .forEach(targetIds::add);
        }
        if (!targetIds.isEmpty()) {
            return List.copyOf(targetIds);
        }
        int fallbackCount = targetSlots.stream().mapToInt(RecommendationSlot::count).sum();
        return observedIds.stream()
                .limit(fallbackCount <= 0 ? 1 : fallbackCount)
                .toList();
    }

    private List<RecommendationSlot> localPlaylistWriteTargetSlots(AgentLoopState state, List<RecommendationSlot> slots) {
        if (slots == null || slots.isEmpty()) {
            return List.of();
        }
        if (localPlaylistWriteAllTargetsRequested(state) || writeIntentOccurrenceCount(localPlaylistWriteText(state)) >= slots.size()) {
            return slots;
        }
        String text = localPlaylistWriteText(state);
        int writeIndex = firstWriteIntentIndex(text);
        if (writeIndex < 0) {
            return slots.size() == 1 ? slots : List.of(slots.getFirst());
        }
        List<RecommendationSlot> targetsBeforeWrite = new ArrayList<>();
        for (RecommendationSlot slot : slots) {
            String target = normalizeLocalPlaylistText(slot.target());
            int targetIndex = target.isBlank() ? -1 : text.indexOf(target);
            if (targetIndex >= 0 && targetIndex <= writeIndex) {
                targetsBeforeWrite.add(slot);
            }
        }
        if (!targetsBeforeWrite.isEmpty()) {
            return List.copyOf(targetsBeforeWrite);
        }
        return slots.size() == 1 ? slots : List.of(slots.getFirst());
    }

    private Map<String, List<String>> recommendationSongIdsBySlot(AgentLoopState state) {
        if (state == null || state.observations() == null) {
            return Map.of();
        }
        Map<String, LinkedHashSet<String>> values = new LinkedHashMap<>();
        for (AgentObservation observation : state.observations()) {
            if (observation.status() != AgentObservationStatus.SUCCESS
                    || !AgentCapabilityRegistry.RECOMMEND_SONGS.equals(observation.toolName())
                    || observation.resultJson().isBlank()) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(observation.resultJson());
                readRecommendationSongSlots(root.path("songs"), values);
                readRecommendationSongSlots(root.path("recommendations"), values);
            } catch (Exception ignored) {
                // Observation songs are still available as an order-based fallback.
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : values.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    private void readRecommendationSongSlots(JsonNode songsNode, Map<String, LinkedHashSet<String>> values) {
        if (songsNode == null || !songsNode.isArray()) {
            return;
        }
        for (JsonNode songNode : songsNode) {
            String slotId = songNode.path("slotId").asText("");
            String songId = songNode.path("id").asText(songNode.path("songId").asText(""));
            if (!slotId.isBlank() && !songId.isBlank()) {
                values.computeIfAbsent(slotId.strip(), ignored -> new LinkedHashSet<>()).add(songId.strip());
            }
        }
    }

    private boolean localPlaylistWriteAllTargetsRequested(AgentLoopState state) {
        String text = localPlaylistWriteText(state);
        return containsAny(text, "全部加入", "全都加入", "都加入", "全部写入", "全都写入", "都写入", "全部收藏", "全都收藏", "都收藏", "这几首加入", "这些歌加入", "这几首收藏", "这些歌收藏");
    }

    private int localPlaylistWriteFallbackCount(AgentLoopState state, int availableSongCount) {
        if (availableSongCount <= 1) {
            return availableSongCount;
        }
        if (localPlaylistWriteAllTargetsRequested(state)) {
            return availableSongCount;
        }
        String text = localPlaylistWriteText(state);
        if (localPlaylistWriteSingleTargetRequested(text)) {
            return 1;
        }
        if (state != null && state.goal() != null && state.goal().localWriteIntent()) {
            int goalCount = state.goal().recommendationTotalCount() > 0
                    ? state.goal().recommendationTotalCount()
                    : state.goal().requestedSongCount();
            if (goalCount > 1) {
                return Math.min(availableSongCount, goalCount);
            }
        }
        int writeCount = writeIntentOccurrenceCount(text);
        return Math.max(1, Math.min(availableSongCount, writeCount));
    }

    private List<String> requestedSongIdsByIndex(AgentLoopState state, AgentStepAction action) {
        List<Integer> indexes = requestedSongIndexes(action);
        if (indexes.isEmpty()) {
            return List.of();
        }
        List<String> observedIds = observedSongIds(state);
        if (observedIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Integer index : indexes) {
            if (index != null && index >= 1 && index <= observedIds.size()) {
                ids.add(observedIds.get(index - 1));
            }
        }
        return List.copyOf(ids);
    }

    private List<Integer> requestedSongIndexes(AgentStepAction action) {
        if (action == null || action.arguments() == null) {
            return List.of();
        }
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
        Object songIndexes = action.arguments().get("songIndexes");
        if (songIndexes instanceof List<?> list) {
            list.stream()
                    .map(this::integerValue)
                    .filter(index -> index != null && index > 0)
                    .forEach(indexes::add);
        }
        Integer songIndex = integerValue(action.arguments().get("songIndex"));
        if (songIndex != null && songIndex > 0) {
            indexes.add(songIndex);
        }
        return List.copyOf(indexes);
    }

    private Integer integerValue(Object value) {
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

    private boolean localPlaylistWriteSingleTargetRequested(String text) {
        int ordinalTargets = 0;
        for (String ordinal : List.of("第一首", "第二首", "第三首", "第四首", "第五首", "第1首", "第2首", "第3首", "第4首", "第5首")) {
            if (text != null && text.contains(ordinal)) {
                ordinalTargets++;
            }
        }
        if (ordinalTargets > 1) {
            return false;
        }
        if (ordinalTargets == 1) {
            return true;
        }
        return containsAny(text, "这首", "这歌", "这首歌", "刚才那首", "上一首", "上首", "其中一首");
    }

    private String localPlaylistWriteText(AgentLoopState state) {
        if (state == null || state.goal() == null) {
            return normalizeLocalPlaylistText(state == null ? "" : state.userMessage());
        }
        String effectiveRequest = state.goal().effectiveRequest();
        return normalizeLocalPlaylistText(effectiveRequest == null || effectiveRequest.isBlank() ? state.userMessage() : effectiveRequest);
    }

    private String normalizeLocalPlaylistText(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
    }

    private boolean containsAny(String value, String... needles) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private int firstWriteIntentIndex(String text) {
        List<Integer> indexes = writeIntentIndexes(text);
        return indexes.isEmpty() ? -1 : indexes.getFirst();
    }

    private int writeIntentOccurrenceCount(String text) {
        return writeIntentIndexes(text).size();
    }

    private List<Integer> writeIntentIndexes(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Integer> indexes = new ArrayList<>();
        for (String verb : List.of("加入", "写入", "添加", "保存", "收藏")) {
            int index = 0;
            while ((index = text.indexOf(verb, index)) >= 0) {
                if ("收藏".equals(verb) || text.substring(index, Math.min(text.length(), index + 18)).contains("歌单")) {
                    indexes.add(index);
                }
                index += verb.length();
            }
        }
        return indexes.stream().distinct().sorted().toList();
    }

    private void addSongCandidate(Map<String, Song> songsById, Song song) {
        if (song != null && song.id() != null && !song.id().isBlank()) {
            songsById.putIfAbsent(song.id().strip(), song);
        }
    }

    private Map<String, Object> confirmedArguments(Map<String, Object> originalArguments, PendingConfirmation confirmation) {
        Map<String, Object> arguments = new LinkedHashMap<>(originalArguments == null ? Map.of() : originalArguments);
        if (confirmation.editedInput() != null) {
            Object selectedSongIds = confirmation.editedInput().get("selectedSongIds");
            if (selectedSongIds instanceof List<?> list && !list.isEmpty()) {
                List<String> ids = list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .filter(id -> !id.isBlank())
                        .map(String::strip)
                        .toList();
                if (ids.size() == 1) {
                    arguments.put("songId", ids.getFirst());
                    arguments.remove("songIds");
                } else if (!ids.isEmpty()) {
                    arguments.put("songIds", ids);
                    arguments.remove("songId");
                }
            }
        }
        return Map.copyOf(arguments);
    }

    private String confirmationRejectReason(PendingConfirmation confirmation) {
        if (confirmation != null && confirmation.editedInput() != null && confirmation.editedInput().get("reason") instanceof String reason && !reason.isBlank()) {
            return "confirmation_" + reason.strip();
        }
        return "confirmation_cancelled";
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
                && manifestFor(state).allows(AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST)
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
            case COMMENTS -> readOutcomeSatisfied(state, "get_hot_comments");
            case LYRICS -> readOutcomeSatisfied(state, "get_lyrics");
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

    private boolean readOutcomeSatisfied(AgentLoopState state, String toolName) {
        List<String> targetIds = readTargetSongIds(state);
        if (targetIds.isEmpty()) {
            return successfulToolObserved(state, toolName);
        }
        return successfulReadSongIds(state, toolName).containsAll(targetIds);
    }

    private List<String> unreadReadTargetSongIds(AgentLoopState state, String toolName) {
        List<String> targetIds = readTargetSongIds(state);
        if (targetIds.isEmpty()) {
            targetIds = observedSongIds(state);
        }
        if (targetIds.isEmpty()) {
            return List.of();
        }
        Set<String> readIds = successfulReadSongIds(state, toolName);
        return targetIds.stream()
                .filter(id -> !readIds.contains(id))
                .toList();
    }

    private List<String> readTargetSongIds(AgentLoopState state) {
        List<String> playerStateIds = explicitPlayerStateReadTargetSongIds(state);
        if (!playerStateIds.isEmpty()) {
            return playerStateIds;
        }
        Set<String> recommendationIds = successfulRecommendationSongIds(state);
        if (!recommendationIds.isEmpty()) {
            return List.copyOf(recommendationIds);
        }
        return observedSongIds(state);
    }

    private List<String> explicitPlayerStateReadTargetSongIds(AgentLoopState state) {
        if (state == null || state.memoryContext() == null || state.memoryContext().evidence().isEmpty()) {
            return List.of();
        }
        String request = normalizeText((state.userMessage() == null ? "" : state.userMessage())
                + " "
                + (state.goal() == null ? "" : state.goal().effectiveRequest()));
        if (!mentionsExplicitPlayerState(request)) {
            return List.of();
        }
        for (MemoryEvidence evidence : state.memoryContext().evidence()) {
            if (evidence == null || evidence.type() != MemoryType.CURRENT_STATE) {
                continue;
            }
            if (mentionsQueuePrevious(request)) {
                String previousId = extractQueuePreviousSongId(evidence.text());
                if (!previousId.isBlank()) {
                    return List.of(previousId);
                }
            }
            if (mentionsCurrentPlayback(request) && !evidence.sourceId().isBlank() && !"player".equals(evidence.sourceId())) {
                return List.of(evidence.sourceId());
            }
        }
        return List.of();
    }

    private boolean mentionsExplicitPlayerState(String normalized) {
        return mentionsCurrentPlayback(normalized) || mentionsQueuePrevious(normalized) || containsAny(normalized, "播放器里", "播放器中", "队列里", "播放队列");
    }

    private boolean mentionsCurrentPlayback(String normalized) {
        return containsAny(normalized, "当前播放", "正在播放", "正在放", "正在播", "现在播放", "现在放", "现在播", "播放器里", "播放器中");
    }

    private boolean mentionsQueuePrevious(String normalized) {
        return containsAny(normalized, "队列上一首", "播放队列上一首");
    }

    private String extractQueuePreviousSongId(String text) {
        String value = text == null ? "" : text;
        int previousStart = value.indexOf("上一首=");
        if (previousStart < 0) {
            return "";
        }
        int idStart = value.indexOf(" id=", previousStart);
        if (idStart < 0) {
            return "";
        }
        idStart += 4;
        int end = idStart;
        while (end < value.length()) {
            char ch = value.charAt(end);
            if (Character.isWhitespace(ch) || ch == '；' || ch == '，' || ch == ',' || ch == '\n') {
                break;
            }
            end++;
        }
        return value.substring(idStart, end).strip();
    }

    private Set<String> successfulReadSongIds(AgentLoopState state, String toolName) {
        Set<String> ids = new LinkedHashSet<>();
        if (state == null || state.observations() == null || toolName == null || toolName.isBlank()) {
            return ids;
        }
        for (AgentObservation observation : state.observations()) {
            if (observation.status() != AgentObservationStatus.SUCCESS || !toolName.equals(observation.toolName())) {
                continue;
            }
            List<String> argumentIds = requestedSongIds(new AgentStepAction(
                    AgentStepActionType.TOOL_CALL,
                    toolName,
                    observation.arguments(),
                    "",
                    1.0,
                    ""
            ));
            if (!argumentIds.isEmpty()) {
                ids.addAll(argumentIds);
            }
        }
        return ids;
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
            Map<String, Integer> resolvedBySlot = resolvedRecommendationSlots(state);
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

    private Map<String, Integer> resolvedRecommendationSlots(AgentLoopState state) {
        if (state == null || state.observations() == null) {
            return Map.of();
        }
        Map<String, LinkedHashSet<String>> idsBySlot = new LinkedHashMap<>();
        Map<String, Integer> fallbackBySlot = new LinkedHashMap<>();
        for (AgentObservation observation : state.observations()) {
            if (observation.status() != AgentObservationStatus.SUCCESS
                    || !AgentCapabilityRegistry.RECOMMEND_SONGS.equals(observation.toolName())
                    || observation.resultJson().isBlank()) {
                continue;
            }
            try {
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(observation.resultJson());
                readRecommendationSlotSongIds(root.path("songs"), idsBySlot);
                readRecommendationSlotSongIds(root.path("recommendations"), idsBySlot);
                mergeSlotResultCounts(root.path("slotResults"), fallbackBySlot);
            } catch (Exception ignored) {
                // Slot coverage is an optimization; song-level recommendation counts remain available.
            }
        }
        if (!idsBySlot.isEmpty()) {
            Map<String, Integer> values = new LinkedHashMap<>();
            for (Map.Entry<String, LinkedHashSet<String>> entry : idsBySlot.entrySet()) {
                values.put(entry.getKey(), entry.getValue().size());
            }
            return Map.copyOf(values);
        }
        return Map.copyOf(fallbackBySlot);
    }

    private void readRecommendationSlotSongIds(JsonNode songsNode, Map<String, LinkedHashSet<String>> idsBySlot) {
        if (songsNode == null || !songsNode.isArray()) {
            return;
        }
        for (JsonNode songNode : songsNode) {
            String slotId = songNode.path("slotId").asText("");
            String songId = songNode.path("id").asText(songNode.path("songId").asText(""));
            if (!slotId.isBlank() && !songId.isBlank()) {
                idsBySlot.computeIfAbsent(slotId.strip(), ignored -> new LinkedHashSet<>()).add(songId.strip());
            }
        }
    }

    private void mergeSlotResultCounts(JsonNode slotResults, Map<String, Integer> resolvedBySlot) {
        if (slotResults == null || !slotResults.isArray()) {
            return;
        }
        for (JsonNode slotResult : slotResults) {
            String slotId = slotResult.path("slotId").asText("");
            int resolved = slotResult.path("resolved").asInt(0);
            if (!slotId.isBlank() && resolved > 0) {
                resolvedBySlot.merge(slotId.strip(), resolved, Integer::sum);
            }
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
        return manifest == null ? capabilityRegistry.readManifest() : manifest;
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
        publishLoopObservation(state, step, observation);
        return state.withObservation(observation);
    }

    private AgentLoopEvidence evidence(AgentLoopState state) {
        AgentLoopEvidence evidence = observationBuilder.evidence(
                state == null ? List.of() : state.observations(),
                completedTaskType(state == null ? List.of() : state.observations())
        );
        List<Song> displaySongs = displaySongs(state, evidence);
        if (displaySongs.equals(evidence.songs())) {
            return evidence;
        }
        Song targetSong = displaySongs.isEmpty() ? evidence.targetSong() : displaySongs.getFirst();
        return new AgentLoopEvidence(
                evidence.observations(),
                displaySongs,
                evidence.completedTaskType(),
                targetSong,
                evidence.observationSummaries()
        );
    }

    private List<Song> displaySongs(AgentLoopState state, AgentLoopEvidence evidence) {
        if (state == null || evidence == null || evidence.songs().isEmpty() || !hasRecommendationObservation(evidence)) {
            return evidence == null ? List.of() : evidence.songs();
        }
        int requestedCount = requestedDisplaySongCount(state);
        if (requestedCount <= 0 || evidence.songs().size() <= requestedCount) {
            return evidence.songs();
        }
        return evidence.songs().stream()
                .limit(requestedCount)
                .toList();
    }

    private boolean hasRecommendationObservation(AgentLoopEvidence evidence) {
        return evidence != null && evidence.observations().stream()
                .anyMatch(observation -> observation.status() == AgentObservationStatus.SUCCESS
                        && AgentCapabilityRegistry.RECOMMEND_SONGS.equals(observation.toolName()));
    }

    private int requestedDisplaySongCount(AgentLoopState state) {
        if (state == null) {
            return 0;
        }
        if (state.goal() != null && state.goal().recommendationTotalCount() > 0) {
            return state.goal().recommendationTotalCount();
        }
        return state.requestedSongCount();
    }

    private AgentLoopOutcome outcome(AgentLoopOutcomeType type, AgentLoopState state, String reason) {
        publishLoopFinished(state, type, reason);
        return new AgentLoopOutcome(type, state, evidence(state), reason);
    }

    private void publishLoopThinking(AgentLoopState state, int step) {
        if (!traceEnabled(state)) {
            return;
        }
        tracePublisher.publishLoopThinking(state.runId(), step, state.observations().size());
    }

    private void publishLoopAction(AgentLoopState state, int step, AgentStepAction action) {
        if (!traceEnabled(state)) {
            return;
        }
        tracePublisher.publishLoopAction(state.runId(), step, action);
    }

    private void publishLoopObservation(AgentLoopState state, int step, AgentObservation observation) {
        if (!traceEnabled(state)) {
            return;
        }
        tracePublisher.publishLoopObservation(state.runId(), step, observation);
    }

    private void publishLoopFinished(AgentLoopState state, AgentLoopOutcomeType type, String reason) {
        if (!traceEnabled(state)) {
            return;
        }
        tracePublisher.publishLoopFinished(state.runId(), type, reason, state.observations().size());
    }

    private boolean traceEnabled(AgentLoopState state) {
        return tracePublisher != null
                && AgentRunContext.traceEnabled()
                && state != null
                && state.runId() != null
                && !state.runId().isBlank();
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

    private record ConfirmationDecision(boolean approved, AgentStepAction action, String reason) {
        static ConfirmationDecision approved(AgentStepAction action) {
            return new ConfirmationDecision(true, action, "");
        }

        static ConfirmationDecision rejected(String reason) {
            return new ConfirmationDecision(false, null, reason == null || reason.isBlank() ? "confirmation_cancelled" : reason);
        }
    }
}
