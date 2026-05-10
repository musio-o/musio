package com.musio.agent;

import com.musio.agent.recommendation.RecommendationSlot;
import com.musio.agent.recommendation.RecommendationSlots;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class AgentGoalNormalizer {
    private AgentGoalNormalizer() {
    }

    static List<AgentRequiredOutcome> requiredOutcomes(AgentTurnPlan turnPlan, AgentTaskContext taskContext) {
        return requiredOutcomes(turnPlan, taskContext, "");
    }

    static List<AgentRequiredOutcome> requiredOutcomes(AgentTurnPlan turnPlan, AgentTaskContext taskContext, String userMessage) {
        LinkedHashSet<AgentRequiredOutcome> outcomes = new LinkedHashSet<>();
        if (turnPlan != null && turnPlan.disposition() == TurnDisposition.USE_TOOLS) {
            outcomeForTaskType(turnPlan.taskType()).ifPresent(outcomes::add);
            if (turnPlan.requiredOutcomes() != null) {
                outcomes.addAll(turnPlan.requiredOutcomes());
            }
            for (AgentToolCall call : turnPlan.toolCalls() == null ? List.<AgentToolCall>of() : turnPlan.toolCalls()) {
                outcomeForTool(call == null ? "" : call.toolName()).ifPresent(outcomes::add);
            }
        }
        if (taskContext != null && taskContext.agentTask()) {
            outcomeForTaskType(taskContext.taskType()).ifPresent(outcomes::add);
        }
        addExplicitMessageOutcomes(outcomes, userMessage);
        return orderedOutcomes(outcomes);
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

    private static Optional<AgentRequiredOutcome> outcomeForTool(String toolName) {
        return switch (safe(toolName)) {
            case "search_songs" -> Optional.of(AgentRequiredOutcome.SEARCH);
            case "get_hot_comments" -> Optional.of(AgentRequiredOutcome.COMMENTS);
            case "get_lyrics" -> Optional.of(AgentRequiredOutcome.LYRICS);
            case "get_song_detail" -> Optional.of(AgentRequiredOutcome.DETAIL);
            case "get_user_playlists", "get_playlist_songs" -> Optional.of(AgentRequiredOutcome.PLAYLIST);
            case "get_user_music_profile" -> Optional.of(AgentRequiredOutcome.PROFILE);
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

    private static void addExplicitMessageOutcomes(LinkedHashSet<AgentRequiredOutcome> outcomes, String userMessage) {
        String normalized = normalize(userMessage);
        if (normalized.isBlank()) {
            return;
        }
        if (hasRecommendationIntent(normalized)) {
            outcomes.add(AgentRequiredOutcome.RECOMMENDATION);
        }
        if (hasCommentsIntent(normalized)) {
            outcomes.add(AgentRequiredOutcome.COMMENTS);
        }
        if (hasLyricsIntent(normalized)) {
            outcomes.add(AgentRequiredOutcome.LYRICS);
        }
        if (hasLocalPlaylistWriteIntent(normalized)) {
            if (!hasPlaylistReadIntent(normalized)) {
                outcomes.remove(AgentRequiredOutcome.PLAYLIST);
            }
            outcomes.add(AgentRequiredOutcome.LOCAL_PLAYLIST_WRITE);
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
