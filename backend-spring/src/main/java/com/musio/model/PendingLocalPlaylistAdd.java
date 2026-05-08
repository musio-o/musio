package com.musio.model;

import java.time.Instant;

public record PendingLocalPlaylistAdd(
        String playlistId,
        Song song,
        String sourceRequest,
        Instant createdAt
) {
    public PendingLocalPlaylistAdd {
        playlistId = playlistId == null || playlistId.isBlank() ? "default" : playlistId.strip();
        sourceRequest = sourceRequest == null ? "" : sourceRequest.strip();
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
