package com.musio.agent.capability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.loop.AgentLoopState;
import com.musio.providers.SourceCapability;
import com.musio.tools.MusicReadTools;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

@Component
@Order(0)
public class MusicReadCapabilityHandler implements AgentCapabilityHandler {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String USER_MUSIC_PROFILE = "get_user_music_profile";

    private final MusicReadTools musicReadTools;
    private final Map<String, ReadCapability> capabilities;

    public MusicReadCapabilityHandler(MusicReadTools musicReadTools) {
        this.musicReadTools = musicReadTools;
        this.capabilities = readCapabilities();
    }

    @Override
    public List<AgentCapability> capabilities() {
        Map<String, AgentCapability> values = new LinkedHashMap<>();
        for (SourceCapability capability : sourceCapabilities(SourceCapability::enabled)) {
            values.put(capability.name(), capability.toAgentCapability(argumentSpec(capability.inputSchema())));
        }
        values.put(USER_MUSIC_PROFILE, capabilities.get(USER_MUSIC_PROFILE).spec());
        return values.values().stream()
                .toList();
    }

    @Override
    public boolean supports(String capabilityName) {
        return isLocalCapability(capabilityName) || sourceCapability(capabilityName).isPresent();
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
        if (isLocalCapability(capabilityName) || sourceCapability(capabilityName).isPresent() && capabilities.containsKey(capabilityName)) {
            return AgentCapabilityArgumentRules.normalizeKnownCapability(capabilityName, arguments, context);
        }
        return arguments == null ? Map.of() : Map.copyOf(arguments);
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
        if (!capabilities.containsKey(capabilityName)) {
            return validateSourceRequiredArguments(capabilityName, arguments == null ? Map.of() : arguments);
        }
        return AgentCapabilityArgumentRules.validateReadRequiredArguments(capabilityName, arguments == null ? Map.of() : arguments);
    }

    @Override
    public AgentCapabilityValidationResult validate(AgentLoopState state, String capabilityName, Map<String, Object> arguments) {
        if (!supports(capabilityName)) {
            return AgentCapabilityValidationResult.rejected("unknown_tool");
        }
        if (!capabilities.containsKey(capabilityName)) {
            return validateSourceRequiredArguments(capabilityName, arguments == null ? Map.of() : arguments);
        }
        return MusicReadCapabilityValidator.validate(state, capabilityName, arguments, supports(capabilityName));
    }

    @Override
    public Optional<String> execute(AgentLoopState state, String capabilityName, Map<String, Object> arguments) {
        ReadCapability capability = capabilities.get(capabilityName);
        if (isLocalCapability(capabilityName)) {
            return Optional.of(capability.executor().execute(state, arguments == null ? Map.of() : arguments));
        }
        Optional<SourceCapability> sourceCapability = sourceCapability(capabilityName);
        if (sourceCapability.isEmpty()) {
            return Optional.empty();
        }
        if (capability != null) {
            return Optional.of(capability.executor().execute(state, arguments == null ? Map.of() : arguments));
        }
        return Optional.of(musicReadTools.executeSourceTool(capabilityName, arguments == null ? Map.of() : arguments));
    }

    private Map<String, ReadCapability> readCapabilities() {
        Map<String, ReadCapability> values = new LinkedHashMap<>();
        register(values, new AgentCapability("search_songs", CapabilityEffect.READ, "搜索歌曲、歌手、专辑或候选音乐；excludedTitles 可选。", "{\"keyword\": string, \"limit\": number, \"excludedTitles\": string[]}", Set.of("keyword", "limit")), (state, arguments) -> searchSongs(arguments));
        register(values, new AgentCapability(USER_MUSIC_PROFILE, CapabilityEffect.READ, "读取本地音乐画像摘要。", "{}", Set.of()), (state, ignored) -> musicReadTools.getUserMusicProfile());
        register(values, new AgentCapability("get_song_detail", CapabilityEffect.READ, "读取歌曲详情。", "{\"songId\": string}", Set.of("songId")), (state, arguments) -> musicReadTools.getSongDetail(text(arguments, "songId")));
        register(values, new AgentCapability("get_lyrics", CapabilityEffect.READ, "读取一首或多首歌曲的歌词。", "{\"songId\": string, \"songIds\": string[]}", Set.of()), this::getLyrics);
        register(values, new AgentCapability("get_hot_comments", CapabilityEffect.READ, "读取一首或多首歌曲的热门评论。", "{\"songId\": string, \"songIds\": string[], \"limit\": number}", Set.of()), this::getHotComments);
        register(values, new AgentCapability("get_user_playlists", CapabilityEffect.READ, "读取用户歌单。", "{\"limit\": number}", Set.of()), (state, arguments) -> musicReadTools.getUserPlaylists(integer(arguments, "limit")));
        register(values, new AgentCapability("get_playlist_songs", CapabilityEffect.READ, "读取歌单歌曲。", "{\"playlistId\": string, \"limit\": number}", Set.of("playlistId")), (state, arguments) -> musicReadTools.getPlaylistSongs(text(arguments, "playlistId"), integer(arguments, "limit")));
        return values;
    }

