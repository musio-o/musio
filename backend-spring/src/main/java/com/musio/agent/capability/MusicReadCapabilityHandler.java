package com.musio.agent.capability;

import com.musio.agent.loop.AgentLoopState;
import com.musio.tools.MusicReadTools;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@Order(0)
public class MusicReadCapabilityHandler implements AgentCapabilityHandler {
    private final MusicReadTools musicReadTools;
    private final Map<String, ReadCapability> capabilities;

    public MusicReadCapabilityHandler(MusicReadTools musicReadTools) {
        this.musicReadTools = musicReadTools;
        this.capabilities = readCapabilities();
    }

    @Override
    public List<AgentCapability> capabilities() {
        return capabilities.values().stream()
                .map(ReadCapability::spec)
                .toList();
    }

    @Override
    public boolean supports(String capabilityName) {
        return capabilities.containsKey(capabilityName);
    }

    @Override
    public Map<String, Object> normalizeArguments(
            String capabilityName,
            Map<String, Object> arguments,
            AgentCapabilityArgumentContext context
    ) {
        if (!supports(capabilityName)) {
            return arguments == null ? Map.of() : Map.copyOf(arguments);
        }
        return AgentCapabilityArgumentRules.normalizeKnownCapability(capabilityName, arguments, context);
    }

    @Override
    public AgentCapabilityValidationResult validateArguments(
            String capabilityName,
            Map<String, Object> arguments,
            AgentCapabilityArgumentContext context
    ) {
        if (!supports(capabilityName)) {
            return AgentCapabilityValidationResult.rejected("unknown_tool");
        }
        return AgentCapabilityArgumentRules.validateReadRequiredArguments(capabilityName, arguments == null ? Map.of() : arguments);
    }

    @Override
    public AgentCapabilityValidationResult validate(AgentLoopState state, String capabilityName, Map<String, Object> arguments) {
        return MusicReadCapabilityValidator.validate(state, capabilityName, arguments, supports(capabilityName));
    }

    @Override
    public Optional<String> execute(AgentLoopState state, String capabilityName, Map<String, Object> arguments) {
        ReadCapability capability = capabilities.get(capabilityName);
        if (capability == null) {
            return Optional.empty();
        }
        return Optional.of(capability.executor().execute(arguments == null ? Map.of() : arguments));
    }

    private Map<String, ReadCapability> readCapabilities() {
        Map<String, ReadCapability> values = new LinkedHashMap<>();
        register(values, new AgentCapability("search_songs", CapabilityEffect.READ, "搜索歌曲、歌手、专辑或候选音乐；excludedTitles 可选。", "{\"keyword\": string, \"limit\": number, \"excludedTitles\": string[]}", Set.of("keyword", "limit")), this::searchSongs);
        register(values, new AgentCapability("get_user_music_profile", CapabilityEffect.READ, "读取本地音乐画像摘要。", "{}", Set.of()), ignored -> musicReadTools.getUserMusicProfile());
        register(values, new AgentCapability("get_song_detail", CapabilityEffect.READ, "读取歌曲详情。", "{\"songId\": string}", Set.of("songId")), arguments -> musicReadTools.getSongDetail(text(arguments, "songId")));
        register(values, new AgentCapability("get_lyrics", CapabilityEffect.READ, "读取歌词。", "{\"songId\": string}", Set.of("songId")), arguments -> musicReadTools.getLyrics(text(arguments, "songId")));
        register(values, new AgentCapability("get_hot_comments", CapabilityEffect.READ, "读取热门评论。", "{\"songId\": string, \"limit\": number}", Set.of("songId")), arguments -> musicReadTools.getHotComments(text(arguments, "songId"), integer(arguments, "limit")));
        register(values, new AgentCapability("get_user_playlists", CapabilityEffect.READ, "读取用户歌单。", "{\"limit\": number}", Set.of()), arguments -> musicReadTools.getUserPlaylists(integer(arguments, "limit")));
        register(values, new AgentCapability("get_playlist_songs", CapabilityEffect.READ, "读取歌单歌曲。", "{\"playlistId\": string, \"limit\": number}", Set.of("playlistId")), arguments -> musicReadTools.getPlaylistSongs(text(arguments, "playlistId"), integer(arguments, "limit")));
        return values;
    }

    private void register(Map<String, ReadCapability> values, AgentCapability spec, ReadExecutor executor) {
        values.put(spec.name(), new ReadCapability(spec, executor));
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

    private record ReadCapability(AgentCapability spec, ReadExecutor executor) {
    }

    @FunctionalInterface
    private interface ReadExecutor {
        String execute(Map<String, Object> arguments);
    }
}
