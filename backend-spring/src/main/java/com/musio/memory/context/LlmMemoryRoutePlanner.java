package com.musio.memory.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.AgentLlmLogger;
import com.musio.ai.SpringAiChatModelFactory;
import com.musio.config.MusioConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class LlmMemoryRoutePlanner {
    private static final Logger log = LoggerFactory.getLogger(LlmMemoryRoutePlanner.class);
    private static final double MIN_CONFIDENCE = 0.55;

    private final SpringAiChatModelFactory chatModelFactory;
    private final ObjectMapper objectMapper;
    private final MemoryReadPlanValidator validator;

    public LlmMemoryRoutePlanner(
            SpringAiChatModelFactory chatModelFactory,
            ObjectMapper objectMapper,
            MemoryReadPlanValidator validator
    ) {
        this.chatModelFactory = chatModelFactory;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public MemoryReadPlan route(MusioConfig.Ai ai, MemoryRouteRequest request) {
        if (chatModelFactory == null || ai == null || request == null) {
            return MemoryReadPlan.empty();
        }
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(instruction()),
                new UserMessage("""
                        当前用户输入：
                        %s

                        turnPlan：
                        taskType=%s
                        contextMode=%s
                        effectiveRequest=%s

                        agentGoal：
                        requiredOutcomes=%s
                        musicTask=%s
                        localWriteIntent=%s

                        允许读取的 MemoryType 和字段：
                        %s
                        """.formatted(
                        request.userMessage(),
                        request.taskType(),
                        request.contextMode(),
                        request.effectiveRequest(),
                        request.goal() == null ? List.of() : request.goal().requiredOutcomes(),
                        request.goal() != null && request.goal().musicTask(),
                        request.goal() != null && request.goal().localWriteIntent(),
                        allowedFieldsPreview()
                ))
        ));
        try {
            AgentLlmLogger.logRequest("memory_route_planner", ai, prompt);
            String content = chatModelFactory.chatClient(ai)
                    .prompt(prompt)
                    .call()
                    .content();
            AgentLlmLogger.logResponse("memory_route_planner", ai, content);
            return parsePlan(content).orElseGet(MemoryReadPlan::empty);
        } catch (Exception e) {
            log.warn("Memory route planning failed", e);
            return MemoryReadPlan.empty();
        }
    }

    Optional<MemoryReadPlan> parsePlan(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(content.strip()));
            if (root.path("confidence").asDouble(1.0) < MIN_CONFIDENCE) {
                return Optional.empty();
            }
            JsonNode itemsNode = root.path("items");
            if (!itemsNode.isArray()) {
                return Optional.empty();
            }
            List<MemoryReadItem> items = new ArrayList<>();
            for (JsonNode itemNode : itemsNode) {
                parseItem(itemNode).ifPresent(items::add);
            }
            if (items.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new MemoryReadPlan(items, root.path("tokenBudget").asInt(1200)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<MemoryReadItem> parseItem(JsonNode node) {
        if (!node.isObject()) {
            return Optional.empty();
        }
        MemoryType type;
        try {
            type = MemoryType.valueOf(text(node, "type"));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.of(new MemoryReadItem(
                type,
                textArray(node.path("fields")),
                text(node, "query"),
                text(node, "scope"),
                node.path("priority").asInt(50),
                node.path("limit").asInt(1),
                text(node, "reason")
        ));
    }

    private String instruction() {
        return """
                你是 Musio 的 Memory Router。只输出 JSON 对象，不要 markdown，不要解释。
                你只能从允许的 MemoryType 和字段中选择记忆读取建议，不能请求读取任意文件路径。
                不要请求原始行为日志；需要行为时只能读取 BEHAVIOR_SUMMARY。
                不要把临时心情当长期偏好，不要输出 chain-of-thought。

                输出格式：
                {"items":[{"type":"TASK_MEMORY|PROFILE_MEMORY|BEHAVIOR_SUMMARY|MUSIC_CACHE|CONVERSATION_SUMMARY|CURRENT_STATE|PENDING_ACTION","fields":["字段"],"query":"短查询","scope":"session|last_24h|last_7_days|profile|current","priority":0到100,"limit":1到10,"reason":"简短原因"}],"tokenBudget":1200,"confidence":0.0到1.0}
                """;
    }

    private String allowedFieldsPreview() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<MemoryType, Set<String>> entry : validator.allowedFields().entrySet()) {
            builder.append(entry.getKey()).append(": ").append(String.join(", ", entry.getValue())).append('\n');
        }
        return builder.toString().strip();
    }

    private String extractJsonObject(String value) {
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return value.substring(start, end + 1);
        }
        return value;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().strip() : "";
    }

    private List<String> textArray(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText().strip());
            }
        }
        return values;
    }
}
