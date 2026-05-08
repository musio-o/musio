package com.musio.agent;

import com.musio.agent.capability.AgentCapabilityManifest;
import com.musio.agent.capability.AgentCapabilityRegistry;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class AgentPolicyGate {
    private final AgentCapabilityRegistry registry;

    public AgentPolicyGate(AgentCapabilityRegistry registry) {
        this.registry = registry;
    }

    public AgentCapabilityManifest manifestFor(String userMessage, AgentTurnPlan turnPlan) {
        return registry.manifest(allowsLocalPlaylistWrite(userMessage, turnPlan));
    }

    public boolean allowsLocalPlaylistWrite(String userMessage, AgentTurnPlan turnPlan) {
        if (turnPlan != null && turnPlan.hasTool(AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST)) {
            return true;
        }
        String normalized = normalize(userMessage);
        if (normalized.isBlank()) {
            return false;
        }
        boolean explicitFavorite = containsAny(normalized, "收藏", "保存", "save");
        boolean addVerb = containsAny(normalized, "加入", "添加", "加到", "放进", "存到", "add");
        boolean localPlaylistContext = containsAny(normalized, "musio", "歌单", "这首", "这歌", "这首歌", "第一首", "第二首", "第三首", "某首歌");
        return explicitFavorite || (addVerb && localPlaylistContext);
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
