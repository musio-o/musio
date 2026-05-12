package com.musio.model;

import java.util.Locale;

public enum ProviderType {
    QQMUSIC("qqmusic"),
    NETEASE("netease"),
    LOCAL("local");

    private final String sourceId;

    ProviderType(String sourceId) {
        this.sourceId = sourceId;
    }

    public String sourceId() {
        return sourceId;
    }

    public static ProviderType fromSourceId(String sourceId) {
        String normalized = normalize(sourceId);
        return switch (normalized) {
            case "qq", "qqmusic" -> QQMUSIC;
            case "netease", "neteasecloudmusic" -> NETEASE;
            case "local", "localmusic" -> LOCAL;
            default -> throw new IllegalArgumentException("Unknown provider source: " + sourceId);
        };
    }

    private static String normalize(String sourceId) {
        return sourceId == null ? "" : sourceId.strip()
                .replace("-", "")
                .replace("_", "")
                .toLowerCase(Locale.ROOT);
    }
}
