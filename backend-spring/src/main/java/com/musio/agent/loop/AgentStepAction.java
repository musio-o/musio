package com.musio.agent.loop;

import java.util.LinkedHashMap;
import java.util.Map;

public record AgentStepAction(
        AgentStepActionType action,
        String toolName,
        Map<String, Object> arguments,
        String publicActivity,
        double confidence,
        String reason
) {
    public AgentStepAction {
        action = action == null ? AgentStepActionType.UNSUPPORTED : action;
        toolName = toolName == null ? "" : toolName.strip();
        arguments = arguments == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(arguments));
        publicActivity = publicActivity == null ? "" : publicActivity.strip();
        reason = reason == null ? "" : reason.strip();
    }

    public static AgentStepAction finalAnswer(String reason, double confidence) {
        return new AgentStepAction(AgentStepActionType.FINAL_ANSWER, "", Map.of(), "整理回答", confidence, reason);
    }
}
