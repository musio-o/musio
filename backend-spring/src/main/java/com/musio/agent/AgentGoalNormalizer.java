package com.musio.agent;

import com.musio.agent.recommendation.RecommendationSlot;
import com.musio.agent.recommendation.RecommendationSlots;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class AgentGoalNormalizer {
    private AgentGoalNormalizer() {
    }

    static List<AgentRequiredOutcome> requiredOutcomes(AgentTurnPlan turnPlan, AgentTaskContext taskContext) {
        return requiredOutcomes(turnPlan, taskContext, "");
    }

    static List<AgentRequiredOutcome> requiredOutcomes(AgentTurnPlan turnPlan, AgentTaskContext taskContext, String userMessage) {
        return orderedOutcomes(new LinkedHashSet<>(requiredOutcomeSources(turnPlan, taskContext, userMessage).keySet()));
    }

    static String requiredOutcomeSourceSummary(AgentTurnPlan turnPlan, AgentTaskContext taskContext, String userMessage) {
        Map<AgentRequiredOutcome, String> sources = requiredOutcomeSources(turnPlan, taskContext, userMessage);
        if (sources.isEmpty()) {
            return "none";
        }
        return orderedOutcomes(new LinkedHashSet<>(sources.keySet())).stream()
                .map(outcome -> outcome + "=" + sources.getOrDefault(outcome, "unknown"))
                .toList()
                .toString();
    }

    private static LinkedHashMap<AgentRequiredOutcome, String> requiredOutcomeSources(AgentTurnPlan turnPlan, AgentTaskContext taskContext, String userMessage) {
        LinkedHashMap<AgentRequiredOutcome, String> outcomes = new LinkedHashMap<>();
        if (turnPlan != null && turnPlan.disposition() == TurnDisposition.USE_TOOLS) {
            outcomeForTaskType(turnPlan.taskType()).ifPresent(outcome -> addOutcome(outcomes, outcome, "turn_plan.taskType"));
            if (turnPlan.requiredOutcomes() != null) {
                for (AgentRequiredOutcome outcome : turnPlan.requiredOutcomes()) {
                    addOutcome(outcomes, outcome, "turn_plan.requiredOutcomes");
                }
            }
            for (AgentToolCall call : turnPlan.toolCalls() == null ? List.<AgentToolCall>of() : turnPlan.toolCalls()) {
                String toolName = call == null ? "" : call.toolName();
                hardOutcomeForToolHint(toolName).ifPresent(outcome -> addOutcome(outcomes, outcome, "turn_plan.toolHint:" + safe(toolName)));
            }
        }
        if (taskContext != null && taskContext.agentTask()) {
            outcomeForTaskType(taskContext.taskType()).ifPresent(outcome -> addOutcome(outcomes, outcome, "task_context.taskType"));
        }
        addExplicitMessageOutcomes(outcomes, userMessage);
        return outcomes;
    }

    static List<RecommendationSlot> recommendationSlots(AgentTurnPlan turnPlan, AgentTaskContext taskContext, String userMessage) {
        if (turnPlan != null && turnPlan.recommendationSlots() != null && !turnPlan.recommendationSlots().isEmpty()) {
            return RecommendationSlots.normalize(turnPlan.recommendationSlots());
        }
        boolean recommendationTask = turnPlan != null && "recommend".equals(safe(turnPlan.taskType()));
        boolean recommendationOutcome = requiredOutcomes(turnPlan, taskContext, userMessage).contains(AgentRequiredOutcome.RECOMMENDATION);
        if (!recommendationTask && !recommendationOutcome) {
            return List.of();
        }
        String source = turnPlan == null || safe(turnPlan.effectiveRequest()).isBlank()
                ? userMessage
                : turnPlan.effectiveRequest();
        List<RecommendationSlot> slots = RecommendationSlots.fromMessage(source);
        if (!slots.isEmpty()) {
            return slots;
        }
        return RecommendationSlots.fromMessage(userMessage);
    }

    private static Optional<AgentRequiredOutcome> outcomeForTaskType(String taskType) {
        return switch (safe(taskType)) {
            case "recommend" -> Optional.of(AgentRequiredOutcome.RECOMMENDATION);
            case "search" -> Optional.of(AgentRequiredOutcome.SEARCH);
            case "comments" -> Optional.of(AgentRequiredOutcome.COMMENTS);
            case "lyrics" -> Optional.of(AgentRequiredOutcome.LYRICS);
            case "detail" -> Optional.of(AgentRequiredOutcome.DETAIL);
            case "playlist" -> Optional.of(AgentRequiredOutcome.PLAYLIST);
            case "profile" -> Optional.of(AgentRequiredOutcome.PROFILE);
            case "playback" -> Optional.of(AgentRequiredOutcome.PLAYBACK);
            default -> Optional.empty();
        };
    }

    private static Optional<AgentRequiredOutcome> hardOutcomeForToolHint(String toolName) {
        return switch (safe(toolName)) {
            case "add_song_to_musio_playlist" -> Optional.of(AgentRequiredOutcome.LOCAL_PLAYLIST_WRITE);
            default -> Optional.empty();
        };
    }

    private static List<AgentRequiredOutcome> orderedOutcomes(LinkedHashSet<AgentRequiredOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return List.of();
        }
        List<AgentRequiredOutcome> priority = List.of(
                AgentRequiredOutcome.RECOMMENDATION,
                AgentRequiredOutcome.SEARCH,
                AgentRequiredOutcome.COMMENTS,
                AgentRequiredOutcome.LYRICS,
                AgentRequiredOutcome.DETAIL,
                AgentRequiredOutcome.PLAYLIST,
                AgentRequiredOutcome.PROFILE,
                AgentRequiredOutcome.PLAYBACK,
                AgentRequiredOutcome.LOCAL_PLAYLIST_WRITE,
                AgentRequiredOutcome.ACCOUNT_WRITE
        );
        return priority.stream()
                .filter(outcomes::contains)
                .toList();
    }

    private static void addExplicitMessageOutcomes(LinkedHashMap<AgentRequiredOutcome, String> outcomes, String userMessage) {
        String normalized = normalize(userMessage);
        if (normalized.isBlank()) {
            return;
        }
        if (hasRecommendationIntent(normalized)) {
            addOutcome(outcomes, AgentRequiredOutcome.RECOMMENDATION, "user_message.recommendation_intent");
        }
        if (hasCommentsIntent(normalized)) {
            addOutcome(outcomes, AgentRequiredOutcome.COMMENTS, "user_message.comments_intent");
        }
        if (hasLyricsIntent(normalized)) {
            addOutcome(outcomes, AgentRequiredOutcome.LYRICS, "user_message.lyrics_intent");
        }
        if (hasLocalPlaylistWriteIntent(normalized)) {
            if (!hasPlaylistReadIntent(normalized)) {
                outcomes.remove(AgentRequiredOutcome.PLAYLIST);
            }
            addOutcome(outcomes, AgentRequiredOutcome.LOCAL_PLAYLIST_WRITE, "user_message.local_playlist_write_intent");
        }
    }

    private static void addOutcome(LinkedHashMap<AgentRequiredOutcome, String> outcomes, AgentRequiredOutcome outcome, String source) {
        if (outcome != null) {
            outcomes.putIfAbsent(outcome, safe(source).isBlank() ? "unknown" : safe(source));
        }
    }

    private static boolean hasRecommendationIntent(String normalized) {
        return containsAny(normalized, "推荐", "来一首", "给我一首", "给我推荐", "帮我推荐", "想听");
    }

    private static boolean hasCommentsIntent(String normalized) {
        return containsAny(normalized, "评论", "热评", "热门评论", "最热门");
    }

    private static boolean hasLyricsIntent(String normalized) {
        return containsAny(normalized, "歌词", "一句词", "唱词");
    }

    private static boolean hasLocalPlaylistWriteIntent(String normalized) {
        if (hasLocalPlaylistCancelIntent(normalized) || isPlainLocalPlaylistConfirmation(normalized)) {
            return false;
        }
        boolean explicitFavorite = containsAny(normalized, "帮我收藏", "收藏到歌单", "收藏这首", "收藏这歌");
        boolean addVerb = containsAny(normalized, "加入", "添加", "加到", "放进", "存到", "保存到");
        boolean playlistContext = containsAny(normalized, "歌单", "musio", "本地");
        return explicitFavorite || (addVerb && playlistContext);
    }

    private static boolean hasPlaylistReadIntent(String normalized) {
        return containsAny(normalized, "查看歌单", "歌单里", "歌单内容", "有哪些歌单", "我的歌单", "播放列表");
    }

    private static boolean hasLocalPlaylistCancelIntent(String normalized) {
        return containsAny(normalized, "取消收藏", "不用收藏", "不用加入", "先不收藏", "不要收藏", "不收藏了", "算了");
    }

    private static boolean isPlainLocalPlaylistConfirmation(String normalized) {
        return List.of("确认", "确认收藏", "确认加入", "确认添加", "确认保存", "可以", "好的", "好", "是的").contains(normalized);
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
