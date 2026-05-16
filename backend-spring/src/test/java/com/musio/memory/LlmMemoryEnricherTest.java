package com.musio.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.loop.AgentLoopEvidence;
import com.musio.memory.context.MemoryContextPackage;
import com.musio.model.AgentTaskMemory;
import com.musio.model.ProviderType;
import com.musio.model.Song;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmMemoryEnricherTest {
    private final LlmMemoryEnricher enricher = new LlmMemoryEnricher(null, null, new ObjectMapper());

    @Test
    void parsesStructuredEnrichmentJson() {
        Optional<MemoryEnrichmentResult> result = enricher.parseResult("""
                ```json
                {
                  "preferenceCandidates": [
                    {"polarity":"negative","name":"too_noisy","label":"不想听太吵","confidenceDelta":0.9,"scope":"long_term","evidence":"别太吵"},
                    {"polarity":"positive","name":"temporary_mood","label":"临时心情","confidenceDelta":0.01,"scope":"ignore","evidence":"你好"}
                  ],
                  "conversationSummary": {
                    "summary": "用户明确表达不想听太吵的歌。",
                    "keywords": ["安静", "偏好", "安静"]
                  },
                  "musicInsights": [
                    {"songId":"qqmusic:1","title":"安静","artist":"周杰伦","content":"评论认为这首歌适合慢下来。","evidence":"comments"}
                  ],
                  "confidence": 0.8
                }
                ```
                """);

        assertTrue(result.isPresent());
        MemoryEnrichmentResult value = result.get();
        assertEquals(2, value.preferenceCandidates().size());
        assertEquals("negative", value.preferenceCandidates().getFirst().polarity());
        assertEquals(0.3, value.preferenceCandidates().getFirst().confidenceDelta());
        assertFalse(value.preferenceCandidates().get(1).writable());
        assertEquals("用户明确表达不想听太吵的歌。", value.conversationSummary().summary());
        assertEquals(2, value.conversationSummary().keywords().size());
        assertEquals("llmMusicInsight", value.musicInsights().getFirst().cacheType());
    }

    @Test
    void rejectsLowConfidenceAndInvalidJson() {
        assertTrue(enricher.parseResult("""
                {"preferenceCandidates":[{"name":"too_noisy"}],"confidence":0.2}
                """).isEmpty());
        assertTrue(enricher.parseResult("not json").isEmpty());
        assertTrue(enricher.parseResult("").isEmpty());
    }

    @Test
    void enrichmentPromptIncludesTaskMemoryForReferenceResolution() {
        Song song = new Song("qqmusic:memory", ProviderType.QQMUSIC, "七秒钟的记忆", List.of("徐良", "孙羽幽"), "", 0, "");
        AgentTaskMemory taskMemory = new AgentTaskMemory(
                "local",
                "深夜写代码推荐",
                "为深夜写代码的场景推荐1首适合的歌",
                "",
                null,
                List.of(song),
                List.of(song.title()),
                List.of(),
                List.of(),
                song,
                "recommend",
                List.of("recommend_songs 成功，已生成并精确匹配 1 首推荐歌曲：七秒钟的记忆 - 徐良/孙羽幽"),
                List.of("RECOMMENDATION"),
                List.of(),
                List.of("recommend_songs"),
                List.of(),
                List.of(),
                null,
                Instant.parse("2026-05-13T10:00:00Z")
        );
        Prompt prompt = enricher.buildPrompt(new MemoryWriteRequest(
                "local",
                "我很喜欢这个场景的歌",
                null,
                MemoryContextPackage.empty(),
                AgentLoopEvidence.empty(),
                taskMemory,
                "下次夜里写代码的时候，我再帮你挑几首适合这个氛围的歌。",
                Instant.parse("2026-05-13T10:01:00Z")
        ));
        String promptText = prompt.getInstructions().stream()
                .map(message -> message.getText() == null ? "" : message.getText())
                .reduce("", (left, right) -> left + "\n" + right);

        assertTrue(promptText.contains("短期任务记忆"));
        assertTrue(promptText.contains("深夜写代码"));
        assertTrue(promptText.contains("七秒钟的记忆"));
        assertTrue(promptText.contains("这个场景"));
        assertTrue(promptText.contains("不要输出“场景未明确”"));
    }
}
