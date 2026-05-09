package com.musio.agent.loop;

public record AgentLoopOutcome(
        AgentLoopOutcomeType type,
        AgentLoopState finalState,
        AgentLoopEvidence evidence,
        String reason
) {
    public AgentLoopOutcome {
        type = type == null ? AgentLoopOutcomeType.FAILED : type;
        evidence = evidence == null ? AgentLoopEvidence.empty() : evidence;
        reason = reason == null ? "" : reason.strip();
    }

    public boolean hasObservations() {
        return evidence.hasObservations();
    }
}
