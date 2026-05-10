package com.musio.model;

import java.time.Instant;
import java.util.List;

public record ChatHistoryMessage(
        String role,
        String content,
        Instant createdAt,
        List<Song> songs,
        ChatConfirmation confirmation
) {
    public ChatHistoryMessage(String role, String content, Instant createdAt, List<Song> songs) {
        this(role, content, createdAt, songs, null);
    }

    public List<Song> songs() {
        return songs == null ? List.of() : List.copyOf(songs);
    }
}
