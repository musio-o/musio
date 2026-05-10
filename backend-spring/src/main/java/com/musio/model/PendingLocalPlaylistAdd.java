package com.musio.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record PendingLocalPlaylistAdd(
        String playlistId,
        List<Song> songs,
        String sourceRequest,
        Instant createdAt
) {
    public PendingLocalPlaylistAdd(String playlistId, Song song, String sourceRequest, Instant createdAt) {
        this(playlistId, song == null ? List.of() : List.of(song), sourceRequest, createdAt);
    }

    @JsonCreator
    public PendingLocalPlaylistAdd(
            @JsonProperty("playlistId") String playlistId,
            @JsonProperty("songs") List<Song> songs,
            @JsonProperty("song") Song song,
            @JsonProperty("sourceRequest") String sourceRequest,
            @JsonProperty("createdAt") Instant createdAt
    ) {
        this(playlistId, songs == null || songs.isEmpty() ? song == null ? List.of() : List.of(song) : songs, sourceRequest, createdAt);
    }

    public PendingLocalPlaylistAdd {
        playlistId = playlistId == null || playlistId.isBlank() ? "default" : playlistId.strip();
        songs = songs == null ? List.of() : songs.stream()
                .filter(song -> song != null && song.id() != null && !song.id().isBlank())
                .distinct()
                .toList();
        sourceRequest = sourceRequest == null ? "" : sourceRequest.strip();
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public Song song() {
        return songs.isEmpty() ? null : songs.getFirst();
    }
}
