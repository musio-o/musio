package com.musio.agent;

import com.musio.agent.recommendation.RecommendationSlot;
import com.musio.model.AgentTaskMemory;
import com.musio.model.AgentTaskRecommendationSlot;
import com.musio.model.ProviderType;
import com.musio.model.Song;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMemoryRouterTest {
    private final AgentMemoryRouter router = new AgentMemoryRouter();

    @Test
    void routesDuplicateRecommendationCorrectionBackToMusicTools() {
        AgentTurnPlan planned = AgentTurnPlan.respondOnly("西乡不是已经推荐过了吗", 0.9, "model_chat");

        AgentTurnPlan repaired = router.repairPlan("西乡不是已经推荐过了吗", planned, previousCompositeRecommendationMemory())
                .orElseThrow();

        assertEquals(TurnDisposition.USE_TOOLS, repaired.disposition());
        assertEquals("recommend", repaired.taskType());
        assertEquals("correction", repaired.contextMode());
        assertEquals(List.of(
                AgentRequiredOutcome.RECOMMENDATION,
                AgentRequiredOutcome.COMMENTS,
                AgentRequiredOutcome.LYRICS,
                AgentRequiredOutcome.LOCAL_PLAYLIST_WRITE
        ), repaired.requiredOutcomes());
        assertEquals(List.of("lastResultSongs", "lastRecommendationSlots", "lastRequiredOutcomes", "avoidSongTitles", "lastEvidenceTools"), repaired.memoryUse().usedFields());

        RecommendationSlot replacementSlot = repaired.recommendationSlots().getFirst();
        assertEquals("houxian", replacementSlot.slotId());
        assertEquals("后弦", replacementSlot.target());
        assertEquals(1, replacementSlot.count());
        assertTrue(repaired.avoidSongTitles().contains("西厢"));
        assertTrue(repaired.avoidSongTitles().contains("素颜"));

        AgentTaskContext context = repaired.toLegacyTaskContext("西乡不是已经推荐过了吗");
        assertTrue(context.agentTask());
        assertTrue(context.followUp());
        assertTrue(context.preservePreviousSongContext());
        assertEquals(List.of("素颜", "清明雨上", "西厢"), context.avoidSongTitles());
    }

    @Test
    void doesNotOverridePlainChat() {
        assertFalse(router.repairPlan("谢谢你", AgentTurnPlan.respondOnly("谢谢你", 0.9, ""), previousCompositeRecommendationMemory()).isPresent());
    }

    @Test
    void doesNotOverrideWithoutSongMatch() {
        assertFalse(router.repairPlan("这首是不是重复了", AgentTurnPlan.respondOnly("这首是不是重复了", 0.9, ""), previousCompositeRecommendationMemory()).isPresent());
    }

    private AgentTaskMemory previousCompositeRecommendationMemory() {
        List<Song> songs = List.of(
                new Song("qqmusic:x1", ProviderType.QQMUSIC, "素颜", List.of("许嵩", "何曼婷"), "自定义", 240, null),
                new Song("qqmusic:x2", ProviderType.QQMUSIC, "清明雨上", List.of("许嵩"), "自定义", 240, null),
                new Song("qqmusic:h1", ProviderType.QQMUSIC, "西厢", List.of("后弦"), "自定义", 240, null)
        );
        return new AgentTaskMemory(
                "local",
                "music-agent-task",
                "推荐两首许嵩的歌和一首后弦的歌，并获取热评、歌词，最后加入歌单",
                "",
                null,
                songs,
                List.of("素颜", "清明雨上", "西厢"),
                List.of(),
                List.of(),
                songs.getFirst(),
                "playlist",
                List.of("recommend_songs 成功，覆盖 3/3", "add_song_to_musio_playlist 成功"),
                List.of("RECOMMENDATION", "COMMENTS", "LYRICS", "LOCAL_PLAYLIST_WRITE"),
                List.of(
                        new AgentTaskRecommendationSlot("xusong", "artist", "许嵩", 2, List.of("qqmusic:x1", "qqmusic:x2"), List.of("素颜", "清明雨上")),
                        new AgentTaskRecommendationSlot("houxian", "artist", "后弦", 1, List.of("qqmusic:h1"), List.of("西厢"))
                ),
                List.of("recommend_songs", "get_hot_comments", "get_lyrics", "add_song_to_musio_playlist"),
                List.of("add_song_to_musio_playlist"),
                null,
                Instant.now()
        );
    }
}
