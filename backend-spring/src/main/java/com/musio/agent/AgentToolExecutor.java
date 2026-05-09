package com.musio.agent;

import com.musio.agent.capability.MusicReadCapabilityHandler;
import com.musio.tools.MusicReadTools;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class AgentToolExecutor {
    private final MusicReadCapabilityHandler readCapabilities;

    @Autowired
    public AgentToolExecutor(MusicReadCapabilityHandler readCapabilities) {
        this.readCapabilities = readCapabilities;
    }

    public AgentToolExecutor(MusicReadTools musicReadTools) {
        this(new MusicReadCapabilityHandler(musicReadTools));
    }

    public List<AgentToolExecution> execute(AgentToolPlan plan) {
        if (plan == null || !plan.hasCalls()) {
            return List.of();
        }
        List<AgentToolExecution> executions = new ArrayList<>();
        for (AgentToolCall call : plan.toolCalls()) {
            execute(call).ifPresent(executions::add);
        }
        return executions;
    }

    public Optional<String> executeTool(String toolName, Map<String, Object> arguments) {
        return readCapabilities.execute(null, toolName, arguments);
    }

    public boolean supports(String toolName) {
        return readCapabilities.supports(toolName);
    }

    private Optional<AgentToolExecution> execute(AgentToolCall call) {
        if (call == null || call.toolName() == null || call.toolName().isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> arguments = call.arguments() == null ? Map.of() : call.arguments();
        return executeTool(call.toolName(), arguments)
                .map(result -> new AgentToolExecution(call.toolName(), arguments, result));
    }
}
