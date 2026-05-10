package com.musio.agent.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.ConversationHistoryMessage;
import com.musio.agent.loop.AgentLoopState;
import com.musio.agent.trace.AgentTracePublisher;
import com.musio.events.AgentEventBus;
import com.musio.model.AgentEvent;
import com.musio.model.AgentTaskMemory;
import com.musio.model.Song;
import com.musio.playlists.MusioPlaylist;
import com.musio.playlists.MusioPlaylistService;
import com.musio.tools.MusicReadTools;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class MusioPlaylistCapabilityExecutor {
    private final MusioPlaylistService musioPlaylistService;
    private final MusicReadTools musicReadTools;
    private final AgentEventBus eventBus;
    private final AgentTracePublisher tracePublisher;
    private final ObjectMapper objectMapper;

    public MusioPlaylistCapabilityExecutor(
            MusioPlaylistService musioPlaylistService,
            MusicReadTools musicReadTools,
            AgentEventBus eventBus,
            AgentTracePublisher tracePublisher,
            ObjectMapper objectMapper
    ) {
        this.musioPlaylistService = musioPlaylistService;
        this.musicReadTools = musicReadTools;
        this.eventBus = eventBus;
        this.tracePublisher = tracePublisher;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper.findAndRegisterModules();
    }

    public String executeAddSongToMusioPlaylist(AgentLoopState state, Map<String, Object> arguments) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        String runId = state == null ? "" : state.runId();
        String playlistId = text(safeArguments, "playlistId").isBlank() ? "default" : text(safeArguments, "playlistId");
        Map<String, Object> input = musioPlaylistAddInput(playlistId, safeArguments);
        publishToolStart(runId, AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST, input);
        tracePublisher.publishToolRunning(runId, AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST, input);

        Map<String, Object> result;
        try {
            List<PlaylistSongResolution> resolutions = resolveSongs(state, safeArguments);
            List<Song> resolvedSongs = resolutions.stream()
                    .map(PlaylistSongResolution::song)
                    .filter(song -> song != null)
                    .toList();
            if (resolvedSongs.isEmpty()) {
                result = batchFailure(resolutions, "还没能确定要收藏哪一首歌。你可以告诉我歌名，或先让我推荐/搜索出歌曲卡片。");
            } else {
                MusioPlaylist playlist = musioPlaylistService.get(playlistId);
                List<Map<String, Object>> itemResults = new ArrayList<>();
                List<Song> songs = new ArrayList<>();
                int addedCount = 0;
                int alreadyExistsCount = 0;
                int unresolvedCount = 0;
                for (PlaylistSongResolution resolution : resolutions) {
                    Song song = resolution.song();
                    if (song == null) {
                        unresolvedCount++;
                        itemResults.add(unresolvedResult(resolution));
                        continue;
                    }
                    boolean existed = playlistContains(playlistId, song.id());
                    playlist = musioPlaylistService.addSong(playlistId, song);
                    if (existed) {
                        alreadyExistsCount++;
                    } else {
                        addedCount++;
                    }
                    songs.add(song);
                    itemResults.add(successResult(song, existed, resolution));
                }
                result = success(songs, playlist, itemResults, addedCount, alreadyExistsCount, unresolvedCount);
            }
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            result = failure("收藏失败：" + message);
        }

        publishToolResult(runId, AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST, result);
        if (Boolean.TRUE.equals(result.get("success"))) {
            tracePublisher.publishToolDone(runId, AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST, result);
        } else {
            tracePublisher.publishToolError(runId, AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST, String.valueOf(result.getOrDefault("message", "收藏失败。")));
        }
        return writeJson(result);
    }

    private List<PlaylistSongResolution> resolveSongs(AgentLoopState state, Map<String, Object> arguments) {
        List<String> songIds = stringList(arguments.get("songIds"));
        if (!songIds.isEmpty()) {
            List<Song> candidates = recentSongs(state);
            List<PlaylistSongResolution> resolutions = new ArrayList<>();
            for (String songId : songIds) {
                resolutions.add(new PlaylistSongResolution(
                        "songId",
                        songId,
                        resolveBySongId(candidates, songId),
                        "未在本轮歌曲卡片或任务记忆中找到 songId=" + songId
                ));
            }
            return List.copyOf(resolutions);
        }

        List<Integer> songIndexes = integerList(arguments.get("songIndexes"));
        if (!songIndexes.isEmpty()) {
            List<Song> candidates = recentSongs(state);
            List<PlaylistSongResolution> resolutions = new ArrayList<>();
            for (Integer songIndex : songIndexes) {
                resolutions.add(new PlaylistSongResolution(
                        "songIndex",
                        songIndex,
                        resolveBySongIndex(candidates, songIndex),
                        "未在本轮歌曲卡片或任务记忆中找到第 " + songIndex + " 首歌"
                ));
            }
            return List.copyOf(resolutions);
        }

        Song song = resolveSong(state, arguments);
        String referenceType = singleReferenceType(arguments);
        Object reference = singleReference(arguments, referenceType);
        return List.of(new PlaylistSongResolution(
                referenceType,
                reference,
                song,
                "未能解析歌曲引用"
        ));
    }

    private Song resolveSong(AgentLoopState state, Map<String, Object> arguments) {
        String songId = text(arguments, "songId");
        String songTitle = text(arguments, "songTitle");
        String artist = text(arguments, "artist");
        Integer songIndex = integer(arguments, "songIndex");
        Song fromContext = resolveFromCandidates(recentSongs(state), songId, songTitle, artist, songIndex);
        if (fromContext != null) {
            return fromContext;
        }
        String query = String.join(" ", List.of(artist, songTitle).stream()
                .filter(value -> value != null && !value.isBlank())
                .toList()).strip();
        if (query.isBlank() || musicReadTools == null) {
            return null;
        }
        return songsFromResultJson(musicReadTools.searchSongs(query, 1)).stream().findFirst().orElse(null);
    }

    private Song resolveFromCandidates(List<Song> candidates, String songId, String songTitle, String artist, Integer songIndex) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        boolean hasSongId = songId != null && !songId.isBlank();
        boolean hasTitle = songTitle != null && !songTitle.isBlank();
        boolean hasArtist = artist != null && !artist.isBlank();
        boolean hasIndex = songIndex != null;
        if (songId != null && !songId.isBlank()) {
            for (Song song : candidates) {
                if (song != null && songId.equals(song.id())) {
                    return song;
                }
            }
        }
        String normalizedTitle = normalizeSongText(songTitle);
        String normalizedArtist = normalizeSongText(artist);
        if (!normalizedTitle.isBlank()) {
            Song titleAndArtistMatch = candidates.stream()
                    .filter(song -> titleMatches(song, normalizedTitle))
                    .filter(song -> normalizedArtist.isBlank() || artistMatches(song, normalizedArtist))
                    .findFirst()
                    .orElse(null);
            if (titleAndArtistMatch != null) {
                return titleAndArtistMatch;
            }
            return candidates.stream()
                    .filter(song -> titleMatches(song, normalizedTitle))
                    .findFirst()
                    .orElse(null);
        }
        if (songIndex != null && songIndex >= 1 && songIndex <= candidates.size()) {
            return candidates.get(songIndex - 1);
        }
        if (hasSongId || hasTitle || hasArtist || hasIndex) {
            return null;
        }
        return candidates.getFirst();
    }

    private Song resolveBySongId(List<Song> candidates, String songId) {
        if (candidates == null || candidates.isEmpty() || songId == null || songId.isBlank()) {
            return null;
        }
        for (Song song : candidates) {
            if (song != null && songId.equals(song.id())) {
                return song;
            }
        }
        return null;
    }

    private Song resolveBySongIndex(List<Song> candidates, Integer songIndex) {
        if (candidates == null || candidates.isEmpty() || songIndex == null || songIndex < 1 || songIndex > candidates.size()) {
            return null;
        }
        return candidates.get(songIndex - 1);
    }

    private List<Song> recentSongs(AgentLoopState state) {
        Map<String, Song> songsById = new LinkedHashMap<>();
        if (state == null) {
            return List.of();
        }
        if (state.observations() != null) {
            state.observations().forEach(observation -> observation.songs().forEach(song -> addSongCandidate(songsById, song)));
        }
        if (state.recentHistory() != null) {
            for (int index = state.recentHistory().size() - 1; index >= 0; index--) {
                ConversationHistoryMessage message = state.recentHistory().get(index);
                if (message != null && "assistant".equals(message.role())) {
                    message.songs().forEach(song -> addSongCandidate(songsById, song));
                }
            }
        }
        AgentTaskMemory memory = state.taskMemory();
        if (memory != null && memory.lastResultSongs() != null) {
            memory.lastResultSongs().forEach(song -> addSongCandidate(songsById, song));
        }
        if (memory != null) {
            addSongCandidate(songsById, memory.lastTargetSong());
        }
        if (memory != null && memory.pendingLocalPlaylistAdd() != null) {
            memory.pendingLocalPlaylistAdd().songs().forEach(song -> addSongCandidate(songsById, song));
        }
        return List.copyOf(songsById.values());
    }

    private void addSongCandidate(Map<String, Song> songsById, Song song) {
        if (song != null && song.id() != null && !song.id().isBlank()) {
            songsById.putIfAbsent(song.id(), song);
        }
    }

    private boolean playlistContains(String playlistId, String songId) {
        if (songId == null || songId.isBlank()) {
            return false;
        }
        return musioPlaylistService.get(playlistId).items().stream()
                .anyMatch(item -> songId.equals(item.providerTrackId()));
    }

    private Map<String, Object> musioPlaylistAddInput(String playlistId, Map<String, Object> arguments) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("playlistId", playlistId);
        copyTextArgument(arguments, input, "songId");
        List<String> songIds = stringList(arguments.get("songIds"));
        if (!songIds.isEmpty()) {
            input.put("songIds", songIds);
        }
        copyTextArgument(arguments, input, "songTitle");
        copyTextArgument(arguments, input, "artist");
        Integer songIndex = integer(arguments, "songIndex");
        if (songIndex != null) {
            input.put("songIndex", songIndex);
        }
        List<Integer> songIndexes = integerList(arguments.get("songIndexes"));
        if (!songIndexes.isEmpty()) {
            input.put("songIndexes", songIndexes);
        }
        return input;
    }

    private Map<String, Object> success(Song song, MusioPlaylist playlist, boolean existed) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("summary", answerText(song, existed));
        result.put("playlistId", playlist.id());
        result.put("playlistName", playlist.name());
        result.put("itemCount", playlist.items().size());
        result.put("alreadyExists", existed);
        result.put("songId", song.id());
        result.put("songTitle", song.title() == null ? song.id() : song.title());
        result.put("song", song);
        if (song.artists() != null && !song.artists().isEmpty()) {
            result.put("artists", song.artists());
        }
        return result;
    }

    private Map<String, Object> success(
            List<Song> songs,
            MusioPlaylist playlist,
            List<Map<String, Object>> itemResults,
            int addedCount,
            int alreadyExistsCount,
            int unresolvedCount
    ) {
        List<Song> safeSongs = songs == null ? List.of() : songs;
        Song first = safeSongs.isEmpty() ? null : safeSongs.getFirst();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("summary", answerText(safeSongs, addedCount, alreadyExistsCount, unresolvedCount));
        result.put("playlistId", playlist.id());
        result.put("playlistName", playlist.name());
        result.put("itemCount", playlist.items().size());
        result.put("requestedCount", itemResults == null ? safeSongs.size() : itemResults.size());
        result.put("count", safeSongs.size());
        result.put("addedCount", addedCount);
        result.put("alreadyExistsCount", alreadyExistsCount);
        result.put("unresolvedCount", unresolvedCount);
        result.put("alreadyExists", addedCount == 0 && alreadyExistsCount > 0 && unresolvedCount == 0);
        result.put("results", itemResults == null ? List.of() : itemResults);
        result.put("songs", safeSongs);
        if (first != null) {
            result.put("songId", first.id());
            result.put("songTitle", first.title() == null ? first.id() : first.title());
            result.put("song", first);
            if (first.artists() != null && !first.artists().isEmpty()) {
                result.put("artists", first.artists());
            }
        }
        return result;
    }

    private Map<String, Object> successResult(Song song, boolean existed, PlaylistSongResolution resolution) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("referenceType", resolution.referenceType());
        result.put("reference", resolution.reference());
        result.put("alreadyExists", existed);
        result.put("songId", song.id());
        result.put("songTitle", song.title() == null ? song.id() : song.title());
        result.put("song", song);
        if (song.artists() != null && !song.artists().isEmpty()) {
            result.put("artists", song.artists());
        }
        return result;
    }

    private Map<String, Object> unresolvedResult(PlaylistSongResolution resolution) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("referenceType", resolution.referenceType());
        result.put("reference", resolution.reference());
        result.put("message", resolution.failureMessage());
        return result;
    }

    private Map<String, Object> batchFailure(List<PlaylistSongResolution> resolutions, String message) {
        List<Map<String, Object>> results = resolutions == null
                ? List.of()
                : resolutions.stream()
                .map(this::unresolvedResult)
                .toList();
        Map<String, Object> result = failure(message);
        result.put("requestedCount", resolutions == null || resolutions.isEmpty() ? 1 : resolutions.size());
        result.put("count", 0);
        result.put("addedCount", 0);
        result.put("alreadyExistsCount", 0);
        result.put("unresolvedCount", results.size());
        result.put("results", results);
        result.put("songs", List.of());
        return result;
    }

    private Map<String, Object> failure(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("summary", message == null || message.isBlank() ? "收藏失败。" : message);
        result.put("message", message == null || message.isBlank() ? "收藏失败。" : message);
        return result;
    }

    private String answerText(Song song, boolean existed) {
        String title = song.title() == null || song.title().isBlank() ? song.id() : song.title();
        String artists = song.artists() == null || song.artists().isEmpty() ? "" : " - " + String.join(" / ", song.artists());
        if (existed) {
            return "这首歌已经在 Musio 歌单里了：" + title + artists + "。";
        }
        return "已帮你收藏到 Musio 歌单：" + title + artists + "。";
    }

    private String answerText(List<Song> songs, int addedCount, int alreadyExistsCount, int unresolvedCount) {
        List<Song> safeSongs = songs == null ? List.of() : songs;
        if (safeSongs.size() == 1 && unresolvedCount == 0) {
            return answerText(safeSongs.getFirst(), addedCount == 0 && alreadyExistsCount > 0);
        }
        String refs = songRefs(safeSongs);
        String prefix;
        if (addedCount > 0 && alreadyExistsCount > 0) {
            prefix = "已帮你收藏到 Musio 歌单 " + addedCount + " 首，另有 " + alreadyExistsCount + " 首原本已在歌单里";
        } else if (addedCount > 0) {
            prefix = "已帮你收藏到 Musio 歌单 " + addedCount + " 首";
        } else {
            prefix = "这些歌已经在 Musio 歌单里了";
        }
        String suffix = unresolvedCount > 0 ? "，还有 " + unresolvedCount + " 首暂时没解析出来" : "";
        return refs.isBlank() ? prefix + suffix + "。" : prefix + "：" + refs + suffix + "。";
    }

    private String songRefs(List<Song> songs) {
        if (songs == null || songs.isEmpty()) {
            return "";
        }
        return songs.stream()
                .limit(6)
                .map(song -> {
                    String title = song.title() == null || song.title().isBlank() ? song.id() : song.title();
                    String artists = song.artists() == null || song.artists().isEmpty() ? "" : " - " + String.join(" / ", song.artists());
                    return title + artists;
                })
                .reduce((left, right) -> left + "；" + right)
                .orElse("");
    }

    private void publishToolStart(String runId, String toolName, Map<String, Object> input) {
        eventBus.publish(runId, AgentEvent.of("tool_start", Map.of(
                "runId", runId,
                "tool", toolName,
                "input", input
        )));
    }

    private void publishToolResult(String runId, String toolName, Map<String, Object> result) {
        eventBus.publish(runId, AgentEvent.of("tool_result", Map.of(
                "runId", runId,
                "tool", toolName,
                "summary", result.getOrDefault("summary", "Musio 歌单操作已完成。")
        )));
    }

    private List<Song> songsFromResultJson(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode songsNode = objectMapper.readTree(resultJson).path("songs");
            if (!songsNode.isArray() || songsNode.isEmpty()) {
                return List.of();
            }
            java.util.ArrayList<Song> songs = new java.util.ArrayList<>();
            for (JsonNode songNode : songsNode) {
                try {
                    songs.add(objectMapper.treeToValue(songNode, Song.class));
                } catch (Exception ignored) {
                    // Ignore malformed song cards.
                }
            }
            return songs;
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean titleMatches(Song song, String normalizedTitle) {
        if (song == null || normalizedTitle == null || normalizedTitle.isBlank()) {
            return false;
        }
        String title = normalizeSongText(song.title());
        return !title.isBlank() && (title.equals(normalizedTitle) || title.contains(normalizedTitle) || normalizedTitle.contains(title));
    }

    private boolean artistMatches(Song song, String normalizedArtist) {
        if (song == null || song.artists() == null || normalizedArtist == null || normalizedArtist.isBlank()) {
            return false;
        }
        return song.artists().stream()
                .map(this::normalizeSongText)
                .anyMatch(artist -> !artist.isBlank()
                        && (artist.equals(normalizedArtist) || artist.contains(normalizedArtist) || normalizedArtist.contains(artist)));
    }

    private String normalizeSongText(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase()
                .replaceAll("[《》<>\\[\\]【】()（）\\s·・,，。.!！?？:：;；'\"“”‘’-]+", "")
                .strip();
    }

    private void copyTextArgument(Map<String, Object> source, Map<String, Object> target, String key) {
        String value = text(source, key);
        if (!value.isBlank()) {
            target.put(key, value);
        }
    }

    private String singleReferenceType(Map<String, Object> arguments) {
        if (!text(arguments, "songId").isBlank()) {
            return "songId";
        }
        if (!text(arguments, "songTitle").isBlank()) {
            return "songTitle";
        }
        if (integer(arguments, "songIndex") != null) {
            return "songIndex";
        }
        return "unknown";
    }

    private Object singleReference(Map<String, Object> arguments, String referenceType) {
        return switch (referenceType) {
            case "songId" -> text(arguments, "songId");
            case "songTitle" -> text(arguments, "songTitle");
            case "songIndex" -> integer(arguments, "songIndex");
            default -> "";
        };
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value instanceof String text ? text.strip() : "";
    }

    private Integer integer(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.strip());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (Object item : list) {
            if (item instanceof String text && !text.isBlank()) {
                values.add(text.strip());
            }
        }
        return values.stream().limit(20).toList();
    }

    private List<Integer> integerList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        Set<Integer> values = new LinkedHashSet<>();
        for (Object item : list) {
            Integer number = integerValue(item);
            if (number != null && number > 0) {
                values.add(number);
            }
        }
        return values.stream().limit(20).toList();
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.strip());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private record PlaylistSongResolution(
            String referenceType,
            Object reference,
            Song song,
            String failureMessage
    ) {
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"success\":false,\"message\":\"local_write_result_json_failed\"}";
        }
    }
}
