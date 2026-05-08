package com.musio.agent;

import com.musio.agent.capability.AgentCapabilityRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPolicyGateTest {
    private final AgentPolicyGate policyGate = new AgentPolicyGate(new AgentCapabilityRegistry());

    @Test
    void exposesLocalPlaylistWriteOnlyForExplicitSaveIntent() {
        assertTrue(policyGate.manifestFor("帮我收藏第一首歌到 Musio 歌单", null)
                .allows(AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST));
        assertTrue(policyGate.manifestFor("帮我收藏李荣浩的不遗憾", null)
                .allows(AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST));
        assertTrue(policyGate.manifestFor("确认收藏", null)
                .allows(AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST));
        assertTrue(policyGate.manifestFor("确认加入歌单", null)
                .allows(AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST));

        assertFalse(policyGate.manifestFor("给我推荐适合深夜听的歌", null)
                .allows(AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST));
    }
}
