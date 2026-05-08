package com.musio.agent;

import com.musio.agent.trace.AgentTracePublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.events.AgentEventBus;
import com.musio.model.AgentTaskMemory;
import com.musio.model.ProviderType;
import com.musio.model.Song;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskContextResolverTest {
    private final AgentTaskContextResolver resolver = new AgentTaskContextResolver(
            null,
            new ObjectMapper(),
            new AgentTracePublisher(new AgentEventBus())
    );

    @Test
    void parsesModelAgentFollowUpAsEffectiveRequest() {
        AgentTaskContext context = resolver.parseModelResponse("再试试", """
                {"mode":"agent","taskType":"recommend","followUp":true,"effectiveRequest":"给我推荐 10 首适合深夜听的歌","searchKeyword":"深夜听","searchLimit":10,"avoidSongTitles":["晴天"],"confidence":0.91}
                """).orElseThrow();

        assertEquals("给我推荐 10 首适合深夜听的歌", context.planningMessage());
        assertEquals("深夜听", context.searchKeyword());
        assertEquals(10, context.searchLimit());
        assertEquals("recommend", context.taskType());
        assertEquals(List.of("晴天"), context.avoidSongTitles());
        assertTrue(context.agentTask());
        assertTrue(context.followUp());
        assertTrue(context.recommendationPreludeAllowed());
        assertTrue(context.promptContext().contains("上下文延续请求"));
        assertTrue(context.promptContext().contains("晴天"));
    }

    @Test
    void parsesModelChatAsDirectTask() {
        AgentTaskContext context = resolver.parseModelResponse("谢谢", """
                {"mode":"chat","followUp":false,"effectiveRequest":"","searchKeyword":"","searchLimit":0,"avoidSongTitles":[],"confidence":0.88}
                """).orElseThrow();

        assertEquals("谢谢", context.planningMessage());
        assertEquals("chat", context.taskType());
        assertFalse(context.agentTask());
        assertFalse(context.followUp());
    }

    @Test
    void rejectsLowConfidenceModelDecision() {
        assertTrue(resolver.parseModelResponse("再试试", """
                {"mode":"agent","followUp":true,"effectiveRequest":"给我推荐 10 首适合深夜听的歌","searchKeyword":"深夜听","searchLimit":10,"avoidSongTitles":[],"confidence":0.31}
                """).isEmpty());
    }

    @Test
    void acceptsHighConfidenceAgentDecisionWithoutTraceableGate() {
        AgentTaskContext context = resolver.parseModelResponse("再试试", """
                {"mode":"agent","taskType":"search","followUp":true,"effectiveRequest":"继续刚才那个","searchKeyword":"周杰伦","searchLimit":1,"avoidSongTitles":["晴天"],"confidence":0.90}
                """).orElseThrow();

        assertTrue(context.agentTask());
        assertEquals("search", context.taskType());
        assertEquals("周杰伦", context.searchKeyword());
        assertEquals(List.of("晴天"), context.avoidSongTitles());
    }

    @Test
    void dropsSearchKeywordWhenItContainsAvoidedTitle() {
        AgentTaskContext context = resolver.parseModelResponse("换一首", """
                {"mode":"agent","taskType":"search","followUp":true,"effectiveRequest":"搜索周杰伦的一首歌曲","searchKeyword":"周杰伦 不同于 晴天","searchLimit":1,"avoidSongTitles":["晴天"],"confidence":0.91}
                """).orElseThrow();

        assertEquals("", context.searchKeyword());
        assertEquals(1, context.searchLimit());
        assertEquals("search", context.taskType());
        assertEquals(List.of("晴天"), context.avoidSongTitles());
        assertTrue(context.searchPreludeAllowed());
    }

    @Test
    void parsesCommentFollowUpWithTargetSongWithoutSearchPrelude() {
        AgentTaskContext context = resolver.parseModelResponse("上一首歌有没有什么背景故事，或者最感人的评论可以分享", """
                {"mode":"agent","taskType":"comments","followUp":true,"effectiveRequest":"读取并总结林俊杰《Always Online》的热门评论和歌曲背景","searchKeyword":"","searchLimit":0,"targetSongId":"qqmusic:001ABC","targetSongTitle":"Always Online","avoidSongTitles":[],"confidence":0.91}
                """).orElseThrow();

        assertEquals("comments", context.taskType());
        assertEquals("qqmusic:001ABC", context.targetSongId());
        assertEquals("Always Online", context.targetSongTitle());
        assertEquals("", context.searchKeyword());
        assertFalse(context.searchPreludeAllowed());
        assertFalse(context.recommendationPreludeAllowed());
        assertTrue(context.promptContext().contains("目标歌曲 ID 是：qqmusic:001ABC"));
    }

    @Test
    void treatsSelfDirectedRecommendationAsNewTask() {
        AgentTaskContext context = resolver.parseModelResponse("你自己推荐5首给我", """
                {"mode":"agent","taskType":"recommend","contextMode":"new_task","followUp":false,"memoryAccess":{"useLastSearchKeyword":true,"useLastResultSongs":true,"useAvoidTitles":true,"useToolFailures":true,"reason":"模型错误地想读取旧任务"},"effectiveRequest":"你自己推荐5首给我","searchKeyword":"","searchLimit":5,"avoidSongTitles":[],"confidence":0.91}
                """).orElseThrow();

        assertEquals("你自己推荐5首给我", context.planningMessage());
        assertEquals("recommend", context.taskType());
        assertEquals("", context.searchKeyword());
        assertEquals(5, context.searchLimit());
        assertEquals(List.of(), context.avoidSongTitles());
        assertFalse(context.followUp());
        assertEquals("new_task", context.contextMode());
        assertTrue(context.memoryAccess().none());
        assertTrue(context.agentTask());
    }

    @Test
    void preservesModelTaskTypeWithoutExplicitSongHardcodedOverride() {
        AgentTaskContext context = resolver.parseModelResponse("给我推荐李荣浩的不遗憾", """
                {"mode":"agent","taskType":"recommend","contextMode":"new_task","followUp":false,"memoryAccess":{"useLastSearchKeyword":false,"useLastResultSongs":false,"useAvoidTitles":false,"useToolFailures":false,"reason":"新推荐"},"effectiveRequest":"给我推荐李荣浩的不遗憾","searchKeyword":"","searchLimit":0,"avoidSongTitles":[],"confidence":0.91}
                """).orElseThrow();

        assertEquals("recommend", context.taskType());
        assertEquals("", context.searchKeyword());
        assertEquals("", context.targetSongTitle());
        assertTrue(context.recommendationPreludeAllowed());
        assertFalse(context.searchPreludeAllowed());
        assertTrue(context.memoryAccess().none());
    }

    @Test
    void acceptsColloquialPlayArtistRequestAsModelAgentDecision() {
        AgentTaskContext context = resolver.parseModelResponse("放几首周杰伦的", """
                {"mode":"agent","taskType":"playback","contextMode":"new_task","followUp":false,"memoryAccess":{"useLastSearchKeyword":false,"useLastResultSongs":false,"useAvoidTitles":false,"useToolFailures":false,"reason":"新的开放请求，不依赖之前上下文"},"effectiveRequest":"放几首周杰伦的","searchKeyword":"周杰伦","searchLimit":0,"targetSongId":"","targetSongTitle":"","avoidSongTitles":[],"confidence":0.9}
                """).orElseThrow();

        assertTrue(context.agentTask());
        assertEquals("playback", context.taskType());
        assertEquals("周杰伦", context.searchKeyword());
        assertEquals("new_task", context.contextMode());
        assertFalse(context.followUp());
        assertTrue(context.toolEvidenceExpected());
        assertFalse(context.searchPreludeAllowed());
        assertTrue(context.memoryAccess().none());
    }

    @Test
    void fallbackTreatsExplicitArtistSongRecommendationAsSearchTask() {
        AgentTaskContext context = resolver.resolve(null, "给我推荐李荣浩的不遗憾", List.of(), AgentTaskMemory.empty("local"));

        assertEquals("search", context.taskType());
        assertEquals("李荣浩 不遗憾", context.searchKeyword());
        assertFalse(context.recommendationPreludeAllowed());
        assertTrue(context.searchPreludeAllowed());
    }

    @Test
    void fallsBackToDirectWhenThereIsNoHistory() {
        AgentTaskContext context = resolver.resolve(null, "再试试", List.of(), AgentTaskMemory.empty("local"));

        assertEquals("再试试", context.planningMessage());
        assertFalse(context.agentTask());
    }

    @Test
    void fallsBackToHeuristicAgentForFirstTurnMusicTaskWhenModelUnavailable() {
        AgentTaskContext context = resolver.resolve(null, "搜索周杰伦的一首歌", List.of(), AgentTaskMemory.empty("local"));

        assertEquals("搜索周杰伦的一首歌", context.planningMessage());
        assertEquals("search", context.taskType());
        assertTrue(context.agentTask());
        assertTrue(context.toolEvidenceExpected());
    }

    @Test
    void buildsTaskMemoryPreviewForResolverPrompt() {
        AgentTaskMemory memory = new AgentTaskMemory(
                "local",
                "music-agent-task",
                "搜索周杰伦的一首歌曲",
                "周杰伦",
                1,
                List.of(),
                List.of("晴天"),
                List.of("晴天"),
                List.of(),
                null,
                "",
                List.of(),
                null,
                java.time.Instant.now()
        );

        String preview = resolver.taskMemoryPreview(memory);

        assertTrue(preview.contains("lastSearchKeyword: 周杰伦"));
        assertTrue(preview.contains("lastResultSongTitles: 晴天"));
    }

    @Test
    void buildsTaskMemoryPreviewWithSongRefsForToolFollowUp() {
        AgentTaskMemory memory = new AgentTaskMemory(
                "local",
                "music-agent-task",
                "搜索林俊杰的一首歌",
                "林俊杰",
                1,
                List.of(new Song("qqmusic:001ABC", ProviderType.QQMUSIC, "Always Online", List.of("林俊杰"), "JJ陆", 303, null)),
                List.of("Always Online"),
                List.of(),
                List.of(),
                null,
                "",
                List.of(),
                null,
                java.time.Instant.now()
        );

        String preview = resolver.taskMemoryPreview(memory);

        assertTrue(preview.contains("lastResultSongRefs: Always Online | 林俊杰 | id=qqmusic:001ABC"));
    }

    @Test
    void hidesTaskMemoryFromPlannerForNewRecommendation() {
        AgentTaskMemory memory = new AgentTaskMemory(
                "local",
                "music-agent-task",
                "给我推荐 5 首适合深夜写代码听的歌",
                "深夜写代码",
                5,
                List.of(),
                List.of("信封", "程序员生活"),
                List.of("信封", "程序员生活"),
                List.of(),
                null,
                "",
                List.of(),
                null,
                java.time.Instant.now()
        );
        AgentTaskContext context = AgentTaskContext.agent(
                "你自己推荐5首给我",
                "你自己推荐5首给我",
                "",
                5,
                false,
                List.of(),
                0.9,
                "test",
                "recommend",
                "",
                ""
        );

        assertEquals("无", resolver.plannerTaskMemoryPreview(context, memory));
    }

    @Test
    void keepsTaskMemoryForFollowUpPlannerContext() {
        AgentTaskMemory memory = new AgentTaskMemory(
                "local",
                "music-agent-task",
                "给我推荐 5 首适合深夜写代码听的歌",
                "深夜写代码",
                5,
                List.of(),
                List.of("信封", "程序员生活"),
                List.of("信封", "程序员生活"),
                List.of(),
                null,
                "",
                List.of(),
                null,
                java.time.Instant.now()
        );
        AgentTaskContext context = AgentTaskContext.agent(
                "再试试",
                "给我推荐 5 首适合深夜写代码听的歌",
                "深夜写代码",
                5,
                true,
                List.of("信封", "程序员生活"),
                0.9,
                "test",
                "recommend",
                "",
                ""
        );

        String preview = resolver.plannerTaskMemoryPreview(context, memory);

        assertTrue(preview.contains("lastSearchKeyword: 深夜写代码"));
        assertTrue(preview.contains("avoidSongTitles: 信封、程序员生活"));
    }
}
