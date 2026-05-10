package com.musio.memory;

import com.musio.model.AgentTaskMemory;
import com.musio.model.AgentTaskRecommendationSlot;
import com.musio.model.AgentToolFailure;
import com.musio.model.AgentRecentRecommendedSong;
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
    private static final int MAX_REQUIRED_OUTCOMES = 12;
    private static final int MAX_RECOMMENDATION_SLOTS = 10;
    private static final int MAX_EVIDENCE_TOOLS = 12;
    private static final int MAX_RECENT_RECOMMENDATIONS = 50;

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
                preservePreviousSongContext ? previous.lastRequiredOutcomes() : List.of(),
                preservePreviousSongContext ? previous.lastRecommendationSlots() : List.of(),
                preservePreviousSongContext ? previous.lastEvidenceTools() : List.of(),
                preservePreviousSongContext ? previous.lastWriteIntentTools() : List.of(),
                previous.recentRecommendedSongs(),
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
                previous.lastRequiredOutcomes(),
                previous.lastRecommendationSlots(),
                previous.lastEvidenceTools(),
                previous.lastWriteIntentTools(),
                previous.recentRecommendedSongs(),
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
                previous.lastRequiredOutcomes(),
                previous.lastRecommendationSlots(),
                previous.lastEvidenceTools(),
                previous.lastWriteIntentTools(),
                previous.recentRecommendedSongs(),
                previous.pendingLocalPlaylistAdd(),
                Instant.now()
        );
        store.write(userId, next);
        return next;
    }

    public AgentTaskMemory recordStructuredEvidence(
            String userId,
            List<String> requiredOutcomes,
            List<AgentTaskRecommendationSlot> recommendationSlots,
            List<String> evidenceTools,
            List<String> writeIntentTools
    ) {
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
                limitedStrings(requiredOutcomes, MAX_REQUIRED_OUTCOMES),
                recommendationSlots == null ? List.of() : recommendationSlots.stream()
                        .filter(slot -> slot != null && !slot.slotId().isBlank())
                        .limit(MAX_RECOMMENDATION_SLOTS)
                        .toList(),
                limitedStrings(evidenceTools, MAX_EVIDENCE_TOOLS),
                limitedStrings(writeIntentTools, MAX_EVIDENCE_TOOLS),
                previous.recentRecommendedSongs(),
                previous.pendingLocalPlaylistAdd(),
                Instant.now()
        );
        store.write(userId, next);
        return next;
    }

    public AgentTaskMemory recordRecentRecommendations(String userId, List<AgentRecentRecommendedSong> recommendations) {
        AgentTaskMemory previous = read(userId);
        List<AgentRecentRecommendedSong> merged = mergeRecentRecommendations(recommendations, previous.recentRecommendedSongs());
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
                previous.lastRequiredOutcomes(),
                previous.lastRecommendationSlots(),
                previous.lastEvidenceTools(),
                previous.lastWriteIntentTools(),
                merged,
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
                previous.lastRequiredOutcomes(),
                previous.lastRecommendationSlots(),
                previous.lastEvidenceTools(),
                previous.lastWriteIntentTools(),
                previous.recentRecommendedSongs(),
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
                previous.lastRequiredOutcomes(),
                previous.lastRecommendationSlots(),
                previous.lastEvidenceTools(),
                previous.lastWriteIntentTools(),
                previous.recentRecommendedSongs(),
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
                previous.lastRequiredOutcomes(),
                previous.lastRecommendationSlots(),
                previous.lastEvidenceTools(),
                previous.lastWriteIntentTools(),
                previous.recentRecommendedSongs(),
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

    private List<AgentRecentRecommendedSong> mergeRecentRecommendations(
            List<AgentRecentRecommendedSong> newRecommendations,
            List<AgentRecentRecommendedSong> previousRecommendations
    ) {
        List<AgentRecentRecommendedSong> merged = new ArrayList<>();
        if (newRecommendations != null) {
            merged.addAll(newRecommendations.stream()
                    .filter(item -> item != null && (!item.songId().isBlank() || !item.title().isBlank()))
                    .toList());
        }
        if (previousRecommendations != null) {
            merged.addAll(previousRecommendations.stream()
                    .filter(item -> item != null && (!item.songId().isBlank() || !item.title().isBlank()))
                    .toList());
        }
        java.util.LinkedHashMap<String, AgentRecentRecommendedSong> byKey = new java.util.LinkedHashMap<>();
        for (AgentRecentRecommendedSong item : merged) {
            byKey.putIfAbsent(recommendationKey(item), item);
        }
        return byKey.values().stream()
                .limit(MAX_RECENT_RECOMMENDATIONS)
                .toList();
    }

    private String recommendationKey(AgentRecentRecommendedSong item) {
        if (item == null) {
            return "";
        }
        if (!item.songId().isBlank()) {
            return "id:" + item.songId();
        }
        String artists = item.artists() == null ? "" : String.join("/", item.artists());
        return "title:" + safe(item.title()).toLowerCase(java.util.Locale.ROOT)
                + "|" + artists.toLowerCase(java.util.Locale.ROOT);
    }
}
