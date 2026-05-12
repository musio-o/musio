package com.musio.agent.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.memory.context.MemoryContextPackage;
import com.musio.memory.context.MemoryEvidence;
import com.musio.memory.context.MemoryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommendationDraftGeneratorTest {
    private final RecommendationDraftGenerator generator = new RecommendationDraftGenerator(null, new ObjectMapper(), null);

    @Test
    void parsesValidatedRecommendationDraft() {
        RecommendationDraft draft = generator.parseDraft("""
                {
                  "candidates": [
                    {"title": "安静", "artist": "周杰伦", "reason": "钢琴和慢速旋律适合深夜专注。"},
                    {"title": "她说", "artist": "林俊杰", "reason": "旋律有起伏但不会打断思路。"}
                  ],
                  "confidence": 0.91,
                  "source": "model"
                }
                """, 5).orElseThrow();

        assertEquals(2, draft.candidates().size());
        assertEquals("安静", draft.candidates().getFirst().title());
        assertEquals("周杰伦", draft.candidates().getFirst().artist());
        assertEquals("model", draft.source());
    }

    @Test
    void rejectsLowConfidenceDraft() {
        assertTrue(generator.parseDraft("""
                {"candidates":[{"title":"安静","artist":"周杰伦","reason":"适合深夜。"}],"confidence":0.2}
                """, 5).isEmpty());
    }

    @Test
    void dropsCandidatesWithoutTitleArtistOrReason() {
        RecommendationDraft draft = generator.parseDraft("""
                {
                  "candidates": [
                    {"title": "安静", "artist": "", "reason": "适合深夜。"},
                    {"title": "她说", "artist": "林俊杰", "reason": ""},
                    {"title": "好久不见", "artist": "陈奕迅", "reason": "叙事感稳定。"}
                  ],
                  "confidence": 0.91
                }
                """, 5).orElseThrow();

        assertEquals(1, draft.candidates().size());
        assertEquals("好久不见", draft.candidates().getFirst().title());
    }

    @Test
    void limitsCandidatesToRequestedCount() {
        RecommendationDraft draft = generator.parseDraft("""
                {
                  "candidates": [
                    {"title": "安静", "artist": "周杰伦", "reason": "适合深夜。"},
                    {"title": "她说", "artist": "林俊杰", "reason": "适合专注。"},
                    {"title": "好久不见", "artist": "陈奕迅", "reason": "节奏平稳。"}
                  ],
                  "confidence": 0.91
                }
                """, 2).orElseThrow();

        assertEquals(2, draft.candidates().size());
        assertEquals("她说", draft.candidates().get(1).title());
    }

    @Test
    void exposesAdditionalMemoryContextForPrompt() {
        MemoryContextPackage memoryContext = new MemoryContextPackage(
                "[动态记忆上下文]\n[短期任务记忆]\n- 近期已推荐: 安静",
                List.of(new MemoryEvidence(MemoryType.TASK_MEMORY, "local", "近期已推荐: 安静", 0.9, 0.9, "test", Instant.now())),
                16
        );

        assertTrue(generator.memoryContextPreview(memoryContext).contains("近期已推荐"));
        assertEquals("无", generator.memoryContextPreview(MemoryContextPackage.empty()));
    }
}
