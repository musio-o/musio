package com.musio.agent.loop;

import com.musio.agent.ConversationHistoryMessage;
import com.musio.agent.AgentGoal;
import com.musio.agent.capability.AgentCapabilityManifest;
import com.musio.memory.context.MemoryContextPackage;
import com.musio.model.AgentTaskMemory;

import java.util.List;

public record AgentLoopState(
        String runId,
        String userId,
        String userMessage,
        List<ConversationHistoryMessage> recentHistory,
        AgentTaskMemory taskMemory,
        List<AgentObservation> observations,
        int stepCount,
        AgentCapabilityManifest capabilityManifest,
        int requestedSongCount,
        AgentGoal goal,
        MemoryContextPackage memoryContext
) {
    public AgentLoopState {
        runId = runId == null ? "" : runId.strip();
        userId = userId == null ? "" : userId.strip();
        userMessage = userMessage == null ? "" : userMessage.strip();
        recentHistory = recentHistory == null ? List.of() : List.copyOf(recentHistory);
        observations = observations == null ? List.of() : List.copyOf(observations);
        stepCount = Math.max(0, stepCount);
        requestedSongCount = Math.max(0, requestedSongCount);
        goal = goal == null ? new AgentGoal(userMessage, userMessage, "unknown", "new_task", false, false, false, false, requestedSongCount, List.of(), List.of()) : goal;
        memoryContext = memoryContext == null ? MemoryContextPackage.empty() : memoryContext;
    }

    public AgentLoopState(
            String runId,
            String userId,
            String userMessage,
            List<ConversationHistoryMessage> recentHistory,
            AgentTaskMemory taskMemory,
            List<AgentObservation> observations,
            int stepCount,
            AgentCapabilityManifest capabilityManifest,
            int requestedSongCount,
            AgentGoal goal
    ) {
        this(runId, userId, userMessage, recentHistory, taskMemory, observations, stepCount, capabilityManifest, requestedSongCount, goal, MemoryContextPackage.empty());
    }

    public AgentLoopState(
            String runId,
            String userId,
            String userMessage,
            List<ConversationHistoryMessage> recentHistory,
            AgentTaskMemory taskMemory,
            List<AgentObservation> observations,
            int stepCount
    ) {
        this(runId, userId, userMessage, recentHistory, taskMemory, observations, stepCount, null, 0, null, MemoryContextPackage.empty());
    }

    public AgentLoopState(
            String runId,
            String userId,
            String userMessage,
            List<ConversationHistoryMessage> recentHistory,
            AgentTaskMemory taskMemory,
            List<AgentObservation> observations,
            int stepCount,
            AgentCapabilityManifest capabilityManifest
    ) {
        this(runId, userId, userMessage, recentHistory, taskMemory, observations, stepCount, capabilityManifest, 0, null, MemoryContextPackage.empty());
    }

    public AgentLoopState(
            String runId,
            String userId,
            String userMessage,
            List<ConversationHistoryMessage> recentHistory,
            AgentTaskMemory taskMemory,
            List<AgentObservation> observations,
            int stepCount,
            AgentCapabilityManifest capabilityManifest,
            int requestedSongCount
    ) {
        this(runId, userId, userMessage, recentHistory, taskMemory, observations, stepCount, capabilityManifest, requestedSongCount, null, MemoryContextPackage.empty());
    }

    public AgentLoopState withObservation(AgentObservation observation) {
        List<AgentObservation> next = new java.util.ArrayList<>(observations);
        next.add(observation);
        return new AgentLoopState(runId, userId, userMessage, recentHistory, taskMemory, next, stepCount + 1, capabilityManifest, requestedSongCount, goal, memoryContext);
    }
}
