package com.musio.playlists;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.capability.MusioPlaylistCapabilityExecutor;
import com.musio.agent.loop.AgentLoopState;
import com.musio.agent.loop.AgentObservation;
import com.musio.agent.loop.AgentObservationStatus;
import com.musio.agent.trace.AgentTracePublisher;
import com.musio.events.AgentEventBus;
import com.musio.model.AgentTaskMemory;
import com.musio.model.ProviderType;
import com.musio.model.Song;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MusioPlaylistCapabilityExecutorTest {
    @TempDir
    Path tempDir;

    @Test
    void explicitSongIdWinsWhenSongIndexAlsoPresent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MusioPlaylistService playlistService = new MusioPlaylistService(
                tempDir.resolve("playlists").resolve("musio-playlists.json"),
                objectMapper
        );
        AgentEventBus eventBus = new AgentEventBus();
        MusioPlaylistCapabilityExecutor executor = new MusioPlaylistCapabilityExecutor(
                playlistService,
                null,
                eventBus,
                new AgentTracePublisher(eventBus),
                objectMapper
        );
        Song first = new Song("qqmusic:x1", ProviderType.QQMUSIC, "素颜", List.of("许嵩", "何曼婷"), "素颜", 238, null);
        Song target = new Song("qqmusic:h1", ProviderType.QQMUSIC, "西厢", List.of("后弦"), "古·玩", 263, null);
        AgentLoopState state = new AgentLoopState(
                "run-1",
                "local",
                "把这首歌加入歌单",
                List.of(),
                AgentTaskMemory.empty("local"),
                List.of(new AgentObservation(
                        "loop.step.1",
                        "recommend_songs",
                        Map.of(),
                        AgentObservationStatus.SUCCESS,
                        "",
                        "recommend_songs 成功",
                        List.of(first, target)
                )),
                1
        );

        String resultJson = executor.executeAddSongToMusioPlaylist(state, Map.of(
                "playlistId", "default",
                "songId", "qqmusic:h1",
                "songTitle", "西厢",
                "artist", "后弦",
                "songIndex", 1
        ));

        var root = objectMapper.readTree(resultJson);
        assertEquals("qqmusic:h1", root.path("songId").asText());
        assertEquals("西厢", root.path("songTitle").asText());
        assertEquals("qqmusic:h1", playlistService.get("default").items().getFirst().providerTrackId());
    }
}
