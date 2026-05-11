package com.musio.playlists;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.musio.config.MusioConfigService;
import com.musio.model.Song;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MusioPlaylistService {
    private static final String DEFAULT_PLAYLIST_ID = "default";

    private final Map<String, MusioPlaylist> playlists = new ConcurrentHashMap<>();
    private final Path playlistPath;
    private final ObjectMapper objectMapper;

    @Autowired
    public MusioPlaylistService(MusioConfigService configService, ObjectMapper objectMapper) {
        this(
                configService.config().storage().home().resolve("playlists").resolve("musio-playlists.json"),
                objectMapper
        );
    }

    MusioPlaylistService(Path playlistPath, ObjectMapper objectMapper) {
        this.playlistPath = playlistPath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper.copy()
                .findAndRegisterModules()
                .enable(SerializationFeature.INDENT_OUTPUT);
        loadOrInitialize();
    }

    private void loadOrInitialize() {
        boolean shouldPersist = false;
        if (Files.isRegularFile(playlistPath)) {
            List<MusioPlaylist> stored = readPlaylists();
            stored.stream()
                    .map(this::safePlaylist)
                    .forEach(playlist -> playlists.put(playlist.id(), playlist));
        } else {
            shouldPersist = true;
        }
        if (!playlists.containsKey(DEFAULT_PLAYLIST_ID)) {
            playlists.put(DEFAULT_PLAYLIST_ID, defaultPlaylist());
            shouldPersist = true;
        }
        if (shouldPersist) {
            persist();
        }
    }

    private List<MusioPlaylist> readPlaylists() {
        try {
            PlaylistFile file = objectMapper.readValue(playlistPath.toFile(), PlaylistFile.class);
            return file.playlists();
        } catch (IOException structuredReadFailure) {
            try {
                return objectMapper.readValue(playlistPath.toFile(), new TypeReference<List<MusioPlaylist>>() {
                });
            } catch (IOException listReadFailure) {
                throw new IllegalStateException("Failed to read Musio playlists: " + playlistPath, structuredReadFailure);
            }
        }
    }

    private MusioPlaylist defaultPlaylist() {
        Instant now = Instant.now();
        return new MusioPlaylist(
                DEFAULT_PLAYLIST_ID,
                "Musio Queue",
                "Cross-source songs saved from Agent results.",
                List.of(),
                now,
                now
        );
    }

    public List<MusioPlaylist> list() {
        return playlists.values().stream()
                .sorted(Comparator.comparing(
                        MusioPlaylist::createdAt,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ))
                .toList();
    }

    public MusioPlaylist get(String playlistId) {
        MusioPlaylist playlist = playlists.get(playlistId);
        if (playlist == null) {
            throw new IllegalArgumentException("Musio playlist not found: " + playlistId);
        }
        return playlist;
    }

    public synchronized MusioPlaylist addSong(String playlistId, Song song) {
        MusioPlaylist playlist = get(playlistId);
        if (song == null || song.id() == null || song.id().isBlank()) {
            throw new IllegalArgumentException("Song id is required.");
        }
        boolean exists = playlist.items().stream()
                .anyMatch(item -> song.id().equals(item.providerTrackId()));
        if (exists) {
            return playlist;
        }

        Instant now = Instant.now();
        List<MusioPlaylistItem> items = new ArrayList<>(playlist.items());
        items.add(new MusioPlaylistItem(
                UUID.randomUUID().toString(),
                playlist.id(),
                song.provider(),
                song.id(),
                song.title(),
                song.artists() == null ? List.of() : List.copyOf(song.artists()),
                song.album(),
                song.durationSeconds(),
                song.artworkUrl(),
                null,
                items.size(),
                now
        ));
        MusioPlaylist updated = new MusioPlaylist(
                playlist.id(),
                playlist.name(),
                playlist.description(),
                List.copyOf(items),
                playlist.createdAt(),
                now
        );
        playlists.put(updated.id(), updated);
        persist();
        return updated;
    }

    public synchronized MusioPlaylist removeItem(String playlistId, String itemId) {
        MusioPlaylist playlist = get(playlistId);
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("Playlist item id is required.");
        }
        List<MusioPlaylistItem> remaining = playlist.items().stream()
                .filter(item -> !itemId.equals(item.id()))
                .toList();
        if (remaining.size() == playlist.items().size()) {
            return playlist;
        }

        MusioPlaylist updated = new MusioPlaylist(
                playlist.id(),
                playlist.name(),
                playlist.description(),
                renumberItems(remaining),
                playlist.createdAt(),
                Instant.now()
        );
        playlists.put(updated.id(), updated);
        persist();
        return updated;
    }

    private void persist() {
        try {
            Files.createDirectories(playlistPath.getParent());
            objectMapper.writeValue(playlistPath.toFile(), new PlaylistFile(list()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store Musio playlists: " + playlistPath, e);
        }
    }

    private MusioPlaylist safePlaylist(MusioPlaylist playlist) {
        if (playlist == null || playlist.id() == null || playlist.id().isBlank()) {
            return defaultPlaylist();
        }
        Instant now = Instant.now();
        return new MusioPlaylist(
                playlist.id(),
                playlist.name() == null || playlist.name().isBlank() ? playlist.id() : playlist.name(),
                playlist.description() == null ? "" : playlist.description(),
                playlist.items() == null ? List.of() : List.copyOf(playlist.items()),
                playlist.createdAt() == null ? now : playlist.createdAt(),
                playlist.updatedAt() == null ? now : playlist.updatedAt()
        );
    }

    private record PlaylistFile(List<MusioPlaylist> playlists) {
        @JsonCreator
        private PlaylistFile(@JsonProperty("playlists") List<MusioPlaylist> playlists) {
            this.playlists = playlists == null ? List.of() : List.copyOf(playlists);
        }
    }

    private List<MusioPlaylistItem> renumberItems(List<MusioPlaylistItem> items) {
        List<MusioPlaylistItem> renumbered = new ArrayList<>();
        for (int index = 0; index < items.size(); index += 1) {
            MusioPlaylistItem item = items.get(index);
            renumbered.add(new MusioPlaylistItem(
                    item.id(),
                    item.playlistId(),
                    item.provider(),
                    item.providerTrackId(),
                    item.title(),
                    item.artists(),
                    item.album(),
                    item.durationSeconds(),
                    item.artworkUrl(),
                    item.sourceUrl(),
                    index,
                    item.createdAt()
            ));
        }
        return List.copyOf(renumbered);
    }
}
