package com.musio.model;

import java.time.Instant;
import java.util.List;

public record AgentRecentRecommendedSong(
        String songId,
        String title,
        List<String> artists,
        String slotId,
        String sourceRequest,
        String reason,
        String runId,
        String dedupePolicy,
        Instant recommendedAt
) {
    public AgentRecentRecommendedSong {
        songId = safe(songId);
        title = safe(title);
        artists = cleanList(artists);
        slotId = safe(slotId);
        sourceRequest = safe(sourceRequest);
        reason = safe(reason);
        runId = safe(runId);
        dedupePolicy = safe(dedupePolicy).isBlank() ? "soft_avoid" : safe(dedupePolicy);
        recommendedAt = recommendedAt == null ? Instant.EPOCH : recommendedAt;
    }

    private static List<String> cleanList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .limit(8)
                .toList();
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
