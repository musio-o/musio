package com.musio.agent.capability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record AgentCapabilityManifest(List<AgentCapability> capabilities) {
    public AgentCapabilityManifest {
        Map<String, AgentCapability> byName = new LinkedHashMap<>();
        if (capabilities != null) {
            for (AgentCapability capability : capabilities) {
                if (capability != null && !capability.name().isBlank()) {
                    byName.putIfAbsent(capability.name(), capability);
                }
            }
        }
        capabilities = List.copyOf(byName.values());
    }

    public static AgentCapabilityManifest empty() {
        return new AgentCapabilityManifest(List.of());
    }

    public boolean isEmpty() {
        return capabilities.isEmpty();
    }

    public boolean allows(String toolName) {
        return find(toolName).isPresent();
    }

    public boolean allowsLocalWrite() {
        return capabilities.stream().anyMatch(capability -> capability.effect() == CapabilityEffect.LOCAL_WRITE);
    }

    public Optional<AgentCapability> find(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        return capabilities.stream()
                .filter(capability -> toolName.equals(capability.name()))
                .findFirst();
    }

    public List<String> names() {
        return capabilities.stream().map(AgentCapability::name).toList();
    }

    public String plannerToolList() {
        if (capabilities.isEmpty()) {
            return "无可用工具";
        }
        return String.join("\n", capabilities.stream().map(AgentCapability::plannerLine).toList());
    }
}
