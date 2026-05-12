package com.musio.providers.qqmusic;

import com.musio.agent.capability.CapabilityEffect;
import com.musio.model.ProviderType;
import com.musio.model.Song;
import com.musio.model.SourceContext;
import com.musio.providers.SourceCapability;
import com.musio.providers.SourceManifest;
import com.musio.providers.SourceToolCall;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QQMusicProviderSourceProviderTest {
    @Test
    void readsEnabledCapabilitiesFromSidecarManifest() {
        QQMusicProvider provider = new QQMusicProvider(null, new StubSidecarClient(false));

        List<SourceCapability> capabilities = provider.capabilities(SourceContext.defaultContext());

        assertEquals(List.of("search_songs"), capabilities.stream().map(SourceCapability::name).toList());
    }

    @Test
    void hidesReadCapabilitiesWhenManifestIsUnavailableByDefault() {
        QQMusicProvider provider = new QQMusicProvider(null, new StubSidecarClient(true, false));

        List<SourceCapability> capabilities = provider.capabilities(SourceContext.defaultContext());

        assertTrue(capabilities.isEmpty());
    }

    @Test
    void fallsBackToDefaultReadCapabilitiesWhenCompatibilityFallbackIsEnabled() {
        QQMusicProvider provider = new QQMusicProvider(null, new StubSidecarClient(true, true));

        List<SourceCapability> capabilities = provider.capabilities(SourceContext.defaultContext());

        assertTrue(capabilities.stream().anyMatch(capability -> "search_songs".equals(capability.name())));
        assertTrue(capabilities.stream().anyMatch(capability -> "get_hot_comments".equals(capability.name())));
    }

    @Test
    void executesGenericSourceToolThroughSidecar() {
        StubSidecarClient sidecarClient = new StubSidecarClient(false);
        QQMusicProvider provider = new QQMusicProvider(null, sidecarClient);

        Map<String, Object> result = provider.execute(new SourceToolCall(
                "qqmusic",
                "search_songs",
                Map.of("keyword", "周杰伦", "limit", 1)
        ), SourceContext.defaultContext());

        assertEquals("search_songs", sidecarClient.lastToolName);
        assertEquals("songs", result.get("resultType"));
        @SuppressWarnings("unchecked")
        List<Song> songs = (List<Song>) result.get("songs");
        assertEquals("晴天", songs.getFirst().title());
    }

    private static final class StubSidecarClient extends QQMusicSidecarClient {
        private final boolean failManifest;
        private final boolean allowStaticManifestFallback;
        private String lastToolName;

        private StubSidecarClient(boolean failManifest) {
            this(failManifest, false);
        }

        private StubSidecarClient(boolean failManifest, boolean allowStaticManifestFallback) {
            super(null);
            this.failManifest = failManifest;
            this.allowStaticManifestFallback = allowStaticManifestFallback;
        }

        @Override
        public boolean allowStaticManifestFallback() {
            return allowStaticManifestFallback;
        }

        @Override
        public SourceManifest manifest() {
            if (failManifest) {
                throw new IllegalStateException("sidecar unavailable");
            }
            return new SourceManifest(
                    "qqmusic",
                    "QQ 音乐",
                    List.of(
                            new SourceCapability(
                                    "search_songs",
                                    CapabilityEffect.READ,
                                    "搜索歌曲",
                                    Map.of("keyword", "string", "limit", "number"),
                                    Set.of("keyword", "limit"),
                                    true,
                                    "",
                                    "songs"
                            ),
                            new SourceCapability(
                                    "disabled_tool",
                                    CapabilityEffect.READ,
                                    "disabled",
                                    Map.of(),
                                    Set.of(),
                                    false,
                                    "disabled",
                                    "generic"
                            )
                    )
            );
        }

        @Override
        public Map<String, Object> executeTool(SourceToolCall call) {
            lastToolName = call.toolName();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("sourceId", "qqmusic");
            result.put("toolName", call.toolName());
            result.put("resultType", "songs");
            result.put("count", 1);
            result.put("songs", List.of(new Song("qqmusic:1", ProviderType.QQMUSIC, "晴天", List.of("周杰伦"), "叶惠美", 269, null)));
            return result;
        }
    }
}
