package com.musio.agent.capability;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class AgentCapabilityRegistry {
    public static final String ADD_SONG_TO_MUSIO_PLAYLIST = "add_song_to_musio_playlist";

    private final List<AgentCapability> readCapabilities = List.of(
            new AgentCapability("search_songs", CapabilityEffect.READ, "搜索歌曲、歌手、专辑或候选音乐；excludedTitles 可选。", "{\"keyword\": string, \"limit\": number, \"excludedTitles\": string[]}", Set.of("keyword", "limit")),
            new AgentCapability("get_user_music_profile", CapabilityEffect.READ, "读取本地音乐画像摘要。", "{}", Set.of()),
            new AgentCapability("get_song_detail", CapabilityEffect.READ, "读取歌曲详情。", "{\"songId\": string}", Set.of("songId")),
            new AgentCapability("get_lyrics", CapabilityEffect.READ, "读取歌词。", "{\"songId\": string}", Set.of("songId")),
            new AgentCapability("get_hot_comments", CapabilityEffect.READ, "读取热门评论。", "{\"songId\": string, \"limit\": number}", Set.of("songId")),
            new AgentCapability("get_user_playlists", CapabilityEffect.READ, "读取用户歌单。", "{\"limit\": number}", Set.of()),
            new AgentCapability("get_playlist_songs", CapabilityEffect.READ, "读取歌单歌曲。", "{\"playlistId\": string, \"limit\": number}", Set.of("playlistId"))
    );

    private final List<AgentCapability> localWriteCapabilities = List.of(
            new AgentCapability(ADD_SONG_TO_MUSIO_PLAYLIST, CapabilityEffect.LOCAL_WRITE, "把歌曲收藏到本地 Musio 默认歌单；这是 Musio 本地歌单写入，不是 QQ 音乐账号收藏。", "{\"playlistId\": string, \"songId\": string, \"songTitle\": string, \"artist\": string, \"songIndex\": number}", Set.of())
    );

    public AgentCapabilityManifest readManifest() {
        return new AgentCapabilityManifest(readCapabilities);
    }

    public AgentCapabilityManifest manifest(boolean allowLocalWrites) {
        List<AgentCapability> capabilities = new ArrayList<>(readCapabilities);
        if (allowLocalWrites) {
            capabilities.addAll(localWriteCapabilities);
        }
        return new AgentCapabilityManifest(capabilities);
    }

    public Optional<AgentCapability> capability(String name) {
        return manifest(true).find(name);
    }
}
