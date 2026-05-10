package com.musio.model;

import java.time.Instant;
import java.util.List;

public record AgentTaskMemory(
        String userId,
        String currentTask,
        String lastEffectiveRequest,
        String lastSearchKeyword,
        Integer lastSearchLimit,
        List<Song> lastResultSongs,
        List<String> lastResultSongTitles,
        List<String> avoidSongTitles,
        List<AgentToolFailure> lastToolFailures,
        Song lastTargetSong,
        String lastCompletedTaskType,
        List<String> lastObservationSummaries,
        List<String> lastRequiredOutcomes,
        List<AgentTaskRecommendationSlot> lastRecommendationSlots,
        List<String> lastEvidenceTools,
        List<String> lastWriteIntentTools,
        List<AgentRecentRecommendedSong> recentRecommendedSongs,
        PendingLocalPlaylistAdd pendingLocalPlaylistAdd,
        Instant updatedAt
) {
    public AgentTaskMemory {
        lastResultSongs = lastResultSongs == null ? List.of() : List.copyOf(lastResultSongs);
        lastResultSongTitles = lastResultSongTitles == null ? List.of() : List.copyOf(lastResultSongTitles);
        avoidSongTitles = avoidSongTitles == null ? List.of() : List.copyOf(avoidSongTitles);
        lastToolFailures = lastToolFailures == null ? List.of() : List.copyOf(lastToolFailures);
        lastCompletedTaskType = lastCompletedTaskType == null ? "" : lastCompletedTaskType.strip();
        lastObservationSummaries = lastObservationSummaries == null ? List.of() : List.copyOf(lastObservationSummaries);
        lastRequiredOutcomes = cleanStrings(lastRequiredOutcomes);
        lastRecommendationSlots = lastRecommendationSlots == null ? List.of() : List.copyOf(lastRecommendationSlots);
        lastEvidenceTools = cleanStrings(lastEvidenceTools);
        lastWriteIntentTools = cleanStrings(lastWriteIntentTools);
        recentRecommendedSongs = recentRecommendedSongs == null ? List.of() : List.copyOf(recentRecommendedSongs);
    }

    public AgentTaskMemory(
            String userId,
            String currentTask,
            String lastEffectiveRequest,
            String lastSearchKeyword,
            Integer lastSearchLimit,
            List<Song> lastResultSongs,
            List<String> lastResultSongTitles,
            List<String> avoidSongTitles,
            List<AgentToolFailure> lastToolFailures,
            Song lastTargetSong,
            String lastCompletedTaskType,
            List<String> lastObservationSummaries,
            List<String> lastRequiredOutcomes,
            List<AgentTaskRecommendationSlot> lastRecommendationSlots,
            List<String> lastEvidenceTools,
            List<String> lastWriteIntentTools,
            PendingLocalPlaylistAdd pendingLocalPlaylistAdd,
            Instant updatedAt
    ) {
        this(
                userId,
                currentTask,
                lastEffectiveRequest,
                lastSearchKeyword,
                lastSearchLimit,
                lastResultSongs,
                lastResultSongTitles,
                avoidSongTitles,
                lastToolFailures,
                lastTargetSong,
                lastCompletedTaskType,
                lastObservationSummaries,
                lastRequiredOutcomes,
                lastRecommendationSlots,
                lastEvidenceTools,
                lastWriteIntentTools,
                List.of(),
                pendingLocalPlaylistAdd,
                updatedAt
        );
    }

    public AgentTaskMemory(
            String userId,
            String currentTask,
            String lastEffectiveRequest,
            String lastSearchKeyword,
            Integer lastSearchLimit,
            List<Song> lastResultSongs,
            List<String> lastResultSongTitles,
            List<String> avoidSongTitles,
            List<AgentToolFailure> lastToolFailures,
            Song lastTargetSong,
            String lastCompletedTaskType,
            List<String> lastObservationSummaries,
            PendingLocalPlaylistAdd pendingLocalPlaylistAdd,
            Instant updatedAt
    ) {
        this(
                userId,
                currentTask,
                lastEffectiveRequest,
                lastSearchKeyword,
                lastSearchLimit,
                lastResultSongs,
                lastResultSongTitles,
                avoidSongTitles,
                lastToolFailures,
                lastTargetSong,
                lastCompletedTaskType,
                lastObservationSummaries,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                pendingLocalPlaylistAdd,
                updatedAt
        );
    }

    public static AgentTaskMemory empty(String userId) {
        return new AgentTaskMemory(
                userId,
                "",
                "",
                "",
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                Instant.EPOCH
        );
    }

    private static List<String> cleanStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .limit(30)
                .toList();
    }
}
