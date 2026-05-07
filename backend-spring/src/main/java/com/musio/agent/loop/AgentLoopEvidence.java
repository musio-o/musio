package com.musio.agent.loop;

import com.musio.model.Song;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AgentLoopEvidence(
        List<AgentObservation> observations,
        List<Song> songs,
        String completedTaskType,
        Song targetSong,
        List<String> observationSummaries
) {
    public AgentLoopEvidence {
        observations = observations == null ? List.of() : List.copyOf(observations);
        songs = songs == null ? List.of() : dedupeSongs(songs);
        completedTaskType = completedTaskType == null ? "" : completedTaskType.strip();
        observationSummaries = observationSummaries == null ? List.of() : List.copyOf(observationSummaries);
    }

    public static AgentLoopEvidence empty() {
        return new AgentLoopEvidence(List.of(), List.of(), "", null, List.of());
    }

    public boolean hasObservations() {
        return !observations.isEmpty();
    }

    private static List<Song> dedupeSongs(List<Song> values) {
        Map<String, Song> songsById = new LinkedHashMap<>();
        for (Song song : values) {
            if (song == null || song.id() == null || song.id().isBlank()) {
                continue;
            }
            songsById.putIfAbsent(song.id(), song);
        }
        return List.copyOf(songsById.values());
    }
}
