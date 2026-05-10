package com.musio.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.ai.SpringAiChatModelFactory;
import com.musio.agent.trace.AgentTracePublisher;
import com.musio.config.MusioConfig;
import com.musio.model.AgentRecentRecommendedSong;
import com.musio.model.AgentTaskMemory;
import com.musio.model.AgentTaskRecommendationSlot;
import com.musio.model.AgentToolFailure;
import com.musio.model.Song;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Locale;

@Component
public class AgentTaskContextResolver {
    private static final Logger log = LoggerFactory.getLogger(AgentTaskContextResolver.class);
    private static final int RECENT_HISTORY_LIMIT = 8;
    private static final int MESSAGE_PREVIEW_LIMIT = 500;
    private static final double MIN_CONFIDENCE = 0.55;

    private final SpringAiChatModelFactory chatModelFactory;
    private final ObjectMapper objectMapper;
    private final AgentTracePublisher tracePublisher;

    public AgentTaskContextResolver(
            SpringAiChatModelFactory chatModelFactory,
            ObjectMapper objectMapper,
            AgentTracePublisher tracePublisher
    ) {
        this.chatModelFactory = chatModelFactory;
        this.objectMapper = objectMapper;
        this.tracePublisher = tracePublisher;
    }

    public AgentTaskContext resolve(
            MusioConfig.Ai ai,
            String userMessage,
            List<ConversationHistoryMessage> history,
            AgentTaskMemory taskMemory
    ) {
        AgentTaskContext context = resolveWithModel(ai, userMessage, history == null ? List.of() : history, taskMemory)
                .orElseGet(() -> fallbackContext(userMessage));
        return context;
    }

    Optional<AgentTaskContext> parseModelResponse(String userMessage, String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        String json = extractJsonObject(content.strip());
        try {
            JsonNode root = objectMapper.readTree(json);
            double confidence = root.path("confidence").asDouble(0.0);
            if (confidence < MIN_CONFIDENCE) {
                return Optional.empty();
            }

            String mode = text(root, "mode");
            String taskType = cleanModelTaskType(text(root, "taskType"));
            String effectiveRequest = text(root, "effectiveRequest");
            String searchKeyword = text(root, "searchKeyword");
            String targetSongId = text(root, "targetSongId");
            String targetSongTitle = text(root, "targetSongTitle");
            int searchLimit = cleanSearchLimit(root.path("searchLimit").asInt(0));
            List<String> avoidSongTitles = textArray(root.path("avoidSongTitles"));
            boolean followUp = root.path("followUp").asBoolean(false);
            String contextMode = cleanContextMode(text(root, "contextMode"), followUp, taskType, targetSongId);
            AgentTaskMemoryAccess memoryAccess = cleanMemoryAccess(root.path("memoryAccess"), contextMode);
            followUp = isFollowUpContextMode(contextMode);
            if (!"agent".equals(mode)) {
                return Optional.of(AgentTaskContext.direct(userMessage, confidence, "model"));
            }
            if (effectiveRequest.isBlank()) {
                return Optional.empty();
            }
            AgentTaskContext context = AgentTaskContext.agent(
                    userMessage,
                    effectiveRequest,
                    cleanSearchKeyword(searchKeyword, avoidSongTitles),
                    searchLimit,
                    followUp,
                    avoidSongTitles,
                    confidence,
                    "model",
                    taskType,
                    targetSongId,
                    targetSongTitle,
                    contextMode,
                    memoryAccess
            );
            return Optional.of(context);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<AgentTaskContext> resolveWithModel(
            MusioConfig.Ai ai,
            String userMessage,
            List<ConversationHistoryMessage> history,
            AgentTaskMemory taskMemory
    ) {
        if (chatModelFactory == null) {
            return Optional.empty();
        }
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(routerInstruction()),
                new UserMessage("""
                        当前任务记忆：
                        %s

                        最近对话：
                        %s

                        当前用户输入：
                        %s
                        """.formatted(taskMemoryPreview(taskMemory), historyPreview(history), userMessage))
        ));
        try {
            AgentLlmLogger.logRequest("router", ai, prompt);
            String content = chatModelFactory.chatClient(ai)
                    .prompt(prompt)
                    .call()
                    .content();
            AgentLlmLogger.logResponse("router", ai, content);
            return parseModelResponse(userMessage, content);
        } catch (Exception e) {
            log.warn("Agent task context resolution failed", e);
            return Optional.empty();
        }
    }

