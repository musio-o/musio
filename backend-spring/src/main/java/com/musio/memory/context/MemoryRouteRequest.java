package com.musio.memory.context;

import com.musio.agent.AgentGoal;
import com.musio.agent.ConversationHistoryMessage;
import com.musio.model.AgentTaskMemory;

import java.util.List;

public record MemoryRouteRequest(
        String userId,
        String userMessage,
        String taskType,
        String contextMode,
        String effectiveRequest,
        AgentGoal goal,
        AgentTaskMemory taskMemory,
        List<ConversationHistoryMessage> recentHistory
) {
    public MemoryRouteRequest {
        userId = userId == null ? "" : userId.strip();
        userMessage = userMessage == null ? "" : userMessage.strip();
        taskType = taskType == null ? "" : taskType.strip();
        contextMode = contextMode == null ? "" : contextMode.strip();
        effectiveRequest = effectiveRequest == null ? "" : effectiveRequest.strip();
        recentHistory = recentHistory == null ? List.of() : List.copyOf(recentHistory);
    }
}
