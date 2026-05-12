package com.musio.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceContextTest {
    @Test
    void defaultsToQqMusicWhenRequestDoesNotCarrySources() {
        ChatRequest request = new ChatRequest("local", "推荐几首歌", null, null, null);

        SourceContext context = request.sourceContext("local");

        assertEquals(List.of("qqmusic"), context.selectedSources());
        assertEquals("qqmusic", context.activeSource());
        assertEquals(ProviderType.QQMUSIC, context.activeProviderType());
    }

    @Test
    void normalizesSelectedSourcesAndUsesRequestedActiveSource() {
        SourceContext context = new SourceContext(
                List.of("QQ_MUSIC", "netease-cloud-music", "qqmusic"),
                "netease",
                "local"
        );

        assertEquals(List.of("qqmusic", "netease"), context.selectedSources());
        assertEquals("netease", context.activeSource());
        assertEquals(ProviderType.NETEASE, context.activeProviderType());
        assertTrue(context.selects("qq-music"));
    }

    @Test
    void fallsBackToFirstSelectedSourceWhenActiveSourceIsNotSelected() {
        SourceContext context = new SourceContext(List.of("local", "qqmusic"), "netease", "local");

        assertEquals("local", context.activeSource());
        assertEquals(ProviderType.LOCAL, context.activeProviderType());
    }
}
