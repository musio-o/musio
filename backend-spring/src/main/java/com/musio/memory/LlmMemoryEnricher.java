package com.musio.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.AgentGoal;
import com.musio.agent.AgentLlmLogger;
import com.musio.agent.loop.AgentLoopEvidence;
import com.musio.agent.loop.AgentObservation;
import com.musio.ai.SpringAiChatModelFactory;
import com.musio.config.MusioConfig;
import com.musio.config.MusioConfigService;
import com.musio.model.AgentRecentRecommendedSong;
import com.musio.model.AgentTaskMemory;
import com.musio.model.AgentTaskRecommendationSlot;
import com.musio.model.Song;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class LlmMemoryEnricher {
    private static final Logger log = LoggerFactory.getLogger(LlmMemoryEnricher.class);
    private static final double MIN_CONFIDENCE = 0.55;
    private static final int VALUE_PREVIEW_LIMIT = 900;

    private final SpringAiChatModelFactory chatModelFactory;
    private final MusioConfigService configService;
    private final ObjectMapper objectMapper;

    public LlmMemoryEnricher(
            SpringAiChatModelFactory chatModelFactory,
            MusioConfigService configService,
            ObjectMapper objectMapper
    ) {
        this.chatModelFactory = chatModelFactory;
        this.configService = configService;
        this.objectMapper = objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper.findAndRegisterModules();
    }

    public MemoryEnrichmentResult enrich(MemoryWriteRequest request) {
        if (chatModelFactory == null || configService == null || request == null) {
            return MemoryEnrichmentResult.empty();
        }
        MusioConfig.Ai ai = configService.config().ai();
        Prompt prompt = buildPrompt(request);
        try {
            AgentLlmLogger.logRequest("memory_enrichment", ai, prompt);
            String content = chatModelFactory.chatClient(ai)
                    .prompt(prompt)
                    .call()
                    .content();
            AgentLlmLogger.logResponse("memory_enrichment", ai, content);
            return parseResult(content).orElseGet(MemoryEnrichmentResult::empty);
        } catch (Exception e) {
            log.warn("LLM memory enrichment failed for user {}", request.userId(), e);
            return MemoryEnrichmentResult.empty();
        }
    }

    Prompt buildPrompt(MemoryWriteRequest request) {
        return new Prompt(List.of(
                new SystemMessage(instruction()),
                new UserMessage("""
                        用户输入：
                        %s

                        任务目标：
                        %s

                        短期任务记忆：
                        %s

                        工具证据：
                        %s

                        最终回答：
                        %s
                        """.formatted(
                        request.userMessage(),
                        goalPreview(request.goal()),
                        taskMemoryPreview(request.taskMemory()),
                        evidencePreview(request.loopEvidence()),
                        truncate(request.finalAnswer())
                ))
        ));
    }

    Optional<MemoryEnrichmentResult> parseResult(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(content.strip()));
            double confidence = root.path("confidence").asDouble(1.0);
            if (confidence < MIN_CONFIDENCE) {
                return Optional.empty();
            }
            List<LlmPreferenceCandidate> preferences = parsePreferences(root.path("preferenceCandidates"));
            LlmConversationSummary summary = parseConversationSummary(root.path("conversationSummary"));
            List<LlmMusicInsight> insights = parseMusicInsights(root.path("musicInsights"));
            MemoryEnrichmentResult result = new MemoryEnrichmentResult(preferences, summary, insights, confidence);
            return result.isEmpty() ? Optional.empty() : Optional.of(result);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private List<LlmPreferenceCandidate> parsePreferences(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<LlmPreferenceCandidate> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                continue;
            }
            values.add(new LlmPreferenceCandidate(
                    text(item, "polarity"),
                    text(item, "name"),
                    text(item, "label"),
                    item.path("confidenceDelta").asDouble(0.1),
                    text(item, "scope"),
                    text(item, "evidence")
            ));
        }
        return values.stream().limit(8).toList();
    }

    private LlmConversationSummary parseConversationSummary(JsonNode node) {
        if (!node.isObject()) {
            return new LlmConversationSummary("", List.of());
        }
        return new LlmConversationSummary(text(node, "summary"), textArray(node.path("keywords"), 20));
    }

    private List<LlmMusicInsight> parseMusicInsights(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<LlmMusicInsight> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                continue;
            }
            values.add(new LlmMusicInsight(
                    "llmMusicInsight",
                    text(item, "songId"),
                    text(item, "title"),
                    text(item, "artist"),
                    text(item, "content"),
                    text(item, "evidence")
            ));
        }
        return values.stream().limit(10).toList();
    }

    private String instruction() {
        return """
                你是 Musio 的异步记忆增强器。只输出 JSON 对象，不要 markdown，不要解释。
                只提取对未来音乐推荐、偏好理解或任务延续有用的信息。
                不要把寒暄、无音乐任务闲聊、临时情绪误判为长期偏好。
                用户说“这首歌”“这个场景”“这种歌”“这个氛围”等指代表达时，必须优先用短期任务记忆解析上一轮歌曲、场景、推荐目标和工具结果。
                如果短期任务记忆能解析出明确场景或歌曲，不要输出“场景未明确”。
                preference scope 只能是 session、long_term、ignore。
                musicInsights 只写评论、歌词、歌曲详情或推荐理由中对未来检索有用的事实。

                输出格式：
                {"preferenceCandidates":[{"polarity":"positive|negative","name":"stable_key","label":"中文标签","confidenceDelta":0.0到0.3,"scope":"session|long_term|ignore","evidence":"原文依据"}],"conversationSummary":{"summary":"面向未来的中文摘要","keywords":["关键词"]},"musicInsights":[{"songId":"","title":"","artist":"","content":"音乐洞察","evidence":"来源说明"}],"confidence":0.0到1.0}
                """;
    }

    private String goalPreview(AgentGoal goal) {
        if (goal == null) {
            return "无";
        }
        return """
                taskType=%s
                contextMode=%s
                effectiveRequest=%s
                musicTask=%s
                requiredOutcomes=%s
                """.formatted(
                safe(goal.taskType()),
                safe(goal.contextMode()),
                truncate(goal.effectiveRequest()),
                goal.musicTask(),
                goal.requiredOutcomes()
        ).strip();
    }

    private String taskMemoryPreview(AgentTaskMemory memory) {
        if (memory == null || isBlankTaskMemory(memory)) {
            return "无";
        }
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "lastEffectiveRequest", memory.lastEffectiveRequest());
        appendLine(builder, "currentTask", memory.currentTask());
        appendLine(builder, "lastCompletedTaskType", memory.lastCompletedTaskType());
        appendLine(builder, "lastTargetSong", songRef(memory.lastTargetSong()));
        if (!memory.lastResultSongs().isEmpty()) {
            appendLine(builder, "lastResultSongs", String.join("；", memory.lastResultSongs().stream()
                    .limit(8)
                    .map(this::songRef)
                    .filter(value -> !value.isBlank())
                    .toList()));
        }
        if (!memory.lastRecommendationSlots().isEmpty()) {
            appendLine(builder, "lastRecommendationSlots", String.join("；", memory.lastRecommendationSlots().stream()
                    .limit(6)
                    .map(this::recommendationSlotRef)
                    .filter(value -> !value.isBlank())
                    .toList()));
        }
        if (!memory.lastObservationSummaries().isEmpty()) {
            appendLine(builder, "lastObservationSummaries", String.join("；", memory.lastObservationSummaries().stream()
                    .limit(6)
                    .map(this::truncate)
                    .toList()));
        }
        if (!memory.lastRequiredOutcomes().isEmpty()) {
            appendLine(builder, "lastRequiredOutcomes", String.join("、", memory.lastRequiredOutcomes().stream().limit(10).toList()));
        }
        if (!memory.recentRecommendedSongs().isEmpty()) {
            appendLine(builder, "recentRecommendedSongs", String.join("；", memory.recentRecommendedSongs().stream()
                    .limit(8)
                    .map(this::recentRecommendationRef)
                    .filter(value -> !value.isBlank())
                    .toList()));
        }
        String preview = builder.toString().strip();
        return preview.isBlank() ? "无" : preview;
    }

    private boolean isBlankTaskMemory(AgentTaskMemory memory) {
        return safe(memory.currentTask()).isBlank()
                && safe(memory.lastEffectiveRequest()).isBlank()
                && safe(memory.lastCompletedTaskType()).isBlank()
                && memory.lastTargetSong() == null
                && memory.lastResultSongs().isEmpty()
                && memory.lastRecommendationSlots().isEmpty()
                && memory.lastObservationSummaries().isEmpty()
                && memory.lastRequiredOutcomes().isEmpty()
                && memory.recentRecommendedSongs().isEmpty();
    }

    private String recommendationSlotRef(AgentTaskRecommendationSlot slot) {
        if (slot == null) {
            return "";
        }
        return "slot=%s type=%s target=%s count=%s songs=%s titles=%s".formatted(
                safe(slot.slotId()),
                safe(slot.targetType()),
                safe(slot.target()),
                slot.requestedCount(),
                String.join(",", slot.songIds().stream().limit(6).toList()),
                String.join("/", slot.songTitles().stream().limit(6).toList())
        ).strip();
    }

    private String recentRecommendationRef(AgentRecentRecommendedSong song) {
        if (song == null) {
            return "";
        }
        return "%s - %s [%s] reason=%s".formatted(
                safe(song.title()),
                String.join("/", song.artists()),
                safe(song.songId()),
                truncate(song.reason())
        ).strip();
    }

    private void appendLine(StringBuilder builder, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        builder.append(key).append(": ").append(truncate(value)).append('\n');
    }

    private String evidencePreview(AgentLoopEvidence evidence) {
        if (evidence == null || !evidence.hasObservations()) {
            return "无";
        }
        List<String> parts = new ArrayList<>();
        if (evidence.targetSong() != null) {
            parts.add("targetSong=" + songRef(evidence.targetSong()));
        }
        if (!evidence.songs().isEmpty()) {
            parts.add("songs=" + String.join("；", evidence.songs().stream()
                    .limit(12)
                    .map(this::songRef)
                    .filter(value -> !value.isBlank())
                    .toList()));
        }
        for (AgentObservation observation : evidence.observations().stream().limit(8).toList()) {
            parts.add("""
                    tool=%s status=%s summary=%s songs=%s result=%s
                    """.formatted(
                    safe(observation.toolName()),
                    observation.status(),
                    truncate(observation.plannerSummary()),
                    String.join("；", observation.songs().stream().limit(8).map(this::songRef).toList()),
                    truncate(observation.resultJson())
            ).strip());
        }
        String preview = String.join("\n", parts).strip();
        return preview.isBlank() ? "无" : preview;
    }

    private String songRef(Song song) {
        if (song == null) {
            return "";
        }
        String artists = song.artists() == null ? "" : String.join("/", song.artists());
        return "%s - %s [%s]".formatted(safe(song.title()), artists, safe(song.id())).strip();
    }

    private List<String> textArray(JsonNode node, int limit) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String text = item.asText("");
            if (!text.isBlank()) {
                values.add(text.strip());
            }
        }
        return values.stream().distinct().limit(limit).toList();
    }

    private String text(JsonNode node, String field) {
        return node == null ? "" : safe(node.path(field).asText(""));
    }

    private String extractJsonObject(String value) {
        if (value == null) {
            return "{}";
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return value.substring(start, end + 1);
        }
        return value;
    }

    private String truncate(String value) {
        String safe = safe(value);
        if (safe.length() <= VALUE_PREVIEW_LIMIT) {
            return safe;
        }
        return safe.substring(0, VALUE_PREVIEW_LIMIT - 3).strip() + "...";
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
