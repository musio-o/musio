package com.musio.memory.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class MemoryCompressor {
    public MemoryContextPackage compress(List<MemoryEvidence> evidence, int tokenBudget) {
        if (evidence == null || evidence.isEmpty()) {
            return MemoryContextPackage.empty();
        }
        Map<MemoryType, List<MemoryEvidence>> grouped = new EnumMap<>(MemoryType.class);
        for (MemoryEvidence item : evidence) {
            if (item != null && !item.text().isBlank()) {
                grouped.computeIfAbsent(item.type(), ignored -> new ArrayList<>()).add(item);
            }
        }
        StringBuilder builder = new StringBuilder();
        appendGroup(builder, grouped, MemoryType.PENDING_ACTION, "待确认动作");
        appendGroup(builder, grouped, MemoryType.CURRENT_STATE, "当前播放状态");
        appendGroup(builder, grouped, MemoryType.TASK_MEMORY, "短期任务记忆");
        appendGroup(builder, grouped, MemoryType.PROFILE_MEMORY, "长期音乐画像");
        appendGroup(builder, grouped, MemoryType.BEHAVIOR_SUMMARY, "近期行为摘要");
        appendGroup(builder, grouped, MemoryType.MUSIC_CACHE, "音乐内容缓存");
        appendGroup(builder, grouped, MemoryType.CONVERSATION_SUMMARY, "历史会话摘要");
        String text = limit(builder.toString().strip(), Math.max(200, tokenBudget) * 4);
        if (text.isBlank()) {
            return MemoryContextPackage.empty();
        }
        String prompt = """

                [动态记忆上下文]
                以下内容只作为偏好和上下文参考；本轮事实仍以工具 observation 为准。
                %s
                """.formatted(text).strip();
        return new MemoryContextPackage(prompt, evidence, estimateTokens(prompt));
    }

    private void appendGroup(StringBuilder builder, Map<MemoryType, List<MemoryEvidence>> grouped, MemoryType type, String title) {
        List<MemoryEvidence> items = grouped.getOrDefault(type, List.of());
        if (items.isEmpty()) {
            return;
        }
        builder.append('[').append(title).append("]\n");
        items.stream()
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .limit(type == MemoryType.TASK_MEMORY ? 3 : 2)
                .forEach(item -> {
                    builder.append("- ").append(item.text().replace("\n", "\n  ")).append('\n');
                    if (!item.evidence().isBlank()) {
                        builder.append("  依据: ").append(item.evidence()).append('\n');
                    }
                });
        builder.append('\n');
    }

    private String limit(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)).strip() + "...";
    }

    private int estimateTokens(String value) {
        return value == null || value.isBlank() ? 0 : Math.max(1, (int) Math.ceil(value.length() / 4.0));
    }
}
