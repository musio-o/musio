package com.musio.memory.context;

import com.musio.memory.MusicProfileService;
import com.musio.model.AgentRecentRecommendedSong;
import com.musio.model.AgentTaskMemory;
import com.musio.model.AgentTaskRecommendationSlot;
import com.musio.model.MusicProfileMemory;
import com.musio.model.PendingLocalPlaylistAdd;
import com.musio.model.Song;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class MemoryRetriever {
    private final MusicProfileService musicProfileService;

    public MemoryRetriever(MusicProfileService musicProfileService) {
        this.musicProfileService = musicProfileService;
    }

    public List<MemoryEvidence> retrieve(MemoryRouteRequest request, MemoryReadPlan plan) {
        if (plan == null || plan.items().isEmpty()) {
            return List.of();
        }
        List<MemoryEvidence> evidence = new ArrayList<>();
        for (MemoryReadItem item : plan.items()) {
            switch (item.type()) {
                case TASK_MEMORY -> evidence.addAll(taskMemoryEvidence(request == null ? null : request.taskMemory(), item));
                case PENDING_ACTION -> evidence.addAll(pendingActionEvidence(request == null ? null : request.taskMemory(), item));
                case PROFILE_MEMORY -> evidence.addAll(profileEvidence(item));
                case BEHAVIOR_SUMMARY, MUSIC_CACHE, CONVERSATION_SUMMARY, CURRENT_STATE -> {
                    // First stage intentionally reserves these types without reading new stores.
                }
            }
        }
        return evidence.stream()
                .filter(item -> item != null && !item.text().isBlank())
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .limit(20)
                .toList();
    }

    private List<MemoryEvidence> taskMemoryEvidence(AgentTaskMemory memory, MemoryReadItem item) {
        if (memory == null) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String field : item.fields()) {
            switch (field) {
                case "lastEffectiveRequest" -> add(parts, "上轮有效请求", memory.lastEffectiveRequest());
                case "lastTargetSong" -> add(parts, "上轮目标歌曲", songRef(memory.lastTargetSong()));
                case "lastResultSongs" -> add(parts, "上轮结果歌曲", songs(memory.lastResultSongs(), item.limit()));
                case "avoidSongTitles" -> add(parts, "本轮排除歌名", join(memory.avoidSongTitles(), item.limit()));
                case "recentRecommendedSongs" -> add(parts, "近期已推荐", recent(memory.recentRecommendedSongs(), item.limit()));
                case "lastRecommendationSlots" -> add(parts, "上轮推荐目标", slots(memory.lastRecommendationSlots(), item.limit()));
                case "lastRequiredOutcomes" -> add(parts, "上轮必要结果", join(memory.lastRequiredOutcomes(), item.limit()));
                case "lastObservationSummaries" -> add(parts, "上轮工具摘要", join(memory.lastObservationSummaries(), item.limit()));
                case "pendingLocalPlaylistAdd" -> add(parts, "待确认歌单写入", pending(memory.pendingLocalPlaylistAdd()));
                default -> {
                }
            }
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        return List.of(new MemoryEvidence(MemoryType.TASK_MEMORY, memory.userId(), String.join("\n", parts), score(item), 0.9, item.reason(), memory.updatedAt()));
    }

    private List<MemoryEvidence> pendingActionEvidence(AgentTaskMemory memory, MemoryReadItem item) {
        if (memory == null || memory.pendingLocalPlaylistAdd() == null) {
            return List.of();
        }
        PendingLocalPlaylistAdd pending = memory.pendingLocalPlaylistAdd();
        return List.of(new MemoryEvidence(
                MemoryType.PENDING_ACTION,
                pending.playlistId(),
                pending(pending),
                1.0,
                0.95,
                item.reason(),
                pending.createdAt()
        ));
    }

    private List<MemoryEvidence> profileEvidence(MemoryReadItem item) {
        if (musicProfileService == null) {
            return List.of();
        }
        return musicProfileService.readOrCreate()
                .map(profile -> profileEvidence(profile, item))
                .orElse(List.of());
    }

    private List<MemoryEvidence> profileEvidence(MusicProfileMemory profile, MemoryReadItem item) {
        List<String> parts = new ArrayList<>();
        for (String field : item.fields()) {
            switch (field) {
                case "summary" -> add(parts, "画像摘要", profile.summary());
                case "strongPreferences" -> add(parts, "强偏好", join(profile.strongPreferences(), item.limit()));
                case "favoriteArtists" -> add(parts, "高频歌手", join(profile.favoriteArtists(), item.limit()));
                case "favoriteAlbums" -> add(parts, "偏好专辑", join(profile.favoriteAlbums(), item.limit()));
                case "likedSongExamples" -> add(parts, "喜欢歌曲示例", join(profile.likedSongExamples(), item.limit()));
                case "recommendationHints" -> add(parts, "推荐提示", join(profile.recommendationHints(), item.limit()));
                case "avoid" -> add(parts, "负向偏好", join(profile.avoid(), item.limit()));
                default -> {
                }
            }
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        Instant updatedAt = profile.generatedAt() == null ? Instant.EPOCH : profile.generatedAt();
        return List.of(new MemoryEvidence(MemoryType.PROFILE_MEMORY, profile.accountKey(), String.join("\n", parts), score(item), 0.75, item.reason(), updatedAt));
    }

    private double score(MemoryReadItem item) {
        return Math.max(0.1, item.priority() / 100.0);
    }

    private void add(List<String> parts, String label, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(label + ": " + value.strip());
        }
    }

    private String songs(List<Song> songs, int limit) {
        if (songs == null || songs.isEmpty()) {
            return "";
        }
        return String.join("；", songs.stream().limit(limit).map(this::songRef).filter(value -> !value.isBlank()).toList());
    }

    private String songRef(Song song) {
        if (song == null) {
            return "";
        }
        String artists = song.artists() == null || song.artists().isEmpty() ? "" : " - " + String.join("/", song.artists());
        String id = song.id() == null || song.id().isBlank() ? "" : " id=" + song.id();
        return safe(song.title()) + artists + id;
    }

    private String recent(List<AgentRecentRecommendedSong> songs, int limit) {
        if (songs == null || songs.isEmpty()) {
            return "";
        }
        return String.join("；", songs.stream()
                .limit(limit)
                .map(item -> safe(item.title()) + (item.artists().isEmpty() ? "" : " - " + String.join("/", item.artists())))
                .filter(value -> !value.isBlank())
                .toList());
    }

    private String slots(List<AgentTaskRecommendationSlot> slots, int limit) {
        if (slots == null || slots.isEmpty()) {
            return "";
        }
        return String.join("；", slots.stream()
                .limit(limit)
                .map(slot -> "%s:%s=%s x%s".formatted(slot.slotId(), slot.targetType(), slot.target(), slot.requestedCount()))
                .toList());
    }

    private String pending(PendingLocalPlaylistAdd pending) {
        if (pending == null) {
            return "";
        }
        return "playlistId=%s, songs=%s".formatted(pending.playlistId(), songs(pending.songs(), 10));
    }

    private String join(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join("、", values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .limit(limit)
                .toList());
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
