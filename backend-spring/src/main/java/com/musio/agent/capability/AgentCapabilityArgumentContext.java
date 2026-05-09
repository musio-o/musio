package com.musio.agent.capability;

public record AgentCapabilityArgumentContext(
        int requestedSongCount,
        int songIndexMax,
        boolean requireLocalWriteTarget
) {
    public AgentCapabilityArgumentContext {
        requestedSongCount = Math.max(0, requestedSongCount);
        songIndexMax = songIndexMax <= 0 ? 100 : songIndexMax;
    }

    public static AgentCapabilityArgumentContext turnPlanner() {
        return new AgentCapabilityArgumentContext(0, 20, false);
    }

    public static AgentCapabilityArgumentContext stepPlanner(int requestedSongCount) {
        return new AgentCapabilityArgumentContext(requestedSongCount, 100, true);
    }

    public static AgentCapabilityArgumentContext defaultContext() {
        return stepPlanner(0);
    }
}
