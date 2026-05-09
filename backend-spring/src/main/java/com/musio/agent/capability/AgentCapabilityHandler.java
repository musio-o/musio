package com.musio.agent.capability;

import com.musio.agent.loop.AgentLoopState;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface AgentCapabilityHandler {
    List<AgentCapability> capabilities();

    boolean supports(String capabilityName);

    default Map<String, Object> normalizeArguments(
            String capabilityName,
            Map<String, Object> arguments,
            AgentCapabilityArgumentContext context
    ) {
        return arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    default AgentCapabilityValidationResult validateArguments(
            String capabilityName,
            Map<String, Object> arguments,
            AgentCapabilityArgumentContext context
    ) {
        return AgentCapabilityValidationResult.accepted();
    }

    default AgentCapabilityValidationResult validate(AgentLoopState state, String capabilityName, Map<String, Object> arguments) {
        return AgentCapabilityValidationResult.accepted();
    }

    Optional<String> execute(AgentLoopState state, String capabilityName, Map<String, Object> arguments);
}
