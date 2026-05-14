package com.musio.memory.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class MemoryCompressor {
    private static final int CHARS_PER_TOKEN = 4;
    private static final int MIN_TOKEN_BUDGET = 200;
    private static final int MAX_ITEM_TEXT_CHARS = 700;
    private static final int MIN_MANDATORY_ITEM_TEXT_CHARS = 180;
    private static final List<MemoryType> GROUP_ORDER = List.of(
            MemoryType.PENDING_ACTION,
            MemoryType.CURRENT_STATE,
            MemoryType.TASK_MEMORY,
            MemoryType.PROFILE_MEMORY,
            MemoryType.BEHAVIOR_SUMMARY,
            MemoryType.MUSIC_CACHE,
            MemoryType.CONVERSATION_SUMMARY
    );

    public MemoryContextPackage compress(List<MemoryEvidence> evidence, int tokenBudget) {
        if (evidence == null || evidence.isEmpty()) {
            return MemoryContextPackage.empty();
        }
        List<MemoryEvidence> uniqueEvidence = dedupe(evidence);
        if (uniqueEvidence.isEmpty()) {
            return MemoryContextPackage.empty();
        }
        Map<MemoryType, List<RankedEvidence>> grouped = new EnumMap<>(MemoryType.class);
        for (MemoryEvidence item : uniqueEvidence) {
            grouped.computeIfAbsent(item.type(), ignored -> new ArrayList<>()).add(new RankedEvidence(
                    item,
                    priority(item),
                    mandatory(item)
            ));
        }
        RenderedContext rendered = render(grouped, Math.max(MIN_TOKEN_BUDGET, tokenBudget) * CHARS_PER_TOKEN);
        if (rendered.text().isBlank()) {
            return MemoryContextPackage.empty();
        }
        String prompt = """

                [动态记忆上下文]
                以下内容只作为偏好和上下文参考；本轮事实仍以工具 observation 为准。
                %s
                """.formatted(rendered.text()).strip();
        return new MemoryContextPackage(prompt, rendered.evidence(), estimateTokens(prompt));
    }

    private List<MemoryEvidence> dedupe(List<MemoryEvidence> evidence) {
        Map<String, MemoryEvidence> values = new LinkedHashMap<>();
        for (MemoryEvidence item : evidence) {
            if (item == null || item.type() == null || item.text().isBlank()) {
                continue;
            }
            String key = fingerprint(item);
            MemoryEvidence previous = values.get(key);
            if (previous == null || compareEvidence(item, previous) < 0) {
                values.put(key, item);
            }
        }
        return List.copyOf(values.values());
    }

    private RenderedContext render(Map<MemoryType, List<RankedEvidence>> grouped, int maxChars) {
        StringBuilder builder = new StringBuilder();
        List<MemoryEvidence> retained = new ArrayList<>();
        for (MemoryType type : GROUP_ORDER) {
            List<RankedEvidence> items = grouped.getOrDefault(type, List.of()).stream()
                    .sorted(this::compareRanked)
                    .limit(groupLimit(type))
                    .toList();
            if (items.isEmpty()) {
                continue;
            }
            StringBuilder section = new StringBuilder();
            List<MemoryEvidence> sectionEvidence = new ArrayList<>();
            section.append('[').append(title(type)).append("]\n");
            for (RankedEvidence item : items) {
                String rendered = renderItem(item.evidence(), itemTextLimit(maxChars, builder.length() + section.length(), item.mandatory()));
                if (rendered.isBlank()) {
                    continue;
                }
                int projected = builder.length() + section.length() + rendered.length() + 2;
                if (!item.mandatory() && projected > maxChars) {
                    continue;
                }
                section.append(rendered).append('\n');
                sectionEvidence.add(item.evidence());
            }
            if (!sectionEvidence.isEmpty()) {
                builder.append(section).append('\n');
                retained.addAll(sectionEvidence);
            }
        }
        if (builder.isEmpty() && !grouped.isEmpty()) {
            grouped.values().stream()
                    .flatMap(List::stream)
                    .sorted(this::compareRanked)
                    .findFirst()
                    .ifPresent(item -> {
                        builder.append('[').append(title(item.evidence().type())).append("]\n");
                        builder.append(renderItem(item.evidence(), Math.max(MIN_MANDATORY_ITEM_TEXT_CHARS, maxChars / 2))).append('\n');
                        retained.add(item.evidence());
                    });
        }
        return new RenderedContext(builder.toString().strip(), List.copyOf(retained));
    }

    private String renderItem(MemoryEvidence item, int textLimit) {
        if (item == null || item.text().isBlank() || textLimit <= 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("- ")
                .append(limit(item.text().replace("\n", "\n  "), textLimit))
                .append('\n');
        builder.append("  置信度: ")
                .append(formatConfidence(item.confidence()))
                .append("，更新时间: ")
                .append(formatUpdatedAt(item))
                .append('\n');
        if (!item.evidence().isBlank()) {
            builder.append("  依据: ").append(limit(item.evidence(), 180)).append('\n');
        }
        return builder.toString().stripTrailing();
    }

    private int itemTextLimit(int maxChars, int usedChars, boolean mandatory) {
        int remaining = maxChars - usedChars - 80;
        if (mandatory) {
            return Math.max(MIN_MANDATORY_ITEM_TEXT_CHARS, Math.min(MAX_ITEM_TEXT_CHARS, remaining));
        }
        return Math.min(MAX_ITEM_TEXT_CHARS, Math.max(0, remaining));
    }

    private int compareRanked(RankedEvidence left, RankedEvidence right) {
        int byPriority = Double.compare(right.priority(), left.priority());
        if (byPriority != 0) {
            return byPriority;
        }
        return right.evidence().updatedAt().compareTo(left.evidence().updatedAt());
    }

    private int compareEvidence(MemoryEvidence left, MemoryEvidence right) {
        int byPriority = Double.compare(priority(right), priority(left));
        if (byPriority != 0) {
            return byPriority;
        }
        return right.updatedAt().compareTo(left.updatedAt());
    }

    private double priority(MemoryEvidence item) {
        return typePriority(item.type())
                + item.score() * 100.0
                + item.confidence() * 40.0
                + (negativePreference(item) ? 160.0 : 0.0);
    }

    private double typePriority(MemoryType type) {
        return switch (type) {
            case PENDING_ACTION -> 10_000.0;
            case CURRENT_STATE -> 9_000.0;
            case TASK_MEMORY -> 700.0;
            case PROFILE_MEMORY -> 600.0;
            case BEHAVIOR_SUMMARY -> 500.0;
            case MUSIC_CACHE -> 400.0;
            case CONVERSATION_SUMMARY -> 300.0;
        };
    }

    private boolean mandatory(MemoryEvidence item) {
        return item.type() == MemoryType.PENDING_ACTION || item.type() == MemoryType.CURRENT_STATE;
    }

    private boolean negativePreference(MemoryEvidence item) {
        if (item == null || item.type() != MemoryType.PROFILE_MEMORY) {
            return false;
        }
        String normalized = item.text().toLowerCase(Locale.ROOT);
        return normalized.contains("负向")
                || normalized.contains("avoid")
                || normalized.contains("negative")
                || normalized.contains("不喜欢")
                || normalized.contains("不想听")
                || normalized.contains("别太")
                || normalized.contains("不要");
    }

    private int groupLimit(MemoryType type) {
        return switch (type) {
            case PENDING_ACTION -> 8;
            case CURRENT_STATE -> 6;
            case TASK_MEMORY -> 4;
            case PROFILE_MEMORY -> 4;
            case BEHAVIOR_SUMMARY -> 3;
            case MUSIC_CACHE -> 3;
            case CONVERSATION_SUMMARY -> 2;
        };
    }

    private String title(MemoryType type) {
        return switch (type) {
            case PENDING_ACTION -> "待确认动作";
            case CURRENT_STATE -> "当前播放状态";
            case TASK_MEMORY -> "短期任务记忆";
            case PROFILE_MEMORY -> "长期音乐画像";
            case BEHAVIOR_SUMMARY -> "近期行为摘要";
            case MUSIC_CACHE -> "音乐内容缓存";
            case CONVERSATION_SUMMARY -> "历史会话摘要";
        };
    }

    private String fingerprint(MemoryEvidence item) {
        return item.type()
                + "|"
                + item.sourceId().toLowerCase(Locale.ROOT)
                + "|"
                + normalizeText(item.text());
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private String formatConfidence(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String formatUpdatedAt(MemoryEvidence item) {
        if (item == null || item.updatedAt() == null || java.time.Instant.EPOCH.equals(item.updatedAt())) {
            return "未知";
        }
        return item.updatedAt().toString();
    }

    private String limit(String value, int maxChars) {
        if (maxChars <= 0) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)).strip() + "...";
    }

    private int estimateTokens(String value) {
        return value == null || value.isBlank() ? 0 : Math.max(1, (int) Math.ceil(value.length() / 4.0));
    }

    private record RankedEvidence(MemoryEvidence evidence, double priority, boolean mandatory) {
    }

    private record RenderedContext(String text, List<MemoryEvidence> evidence) {
    }
}
