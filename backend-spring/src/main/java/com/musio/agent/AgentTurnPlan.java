package com.musio.agent;

import com.musio.agent.recommendation.RecommendationSlot;
import com.musio.agent.recommendation.RecommendationSlots;

import java.util.List;
import java.util.Map;
import java.util.Set;

record AgentTurnPlan(
        TurnDisposition disposition,
        String taskType,
        String contextMode,
        String effectiveRequest,
        AgentTurnMemoryUse memoryUse,
        List<AgentToolCall> toolCalls,
        List<AgentRequiredOutcome> requiredOutcomes,
        List<RecommendationSlot> recommendationSlots,
        double confidence,
        String fallbackReason,
        List<String> avoidSongTitles
) {
    private static final Set<String> READ_ONLY_LOOP_TOOLS = Set.of(
            "search_songs",
            "get_user_music_profile",
            "get_song_detail",
            "get_lyrics",
            "get_hot_comments",
            "get_user_playlists",
            "get_playlist_songs"
    );
    private static final Set<String> LOCAL_WRITE_TOOLS = Set.of("add_song_to_musio_playlist");
    private static final Set<String> ACCOUNT_WRITE_TOOLS = Set.of();

    AgentTurnPlan(
            TurnDisposition disposition,
            String taskType,
            String contextMode,
            String effectiveRequest,
            AgentTurnMemoryUse memoryUse,
            List<AgentToolCall> toolCalls,
            double confidence,
            String fallbackReason
    ) {
        this(disposition, taskType, contextMode, effectiveRequest, memoryUse, toolCalls, List.of(), List.of(), confidence, fallbackReason, List.of());
    }

    AgentTurnPlan(
            TurnDisposition disposition,
            String taskType,
            String contextMode,
            String effectiveRequest,
            AgentTurnMemoryUse memoryUse,
            List<AgentToolCall> toolCalls,
            List<AgentRequiredOutcome> requiredOutcomes,
            double confidence,
            String fallbackReason
    ) {
        this(disposition, taskType, contextMode, effectiveRequest, memoryUse, toolCalls, requiredOutcomes, List.of(), confidence, fallbackReason, List.of());
    }

    AgentTurnPlan(
            TurnDisposition disposition,
            String taskType,
            String contextMode,
            String effectiveRequest,
            AgentTurnMemoryUse memoryUse,
            List<AgentToolCall> toolCalls,
            List<AgentRequiredOutcome> requiredOutcomes,
            List<RecommendationSlot> recommendationSlots,
            double confidence,
            String fallbackReason
    ) {
        this(disposition, taskType, contextMode, effectiveRequest, memoryUse, toolCalls, requiredOutcomes, recommendationSlots, confidence, fallbackReason, List.of());
    }

    AgentTurnPlan {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        requiredOutcomes = requiredOutcomes == null ? List.of() : List.copyOf(requiredOutcomes);
        recommendationSlots = RecommendationSlots.normalize(recommendationSlots);
        avoidSongTitles = cleanStrings(avoidSongTitles);
    }

    static AgentTurnPlan respondOnly(String effectiveRequest, double confidence, String fallbackReason) {
        return new AgentTurnPlan(
                TurnDisposition.RESPOND_ONLY,
                "chat",
                "new_task",
                safe(effectiveRequest),
                AgentTurnMemoryUse.none("本轮不需要读取短期任务记忆。"),
                List.of(),
                List.of(),
                List.of(),
                confidence,
                safe(fallbackReason),
                List.of()
        );
    }

    AgentToolPlan toToolPlan() {
        if (disposition != TurnDisposition.USE_TOOLS || toolCalls == null || toolCalls.isEmpty()) {
            return AgentToolPlan.empty();
        }
        List<AgentToolCall> executableCalls = toolCalls.stream()
                .filter(this::isReadOnlyLoopTool)
                .toList();
        return new AgentToolPlan(executableCalls, confidence);
    }

    AgentTaskContext toLegacyTaskContext(String originalMessage) {
        // 迁移期适配旧 composer / memory 结构；这里不能补自然语言场景特判。
        if (disposition != TurnDisposition.USE_TOOLS) {
            return AgentTaskContext.direct(originalMessage, confidence, "turn-planner");
        }
        ToolIntent toolIntent = firstToolIntent();
        String targetSongId = firstSongId();
        boolean followUp = List.of("follow_up", "retry", "refer_previous_song", "correction").contains(safe(contextMode));
        List<String> excludedTitles = avoidSongTitles.isEmpty() ? toolIntent.excludedTitles() : avoidSongTitles;
        return AgentTaskContext.agent(
                originalMessage,
                effectiveRequest.isBlank() ? originalMessage : effectiveRequest,
                toolIntent.keyword(),
                toolIntent.limit(),
                followUp,
                excludedTitles,
                confidence,
                "turn-planner",
                effectiveTaskType(),
                targetSongId,
                "",
                safe(contextMode).isBlank() ? "new_task" : safe(contextMode),
                memoryUse == null ? AgentTaskMemoryAccess.none("Turn planner 未声明任务记忆使用。") : memoryUse.toLegacyAccess()
        );
    }

    boolean usesTools() {
        return disposition == TurnDisposition.USE_TOOLS;
    }

    boolean hasLocalWriteTools() {
        return localWriteToolCalls().stream().findAny().isPresent()
                || requiredOutcomes.contains(AgentRequiredOutcome.LOCAL_PLAYLIST_WRITE);
    }

    boolean hasAccountWriteTools() {
        return nonBlankToolCalls().stream().anyMatch(this::isAccountWriteTool)
                || requiredOutcomes.contains(AgentRequiredOutcome.ACCOUNT_WRITE);
    }

    boolean hasOnlyLocalWriteTools() {
        List<AgentToolCall> executableCalls = nonBlankToolCalls();
        return !executableCalls.isEmpty() && executableCalls.stream().allMatch(this::isLocalWriteTool);
    }

    List<AgentToolCall> readOnlyLoopToolCalls() {
        return nonBlankToolCalls().stream()
                .filter(this::isReadOnlyLoopTool)
                .toList();
    }

    List<AgentToolCall> localWriteToolCalls() {
        return nonBlankToolCalls().stream()
                .filter(this::isLocalWriteTool)
                .toList();
    }

    boolean hasTool(String toolName) {
        return toolCalls != null && toolCalls.stream()
                .anyMatch(call -> call != null && toolName.equals(call.toolName()));
    }

    private String effectiveTaskType() {
        return safe(taskType).isBlank() ? "unknown" : safe(taskType);
    }

    private ToolIntent firstToolIntent() {
        if (toolCalls == null) {
            return new ToolIntent("", 0, List.of());
        }
        for (AgentToolCall call : toolCalls) {
            if (call == null || call.arguments() == null) {
                continue;
            }
            Map<String, Object> arguments = call.arguments();
            if ("search_songs".equals(call.toolName())) {
                return new ToolIntent(text(arguments, "keyword"), integer(arguments, "limit"), stringList(arguments, "excludedTitles"));
            }
        }
        return new ToolIntent("", 0, List.of());
    }

    private String firstSongId() {
        if (toolCalls == null) {
            return "";
        }
        for (AgentToolCall call : toolCalls) {
            if (call == null || call.arguments() == null) {
                continue;
            }
            String songId = text(call.arguments(), "songId");
            if (!songId.isBlank()) {
                return songId;
            }
        }
        return "";
    }

    private List<AgentToolCall> nonBlankToolCalls() {
        if (toolCalls == null) {
            return List.of();
        }
        return toolCalls.stream()
                .filter(call -> call != null && call.toolName() != null && !call.toolName().isBlank())
                .toList();
    }

    private boolean isReadOnlyLoopTool(AgentToolCall call) {
        return call != null && READ_ONLY_LOOP_TOOLS.contains(call.toolName());
    }

    private boolean isLocalWriteTool(AgentToolCall call) {
        return call != null && LOCAL_WRITE_TOOLS.contains(call.toolName());
    }

    private boolean isAccountWriteTool(AgentToolCall call) {
        return call != null && ACCOUNT_WRITE_TOOLS.contains(call.toolName());
    }

    private static String text(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value instanceof String text ? text.strip() : "";
    }

    private static int integer(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.strip());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static List<String> stringList(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
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

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static List<String> cleanStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .limit(30)
                .toList();
    }

    private record ToolIntent(String keyword, int limit, List<String> excludedTitles) {
    }
}

enum TurnDisposition {
    RESPOND_ONLY,
    USE_TOOLS,
    REQUEST_CONFIRMATION,
    UNSUPPORTED
}

record AgentTurnMemoryUse(
        boolean usesTaskMemory,
        List<String> usedFields,
        String reason
) {
    static AgentTurnMemoryUse none(String reason) {
        return new AgentTurnMemoryUse(false, List.of(), reason);
    }

    AgentTaskMemoryAccess toLegacyAccess() {
        if (!usesTaskMemory) {
            return AgentTaskMemoryAccess.none(reason);
        }
        List<String> fields = usedFields == null ? List.of() : usedFields;
        return new AgentTaskMemoryAccess(
                fields.contains("lastSearchKeyword"),
                fields.contains("lastResultSongs"),
                fields.contains("avoidSongTitles"),
                fields.contains("lastToolFailures"),
                reason == null || reason.isBlank() ? "Turn planner 声明需要读取短期任务记忆。" : reason
        );
    }

    String summary() {
        if (!usesTaskMemory) {
            return "none";
        }
        return String.join(",", usedFields == null ? List.of() : usedFields);
    }
}
