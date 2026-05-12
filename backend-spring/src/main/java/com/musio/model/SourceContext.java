package com.musio.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record SourceContext(
        List<String> selectedSources,
        String activeSource,
        String userId
) {
    public static final String DEFAULT_SOURCE = "qqmusic";
    public static final String DEFAULT_USER_ID = "local";

    public SourceContext {
        List<String> normalizedSelectedSources = normalizeSources(selectedSources);
        String normalizedActiveSource = normalizeSource(activeSource);
        if (normalizedActiveSource.isBlank() || !normalizedSelectedSources.contains(normalizedActiveSource)) {
            normalizedActiveSource = normalizedSelectedSources.getFirst();
        }
        selectedSources = normalizedSelectedSources;
        activeSource = normalizedActiveSource;
        userId = userId == null || userId.isBlank() ? DEFAULT_USER_ID : userId.strip();
    }

    public static SourceContext defaultContext() {
        return new SourceContext(List.of(DEFAULT_SOURCE), DEFAULT_SOURCE, DEFAULT_USER_ID);
    }

    public SourceContext withUserId(String userId) {
        return new SourceContext(selectedSources, activeSource, userId);
    }

    public ProviderType activeProviderType() {
        return ProviderType.fromSourceId(activeSource);
    }

    public boolean selects(String sourceId) {
        String normalized = normalizeSource(sourceId);
        return !normalized.isBlank() && selectedSources.contains(normalized);
    }

    private static List<String> normalizeSources(List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of(DEFAULT_SOURCE);
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String source : sources) {
            String normalizedSource = normalizeSource(source);
            if (!normalizedSource.isBlank()) {
                normalized.add(normalizedSource);
            }
        }
        if (normalized.isEmpty()) {
            normalized.add(DEFAULT_SOURCE);
        }
        return List.copyOf(normalized);
    }

    private static String normalizeSource(String source) {
        if (source == null) {
            return "";
        }
        String normalized = source.strip()
                .replace("-", "")
                .replace("_", "")
                .toLowerCase(Locale.ROOT);
        if ("qq".equals(normalized) || "qqmusic".equals(normalized)) {
            return DEFAULT_SOURCE;
        }
        if ("neteasecloudmusic".equals(normalized)) {
            return "netease";
        }
        if ("localmusic".equals(normalized)) {
            return "local";
        }
        return normalized;
    }
}
