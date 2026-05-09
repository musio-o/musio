package com.musio.agent.capability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class AgentCapabilityRegistry {
    public static final String RECOMMEND_SONGS = "recommend_songs";
    public static final String ADD_SONG_TO_MUSIO_PLAYLIST = "add_song_to_musio_playlist";

    private static final List<AgentCapability> DEFAULT_CAPABILITIES = List.of(
            new AgentCapability(RECOMMEND_SONGS, CapabilityEffect.READ, "根据开放推荐、场景、风格、心境或结构化多目标请求生成具体歌曲候选，并在音乐源中精确匹配真实歌曲。", "{\"request\": string, \"count\": number, \"slots\": [{\"slotId\": string, \"targetType\": string, \"target\": string, \"count\": number}], \"excludedTitles\": string[]}", Set.of("request")),
            new AgentCapability("search_songs", CapabilityEffect.READ, "搜索歌曲、歌手、专辑或候选音乐；excludedTitles 可选。", "{\"keyword\": string, \"limit\": number, \"excludedTitles\": string[]}", Set.of("keyword", "limit")),
            new AgentCapability("get_user_music_profile", CapabilityEffect.READ, "读取本地音乐画像摘要。", "{}", Set.of()),
            new AgentCapability("get_song_detail", CapabilityEffect.READ, "读取歌曲详情。", "{\"songId\": string}", Set.of("songId")),
            new AgentCapability("get_lyrics", CapabilityEffect.READ, "读取一首或多首歌曲的歌词。", "{\"songId\": string, \"songIds\": string[]}", Set.of()),
            new AgentCapability("get_hot_comments", CapabilityEffect.READ, "读取一首或多首歌曲的热门评论。", "{\"songId\": string, \"songIds\": string[], \"limit\": number}", Set.of()),
            new AgentCapability("get_user_playlists", CapabilityEffect.READ, "读取用户歌单。", "{\"limit\": number}", Set.of()),
            new AgentCapability("get_playlist_songs", CapabilityEffect.READ, "读取歌单歌曲。", "{\"playlistId\": string, \"limit\": number}", Set.of("playlistId")),
            new AgentCapability(ADD_SONG_TO_MUSIO_PLAYLIST, CapabilityEffect.LOCAL_WRITE, "把一首或多首歌曲收藏到本地 Musio 默认歌单；这是 Musio 本地歌单写入，不是 QQ 音乐账号收藏。", "{\"playlistId\": string, \"songId\": string, \"songIds\": string[], \"songTitle\": string, \"artist\": string, \"songIndex\": number, \"songIndexes\": number[]}", Set.of())
    );

    private final List<AgentCapability> capabilities;
    private final Map<String, AgentCapabilityHandler> handlers;

    public AgentCapabilityRegistry() {
        this.capabilities = DEFAULT_CAPABILITIES;
        this.handlers = Map.of();
    }

    @Autowired
    public AgentCapabilityRegistry(List<AgentCapabilityHandler> handlers) {
        List<AgentCapability> registered = handlers == null ? List.of() : handlers.stream()
                .filter(handler -> handler != null && handler.capabilities() != null)
                .flatMap(handler -> handler.capabilities().stream())
                .filter(capability -> capability != null && !capability.name().isBlank())
                .toList();
        this.capabilities = registered.isEmpty() ? DEFAULT_CAPABILITIES : registered;
        this.handlers = handlerMap(handlers);
    }

    public AgentCapabilityManifest readManifest() {
        return new AgentCapabilityManifest(capabilities.stream()
                .filter(capability -> capability.effect() == CapabilityEffect.READ)
                .toList());
    }

    public AgentCapabilityManifest manifest(boolean allowLocalWrites) {
        List<AgentCapability> allowed = new ArrayList<>();
        for (AgentCapability capability : capabilities) {
            if (capability.effect() == CapabilityEffect.READ
                    || (allowLocalWrites && capability.effect() == CapabilityEffect.LOCAL_WRITE)) {
                allowed.add(capability);
            }
        }
        return new AgentCapabilityManifest(allowed);
    }

    public Optional<AgentCapability> capability(String name) {
        return manifest(true).find(name);
    }

    public Map<String, Object> normalizeArguments(
            String capabilityName,
            Map<String, Object> arguments,
            AgentCapabilityArgumentContext context
    ) {
        AgentCapabilityHandler handler = handlers.get(capabilityName);
        if (handler != null) {
            return handler.normalizeArguments(capabilityName, arguments, context);
        }
        return AgentCapabilityArgumentRules.normalizeKnownCapability(capabilityName, arguments, context);
    }

    public AgentCapabilityValidationResult validateArguments(
            String capabilityName,
            Map<String, Object> arguments,
            AgentCapabilityArgumentContext context
    ) {
        AgentCapabilityHandler handler = handlers.get(capabilityName);
        if (handler != null) {
            return handler.validateArguments(capabilityName, arguments, context);
        }
        return AgentCapabilityArgumentRules.validateKnownCapability(capabilityName, arguments == null ? Map.of() : arguments, context);
    }

    private Map<String, AgentCapabilityHandler> handlerMap(List<AgentCapabilityHandler> handlers) {
        if (handlers == null || handlers.isEmpty()) {
            return Map.of();
        }
        Map<String, AgentCapabilityHandler> byName = new LinkedHashMap<>();
        for (AgentCapabilityHandler handler : handlers) {
            if (handler == null || handler.capabilities() == null) {
                continue;
            }
            for (AgentCapability capability : handler.capabilities()) {
                if (capability != null && !capability.name().isBlank() && handler.supports(capability.name())) {
                    byName.putIfAbsent(capability.name(), handler);
                }
            }
        }
        return Map.copyOf(byName);
    }
}
