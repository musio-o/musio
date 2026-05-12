package com.musio.providers;

import com.musio.agent.AgentRunContext;
import com.musio.model.Comment;
import com.musio.model.LoginStartResult;
import com.musio.model.LoginStatus;
import com.musio.model.Lyrics;
import com.musio.model.Playlist;
import com.musio.model.ProviderType;
import com.musio.model.Song;
import com.musio.model.SongDetail;
import com.musio.model.SongUrl;
import com.musio.model.SourceContext;
import com.musio.model.UserProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MusicProviderGatewaySourceContextTest {
    @AfterEach
    void clearRunContext() {
        AgentRunContext.clear();
    }

    @Test
    void routesDefaultProviderThroughCurrentSourceContext() {
        MusicProviderGateway gateway = new MusicProviderGateway(List.of(
                new SearchProvider(ProviderType.QQMUSIC, "QQ result"),
                new SearchProvider(ProviderType.NETEASE, "NetEase result")
        ));
        AgentRunContext.setSourceContext(new SourceContext(List.of("qqmusic", "netease"), "netease", "local"));

        List<Song> songs = gateway.defaultProvider().searchSongs("周杰伦", 5);

        assertEquals("NetEase result", songs.getFirst().title());
    }

    @Test
    void keepsQqMusicAsCompatibilityFallbackWhenNoContextExists() {
        MusicProviderGateway gateway = new MusicProviderGateway(List.of(
                new SearchProvider(ProviderType.QQMUSIC, "QQ result"),
                new SearchProvider(ProviderType.NETEASE, "NetEase result")
        ));

        List<Song> songs = gateway.defaultProvider().searchSongs("周杰伦", 5);

        assertEquals("QQ result", songs.getFirst().title());
    }

    @Test
    void rejectsActiveSourceWhenProviderIsNotRegistered() {
        MusicProviderGateway gateway = new MusicProviderGateway(List.of(
                new SearchProvider(ProviderType.QQMUSIC, "QQ result")
        ));

        assertThrows(IllegalArgumentException.class, () ->
                gateway.provider(new SourceContext(List.of("netease"), "netease", "local"))
        );
    }

    private static final class SearchProvider implements MusicProvider {
        private final ProviderType type;
        private final String title;

        private SearchProvider(ProviderType type, String title) {
            this.type = type;
            this.title = title;
        }

        @Override
        public ProviderType type() {
            return type;
        }

        @Override
        public List<Song> searchSongs(String keyword, int limit) {
            return List.of(new Song(type.sourceId() + ":1", type, title, List.of("artist"), "album", 180, null));
        }

        @Override
        public LoginStartResult startLogin() {
            throw new UnsupportedOperationException();
        }

        @Override
        public LoginStatus checkLogin(String loginId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserProfile getProfile(String userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Playlist> getPlaylists(String userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Song> getPlaylistSongs(String playlistId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SongDetail getSongDetail(String songId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SongUrl getSongUrl(String songId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Lyrics getLyrics(String songId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Comment> getComments(String songId) {
            throw new UnsupportedOperationException();
        }
    }
}
