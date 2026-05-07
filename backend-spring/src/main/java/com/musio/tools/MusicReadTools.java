package com.musio.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.AgentRunContext;
import com.musio.agent.trace.AgentTracePublisher;
import com.musio.events.AgentEventBus;
import com.musio.model.AgentEvent;
import com.musio.model.Comment;
import com.musio.model.Lyrics;
import com.musio.model.MusicProfileMemory;
import com.musio.model.Playlist;
import com.musio.model.Song;
import com.musio.model.SongDetail;
import com.musio.memory.AgentTaskMemoryService;
import com.musio.memory.MusicProfileService;
import com.musio.providers.MusicProviderGateway;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class MusicReadTools {
    private final MusicProviderGateway providerGateway;
    private final MusicProfileService musicProfileService;
    private final AgentEventBus eventBus;
    private final ObjectMapper objectMapper;
    private final AgentTracePublisher tracePublisher;
    private final AgentTaskMemoryService taskMemoryService;

    public MusicReadTools(
            MusicProviderGateway providerGateway,
            MusicProfileService musicProfileService,
            AgentEventBus eventBus,
            ObjectMapper objectMapper,
            AgentTracePublisher tracePublisher,
            AgentTaskMemoryService taskMemoryService
    ) {
        this.providerGateway = providerGateway;
        this.musicProfileService = musicProfileService;
        this.eventBus = eventBus;
        this.objectMapper = objectMapper.findAndRegisterModules();
        this.tracePublisher = tracePublisher;
        this.taskMemoryService = taskMemoryService;
    }

    @Tool(name = "search_songs", description = "Search QQ Music songs by keyword. Use this when the user asks to find songs, recommend tracks, or discover music.")
    public String searchSongs(
            @ToolParam(description = "Search keyword, such as a song title, artist, mood, scene, or genre") String keyword,
            @ToolParam(description = "Maximum number of songs to return. Default 5, maximum 20") Integer limit) {
        int actualLimit = clamp(limit, 5, 1, 20);
        return runTool("search_songs", Map.of("keyword", keyword, "limit", actualLimit), () -> {
            List<Song> songs = providerGateway.defaultProvider().searchSongs(keyword, actualLimit);
            publish("song_cards", Map.of("songs", songs));
            return Map.of("success", true, "count", songs.size(), "songs", songs);
        });
    }

    public String searchSongsExcludingTitles(String keyword, Integer limit, List<String> excludedTitles) {
        int actualLimit = clamp(limit, 5, 1, 20);
        List<String> normalizedExclusions = normalizedTitles(excludedTitles);
        if (normalizedExclusions.isEmpty()) {
            return searchSongs(keyword, actualLimit);
        }
        int searchLimit = Math.max(actualLimit, Math.min(20, actualLimit + normalizedExclusions.size() + 8));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("keyword", keyword);
        input.put("limit", actualLimit);
        input.put("excludedTitles", excludedTitles);
        return runTool("search_songs", input, () -> {
            List<Song> songs = providerGateway.defaultProvider().searchSongs(keyword, searchLimit).stream()
                    .filter(song -> !isExcludedTitle(song, normalizedExclusions))
                    .limit(actualLimit)
                    .toList();
            publish("song_cards", Map.of("songs", songs));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("count", songs.size());
            result.put("excludedTitles", excludedTitles);
            result.put("songs", songs);
            return result;
        });
    }

    @Tool(name = "get_user_music_profile", description = "Read the current user's summarized Musio music profile memory. Use this before personalized recommendations based on the user's taste.")
    public String getUserMusicProfile() {
        return runTool("get_user_music_profile", Map.of(), () -> musicProfileService.readOrCreate()
                .map(this::musicProfileResult)
                .orElseGet(() -> Map.of(
                        "success", false,
                        "message", "音乐画像记忆还没有生成。请先让用户登录 QQ 音乐并生成音乐基因。"
                )));
    }

    @Tool(name = "get_song_detail", description = "Get details for one QQ Music song by provider-prefixed song id.")
    public String getSongDetail(
            @ToolParam(description = "Song id, preferably provider-prefixed, for example qqmusic:003OUlho2HcRHC") String songId) {
        return runTool("get_song_detail", Map.of("songId", songId), () -> {
            SongDetail detail = providerGateway.defaultProvider().getSongDetail(songId);
            return Map.of("success", true, "song", detail);
        });
    }

    @Tool(name = "get_lyrics", description = "Get lyrics for one QQ Music song by provider-prefixed song id.")
    public String getLyrics(
            @ToolParam(description = "Song id, preferably provider-prefixed, for example qqmusic:003OUlho2HcRHC") String songId) {
        return runTool("get_lyrics", Map.of("songId", songId), () -> {
            Lyrics lyrics = providerGateway.defaultProvider().getLyrics(songId);
            return Map.of("success", true, "lyrics", lyrics);
        });
    }

    @Tool(name = "get_hot_comments", description = "Get hot comments for one QQ Music song. Use this for comment analysis or listener sentiment.")
    public String getHotComments(
            @ToolParam(description = "Song id, preferably provider-prefixed, for example qqmusic:003OUlho2HcRHC") String songId,
            @ToolParam(description = "Maximum number of comments to return. Default 10, maximum 30") Integer limit) {
        int actualLimit = clamp(limit, 10, 1, 30);
        return runTool("get_hot_comments", Map.of("songId", songId, "limit", actualLimit), () -> {
            List<Comment> comments = providerGateway.defaultProvider().getComments(songId).stream()
                    .limit(actualLimit)
                    .toList();
            return Map.of("success", true, "count", comments.size(), "comments", comments);
        });
    }

    @Tool(name = "get_user_playlists", description = "Get the current QQ Music user's playlists. Use this only after the user has logged in.")
    public String getUserPlaylists(
            @ToolParam(description = "Maximum number of playlists to return. Default 20, maximum 50") Integer limit) {
        int actualLimit = clamp(limit, 20, 1, 50);
        return runTool("get_user_playlists", Map.of("limit", actualLimit), () -> {
            List<Playlist> playlists = providerGateway.defaultProvider().getPlaylists("local").stream()
                    .limit(actualLimit)
                    .toList();
            return Map.of("success", true, "count", playlists.size(), "playlists", playlists);
        });
    }

    @Tool(name = "get_playlist_songs", description = "Get songs in one QQ Music playlist by playlist id.")
    public String getPlaylistSongs(
            @ToolParam(description = "Playlist id, preferably provider-prefixed if available") String playlistId,
            @ToolParam(description = "Maximum number of songs to return. Default 20, maximum 50") Integer limit) {
        int actualLimit = clamp(limit, 20, 1, 50);
        return runTool("get_playlist_songs", Map.of("playlistId", playlistId, "limit", actualLimit), () -> {
            List<Song> songs = providerGateway.defaultProvider().getPlaylistSongs(playlistId).stream()
                    .limit(actualLimit)
                    .toList();
            publish("song_cards", Map.of("songs", songs));
            return Map.of("success", true, "count", songs.size(), "songs", songs);
        });
    }

    private String runTool(String toolName, Map<String, Object> input, Supplier<Map<String, Object>> action) {
        publish("tool_start", Map.of("tool", toolName, "input", input));
        publishToolTrace(toolName, "running", input, null);
        try {
            Map<String, Object> result = action.get();
            publish("tool_result", Map.of(
                    "tool", toolName,
                    "summary", summary(toolName, result)
            ));
            publishToolTrace(toolName, "done", null, result);
            recordToolSuccess(result);
            return writeJson(result);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Map<String, Object> error = Map.of("success", false, "tool", toolName, "message", message);
            publish("tool_result", Map.of("tool", toolName, "summary", "工具执行失败: " + message));
            publishToolTrace(toolName, "error", null, error);
            recordToolFailure(toolName, message);
            return writeJson(error);
        }
    }

    private void recordToolSuccess(Map<String, Object> result) {
        if (taskMemoryService == null || !result.containsKey("songs")) {
            return;
        }
        Object songsValue = result.get("songs");
        if (!(songsValue instanceof List<?> rawSongs)) {
            return;
        }
        List<Song> songs = rawSongs.stream()
                .filter(Song.class::isInstance)
                .map(Song.class::cast)
                .toList();
        AgentRunContext.userId().ifPresent(userId -> taskMemoryService.recordResultSongs(userId, songs));
    }

    private void recordToolFailure(String toolName, String message) {
        if (taskMemoryService == null) {
            return;
        }
        AgentRunContext.userId().ifPresent(userId -> taskMemoryService.recordToolFailure(userId, toolName, message));
    }

    private void publishToolTrace(String toolName, String status, Map<String, Object> input, Map<String, Object> result) {
        if (!AgentRunContext.traceEnabled()) {
            return;
        }
        AgentRunContext.runId().ifPresent(runId -> {
            if ("running".equals(status)) {
                tracePublisher.publishToolRunning(runId, toolName, input == null ? Map.of() : input);
            } else if ("done".equals(status)) {
                tracePublisher.publishToolDone(runId, toolName, result == null ? Map.of() : result);
            } else if ("error".equals(status)) {
                Object message = result == null ? null : result.get("message");
                tracePublisher.publishToolError(runId, toolName, message instanceof String ? (String) message : "未知错误");
            }
        });
    }

    private void publish(String type, Map<String, Object> data) {
        AgentRunContext.runId().ifPresent(runId -> {
            Map<String, Object> eventData = new LinkedHashMap<>();
            eventData.put("runId", runId);
            eventData.putAll(data);
            eventBus.publish(runId, AgentEvent.of(type, eventData));
        });
    }

    private String summary(String toolName, Map<String, Object> result) {
        if ("get_user_music_profile".equals(toolName)) {
            return Boolean.TRUE.equals(result.get("success")) ? "已读取音乐画像记忆" : "音乐画像记忆不可用";
        }
        Object count = result.get("count");
        if (count != null) {
            return toolName + " returned " + count + " item(s)";
        }
        return toolName + " completed";
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"message\":\"Failed to serialize tool result.\"}";
        }
    }

    private int clamp(Integer value, int defaultValue, int min, int max) {
        int actual = value == null ? defaultValue : value;
        return Math.max(min, Math.min(max, actual));
    }

    private List<String> normalizedTitles(List<String> titles) {
        if (titles == null || titles.isEmpty()) {
            return List.of();
        }
        return titles.stream()
                .map(this::normalizeTitle)
                .filter(title -> !title.isBlank())
                .distinct()
                .toList();
    }

    private boolean isExcludedTitle(Song song, List<String> normalizedExclusions) {
        if (song == null || song.title() == null) {
            return false;
        }
        String title = normalizeTitle(song.title());
        return normalizedExclusions.stream().anyMatch(excluded ->
                title.equals(excluded) || title.contains(excluded) || excluded.contains(title)
        );
    }

    private String normalizeTitle(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[《》<>\\[\\]【】()（）\\s]+", "")
                .strip();
    }

    private Map<String, Object> musicProfileResult(MusicProfileMemory profile) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("provider", profile.provider());
        result.put("userId", profile.userId());
        result.put("generatedAt", profile.generatedAt());
        result.put("sourceGeneGeneratedAt", profile.sourceGeneGeneratedAt());
        result.put("summary", profile.summary());
        result.put("strongPreferences", profile.strongPreferences());
        result.put("favoriteArtists", profile.favoriteArtists());
        result.put("favoriteAlbums", profile.favoriteAlbums());
        result.put("likedSongExamples", profile.likedSongExamples());
        result.put("recommendationHints", profile.recommendationHints());
        result.put("avoid", profile.avoid());
        result.put("sourceStats", profile.sourceStats());
        return result;
    }
}
