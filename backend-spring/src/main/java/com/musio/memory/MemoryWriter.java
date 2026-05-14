package com.musio.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.AgentRunContext;
import com.musio.agent.capability.AgentCapabilityRegistry;
import com.musio.agent.loop.AgentLoopEvidence;
import com.musio.agent.loop.AgentObservation;
import com.musio.agent.loop.AgentObservationStatus;
import com.musio.model.Song;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class MemoryWriter {
    private static final int MAX_CACHE_CONTENT_CHARS = 6000;
    private static final int MAX_SUMMARY_CHARS = 1200;

    private final BehaviorEventStore behaviorEventStore;
    private final PreferenceStore preferenceStore;
    private final MusicCacheStore musicCacheStore;
    private final ConversationSummaryStore conversationSummaryStore;
    private final ObjectMapper objectMapper;

    public MemoryWriter(
            BehaviorEventStore behaviorEventStore,
            PreferenceStore preferenceStore,
            MusicCacheStore musicCacheStore,
            ConversationSummaryStore conversationSummaryStore,
            ObjectMapper objectMapper
    ) {
        this.behaviorEventStore = behaviorEventStore;
        this.preferenceStore = preferenceStore;
        this.musicCacheStore = musicCacheStore;
        this.conversationSummaryStore = conversationSummaryStore;
        this.objectMapper = objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper.findAndRegisterModules();
    }

    public MemoryWritePlan writeAfterTurn(MemoryWriteRequest request) {
        MemoryWritePlan plan = plan(request);
        for (BehaviorEvent event : plan.behaviorEvents()) {
            if (behaviorEventStore != null) {
                behaviorEventStore.append(event);
            }
        }
        for (PreferenceCandidate candidate : plan.preferenceCandidates()) {
            if (preferenceStore != null) {
                preferenceStore.addCandidate(candidate);
            }
        }
        for (MusicCacheEntry entry : plan.musicCacheEntries()) {
            if (musicCacheStore != null) {
                musicCacheStore.upsert(entry);
            }
        }
        for (ConversationSummary summary : plan.conversationSummaries()) {
            if (conversationSummaryStore != null) {
                conversationSummaryStore.upsert(summary);
            }
        }
        return plan;
    }

    public MemoryWritePlan plan(MemoryWriteRequest request) {
        if (request == null) {
            return MemoryWritePlan.empty();
        }
        List<BehaviorEvent> events = new ArrayList<>();
        List<PreferenceCandidate> candidates = preferenceCandidates(request);
        if (!candidates.isEmpty()) {
            events.add(new BehaviorEvent(
                    "",
                    request.userId(),
                    isTemporaryMessage(request.userMessage()) ? "session_preference" : "user_feedback",
                    "user_message",
                    "",
                    "",
                    List.of(),
                    request.userMessage(),
                    basePayload(request, Map.of("candidateCount", candidates.size())),
                    0.8,
                    request.occurredAt()
            ));
        }
        events.addAll(behaviorEvents(request));
        List<MusicCacheEntry> cacheEntries = musicCacheEntries(request);
        List<ConversationSummary> summaries = conversationSummaries(request);
        return new MemoryWritePlan(events, candidates, cacheEntries, summaries);
    }

    private List<BehaviorEvent> behaviorEvents(MemoryWriteRequest request) {
        AgentLoopEvidence evidence = request.loopEvidence();
        if (evidence == null || evidence.observations().isEmpty()) {
            return List.of();
        }
        List<BehaviorEvent> events = new ArrayList<>();
        for (AgentObservation observation : evidence.observations()) {
            if (observation.status() != AgentObservationStatus.SUCCESS) {
                events.add(eventForObservation(request, observation, "tool_failure", null));
                continue;
            }
            String eventType = eventType(observation.toolName());
            if (eventType.isBlank()) {
                continue;
            }
            List<Song> songs = observation.songs().isEmpty() ? evidence.songs() : observation.songs();
            if (songs.isEmpty()) {
                events.add(eventForObservation(request, observation, eventType, null));
                continue;
            }
            for (Song song : songs.stream().limit(20).toList()) {
                events.add(eventForObservation(request, observation, eventType, song));
            }
        }
        return events;
    }

    private BehaviorEvent eventForObservation(MemoryWriteRequest request, AgentObservation observation, String eventType, Song song) {
        return new BehaviorEvent(
                "",
                request.userId(),
                eventType,
                observation.toolName(),
                song == null ? songIdFromArguments(observation.arguments()) : safe(song.id()),
                song == null ? "" : safe(song.title()),
                song == null ? List.of() : song.artists(),
                observation.plannerSummary(),
                basePayload(request, Map.of(
                        "toolName", observation.toolName(),
                        "status", observation.status().name(),
                        "arguments", observation.arguments(),
                        "resultSummary", resultSummary(observation.resultJson())
                )),
                observation.status() == AgentObservationStatus.SUCCESS ? 0.9 : 0.75,
                request.occurredAt()
        );
    }

    private List<PreferenceCandidate> preferenceCandidates(MemoryWriteRequest request) {
        String message = request.userMessage();
        String normalized = normalize(message);
        List<PreferenceCandidate> values = new ArrayList<>();
        boolean temporary = isTemporaryMessage(message);
        String source = temporary ? "session_feedback" : "explicit_feedback";
        double delta = temporary ? 0.15 : 0.25;
        if (containsNoiseRejection(normalized)) {
            values.add(new PreferenceCandidate(
                    "",
                    request.userId(),
                    "negative",
                    "too_noisy",
                    "不想听太吵的歌",
                    delta,
                    message,
                    source,
                    request.occurredAt()
            ));
        }
        if (containsAny(normalized, "不喜欢", "讨厌", "别放", "不要听", "不想听") && values.stream().noneMatch(item -> "too_noisy".equals(item.name()))) {
            values.add(new PreferenceCandidate(
                    "",
                    request.userId(),
                    "negative",
                    "explicit_negative_feedback",
                    "明确负向音乐反馈",
                    Math.min(0.2, delta),
                    message,
                    source,
                    request.occurredAt()
            ));
        }
        if (containsAny(normalized, "喜欢", "爱听", "多来点", "就这种", "这个不错")) {
            values.add(new PreferenceCandidate(
                    "",
                    request.userId(),
                    "positive",
                    "explicit_positive_feedback",
                    "明确正向音乐反馈",
                    delta,
                    message,
                    source,
                    request.occurredAt()
            ));
        }
        return values.stream().limit(5).toList();
    }

    private List<MusicCacheEntry> musicCacheEntries(MemoryWriteRequest request) {
        AgentLoopEvidence evidence = request.loopEvidence();
        if (evidence == null || evidence.observations().isEmpty()) {
            return List.of();
        }
        List<MusicCacheEntry> entries = new ArrayList<>();
        for (AgentObservation observation : evidence.observations()) {
            if (observation.status() != AgentObservationStatus.SUCCESS) {
                continue;
            }
            entries.addAll(cacheEntriesForObservation(request, observation));
        }
        return entries;
    }

    private List<MusicCacheEntry> cacheEntriesForObservation(MemoryWriteRequest request, AgentObservation observation) {
        if (observation == null || observation.resultJson().isBlank()) {
            return List.of();
        }
        if ("get_hot_comments".equals(observation.toolName())) {
            return commentCacheEntries(request, observation);
        }
        String cacheType = cacheType(observation.toolName());
        if (cacheType.isBlank()) {
            return List.of();
        }
        return List.of(new MusicCacheEntry(
                "",
                request.userId(),
                cacheType,
                songIdFromArguments(observation.arguments()),
                titleFromObservation(observation),
                limit(resultSummary(observation.resultJson()) + "\n" + observation.resultJson(), MAX_CACHE_CONTENT_CHARS),
                observation.plannerSummary(),
                request.occurredAt()
        ));
    }

    private List<MusicCacheEntry> commentCacheEntries(MemoryWriteRequest request, AgentObservation observation) {
        List<MusicCacheEntry> entries = new ArrayList<>();
        String rawContent = limit(observation.resultJson(), MAX_CACHE_CONTENT_CHARS);
        String summaryContent = limit(commentSummary(observation.resultJson()), MAX_SUMMARY_CHARS);
        if (!rawContent.isBlank()) {
            entries.add(new MusicCacheEntry(
                    "",
                    request.userId(),
                    "comments",
                    songIdFromArguments(observation.arguments()),
                    titleFromObservation(observation),
                    rawContent,
                    observation.plannerSummary(),
                    request.occurredAt()
            ));
        }
        if (!summaryContent.isBlank()) {
            entries.add(new MusicCacheEntry(
                    "",
                    request.userId(),
                    "commentSummary",
                    songIdFromArguments(observation.arguments()),
                    titleFromObservation(observation),
                    summaryContent,
                    observation.plannerSummary(),
                    request.occurredAt()
            ));
        }
        return List.copyOf(entries);
    }

    private List<ConversationSummary> conversationSummaries(MemoryWriteRequest request) {
        if (request.userMessage().isBlank() && request.finalAnswer().isBlank()) {
            return List.of();
        }
        String summary = limit("""
                用户：%s
                助手：%s
                """.formatted(request.userMessage(), request.finalAnswer()), MAX_SUMMARY_CHARS);
        List<String> keywords = new ArrayList<>();
        if (request.goal() != null) {
            keywords.add(request.goal().taskType());
            keywords.add(request.goal().contextMode());
            keywords.add(request.goal().effectiveRequest());
            keywords.addAll(request.goal().requiredOutcomes().stream().map(Enum::name).toList());
        }
        return List.of(new ConversationSummary("", request.userId(), summary, keywords, request.occurredAt()));
    }

    private Map<String, Object> basePayload(MemoryWriteRequest request, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        AgentRunContext.runId().ifPresent(runId -> payload.put("runId", runId));
        payload.put("request", request.userMessage());
        if (request.goal() != null) {
            payload.put("taskType", request.goal().taskType());
            payload.put("contextMode", request.goal().contextMode());
        }
        if (extra != null) {
            payload.putAll(extra);
        }
        return payload;
    }

    private String eventType(String toolName) {
        return switch (toolName == null ? "" : toolName) {
            case AgentCapabilityRegistry.RECOMMEND_SONGS -> "recommendation_shown";
            case "search_songs" -> "search_performed";
            case "get_hot_comments" -> "comments_read";
            case "get_lyrics" -> "lyrics_read";
            case "get_song_detail" -> "song_detail_read";
            case AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST -> "local_playlist_add";
            default -> "";
        };
    }

    private String cacheType(String toolName) {
        return switch (toolName == null ? "" : toolName) {
            case "get_lyrics" -> "lyricsSummary";
            case "get_song_detail" -> "songDetail";
            default -> "";
        };
    }

    private String resultSummary(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(resultJson);
            String summary = root.path("summary").asText("");
            if (!summary.isBlank()) {
                return summary.strip();
            }
            String message = root.path("message").asText("");
            if (!message.isBlank()) {
                return message.strip();
            }
        } catch (Exception ignored) {
        }
        return limit(resultJson, 500);
    }

    private String commentSummary(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(resultJson);
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

    private String titleFromObservation(AgentObservation observation) {
        if (observation.songs() != null && !observation.songs().isEmpty()) {
            return safe(observation.songs().getFirst().title());
        }
        return resultTitle(observation.resultJson());
    }

    private String resultTitle(String resultJson) {
        try {
            JsonNode root = objectMapper.readTree(resultJson == null ? "{}" : resultJson);
            String title = root.path("title").asText("");
            if (!title.isBlank()) {
                return title.strip();
            }
            JsonNode song = root.path("song");
            if (song.isObject()) {
                return song.path("title").asText("").strip();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private String songIdFromArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        Object songId = arguments.get("songId");
        if (songId instanceof String text) {
            return text.strip();
        }
        Object songIds = arguments.get("songIds");
        if (songIds instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof String text) {
            return text.strip();
        }
        return "";
    }

    private boolean containsNoiseRejection(String normalized) {
        return containsAny(normalized, "别太吵", "不要太吵", "不想听太吵", "别太炸", "不要太炸")
                || (normalized.contains("太吵") && containsAny(normalized, "不", "别", "不要"));
    }

    private boolean isTemporaryMessage(String message) {
        String normalized = normalize(message);
        return containsAny(normalized, "今天", "今晚", "现在", "这会儿", "此刻", "临时", "先");
    }

    private boolean containsAny(String value, String... needles) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return safe(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private String limit(String value, int maxChars) {
        String safe = value == null ? "" : value.strip();
        if (safe.length() <= maxChars) {
            return safe;
        }
        return safe.substring(0, Math.max(0, maxChars - 3)).strip() + "...";
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
