package com.musio.agent.trace;

import com.musio.events.AgentEventBus;
import com.musio.model.AgentEvent;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AgentTracePublisher {
    private static final int SUMMARY_LIMIT = 160;

    private final AgentEventBus eventBus;

    public AgentTracePublisher(AgentEventBus eventBus) {
        this.eventBus = eventBus;
    }

    public void publishIntentRunning(String runId) {
        publish(runId, "intent.music-task", "intent", "running", "理解音乐任务", "正在判断这次音乐请求要调用哪些能力。", Map.of());
    }

    public void publishRequestReceived(String runId, String userMessage) {
        publish(runId, "intent.request", "intent", "done", "理解请求", "已收到你的消息，开始判断这次要不要结合音乐能力。", Map.of(
                "messageLength", userMessage == null ? 0 : userMessage.length()
        ));
    }

    public void publishPlanningRunning(String runId) {
        publish(runId, "context.turn-plan", "context", "running", "规划处理方式", "正在判断是直接回答，还是先搜索、读取歌词、评论或生成推荐。", Map.of());
    }

    public void publishPlanningDone(String runId, String disposition, String taskType, List<String> toolNames) {
        List<String> safeToolNames = toolNames == null ? List.of() : toolNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .limit(8)
                .toList();
        String summary = safeToolNames.isEmpty()
                ? "这轮会直接组织回复，不需要调用音乐工具。"
                : "这轮会先使用音乐能力：" + String.join("、", safeToolNames) + "。";
        publish(runId, "context.turn-plan", "context", "done", "规划处理方式", summary, Map.of(
                "disposition", disposition == null ? "" : disposition,
                "taskType", taskType == null ? "" : taskType,
                "toolNames", safeToolNames
        ));
    }

    public void publishIntentDone(String runId) {
        publish(runId, "intent.music-task", "intent", "done", "理解音乐任务", "已识别为音乐任务，会结合可用音乐能力生成回答。", Map.of());
    }

    public void publishComposeRunning(String runId) {
        publish(runId, "compose.answer", "compose", "running", "整理回答", "正在把工具结果整理成可读回答。", Map.of());
    }

    public void publishComposeDone(String runId) {
        publish(runId, "compose.answer", "compose", "done", "整理回答", "回答已整理完成。", Map.of());
    }

    public void publishRecommendationRunning(String runId) {
        publish(runId, "context.recommendation-candidates", "context", "running", "生成推荐候选", "正在根据场景先挑出适合的歌曲名。", Map.of());
    }

    public void publishRecommendationDone(String runId, int count) {
        publish(runId, "context.recommendation-candidates", "context", "done", "生成推荐候选", "已生成 " + count + " 个候选歌曲查询。", Map.of(
                "count", count
        ));
    }

    public void publishRecommendationResolveRunning(String runId) {
        publish(runId, "tool.recommendation-resolve", "tool", "running", "解析可播放歌曲", "正在把推荐候选精确匹配到 QQ 音乐歌曲。", Map.of());
    }

    public void publishRecommendationResolveToolStart(String runId, int candidateCount) {
        eventBus.publish(runId, AgentEvent.of("tool_start", Map.of(
                "runId", runId,
                "tool", "recommendation_resolve",
                "input", Map.of(
                        "candidateCount", candidateCount,
                        "searchLimitPerCandidate", 10,
                        "matchingPolicy", "title_artist_conservative"
                )
        )));
    }

    public void publishRecommendationResolveToolResult(String runId, List<String> resolvedTitles, List<String> unresolvedTitles) {
        int resolvedCount = resolvedTitles == null ? 0 : resolvedTitles.size();
        int unresolvedCount = unresolvedTitles == null ? 0 : unresolvedTitles.size();
        List<String> safeResolvedTitles = safeTitles(resolvedTitles);
        List<String> safeUnresolvedTitles = safeTitles(unresolvedTitles);
        eventBus.publish(runId, AgentEvent.of("tool_result", Map.of(
                "runId", runId,
                "tool", "recommendation_resolve",
                "summary", recommendationResolveSummary(
                        safeResolvedTitles,
                        safeUnresolvedTitles,
                        resolvedCount,
                        unresolvedCount
                ),
                "resolvedCount", resolvedCount,
                "unresolvedCount", unresolvedCount
        )));
    }

    public void publishRecommendationResolveDone(String runId, int resolvedCount, int unresolvedCount) {
        publishRecommendationResolveDone(runId, List.of(), List.of(), resolvedCount, unresolvedCount);
    }

    public void publishRecommendationResolveDone(String runId, List<String> resolvedTitles, List<String> unresolvedTitles) {
        publishRecommendationResolveDone(
                runId,
                resolvedTitles,
                unresolvedTitles,
                resolvedTitles == null ? 0 : resolvedTitles.size(),
                unresolvedTitles == null ? 0 : unresolvedTitles.size()
        );
    }

    private void publishRecommendationResolveDone(
            String runId,
            List<String> resolvedTitles,
            List<String> unresolvedTitles,
            int resolvedCount,
            int unresolvedCount
    ) {
        List<String> safeResolvedTitles = safeTitles(resolvedTitles);
        List<String> safeUnresolvedTitles = safeTitles(unresolvedTitles);
        publish(runId, "tool.recommendation-resolve", "tool", "done", "解析可播放歌曲", recommendationResolveSummary(
                safeResolvedTitles,
                safeUnresolvedTitles,
                resolvedCount,
                unresolvedCount
        ), Map.of(
                "resolvedCount", resolvedCount,
                "unresolvedCount", unresolvedCount,
                "resolvedTitles", safeResolvedTitles,
                "unresolvedTitles", safeUnresolvedTitles
        ));
    }

    public void publishToolRunning(String runId, String toolName, Map<String, Object> input) {
        publish(runId, "tool." + toolName, "tool", "running", toolTitle(toolName), runningSummary(toolName), toolInputPreview(toolName, input));
    }

    public void publishToolDone(String runId, String toolName, Map<String, Object> result) {
        publish(runId, "tool." + toolName, "tool", "done", toolTitle(toolName), doneSummary(toolName, result), toolResultPreview(toolName, result));
    }

    public void publishToolError(String runId, String toolName, String message) {
        publish(runId, "tool." + toolName, "tool", "error", toolTitle(toolName), "工具执行失败：" + safeSummary(message), Map.of(
                "tool", toolName
        ));
    }

    public void publishProgress(String runId, String stepId, String stage, String title, String summary, Map<String, Object> safeData) {
        publish(runId, stepId, stage, "running", title, summary, safeData == null ? Map.of() : safeData);
    }

    public boolean shouldTraceUserMessage(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.strip().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || isSmallTalk(normalized)) {
            return false;
        }
        return normalized.contains("歌")
                || normalized.contains("音乐")
                || normalized.contains("播放")
                || normalized.contains("歌词")
                || normalized.contains("评论")
                || normalized.contains("歌单")
                || normalized.contains("专辑")
                || normalized.contains("歌手")
                || normalized.contains("qq音乐")
                || normalized.contains("qq music")
                || (normalized.contains("推荐") && (normalized.contains("听") || normalized.contains("首")));
    }

    public boolean shouldPlanRecommendation(String message) {
        if (!shouldTraceUserMessage(message)) {
            return false;
        }
        String normalized = stripSentence(message);
        return normalized.contains("推荐") && (
                normalized.contains("歌")
                        || normalized.contains("音乐")
                        || normalized.contains("听")
                        || normalized.contains("首")
        );
    }

    private void publish(
            String runId,
            String stepId,
            String stage,
            String status,
            String title,
            String summary,
            Map<String, Object> safeData
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", runId);
        data.put("stepId", stepId);
        data.put("stage", stage);
        data.put("status", status);
        data.put("visibility", "user");
        data.put("title", title);
        data.put("summary", safeSummary(summary));
        data.put("safeData", safeData);
        eventBus.publish(runId, AgentEvent.of("trace_step", data));
    }

    private Map<String, Object> toolInputPreview(String toolName, Map<String, Object> input) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("tool", toolName);
        copyIfPresent(input, preview, "keyword");
        copyIfPresent(input, preview, "limit");
        copyIfPresent(input, preview, "songId");
        copyIfPresent(input, preview, "playlistId");
        return preview;
    }

    private Map<String, Object> toolResultPreview(String toolName, Map<String, Object> result) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("tool", toolName);
        copyIfPresent(result, preview, "count");
        Object success = result.get("success");
        if (success instanceof Boolean) {
            preview.put("success", success);
        }
        return preview;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            target.put(key, value);
        }
    }

    private String runningSummary(String toolName) {
        return switch (toolName) {
            case "search_songs" -> "正在搜索 QQ 音乐候选歌曲。";
            case "get_user_music_profile" -> "正在读取本地音乐偏好摘要。";
            case "get_song_detail" -> "正在读取歌曲详情。";
            case "get_lyrics" -> "正在读取歌词。";
            case "get_hot_comments" -> "正在读取热门评论。";
            case "get_user_playlists" -> "正在读取你的 QQ 音乐歌单。";
            case "get_playlist_songs" -> "正在读取歌单里的歌曲。";
            default -> "正在调用音乐能力。";
        };
    }

    private String doneSummary(String toolName, Map<String, Object> result) {
        if ("get_user_music_profile".equals(toolName)) {
            return Boolean.TRUE.equals(result.get("success")) ? "已读取本地音乐偏好摘要。" : "音乐偏好摘要暂不可用。";
        }
        Object count = result.get("count");
        if (count != null) {
            return "已返回 " + count + " 条结果。";
        }
        return "音乐能力调用已完成。";
    }

    private String toolTitle(String toolName) {
        return switch (toolName) {
            case "search_songs" -> "搜索候选歌曲";
            case "get_user_music_profile" -> "读取音乐偏好";
            case "get_song_detail" -> "读取歌曲详情";
            case "get_lyrics" -> "读取歌词";
            case "get_hot_comments" -> "读取热门评论";
            case "get_user_playlists" -> "读取歌单";
            case "get_playlist_songs" -> "读取歌单歌曲";
            default -> "调用音乐能力";
        };
    }

    private String safeSummary(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cleaned = value
                .replaceAll("(?i)(token|cookie|credential|systemPrompt|prompt|rawToolResult|rawMusicGene)=[^\\s,;]+", "$1=[redacted]")
                .strip();
        if (cleaned.length() <= SUMMARY_LIMIT) {
            return cleaned;
        }
        return cleaned.substring(0, SUMMARY_LIMIT) + "...";
    }

    private String recommendationResolveSummary(List<String> resolvedTitles, List<String> unresolvedTitles, int resolvedCount, int unresolvedCount) {
        StringBuilder builder = new StringBuilder("已匹配 ")
                .append(resolvedCount)
                .append(" 首");
        if (!resolvedTitles.isEmpty()) {
            builder.append("：").append(String.join("、", resolvedTitles));
        }
        builder.append("；未匹配 ").append(unresolvedCount).append(" 首");
        if (!unresolvedTitles.isEmpty()) {
            builder.append("：").append(String.join("、", unresolvedTitles));
        }
        builder.append("。");
        return builder.toString();
    }

    private List<String> safeTitles(List<String> titles) {
        if (titles == null || titles.isEmpty()) {
            return List.of();
        }
        return titles.stream()
                .filter(title -> title != null && !title.isBlank())
                .map(String::strip)
                .limit(8)
                .toList();
    }

    private String stripSentence(String value) {
        return value.strip()
                .replaceAll("[。！？!?，,；;：:]+$", "")
                .replaceAll("\\s+", "");
    }

    private boolean isSmallTalk(String normalized) {
        return normalized.length() <= 8 && (
                normalized.equals("谢谢")
                        || normalized.equals("好的")
                        || normalized.equals("好")
                        || normalized.equals("可以")
                        || normalized.equals("收到")
                        || normalized.equals("嗯")
                        || normalized.equals("ok")
                        || normalized.equals("hello")
                        || normalized.equals("hi")
                        || normalized.equals("你好")
        );
    }
}
