package com.musio.agent.loop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.AgentLlmLogger;
import com.musio.agent.AgentRunContext;
import com.musio.agent.ConversationHistoryMessage;
import com.musio.agent.capability.AgentCapabilityArgumentContext;
import com.musio.agent.capability.AgentCapabilityManifest;
import com.musio.agent.capability.AgentCapabilityRegistry;
import com.musio.agent.capability.AgentCapabilityValidationResult;
import com.musio.ai.SpringAiChatModelFactory;
import com.musio.config.MusioConfig;
import com.musio.model.AgentRecentRecommendedSong;
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

                        当前 Agent Goal：
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
                        goalPreview(state),
                        historyPreview(state == null ? List.of() : state.recentHistory()),
                        observationPreview(state == null ? List.of() : state.observations()),
                        requestedSongCountPreview(state),
                        state == null ? "" : state.userMessage()
                ))
        ));
        try {
            AgentLlmLogger.logRequest("agent_step_planner", ai, prompt);
            log.info(
                    "AGENT_STEP_PLANNER_REQUEST stage=agent_step_planner runId={} userId={} provider={} model={} taskType={} manifestTools={} stepCount={} observationCount={}",
                    currentRunId(),
                    currentUserId(),
                    ai == null ? "" : safe(ai.provider()),
                    ai == null ? "" : safe(ai.model()),
                    state == null || state.goal() == null ? "" : safe(state.goal().taskType()),
                    String.join(",", manifest.names()),
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
                AgentCapabilityArgumentContext argumentContext = AgentCapabilityArgumentContext.stepPlanner(requestedSongCount);
                arguments = capabilityRegistry.normalizeArguments(toolName, arguments, argumentContext);
                AgentCapabilityValidationResult validation = capabilityRegistry.validateArguments(toolName, arguments, argumentContext);
                if (!validation.valid()) {
                    logRejectedAction(toolName, validation.reason());
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
                你每次只决定下一步 action；后端执行工具后，会把 observation 再交给你继续判断。不要一次性列完整工具链。

                本轮可用能力：
                %s

                输出格式：
                {"action":"tool_call|final_answer|request_confirmation|unsupported","toolName":"工具名或空","arguments":{},"publicActivity":"用户可见动作摘要","confidence":0.0到1.0,"reason":"为什么下一步这样做"}

                规则：
                - 每次最多输出一个 action。
                - 需要真实音乐数据时输出 action=tool_call。
                - 信息已经足够回答时输出 action=final_answer。
                - 不要编造 songId 或 playlistId；这类 id 必须来自用户输入、当前任务记忆或本轮 observations。
                - 开放推荐、场景推荐、风格推荐或心境推荐，且 recommend_songs 出现在“本轮可用能力”时，优先调用 recommend_songs；不要直接把“深夜学习”“写代码”“治愈”等场景词塞进 search_songs.keyword。
                - recommend_songs 会先生成具体歌曲候选，再精确匹配真实歌曲；它的 songs observation 可作为后续评论、歌词、详情或收藏的 songId 来源。
                - recommend_songs.request 应保留用户完整推荐需求；count 应等于本轮推荐总数，未明确时默认 5。
                - 如果 Agent Goal 里有 recommendationSlots，recommend_songs.arguments 必须带 slots 原样传递，并让 count 等于这些 slots 的 count 总和；不要把多目标推荐压成单个“一首”。
                - 当前任务记忆里的 recentRecommendedSongs 是近期已推荐歌曲；普通连续推荐应优先避免重复。用户明确点名、要求经典代表作、或继续讨论同一首时，可以允许重复。
                - recommend_songs observation 会提供 requestedTotal、resolvedTotal、slotResults、songs、unresolved；推荐是否完成必须以这些结构化覆盖度为准。
                - 如果 recommend_songs 已成功返回足够 songs / slotResults 已覆盖所有 slots，且用户没有继续要求评论、歌词、详情或写入，下一步应 final_answer。
                - 如果用户要歌词、评论或歌曲详情，但当前没有目标 songId，下一步应先搜索或利用已有 observation / 任务记忆里的歌曲 id。
                - 如果用户要对多首已推荐/已搜索歌曲读取歌词或热门评论，优先用 get_lyrics.songIds 或 get_hot_comments.songIds 一次传入多个 songId，不要逐首拆成多次工具调用。
                - 如果同一个 songId 的 get_hot_comments / get_lyrics / get_song_detail 已经成功出现在 observations 中，不要再次调用同一个工具；应继续处理还没完成的目标。
                - search_songs.keyword 只写正向搜索目标，例如歌手、歌曲名或风格；不要把排除、比较或“不是 X 是 Y”这类关系拼进 keyword。
                - search_songs.limit 必须显式填写；完全没有数量含义时默认 5。
                - 如果“本轮用户要求的歌曲数量”是明确数字，search_songs.limit 不得超过这个数字；例如用户说“推荐一首”时 limit 必须是 1。
                - 复合任务中的数量约束必须贯穿搜索、评论和收藏步骤；用户说一首时，只围绕一首歌继续读取评论和收藏。
                - get_hot_comments.limit 默认 10，最大 30。
                - 如果用户说“最热门的评论/一条评论/一条热评”，get_hot_comments.limit 应填写 1。
                - add_song_to_musio_playlist 只能在用户已经确认收藏的确认轮次调用；初次表达收藏/保存/加入 Musio 歌单意图时，不要直接写入，应让后端保存待确认目标并请求用户确认。
                - 如果用户要把多首已推荐/已搜索歌曲加入 Musio 歌单，优先用 add_song_to_musio_playlist.songIds 一次传入多个 songId；没有 songId 但有明确序号时才用 songIndexes。
                - add_song_to_musio_playlist 如果已经能确定 songId/songIds，不要再同时填写可能冲突的 songIndex/songIndexes；序号只用于“第一首/第二首/这几首”这种没有明确 songId 的引用。
                - 如果用户要求写入 QQ 音乐账号、公开评论、账号收藏等 account_write 能力，但本轮可用能力没有对应工具，应输出 request_confirmation 或 unsupported，不要改用本地 Musio 写入冒充账号写入。
                - add_song_to_musio_playlist 至少应提供 songId、songIds、songIndex、songIndexes、songTitle 之一；playlistId 缺省为 default。
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

    private String requestedSongCountPreview(AgentLoopState state) {
        int count = state == null ? 0 : state.requestedSongCount();
        return count <= 0 ? "未明确，缺省按工具规则处理" : String.valueOf(count);
    }

    private String goalPreview(AgentLoopState state) {
        if (state == null || state.goal() == null) {
            return "无";
        }
        return state.goal().plannerSummary();
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
        if (memory.recentRecommendedSongs() != null && !memory.recentRecommendedSongs().isEmpty()) {
            appendLine(builder, "recentRecommendedSongs", String.join("；", memory.recentRecommendedSongs().stream()
                    .limit(12)
                    .map(this::recentRecommendationSummary)
                    .filter(summary -> !summary.isBlank())
                    .toList()));
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

    private String recentRecommendationSummary(AgentRecentRecommendedSong recommendation) {
        if (recommendation == null || recommendation.title().isBlank()) {
            return "";
        }
        String artists = recommendation.artists().isEmpty() ? "" : " - " + String.join("/", recommendation.artists());
        String slot = recommendation.slotId().isBlank() ? "" : " slot=" + recommendation.slotId();
        return recommendation.title() + artists + slot;
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
