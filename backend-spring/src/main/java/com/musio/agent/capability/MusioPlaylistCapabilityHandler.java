package com.musio.agent.capability;

import com.musio.agent.loop.AgentLoopState;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@Order(10)
public class MusioPlaylistCapabilityHandler implements AgentCapabilityHandler {
    private static final AgentCapability ADD_SONG = new AgentCapability(
            AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST,
            CapabilityEffect.LOCAL_WRITE,
            "把一首或多首歌曲收藏到本地 Musio 默认歌单；这是 Musio 本地歌单写入，不是 QQ 音乐账号收藏。",
            "{\"playlistId\": string, \"songId\": string, \"songIds\": string[], \"songTitle\": string, \"artist\": string, \"songIndex\": number, \"songIndexes\": number[]}",
            Set.of()
    );

    private final MusioPlaylistCapabilityExecutor executor;

    public MusioPlaylistCapabilityHandler(MusioPlaylistCapabilityExecutor executor) {
        this.executor = executor;
    }

    @Override
    public List<AgentCapability> capabilities() {
        return List.of(ADD_SONG);
    }

    @Override
    public boolean supports(String capabilityName) {
        return ADD_SONG.name().equals(capabilityName);
    }

    @Override
    public Map<String, Object> normalizeArguments(
            String capabilityName,
            Map<String, Object> arguments,
            AgentCapabilityArgumentContext context
    ) {
        if (!supports(capabilityName)) {
            return arguments == null ? Map.of() : Map.copyOf(arguments);
        }
        return AgentCapabilityArgumentRules.normalizeKnownCapability(capabilityName, arguments, context);
    }

    @Override
    public AgentCapabilityValidationResult validateArguments(
            String capabilityName,
            Map<String, Object> arguments,
            AgentCapabilityArgumentContext context
    ) {
        if (!supports(capabilityName)) {
            return AgentCapabilityValidationResult.rejected("unknown_tool");
        }
        return AgentCapabilityArgumentRules.validateKnownCapability(capabilityName, arguments == null ? Map.of() : arguments, context);
    }

    @Override
    public AgentCapabilityValidationResult validate(AgentLoopState state, String capabilityName, Map<String, Object> arguments) {
        if (!supports(capabilityName)) {
            return AgentCapabilityValidationResult.rejected("unknown_tool");
        }
        return AgentCapabilityArgumentRules.validateMusioPlaylistRequiredArguments(arguments == null ? Map.of() : arguments);
    }

    @Override
    public Optional<String> execute(AgentLoopState state, String capabilityName, Map<String, Object> arguments) {
        if (!supports(capabilityName)) {
            return Optional.empty();
        }
        return Optional.of(executor.executeAddSongToMusioPlaylist(state, arguments == null ? Map.of() : arguments));
    }

}
