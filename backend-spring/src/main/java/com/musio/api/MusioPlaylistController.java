package com.musio.api;

import com.musio.model.Song;
import com.musio.playlists.MusioPlaylist;
import com.musio.playlists.MusioPlaylistService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/musio/playlists")
public class MusioPlaylistController {
    private final MusioPlaylistService musioPlaylistService;

    public MusioPlaylistController(MusioPlaylistService musioPlaylistService) {
        this.musioPlaylistService = musioPlaylistService;
    }

    @GetMapping
    public List<MusioPlaylist> playlists() {
        return musioPlaylistService.list();
    }

    @GetMapping("/{playlistId}")
    public MusioPlaylist playlist(@PathVariable String playlistId) {
        return musioPlaylistService.get(playlistId);
    }

    @PostMapping("/{playlistId}/items")
    public MusioPlaylist addSong(@PathVariable String playlistId, @RequestBody Song song) {
        return musioPlaylistService.addSong(playlistId, song);
    }

    @DeleteMapping("/{playlistId}/items/{itemId}")
    public MusioPlaylist removeItem(@PathVariable String playlistId, @PathVariable String itemId) {
        return musioPlaylistService.removeItem(playlistId, itemId);
    }
}
