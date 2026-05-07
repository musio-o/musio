package com.musio.agent;

import com.musio.tools.MusicReadTools;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class AgentToolExecutor {
    private final MusicReadTools musicReadTools;

    public AgentToolExecutor(MusicReadTools musicReadTools) {
        this.musicReadTools = musicReadTools;
    }

    public List<AgentToolExecution> execute(AgentToolPlan plan) {
        if (plan == null || !plan.hasCalls()) {
            return List.of();
        }
        List<AgentToolExecution> executions = new ArrayList<>();
        for (AgentToolCall call : plan.toolCalls()) {
            execute(call).ifPresent(executions::add);
        }
        return executions;
    }

    public Optional<String> executeTool(String toolName, Map<String, Object> arguments) {
        return execute(new AgentToolCall(toolName, arguments))
                .map(AgentToolExecution::resultJson);
    }

    private Optional<AgentToolExecution> execute(AgentToolCall call) {
        if (call == null || call.toolName() == null || call.toolName().isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> arguments = call.arguments() == null ? Map.of() : call.arguments();
        String result = switch (call.toolName()) {
            case "search_songs" -> searchSongs(arguments);
            case "get_user_music_profile" -> musicReadTools.getUserMusicProfile();
            case "get_song_detail" -> musicReadTools.getSongDetail(text(arguments, "songId"));
            case "get_lyrics" -> musicReadTools.getLyrics(text(arguments, "songId"));
            case "get_hot_comments" -> musicReadTools.getHotComments(text(arguments, "songId"), integer(arguments, "limit"));
            case "get_user_playlists" -> musicReadTools.getUserPlaylists(integer(arguments, "limit"));
            case "get_playlist_songs" -> musicReadTools.getPlaylistSongs(text(arguments, "playlistId"), integer(arguments, "limit"));
            default -> null;
        };
        if (result == null) {
            return Optional.empty();
        }
        return Optional.of(new AgentToolExecution(call.toolName(), arguments, result));
    }

    private String searchSongs(Map<String, Object> arguments) {
        List<String> excludedTitles = stringList(arguments.get("excludedTitles"));
        if (!excludedTitles.isEmpty()) {
            return musicReadTools.searchSongsExcludingTitles(text(arguments, "keyword"), integer(arguments, "limit"), excludedTitles);
        }
        return musicReadTools.searchSongs(text(arguments, "keyword"), integer(arguments, "limit"));
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value instanceof String text ? text.strip() : "";
    }

    private Integer integer(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
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
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(item -> !item.isBlank())
                .map(String::strip)
                .toList();
    }
}