    private AgentTaskContext fallbackContext(String userMessage) {
        Optional<ExplicitSongSearch> explicitSongSearch = explicitSongSearch(userMessage);
        if (explicitSongSearch.isPresent()) {
            return explicitSongSearchContext(userMessage, AgentTaskContext.direct(userMessage));
        }
        if (tracePublisher != null && tracePublisher.shouldTraceUserMessage(userMessage)) {
            String taskType = inferFallbackTaskType(userMessage, "");
            AgentTaskContext context = AgentTaskContext.agent(
                    userMessage,
                    userMessage,
                    "",
                    0,
                    false,
                    List.of(),
                    0.60,
                    "heuristic",
                    taskType,
                    "",
                    "",
                    "new_task",
                    AgentTaskMemoryAccess.none("启发式识别的新音乐任务。")
            );
            return explicitSongSearchContext(userMessage, context);
        }
        return AgentTaskContext.direct(userMessage);
    }

    private String routerInstruction() {
        return """
                你是 Musio 的对话轮次解析器。只输出 JSON 对象，不要 markdown，不要解释。
                你的任务是判断当前用户输入是否应该作为普通聊天处理，还是作为音乐 Agent 任务处理。

                输出格式：
                {"mode":"chat|agent","taskType":"chat|search|recommend|comments|lyrics|detail|playlist|profile|playback|unknown","contextMode":"new_task|follow_up|retry|refer_previous_song","followUp":true|false,"memoryAccess":{"useLastSearchKeyword":false,"useLastResultSongs":false,"useAvoidTitles":false,"useToolFailures":false,"reason":"为什么需要或不需要任务记忆"},"effectiveRequest":"用于本轮规划的完整用户请求","searchKeyword":"正向搜索关键词","searchLimit":数量,"targetSongId":"目标歌曲 provider-prefixed id","targetSongTitle":"目标歌曲名","avoidSongTitles":["需要排除的歌名"],"confidence":0.0到1.0}

                规则：
                - 普通寒暄、感谢、确认、情绪表达属于 chat。
                - 搜索歌曲、推荐歌曲、歌词、评论、歌单、歌手、专辑、播放相关请求属于 agent。
                - taskType 必须反映本轮最需要调用的音乐能力：搜索是 search，推荐是 recommend，热门评论/感人评论是 comments，歌词是 lyrics，歌曲详情/背景/故事/介绍是 detail，歌单是 playlist，音乐画像是 profile，播放控制是 playback。
                - playback 只用于暂停、继续播放、上一首、下一首、播放当前或已确定歌曲等控制动作；如果用户想播放但还需要先发现歌曲，应按搜索或推荐任务处理。
                - “推荐/找/搜索/播放 某歌手的某首明确歌曲”是精确搜歌任务，不是开放推荐；例如“给我推荐李荣浩的不遗憾”应输出 taskType=search，searchKeyword="李荣浩 不遗憾"。
                - 如果当前输入依赖最近对话上下文，请把它改写成一条完整、可独立执行的音乐请求，放入 effectiveRequest。
                - 当前用户输入优先级最高；只有用户明确说“再试试”“继续”“换一首”“类似刚才”“上一首/这首”等延续请求时，contextMode 才能是 follow_up、retry 或 refer_previous_song。
                - 新的开放推荐请求属于 new_task，不要继承上一轮场景、关键词或排除列表；memoryAccess 的所有布尔值应为 false。
                - 只有 contextMode 是 follow_up 或 retry 时，才可以使用 lastSearchKeyword、avoidSongTitles、lastToolFailures。
                - 只有 contextMode 是 refer_previous_song，或用户明确延续上一轮歌曲时，才可以使用 lastResultSongRefs / lastResultSongTitles。
                - 如果用户问“上一首歌/这首歌”的歌词、评论、背景、故事或详情，并且当前任务记忆里有 lastResultSongRefs，请把对应 provider-prefixed song id 填入 targetSongId，把歌名填入 targetSongTitle；searchKeyword 留空。
                - 评论、歌词、背景、详情类任务不要把“背景故事”“评论”“歌词”等需求词拼进 searchKeyword；这些词代表要调用的能力，不是歌曲搜索关键词。
                - 如果用户表达替代、排除已返回结果、或继续寻找不同结果的意图，avoidSongTitles 填入最近结果里本轮应排除的歌曲名。
                - 对搜索类任务，searchKeyword 只写正向搜索目标，例如歌手、歌曲名或风格，不要包含 avoidSongTitles 中的歌名，也不要包含排除/比较关系。
                - 对搜索类任务，如果用户明确要求数量，searchLimit 填入该数量；没有明确数量时填 0。
                - 对搜索类上下文延续，effectiveRequest 应包含可直接执行的搜索目标和数量，不要只写模糊指代。
                - 如果当前输入是全新的音乐请求，effectiveRequest 保持当前请求含义。
                - 如果不能确定，不要猜，输出 chat 且 confidence 低于 0.55。
                - 不要输出 chain-of-thought。
                """;
    }

