package com.musio.model;

import java.util.List;

public record AgentTaskRecommendationSlot(
        String slotId,
        String targetType,
        String target,
        int requestedCount,
        List<String> songIds,
        List<String> songTitles
) {
    public AgentTaskRecommendationSlot {
        slotId = safe(slotId);
        targetType = safe(targetType).isBlank() ? "artist" : safe(targetType);
        target = safe(target);
        requestedCount = Math.max(0, requestedCount);
        songIds = cleanList(songIds);
        songTitles = cleanList(songTitles);
    }

    private static List<String> cleanList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .limit(20)
                .toList();
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
