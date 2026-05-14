package com.musio.memory.context;

import com.musio.agent.AgentGoal;
import com.musio.agent.AgentRequiredOutcome;
import com.musio.memory.MusicCacheEntry;
import com.musio.memory.MusicCacheStore;
import com.musio.memory.SQLiteMemoryDatabase;
import com.musio.model.AgentTaskMemory;
import com.musio.model.ProviderType;
import com.musio.model.Song;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryRetrieverMusicCacheTest {
    @TempDir
    Path tempDir;

    @Test
    void musicCacheFallsBackToTaskMemorySongIdWhenQueryMisses() {
        SQLiteMemoryDatabase database = new SQLiteMemoryDatabase(tempDir.resolve("memory.sqlite"));
        MusicCacheStore musicCacheStore = new MusicCacheStore(database);
        Song song = new Song("qqmusic:1", ProviderType.QQMUSIC, "七里香", List.of("周杰伦"), "七里香", 299, "");
        musicCacheStore.upsert(new MusicCacheEntry(
                "",
                "local",
                "commentSummary",
                song.id(),
                "",
                "评论摘录：大家都在聊青春、夏天和校园回忆。",
                "comment summary cache",
                Instant.parse("2026-05-14T02:00:00Z")
        ));
        MemoryRetriever retriever = new MemoryRetriever(null, null, musicCacheStore, null, null);

        List<MemoryEvidence> evidence = retriever.retrieve(new MemoryRouteRequest(
                "local",
                "这首歌的评论区都在聊啥",
                "comments",
                "refer_previous_song",
                "查看《七里香》的热门评论",
                goal(),
                taskMemory(song),
                List.of()
        ), new MemoryReadPlan(List.of(new MemoryReadItem(
                MemoryType.MUSIC_CACHE,
                List.of("commentSummary"),
                "七里香 周杰伦 评论",
                "session",
                90,
                1,
                "读取评论缓存"
        )), 1200));

        assertTrue(evidence.stream().anyMatch(item -> item.type() == MemoryType.MUSIC_CACHE
                && song.id().equals(item.sourceId())
                && item.text().contains("校园回忆")));
    }

    private AgentGoal goal() {
        return new AgentGoal(
                "这首歌的评论区都在聊啥",
                "查看《七里香》的热门评论",
                "comments",
                "refer_previous_song",
                true,
                true,
                false,
                false,
                0,
                List.of(),
                List.of(AgentRequiredOutcome.COMMENTS)
        );
    }

    private AgentTaskMemory taskMemory(Song song) {
        return new AgentTaskMemory(
                "local",
                "music-agent-task",
                "推荐一首周杰伦的歌",
                "七里香",
                1,
                List.of(song),
                List.of(song.title()),
                List.of(),
                List.of(),
                song,
                "recommend",
                List.of("recommend_songs 成功，歌曲 1 首：七里香 id=qqmusic:1"),
                null,
                Instant.parse("2026-05-14T01:59:00Z")
        );
    }
}
