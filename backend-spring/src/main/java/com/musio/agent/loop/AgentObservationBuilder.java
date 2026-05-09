package com.musio.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.model.ProviderType;
import com.musio.model.Song;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentObservationBuilder {
    private final ObjectMapper objectMapper;

    public AgentObservationBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper.findAndRegisterModules();
    }

    public AgentObservation build(String stepId, String toolName, Map<String, Object> arguments, String resultJson) {
        try {
            JsonNode root = objectMapper.readTree(resultJson == null ? "{}" : resultJson);
            boolean success = root.path("success").asBoolean(false);
            AgentObservationStatus status = success ? AgentObservationStatus.SUCCESS : AgentObservationStatus.FAILURE;
            List<Song> songs = songsFrom(root);
            return new AgentObservation(
                    stepId,
                    toolName,
                    arguments,
                    status,
                    resultJson,
                    plannerSummary(toolName, root, songs, status),
                    songs
            );
        } catch (Exception e) {
            return new AgentObservation(
                    stepId,
                    toolName,
                    arguments,
                    AgentObservationStatus.FAILURE,
                    resultJson,
                    toolName + " 返回了无法解析的 JSON 结果",
                    List.of()
            );
        }
    }

    public AgentLoopEvidence evidence(List<AgentObservation> observations, String completedTaskType) {
        List<AgentObservation> safeObservations = observations == null ? List.of() : List.copyOf(observations);
        List<Song> songs = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        Song targetSong = null;
        for (AgentObservation observation : safeObservations) {
            songs.addAll(observation.songs());
            if (targetSong == null && !observation.songs().isEmpty()) {
                targetSong = observation.songs().getFirst();
            }
            if (!observation.plannerSummary().isBlank()) {
                summaries.add(observation.plannerSummary());
            }
        }
        return new AgentLoopEvidence(safeObservations, songs, completedTaskType, targetSong, summaries);
    }

    private String plannerSummary(String toolName, JsonNode root, List<Song> songs, AgentObservationStatus status) {
        if (status == AgentObservationStatus.FAILURE) {
            String message = root.path("message").isTextual() ? root.path("message").asText() : "未提供错误原因";
            return toolName + " 失败：" + message;
        }
        if ("add_song_to_musio_playlist".equals(toolName)) {
            String summary = root.path("summary").asText("");
            return summary.isBlank() ? toolName + " 成功，已写入 Musio 本地歌单" : summary;
        }
        if ("recommend_songs".equals(toolName)) {
            JsonNode unresolved = root.path("unresolved");
            String suffix = unresolved.isArray() && !unresolved.isEmpty()
                    ? "，未精确匹配 " + unresolved.size() + " 首"
                    : "";
            String coverage = recommendationCoverage(root, songs);
            if (!songs.isEmpty()) {
                return toolName + " 成功，" + coverage + "，已生成并精确匹配 " + songs.size() + " 首推荐歌曲：" + songRefs(songs) + suffix;
            }
            String summary = root.path("summary").asText("");
            return summary.isBlank() ? toolName + " 未匹配到可用歌曲" : summary;
        }
        if (!songs.isEmpty()) {
            return toolName + " 成功，歌曲 " + songs.size() + " 首：" + songRefs(songs);
        }
        JsonNode comments = root.path("comments");
        JsonNode commentResults = root.path("commentResults");
        if (commentResults.isArray()) {
            return toolName + " 成功，歌曲 " + commentResults.size() + " 首，评论 " + comments.size() + " 条";
        }
        if (comments.isArray()) {
            return toolName + " 成功，评论 " + comments.size() + " 条";
        }
        JsonNode lyrics = root.path("lyrics");
        JsonNode lyricsResults = root.path("lyricsResults");
        if (lyricsResults.isArray()) {
            return toolName + " 成功，已读取歌词 " + successfulResultCount(lyricsResults) + "/" + lyricsResults.size() + " 首";
        }
        if (lyrics.isObject()) {
            String songId = lyrics.path("songId").asText("");
            return songId.isBlank() ? toolName + " 成功，已读取歌词" : toolName + " 成功，已读取歌词 songId=" + songId;
        }
        JsonNode playlists = root.path("playlists");
        if (playlists.isArray()) {
            return toolName + " 成功，歌单 " + playlists.size() + " 个：" + playlistRefs(playlists);
        }
        JsonNode count = root.path("count");
        if (count.isNumber()) {
            return toolName + " 成功，返回 " + count.asInt() + " 条结果";
        }
        return toolName + " 成功";
    }

    private List<Song> songsFrom(JsonNode root) {
        List<Song> songs = new ArrayList<>();
        JsonNode songsNode = root.path("songs");
        if (songsNode.isArray()) {
            for (JsonNode songNode : songsNode) {
                readSong(songNode, songs);
            }
        }
        JsonNode songNode = root.path("song");
        if (songNode.isObject()) {
            readSong(songNode, songs);
        }
        Map<String, Song> songsById = new LinkedHashMap<>();
        for (Song song : songs) {
            if (song != null && song.id() != null && !song.id().isBlank()) {
                songsById.putIfAbsent(song.id(), song);
            }
        }
        return List.copyOf(songsById.values());
    }

    private void readSong(JsonNode songNode, List<Song> songs) {
        try {
            songs.add(objectMapper.treeToValue(songNode, Song.class));
        } catch (Exception ignored) {
            Song song = lenientSong(songNode);
            if (song != null) {
                songs.add(song);
            }
        }
    }

    private Song lenientSong(JsonNode songNode) {
        if (songNode == null || !songNode.isObject()) {
            return null;
        }
        String id = songNode.path("id").asText(songNode.path("songId").asText(""));
        if (id.isBlank()) {
            return null;
        }
        ProviderType provider = ProviderType.QQMUSIC;
        String providerText = songNode.path("provider").asText("");
        if (!providerText.isBlank()) {
            try {
                provider = ProviderType.valueOf(providerText);
            } catch (IllegalArgumentException ignored) {
                provider = ProviderType.QQMUSIC;
            }
        }
        List<String> artists = new ArrayList<>();
        JsonNode artistsNode = songNode.path("artists");
        if (artistsNode.isArray()) {
            for (JsonNode artist : artistsNode) {
                if (artist.isTextual() && !artist.asText().isBlank()) {
                    artists.add(artist.asText().strip());
                }
            }
        }
        if (artists.isEmpty() && songNode.path("artist").isTextual() && !songNode.path("artist").asText().isBlank()) {
            artists.add(songNode.path("artist").asText().strip());
        }
        Integer durationSeconds = songNode.path("durationSeconds").isNumber() ? songNode.path("durationSeconds").asInt() : null;
        return new Song(
                id,
                provider,
                songNode.path("title").asText(""),
                artists,
                songNode.path("album").asText(null),
                durationSeconds,
                songNode.path("artworkUrl").asText(null)
        );
    }

    private String recommendationCoverage(JsonNode root, List<Song> songs) {
        int requestedTotal = root.path("requestedTotal").asInt(root.path("requestedCount").asInt(0));
        int resolvedTotal = root.path("resolvedTotal").asInt(root.path("count").asInt(songs == null ? 0 : songs.size()));
        String total = requestedTotal > 0 ? "覆盖 " + resolvedTotal + "/" + requestedTotal : "覆盖 " + resolvedTotal;
        JsonNode slotResults = root.path("slotResults");
        if (!slotResults.isArray() || slotResults.isEmpty()) {
            return total;
        }
        List<String> slots = new ArrayList<>();
        for (JsonNode slotResult : slotResults) {
            String slotId = slotResult.path("slotId").asText("");
            if (slotId.isBlank()) {
                continue;
            }
            slots.add(slotId + " " + slotResult.path("resolved").asInt(0) + "/" + slotResult.path("requested").asInt(0));
        }
        return slots.isEmpty() ? total : total + "，slots " + String.join("，", slots);
    }

    private String songRefs(List<Song> songs) {
        List<String> refs = new ArrayList<>();
        for (Song song : songs.stream().limit(5).toList()) {
            String title = song.title() == null || song.title().isBlank() ? "未知歌曲" : song.title();
            String artists = song.artists() == null || song.artists().isEmpty() ? "" : " - " + String.join("/", song.artists());
            refs.add(title + artists + " id=" + song.id());
        }
        return String.join("；", refs);
    }

    private String playlistRefs(JsonNode playlists) {
        List<String> refs = new ArrayList<>();
        for (JsonNode playlist : playlists) {
            String id = playlist.path("id").asText("");
            String name = playlist.path("name").asText("");
            if (!id.isBlank()) {
                refs.add(name.isBlank() ? id : name + " id=" + id);
            }
            if (refs.size() >= 5) {
                break;
            }
        }
        return String.join("；", refs);
    }

    private int successfulResultCount(JsonNode results) {
        if (!results.isArray()) {
            return 0;
        }
        int count = 0;
        for (JsonNode result : results) {
            if (result.path("success").asBoolean(false)) {
                count++;
            }
        }
        return count;
    }
}
