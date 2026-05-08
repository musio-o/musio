package com.musio.agent.loop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.AgentLlmLogger;
import com.musio.agent.AgentRunContext;
import com.musio.agent.ConversationHistoryMessage;
import com.musio.agent.capability.AgentCapabilityManifest;
import com.musio.agent.capability.AgentCapabilityRegistry;
import com.musio.ai.SpringAiChatModelFactory;
import com.musio.config.MusioConfig;
import com.musio.model.AgentTaskMemory;
import com.musio.model.AgentToolFailure;
import com.musio.model.Song;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class AgentStepPlanner {
    private static final Logger log = LoggerFactory.getLogger(AgentStepPlanner.class);
    private static final double MIN_CONFIDENCE = 0.55;

    private final SpringAiChatModelFactory chatModelFactory;
    private final ObjectMapper objectMapper;
    private final AgentCapabilityRegistry capabilityRegistry;

    public AgentStepPlanner() {
        this(null, new ObjectMapper(), new AgentCapabilityRegistry());
    }

    public AgentStepPlanner(SpringAiChatModelFactory chatModelFactory, ObjectMapper objectMapper) {
        this(chatModelFactory, objectMapper, new AgentCapabilityRegistry());
    }

    @Autowired
    public AgentStepPlanner(SpringAiChatModelFactory chatModelFactory, ObjectMapper objectMapper, AgentCapabilityRegistry capabilityRegistry) {
        this.chatModelFactory = chatModelFactory;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.capabilityRegistry = capabilityRegistry == null ? new AgentCapabilityRegistry() : capabilityRegistry;
    }

    public AgentStepAction nextAction(MusioConfig.Ai ai, AgentLoopState state) {
        if (chatModelFactory == null) {
            AgentStepAction fallback = AgentStepAction.finalAnswer("step_planner_model_unavailable", 0.0);
            logAction("step_planner", ai, fallback, "fallback");
            return fallback;
        }
        AgentCapabilityManifest manifest = manifestFor(state);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(plannerInstruction(manifest)),
                new UserMessage("""
                        当前任务记忆：
                        %s

                        最近对话：
                        %s

                        本轮 observations：
                        %s

                        本轮用户要求的歌曲数量：
                        %s

                        当前用户输入：
                        %s
                        """.formatted(
                        taskMemoryPreview(state == null ? null : state.taskMemory()),
                        historyPreview(state == null ? List.of() : state.recentHistory()),
                        observationPreview(state == null ? List.of() : state.observations()),
                        requestedSongCountPreview(state),
                        state == null ? "" : state.userMessage()
                ))
        ));
        try {
            AgentLlmLogger.logRequest("agent_step_planner", ai, prompt);
            log.info(
                    "AGENT_STEP_PLANNER_REQUEST stage=agent_step_planner runId={} userId={} provider={} model={} stepCount={} observationCount={}",
                    currentRunId(),
                    currentUserId(),
                    ai == null ? "" : safe(ai.provider()),
                    ai == null ? "" : safe(ai.model()),
                    state == null ? 0 : state.stepCount(),
                    state == null || state.observations() == null ? 0 : state.observations().size()
            );
            String content = chatModelFactory.chatClient(ai)
                    .prompt(prompt)
                    .call()
                    .content();
            AgentLlmLogger.logResponse("agent_step_planner", ai, content);
            AgentStepAction action = parseAction(content, manifest, state == null ? 0 : state.requestedSongCount())
                    .orElseGet(() -> AgentStepAction.finalAnswer("invalid_step_action", 0.0));
            logAction("agent_step_planner", ai, action, "model");
            return action;
        } catch (Exception e) {
            log.warn("Agent step planning failed", e);
            AgentStepAction fallback = AgentStepAction.finalAnswer("step_planner_exception", 0.0);
            logAction("agent_step_planner", ai, fallback, "exception");
            return fallback;
        }
    }

    Optional<AgentStepAction> parseAction(String content) {
        return parseAction(content, capabilityRegistry.readManifest());
    }

    Optional<AgentStepAction> parseAction(String content, AgentCapabilityManifest manifest) {
        return parseAction(content, manifest, 0);
    }

    Optional<AgentStepAction> parseAction(String content, AgentCapabilityManifest manifest, int requestedSongCount) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        try {
            AgentCapabilityManifest effectiveManifest = manifest == null || manifest.isEmpty()
                    ? capabilityRegistry.readManifest()
                    : manifest;
            JsonNode root = objectMapper.readTree(extractJsonObject(content.strip()));
            double confidence = root.path("confidence").asDouble(0.0);
            if (confidence < MIN_CONFIDENCE) {
                return Optional.of(AgentStepAction.finalAnswer("low_confidence", confidence));
            }
            AgentStepActionType actionType = parseActionType(text(root, "action"));
            if (actionType == null) {
                logRejectedAction("", "unknown_action");
                return Optional.empty();
            }
            String toolName = text(root, "toolName");
            if (toolName.isBlank()) {
                toolName = text(root, "tool");
            }
            Map<String, Object> arguments = arguments(root);
            if (actionType == AgentStepActionType.TOOL_CALL) {
                if (!effectiveManifest.allows(toolName)) {
                    logRejectedAction(toolName, "unknown_tool");
                    return Optional.empty();
                }
                arguments = cleanArguments(toolName, arguments, requestedSongCount);
                if (!requiredArgumentsPresent(toolName, arguments)) {
                    logRejectedAction(toolName, "missing_required_argument");
                    return Optional.empty();
                }
            } else {
                toolName = "";
                arguments = Map.of();
            }
            return Optional.of(new AgentStepAction(
                    actionType,
                    toolName,
                    arguments,
                    text(root, "publicActivity"),
                    confidence,
                    text(root, "reason")
            ));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private AgentCapabilityManifest manifestFor(AgentLoopState state) {
        AgentCapabilityManifest manifest = state == null ? null : state.capabilityManifest();
        return manifest == null || manifest.isEmpty() ? capabilityRegistry.readManifest() : manifest;
    }

    private String plannerInstruction(AgentCapabilityManifest manifest) {
        return """
                你是 Musio 的 AgentStepPlanner。只输出 JSON 对象，不要 markdown，不要解释。
                你每次只决定下一步 action；后端执行工具后，会把 observation 再交给你继续判断。

                本轮可用能力：
                %s

                输出格式：
                {"action":"tool_call|final_answer|request_confirmation|unsupported","toolName":"工具名或空","arguments":{},"publicActivity":"用户可见动作摘要","confidence":0.0到1.0,"reason":"为什么下一步这样做"}

                规则：
                - 每次最多输出一个 action。
                - 需要真实音乐数据时输出 action=tool_call。
                - 信息已经足够回答时输出 action=final_answer。
                - 不要编造 songId 或 playlistId；这类 id 必须来自用户输入、当前任务记忆或本轮 observations。
                - 如果用户要歌词、评论或歌曲详情，但当前没有目标 songId，下一步应先搜索或利用已有 observation / 任务记忆里的歌曲 id。
                - search_songs.keyword 只写正向搜索目标，例如歌手、歌曲名或风格；不要把排除、比较或“不是 X 是 Y”这类关系拼进 keyword。
                - search_songs.limit 必须显式填写；完全没有数量含义时默认 5。
                - 如果“本轮用户要求的歌曲数量”是明确数字，search_songs.limit 不得超过这个数字；例如用户说“推荐一首”时 limit 必须是 1。
                - 复合任务中的数量约束必须贯穿搜索、评论和收藏步骤；用户说一首时，只围绕一首歌继续读取评论和收藏。
                - get_hot_comments.limit 默认 10，最大 30。
                - 如果用户说“最热门的评论/一条评论/一条热评”，get_hot_comments.limit 应填写 1。
                - add_song_to_musio_playlist 只有在用户已经明确确认收藏、且出现在“本轮可用能力”时才允许调用；它只写入本地 Musio 歌单，不代表 QQ 音乐账号收藏。
                - add_song_to_musio_playlist 至少应提供 songId、songIndex、songTitle 三者之一；playlistId 缺省为 default。
                - 不要输出 chain-of-thought。
                """.formatted(manifest.plannerToolList());
    }

    private Map<String, Object> arguments(JsonNode root) {
        JsonNode argumentsNode = root.path("arguments");
        if (!argumentsNode.isObject()) {
            argumentsNode = root.path("args");
        }
        if (!argumentsNode.isObject()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(argumentsNode, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private Map<String, Object> cleanArguments(String toolName, Map<String, Object> arguments, int requestedSongCount) {
        Map<String, Object> cleaned = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        if ("search_songs".equals(toolName)) {
            cleaned.put("keyword", text(cleaned, "keyword"));
            Integer limit = cleanRequiredLimit(cleaned.get("limit"), 1, 20);
            if (limit == null && requestedSongCount > 0) {
                cleaned.put("limit", Math.min(requestedSongCount, 20));
            } else if (limit == null) {
                cleaned.remove("limit");
            } else {
                cleaned.put("limit", requestedSongCount > 0 ? Math.min(limit, requestedSongCount) : limit);
            }
            List<String> excludedTitles = stringList(cleaned.get("excludedTitles"));
            if (excludedTitles.isEmpty()) {
                cleaned.remove("excludedTitles");
            } else {
                cleaned.put("excludedTitles", excludedTitles);
            }
        }
        if ("get_song_detail".equals(toolName) || "get_lyrics".equals(toolName) || "get_hot_comments".equals(toolName)) {
            cleaned.put("songId", text(cleaned, "songId"));
        }
        if ("get_hot_comments".equals(toolName)) {
            cleaned.put("limit", cleanLimit(cleaned.get("limit"), 10, 1, 30));
        }
        if ("get_user_playlists".equals(toolName)) {
            cleaned.put("limit", cleanLimit(cleaned.get("limit"), 20, 1, 50));
        }
        if ("get_playlist_songs".equals(toolName)) {
            cleaned.put("playlistId", text(cleaned, "playlistId"));
            cleaned.put("limit", cleanLimit(cleaned.get("limit"), 20, 1, 50));
        }
        if (AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST.equals(toolName)) {
            cleaned.put("playlistId", text(cleaned, "playlistId").isBlank() ? "default" : text(cleaned, "playlistId"));
            cleaned.put("songId", text(cleaned, "songId"));
            cleaned.put("songTitle", text(cleaned, "songTitle"));
            cleaned.put("artist", text(cleaned, "artist"));
            Integer songIndex = cleanRequiredLimit(cleaned.get("songIndex"), 1, 100);
            if (songIndex == null) {
                cleaned.remove("songIndex");
            } else {
                cleaned.put("songIndex", songIndex);
            }
        }
        return cleaned;
    }

    private String requestedSongCountPreview(AgentLoopState state) {
        int count = state == null ? 0 : state.requestedSongCount();
        return count <= 0 ? "未明确，缺省按工具规则处理" : String.valueOf(count);
    }

    private boolean requiredArgumentsPresent(String toolName, Map<String, Object> arguments) {
        return switch (toolName) {
            case "get_song_detail", "get_lyrics", "get_hot_comments" -> hasText(arguments, "songId");
            case "get_playlist_songs" -> hasText(arguments, "playlistId");
            case "search_songs" -> hasText(arguments, "keyword") && hasInteger(arguments, "limit");
            case AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST -> hasText(arguments, "songId")
                    || hasText(arguments, "songTitle")
                    || hasInteger(arguments, "songIndex");
            default -> true;
        };
    }

    private AgentStepActionType parseActionType(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "tool_call" -> AgentStepActionType.TOOL_CALL;
            case "final_answer" -> AgentStepActionType.FINAL_ANSWER;
            case "request_confirmation" -> AgentStepActionType.REQUEST_CONFIRMATION;
            case "unsupported" -> AgentStepActionType.UNSUPPORTED;
            default -> null;
        };
    }

    private String taskMemoryPreview(AgentTaskMemory memory) {
        if (memory == null) {
            return "无";
        }
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "currentTask", memory.currentTask());
        appendLine(builder, "lastEffectiveRequest", memory.lastEffectiveRequest());
        appendLine(builder, "lastSearchKeyword", memory.lastSearchKeyword());
        if (memory.lastResultSongs() != null && !memory.lastResultSongs().isEmpty()) {
            appendLine(builder, "lastResultSongRefs", String.join("；", memory.lastResultSongs().stream()
                    .limit(5)
                    .map(this::songRef)
                    .filter(ref -> !ref.isBlank())
                    .toList()));
        }
        if (memory.lastTargetSong() != null) {
            appendLine(builder, "lastTargetSong", songRef(memory.lastTargetSong()));
        }
        appendLine(builder, "lastCompletedTaskType", memory.lastCompletedTaskType());
        if (memory.lastObservationSummaries() != null && !memory.lastObservationSummaries().isEmpty()) {
            appendLine(builder, "lastObservationSummaries", String.join("；", memory.lastObservationSummaries().stream().limit(5).toList()));
        }
        if (memory.lastResultSongTitles() != null && !memory.lastResultSongTitles().isEmpty()) {
            appendLine(builder, "lastResultSongTitles", String.join("、", memory.lastResultSongTitles().stream().limit(10).toList()));
        }
        if (memory.lastToolFailures() != null && !memory.lastToolFailures().isEmpty()) {
            appendLine(builder, "lastToolFailures", String.join("；", memory.lastToolFailures().stream()
                    .limit(3)
                    .map(this::failureSummary)
                    .toList()));
        }
        String value = builder.toString().strip();
        return value.isBlank() ? "无" : value;
    }

    private String historyPreview(List<ConversationHistoryMessage> history) {
        int start = Math.max(0, history.size() - 8);
        StringBuilder builder = new StringBuilder();
        for (ConversationHistoryMessage message : history.subList(start, history.size())) {
            builder.append(message.role())
                    .append(": ")
                    .append(truncate(message.content()))
                    .append('\n');
        }
        return builder.toString().strip();
    }

    private String observationPreview(List<AgentObservation> observations) {
        if (observations == null || observations.isEmpty()) {
            return "无";
        }
        StringBuilder builder = new StringBuilder();
        for (AgentObservation observation : observations.stream().limit(8).toList()) {
            builder.append(observation.stepId())
                    .append(" | ")
                    .append(observation.toolName())
                    .append(" | ")
                    .append(observation.status())
                    .append(" | ")
                    .append(observation.plannerSummary())
                    .append('\n');
        }
        return builder.toString().strip();
    }

    private void appendLine(StringBuilder builder, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        builder.append(key).append(": ").append(value.strip()).append('\n');
    }

    private String songRef(Song song) {
        if (song == null) {
            return "";
        }
        String artists = song.artists() == null || song.artists().isEmpty() ? "" : String.join(" / ", song.artists());
        return "%s | %s | id=%s".formatted(safe(song.title()), artists, safe(song.id())).strip();
    }

    private String failureSummary(AgentToolFailure failure) {
        if (failure == null) {
            return "";
        }
        return "%s: %s".formatted(safe(failure.toolName()), safe(failure.message())).strip();
    }

    private void logAction(String stage, MusioConfig.Ai ai, AgentStepAction action, String source) {
        log.info(
                "AGENT_STEP_ACTION stage={} runId={} userId={} provider={} model={} source={} action={} toolName={} confidence={} reason={}",
                stage,
                currentRunId(),
                currentUserId(),
                ai == null ? "" : safe(ai.provider()),
                ai == null ? "" : safe(ai.model()),
                source,
                action == null ? "" : action.action(),
                action == null ? "" : action.toolName(),
                action == null ? 0.0 : action.confidence(),
                action == null ? "" : truncate(action.reason())
        );
    }

    private void logRejectedAction(String toolName, String reason) {
        log.info(
                "AGENT_STEP_VALIDATION stage=agent_step_validator runId={} userId={} validationStatus=rejected toolName={} reason={}",
                currentRunId(),
                currentUserId(),
                toolName == null || toolName.isBlank() ? "blank" : truncate(toolName),
                reason
        );
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

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value instanceof String text ? text.strip() : "";
    }

    private boolean hasText(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value instanceof String text && !text.isBlank();
    }

    private boolean hasInteger(Map<String, Object> arguments, String key) {
        return arguments.get(key) instanceof Number;
    }

    private int cleanLimit(Object value, int defaultValue, int min, int max) {
        int actual = defaultValue;
        if (value instanceof Number number) {
            actual = number.intValue();
        } else if (value instanceof String text && !text.isBlank()) {
            try {
                actual = Integer.parseInt(text.strip());
            } catch (NumberFormatException ignored) {
                actual = defaultValue;
            }
        }
        return Math.max(min, Math.min(max, actual));
    }

    private Integer cleanRequiredLimit(Object value, int min, int max) {
        Integer actual = null;
        if (value instanceof Number number) {
            actual = number.intValue();
        } else if (value instanceof String text && !text.isBlank()) {
            try {
                actual = Integer.parseInt(text.strip());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (actual == null) {
            return null;
        }
        return Math.max(min, Math.min(max, actual));
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
                .distinct()
                .limit(20)
                .toList();
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        String stripped = value.strip().replaceAll("\\s+", " ");
        if (stripped.length() <= 500) {
            return stripped;
        }
        return stripped.substring(0, 500) + "...";
    }

    private String currentRunId() {
        return AgentRunContext.runId().orElse("-");
    }

    private String currentUserId() {
        return AgentRunContext.userId().orElse("-");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
