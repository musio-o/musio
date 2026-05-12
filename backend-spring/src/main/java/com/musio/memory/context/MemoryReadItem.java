package com.musio.memory.context;

import java.util.List;

public record MemoryReadItem(
        MemoryType type,
        List<String> fields,
        String query,
        String scope,
        int priority,
        int limit,
        String reason
) {
    public MemoryReadItem {
        fields = fields == null ? List.of() : fields.stream()
                .filter(field -> field != null && !field.isBlank())
                .map(String::strip)
                .distinct()
                .limit(20)
                .toList();
        query = query == null ? "" : query.strip();
        scope = scope == null || scope.isBlank() ? "session" : scope.strip();
        priority = Math.max(0, Math.min(100, priority));
        limit = limit <= 0 ? 1 : Math.min(10, limit);
        reason = reason == null ? "" : reason.strip();
    }
}
