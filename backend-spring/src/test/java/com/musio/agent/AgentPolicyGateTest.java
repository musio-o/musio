package com.musio.agent;

import com.musio.agent.capability.AgentCapabilityRegistry;
import com.musio.model.MusicGeneState;
import com.musio.model.MusicGeneStatus;
import com.musio.model.ProviderStatus;
import com.musio.model.ProviderType;
import com.musio.model.SourceContext;
import com.musio.providers.ProviderStatusService;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void hidesSourceBackedReadToolsWhenActiveSourceIsNotAuthenticated() {
        AgentPolicyGate gate = new AgentPolicyGate(
                new AgentCapabilityRegistry(),
                new StubProviderStatusService(status(false))
        );

        var manifest = gate.manifestFor(
                "给我推荐几首歌",
                null,
                new SourceContext(List.of("qqmusic"), "qqmusic", "local")
        );

        assertFalse(manifest.allows(AgentCapabilityRegistry.RECOMMEND_SONGS));
        assertFalse(manifest.allows("search_songs"));
        assertFalse(manifest.allows("get_user_music_profile"));
    }

    @Test
    void keepsSourceBackedReadToolsWhenActiveSourceIsAuthenticated() {
        AgentPolicyGate gate = new AgentPolicyGate(
                new AgentCapabilityRegistry(),
                new StubProviderStatusService(status(true))
        );

        var manifest = gate.manifestFor(
                "给我推荐几首歌",
                null,
                new SourceContext(List.of("qqmusic"), "qqmusic", "local")
        );

        assertTrue(manifest.allows(AgentCapabilityRegistry.RECOMMEND_SONGS));
        assertTrue(manifest.allows("search_songs"));
    }

    @Test
    void hidesUserMusicProfileWhenProfileHasNotBeenGenerated() {
        AgentPolicyGate gate = new AgentPolicyGate(
                new AgentCapabilityRegistry(),
                new StubProviderStatusService(status(true, new MusicGeneStatus(
                        MusicGeneState.MISSING,
                        ProviderType.QQMUSIC,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        "profile_missing",
                        "QQ 音乐已连接，可以为当前账号生成音乐基因。"
                )))
        );

        var manifest = gate.manifestFor(
                "读取我的音乐偏好",
                null,
                new SourceContext(List.of("qqmusic"), "qqmusic", "local")
        );

        assertTrue(manifest.allows("search_songs"));
        assertFalse(manifest.allows("get_user_music_profile"));
    }

    private static ProviderStatus status(boolean authenticated) {
        MusicGeneStatus musicGeneStatus = authenticated
                ? new MusicGeneStatus(
                MusicGeneState.READY,
                ProviderType.QQMUSIC,
                "qqmusic:local",
                "local",
                null,
                null,
                null,
                null,
                true,
                null,
                "QQ 音乐已连接，当前账号音乐基因已生成。"
        )
                : MusicGeneStatus.unavailable(ProviderType.QQMUSIC, "需要扫码登录 QQ 音乐。");
        return status(authenticated, musicGeneStatus);
    }

    private static ProviderStatus status(boolean authenticated, MusicGeneStatus musicGeneStatus) {
        return new ProviderStatus(
                ProviderType.QQMUSIC,
                "QQ 音乐",
                true,
                authenticated,
                authenticated,
                "QR_CODE",
                authenticated ? "QQ 音乐已连接。" : "需要扫码登录 QQ 音乐。",
                authenticated ? "READY" : "NOT_LOGGED_IN",
                musicGeneStatus == null || musicGeneStatus.state() == null ? "UNAVAILABLE" : musicGeneStatus.state().name(),
                musicGeneStatus
        );
    }

    private static final class StubProviderStatusService extends ProviderStatusService {
        private final ProviderStatus status;

        private StubProviderStatusService(ProviderStatus status) {
            super(null, null, null, null);
            this.status = status;
        }

        @Override
        public ProviderStatus status(ProviderType provider) {
            return status;
        }
    }
}
