package com.musio.playlists;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.model.ProviderType;
import com.musio.model.Song;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusioPlaylistServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createsDefaultPlaylistFileWhenMissing() {
        Path path = playlistPath();

        MusioPlaylistService service = service(path);

        assertTrue(Files.isRegularFile(path));
        assertEquals(1, service.list().size());
        assertEquals("default", service.list().getFirst().id());
    }

    @Test
    void persistsAddedSongAndLoadsItAfterRestart() {
        Path path = playlistPath();
        Song song = song("qqmusic:1", "不遗憾");

        service(path).addSong("default", song);
        MusioPlaylistService restarted = service(path);

        MusioPlaylist playlist = restarted.get("default");
        assertEquals(1, playlist.items().size());
        assertEquals("qqmusic:1", playlist.items().getFirst().providerTrackId());
        assertEquals("不遗憾", playlist.items().getFirst().title());
        assertEquals(List.of("李荣浩"), playlist.items().getFirst().artists());
    }

    @Test
    void duplicateSongDoesNotCreateDuplicateItem() {
        Path path = playlistPath();
        MusioPlaylistService service = service(path);
        Song song = song("qqmusic:1", "不遗憾");

        service.addSong("default", song);
        service.addSong("default", song);
        MusioPlaylistService restarted = service(path);

        assertEquals(1, restarted.get("default").items().size());
    }

    @Test
    void removesSongAndCompactsSortOrder() {
        Path path = playlistPath();
        MusioPlaylistService service = service(path);

        service.addSong("default", song("qqmusic:1", "不遗憾"));
        service.addSong("default", song("qqmusic:2", "麻雀"));
        String firstItemId = service.get("default").items().getFirst().id();

        MusioPlaylist updated = service.removeItem("default", firstItemId);
        MusioPlaylistService restarted = service(path);

        assertEquals(1, updated.items().size());
        assertEquals("qqmusic:2", updated.items().getFirst().providerTrackId());
        assertEquals(0, updated.items().getFirst().sortOrder());
        assertEquals("qqmusic:2", restarted.get("default").items().getFirst().providerTrackId());
    }

    private MusioPlaylistService service(Path path) {
        return new MusioPlaylistService(path, new ObjectMapper());
    }

    private Path playlistPath() {
        return tempDir.resolve("playlists").resolve("musio-playlists.json");
    }

    private Song song(String id, String title) {
        return new Song(id, ProviderType.QQMUSIC, title, List.of("李荣浩"), "麻雀", 240, "https://example.com/cover.jpg");
    }
}
