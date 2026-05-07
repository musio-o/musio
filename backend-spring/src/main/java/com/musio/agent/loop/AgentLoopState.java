package com.musio.agent.loop;

import com.musio.agent.ConversationHistoryMessage;
import com.musio.model.AgentTaskMemory;

import java.util.List;

public record AgentLoopState(
        String runId,
        String userId,
        String userMessage,
        List<ConversationHistoryMessage> recentHistory,
        AgentTaskMemory taskMemory,
        List<AgentObservation> observations,
        int stepCount
) {
    public AgentLoopState {
        runId = runId == null ? "" : runId.strip();
        userId = userId == null ? "" : userId.strip();
        userMessage = userMessage == null ? "" : userMessage.strip();
        recentHistory = recentHistory == null ? List.of() : List.copyOf(recentHistory);
        observations = observations == null ? List.of() : List.copyOf(observations);
        stepCount = Math.max(0, stepCount);
    }

    public AgentLoopState withObservation(AgentObservation observation) {
        List<AgentObservation> next = new java.util.ArrayList<>(observations);
        next.add(observation);
        return new AgentLoopState(runId, userId, userMessage, recentHistory, taskMemory, next, stepCount + 1);
    }
}