    private List<SourceCapability> sourceCapabilities(Predicate<SourceCapability> filter) {
        try {
            return musicReadTools.sourceCapabilities().stream()
                    .filter(capability -> capability != null && !capability.name().isBlank())
                    .filter(filter)
                    .toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private Optional<SourceCapability> sourceCapability(String capabilityName) {
        if (capabilityName == null || capabilityName.isBlank()) {
            return Optional.empty();
        }
        return sourceCapabilities(SourceCapability::enabled).stream()
                .filter(capability -> capabilityName.equals(capability.name()))
                .findFirst();
    }

    private boolean isLocalCapability(String capabilityName) {
        return USER_MUSIC_PROFILE.equals(capabilityName);
    }

    private AgentCapabilityValidationResult validateSourceRequiredArguments(String capabilityName, Map<String, Object> arguments) {
        Optional<SourceCapability> capability = sourceCapability(capabilityName);
        if (capability.isEmpty()) {
            return AgentCapabilityValidationResult.rejected("unknown_tool");
        }
        for (String required : capability.get().required()) {
            Object value = arguments.get(required);
            if (value == null || (value instanceof String text && text.isBlank())) {
                return AgentCapabilityValidationResult.rejected("missing_required_argument");
            }
        }
        return AgentCapabilityValidationResult.accepted();
    }

    private String argumentSpec(Map<String, Object> inputSchema) {
        if (inputSchema == null || inputSchema.isEmpty()) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(inputSchema);
        } catch (JsonProcessingException ignored) {
            return inputSchema.toString();
        }
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

    private String getLyrics(AgentLoopState state, Map<String, Object> arguments) {
        List<String> ids = unreadSongIds(state, arguments, "get_lyrics");
        if (ids.size() > 1) {
            return musicReadTools.getLyricsForSongs(ids);
        }
        if (ids.size() == 1) {
            return musicReadTools.getLyrics(ids.getFirst());
        }
        return musicReadTools.getLyrics(text(arguments, "songId"));
    }

    private String getHotComments(AgentLoopState state, Map<String, Object> arguments) {
        List<String> ids = unreadSongIds(state, arguments, "get_hot_comments");
        Integer limit = integer(arguments, "limit");
        if (ids.size() > 1) {
            return musicReadTools.getHotCommentsForSongs(ids, limit);
        }
        if (ids.size() == 1) {
            return musicReadTools.getHotComments(ids.getFirst(), limit);
        }
        return musicReadTools.getHotComments(text(arguments, "songId"), limit);
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

    private List<String> unreadSongIds(AgentLoopState state, Map<String, Object> arguments, String toolName) {
        List<String> requested = songIds(arguments);
        if (requested.isEmpty()) {
            return List.of();
        }
        java.util.Set<String> observed = AgentCapabilityStateFacts.successfulReadSongIds(state, toolName);
        if (observed.isEmpty()) {
            return requested;
        }
        return requested.stream()
                .filter(songId -> !observed.contains(songId))
                .toList();
    }

    private List<String> songIds(Map<String, Object> arguments) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        String songId = text(arguments, "songId");
        if (!songId.isBlank()) {
            ids.add(songId);
        }
        ids.addAll(stringList(arguments.get("songIds")));
        return ids.stream().limit(10).toList();
    }

    private record ReadCapability(AgentCapability spec, ReadExecutor executor) {
    }

    @FunctionalInterface
    private interface ReadExecutor {
        String execute(AgentLoopState state, Map<String, Object> arguments);
    }
}
