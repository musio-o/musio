package com.musio.memory.context;

import com.musio.agent.AgentRequiredOutcome;
import com.musio.model.AgentTaskMemory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Component
public class DeterministicMemoryGuard {
    public MemoryReadPlan requiredPlan(MemoryRouteRequest request) {
        List<MemoryReadItem> items = new ArrayList<>();
        AgentTaskMemory memory = request == null ? null : request.taskMemory();
        String message = normalize(request == null ? "" : request.userMessage());
        String taskType = request == null ? "" : safe(request.taskType());
        String contextMode = request == null ? "" : safe(request.contextMode());

        if (memory != null && memory.pendingLocalPlaylistAdd() != null) {
            items.add(item(MemoryType.PENDING_ACTION, List.of("pendingLocalPlaylistAdd"), 100, 1, "存在待确认本地歌单写入。"));
        }
        if (mentionsPreviousSong(message)) {
            items.add(item(MemoryType.TASK_MEMORY, List.of("lastTargetSong", "lastResultSongs", "lastRecommendationSlots"), 100, 5, "用户使用这首/刚才那首等指代，优先读取 Agent 最近推荐和结果。"));
        }
        if (mentionsExplicitPlayerReference(message)) {
            items.add(item(MemoryType.CURRENT_STATE, currentStateFields(message), "current", 90, 3, "用户明确引用当前播放或播放器队列。"));
        }
        if (containsAny(message, "加入", "收藏", "保存", "歌单", "确认")) {
            items.add(item(MemoryType.PENDING_ACTION, List.of("pendingLocalPlaylistAdd"), 95, 1, "写入或确认意图需要读取待确认动作。"));
            items.add(item(MemoryType.TASK_MEMORY, List.of("lastResultSongs"), 90, 5, "歌单写入可能引用上一轮结果。"));
        }
        if ("correction".equals(contextMode)) {
            items.add(item(MemoryType.TASK_MEMORY, List.of("lastResultSongs", "lastRecommendationSlots", "lastRequiredOutcomes"), 95, 5, "纠错需要上一轮结果和目标。"));
        } else if ("follow_up".equals(contextMode) || "refer_previous_song".equals(contextMode)) {
            items.add(item(MemoryType.TASK_MEMORY, List.of("lastEffectiveRequest", "lastTargetSong", "lastResultSongs"), 90, 5, "追问需要短期任务上下文。"));
        }
        if (containsAny(message, "别重复", "不要重复", "换一首", "换首", "推荐过", "推过")) {
            items.add(item(MemoryType.TASK_MEMORY, List.of("avoidSongTitles", "recentRecommendedSongs", "lastResultSongs"), 95, 10, "本轮需要避免重复推荐。"));
        }
        if ("recommend".equals(taskType) || hasOutcome(request, AgentRequiredOutcome.RECOMMENDATION)) {
            items.add(item(MemoryType.PROFILE_MEMORY, List.of("summary", "strongPreferences", "favoriteArtists", "recommendationHints", "avoid"), 80, 1, "推荐任务需要长期音乐画像摘要。"));
            items.add(item(MemoryType.TASK_MEMORY, List.of("recentRecommendedSongs", "avoidSongTitles"), 80, 10, "推荐任务需要近期已推荐和排除项。"));
            items.add(item(MemoryType.BEHAVIOR_SUMMARY, List.of("last7DaysSummary", "negativeSignals"), 55, 2, "预留近期行为摘要。"));
        }
        if ("comments".equals(taskType) || hasOutcome(request, AgentRequiredOutcome.COMMENTS) || containsAny(message, "评论", "热评", "评论区")) {
            items.add(item(MemoryType.MUSIC_CACHE, List.of("commentSummary", "comments"), 70, 3, "评论任务预留音乐缓存读取。"));
        }
        return new MemoryReadPlan(dedupe(items), 1200);
    }

    private boolean hasOutcome(MemoryRouteRequest request, AgentRequiredOutcome outcome) {
        return request != null && request.goal() != null && request.goal().requiredOutcomes().contains(outcome);
    }

    private MemoryReadItem item(MemoryType type, List<String> fields, int priority, int limit, String reason) {
        return item(type, fields, "session", priority, limit, reason);
    }

    private MemoryReadItem item(MemoryType type, List<String> fields, String scope, int priority, int limit, String reason) {
        return new MemoryReadItem(type, fields, "", scope, priority, limit, reason);
    }

    private List<MemoryReadItem> dedupe(List<MemoryReadItem> items) {
        List<MemoryReadItem> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (MemoryReadItem item : items) {
            String key = item.type() + "|" + String.join(",", item.fields());
            if (seen.add(key)) {
                result.add(item);
            }
        }
        return result;
    }

    private boolean mentionsPreviousSong(String normalized) {
        return containsAny(normalized, "这首", "这歌", "刚才那首", "上一首", "上首", "刚刚那首", "刚才推荐");
    }

    private boolean mentionsExplicitPlayerReference(String normalized) {
        return containsAny(
                normalized,
                "当前播放",
                "正在播放",
                "正在放",
                "正在播",
                "现在播放",
                "现在放",
                "现在播",
                "播放器里",
                "播放器中",
                "播放队列",
                "队列上一首",
                "队列里",
                "播放列表"
        );
    }

    private List<String> currentStateFields(String normalized) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        if (containsAny(normalized, "当前播放", "正在播放", "正在放", "正在播", "现在播放", "现在放", "现在播", "播放器里", "播放器中")) {
            fields.add("currentPlayback");
        }
        if (containsAny(normalized, "播放队列", "队列上一首", "队列里", "播放列表")) {
            fields.add("queueState");
        }
        if (fields.isEmpty()) {
            fields.add("currentPlayback");
        }
        return List.copyOf(fields);
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return safe(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
