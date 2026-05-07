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
    void buildsFailureObservation() {
        AgentObservation observation = builder.build("step-1", "get_hot_comments", Map.of(), """
                {"success": false, "message": "missing songId"}
                """);

        assertEquals(AgentObservationStatus.FAILURE, observation.status());
        assertTrue(observation.plannerSummary().contains("missing songId"));
    }
}
