package com.musio.memory.context;

import com.musio.memory.BehaviorSummary;
import com.musio.memory.BehaviorSummaryService;
import com.musio.memory.ConversationSummary;
import com.musio.memory.ConversationSummaryStore;
import com.musio.memory.MusicCacheEntry;
import com.musio.memory.MusicCacheStore;
import com.musio.memory.MusicProfileService;
import com.musio.memory.PreferenceItem;
import com.musio.memory.PreferenceStore;
import com.musio.model.AgentRecentRecommendedSong;
import com.musio.model.AgentTaskMemory;
import com.musio.model.AgentTaskRecommendationSlot;
import com.musio.model.MusicProfileMemory;
import com.musio.model.PendingLocalPlaylistAdd;
import com.musio.model.Song;
import com.musio.player.PlayerQueueService;
import com.musio.player.PlayerState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class MemoryRetriever {
    private final MusicProfileService musicProfileService;
    private final BehaviorSummaryService behaviorSummaryService;
    private final MusicCacheStore musicCacheStore;
    private final ConversationSummaryStore conversationSummaryStore;
    private final PreferenceStore preferenceStore;
    private final PlayerQueueService playerQueueService;

    public MemoryRetriever(MusicProfileService musicProfileService) {
        this(musicProfileService, null, null, null, null, null);
    }

    public MemoryRetriever(
            MusicProfileService musicProfileService,
            BehaviorSummaryService behaviorSummaryService,
            MusicCacheStore musicCacheStore,
            ConversationSummaryStore conversationSummaryStore,
            PreferenceStore preferenceStore
    ) {
        this(musicProfileService, behaviorSummaryService, musicCacheStore, conversationSummaryStore, preferenceStore, null);
    }

    @Autowired
    public MemoryRetriever(
            MusicProfileService musicProfileService,
            BehaviorSummaryService behaviorSummaryService,
            MusicCacheStore musicCacheStore,
            ConversationSummaryStore conversationSummaryStore,
            PreferenceStore preferenceStore,
            PlayerQueueService playerQueueService
    ) {
        this.musicProfileService = musicProfileService;
        this.behaviorSummaryService = behaviorSummaryService;
        this.musicCacheStore = musicCacheStore;
        this.conversationSummaryStore = conversationSummaryStore;
        this.preferenceStore = preferenceStore;
        this.playerQueueService = playerQueueService;
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
                case PROFILE_MEMORY -> {
                    evidence.addAll(profileEvidence(item));
                    evidence.addAll(preferenceEvidence(request == null ? "" : request.userId(), item));
                }
                case BEHAVIOR_SUMMARY -> evidence.addAll(behaviorSummaryEvidence(request, item));
                case MUSIC_CACHE -> evidence.addAll(musicCacheEvidence(request, item));
                case CONVERSATION_SUMMARY -> evidence.addAll(conversationSummaryEvidence(request, item));
                case CURRENT_STATE -> evidence.addAll(currentStateEvidence(item));
            }
        }
        return evidence.stream()
                .filter(item -> item != null && !item.text().isBlank())
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .limit(20)
                .toList();
    }

    private List<MemoryEvidence> currentStateEvidence(MemoryReadItem item) {
        if (playerQueueService == null) {
            return List.of();
        }
        PlayerState state = playerQueueService.state();
        if (state == null) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String field : item.fields()) {
            switch (field) {
                case "currentPlayback" -> add(parts, "当前播放", currentPlayback(state));
                case "queueState" -> add(parts, "播放队列", queueState(state, item.limit()));
                default -> {
                }
            }
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        return List.of(new MemoryEvidence(
                MemoryType.CURRENT_STATE,
                currentPlaybackSourceId(state),
                String.join("\n", parts),
                score(item),
                0.85,
                item.reason(),
                Instant.now()
        ));
    }

    private String currentPlaybackSourceId(PlayerState state) {
        return state.currentSong() == null || safe(state.currentSong().id()).isBlank()
                ? "player"
                : safe(state.currentSong().id());
    }

    private List<MemoryEvidence> behaviorSummaryEvidence(MemoryRouteRequest request, MemoryReadItem item) {
        if (behaviorSummaryService == null || request == null) {
            return List.of();
        }
        BehaviorSummary summary = behaviorSummaryService.summarize(request.userId());
        List<String> parts = new ArrayList<>();
        for (String field : item.fields()) {
            switch (field) {
                case "last24HoursSummary" -> add(parts, "最近 24 小时行为", summary.last24HoursSummary());
                case "last7DaysSummary" -> add(parts, "最近 7 天行为", summary.last7DaysSummary());
                case "negativeSignals" -> add(parts, "近期负向信号", join(summary.negativeSignals(), item.limit()));
                case "sceneSignals" -> add(parts, "近期场景信号", join(summary.sceneSignals(), item.limit()));
                default -> {
                }
            }
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        return List.of(new MemoryEvidence(MemoryType.BEHAVIOR_SUMMARY, summary.userId(), String.join("\n", parts), score(item), 0.7, item.reason(), summary.generatedAt()));
    }

    private List<MemoryEvidence> musicCacheEvidence(MemoryRouteRequest request, MemoryReadItem item) {
        if (musicCacheStore == null || request == null) {
            return List.of();
        }
        String query = item.query().isBlank() ? request.effectiveRequest() : item.query();
        List<MusicCacheEntry> entries = musicCacheStore.search(request.userId(), item.fields(), query, item.limit());
        return entries.stream()
                .map(entry -> new MemoryEvidence(
                        MemoryType.MUSIC_CACHE,
                        entry.id(),
                        "%s%s%s".formatted(
                                cacheTypeLabel(entry.cacheType()),
                                entry.title().isBlank() ? "" : "：" + entry.title(),
                                "\n" + entry.content()
                        ).strip(),
                        score(item) * 0.95,
                        0.75,
                        entry.evidence().isBlank() ? item.reason() : entry.evidence(),
                        entry.updatedAt()
                ))
                .toList();
    }

    private List<MemoryEvidence> conversationSummaryEvidence(MemoryRouteRequest request, MemoryReadItem item) {
        if (conversationSummaryStore == null || request == null) {
            return List.of();
        }
        String query = item.query().isBlank() ? request.effectiveRequest() : item.query();
        List<ConversationSummary> summaries = conversationSummaryStore.search(request.userId(), query, item.limit());
        return summaries.stream()
                .map(summary -> new MemoryEvidence(
                        MemoryType.CONVERSATION_SUMMARY,
                        summary.id(),
                        summary.summary(),
                        score(item) * 0.85,
                        0.65,
                        item.reason(),
                        summary.updatedAt()
                ))
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

    private List<MemoryEvidence> preferenceEvidence(String userId, MemoryReadItem item) {
        if (preferenceStore == null) {
            return List.of();
        }
        List<PreferenceItem> preferences = preferenceStore.items(userId, Math.max(5, item.limit()));
        if (preferences.isEmpty()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        if (item.fields().contains("strongPreferences") || item.fields().contains("recommendationHints")) {
            add(parts, "偏好候选聚合", join(preferences.stream()
                    .filter(preference -> "positive".equals(preference.polarity()))
                    .map(preference -> preference.label() + "（" + confidence(preference.confidence()) + "）")
                    .toList(), item.limit()));
        }
        if (item.fields().contains("avoid")) {
            add(parts, "负向偏好候选聚合", join(preferences.stream()
                    .filter(preference -> "negative".equals(preference.polarity()))
                    .map(preference -> preference.label() + "（" + confidence(preference.confidence()) + "）")
                    .toList(), item.limit()));
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        Instant updatedAt = preferences.stream()
                .map(PreferenceItem::updatedAt)
                .max(Instant::compareTo)
                .orElse(Instant.EPOCH);
        return List.of(new MemoryEvidence(MemoryType.PROFILE_MEMORY, userId, String.join("\n", parts), score(item) * 0.9, 0.65, "本地偏好候选聚合。", updatedAt));
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

    private String currentPlayback(PlayerState state) {
        Song currentSong = state.currentSong();
        if (currentSong == null) {
            return "无当前播放";
        }
        String status = state.paused() ? "已暂停" : "播放中";
        String duration = state.durationSeconds() == null || state.durationSeconds() <= 0
                ? ""
                : "/" + formatDuration(state.durationSeconds());
        String lyric = safe(state.lyricLine()).isBlank() ? "" : "，歌词行=" + safe(state.lyricLine());
        return "%s，状态=%s，进度=%s%s，模式=%s%s".formatted(
                songRef(currentSong),
                status,
                formatDuration(state.positionSeconds()),
                duration,
                state.playbackMode(),
                lyric
        );
    }

    private String queueState(PlayerState state, int limit) {
        List<Song> queue = state.queue();
        if (queue == null || queue.isEmpty()) {
            return "队列为空";
        }
        int currentIndex = state.currentIndex();
        List<String> parts = new ArrayList<>();
        parts.add("总数=" + queue.size());
        parts.add("当前序号=" + (currentIndex >= 0 ? currentIndex + 1 : "未知"));
        if (currentIndex > 0 && currentIndex - 1 < queue.size()) {
            parts.add("上一首=" + songRef(queue.get(currentIndex - 1)));
        }
        if (currentIndex >= 0 && currentIndex < queue.size()) {
            parts.add("当前=" + songRef(queue.get(currentIndex)));
        }
        if (currentIndex >= 0 && currentIndex + 1 < queue.size()) {
            parts.add("下一首=" + songRef(queue.get(currentIndex + 1)));
        }
        parts.add("队列摘要=" + queuePreview(queue, limit));
        return String.join("；", parts);
    }

    private String queuePreview(List<Song> queue, int limit) {
        return String.join("；", queue.stream()
                .limit(limit)
                .map(this::songRef)
                .filter(value -> !value.isBlank())
                .toList());
    }

    private String formatDuration(int seconds) {
        int safeSeconds = Math.max(0, seconds);
        return "%d:%02d".formatted(safeSeconds / 60, safeSeconds % 60);
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

    private String cacheTypeLabel(String cacheType) {
        return switch (cacheType == null ? "" : cacheType) {
            case "songDetail" -> "歌曲详情缓存";
            case "lyricsSummary" -> "歌词缓存";
            case "commentSummary", "comments" -> "评论缓存";
            case "playlistSummary" -> "歌单缓存";
            default -> "音乐缓存";
        };
    }

    private String confidence(double confidence) {
        return String.format(java.util.Locale.ROOT, "%.2f", confidence);
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
