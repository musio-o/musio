package com.musio.model;

import java.util.List;

public record ChatConfirmation(
        String type,
        String title,
        String description,
        String confirmText,
        String cancelText,
        Song song,
        List<Song> songs,
        String selectionMode,
        List<String> defaultSelectedSongIds
) {
    public ChatConfirmation(String type, String title, String description, String confirmText, String cancelText, Song song) {
        this(type, title, description, confirmText, cancelText, song, song == null ? List.of() : List.of(song), "single",
                song == null || song.id() == null || song.id().isBlank() ? List.of() : List.of(song.id()));
    }

    public ChatConfirmation {
        type = type == null || type.isBlank() ? "local_playlist_add" : type.strip();
        title = title == null ? "" : title.strip();
        description = description == null ? "" : description.strip();
        confirmText = confirmText == null || confirmText.isBlank() ? "确认收藏" : confirmText.strip();
        cancelText = cancelText == null || cancelText.isBlank() ? "取消收藏" : cancelText.strip();
        songs = songs == null ? List.of() : songs.stream()
                .filter(item -> item != null && item.id() != null && !item.id().isBlank())
                .distinct()
                .toList();
        if (song == null && !songs.isEmpty()) {
            song = songs.getFirst();
        }
        selectionMode = selectionMode == null || selectionMode.isBlank()
                ? songs.size() > 1 ? "multiple" : "single"
                : selectionMode.strip();
        defaultSelectedSongIds = defaultSelectedSongIds == null ? List.of() : defaultSelectedSongIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
        if (defaultSelectedSongIds.isEmpty()) {
            defaultSelectedSongIds = songs.stream().map(Song::id).toList();
        }
    }
}
