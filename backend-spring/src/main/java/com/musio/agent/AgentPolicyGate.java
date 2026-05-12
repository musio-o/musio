package com.musio.agent;

import com.musio.agent.capability.AgentCapability;
import com.musio.agent.capability.AgentCapabilityManifest;
import com.musio.agent.capability.AgentCapabilityRegistry;
import com.musio.agent.capability.CapabilityEffect;
import com.musio.model.MusicGeneState;
import com.musio.model.ProviderStatus;
import com.musio.model.SourceContext;
import com.musio.providers.ProviderStatusService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class AgentPolicyGate {
    private static final String USER_MUSIC_PROFILE_TOOL = "get_user_music_profile";

    private final AgentCapabilityRegistry registry;
    private final ProviderStatusService providerStatusService;

    public AgentPolicyGate(AgentCapabilityRegistry registry) {
        this(registry, null);
    }

    @Autowired
    public AgentPolicyGate(AgentCapabilityRegistry registry, ProviderStatusService providerStatusService) {
        this.registry = registry;
        this.providerStatusService = providerStatusService;
    }

    public AgentCapabilityManifest manifestFor(String userMessage, AgentTurnPlan turnPlan) {
        return manifestFor(userMessage, turnPlan, AgentRunContext.sourceContextOrDefault());
    }

    public AgentCapabilityManifest manifestFor(String userMessage, AgentTurnPlan turnPlan, SourceContext sourceContext) {
        AgentCapabilityManifest manifest = registry.manifest(allowsLocalPlaylistWrite(userMessage, turnPlan));
        return filterForSource(manifest, sourceContext);
    }

    public AgentCapabilityManifest manifestFor(AgentGoal goal, AgentTurnPlan turnPlan) {
        return manifestFor(goal, turnPlan, AgentRunContext.sourceContextOrDefault());
    }

    public AgentCapabilityManifest manifestFor(AgentGoal goal, AgentTurnPlan turnPlan, SourceContext sourceContext) {
        String userMessage = goal == null ? "" : goal.userMessage();
        boolean allowLocalWrite = goal != null && goal.localWriteIntent();
        AgentCapabilityManifest manifest = registry.manifest(allowLocalWrite || allowsLocalPlaylistWrite(userMessage, turnPlan));
        return filterForSource(manifest, sourceContext);
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

    private AgentCapabilityManifest filterForSource(AgentCapabilityManifest manifest, SourceContext sourceContext) {
        if (manifest == null || manifest.isEmpty() || providerStatusService == null) {
            return manifest == null ? AgentCapabilityManifest.empty() : manifest;
        }
        SourceContext context = sourceContext == null ? SourceContext.defaultContext() : sourceContext;
        ProviderStatus status = sourceStatus(context);
        List<AgentCapability> allowed = manifest.capabilities().stream()
                .filter(capability -> allowedForSource(capability, context, status))
                .toList();
        return new AgentCapabilityManifest(allowed);
    }

    private ProviderStatus sourceStatus(SourceContext context) {
        try {
            return providerStatusService.status(context.activeProviderType());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean allowedForSource(AgentCapability capability, SourceContext context, ProviderStatus status) {
        if (capability == null || capability.effect() != CapabilityEffect.READ) {
            return true;
        }
        if (!sourceReady(context, status)) {
            return false;
        }
        if (USER_MUSIC_PROFILE_TOOL.equals(capability.name())) {
            return profileReady(status);
        }
        return true;
    }

    private boolean sourceReady(SourceContext context, ProviderStatus status) {
        if (!context.selects(context.activeSource())) {
            return false;
        }
        return status != null && status.available() && status.authenticated();
    }

    private boolean profileReady(ProviderStatus status) {
        if (status == null || status.musicGeneStatus() == null) {
            return false;
        }
        return status.musicGeneStatus().state() == MusicGeneState.READY;
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
