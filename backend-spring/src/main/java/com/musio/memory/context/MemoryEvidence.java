package com.musio.memory.context;

import java.time.Instant;

public record MemoryEvidence(
        MemoryType type,
        String sourceId,
        String text,
        double score,
        double confidence,
        String evidence,
        Instant updatedAt
) {
    public MemoryEvidence {
        sourceId = sourceId == null ? "" : sourceId.strip();
        text = text == null ? "" : text.strip();
        score = Math.max(0.0, Math.min(1.0, score));
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        evidence = evidence == null ? "" : evidence.strip();
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
    }
}
