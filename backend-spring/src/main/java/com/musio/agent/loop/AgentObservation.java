package com.musio.agent.loop;

import com.musio.model.Song;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AgentObservation(
        String stepId,
        String toolName,
        Map<String, Object> arguments,
        AgentObservationStatus status,
        String resultJson,
        String plannerSummary,
        List<Song> songs
) {
    public AgentObservation {
        stepId = stepId == null ? "" : stepId.strip();
        toolName = toolName == null ? "" : toolName.strip();
        arguments = arguments == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(arguments));
        status = status == null ? AgentObservationStatus.SKIPPED : status;
        resultJson = resultJson == null ? "" : resultJson;
        plannerSummary = plannerSummary == null ? "" : plannerSummary.strip();
        songs = songs == null ? List.of() : List.copyOf(songs);
    }
}
