package com.musio.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class AgentTurnPlanner {
    private static final Logger log = LoggerFactory.getLogger(AgentTurnPlanner.class);
    private static final double MIN_CONFIDENCE = 0.55;
    private static final int MAX_TOOL_CALLS = 12;
    private static final int DEFAULT_SEARCH_LIMIT = 5;
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "recommend_songs",
            "search_songs",
            "get_user_music_profile",
            "get_song_detail",
            "get_lyrics",
            "get_hot_comments",
            "get_user_playlists",
            "get_playlist_songs",
            "add_song_to_musio_playlist"
    );

    private final SpringAiChatModelFactory chatModelFactory;
    private final ObjectMapper objectMapper;

    public AgentTurnPlanner(
            SpringAiChatModelFactory chatModelFactory,
            ObjectMapper objectMapper
    ) {
        this.chatModelFactory = chatModelFactory;
        this.objectMapper = objectMapper;
    }

    public AgentTurnPlan planTurn(
            MusioConfig.Ai ai,
            String userMessage,
            List<ConversationHistoryMessage> history,
            AgentTaskMemory taskMemory
    ) {
        if (chatModelFactory == null) {
            AgentTurnPlan fallback = AgentTurnPlan.respondOnly(userMessage, 0.0, "model_unavailable");
            logPlanSummary("turn_planner", ai, fallback, "fallback");
            return fallback;
        }
        String memoryPreview = turnPlannerTaskMemoryPreview(taskMemory);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(plannerInstruction()),
                new UserMessage("""
                        当前任务记忆：
                        %s

                        最近对话：
                        %s

                        当前用户输入：
                        %s
                        """.formatted(memoryPreview, historyPreview(history == null ? List.of() : history), userMessage))
        ));
        try {
            // Turn planner prompt 是定位误规划的关键证据；其中只包含对话、任务记忆安全摘要和工具 manifest。
            AgentLlmLogger.logRequest("turn_planner", ai, prompt);
            // 这条摘要日志方便按 runId 快速筛选，不替代上面的结构化 prompt 日志。
            log.info(
                    "TURN_PLANNER_REQUEST stage=turn_planner runId={} userId={} provider={} model={} historyCount={} memoryPresent={}",
                    currentRunId(),
                    currentUserId(),
                    ai == null ? "" : safe(ai.provider()),
                    ai == null ? "" : safe(ai.model()),
                    history == null ? 0 : history.size(),
                    memoryPreview == null || "无".equals(memoryPreview) ? "false" : "true"
            );
            String content = chatModelFactory.chatClient(ai)
                    .prompt(prompt)
                    .call()
                    .content();
            AgentLlmLogger.logResponse("turn_planner", ai, content);
            AgentTurnPlan plan = parsePlan(content, userMessage)
                    .orElseGet(() -> AgentTurnPlan.respondOnly(userMessage, 0.0, "invalid_turn_plan"));
            logPlanSummary("turn_planner", ai, plan, "model");
            return plan;
        } catch (Exception e) {
            log.warn("Agent turn planning failed", e);
            AgentTurnPlan fallback = AgentTurnPlan.respondOnly(userMessage, 0.0, "planner_exception");
            logPlanSummary("turn_planner", ai, fallback, "exception");
            return fallback;
        }
    }

    Optional<AgentTurnPlan> parsePlan(String content, String originalMessage) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        try {
            // Planner 只表达意图；后端仍负责 allowlist、必填参数和 limit 校验。
            JsonNode root = objectMapper.readTree(extractJsonObject(content.strip()));
            double confidence = root.path("confidence").asDouble(0.0);
            if (confidence < MIN_CONFIDENCE) {
                return Optional.of(AgentTurnPlan.respondOnly(originalMessage, confidence, "low_confidence"));
            }
            TurnDisposition disposition = parseDisposition(text(root, "disposition"));
            if (disposition == null) {
                return Optional.empty();
            }
            String taskType = cleanTaskType(text(root, "taskType"));
            String contextMode = cleanContextMode(text(root, "contextMode"));
            String effectiveRequest = text(root, "effectiveRequest");
            AgentTurnMemoryUse memoryUse = parseMemoryUse(root.path("memoryUse"));
            List<AgentToolCall> calls = parseCalls(root.path("toolCalls"));
            if (calls.isEmpty() && root.path("calls").isArray()) {
                calls = parseCalls(root.path("calls"));
            }
            if (disposition == TurnDisposition.RESPOND_ONLY) {
                calls = List.of();
            }
            if (disposition == TurnDisposition.USE_TOOLS && calls.isEmpty()) {
                return Optional.of(AgentTurnPlan.respondOnly(
                        effectiveRequest.isBlank() ? originalMessage : effectiveRequest,
                        confidence,
                        "no_valid_tool_calls"
                ));
            }
            return Optional.of(new AgentTurnPlan(
                    disposition,
                    taskType,
                    contextMode,
                    effectiveRequest.isBlank() ? originalMessage : effectiveRequest,
                    memoryUse,
                    calls,
                    confidence,
                    ""
            ));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private List<AgentToolCall> parseCalls(JsonNode callsNode) {
        if (!callsNode.isArray()) {
            return List.of();
        }
        List<AgentToolCall> calls = new ArrayList<>();
        for (JsonNode callNode : callsNode) {
            parseCall(callNode).ifPresent(calls::add);
            if (calls.size() >= MAX_TOOL_CALLS) {
                break;
            }
        }
        return List.copyOf(calls);
    }

    private Optional<AgentToolCall> parseCall(JsonNode callNode) {
        if (!callNode.isObject()) {
            logRejectedTool("", "invalid_tool_call");
            return Optional.empty();
        }
        String toolName = text(callNode, "toolName");
        if (toolName.isBlank()) {
            toolName = text(callNode, "name");
        }
        if (!ALLOWED_TOOLS.contains(toolName)) {
            logRejectedTool(toolName, "unknown_tool");
            return Optional.empty();
        }
        JsonNode argumentsNode = callNode.path("arguments");
        if (!argumentsNode.isObject()) {
            argumentsNode = callNode.path("args");
        }
        Map<String, Object> arguments = argumentsNode.isObject()
                ? objectMapper.convertValue(argumentsNode, new TypeReference<LinkedHashMap<String, Object>>() {
                })
                : new LinkedHashMap<>();
        Map<String, Object> cleanedArguments = cleanArguments(toolName, arguments);
        if (!requiredArgumentsPresent(toolName, cleanedArguments)) {
            logRejectedTool(toolName, "missing_required_argument");
            return Optional.empty();
        }
        return Optional.of(new AgentToolCall(toolName, cleanedArguments));
    }

    private Map<String, Object> cleanArguments(String toolName, Map<String, Object> arguments) {
        Map<String, Object> cleaned = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        if ("search_songs".equals(toolName)) {
            cleaned.put("keyword", text(cleaned, "keyword"));
            Integer limit = cleanRequiredLimit(cleaned.get("limit"), 1, 20);
            if (limit == null) {
                cleaned.remove("limit");
            } else {
                cleaned.put("limit", limit);
            }
            List<String> excludedTitles = stringList(cleaned.get("excludedTitles"));
            if (!excludedTitles.isEmpty()) {
                cleaned.put("excludedTitles", excludedTitles);
            } else {
                cleaned.remove("excludedTitles");
            }
        }
        if ("recommend_songs".equals(toolName)) {
            cleaned.put("request", text(cleaned, "request"));
            Integer count = cleanRequiredLimit(cleaned.get("count"), 1, 10);
            if (count == null) {
                cleaned.remove("count");
            } else {
                cleaned.put("count", count);
            }
            List<String> excludedTitles = stringList(cleaned.get("excludedTitles"));
            if (!excludedTitles.isEmpty()) {
                cleaned.put("excludedTitles", excludedTitles);
            } else {
                cleaned.remove("excludedTitles");
            }
        }
        if ("get_hot_comments".equals(toolName)) {
            cleaned.put("limit", cleanLimit(cleaned.get("limit"), 10, 1, 30));
        }
        if ("get_user_playlists".equals(toolName) || "get_playlist_songs".equals(toolName)) {
            cleaned.put("limit", cleanLimit(cleaned.get("limit"), 20, 1, 50));
        }
        if ("add_song_to_musio_playlist".equals(toolName)) {
            String playlistId = text(cleaned, "playlistId");
            cleaned.put("playlistId", playlistId.isBlank() ? "default" : playlistId);
            cleaned.put("songId", text(cleaned, "songId"));
            cleaned.put("songTitle", text(cleaned, "songTitle"));
            cleaned.put("artist", text(cleaned, "artist"));
            Integer songIndex = cleanRequiredLimit(cleaned.get("songIndex"), 1, 20);
            if (songIndex == null) {
                cleaned.remove("songIndex");
            } else {
                cleaned.put("songIndex", songIndex);
            }
        }
        return cleaned;
    }

    private AgentTurnMemoryUse parseMemoryUse(JsonNode node) {
        if (!node.isObject()) {
            return AgentTurnMemoryUse.none("Planner 未声明任务记忆使用。");
        }
        return new AgentTurnMemoryUse(
                node.path("usesTaskMemory").asBoolean(false),
                textArray(node.path("usedFields")),
                text(node, "reason")
        );
    }

    private boolean requiredArgumentsPresent(String toolName, Map<String, Object> arguments) {
        return switch (toolName) {
            case "get_song_detail", "get_lyrics", "get_hot_comments" -> hasText(arguments, "songId");
            case "get_playlist_songs" -> hasText(arguments, "playlistId");
            case "search_songs" -> hasText(arguments, "keyword") && hasInteger(arguments, "limit");
            case "recommend_songs" -> hasText(arguments, "request") && hasInteger(arguments, "count");
            case "add_song_to_musio_playlist" -> true;
            default -> true;
        };
    }

    private TurnDisposition parseDisposition(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "respond_only" -> TurnDisposition.RESPOND_ONLY;
            case "use_tools" -> TurnDisposition.USE_TOOLS;
            case "request_confirmation" -> TurnDisposition.REQUEST_CONFIRMATION;
            case "unsupported" -> TurnDisposition.UNSUPPORTED;
            default -> null;
        };
    }

    private String cleanTaskType(String taskType) {
        String normalized = taskType == null ? "" : taskType.strip().toLowerCase(Locale.ROOT);
        if (List.of("chat", "search", "recommend", "comments", "lyrics", "detail", "playlist", "profile", "playback", "unknown").contains(normalized)) {
            return normalized;
        }
        return "unknown";
    }

    private String cleanContextMode(String contextMode) {
        String normalized = contextMode == null ? "" : contextMode.strip().toLowerCase(Locale.ROOT);
        if (List.of("new_task", "follow_up", "retry", "refer_previous_song", "correction").contains(normalized)) {
            return normalized;
        }
        return "new_task";
    }

    private String plannerInstruction() {
        return """
                你是 Musio 的 Turn Planner。只输出 JSON 对象，不要 markdown，不要解释。
                所有用户输入都已经进入 Agent runtime；你的任务不是决定是否进入 Agent，而是决定本轮是否需要调用音乐能力。

                可用只读工具：
                - recommend_songs {"request": string, "count": number, "excludedTitles": string[]}：生成个性化推荐候选，并由后端精确解析成可播放歌曲卡片；适合开放推荐、场景推荐、风格推荐。excludedTitles 可选。
                - search_songs {"keyword": string, "limit": number, "excludedTitles": string[]}：搜索歌曲、歌手、专辑或候选音乐；excludedTitles 可选。
                - get_user_music_profile {}：读取本地音乐画像摘要。
                - get_song_detail {"songId": string}：读取歌曲详情。
                - get_lyrics {"songId": string}：读取歌词。
                - get_hot_comments {"songId": string, "limit": number}：读取热门评论。
                - get_user_playlists {"limit": number}：读取用户歌单。
                - get_playlist_songs {"playlistId": string, "limit": number}：读取歌单歌曲。

                可用本地 Musio 写入能力：
                - add_song_to_musio_playlist {"playlistId": "default", "songId": string, "songTitle": string, "artist": string, "songIndex": number}：把歌曲收藏到本地 Musio 默认歌单；这是 Musio 本地歌单写入，不是 QQ 音乐账号收藏。

                输出格式：
                {"disposition":"respond_only|use_tools|request_confirmation|unsupported","taskType":"chat|search|recommend|comments|lyrics|detail|playlist|profile|playback|unknown","contextMode":"new_task|follow_up|retry|refer_previous_song|correction","effectiveRequest":"用于本轮执行的完整请求","memoryUse":{"usesTaskMemory":true|false,"usedFields":["lastSearchKeyword"],"reason":"为什么需要或不需要短期任务记忆"},"toolCalls":[{"toolName":"工具名","arguments":{}}],"confidence":0.0到1.0}

                规则：
                - 普通寒暄、感谢、确认、情绪表达且不需要音乐能力时，输出 disposition=respond_only、taskType=chat、toolCalls=[]。
                - 当前用户输入优先级最高；任务记忆只用于恢复搜索目标、上一轮结果和排除项，不用于默认继承旧数量。
                - 搜索歌曲、推荐歌曲、歌词、评论、歌单、歌手、专辑、播放前发现歌曲等音乐相关请求，应输出 disposition=use_tools 并规划只读工具。
                - 开放推荐、场景推荐、风格推荐应使用 recommend_songs，taskType=recommend；不要把场景、用途、时段或心境描述直接当 search_songs.keyword 搜索。
                - 精确搜歌、歌手搜索、替换已有候选、播放前查找候选时使用 search_songs，taskType=search。
                - taskType 必须描述本轮主能力：如果主要工具是 search_songs 且目标是查找/替换候选歌曲，使用 taskType=search；如果主要工具是 recommend_songs，使用 taskType=recommend。
                - 如果用户在纠正上一轮音乐搜索目标，例如说明刚才的歌手、歌名或关键词说错了，使用 contextMode=correction，并基于纠正后的正向目标规划 search_songs。
                - search_songs.keyword 只写正向搜索目标，例如歌手、歌曲名或风格；不要把排除、比较或“不是 X 是 Y”这类关系拼进 keyword。
                - search_songs.arguments.limit 必须显式填写。根据当前用户输入和 effectiveRequest 的数量含义填写；只有当前请求完全没有数量含义时才用默认 5。
                - recommend_songs.arguments.count 必须显式填写。根据当前用户输入和 effectiveRequest 的推荐数量填写；完全没有数量含义时默认 5。
                - 用户说“一首/1首/一个/一支/一曲”时，search_songs.limit 或 recommend_songs.count 必须填 1，不能使用默认 5。
                - 不要编造 songId、playlistId 或用户没有提供且任务记忆中没有的标识符。
                - 歌曲评论、歌词、详情类任务如果没有目标 songId，需要先 search_songs 找候选；如果有目标 songId，优先直接调用对应工具。
                - 用户说“收藏/保存/加入 Musio 歌单/帮我收藏某首歌/加入歌单”时，规划 add_song_to_musio_playlist 只表示待确认写入意图；后端必须等用户下一轮确认后才真正写入。
                - 如果用户要收藏“刚才那首/第一首/第二首”等上一轮卡片歌曲，memoryUse.usesTaskMemory=true，usedFields 包含 lastResultSongs；能确定序号时填写 songIndex，能确定 songId 时填写 songId。
                - 如果用户明确给出歌名或歌手但没有 songId，add_song_to_musio_playlist 填 songTitle/artist；后端会先解析或搜索真实歌曲。
                - add_song_to_musio_playlist 是本地 Musio 歌单写入，不是只读工具；不要声称它已经执行成功，除非当前用户输入是明确确认语句。
                - 当前只读工具可以直接 use_tools；本地 Musio 歌单写入必须等待用户确认。
                - 不需要工具时不要为了展示能力而调用工具。
                - 不要输出 chain-of-thought。
                """;
    }

    private void logPlanSummary(String stage, MusioConfig.Ai ai, AgentTurnPlan plan, String source) {
        log.info(
                "TURN_PLAN stage={} runId={} userId={} provider={} model={} source={} disposition={} taskType={} contextMode={} memoryUse={} toolCallCount={} toolNames={} confidence={} fallbackReason={}",
                stage,
                currentRunId(),
                currentUserId(),
                ai == null ? "" : safe(ai.provider()),
                ai == null ? "" : safe(ai.model()),
                source,
                plan.disposition(),
                plan.taskType(),
                plan.contextMode(),
                plan.memoryUse() == null ? "none" : plan.memoryUse().summary(),
                plan.toolCalls() == null ? 0 : plan.toolCalls().size(),
                toolNames(plan.toolCalls()),
                plan.confidence(),
                plan.fallbackReason()
        );
    }

    private void logRejectedTool(String toolName, String reason) {
        log.info(
                "TURN_PLAN_VALIDATION stage=plan_validator runId={} userId={} validationStatus=rejected toolName={} reason={}",
                currentRunId(),
                currentUserId(),
                toolName == null || toolName.isBlank() ? "blank" : truncate(toolName),
                reason
        );
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

    String turnPlannerTaskMemoryPreview(AgentTaskMemory memory) {
        if (memory == null || (safe(memory.currentTask()).isBlank()
                && safe(memory.lastEffectiveRequest()).isBlank()
                && safe(memory.lastSearchKeyword()).isBlank()
                && (memory.lastResultSongs() == null || memory.lastResultSongs().isEmpty())
                && (memory.lastResultSongTitles() == null || memory.lastResultSongTitles().isEmpty())
                && (memory.avoidSongTitles() == null || memory.avoidSongTitles().isEmpty())
                && (memory.lastToolFailures() == null || memory.lastToolFailures().isEmpty()))) {
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
        if (memory.avoidSongTitles() != null && !memory.avoidSongTitles().isEmpty()) {
            appendLine(builder, "avoidSongTitles", String.join("、", memory.avoidSongTitles()));
        }
        if (memory.lastToolFailures() != null && !memory.lastToolFailures().isEmpty()) {
            appendLine(builder, "lastToolFailures", String.join("；", memory.lastToolFailures().stream()
                    .limit(3)
                    .map(this::failureSummary)
                    .toList()));
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

    private String toolNames(List<AgentToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return "none";
        }
        return String.join(",", calls.stream()
                .map(AgentToolCall::toolName)
                .filter(name -> name != null && !name.isBlank())
                .toList());
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
        return values.stream()
                .distinct()
                .limit(20)
                .toList();
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
