package com.musio.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.model.AgentRecentRecommendedSong;
import com.musio.model.AgentTaskMemory;
import com.musio.model.AgentTaskRecommendationSlot;
import com.musio.model.PendingLocalPlaylistAdd;
import com.musio.model.ProviderType;
import com.musio.model.Song;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskMemoryServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void recordsTaskAndResultSongs() {
        AgentTaskMemoryService service = service();

        service.recordTask("local", "搜索周杰伦的一首歌曲", "周杰伦", 1, List.of("晴天"));
        service.recordResultSongs("local", List.of(
                new Song("qqmusic:1", ProviderType.QQMUSIC, "枫", List.of("周杰伦"), "十一月的萧邦", 275, null)
        ));

        AgentTaskMemory memory = service.read("local");
        assertEquals("搜索周杰伦的一首歌曲", memory.lastEffectiveRequest());
        assertEquals("周杰伦", memory.lastSearchKeyword());
        assertEquals(1, memory.lastSearchLimit());
        assertEquals(List.of("枫"), memory.lastResultSongTitles());
        assertEquals("qqmusic:1", memory.lastTargetSong().id());
        assertEquals(List.of("晴天"), memory.avoidSongTitles());
    }

    @Test
    void recordsToolFailureAndClearsItOnSuccessfulSongs() {
        AgentTaskMemoryService service = service();

        service.recordTask("local", "给我推荐深夜听的歌", "深夜听", 5, List.of());
        service.recordToolFailure("local", "search_songs", "HTTP 500");
        assertEquals(1, service.read("local").lastToolFailures().size());

        service.recordResultSongs("local", List.of());
        assertTrue(service.read("local").lastToolFailures().isEmpty());
    }

    @Test
    void clearsOldSongResultsWhenTaskChanges() {
        AgentTaskMemoryService service = service();

        service.recordTask("local", "搜索周杰伦的一首歌曲", "周杰伦", 1, List.of());
        service.recordResultSongs("local", List.of(
                new Song("qqmusic:1", ProviderType.QQMUSIC, "晴天", List.of("周杰伦"), "叶惠美", 269, null)
        ));
        service.recordTask("local", "搜索林俊杰的一首歌曲", "林俊杰", 1, List.of());

        assertTrue(service.read("local").lastResultSongTitles().isEmpty());
        assertEquals(null, service.read("local").lastTargetSong());
    }

    @Test
    void preservesLastSongResultsForNonSearchFollowUp() {
        AgentTaskMemoryService service = service();

        service.recordTask("local", "搜索林俊杰的一首歌曲", "林俊杰", 1, List.of());
        service.recordResultSongs("local", List.of(
                new Song("qqmusic:1", ProviderType.QQMUSIC, "Always Online", List.of("林俊杰"), "JJ陆", 303, null)
        ));
        service.recordTask("local", "读取并总结林俊杰《Always Online》的热门评论", "", null, List.of());

        AgentTaskMemory memory = service.read("local");
        assertEquals(List.of("Always Online"), memory.lastResultSongTitles());
        assertEquals("qqmusic:1", memory.lastResultSongs().getFirst().id());
        assertEquals("qqmusic:1", memory.lastTargetSong().id());
    }

    @Test
    void recordsLoopEvidenceSummaryWithoutRawResults() {
        AgentTaskMemoryService service = service();
        Song song = new Song("qqmusic:1", ProviderType.QQMUSIC, "晴天", List.of("周杰伦"), "叶惠美", 269, null);

        service.recordTask("local", "搜索周杰伦并分享评论", "周杰伦", 1, List.of());
        service.recordLoopEvidence("local", song, "comments", List.of("search_songs 成功，歌曲 1 首：晴天 id=qqmusic:1", "get_hot_comments 成功，评论 1 条"));

        AgentTaskMemory memory = service.read("local");
        assertEquals("qqmusic:1", memory.lastTargetSong().id());
        assertEquals("comments", memory.lastCompletedTaskType());
        assertEquals(2, memory.lastObservationSummaries().size());
    }

    @Test
    void recordsStructuredEvidenceAndPreservesItForCorrection() {
        AgentTaskMemoryService service = service();
        Song song = new Song("qqmusic:h1", ProviderType.QQMUSIC, "西厢", List.of("后弦"), "自定义", 240, null);

        service.recordTask("local", "推荐一首后弦的歌", "", null, List.of());
        service.recordResultSongs("local", List.of(song));
        service.recordStructuredEvidence(
                "local",
                List.of("RECOMMENDATION", "COMMENTS", "LOCAL_PLAYLIST_WRITE"),
                List.of(new AgentTaskRecommendationSlot("houxian", "artist", "后弦", 1, List.of("qqmusic:h1"), List.of("西厢"))),
                List.of("recommend_songs", "get_hot_comments", "add_song_to_musio_playlist"),
                List.of("add_song_to_musio_playlist")
        );

        service.recordTask("local", "修正上一轮推荐，替换《西厢》", "", null, List.of("西厢"), true);

        AgentTaskMemory memory = service.read("local");
        assertEquals(List.of("RECOMMENDATION", "COMMENTS", "LOCAL_PLAYLIST_WRITE"), memory.lastRequiredOutcomes());
        assertEquals("houxian", memory.lastRecommendationSlots().getFirst().slotId());
        assertEquals(List.of("西厢"), memory.avoidSongTitles());
        assertEquals("qqmusic:h1", memory.lastResultSongs().getFirst().id());
    }

    @Test
    void clearsStructuredEvidenceForUnrelatedNewTask() {
        AgentTaskMemoryService service = service();
        service.recordStructuredEvidence(
                "local",
                List.of("RECOMMENDATION"),
                List.of(new AgentTaskRecommendationSlot("houxian", "artist", "后弦", 1, List.of("qqmusic:h1"), List.of("西厢"))),
                List.of("recommend_songs"),
                List.of()
        );

        service.recordTask("local", "搜索周杰伦", "周杰伦", 1, List.of(), false);

        AgentTaskMemory memory = service.read("local");
        assertTrue(memory.lastRequiredOutcomes().isEmpty());
        assertTrue(memory.lastRecommendationSlots().isEmpty());
        assertTrue(memory.lastEvidenceTools().isEmpty());
    }

    @Test
    void recordsRecentRecommendationsAndPreservesThemAcrossNewTasks() {
        AgentTaskMemoryService service = service();

        service.recordRecentRecommendations("local", List.of(
                new AgentRecentRecommendedSong(
                        "qqmusic:h1",
                        "西厢",
                        List.of("后弦"),
                        "houxian",
                        "推荐一首后弦的歌",
                        "代表作",
                        "run-1",
                        "soft_avoid",
                        java.time.Instant.now()
                )
        ));
        service.recordTask("local", "推荐一首许嵩的歌", "", null, List.of(), false);

        AgentTaskMemory memory = service.read("local");
        assertEquals(1, memory.recentRecommendedSongs().size());
        assertEquals("西厢", memory.recentRecommendedSongs().getFirst().title());
    }

    @Test
    void dedupesRecentRecommendationsBySongIdWithNewestFirst() {
        AgentTaskMemoryService service = service();

        service.recordRecentRecommendations("local", List.of(
                new AgentRecentRecommendedSong("qqmusic:h1", "西厢", List.of("后弦"), "houxian", "第一次", "", "run-1", "soft_avoid", java.time.Instant.now())
        ));
        service.recordRecentRecommendations("local", List.of(
                new AgentRecentRecommendedSong("qqmusic:h1", "西厢", List.of("后弦"), "houxian", "第二次", "", "run-2", "soft_avoid", java.time.Instant.now())
        ));

        AgentTaskMemory memory = service.read("local");
        assertEquals(1, memory.recentRecommendedSongs().size());
        assertEquals("run-2", memory.recentRecommendedSongs().getFirst().runId());
    }

    @Test
    void recordsAndClearsPendingLocalPlaylistAdd() {
        AgentTaskMemoryService service = service();
        Song song = new Song("qqmusic:1", ProviderType.QQMUSIC, "晴天", List.of("周杰伦"), "叶惠美", 269, null);

        service.recordPendingLocalPlaylistAdd("local", new PendingLocalPlaylistAdd("default", song, "帮我收藏晴天", null));

        AgentTaskMemory pendingMemory = service.read("local");
        assertEquals("qqmusic:1", pendingMemory.pendingLocalPlaylistAdd().song().id());
        assertEquals("qqmusic:1", pendingMemory.lastTargetSong().id());

        service.clearPendingLocalPlaylistAdd("local");

        assertEquals(null, service.read("local").pendingLocalPlaylistAdd());
    }

    private AgentTaskMemoryService service() {
        return new AgentTaskMemoryService(new AgentTaskMemoryStore(tempDir, new ObjectMapper()));
    }
}
