package com.musio.model;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        String userId,
        @NotBlank String message,
        String displayMessage
) {
    public ChatRequest(String userId, String message) {
        this(userId, message, null);
    }

    public String visibleMessage() {
        return displayMessage == null || displayMessage.isBlank() ? message : displayMessage.strip();
    }
}