    String taskMemoryPreview(AgentTaskMemory memory) {
        if (isBlankTaskMemory(memory)) {
            return "无";
        }
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "currentTask", memory.currentTask());
        appendLine(builder, "lastEffectiveRequest", memory.lastEffectiveRequest());
        appendLine(builder, "lastSearchKeyword", memory.lastSearchKeyword());
        if (memory.lastSearchLimit() != null && memory.lastSearchLimit() > 0) {
            appendLine(builder, "lastSearchLimit", String.valueOf(memory.lastSearchLimit()));
        }
        if (memory.lastResultSongs() != null && !memory.lastResultSongs().isEmpty()) {
            appendLine(builder, "lastResultSongRefs", memory.lastResultSongs().stream()
                    .limit(5)
                    .map(this::songRef)
                    .filter(ref -> !ref.isBlank())
                    .reduce((left, right) -> left + "；" + right)
                    .orElse(""));
        }
        if (memory.lastTargetSong() != null) {
            appendLine(builder, "lastTargetSong", songRef(memory.lastTargetSong()));
        }
        appendLine(builder, "lastCompletedTaskType", memory.lastCompletedTaskType());
        if (memory.lastObservationSummaries() != null && !memory.lastObservationSummaries().isEmpty()) {
            appendLine(builder, "lastObservationSummaries", String.join("；", memory.lastObservationSummaries().stream().limit(5).toList()));
        }
        if (memory.lastRequiredOutcomes() != null && !memory.lastRequiredOutcomes().isEmpty()) {
            appendLine(builder, "lastRequiredOutcomes", String.join("、", memory.lastRequiredOutcomes().stream().limit(10).toList()));
        }
        if (memory.lastRecommendationSlots() != null && !memory.lastRecommendationSlots().isEmpty()) {
            appendLine(builder, "lastRecommendationSlots", memory.lastRecommendationSlots().stream()
                    .limit(6)
                    .map(this::recommendationSlotSummary)
                    .filter(summary -> !summary.isBlank())
                    .reduce((left, right) -> left + "；" + right)
                    .orElse(""));
        }
        if (memory.lastEvidenceTools() != null && !memory.lastEvidenceTools().isEmpty()) {
            appendLine(builder, "lastEvidenceTools", String.join("、", memory.lastEvidenceTools().stream().limit(10).toList()));
        }
        if (memory.lastWriteIntentTools() != null && !memory.lastWriteIntentTools().isEmpty()) {
            appendLine(builder, "lastWriteIntentTools", String.join("、", memory.lastWriteIntentTools().stream().limit(10).toList()));
        }
        if (memory.recentRecommendedSongs() != null && !memory.recentRecommendedSongs().isEmpty()) {
            appendLine(builder, "recentRecommendedSongs", memory.recentRecommendedSongs().stream()
                    .limit(12)
                    .map(this::recentRecommendationSummary)
                    .filter(summary -> !summary.isBlank())
                    .reduce((left, right) -> left + "；" + right)
                    .orElse(""));
        }
        if (memory.lastResultSongTitles() != null && !memory.lastResultSongTitles().isEmpty()) {
            appendLine(builder, "lastResultSongTitles", String.join("、", memory.lastResultSongTitles().stream().limit(10).toList()));
        } else if (memory.lastResultSongs() != null && !memory.lastResultSongs().isEmpty()) {
            appendLine(builder, "lastResultSongTitles", memory.lastResultSongs().stream()
                    .map(Song::title)
                    .filter(title -> title != null && !title.isBlank())
                    .distinct()
                    .limit(10)
                    .reduce((left, right) -> left + "、" + right)
                    .orElse(""));
        }
        if (memory.avoidSongTitles() != null && !memory.avoidSongTitles().isEmpty()) {
            appendLine(builder, "avoidSongTitles", String.join("、", memory.avoidSongTitles()));
        }
        if (memory.lastToolFailures() != null && !memory.lastToolFailures().isEmpty()) {
            appendLine(builder, "lastToolFailures", memory.lastToolFailures().stream()
                    .limit(3)
                    .map(this::failureSummary)
                    .reduce((left, right) -> left + "；" + right)
                    .orElse(""));
        }
        return builder.toString().strip();
    }

    String plannerTaskMemoryPreview(AgentTaskContext taskContext, AgentTaskMemory memory) {
        if (taskContext == null || !taskContext.agentTask()) {
            return "无";
        }
        AgentTaskMemoryAccess access = taskContext.memoryAccess();
        if (access == null || access.none() || isBlankTaskMemory(memory)) {
            return "无";
        }
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "memoryAccessReason", access.reason());
        appendLine(builder, "currentTask", memory.currentTask());
        appendLine(builder, "lastEffectiveRequest", memory.lastEffectiveRequest());
        if (access.useLastSearchKeyword()) {
            appendLine(builder, "lastSearchKeyword", memory.lastSearchKeyword());
            if (memory.lastSearchLimit() != null && memory.lastSearchLimit() > 0) {
                appendLine(builder, "lastSearchLimit", String.valueOf(memory.lastSearchLimit()));
            }
        }
        if (access.useLastResultSongs()) {
            if (memory.lastResultSongs() != null && !memory.lastResultSongs().isEmpty()) {
                appendLine(builder, "lastResultSongRefs", memory.lastResultSongs().stream()
                        .limit(5)
                        .map(this::songRef)
                        .filter(ref -> !ref.isBlank())
                        .reduce((left, right) -> left + "；" + right)
                        .orElse(""));
            }
            if (memory.lastTargetSong() != null) {
                appendLine(builder, "lastTargetSong", songRef(memory.lastTargetSong()));
            }
            if (memory.lastResultSongTitles() != null && !memory.lastResultSongTitles().isEmpty()) {
                appendLine(builder, "lastResultSongTitles", String.join("、", memory.lastResultSongTitles().stream().limit(10).toList()));
            }
            if (memory.lastRecommendationSlots() != null && !memory.lastRecommendationSlots().isEmpty()) {
                appendLine(builder, "lastRecommendationSlots", memory.lastRecommendationSlots().stream()
                        .limit(6)
                        .map(this::recommendationSlotSummary)
                        .filter(summary -> !summary.isBlank())
                        .reduce((left, right) -> left + "；" + right)
                        .orElse(""));
            }
        }
        if (access.useAvoidTitles() && memory.avoidSongTitles() != null && !memory.avoidSongTitles().isEmpty()) {
            appendLine(builder, "avoidSongTitles", String.join("、", memory.avoidSongTitles()));
        }
        if (access.useToolFailures() && memory.lastToolFailures() != null && !memory.lastToolFailures().isEmpty()) {
            appendLine(builder, "lastToolFailures", memory.lastToolFailures().stream()
                    .limit(3)
                    .map(this::failureSummary)
                    .reduce((left, right) -> left + "；" + right)
                    .orElse(""));
        }
        String preview = builder.toString().strip();
        return preview.isBlank() ? "无" : preview;
    }

    private String songRef(Song song) {
        if (song == null) {
            return "";
        }
        String title = isBlank(song.title()) ? "未知歌曲" : song.title();
        String artists = song.artists() == null || song.artists().isEmpty() ? "未知歌手" : String.join("/", song.artists());
        String id = isBlank(song.id()) ? "无 id" : song.id();
        return title + " | " + artists + " | id=" + id;
    }

    private boolean isBlankTaskMemory(AgentTaskMemory memory) {
        if (memory == null) {
            return true;
        }
        return isBlank(memory.currentTask())
                && isBlank(memory.lastEffectiveRequest())
                && isBlank(memory.lastSearchKeyword())
                && (memory.lastResultSongTitles() == null || memory.lastResultSongTitles().isEmpty())
                && (memory.lastResultSongs() == null || memory.lastResultSongs().isEmpty())
                && memory.lastTargetSong() == null
                && isBlank(memory.lastCompletedTaskType())
                && (memory.lastObservationSummaries() == null || memory.lastObservationSummaries().isEmpty())
                && (memory.lastRequiredOutcomes() == null || memory.lastRequiredOutcomes().isEmpty())
                && (memory.lastRecommendationSlots() == null || memory.lastRecommendationSlots().isEmpty())
                && (memory.lastEvidenceTools() == null || memory.lastEvidenceTools().isEmpty())
                && (memory.lastWriteIntentTools() == null || memory.lastWriteIntentTools().isEmpty())
                && (memory.recentRecommendedSongs() == null || memory.recentRecommendedSongs().isEmpty())
                && (memory.lastToolFailures() == null || memory.lastToolFailures().isEmpty());
    }

    private void appendLine(StringBuilder builder, String key, String value) {
        if (!isBlank(value)) {
            builder.append(key).append(": ").append(truncate(value)).append('\n');
        }
    }

    private String failureSummary(AgentToolFailure failure) {
        if (failure == null) {
            return "";
        }
        return failure.toolName() + ": " + truncate(failure.message());
    }

    private String recommendationSlotSummary(AgentTaskRecommendationSlot slot) {
        if (slot == null || slot.slotId().isBlank()) {
            return "";
        }
        String songs = slot.songTitles().isEmpty() ? "无已命中歌曲" : String.join("/", slot.songTitles());
        return "%s:%s=%s x%s -> %s".formatted(slot.slotId(), slot.targetType(), slot.target(), slot.requestedCount(), songs);
    }

    private String recentRecommendationSummary(AgentRecentRecommendedSong recommendation) {
        if (recommendation == null || recommendation.title().isBlank()) {
            return "";
        }
        String artists = recommendation.artists().isEmpty() ? "" : " - " + String.join("/", recommendation.artists());
        return recommendation.title() + artists;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String historyPreview(List<ConversationHistoryMessage> history) {
        int start = Math.max(0, history.size() - RECENT_HISTORY_LIMIT);
        StringBuilder builder = new StringBuilder();
        for (ConversationHistoryMessage message : history.subList(start, history.size())) {
            builder.append(message.role())
                    .append(": ")
                    .append(truncate(message.content()))
                    .append('\n');
        }
        return builder.toString().strip();
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        String stripped = value.strip().replaceAll("\\s+", " ");
        if (stripped.length() <= MESSAGE_PREVIEW_LIMIT) {
            return stripped;
        }
        return stripped.substring(0, MESSAGE_PREVIEW_LIMIT) + "...";
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

    private String cleanSearchKeyword(String keyword, List<String> avoidSongTitles) {
        if (keyword == null || keyword.isBlank()) {
            return "";
        }
        String cleaned = keyword.strip();
        for (String title : avoidSongTitles) {
            if (title != null && !title.isBlank() && containsNormalized(cleaned, title)) {
                return "";
            }
        }
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    private AgentTaskContext explicitSongSearchContext(String userMessage, AgentTaskContext context) {
        Optional<ExplicitSongSearch> request = explicitSongSearch(userMessage);
        if (request.isEmpty()) {
            return context;
        }
        ExplicitSongSearch songSearch = request.get();
        int searchLimit = context == null ? 0 : context.searchLimit();
        double confidence = Math.max(context == null ? 0.0 : context.confidence(), 0.92);
        String source = context == null || context.source() == null || context.source().isBlank()
                ? "heuristic-explicit-song"
                : context.source() + "+explicit-song";
        return AgentTaskContext.agent(
                userMessage,
                "搜索 " + songSearch.artist() + "《" + songSearch.title() + "》",
                songSearch.artist() + " " + songSearch.title(),
                searchLimit,
                false,
                List.of(),
                confidence,
                source,
                "search",
                "",
                songSearch.title(),
                "new_task",
                AgentTaskMemoryAccess.none("明确歌手和歌曲名的请求按精确搜歌处理。")
        );
    }

    private Optional<ExplicitSongSearch> explicitSongSearch(String userMessage) {
        String value = safe(userMessage)
                .replaceAll("[。！？!?，,；;：:]+$", "")
                .strip();
        if (value.isBlank() || !hasSearchIntent(value) || hasSecondarySongIntent(value)) {
            return Optional.empty();
        }
        String stripped = stripSearchPrefix(value);
        int separator = stripped.indexOf("的");
        if (separator <= 0 || separator >= stripped.length() - 1) {
            return Optional.empty();
        }
        String artist = cleanExplicitSongPart(stripped.substring(0, separator));
        String title = cleanExplicitSongTitle(stripped.substring(separator + 1));
        if (artist.isBlank() || title.isBlank() || genericSongTitle(title)) {
            return Optional.empty();
        }
        if (artist.length() > 24 || title.length() > 40) {
            return Optional.empty();
        }
        return Optional.of(new ExplicitSongSearch(artist, title));
    }

    private boolean hasSearchIntent(String value) {
        String normalized = normalizeComparable(value);
        return normalized.contains("推荐")
                || normalized.contains("找")
                || normalized.contains("搜索")
                || normalized.contains("播放")
                || normalized.contains("想听")
                || normalized.contains("听一下")
                || normalized.contains("来一首");
    }

    private boolean hasSecondarySongIntent(String value) {
        String normalized = normalizeComparable(value);
        return normalized.contains("歌词")
                || normalized.contains("评论")
                || normalized.contains("背景")
                || normalized.contains("故事")
                || normalized.contains("详情")
                || normalized.contains("介绍");
    }

    private String stripSearchPrefix(String value) {
        String stripped = safe(value);
        boolean changed;
        do {
            String next = stripped.replaceFirst("^(请|麻烦|可以|能不能|帮我|给我|我想听|想听|推荐一下|推荐|找一下|找|搜索一下|搜索|播放一下|播放|放一下|听一下|来一首)", "").strip();
            changed = !next.equals(stripped);
            stripped = next;
        } while (changed);
        return stripped;
    }

    private String cleanExplicitSongPart(String value) {
        return safe(value)
                .replaceAll("^[《“\"'‘]+", "")
                .replaceAll("[》”\"'’]+$", "")
                .strip();
    }

    private String cleanExplicitSongTitle(String value) {
        return cleanExplicitSongPart(value)
                .replaceAll("(这首歌曲|这首歌|这首|歌曲)$", "")
                .strip();
    }

    private boolean genericSongTitle(String value) {
        String normalized = normalizeComparable(value);
        return List.of("歌", "歌曲", "音乐", "一首", "一首歌", "一首歌曲", "几首歌", "几首歌曲").contains(normalized);
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private boolean containsNormalized(String value, String candidate) {
        String normalizedValue = normalizeComparable(value);
        String normalizedCandidate = normalizeComparable(candidate);
        return !normalizedCandidate.isBlank() && normalizedValue.contains(normalizedCandidate);
    }

    private int cleanSearchLimit(int value) {
        if (value <= 0) {
            return 0;
        }
        return Math.max(1, Math.min(20, value));
    }

    private String cleanModelTaskType(String taskType) {
        String normalized = taskType == null ? "" : taskType.strip().toLowerCase(Locale.ROOT);
        if (List.of("chat", "search", "recommend", "comments", "lyrics", "detail", "playlist", "profile", "playback", "unknown").contains(normalized)) {
            return normalized;
        }
        return "unknown";
    }

    private String inferFallbackTaskType(String effectiveRequest, String searchKeyword) {
        String request = normalizeComparable(effectiveRequest);
        if (request.contains("评论")) {
            return "comments";
        }
        if (request.contains("歌词")) {
            return "lyrics";
        }
        if (request.contains("背景") || request.contains("故事") || request.contains("详情") || request.contains("介绍")) {
            return "detail";
        }
        if (request.contains("歌单")) {
            return "playlist";
        }
        if (request.contains("画像") || request.contains("音乐基因") || request.contains("偏好")) {
            return "profile";
        }
        if (request.contains("播放")) {
            return "playback";
        }
        if (request.contains("推荐")) {
            return "recommend";
        }
        if (!isBlank(searchKeyword) || request.contains("搜索") || request.contains("找")) {
            return "search";
        }
        return "unknown";
    }

    private String cleanContextMode(String contextMode, boolean followUp, String taskType, String targetSongId) {
        String normalized = contextMode == null ? "" : contextMode.strip().toLowerCase(Locale.ROOT);
        if (List.of("new_task", "follow_up", "retry", "refer_previous_song").contains(normalized)) {
            return normalized;
        }
        if (!isBlank(targetSongId) || List.of("comments", "lyrics", "detail", "playback").contains(taskType)) {
            return "refer_previous_song";
        }
        return followUp ? "follow_up" : "new_task";
    }

    private boolean isFollowUpContextMode(String contextMode) {
        return "follow_up".equals(contextMode)
                || "retry".equals(contextMode)
                || "refer_previous_song".equals(contextMode);
    }

    private AgentTaskMemoryAccess cleanMemoryAccess(JsonNode node, String contextMode) {
        if ("new_task".equals(contextMode)) {
            return AgentTaskMemoryAccess.none("新任务不读取上一轮任务记忆。");
        }
        if ("refer_previous_song".equals(contextMode)) {
            return new AgentTaskMemoryAccess(false, true, false, false, memoryReason(node, "需要引用上一轮歌曲。"));
        }
        if (!node.isObject()) {
            return new AgentTaskMemoryAccess(true, true, true, "retry".equals(contextMode), contextMode + " 需要读取上一轮任务记忆。");
        }
        return new AgentTaskMemoryAccess(
                node.path("useLastSearchKeyword").asBoolean(false),
                node.path("useLastResultSongs").asBoolean(false),
                node.path("useAvoidTitles").asBoolean(false),
                node.path("useToolFailures").asBoolean(false),
                memoryReason(node, contextMode + " 由 Router 授权读取任务记忆。")
        );
    }

    private String memoryReason(JsonNode node, String fallback) {
        if (node != null && node.path("reason").isTextual() && !node.path("reason").asText().isBlank()) {
            return node.path("reason").asText().strip();
        }
        return fallback;
    }

    private String normalizeComparable(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[《》<>\\[\\]【】()（）\\s]+", "")
                .strip();
    }

    private List<String> textArray(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new java.util.ArrayList<>();
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
}

record ExplicitSongSearch(
        String artist,
        String title
) {
}

record AgentTaskContext(
        String originalMessage,
        String planningMessage,
        String searchKeyword,
        int searchLimit,
        boolean agentTask,
        boolean followUp,
        List<String> avoidSongTitles,
        double confidence,
        String source,
        String taskType,
        String targetSongId,
        String targetSongTitle,
        String contextMode,
        AgentTaskMemoryAccess memoryAccess
) {
    static AgentTaskContext direct(String message) {
        return direct(message, 1.0, "direct");
    }

    static AgentTaskContext direct(String message, double confidence, String source) {
        return new AgentTaskContext(message, message, "", 0, false, false, List.of(), confidence, source, "chat", "", "", "new_task", AgentTaskMemoryAccess.none("普通聊天不读取任务记忆。"));
    }

    static AgentTaskContext agent(
            String message,
            String effectiveRequest,
            String searchKeyword,
            int searchLimit,
            boolean followUp,
            List<String> avoidSongTitles,
            double confidence,
            String source,
            String taskType,
            String targetSongId,
            String targetSongTitle
    ) {
        String contextMode = followUp ? "follow_up" : "new_task";
        AgentTaskMemoryAccess memoryAccess = followUp
                ? new AgentTaskMemoryAccess(true, true, true, true, "兼容旧调用：上下文延续任务读取上一轮任务记忆。")
                : AgentTaskMemoryAccess.none("兼容旧调用：新任务不读取上一轮任务记忆。");
        return agent(message, effectiveRequest, searchKeyword, searchLimit, followUp, avoidSongTitles, confidence, source, taskType, targetSongId, targetSongTitle, contextMode, memoryAccess);
    }

    static AgentTaskContext agent(
            String message,
            String effectiveRequest,
            String searchKeyword,
            int searchLimit,
            boolean followUp,
            List<String> avoidSongTitles,
            double confidence,
            String source,
            String taskType,
            String targetSongId,
            String targetSongTitle,
            String contextMode,
            AgentTaskMemoryAccess memoryAccess
    ) {
        return new AgentTaskContext(message, effectiveRequest, searchKeyword, searchLimit, true, followUp, List.copyOf(avoidSongTitles), confidence, source, taskType, targetSongId, targetSongTitle, contextMode, memoryAccess == null ? AgentTaskMemoryAccess.none("未授权任务记忆。") : memoryAccess);
    }

    boolean searchPreludeAllowed() {
        return "search".equals(taskType);
    }

    boolean recommendationPreludeAllowed() {
        return "recommend".equals(taskType);
    }

    boolean toolEvidenceExpected() {
        return "search".equals(taskType)
                || "recommend".equals(taskType)
                || "comments".equals(taskType)
                || "lyrics".equals(taskType)
                || "detail".equals(taskType)
                || "playlist".equals(taskType)
                || "profile".equals(taskType)
                || ("playback".equals(taskType) && !searchKeyword.isBlank());
    }

    boolean preservePreviousSongContext() {
        return "correction".equals(contextMode)
                || "comments".equals(taskType)
                || "lyrics".equals(taskType)
                || "detail".equals(taskType)
                || "playback".equals(taskType);
    }

    String promptContext() {
        if (!agentTask) {
            return "";
        }
        return """

                本轮用户的原话是：%s
                Turn Planner 判断这是一条%s。
                本轮任务类型是：%s
                本轮用于规划和工具调用的完整请求是：%s
                本轮正向搜索关键词是：%s
                本轮搜索数量提示是：%s
                本轮目标歌曲 ID 是：%s
                本轮目标歌曲名是：%s
                本轮上下文模式是：%s
                本轮任务记忆权限是：%s
                本轮需要避免重复这些歌曲：%s
                请按这个完整请求重新执行，不要只根据历史回答声称已经完成。
                评论、歌词、背景、详情类任务应优先使用目标歌曲 ID 调用对应工具，不要改成搜索关键词。
                如果系统消息提供了新的搜索结果或候选结果，最终回答必须基于这些新的真实结果。
                """.formatted(
                originalMessage,
                followUp ? "上下文延续请求" : "音乐任务请求",
                taskType,
                planningMessage,
                searchKeyword.isBlank() ? "未指定" : searchKeyword,
                searchLimit <= 0 ? "未指定" : String.valueOf(searchLimit),
                targetSongId.isBlank() ? "未指定" : targetSongId,
                targetSongTitle.isBlank() ? "未指定" : targetSongTitle,
                contextMode,
                memoryAccess == null ? "无" : memoryAccess.summary(),
                avoidSongTitles.isEmpty() ? "无" : String.join("、", avoidSongTitles)
        );
    }
}

record AgentTaskMemoryAccess(
        boolean useLastSearchKeyword,
        boolean useLastResultSongs,
        boolean useAvoidTitles,
        boolean useToolFailures,
        String reason
) {
    static AgentTaskMemoryAccess none(String reason) {
        return new AgentTaskMemoryAccess(false, false, false, false, reason);
    }

    boolean none() {
        return !useLastSearchKeyword && !useLastResultSongs && !useAvoidTitles && !useToolFailures;
    }

    String summary() {
        return "useLastSearchKeyword=" + useLastSearchKeyword
                + ", useLastResultSongs=" + useLastResultSongs
                + ", useAvoidTitles=" + useAvoidTitles
                + ", useToolFailures=" + useToolFailures
                + ", reason=" + reason;
    }
}
