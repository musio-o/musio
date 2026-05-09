package com.musio.agent.capability;

public record AgentCapabilityValidationResult(boolean valid, String reason) {
    public AgentCapabilityValidationResult {
        reason = reason == null ? "" : reason.strip();
    }

    public static AgentCapabilityValidationResult accepted() {
        return new AgentCapabilityValidationResult(true, "");
    }

    public static AgentCapabilityValidationResult rejected(String reason) {
        return new AgentCapabilityValidationResult(false, reason);
    }
}
