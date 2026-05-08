package com.musio.agent.capability;

import java.util.Set;

public record AgentCapability(
        String name,
        CapabilityEffect effect,
        String description,
        String argumentSpec,
        Set<String> requiredArguments
) {
    public AgentCapability {
        name = name == null ? "" : name.strip();
        effect = effect == null ? CapabilityEffect.READ : effect;
        description = description == null ? "" : description.strip();
        argumentSpec = argumentSpec == null ? "{}" : argumentSpec.strip();
        requiredArguments = requiredArguments == null ? Set.of() : Set.copyOf(requiredArguments);
    }

    public String plannerLine() {
        String effectLabel = switch (effect) {
            case READ -> "read";
            case LOCAL_WRITE -> "local_write";
            case ACCOUNT_WRITE -> "account_write";
        };
        return "- %s %s：%s（effect=%s）".formatted(name, argumentSpec, description, effectLabel);
    }
}
