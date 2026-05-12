package com.musio.memory.context;

import java.util.List;

public record MemoryReadPlan(List<MemoryReadItem> items, int tokenBudget) {
    public MemoryReadPlan {
        items = items == null ? List.of() : List.copyOf(items);
        tokenBudget = tokenBudget <= 0 ? 1200 : Math.min(2000, tokenBudget);
    }

    public static MemoryReadPlan empty() {
        return new MemoryReadPlan(List.of(), 1200);
    }
}
