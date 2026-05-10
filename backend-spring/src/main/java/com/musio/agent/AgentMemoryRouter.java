package com.musio.agent;

import com.musio.agent.recommendation.RecommendationSlot;
import com.musio.model.AgentTaskMemory;
import com.musio.model.AgentTaskRecommendationSlot;
import com.musio.model.Song;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
class AgentMemoryRouter {
    Optional<AgentTurnPlan> repairPlan(String userMessage, AgentTurnPlan planned, AgentTaskMemory memory) {
        if (!shouldConsiderRepair(userMessage, planned, memory)) {
            return Optional.empty();
        }
        MatchedSong matchedSong = matchSong(userMessage, memory).orElse(null);
        if (matchedSong == null) {
            return Optional.empty();
        }
        AgentTaskRecommendationSlot sourceSlot = matchedSong.slot();
        RecommendationSlot replacementSlot = replacementSlot(sourceSlot, matchedSong.song());
        List<String> avoidTitles = avoidTitles(memory, matchedSong.title());
        List<AgentRequiredOutcome> outcomes = repairOutcomes(memory.lastRequiredOutcomes());
        String effectiveRequest = repairRequest(memory, matchedSong.title(), replacementSlot, outcomes);
        return Optional.of(new AgentTurnPlan(
                TurnDisposition.USE_TOOLS,
                "recommend",
                "correction",
                effectiveRequest,
                new AgentTurnMemoryUse(
                        true,
                        List.of("lastResultSongs", "lastRecommendationSlots", "lastRequiredOutcomes", "avoidSongTitles", "lastEvidenceTools"),
                        "用户在纠正上一轮音乐推荐结果，需要读取短期任务记忆并重新执行缺口。"
                ),
                List.of(),
                outcomes,
                List.of(replacementSlot),
                0.92,
                "memory_repair",
                avoidTitles
        ));
    }

    private boolean shouldConsiderRepair(String userMessage, AgentTurnPlan planned, AgentTaskMemory memory) {
        if (memory == null || memory.lastResultSongs().isEmpty()) {
            return false;
        }
        String normalized = normalize(userMessage);
        if (normalized.isBlank() || !hasRepairSignal(normalized)) {
            return false;
        }
        if (planned == null || planned.disposition() == TurnDisposition.RESPOND_ONLY) {
            return true;
        }
        return planned.disposition() == TurnDisposition.USE_TOOLS
                && ("chat".equals(planned.taskType()) || "unknown".equals(planned.taskType()));
    }

    private boolean hasRepairSignal(String normalized) {
        return containsAny(normalized,
                "重复",
                "推荐过",
                "推过",
                "出现过",
                "已经推荐",
                "不是已经",
                "不是给过",
                "换一首",
                "换首",
                "重新推荐",
                "别重复",
                "不要重复");
    }

    private Optional<MatchedSong> matchSong(String userMessage, AgentTaskMemory memory) {
        List<MatchedSong> candidates = new ArrayList<>();
        for (Song song : memory.lastResultSongs()) {
            if (song == null || song.title() == null || song.title().isBlank()) {
                continue;
            }
            AgentTaskRecommendationSlot slot = slotForSong(memory.lastRecommendationSlots(), song);
            candidates.add(new MatchedSong(song.title(), song, slot));
        }
        for (AgentTaskRecommendationSlot slot : memory.lastRecommendationSlots()) {
            for (String title : slot.songTitles()) {
                candidates.add(new MatchedSong(title, null, slot));
            }
        }
        String message = normalize(userMessage);
        return candidates.stream()
                .filter(candidate -> titleMentioned(message, candidate.title()))
                .findFirst();
    }

    private AgentTaskRecommendationSlot slotForSong(List<AgentTaskRecommendationSlot> slots, Song song) {
        if (slots == null || slots.isEmpty() || song == null) {
            return null;
        }
        String songId = safe(song.id());
        String title = safe(song.title());
        for (AgentTaskRecommendationSlot slot : slots) {
            if (!songId.isBlank() && slot.songIds().contains(songId)) {
                return slot;
            }
            if (!title.isBlank() && slot.songTitles().stream().anyMatch(value -> sameTitle(value, title))) {
                return slot;
            }
        }
        return null;
    }

    private RecommendationSlot replacementSlot(AgentTaskRecommendationSlot sourceSlot, Song song) {
        if (sourceSlot != null && !sourceSlot.target().isBlank()) {
            return new RecommendationSlot(sourceSlot.slotId(), sourceSlot.targetType(), sourceSlot.target(), 1);
        }
        String artist = song == null || song.artists() == null || song.artists().isEmpty() ? "其他" : song.artists().getFirst();
        return new RecommendationSlot("", "artist", artist, 1);
    }

