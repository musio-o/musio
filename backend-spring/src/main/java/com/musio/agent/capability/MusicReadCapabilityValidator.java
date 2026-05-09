package com.musio.agent.capability;

import com.musio.agent.loop.AgentLoopState;

import java.util.Map;

final class MusicReadCapabilityValidator {
    private MusicReadCapabilityValidator() {
    }

    static AgentCapabilityValidationResult validate(
            AgentLoopState state,
            String capabilityName,
            Map<String, Object> arguments,
            boolean supported
    ) {
        if (!supported) {
            return AgentCapabilityValidationResult.rejected("unknown_tool");
        }
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        AgentCapabilityValidationResult requiredArguments = AgentCapabilityArgumentRules.validateReadRequiredArguments(capabilityName, safeArguments);
        if (!requiredArguments.valid()) {
            return requiredArguments;
        }
        if ("search_songs".equals(capabilityName)
                && AgentCapabilityStateFacts.searchedKeywords(state).contains(AgentCapabilityStateFacts.normalizedKeyword(AgentCapabilityStateFacts.text(safeArguments, "keyword")))) {
            return AgentCapabilityValidationResult.rejected("search_keyword_already_observed");
        }
        if (requiresSongId(capabilityName)
                && !AgentCapabilityStateFacts.knownSongIds(state).contains(AgentCapabilityStateFacts.text(safeArguments, "songId"))) {
            return AgentCapabilityValidationResult.rejected("song_id_not_observed");
        }
        if (supportsBatchSongIds(capabilityName)) {
            java.util.List<String> songIds = AgentCapabilityStateFacts.songIds(safeArguments);
            if (!songIds.isEmpty() && !AgentCapabilityStateFacts.knownSongIds(state).containsAll(songIds)) {
                return AgentCapabilityValidationResult.rejected("song_id_not_observed");
            }
            if (!songIds.isEmpty() && AgentCapabilityStateFacts.successfulReadSongIds(state, capabilityName).containsAll(songIds)) {
                return AgentCapabilityValidationResult.rejected("tool_result_already_observed");
            }
        }
        if ("get_playlist_songs".equals(capabilityName)
                && !AgentCapabilityStateFacts.knownPlaylistIds(state).contains(AgentCapabilityStateFacts.text(safeArguments, "playlistId"))) {
            return AgentCapabilityValidationResult.rejected("playlist_id_not_observed");
        }
        return AgentCapabilityValidationResult.accepted();
    }

    private static boolean requiresSongId(String capabilityName) {
        return "get_song_detail".equals(capabilityName);
    }

    private static boolean supportsBatchSongIds(String capabilityName) {
        return "get_lyrics".equals(capabilityName) || "get_hot_comments".equals(capabilityName);
    }
}
