package com.musio.memory;

import com.musio.model.AgentTaskMemory;
import com.musio.model.AgentToolFailure;
import com.musio.model.PendingLocalPlaylistAdd;
import com.musio.model.Song;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class AgentTaskMemoryService {
    private static final int MAX_RESULT_SONGS = 20;
    private static final int MAX_FAILURES = 5;
    private static final int MAX_OBSERVATION_SUMMARIES = 8;

    private final AgentTaskMemoryStore store;

    public AgentTaskMemoryService(AgentTaskMemoryStore store) {
        this.store = store;
    }

    public AgentTaskMemory read(String userId) {
        return store.read(userId).orElseGet(() -> AgentTaskMemory.empty(userId));
    }

    public AgentTaskMemory recordTask(
            String userId,
            String effectiveRequest,
            String searchKeyword,
            Integer searchLimit,
            List<String> avoidSongTitles
    ) {
        return recordTask(userId, effectiveRequest, searchKeyword, searchLimit, avoidSongTitles, safe(searchKeyword).isBlank());
    }

    public AgentTaskMemory recordTask(
            String userId,
            String effectiveRequest,
            String searchKeyword,
            Integer searchLimit,
            List<String> avoidSongTitles,
            boolean preserveSongContext
    ) {
        AgentTaskMemory previous = read(userId);
        boolean sameTask = equalsText(previous.lastEffectiveRequest(), effectiveRequest);
        boolean preservePreviousSongContext = sameTask || preserveSongContext;
        AgentTaskMemory next = new AgentTaskMemory(
                userId,
                "music-agent-task",
                safe(effectiveRequest),
                safe(searchKeyword),
                searchLimit == null || searchLimit <= 0 ? null : searchLimit,
                preservePreviousSongContext ? previous.lastResultSongs() : List.of(),
                preservePreviousSongContext ? previous.lastResultSongTitles() : List.of(),
                limitedStrings(avoidSongTitles, 20),
                sameTask ? previous.lastToolFailures() : List.of(),
                preservePreviousSongContext ? previous.lastTargetSong() : null,
                preservePreviousSongContext ? previous.lastCompletedTaskType() : "",
                preservePreviousSongContext ? previous.lastObservationSummaries() : List.of(),
                previous.pendingLocalPlaylistAdd(),
                Instant.now()
        );
        store.write(userId, next);
        return next;
    }

    public AgentTaskMemory recordResultSongs(String userId, List<Song> songs) {
        AgentTaskMemory previous = read(userId);
        List<Song> limitedSongs = songs == null ? List.of() : songs.stream()
                .limit(MAX_RESULT_SONGS)
                .toList();
        AgentTaskMemory next = new AgentTaskMemory(
                userId,
                previous.currentTask(),
                previous.lastEffectiveRequest(),
                previous.lastSearchKeyword(),
                previous.lastSearchLimit(),
                limitedSongs,
                limitedSongs.stream()
                        .map(Song::title)
                        .filter(title -> title != null && !title.isBlank())
                        .distinct()
                        .toList(),
                previous.avoidSongTitles(),
                List.of(),
                limitedSongs.isEmpty() ? previous.lastTargetSong() : limitedSongs.getFirst(),
                previous.lastCompletedTaskType(),
                previous.lastObservationSummaries(),
                previous.pendingLocalPlaylistAdd(),
                Instant.now()
        );
        store.write(userId, next);
        return next;
    }

    public AgentTaskMemory recordLoopEvidence(String userId, Song targetSong, String completedTaskType, List<String> observationSummaries) {
        AgentTaskMemory previous = read(userId);
        AgentTaskMemory next = new AgentTaskMemory(
                userId,
                previous.currentTask(),
                previous.lastEffectiveRequest(),
                previous.lastSearchKeyword(),
                previous.lastSearchLimit(),
                previous.lastResultSongs(),
                previous.lastResultSongTitles(),
                previous.avoidSongTitles(),
                previous.lastToolFailures(),
                targetSong == null ? previous.lastTargetSong() : targetSong,
                safe(completedTaskType).isBlank() ? previous.lastCompletedTaskType() : safe(completedTaskType),
                limitedStrings(observationSummaries, MAX_OBSERVATION_SUMMARIES),
                previous.pendingLocalPlaylistAdd(),
                Instant.now()
        );
        store.write(userId, next);
        return next;
    }

    public AgentTaskMemory recordToolFailure(String userId, String toolName, String message) {
        AgentTaskMemory previous = read(userId);
        List<AgentToolFailure> failures = new ArrayList<>();
        failures.add(new AgentToolFailure(safe(toolName), safe(message), Instant.now()));
        failures.addAll(previous.lastToolFailures() == null ? List.of() : previous.lastToolFailures());
        AgentTaskMemory next = new AgentTaskMemory(
                userId,
                previous.currentTask(),
                previous.lastEffectiveRequest(),
                previous.lastSearchKeyword(),
                previous.lastSearchLimit(),
                previous.lastResultSongs(),
                previous.lastResultSongTitles(),
                previous.avoidSongTitles(),
                failures.stream().limit(MAX_FAILURES).toList(),
                previous.lastTargetSong(),
                previous.lastCompletedTaskType(),
                previous.lastObservationSummaries(),
                previous.pendingLocalPlaylistAdd(),
                Instant.now()
        );
        store.write(userId, next);
        return next;
    }

    public AgentTaskMemory recordPendingLocalPlaylistAdd(String userId, PendingLocalPlaylistAdd pending) {
        AgentTaskMemory previous = read(userId);
        AgentTaskMemory next = new AgentTaskMemory(
                userId,
                previous.currentTask(),
                previous.lastEffectiveRequest(),
                previous.lastSearchKeyword(),
                previous.lastSearchLimit(),
                previous.lastResultSongs(),
                previous.lastResultSongTitles(),
                previous.avoidSongTitles(),
                previous.lastToolFailures(),
                pending == null || pending.song() == null ? previous.lastTargetSong() : pending.song(),
                previous.lastCompletedTaskType(),
                previous.lastObservationSummaries(),
                pending,
                Instant.now()
        );
        store.write(userId, next);
        return next;
    }

    public AgentTaskMemory clearPendingLocalPlaylistAdd(String userId) {
        AgentTaskMemory previous = read(userId);
        AgentTaskMemory next = new AgentTaskMemory(
                userId,
                previous.currentTask(),
                previous.lastEffectiveRequest(),
                previous.lastSearchKeyword(),
                previous.lastSearchLimit(),
                previous.lastResultSongs(),
                previous.lastResultSongTitles(),
                previous.avoidSongTitles(),
                previous.lastToolFailures(),
                previous.lastTargetSong(),
                previous.lastCompletedTaskType(),
                previous.lastObservationSummaries(),
                null,
                Instant.now()
        );
        store.write(userId, next);
        return next;
    }

    private boolean equalsText(String left, String right) {
        return safe(left).equals(safe(right));
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private List<String> limitedStrings(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .limit(limit)
                .toList();
    }
}
