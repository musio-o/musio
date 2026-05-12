package com.musio.memory.context;

import java.util.List;

public record MemoryContextPackage(
        String promptText,
        List<MemoryEvidence> evidence,
        int estimatedTokens
) {
    public MemoryContextPackage {
        promptText = promptText == null ? "" : promptText.strip();
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        estimatedTokens = Math.max(0, estimatedTokens);
    }

    public static MemoryContextPackage empty() {
        return new MemoryContextPackage("", List.of(), 0);
    }

    public boolean isEmpty() {
        return promptText.isBlank() && evidence.isEmpty();
    }
}
