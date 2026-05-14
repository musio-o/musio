package com.musio.memory.context;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryCompressorTest {
    @Test
    void deduplicatesEvidenceAndPreservesPendingActionAndCurrentState() {
        MemoryCompressor compressor = new MemoryCompressor();
        String duplicateText = "评论缓存：很多人说这首歌适合夜间专注。";

        MemoryContextPackage context = compressor.compress(List.of(
                evidence(MemoryType.MUSIC_CACHE, "comment:1", duplicateText, 0.6, 0.7, "缓存", "2026-05-14T01:00:00Z"),
                evidence(MemoryType.MUSIC_CACHE, "comment:1", duplicateText, 0.5, 0.6, "旧缓存", "2026-05-13T01:00:00Z"),
                evidence(MemoryType.PENDING_ACTION, "default", "待确认歌单写入: 把《安静》加入默认歌单", 0.2, 0.95, "用户尚未确认", "2026-05-14T02:00:00Z"),
                evidence(MemoryType.CURRENT_STATE, "qqmusic:current", "当前播放: 给你宇宙 id=qqmusic:current", 0.2, 0.85, "用户明确引用当前播放", "2026-05-14T03:00:00Z"),
                evidence(MemoryType.MUSIC_CACHE, "comment:2", "评论缓存：" + "很长的缓存内容。".repeat(80), 1.0, 0.9, "缓存", "2026-05-14T04:00:00Z")
        ), 1200);

        assertTrue(context.promptText().contains("待确认动作"));
        assertTrue(context.promptText().contains("待确认歌单写入"));
        assertTrue(context.promptText().contains("当前播放状态"));
        assertTrue(context.promptText().contains("qqmusic:current"));
        assertEquals(1, occurrences(context.promptText(), duplicateText));
    }

    @Test
    void prioritizesNegativePreferencesWithinProfileMemory() {
        MemoryCompressor compressor = new MemoryCompressor();

        MemoryContextPackage context = compressor.compress(List.of(
                evidence(MemoryType.PROFILE_MEMORY, "p1", "偏好候选聚合: 喜欢民谣", 0.9, 0.9, "正向", "2026-05-14T01:00:00Z"),
                evidence(MemoryType.PROFILE_MEMORY, "p2", "偏好候选聚合: 喜欢电子", 0.9, 0.9, "正向", "2026-05-14T01:01:00Z"),
                evidence(MemoryType.PROFILE_MEMORY, "p3", "偏好候选聚合: 喜欢流行", 0.9, 0.9, "正向", "2026-05-14T01:02:00Z"),
                evidence(MemoryType.PROFILE_MEMORY, "p4", "偏好候选聚合: 喜欢R&B", 0.9, 0.9, "正向", "2026-05-14T01:03:00Z"),
                evidence(MemoryType.PROFILE_MEMORY, "n1", "负向偏好候选聚合: 不想听太吵的歌", 0.1, 0.7, "负向", "2026-05-14T01:04:00Z")
        ), 1200);

        assertTrue(context.promptText().contains("负向偏好候选聚合"));
        assertTrue(context.promptText().contains("不想听太吵"));
        assertEquals(4, occurrences(context.promptText(), "偏好候选聚合:"));
        assertFalse(context.promptText().contains("喜欢民谣"));
    }

    @Test
    void rendersConfidenceAndUpdatedAtMetadata() {
        MemoryCompressor compressor = new MemoryCompressor();

        MemoryContextPackage context = compressor.compress(List.of(
                evidence(MemoryType.BEHAVIOR_SUMMARY, "behavior", "最近 7 天行为: 推荐展示 3 次", 0.8, 0.7, "行为摘要", "2026-05-14T01:02:03Z")
        ), 1200);

        assertTrue(context.promptText().contains("置信度: 0.70"));
        assertTrue(context.promptText().contains("更新时间: 2026-05-14T01:02:03Z"));
        assertTrue(context.promptText().contains("依据: 行为摘要"));
    }

    private MemoryEvidence evidence(
            MemoryType type,
            String sourceId,
            String text,
            double score,
            double confidence,
            String evidence,
            String updatedAt
    ) {
        return new MemoryEvidence(type, sourceId, text, score, confidence, evidence, Instant.parse(updatedAt));
    }

    private int occurrences(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
