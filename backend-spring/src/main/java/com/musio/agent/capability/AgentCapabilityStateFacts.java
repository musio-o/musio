package com.musio.agent.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.loop.AgentLoopState;
import com.musio.agent.loop.AgentObservation;
import com.musio.agent.loop.AgentObservationStatus;
import com.musio.memory.context.MemoryEvidence;
import com.musio.memory.context.MemoryType;
import com.musio.model.AgentTaskMemory;
import com.musio.model.Song;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AgentCapabilityStateFacts {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AgentCapabilityStateFacts() {
    }

    static Set<String> knownSongIds(AgentLoopState state) {
        Set<String> ids = new LinkedHashSet<>();
        if (state == null) {
            return ids;
        }
        String userMessage = state.userMessage() == null ? "" : state.userMessage();
        AgentTaskMemory memory = state.taskMemory();
        if (memory != null && memory.lastResultSongs() != null) {
            for (Song song : memory.lastResultSongs()) {
                addSongId(ids, song);
            }
        }
        if (memory != null) {
            addSongId(ids, memory.lastTargetSong());
        }
        for (AgentObservation observation : state.observations()) {
            for (Song song : observation.songs()) {
                addSongId(ids, song);
            }
            ids.addAll(songIdsFromResultJson(observation.resultJson()));
        }
        if (state.memoryContext() != null) {
            for (MemoryEvidence evidence : state.memoryContext().evidence()) {
                if (evidence == null || evidence.type() != MemoryType.CURRENT_STATE) {
                    continue;
                }
                addTextId(ids, evidence.sourceId());
                ids.addAll(providerIdsInText(evidence.text()));
            }
        }
        ids.addAll(providerIdsInText(userMessage));
        return ids;
    }

    static Set<String> searchedKeywords(AgentLoopState state) {
        Set<String> keywords = new LinkedHashSet<>();
        if (state == null || state.observations() == null) {
            return keywords;
        }
        for (AgentObservation observation : state.observations()) {
            if (observation.status() != AgentObservationStatus.SUCCESS || !"search_songs".equals(observation.toolName())) {
                continue;
            }
            String keyword = normalizedKeyword(text(observation.arguments(), "keyword"));
            if (!keyword.isBlank()) {
                keywords.add(keyword);
            }
        }
        return keywords;
    }

    static Set<String> knownPlaylistIds(AgentLoopState state) {
        Set<String> ids = new LinkedHashSet<>();
        if (state == null) {
            return ids;
        }
        ids.addAll(providerIdsInText(state.userMessage() == null ? "" : state.userMessage()));
        for (AgentObservation observation : state.observations()) {
            ids.addAll(playlistIdsFromResultJson(observation.resultJson()));
        }
        return ids;
    }

    static Set<String> successfulReadSongIds(AgentLoopState state, String toolName) {
        Set<String> ids = new LinkedHashSet<>();
        if (state == null || state.observations() == null || toolName == null || toolName.isBlank()) {
            return ids;
        }
        for (AgentObservation observation : state.observations()) {
            if (observation == null || observation.status() != AgentObservationStatus.SUCCESS || !toolName.equals(observation.toolName())) {
                continue;
            }
            Set<String> resultIds = successfulReadSongIdsFromResultJson(toolName, observation.resultJson());
            if (resultIds.isEmpty()) {
                ids.addAll(songIds(observation.arguments()));
            } else {
                ids.addAll(resultIds);
            }
        }
        return ids;
    }

    static List<String> songIds(Map<String, Object> arguments) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        String songId = text(arguments, "songId");
        if (!songId.isBlank()) {
            ids.add(songId);
        }
        Object songIds = arguments == null ? null : arguments.get("songIds");
        if (songIds instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String id && !id.isBlank()) {
                    ids.add(id.strip());
                }
            }
        }
        return ids.stream().limit(10).toList();
    }

    static String text(java.util.Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value instanceof String text ? text.strip() : "";
    }

    static String normalizedKeyword(String value) {
        return value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
    }

    private static Set<String> providerIdsInText(String value) {
        Set<String> ids = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return ids;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("qqmusic:[A-Za-z0-9:_-]+").matcher(value);
        while (matcher.find()) {
            ids.add(matcher.group());
        }
        return ids;
    }

    private static Set<String> songIdsFromResultJson(String resultJson) {
        Set<String> ids = new LinkedHashSet<>();
        try {
            JsonNode root = OBJECT_MAPPER.readTree(resultJson == null ? "{}" : resultJson);
            JsonNode songs = root.path("songs");
            if (songs.isArray()) {
                for (JsonNode song : songs) {
                    addTextId(ids, song.path("id").asText(""));
                }
            }
            JsonNode song = root.path("song");
            if (song.isObject()) {
                addTextId(ids, song.path("id").asText(""));
            }
            JsonNode lyrics = root.path("lyrics");
            if (lyrics.isObject()) {
                addTextId(ids, lyrics.path("songId").asText(""));
            }
            JsonNode lyricsResults = root.path("lyricsResults");
            if (lyricsResults.isArray()) {
                for (JsonNode item : lyricsResults) {
                    addTextId(ids, item.path("songId").asText(""));
                    JsonNode itemLyrics = item.path("lyrics");
                    if (itemLyrics.isObject()) {
                        addTextId(ids, itemLyrics.path("songId").asText(""));
                    }
                }
            }
            JsonNode comments = root.path("comments");
            if (comments.isArray()) {
                for (JsonNode comment : comments) {
                    addTextId(ids, comment.path("songId").asText(""));
                }
            }
            JsonNode commentResults = root.path("commentResults");
            if (commentResults.isArray()) {
                for (JsonNode item : commentResults) {
                    addTextId(ids, item.path("songId").asText(""));
                    JsonNode itemComments = item.path("comments");
                    if (itemComments.isArray()) {
                        for (JsonNode comment : itemComments) {
                            addTextId(ids, comment.path("songId").asText(""));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            return Set.of();
        }
        return ids;
    }

    private static Set<String> successfulReadSongIdsFromResultJson(String toolName, String resultJson) {
        Set<String> ids = new LinkedHashSet<>();
        try {
            JsonNode root = OBJECT_MAPPER.readTree(resultJson == null ? "{}" : resultJson);
            if ("get_lyrics".equals(toolName)) {
                JsonNode lyrics = root.path("lyrics");
                if (lyrics.isObject()) {
                    addTextId(ids, lyrics.path("songId").asText(""));
                }
                JsonNode lyricsResults = root.path("lyricsResults");
                if (lyricsResults.isArray()) {
                    for (JsonNode item : lyricsResults) {
                        if (item.path("success").asBoolean(false)) {
                            addTextId(ids, item.path("songId").asText(""));
                        }
                    }
                }
            }
            if ("get_hot_comments".equals(toolName)) {
                JsonNode comments = root.path("comments");
                if (comments.isArray()) {
                    for (JsonNode comment : comments) {
                        addTextId(ids, comment.path("songId").asText(""));
                    }
                }
                JsonNode commentResults = root.path("commentResults");
                if (commentResults.isArray()) {
                    for (JsonNode item : commentResults) {
                        if (item.path("success").asBoolean(false)) {
                            addTextId(ids, item.path("songId").asText(""));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            return Set.of();
        }
        return ids;
    }

    private static Set<String> playlistIdsFromResultJson(String resultJson) {
        Set<String> ids = new LinkedHashSet<>();
        try {
            JsonNode root = OBJECT_MAPPER.readTree(resultJson == null ? "{}" : resultJson);
            JsonNode playlists = root.path("playlists");
            if (playlists.isArray()) {
                for (JsonNode playlist : playlists) {
                    addTextId(ids, playlist.path("id").asText(""));
                }
            }
        } catch (Exception ignored) {
            return Set.of();
        }
        return ids;
    }

    private static void addSongId(Set<String> ids, Song song) {
        if (song != null) {
            addTextId(ids, song.id());
        }
    }

    private static void addTextId(Set<String> ids, String value) {
        if (value != null && value.startsWith("qqmusic:")) {
            ids.add(value);
        }
    }
}
