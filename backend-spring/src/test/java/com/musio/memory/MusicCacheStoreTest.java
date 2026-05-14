package com.musio.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicCacheStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void commentSummarySearchFallsBackToLegacyCommentsCache() {
        MusicCacheStore store = new MusicCacheStore(new SQLiteMemoryDatabase(tempDir.resolve("memory.sqlite")));
        store.upsert(new MusicCacheEntry(
                "",
                "local",
                "comments",
                "qqmusic:1",
                "安静",
                "旧评论缓存：很多人说这首歌安静、治愈，适合夜间专注。",
                "legacy comments cache",
                Instant.parse("2026-05-14T01:00:00Z")
        ));

        List<MusicCacheEntry> results = store.search("local", List.of("commentSummary"), "夜间专注", 5);

        assertTrue(results.stream().anyMatch(entry -> "comments".equals(entry.cacheType())
                && entry.content().contains("夜间专注")));
    }

    @Test
    void searchBySongIdFindsCommentSummaryWhenTextQueryCannotMatch() {
        MusicCacheStore store = new MusicCacheStore(new SQLiteMemoryDatabase(tempDir.resolve("memory.sqlite")));
        store.upsert(new MusicCacheEntry(
                "",
                "local",
                "commentSummary",
                "qqmusic:1",
                "",
                "评论摘录：大家都在聊青春、夏天和校园回忆。",
                "comment summary cache",
                Instant.parse("2026-05-14T02:00:00Z")
        ));

        List<MusicCacheEntry> results = store.searchBySongId("local", List.of("commentSummary"), "qqmusic:1", 5);

        assertTrue(results.stream().anyMatch(entry -> "commentSummary".equals(entry.cacheType())
                && entry.content().contains("校园回忆")));
    }
}
