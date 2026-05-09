package com.musio.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentObservationBuilderTest {
    private final AgentObservationBuilder builder = new AgentObservationBuilder(new ObjectMapper());

    @Test
    void buildsSearchObservationWithSongIdsInSummary() {
        AgentObservation observation = builder.build("step-1", "search_songs", Map.of("keyword", "周杰伦", "limit", 1), """
                {
                  "success": true,
                  "count": 1,
                  "songs": [
                    {
                      "id": "qqmusic:0",
                      "provider": "QQMUSIC",
                      "title": "晴天",
                      "artists": ["周杰伦"],
                      "album": "叶惠美",
                      "durationSeconds": 269,
                      "artworkUrl": null
                    }
                  ]
                }
                """);

        assertEquals(AgentObservationStatus.SUCCESS, observation.status());
        assertEquals(1, observation.songs().size());
        assertTrue(observation.plannerSummary().contains("qqmusic:0"));
        assertTrue(observation.plannerSummary().contains("晴天"));
    }

    @Test
    void buildsCommentObservationWithCountOnlyForPlannerSummary() {
        AgentObservation observation = builder.build("step-2", "get_hot_comments", Map.of("songId", "qqmusic:0"), """
                {
                  "success": true,
                  "count": 1,
                  "comments": [
                    {
                      "id": "comment:1",
                      "songId": "qqmusic:0",
                      "provider": "QQMUSIC",
                      "authorName": "Cheer",
                      "text": "虽然叫晴天，但整个故事都在下雨、",
                      "likedCount": 251122,
                      "createdAt": "1970-01-01T00:00:00Z"
                    }
                  ]
                }
                """);

        assertEquals(AgentObservationStatus.SUCCESS, observation.status());
        assertEquals("get_hot_comments 成功，评论 1 条", observation.plannerSummary());
        assertTrue(observation.resultJson().contains("整个故事都在下雨"));
    }

    @Test
    void buildsRecommendationObservationWithSlotCoverage() {
        AgentObservation observation = builder.build("step-1", "recommend_songs", Map.of("request", "推荐两首许嵩和一首后弦"), """
                {
                  "success": true,
                  "requestedTotal": 3,
                  "resolvedTotal": 3,
                  "slotResults": [
                    {"slotId": "xusong", "requested": 2, "resolved": 2},
                    {"slotId": "houxian", "requested": 1, "resolved": 1}
                  ],
                  "songs": [
                    {"slotId": "xusong", "id": "qqmusic:x1", "provider": "QQMUSIC", "title": "断桥残雪", "artists": ["许嵩"], "album": "自定义", "durationSeconds": 240, "artworkUrl": null},
                    {"slotId": "xusong", "id": "qqmusic:x2", "provider": "QQMUSIC", "title": "清明雨上", "artists": ["许嵩"], "album": "自定义", "durationSeconds": 240, "artworkUrl": null},
                    {"slotId": "houxian", "id": "qqmusic:h1", "provider": "QQMUSIC", "title": "西厢", "artists": ["后弦"], "album": "自定义", "durationSeconds": 240, "artworkUrl": null}
                  ]
                }
                """);

        assertEquals(AgentObservationStatus.SUCCESS, observation.status());
        assertEquals(3, observation.songs().size());
        assertTrue(observation.plannerSummary().contains("覆盖 3/3"));
        assertTrue(observation.plannerSummary().contains("xusong 2/2"));
        assertTrue(observation.plannerSummary().contains("houxian 1/1"));
    }

    @Test
    void buildsBatchLyricsAndCommentsSummaries() {
        AgentObservation lyrics = builder.build("step-1", "get_lyrics", Map.of("songIds", java.util.List.of("qqmusic:1", "qqmusic:2")), """
                {
                  "success": true,
                  "requestedCount": 2,
                  "count": 2,
                  "lyricsResults": [
                    {"songId": "qqmusic:1", "success": true, "lyrics": {"songId": "qqmusic:1", "provider": "QQMUSIC", "plainText": "A", "syncedText": null}},
                    {"songId": "qqmusic:2", "success": true, "lyrics": {"songId": "qqmusic:2", "provider": "QQMUSIC", "plainText": "B", "syncedText": null}}
                  ]
                }
                """);
        AgentObservation comments = builder.build("step-2", "get_hot_comments", Map.of("songIds", java.util.List.of("qqmusic:1", "qqmusic:2")), """
                {
                  "success": true,
                  "requestedCount": 2,
                  "count": 2,
                  "commentResults": [
                    {"songId": "qqmusic:1", "success": true, "count": 1, "comments": [{"id": "c1", "songId": "qqmusic:1", "provider": "QQMUSIC", "authorName": "A", "text": "a", "likedCount": 1, "createdAt": "1970-01-01T00:00:00Z"}]},
                    {"songId": "qqmusic:2", "success": true, "count": 1, "comments": [{"id": "c2", "songId": "qqmusic:2", "provider": "QQMUSIC", "authorName": "B", "text": "b", "likedCount": 1, "createdAt": "1970-01-01T00:00:00Z"}]}
                  ],
                  "comments": [
                    {"id": "c1", "songId": "qqmusic:1", "provider": "QQMUSIC", "authorName": "A", "text": "a", "likedCount": 1, "createdAt": "1970-01-01T00:00:00Z"},
                    {"id": "c2", "songId": "qqmusic:2", "provider": "QQMUSIC", "authorName": "B", "text": "b", "likedCount": 1, "createdAt": "1970-01-01T00:00:00Z"}
                  ]
                }
                """);

        assertEquals("get_lyrics 成功，已读取歌词 2/2 首", lyrics.plannerSummary());
        assertEquals("get_hot_comments 成功，歌曲 2 首，评论 2 条", comments.plannerSummary());
    }

    @Test
    void buildsFailureObservation() {
        AgentObservation observation = builder.build("step-1", "get_hot_comments", Map.of(), """
                {"success": false, "message": "missing songId"}
                """);

        assertEquals(AgentObservationStatus.FAILURE, observation.status());
        assertTrue(observation.plannerSummary().contains("missing songId"));
    }
}
