package com.musio.model;

public record ChatConfirmation(
        String type,
        String title,
        String description,
        String confirmText,
        String cancelText,
        Song song
) {
    public ChatConfirmation {
        type = type == null || type.isBlank() ? "local_playlist_add" : type.strip();
        title = title == null ? "" : title.strip();
        description = description == null ? "" : description.strip();
        confirmText = confirmText == null || confirmText.isBlank() ? "确认收藏" : confirmText.strip();
        cancelText = cancelText == null || cancelText.isBlank() ? "取消收藏" : cancelText.strip();
    }
}
