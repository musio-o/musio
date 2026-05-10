package com.musio.agent.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.AgentLlmLogger;
import com.musio.ai.SpringAiChatModelFactory;
import com.musio.config.MusioConfig;
import com.musio.model.AgentRecentRecommendedSong;
import com.musio.memory.MusicProfileService;
import com.musio.model.AgentTaskMemory;
import com.musio.model.MusicProfileMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class RecommendationDraftGenerator {
    private static final Logger log = LoggerFactory.getLogger(RecommendationDraftGenerator.class);
    private static final double MIN_CONFIDENCE = 0.55;
    private static final int DEFAULT_COUNT = 5;
    private static final int MAX_CANDIDATES = 10;

    private final SpringAiChatModelFactory chatModelFactory;
    private final ObjectMapper objectMapper;
    private final MusicProfileService musicProfileService;

    public RecommendationDraftGenerator(
            SpringAiChatModelFactory chatModelFactory,
            ObjectMapper objectMapper,
            MusicProfileService musicProfileService
    ) {
        this.chatModelFactory = chatModelFactory;
        this.objectMapper = objectMapper;
        this.musicProfileService = musicProfileService;
    }

    public Optional<RecommendationDraft> generate(
            MusioConfig.Ai ai,
            String userRequest,
            int requestedCount,
            List<String> avoidSongTitles,
            AgentTaskMemory taskMemory
    ) {
        if (chatModelFactory == null || ai == null) {
            return Optional.empty();
        }
        int count = requestedCount(requestedCount);
        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(instruction()),
                    new UserMessage("""
                            当前用户请求：
                            %s

                            需要推荐数量：%s

                            当前用户音乐画像：
                            %s

                            本轮应避免重复的歌名：
                            %s

                            最近任务记忆（只作短期上下文，不是事实来源）：
                            %s
                            """.formatted(
                            safe(userRequest),
                            count,
                            musicProfilePreview(),
                            avoidSongTitles == null || avoidSongTitles.isEmpty() ? "无" : String.join("、", avoidSongTitles),
                            taskMemoryPreview(taskMemory)
                    ))
            ));
            AgentLlmLogger.logRequest("recommendation_draft", ai, prompt);
            String content = chatModelFactory.chatClient(ai)
                    .prompt(prompt)
                    .call()
                    .content();
            AgentLlmLogger.logResponse("recommendation_draft", ai, content);
            return filterAvoidedTitles(parseDraft(content, count), avoidSongTitles);
        } catch (Exception e) {
            log.warn("Recommendation draft generation failed", e);
            return Optional.empty();
        }
    }

    public Optional<RecommendationDraft> generate(
            MusioConfig.Ai ai,
            String userRequest,
            List<RecommendationSlot> recommendationSlots,
            List<String> avoidSongTitles,
            AgentTaskMemory taskMemory
    ) {
        List<RecommendationSlot> slots = RecommendationSlots.normalize(recommendationSlots);
        if (slots.isEmpty()) {
            return generate(ai, userRequest, 0, avoidSongTitles, taskMemory);
        }
        if (chatModelFactory == null || ai == null) {
            return Optional.empty();
        }
        int count = requestedCount(RecommendationSlots.totalCount(slots));
        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(instruction()),
                    new UserMessage("""
                            当前用户请求：
                            %s

                            结构化推荐目标：
                            %s

                            需要推荐总数：%s

                            当前用户音乐画像：
                            %s

                            本轮应避免重复的歌名：
                            %s

                            最近任务记忆（只作短期上下文，不是事实来源）：
                            %s
                            """.formatted(
                            safe(userRequest),
                            slotPreview(slots),
                            count,
                            musicProfilePreview(),
                            avoidSongTitles == null || avoidSongTitles.isEmpty() ? "无" : String.join("、", avoidSongTitles),
                            taskMemoryPreview(taskMemory)
                    ))
            ));
            AgentLlmLogger.logRequest("recommendation_draft", ai, prompt);
            String content = chatModelFactory.chatClient(ai)
                    .prompt(prompt)
                    .call()
                    .content();
            AgentLlmLogger.logResponse("recommendation_draft", ai, content);
            return filterAvoidedTitles(parseDraft(content, count, slots), avoidSongTitles);
        } catch (Exception e) {
            log.warn("Recommendation draft generation failed", e);
            return Optional.empty();
        }
    }

    Optional<RecommendationDraft> parseDraft(String content, int requestedCount) {
        return parseDraft(content, requestedCount, List.of());
    }

    Optional<RecommendationDraft> parseDraft(String content, int requestedCount, List<RecommendationSlot> recommendationSlots) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(content.strip()));
            double confidence = root.path("confidence").asDouble(0.0);
            if (confidence < MIN_CONFIDENCE) {
                return Optional.empty();
            }
            JsonNode candidatesNode = root.path("candidates");
            if (!candidatesNode.isArray()) {
                candidatesNode = root.path("recommendations");
            }
            if (!candidatesNode.isArray()) {
                candidatesNode = root.path("items");
            }
            if (!candidatesNode.isArray()) {
                return Optional.empty();
            }

            int limit = requestedCount(requestedCount);
            List<RecommendationSlot> slots = RecommendationSlots.normalize(recommendationSlots);
            Map<String, Integer> requestedBySlot = requestedBySlot(slots);
            Map<String, Integer> candidateCountBySlot = new LinkedHashMap<>();
            Set<String> seen = new LinkedHashSet<>();
            List<RecommendationCandidate> candidates = new ArrayList<>();
            for (JsonNode candidateNode : candidatesNode) {
                String title = text(candidateNode, "title");
                String artist = text(candidateNode, "artist");
                String reason = text(candidateNode, "reason");
                String slotId = slotId(candidateNode, slots);
                if (title.isBlank() || artist.isBlank() || reason.isBlank()) {
                    continue;
                }
                if (!slots.isEmpty()) {
                    if (slotId.isBlank() || !requestedBySlot.containsKey(slotId)) {
                        continue;
                    }
                    int acceptedForSlot = candidateCountBySlot.getOrDefault(slotId, 0);
                    if (acceptedForSlot >= requestedBySlot.get(slotId)) {
                        continue;
                    }
                }
                String key = normalizeKey(title) + "|" + normalizeKey(artist) + "|" + normalizeKey(slotId);
                if (!seen.add(key)) {
                    continue;
                }
                candidates.add(new RecommendationCandidate(title, artist, reason, slotId));
                if (!slotId.isBlank()) {
                    candidateCountBySlot.put(slotId, candidateCountBySlot.getOrDefault(slotId, 0) + 1);
                }
                if (candidates.size() >= limit) {
                    break;
                }
            }
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new RecommendationDraft(candidates, confidence, source(root)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String instruction() {
        return """
                你是 Musio 的推荐草稿生成器。只输出 JSON 对象，不要 markdown，不要解释。
                你的任务是先决定“应该推荐哪些具体歌曲”，不是搜索 QQ 音乐。

                输出格式：
                {"candidates":[{"slotId":"推荐目标 slotId，可为空","title":"歌曲名","artist":"歌手名","reason":"一句中文推荐理由"}],"confidence":0.0到1.0,"source":"model"}

                规则：
                - 必须输出具体歌曲名和歌手名；不要输出“深夜写代码”“助眠白噪音”这类场景词作为 title。
                - 如果用户消息提供了结构化推荐目标，每个 candidates 项必须带对应 slotId，并且每个 slotId 的数量尽量等于该 slot 的 count。
                - 推荐理由要根据用户当前请求和音乐画像写，不要声称已经搜索或播放。
                - title 和 artist 必须是可用于音乐平台搜索的真实歌曲信息。
                - 避免重复“本轮应避免重复的歌名”。
                - recentRecommendedSongs 代表近期已经推荐过的歌曲；普通连续推荐不要重复它们。用户明确点名、要求经典代表作、或继续讨论同一首时，可以有意重复。
                - candidates 数量尽量等于需要推荐数量。
                - 不要输出 chain-of-thought。
                """;
    }

    private Optional<RecommendationDraft> filterAvoidedTitles(Optional<RecommendationDraft> draft, List<String> avoidSongTitles) {
        if (draft.isEmpty() || avoidSongTitles == null || avoidSongTitles.isEmpty()) {
            return draft;
        }
        Set<String> avoided = avoidSongTitles.stream()
                .map(this::normalizeTitle)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (avoided.isEmpty()) {
            return draft;
        }
        List<RecommendationCandidate> candidates = draft.get().candidates().stream()
                .filter(candidate -> candidate != null && !avoided.contains(normalizeTitle(candidate.title())))
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RecommendationDraft(candidates, draft.get().confidence(), draft.get().source()));
    }

    private String musicProfilePreview() {
        if (musicProfileService == null) {
            return "暂无";
        }
        return musicProfileService.readOrCreate()
                .map(this::profilePreview)
                .orElse("暂无");
    }

    private String profilePreview(MusicProfileMemory profile) {
        return """
                摘要：%s
                高频歌手：%s
                偏好专辑：%s
                推荐提示：%s
                """.formatted(
                safe(profile.summary()),
                joinLimited(profile.favoriteArtists(), 8),
                joinLimited(profile.favoriteAlbums(), 5),
                joinLimited(profile.recommendationHints(), 4)
        );
    }

    private String taskMemoryPreview(AgentTaskMemory memory) {
        if (memory == null) {
            return "无";
        }
        List<String> parts = new ArrayList<>();
        if (!safe(memory.lastEffectiveRequest()).isBlank()) {
            parts.add("lastEffectiveRequest: " + safe(memory.lastEffectiveRequest()));
        }
        if (memory.lastResultSongTitles() != null && !memory.lastResultSongTitles().isEmpty()) {
            parts.add("lastResultSongTitles: " + String.join("、", memory.lastResultSongTitles().stream().limit(10).toList()));
        }
        if (memory.recentRecommendedSongs() != null && !memory.recentRecommendedSongs().isEmpty()) {
            parts.add("recentRecommendedSongs: " + String.join("；", memory.recentRecommendedSongs().stream()
                    .limit(15)
                    .map(this::recentRecommendationSummary)
                    .filter(summary -> !summary.isBlank())
                    .toList()));
        }
        return parts.isEmpty() ? "无" : String.join("\n", parts);
    }

    private String recentRecommendationSummary(AgentRecentRecommendedSong recommendation) {
        if (recommendation == null || recommendation.title().isBlank()) {
            return "";
        }
        String artists = recommendation.artists().isEmpty() ? "" : " - " + String.join("/", recommendation.artists());
        String slot = recommendation.slotId().isBlank() ? "" : " slot=" + recommendation.slotId();
        return recommendation.title() + artists + slot;
    }

    private String slotPreview(List<RecommendationSlot> slots) {
        if (slots == null || slots.isEmpty()) {
            return "无";
        }
        return String.join("\n", slots.stream()
                .map(slot -> "- slotId=%s, targetType=%s, target=%s, count=%s".formatted(slot.slotId(), slot.targetType(), slot.target(), slot.count()))
                .toList());
    }

    private Map<String, Integer> requestedBySlot(List<RecommendationSlot> slots) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (RecommendationSlot slot : slots == null ? List.<RecommendationSlot>of() : slots) {
            values.put(slot.slotId(), slot.count());
        }
        return values;
    }

    private String slotId(JsonNode candidateNode, List<RecommendationSlot> slots) {
        String value = text(candidateNode, "slotId");
        if (!value.isBlank()) {
            return value;
        }
        if (slots != null && slots.size() == 1) {
            return slots.getFirst().slotId();
        }
        return "";
    }

    private String joinLimited(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return "暂无";
        }
        return String.join("、", values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .limit(limit)
                .toList());
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().strip() : "";
    }

    private String source(JsonNode root) {
        String value = text(root, "source");
        return value.isBlank() ? "model" : value;
    }

    private int requestedCount(int count) {
        int value = count <= 0 ? DEFAULT_COUNT : count;
        return Math.max(1, Math.min(MAX_CANDIDATES, value));
    }

    private String extractJsonObject(String value) {
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return value.substring(start, end + 1);
        }
        return value;
    }

    private String normalizeKey(String value) {
        return safe(value).toLowerCase(java.util.Locale.ROOT);
    }

    private String normalizeTitle(String value) {
        return safe(value)
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[《》<>\\[\\]【】()（）\\s，,。！？!?；;：:\"'“”‘’、]+", "");
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
