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
                null,
                Instant.EPOCH
        );
    }
}
