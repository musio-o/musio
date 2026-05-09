package com.musio.agent.capability;

import com.musio.agent.recommendation.RecommendationSlot;
import com.musio.agent.recommendation.RecommendationSlots;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AgentCapabilityArgumentRules {
    private AgentCapabilityArgumentRules() {
    }

    static Map<String, Object> normalizeKnownCapability(
            String capabilityName,
            Map<String, Object> arguments,
            AgentCapabilityArgumentContext context
    ) {
        Map<String, Object> cleaned = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        AgentCapabilityArgumentContext safeContext = context == null ? AgentCapabilityArgumentContext.defaultContext() : context;
        if (AgentCapabilityRegistry.RECOMMEND_SONGS.equals(capabilityName)) {
            cleaned.put("request", text(cleaned, "request"));
            List<RecommendationSlot> slots = RecommendationSlots.fromArgument(cleaned.get("slots"));
            if (slots.isEmpty()) {
                cleaned.remove("slots");
            } else {
                cleaned.put("slots", RecommendationSlots.toArgument(slots));
            }
            Integer count = cleanRequiredLimit(cleaned.get("count"), 1, 10);
            if (!slots.isEmpty()) {
                cleaned.put("count", RecommendationSlots.totalCount(slots));
            } else if (count == null && safeContext.requestedSongCount() > 0) {
                cleaned.put("count", Math.min(safeContext.requestedSongCount(), 10));
            } else if (count == null) {
                cleaned.remove("count");
            } else {
                cleaned.put("count", count);
            }
            List<String> excludedTitles = stringList(cleaned.get("excludedTitles"));
            if (excludedTitles.isEmpty()) {
                cleaned.remove("excludedTitles");
            } else {
                cleaned.put("excludedTitles", excludedTitles);
            }
        }
        if ("search_songs".equals(capabilityName)) {
            cleaned.put("keyword", text(cleaned, "keyword"));
            Integer limit = cleanRequiredLimit(cleaned.get("limit"), 1, 20);
            if (limit == null && safeContext.requestedSongCount() > 0) {
                cleaned.put("limit", Math.min(safeContext.requestedSongCount(), 20));
            } else if (limit == null) {
                cleaned.remove("limit");
            } else {
                cleaned.put("limit", safeContext.requestedSongCount() > 0 ? Math.min(limit, safeContext.requestedSongCount()) : limit);
            }
            List<String> excludedTitles = stringList(cleaned.get("excludedTitles"));
            if (excludedTitles.isEmpty()) {
                cleaned.remove("excludedTitles");
            } else {
                cleaned.put("excludedTitles", excludedTitles);
            }
        }
        if ("get_song_detail".equals(capabilityName) || "get_lyrics".equals(capabilityName) || "get_hot_comments".equals(capabilityName)) {
            cleaned.put("songId", text(cleaned, "songId"));
        }
        if ("get_lyrics".equals(capabilityName) || "get_hot_comments".equals(capabilityName)) {
            List<String> songIds = stringList(cleaned.get("songIds"));
            if (songIds.isEmpty()) {
                cleaned.remove("songIds");
            } else {
                cleaned.put("songIds", songIds.stream().limit(10).toList());
                if (text(cleaned, "songId").isBlank()) {
                    cleaned.put("songId", songIds.getFirst());
                }
            }
        }
        if ("get_hot_comments".equals(capabilityName)) {
            cleaned.put("limit", cleanLimit(cleaned.get("limit"), 10, 1, 30));
        }
        if ("get_user_playlists".equals(capabilityName)) {
            cleaned.put("limit", cleanLimit(cleaned.get("limit"), 20, 1, 50));
        }
        if ("get_playlist_songs".equals(capabilityName)) {
            cleaned.put("playlistId", text(cleaned, "playlistId"));
            cleaned.put("limit", cleanLimit(cleaned.get("limit"), 20, 1, 50));
        }
        if (AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST.equals(capabilityName)) {
            String playlistId = text(cleaned, "playlistId");
            cleaned.put("playlistId", playlistId.isBlank() ? "default" : playlistId);
            cleaned.put("songId", text(cleaned, "songId"));
            cleaned.put("songTitle", text(cleaned, "songTitle"));
            cleaned.put("artist", text(cleaned, "artist"));
            Integer songIndex = cleanRequiredLimit(cleaned.get("songIndex"), 1, safeContext.songIndexMax());
            if (songIndex == null) {
                cleaned.remove("songIndex");
            } else {
                cleaned.put("songIndex", songIndex);
            }
        }
        return cleaned;
    }

    static AgentCapabilityValidationResult validateKnownCapability(
            String capabilityName,
            Map<String, Object> arguments,
            AgentCapabilityArgumentContext context
    ) {
        AgentCapabilityArgumentContext safeContext = context == null ? AgentCapabilityArgumentContext.defaultContext() : context;
        if (AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST.equals(capabilityName)) {
            if (!safeContext.requireLocalWriteTarget()) {
                return AgentCapabilityValidationResult.accepted();
            }
            return validateMusioPlaylistRequiredArguments(arguments);
        }
        return validateReadRequiredArguments(capabilityName, arguments);
    }

    static AgentCapabilityValidationResult validateReadRequiredArguments(String capabilityName, Map<String, Object> arguments) {
        return switch (capabilityName) {
            case AgentCapabilityRegistry.RECOMMEND_SONGS -> hasText(arguments, "request")
                    && (integer(arguments, "count") != null || !RecommendationSlots.fromArgument(arguments == null ? null : arguments.get("slots")).isEmpty())
                    ? AgentCapabilityValidationResult.accepted()
                    : AgentCapabilityValidationResult.rejected("missing_required_argument");
            case "search_songs" -> hasText(arguments, "keyword") && integer(arguments, "limit") != null
                    ? AgentCapabilityValidationResult.accepted()
                    : AgentCapabilityValidationResult.rejected("missing_required_argument");
            case "get_lyrics", "get_hot_comments" -> hasText(arguments, "songId") || !stringList(arguments == null ? null : arguments.get("songIds")).isEmpty()
                    ? AgentCapabilityValidationResult.accepted()
                    : AgentCapabilityValidationResult.rejected("missing_required_argument");
            case "get_song_detail" -> hasText(arguments, "songId")
                    ? AgentCapabilityValidationResult.accepted()
                    : AgentCapabilityValidationResult.rejected("missing_required_argument");
            case "get_playlist_songs" -> hasText(arguments, "playlistId")
                    ? AgentCapabilityValidationResult.accepted()
                    : AgentCapabilityValidationResult.rejected("missing_required_argument");
            default -> AgentCapabilityValidationResult.accepted();
        };
    }

    static AgentCapabilityValidationResult validateMusioPlaylistRequiredArguments(Map<String, Object> arguments) {
        if (hasText(arguments, "songId") || hasText(arguments, "songTitle") || integer(arguments, "songIndex") != null) {
            return AgentCapabilityValidationResult.accepted();
        }
        return AgentCapabilityValidationResult.rejected("missing_song_reference");
    }

    static String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value instanceof String text ? text.strip() : "";
    }

    static Integer integer(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.strip());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    static List<String> stringList(Object value) {
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

    private static boolean hasText(Map<String, Object> arguments, String key) {
        return !text(arguments, key).isBlank();
    }

    private static int cleanLimit(Object value, int defaultValue, int min, int max) {
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

    private static Integer cleanRequiredLimit(Object value, int min, int max) {
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
}
