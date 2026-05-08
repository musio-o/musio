package com.musio.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.trace.AgentTracePublisher;
import com.musio.events.AgentEventBus;
import com.musio.memory.MusicProfileService;
import com.musio.model.Comment;
import com.musio.model.LoginStartResult;
import com.musio.model.LoginStatus;
import com.musio.model.Lyrics;
import com.musio.model.MusicProfileMemory;
import com.musio.model.Playlist;
import com.musio.model.ProviderType;
import com.musio.model.Song;
import com.musio.model.SongDetail;
import com.musio.model.SongUrl;
import com.musio.model.UserProfile;
import com.musio.providers.MusicProvider;
import com.musio.providers.MusicProviderGateway;
import com.musio.tools.MusicReadTools;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolExecutorTest {
    @Test
    void executesPlannedDetailAndCommentTools() {
        AgentToolExecutor executor = new AgentToolExecutor(tools());
        AgentToolPlan plan = new AgentToolPlan(List.of(
                new AgentToolCall("get_song_detail", Map.of("songId", "qqmusic:1")),
                new AgentToolCall("get_hot_comments", Map.of("songId", "qqmusic:1", "limit", 1))
        ), 0.9);

        List<AgentToolExecution> executions = executor.execute(plan);

        assertEquals(2, executions.size());
        assertEquals("get_song_detail", executions.getFirst().toolName());
        assertTrue(executions.getFirst().resultJson().contains("晴天"));
        assertEquals("get_hot_comments", executions.get(1).toolName());
        assertTrue(executions.get(1).resultJson().contains("整个故事都在下雨"));
    }

    @Test
    void hotCommentsFiltersGuideCommentsAndSortsByLikedCount() {
        AgentToolExecutor executor = new AgentToolExecutor(tools(new UnsortedCommentsProvider(), null));
        AgentToolPlan plan = new AgentToolPlan(List.of(
                new AgentToolCall("get_hot_comments", Map.of("songId", "qqmusic:1", "limit", 1))
        ), 0.9);

        List<AgentToolExecution> executions = executor.execute(plan);

        assertEquals(1, executions.size());
        String resultJson = executions.getFirst().resultJson();
        assertTrue(resultJson.contains("\"count\":1"));
        assertTrue(resultJson.contains("真正的高赞用户评论"));
        assertFalse(resultJson.contains("@元宝 介绍下这首歌"));
        assertFalse(resultJson.contains("Q音辅导员"));
    }

    @Test
    void executesSearchToolWithExcludedTitles() {
        AgentToolExecutor executor = new AgentToolExecutor(tools());
        AgentToolPlan plan = new AgentToolPlan(List.of(
                new AgentToolCall("search_songs", Map.of("keyword", "周杰伦", "limit", 1, "excludedTitles", List.of("晴天")))
        ), 0.9);

        List<AgentToolExecution> executions = executor.execute(plan);

        assertEquals(1, executions.size());
        assertEquals("search_songs", executions.getFirst().toolName());
        String resultJson = executions.getFirst().resultJson();
        assertTrue(resultJson.contains("\"count\":1"));
        assertTrue(resultJson.contains("枫"));
        assertTrue(resultJson.contains("\"excludedTitles\""));
    }

    @Test
    void executesSearchToolFromTurnPlan() {
        AgentToolExecutor executor = new AgentToolExecutor(tools());
        AgentTurnPlan turnPlan = new AgentTurnPlan(
                TurnDisposition.USE_TOOLS,
                "search",
                "correction",
                "搜索后弦的歌曲",
                new AgentTurnMemoryUse(true, List.of("lastSearchKeyword"), "纠正上一轮关键词"),
                List.of(new AgentToolCall("search_songs", Map.of("keyword", "后弦", "limit", 8))),
                0.92,
                ""
        );

        List<AgentToolExecution> executions = executor.execute(turnPlan.toToolPlan());

        assertEquals(1, executions.size());
        assertEquals("search_songs", executions.getFirst().toolName());
        assertEquals("后弦", executions.getFirst().arguments().get("keyword"));
        assertTrue(executions.getFirst().resultJson().contains("\"songs\""));
    }

    @Test
    void executesRemainingRegisteredReadOnlyTools() {
        AgentToolExecutor executor = new AgentToolExecutor(tools(new FakeMusicProfileService()));
        AgentToolPlan plan = new AgentToolPlan(List.of(
                new AgentToolCall("get_user_music_profile", Map.of()),
                new AgentToolCall("get_lyrics", Map.of("songId", "qqmusic:1")),
                new AgentToolCall("get_user_playlists", Map.of("limit", 1)),
                new AgentToolCall("get_playlist_songs", Map.of("playlistId", "qqmusic:playlist:1", "limit", 1))
        ), 0.9);

        List<AgentToolExecution> executions = executor.execute(plan);

        assertEquals(4, executions.size());
        assertTrue(executions.get(0).resultJson().contains("偏好安静流行"));
        assertTrue(executions.get(1).resultJson().contains("从前从前"));
        assertTrue(executions.get(2).resultJson().contains("我的歌单"));
        assertTrue(executions.get(3).resultJson().contains("夜曲"));
    }

    private MusicReadTools tools() {
        return tools(null);
    }

    private MusicReadTools tools(MusicProfileService musicProfileService) {
        return tools(new FakeProvider(), musicProfileService);
    }

    private MusicReadTools tools(MusicProvider provider, MusicProfileService musicProfileService) {
        AgentEventBus eventBus = new AgentEventBus();
        return new MusicReadTools(
                new MusicProviderGateway(List.of(provider)),
                musicProfileService,
                eventBus,
                new ObjectMapper(),
                new AgentTracePublisher(eventBus),
                null
        );
    }

    private static class FakeProvider implements MusicProvider {
        @Override
        public ProviderType type() {
            return ProviderType.QQMUSIC;
        }

        @Override
        public SongDetail getSongDetail(String songId) {
            return new SongDetail(songId, ProviderType.QQMUSIC, "晴天", List.of("周杰伦"), "叶惠美", 269, null, null);
        }

        @Override
        public List<Comment> getComments(String songId) {
            return List.of(new Comment("comment:1", songId, ProviderType.QQMUSIC, "Cheer", "虽然叫晴天，但整个故事都在下雨、", 251122, Instant.EPOCH));
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
            return List.of(new Playlist("qqmusic:playlist:1", ProviderType.QQMUSIC, "我的歌单", 1, null));
        }

        @Override
        public List<Song> getPlaylistSongs(String playlistId) {
            return List.of(new Song("qqmusic:3", ProviderType.QQMUSIC, "夜曲", List.of("周杰伦"), "十一月的萧邦", 226, null));
        }

        @Override
        public List<Song> searchSongs(String keyword, int limit) {
            return List.of(
                    new Song("qqmusic:0", ProviderType.QQMUSIC, "晴天", List.of("周杰伦"), "叶惠美", 269, null),
                    new Song("qqmusic:2", ProviderType.QQMUSIC, "枫", List.of("周杰伦"), "十一月的萧邦", 275, null)
            ).stream().limit(limit).toList();
        }

        @Override
        public SongUrl getSongUrl(String songId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Lyrics getLyrics(String songId) {
            return new Lyrics(songId, ProviderType.QQMUSIC, "从前从前有个人爱你很久", null);
        }
    }

    private static class UnsortedCommentsProvider extends FakeProvider {
        @Override
        public List<Comment> getComments(String songId) {
            return List.of(
                    new Comment("comment:guide", songId, ProviderType.QQMUSIC, "Q音辅导员", "@元宝 介绍下这首歌", 999999, Instant.EPOCH),
                    new Comment("comment:low", songId, ProviderType.QQMUSIC, "UserA", "普通评论", 10, Instant.EPOCH),
                    new Comment("comment:top", songId, ProviderType.QQMUSIC, "UserB", "真正的高赞用户评论", 2000, Instant.EPOCH)
            );
        }
    }

    private static final class FakeMusicProfileService extends MusicProfileService {
        private FakeMusicProfileService() {
            super(null, null, null);
        }

        @Override
        public Optional<MusicProfileMemory> readOrCreate() {
            return Optional.of(new MusicProfileMemory(
                    ProviderType.QQMUSIC,
                    "qqmusic_local",
                    "local",
                    Instant.EPOCH,
                    Instant.EPOCH,
                    "偏好安静流行",
                    List.of("华语流行"),
                    List.of("周杰伦"),
                    List.of("叶惠美"),
                    List.of("晴天"),
                    List.of("深夜低干扰"),
                    List.of(),
                    Map.of()
            ));
        }
    }
}
