package com.musio.memory.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.AgentLlmLogger;
import com.musio.agent.ConversationHistoryMessage;
import com.musio.ai.SpringAiChatModelFactory;
import com.musio.config.MusioConfig;
import com.musio.model.AgentRecentRecommendedSong;
import com.musio.model.AgentTaskMemory;
import com.musio.model.AgentTaskRecommendationSlot;
import com.musio.model.AgentToolFailure;
import com.musio.model.PendingLocalPlaylistAdd;
import com.musio.model.Song;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class LlmMemoryRoutePlanner {
    private static final Logger log = LoggerFactory.getLogger(LlmMemoryRoutePlanner.class);
    private static final double MIN_CONFIDENCE = 0.55;
    private static final int HISTORY_PREVIEW_LIMIT = 6;
    private static final int VALUE_PREVIEW_LIMIT = 220;

    private final SpringAiChatModelFactory chatModelFactory;
    private final ObjectMapper objectMapper;
    private final MemoryReadPlanValidator validator;

    public LlmMemoryRoutePlanner(
            SpringAiChatModelFactory chatModelFactory,
            ObjectMapper objectMapper,
            MemoryReadPlanValidator validator
    ) {
        this.chatModelFactory = chatModelFactory;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public MemoryReadPlan route(MusioConfig.Ai ai, MemoryRouteRequest request) {
        if (chatModelFactory == null || ai == null || request == null) {
            return MemoryReadPlan.empty();
        }
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(instruction()),
                new UserMessage("""
                        当前用户输入：
                        %s

                        turnPlan：
                        taskType=%s
                        contextMode=%s
                        effectiveRequest=%s

                        agentGoal：
                        requiredOutcomes=%s
                        musicTask=%s
                        localWriteIntent=%s

                        短期任务记忆 preview（只含受控关键字段）：
                        %s

                        最近对话 preview（只含最近 %s 条截断消息）：
                        %s

                        允许读取的 MemoryType 和字段：
                        %s
                        """.formatted(
                        request.userMessage(),
                        request.taskType(),
                        request.contextMode(),
                        request.effectiveRequest(),
                        request.goal() == null ? List.of() : request.goal().requiredOutcomes(),
                        request.goal() != null && request.goal().musicTask(),
                        request.goal() != null && request.goal().localWriteIntent(),
                        taskMemoryPreview(request.taskMemory()),
                        HISTORY_PREVIEW_LIMIT,
                        recentHistoryPreview(request.recentHistory()),
                        allowedFieldsPreview()
                ))
        ));
        try {
            AgentLlmLogger.logRequest("memory_route_planner", ai, prompt);
            String content = chatModelFactory.chatClient(ai)
                    .prompt(prompt)
                    .call()
                    .content();
            AgentLlmLogger.logResponse("memory_route_planner", ai, content);
            return parsePlan(content).orElseGet(MemoryReadPlan::empty);
        } catch (Exception e) {
            log.warn("Memory route planning failed", e);
            return MemoryReadPlan.empty();
        }
    }

    Optional<MemoryReadPlan> parsePlan(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(content.strip()));
            if (root.path("confidence").asDouble(1.0) < MIN_CONFIDENCE) {
                return Optional.empty();
            }
            JsonNode itemsNode = root.path("items");
            if (!itemsNode.isArray()) {
                return Optional.empty();
            }
            List<MemoryReadItem> items = new ArrayList<>();
            for (JsonNode itemNode : itemsNode) {
                parseItem(itemNode).ifPresent(items::add);
            }
            if (items.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new MemoryReadPlan(items, root.path("tokenBudget").asInt(1200)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<MemoryReadItem> parseItem(JsonNode node) {
        if (!node.isObject()) {
            return Optional.empty();
        }
        MemoryType type;
        try {
            type = MemoryType.valueOf(text(node, "type"));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.of(new MemoryReadItem(
                type,
                textArray(node.path("fields")),
                text(node, "query"),
                text(node, "scope"),
                node.path("priority").asInt(50),
                node.path("limit").asInt(1),
                text(node, "reason")
        ));
    }

    private String instruction() {
        return """
                你是 Musio 的 Memory Router。只输出 JSON 对象，不要 markdown，不要解释。
                你只能从允许的 MemoryType 和字段中选择记忆读取建议，不能请求读取任意文件路径。
                不要请求原始行为日志；需要行为时只能读取 BEHAVIOR_SUMMARY。
                不要把临时心情当长期偏好，不要输出 chain-of-thought。

                输出格式：
                {"items":[{"type":"TASK_MEMORY|PROFILE_MEMORY|BEHAVIOR_SUMMARY|MUSIC_CACHE|CONVERSATION_SUMMARY|CURRENT_STATE|PENDING_ACTION","fields":["字段"],"query":"短查询","scope":"session|last_24h|last_7_days|profile|current","priority":0到100,"limit":1到10,"reason":"简短原因"}],"tokenBudget":1200,"confidence":0.0到1.0}
                """;
    }

    private String allowedFieldsPreview() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<MemoryType, Set<String>> entry : validator.allowedFields().entrySet()) {
            builder.append(entry.getKey()).append(": ").append(String.join(", ", entry.getValue())).append('\n');
        }
        return builder.toString().strip();
    }

    String taskMemoryPreview(AgentTaskMemory memory) {
        if (isBlankTaskMemory(memory)) {
            return "无";
        }
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "currentTask", memory.currentTask());
        appendLine(builder, "lastEffectiveRequest", memory.lastEffectiveRequest());
        appendLine(builder, "lastTargetSong", songRef(memory.lastTargetSong()));
        if (memory.lastResultSongs() != null && !memory.lastResultSongs().isEmpty()) {
            appendLine(builder, "lastResultSongRefs", String.join("；", memory.lastResultSongs().stream()
                    .limit(5)
                    .map(this::songRef)
                    .filter(value -> !value.isBlank())
                    .toList()));
        }
        if (memory.lastRecommendationSlots() != null && !memory.lastRecommendationSlots().isEmpty()) {
            appendLine(builder, "lastRecommendationSlots", String.join("；", memory.lastRecommendationSlots().stream()
                    .limit(6)
                    .map(this::recommendationSlotSummary)
                    .filter(value -> !value.isBlank())
                    .toList()));
        }
        if (memory.recentRecommendedSongs() != null && !memory.recentRecommendedSongs().isEmpty()) {
            appendLine(builder, "recentRecommendedSongs", String.join("；", memory.recentRecommendedSongs().stream()
                    .limit(12)
                    .map(this::recentRecommendationSummary)
                    .filter(value -> !value.isBlank())
                    .toList()));
        }
        if (memory.lastRequiredOutcomes() != null && !memory.lastRequiredOutcomes().isEmpty()) {
            appendLine(builder, "lastRequiredOutcomes", String.join("、", memory.lastRequiredOutcomes().stream().limit(10).toList()));
        }
        if (memory.avoidSongTitles() != null && !memory.avoidSongTitles().isEmpty()) {
            appendLine(builder, "avoidSongTitles", String.join("、", memory.avoidSongTitles().stream().limit(10).toList()));
        }
        if (memory.lastObservationSummaries() != null && !memory.lastObservationSummaries().isEmpty()) {
            appendLine(builder, "lastObservationSummaries", String.join("；", memory.lastObservationSummaries().stream().limit(5).toList()));
        }
        if (memory.lastToolFailures() != null && !memory.lastToolFailures().isEmpty()) {
            appendLine(builder, "lastToolFailures", String.join("；", memory.lastToolFailures().stream()
                    .limit(3)
                    .map(this::failureSummary)
                    .filter(value -> !value.isBlank())
                    .toList()));
        }
        appendLine(builder, "pendingLocalPlaylistAdd", pendingSummary(memory.pendingLocalPlaylistAdd()));
        String preview = builder.toString().strip();
        return preview.isBlank() ? "无" : preview;
    }

    String recentHistoryPreview(List<ConversationHistoryMessage> history) {
        if (history == null || history.isEmpty()) {
            return "无";
        }
        int start = Math.max(0, history.size() - HISTORY_PREVIEW_LIMIT);
        StringBuilder builder = new StringBuilder();
        for (ConversationHistoryMessage message : history.subList(start, history.size())) {
            if (message == null) {
                continue;
            }
            builder.append(safe(message.role()).isBlank() ? "unknown" : safe(message.role()))
                    .append(": ")
                    .append(truncate(message.content()));
            String songs = historySongRefs(message);
            if (!songs.isBlank()) {
                builder.append(" [songs: ").append(songs).append(']');
            }
            builder.append('\n');
        }
        String preview = builder.toString().strip();
        return preview.isBlank() ? "无" : preview;
    }

    private boolean isBlankTaskMemory(AgentTaskMemory memory) {
        return memory == null
                || (safe(memory.currentTask()).isBlank()
                && safe(memory.lastEffectiveRequest()).isBlank()
                && memory.lastTargetSong() == null
                && (memory.lastResultSongs() == null || memory.lastResultSongs().isEmpty())
                && (memory.lastRecommendationSlots() == null || memory.lastRecommendationSlots().isEmpty())
                && (memory.recentRecommendedSongs() == null || memory.recentRecommendedSongs().isEmpty())
                && (memory.lastRequiredOutcomes() == null || memory.lastRequiredOutcomes().isEmpty())
                && (memory.avoidSongTitles() == null || memory.avoidSongTitles().isEmpty())
                && (memory.lastObservationSummaries() == null || memory.lastObservationSummaries().isEmpty())
                && (memory.lastToolFailures() == null || memory.lastToolFailures().isEmpty())
                && memory.pendingLocalPlaylistAdd() == null);
    }

    private void appendLine(StringBuilder builder, String key, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(key).append(": ").append(truncate(value)).append('\n');
        }
    }

    private String songRef(Song song) {
        if (song == null) {
            return "";
        }
        String artists = song.artists() == null || song.artists().isEmpty() ? "" : " - " + String.join("/", song.artists());
        String id = safe(song.id()).isBlank() ? "" : " id=" + safe(song.id());
        return (safe(song.title()) + artists + id).strip();
    }

    private String recommendationSlotSummary(AgentTaskRecommendationSlot slot) {
        if (slot == null || safe(slot.slotId()).isBlank()) {
            return "";
        }
        String songs = slot.songTitles().isEmpty() ? "无已命中歌曲" : String.join("/", slot.songTitles());
        return "%s:%s=%s x%s -> %s".formatted(slot.slotId(), slot.targetType(), slot.target(), slot.requestedCount(), songs);
    }

    private String recentRecommendationSummary(AgentRecentRecommendedSong recommendation) {
        if (recommendation == null || safe(recommendation.title()).isBlank()) {
            return "";
        }
        String artists = recommendation.artists().isEmpty() ? "" : " - " + String.join("/", recommendation.artists());
        return recommendation.title() + artists;
    }

    private String failureSummary(AgentToolFailure failure) {
        if (failure == null || safe(failure.toolName()).isBlank()) {
            return "";
        }
        return failure.toolName() + ": " + safe(failure.message());
    }

    private String pendingSummary(PendingLocalPlaylistAdd pending) {
        if (pending == null) {
            return "";
        }
        Song singleSong = pending.song();
        int songCount = pending.songs() == null ? 0 : pending.songs().size();
        String target = singleSong == null ? "songs=" + songCount : songRef(singleSong);
        return "playlistId=%s, %s".formatted(safe(pending.playlistId()), target);
    }

    private String historySongRefs(ConversationHistoryMessage message) {
        if (message == null || message.songs().isEmpty()) {
            return "";
        }
        return String.join("；", message.songs().stream()
                .limit(3)
                .map(this::songRef)
                .filter(value -> !value.isBlank())
                .toList());
    }

    private String truncate(String value) {
        String stripped = safe(value).replaceAll("\\s+", " ");
        if (stripped.length() <= VALUE_PREVIEW_LIMIT) {
            return stripped;
        }
        return stripped.substring(0, VALUE_PREVIEW_LIMIT) + "...";
    }

    private String extractJsonObject(String value) {
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return value.substring(start, end + 1);
        }
        return value;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().strip() : "";
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private List<String> textArray(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText().strip());
            }
        }
        return values;
    }
}