    private List<AgentRequiredOutcome> repairOutcomes(List<String> previousOutcomes) {
        LinkedHashSet<AgentRequiredOutcome> outcomes = new LinkedHashSet<>();
        outcomes.add(AgentRequiredOutcome.RECOMMENDATION);
        for (String previous : previousOutcomes == null ? List.<String>of() : previousOutcomes) {
            parseOutcome(previous).ifPresent(outcome -> {
                if (outcome != AgentRequiredOutcome.SEARCH && outcome != AgentRequiredOutcome.ACCOUNT_WRITE) {
                    outcomes.add(outcome);
                }
            });
        }
        return List.copyOf(outcomes);
    }

    private Optional<AgentRequiredOutcome> parseOutcome(String value) {
        String normalized = safe(value).toUpperCase(Locale.ROOT);
        try {
            return normalized.isBlank() ? Optional.empty() : Optional.of(AgentRequiredOutcome.valueOf(normalized));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private List<String> avoidTitles(AgentTaskMemory memory, String matchedTitle) {
        LinkedHashSet<String> titles = new LinkedHashSet<>();
        titles.addAll(memory.avoidSongTitles());
        titles.addAll(memory.lastResultSongTitles());
        if (matchedTitle != null && !matchedTitle.isBlank()) {
            titles.add(matchedTitle.strip());
        }
        return titles.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .limit(20)
                .toList();
    }

    private String repairRequest(
            AgentTaskMemory memory,
            String duplicateTitle,
            RecommendationSlot replacementSlot,
            List<AgentRequiredOutcome> outcomes
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("修正上一轮推荐：用户指出《")
                .append(duplicateTitle)
                .append("》已经推荐过。请重新推荐 1 首不同于这些已返回歌曲的真实歌曲");
        if (!replacementSlot.target().isBlank()) {
            builder.append("，推荐目标仍然是")
                    .append(replacementSlot.target());
        }
        builder.append("。");
        if (memory.lastEffectiveRequest() != null && !memory.lastEffectiveRequest().isBlank()) {
            builder.append("上一轮原始任务是：").append(memory.lastEffectiveRequest()).append("。");
        }
        if (outcomes.contains(AgentRequiredOutcome.COMMENTS)) {
            builder.append("新歌也需要读取热门评论。");
        }
        if (outcomes.contains(AgentRequiredOutcome.LYRICS)) {
            builder.append("新歌也需要读取歌词。");
        }
        if (outcomes.contains(AgentRequiredOutcome.LOCAL_PLAYLIST_WRITE)) {
            builder.append("只有真实写入成功后，才可以说已加入本地 Musio 歌单。");
        }
        return builder.toString();
    }

    private boolean titleMentioned(String normalizedMessage, String title) {
        String normalizedTitle = normalize(title);
        if (normalizedMessage.isBlank() || normalizedTitle.isBlank()) {
            return false;
        }
        if (normalizedMessage.contains(normalizedTitle)) {
            return true;
        }
        return minWindowDistance(normalizedMessage, normalizedTitle) <= fuzzyThreshold(normalizedTitle);
    }

    private int fuzzyThreshold(String normalizedTitle) {
        if (normalizedTitle.length() <= 1) {
            return 0;
        }
        if (normalizedTitle.length() <= 4) {
            return 1;
        }
        return 2;
    }

    private int minWindowDistance(String message, String title) {
        int titleLength = title.length();
        if (message.length() < titleLength) {
            return levenshtein(message, title);
        }
        int best = Integer.MAX_VALUE;
        for (int start = 0; start <= message.length() - titleLength; start++) {
            String window = message.substring(start, start + titleLength);
            best = Math.min(best, levenshtein(window, title));
            if (best == 0) {
                return 0;
            }
        }
        return best;
    }

    private int levenshtein(String left, String right) {
        int[][] dp = new int[left.length() + 1][right.length() + 1];
        for (int i = 0; i <= left.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= right.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }
        return dp[left.length()][right.length()];
    }

    private boolean sameTitle(String left, String right) {
        return normalize(left).equals(normalize(right));
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
        return safe(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[《》<>\\[\\]【】()（）\\s，,。！？!?；;：:\"'“”‘’、]+", "");
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private record MatchedSong(String title, Song song, AgentTaskRecommendationSlot slot) {
    }
}
