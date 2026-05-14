package com.musio.memory.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class MemoryReadPlanValidator {
    private static final int MAX_TOKEN_BUDGET = 1600;
    private static final int MAX_QUERY_LENGTH = 160;
    private static final Set<String> ALLOWED_SCOPES = Set.of("session", "last_24h", "last_7_days", "profile", "current");
    private static final Map<MemoryType, Set<String>> ALLOWED_FIELDS = Map.of(
            MemoryType.TASK_MEMORY, Set.of("lastEffectiveRequest", "lastTargetSong", "lastResultSongs", "avoidSongTitles", "recentRecommendedSongs", "lastRecommendationSlots", "lastRequiredOutcomes", "lastObservationSummaries", "pendingLocalPlaylistAdd"),
            MemoryType.PROFILE_MEMORY, Set.of("summary", "strongPreferences", "favoriteArtists", "favoriteAlbums", "likedSongExamples", "recommendationHints", "avoid"),
            MemoryType.BEHAVIOR_SUMMARY, Set.of("last24HoursSummary", "last7DaysSummary", "negativeSignals", "sceneSignals"),
            MemoryType.MUSIC_CACHE, Set.of("songDetail", "lyricsSummary", "commentSummary", "comments", "playlistSummary"),
            MemoryType.CONVERSATION_SUMMARY, Set.of("dialogueSummary", "lastMessages"),
            MemoryType.CURRENT_STATE, Set.of("currentPlayback", "queueState"),
            MemoryType.PENDING_ACTION, Set.of("pendingLocalPlaylistAdd")
    );

    public MemoryReadPlan validate(MemoryReadPlan required, MemoryReadPlan dynamic) {
        List<MemoryReadItem> merged = new ArrayList<>();
        addValidItems(merged, required);
        addValidItems(merged, dynamic);
        return new MemoryReadPlan(mergeItems(merged), tokenBudget(required, dynamic));
    }

    public Map<MemoryType, Set<String>> allowedFields() {
        return ALLOWED_FIELDS;
    }

    private void addValidItems(List<MemoryReadItem> target, MemoryReadPlan plan) {
        if (plan == null || plan.items().isEmpty()) {
            return;
        }
        for (MemoryReadItem item : plan.items()) {
            validateItem(item).forEach(target::add);
        }
    }

    private List<MemoryReadItem> validateItem(MemoryReadItem item) {
        if (item == null || item.type() == null || !ALLOWED_FIELDS.containsKey(item.type())) {
            return List.of();
        }
        if (!allowedScope(item.scope()) || unsafeQuery(item.query())) {
            return List.of();
        }
        List<String> fields = item.fields().stream()
                .filter(field -> ALLOWED_FIELDS.get(item.type()).contains(field))
                .distinct()
                .limit(12)
                .toList();
        if (fields.isEmpty()) {
            return List.of();
        }
        return List.of(new MemoryReadItem(
                item.type(),
                fields,
                truncate(item.query(), MAX_QUERY_LENGTH),
                item.scope(),
                item.priority(),
                item.limit(),
                item.reason()
        ));
    }

    private List<MemoryReadItem> mergeItems(List<MemoryReadItem> items) {
        Map<MergeKey, MergedItem> merged = new LinkedHashMap<>();
        for (MemoryReadItem item : items) {
            MergeKey key = new MergeKey(item.type(), item.scope(), item.query());
            MergedItem value = merged.computeIfAbsent(key, ignored -> new MergedItem(item.type(), item.scope(), item.query()));
            value.fields.addAll(item.fields());
            value.priority = Math.max(value.priority, item.priority());
            value.limit = Math.max(value.limit, item.limit());
            if (value.reason.isBlank() && !item.reason().isBlank()) {
                value.reason = item.reason();
            }
        }
        return merged.values().stream()
                .map(value -> new MemoryReadItem(value.type, List.copyOf(value.fields), value.query, value.scope, value.priority, value.limit, value.reason))
                .sorted((left, right) -> Integer.compare(right.priority(), left.priority()))
                .limit(12)
                .toList();
    }

    private int tokenBudget(MemoryReadPlan required, MemoryReadPlan dynamic) {
        int budget = Math.max(required == null ? 0 : required.tokenBudget(), dynamic == null ? 0 : dynamic.tokenBudget());
        return budget <= 0 ? 1200 : Math.min(MAX_TOKEN_BUDGET, budget);
    }

    private boolean allowedScope(String scope) {
        return ALLOWED_SCOPES.contains(scope == null ? "session" : scope.strip());
    }

    private boolean unsafeQuery(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return normalized.contains("..")
                || normalized.contains("/")
                || normalized.contains("\\")
                || normalized.contains("jsonl")
                || normalized.contains("file:")
                || normalized.contains("path=");
    }

    private String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value == null ? "" : value;
        }
        return value.substring(0, limit);
    }

    private record MergeKey(MemoryType type, String scope, String query) {
    }

    private static final class MergedItem {
        private final MemoryType type;
        private final LinkedHashSet<String> fields = new LinkedHashSet<>();
        private final String query;
        private final String scope;
        private int priority = 0;
        private int limit = 1;
        private String reason = "";

        private MergedItem(MemoryType type, String scope, String query) {
            this.type = type;
            this.scope = scope;
            this.query = query;
        }
    }
}
