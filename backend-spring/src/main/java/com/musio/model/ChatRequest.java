package com.musio.model;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ChatRequest(
        String userId,
        @NotBlank String message,
        String displayMessage,
        List<String> selectedSources,
        String activeSource
) {
    public ChatRequest {
        selectedSources = defaultedSources(selectedSources);
        activeSource = activeSource == null || activeSource.isBlank() ? SourceContext.DEFAULT_SOURCE : activeSource;
    }

    public ChatRequest(String userId, String message) {
        this(userId, message, null);
    }

    public ChatRequest(String userId, String message, String displayMessage) {
        this(userId, message, displayMessage, List.of(SourceContext.DEFAULT_SOURCE), SourceContext.DEFAULT_SOURCE);
    }

    public String visibleMessage() {
        return displayMessage == null || displayMessage.isBlank() ? message : displayMessage.strip();
    }

    public SourceContext sourceContext(String normalizedUserId) {
        return new SourceContext(selectedSources, activeSource, normalizedUserId);
    }

    private static List<String> defaultedSources(List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of(SourceContext.DEFAULT_SOURCE);
        }
        List<String> selected = sources.stream()
                .filter(source -> source != null && !source.isBlank())
                .toList();
        return selected.isEmpty() ? List.of(SourceContext.DEFAULT_SOURCE) : selected;
    }
}
