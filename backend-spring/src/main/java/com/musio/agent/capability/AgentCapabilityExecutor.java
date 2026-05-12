package com.musio.agent.capability;

import com.musio.agent.AgentToolExecutor;
import com.musio.agent.loop.AgentLoopState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class AgentCapabilityExecutor {
    private final List<AgentCapabilityHandler> handlerList;
    private final Map<String, AgentCapabilityHandler> handlers;
    private final AgentToolExecutor readToolExecutor;
    private final MusioPlaylistCapabilityExecutor musioPlaylistCapabilityExecutor;

    @Autowired
    public AgentCapabilityExecutor(List<AgentCapabilityHandler> handlers) {
        this.handlerList = handlers == null ? List.of() : List.copyOf(handlers);
        this.handlers = handlerMap(this.handlerList);
        this.readToolExecutor = null;
        this.musioPlaylistCapabilityExecutor = null;
    }

    public AgentCapabilityExecutor(
            AgentToolExecutor readToolExecutor,
            MusioPlaylistCapabilityExecutor musioPlaylistCapabilityExecutor
    ) {
        this.handlerList = List.of();
        this.handlers = Map.of();
        this.readToolExecutor = readToolExecutor;
        this.musioPlaylistCapabilityExecutor = musioPlaylistCapabilityExecutor;
    }

    public boolean canExecute(String capabilityName) {
        if (handlerFor(capabilityName) != null) {
            return true;
        }
        if (AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST.equals(capabilityName)) {
            return musioPlaylistCapabilityExecutor != null;
        }
        return readToolExecutor != null && readToolExecutor.supports(capabilityName);
    }

    public AgentCapabilityValidationResult validate(AgentLoopState state, String capabilityName, Map<String, Object> arguments) {
        AgentCapabilityHandler handler = handlerFor(capabilityName);
        if (handler != null) {
            return handler.validate(state, capabilityName, arguments);
        }
        if (!canExecute(capabilityName)) {
            return AgentCapabilityValidationResult.rejected("tool_not_executable");
        }
        return fallbackValidate(state, capabilityName, arguments == null ? Map.of() : arguments);
    }

    public Optional<String> execute(AgentLoopState state, String capabilityName, Map<String, Object> arguments) {
        AgentCapabilityHandler handler = handlerFor(capabilityName);
        if (handler != null) {
            return handler.execute(state, capabilityName, arguments);
        }
        if (!canExecute(capabilityName)) {
            return Optional.empty();
        }
        if (AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST.equals(capabilityName)) {
            return Optional.of(musioPlaylistCapabilityExecutor.executeAddSongToMusioPlaylist(state, arguments));
        }
        return readToolExecutor.executeTool(capabilityName, arguments);
    }

    private AgentCapabilityValidationResult fallbackValidate(AgentLoopState state, String capabilityName, Map<String, Object> arguments) {
        if (readToolExecutor != null && readToolExecutor.supports(capabilityName)) {
            return MusicReadCapabilityValidator.validate(state, capabilityName, arguments, true);
        }
        if (AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST.equals(capabilityName)) {
            return AgentCapabilityArgumentRules.validateMusioPlaylistRequiredArguments(arguments);
        }
        return AgentCapabilityValidationResult.accepted();
    }

    private AgentCapabilityHandler handlerFor(String capabilityName) {
        AgentCapabilityHandler handler = handlers.get(capabilityName);
        if (handler != null) {
            return handler;
        }
        if (capabilityName == null || capabilityName.isBlank()) {
            return null;
        }
        for (AgentCapabilityHandler candidate : handlerList) {
            if (candidate != null && candidate.supports(capabilityName)) {
                return candidate;
            }
        }
        return null;
    }

    private Map<String, AgentCapabilityHandler> handlerMap(List<AgentCapabilityHandler> handlers) {
        if (handlers == null || handlers.isEmpty()) {
            return Map.of();
        }
        Map<String, AgentCapabilityHandler> byName = new LinkedHashMap<>();
        for (AgentCapabilityHandler handler : handlers) {
            if (handler == null || handler.capabilities() == null) {
                continue;
            }
            for (AgentCapability capability : handler.capabilities()) {
                if (capability != null && !capability.name().isBlank() && handler.supports(capability.name())) {
                    byName.putIfAbsent(capability.name(), handler);
                }
            }
        }
        return Map.copyOf(byName);
    }
}
