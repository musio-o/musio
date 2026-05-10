package com.musio.agent;

import com.musio.model.Song;
import com.musio.model.ChatConfirmation;

import java.time.Instant;
import java.util.List;

public record ConversationHistoryMessage(
        String role,
        String content,
        Instant createdAt,
        List<Song> songs,
        ChatConfirmation confirmation
) {
    public ConversationHistoryMessage(String role, String content, Instant createdAt, List<Song> songs) {
        this(role, content, createdAt, songs, null);
    }

    public List<Song> songs() {
        return songs == null ? List.of() : List.copyOf(songs);
    }
}
