package com.musio.agent.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.AgentRunContext;
import com.musio.agent.trace.AgentTracePublisher;
import com.musio.events.AgentEventBus;
import com.musio.memory.AgentTaskMemoryService;
import com.musio.model.AgentEvent;
import com.musio.model.AgentRecentRecommendedSong;
import com.musio.model.AgentTaskMemory;
import com.musio.model.Comment;
import com.musio.model.LoginStartResult;
import com.musio.model.LoginStatus;
import com.musio.model.Lyrics;
import com.musio.model.Playlist;
import com.musio.model.ProviderType;
import com.musio.model.Song;
import com.musio.model.SongDetail;
import com.musio.model.SongUrl;
import com.musio.model.UserProfile;
import com.musio.providers.MusicProvider;
import com.musio.providers.MusicProviderGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommendationOrchestratorTest {
    private final AgentEventBus eventBus = new AgentEventBus();

    @AfterEach
    void clearRunContext() {
        AgentRunContext.clear();
    }

    @Test
    void rendersAnswerAndSongsFromResolvedRecommendationResult() {
        List<AgentEvent> events = subscribe("run-recommend");
        RecordingTaskMemoryService taskMemory = new RecordingTaskMemoryService();
        RecommendationOrchestrator orchestrator = new RecommendationOrchestrator(
                new StubDraftGenerator(Optional.of(new RecommendationDraft(List.of(
                        new RecommendationCandidate("安静", "周杰伦", "钢琴和慢速旋律适合深夜专注。"),
                        new RecommendationCandidate("不存在的歌", "不存在的歌手", "这首会无法匹配。")
                ), 0.9, "test"))),
                new SongResolver(new MusicProviderGateway(List.of(new FakeProvider()))),
                new AgentTracePublisher(eventBus),
                taskMemory
        );
        AgentRunContext.setRunId("run-recommend");
        AgentRunContext.setUserId("local");
        AgentRunContext.setTraceEnabled(true);

        RecommendationResponse response = orchestrator.recommend(null, "给我推荐 2 首适合深夜写代码听的歌", 2, List.of(), AgentTaskMemory.empty("local"));

        assertEquals(1, response.songs().size());
        assertEquals("安静", response.songs().getFirst().title());
        assertTrue(response.answerText().contains("《安静》- 周杰伦"));
        assertFalse(response.answerText().contains("不存在的歌》"));
        assertEquals(List.of("安静"), taskMemory.songTitles);
        assertEquals(List.of("安静"), taskMemory.recentRecommendationTitles);
        assertTrue(events.stream().anyMatch(event -> "trace_step".equals(event.type())));
        AgentEvent resolveTrace = events.stream()
                .filter(event -> "trace_step".equals(event.type()))
                .filter(event -> "tool.recommendation-resolve".equals(event.data().get("stepId")))
                .filter(event -> "done".equals(event.data().get("status")))
                .findFirst()
                .orElseThrow();
        assertEquals("tool", resolveTrace.data().get("stage"));
        assertTrue(((String) resolveTrace.data().get("summary")).contains("不存在的歌 - 不存在的歌手"));
        assertTrue(events.stream().anyMatch(event ->
                "tool_start".equals(event.type()) && "recommendation_resolve".equals(event.data().get("tool"))
        ));
        assertTrue(events.stream().anyMatch(event ->
                "tool_result".equals(event.type())
                        && "recommendation_resolve".equals(event.data().get("tool"))
                        && ((String) event.data().get("summary")).contains("不存在的歌 - 不存在的歌手")
        ));
    }

    @Test
    void doesNotReturnSongCardsWhenDraftIsUnavailable() {
        RecordingTaskMemoryService taskMemory = new RecordingTaskMemoryService();
        RecommendationOrchestrator orchestrator = new RecommendationOrchestrator(
                new StubDraftGenerator(Optional.empty()),
                new SongResolver(new MusicProviderGateway(List.of(new FakeProvider()))),
                new AgentTracePublisher(eventBus),
                taskMemory
        );

        RecommendationResponse response = orchestrator.recommend(null, "给我推荐歌", 5, List.of(), AgentTaskMemory.empty("local"));

        assertTrue(response.songs().isEmpty());
        assertTrue(taskMemory.songTitles.isEmpty());
        assertTrue(response.answerText().contains("不展示可能不准确的歌曲卡片"));
    }

    private List<AgentEvent> subscribe(String runId) {
        List<AgentEvent> events = new ArrayList<>();
        eventBus.subscribe(runId, events::add);
        return events;
    }

    private static final class StubDraftGenerator extends RecommendationDraftGenerator {
        private final Optional<RecommendationDraft> draft;

        private StubDraftGenerator(Optional<RecommendationDraft> draft) {
            super(null, new ObjectMapper(), null);
            this.draft = draft;
        }

        @Override
        public Optional<RecommendationDraft> generate(
                com.musio.config.MusioConfig.Ai ai,
                String userRequest,
                int requestedCount,
                List<String> avoidSongTitles,
                AgentTaskMemory taskMemory
        ) {
            return draft;
        }
    }

    private static final class RecordingTaskMemoryService extends AgentTaskMemoryService {
        private List<String> songTitles = List.of();
        private List<String> recentRecommendationTitles = List.of();

        private RecordingTaskMemoryService() {
            super(null);
        }

        @Override
        public AgentTaskMemory recordResultSongs(String userId, List<Song> songs) {
            songTitles = songs.stream().map(Song::title).toList();
            return AgentTaskMemory.empty(userId);
        }

        @Override
        public AgentTaskMemory recordRecentRecommendations(String userId, List<AgentRecentRecommendedSong> recommendations) {
            recentRecommendationTitles = recommendations.stream().map(AgentRecentRecommendedSong::title).toList();
            return AgentTaskMemory.empty(userId);
        }
    }

    private static final class FakeProvider implements MusicProvider {
        @Override
        public ProviderType type() {
            return ProviderType.QQMUSIC;
        }

        @Override
        public List<Song> searchSongs(String keyword, int limit) {
            if ("安静 周杰伦".equals(keyword)) {
                return List.of(new Song("qqmusic:quiet", ProviderType.QQMUSIC, "安静", List.of("周杰伦"), "范特西", 334, null));
            }
            return List.of();
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
