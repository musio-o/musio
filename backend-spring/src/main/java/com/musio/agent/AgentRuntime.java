package com.musio.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.ai.SpringAiChatModelFactory;
import com.musio.agent.loop.AgentLoopEvidence;
import com.musio.agent.loop.AgentLoopRunner;
import com.musio.agent.loop.AgentLoopState;
import com.musio.agent.loop.AgentObservation;
import com.musio.agent.loop.AgentStepAction;
import com.musio.agent.loop.AgentStepActionType;
import com.musio.agent.recommendation.RecommendationOrchestrator;
import com.musio.agent.recommendation.RecommendationResponse;
import com.musio.agent.trace.AgentTracePublisher;
import com.musio.config.MusioConfig;
import com.musio.config.MusioConfigService;
import com.musio.events.AgentEventBus;
import com.musio.model.AgentEvent;
import com.musio.model.ChatRequest;
import com.musio.model.MusicProfileMemory;
import com.musio.model.AgentTaskMemory;
import com.musio.model.Song;
import com.musio.memory.AgentTaskMemoryService;
import com.musio.memory.MusicProfileService;
import com.musio.playlists.MusioPlaylist;
import com.musio.playlists.MusioPlaylistService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AgentRuntime {
    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    private final AgentPrompts prompts;
    private final MusioConfigService configService;
    private final SpringAiChatModelFactory chatModelFactory;
    private final MusicProfileService musicProfileService;
    private final AgentEventBus eventBus;
    private final ConversationHistoryService conversationHistoryService;
    private final AgentTaskMemoryService taskMemoryService;
    private final AgentTracePublisher tracePublisher;
    private final AgentTurnPlanner turnPlanner;
    private final AgentToolExecutor toolExecutor;
    private final AgentLoopRunner agentLoopRunner;
    private final RecommendationOrchestrator recommendationOrchestrator;
    private final MusioPlaylistService musioPlaylistService;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService progressExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "musio-agent-progress-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public AgentRuntime(
            AgentPrompts prompts,
            MusioConfigService configService,
            SpringAiChatModelFactory chatModelFactory,
            MusicProfileService musicProfileService,
            AgentEventBus eventBus,
            ConversationHistoryService conversationHistoryService,
            AgentTaskMemoryService taskMemoryService,
            AgentTracePublisher tracePublisher,
            AgentTurnPlanner turnPlanner,
            AgentToolExecutor toolExecutor,
            AgentLoopRunner agentLoopRunner,
            RecommendationOrchestrator recommendationOrchestrator,
            MusioPlaylistService musioPlaylistService,
            ObjectMapper objectMapper
    ) {
        this.prompts = prompts;
        this.configService = configService;
        this.chatModelFactory = chatModelFactory;
        this.musicProfileService = musicProfileService;
        this.eventBus = eventBus;
        this.conversationHistoryService = conversationHistoryService;
        this.taskMemoryService = taskMemoryService;
        this.tracePublisher = tracePublisher;
        this.turnPlanner = turnPlanner;
        this.toolExecutor = toolExecutor;
        this.agentLoopRunner = agentLoopRunner;
        this.recommendationOrchestrator = recommendationOrchestrator;
        this.musioPlaylistService = musioPlaylistService;
        this.objectMapper = objectMapper;
    }

    public void start(String runId, ChatRequest request) {
        MusioConfig.Ai ai = configService.config().ai();
        AgentRunContext.setRunId(runId);
        try {
            String userId = conversationHistoryService.normalizeUserId(request.userId());
            AgentRunContext.setUserId(userId);
            List<ConversationHistoryMessage> history = conversationHistoryService.load(userId);
            AgentTaskMemory taskMemory = taskMemoryService.read(userId);
            tracePublisher.publishRequestReceived(runId, request.message());
            // 统一入口：每轮都进入 Agent runtime；respond_only 只是 planner 不调工具的结果。
            tracePublisher.publishPlanningRunning(runId);
            AgentTurnPlan turnPlan;
            try (TraceHeartbeat ignored = progressHeartbeat(
                    runId,
                    "context.turn-plan",
                    "context",
                    "规划处理方式",
                    "还在理解你的表达和最近对话。",
                    "还在判断这轮是否需要搜索、歌词、评论或推荐能力。"
            )) {
                turnPlan = turnPlanner.planTurn(ai, request.message(), history, taskMemory);
            }
            AgentTaskContext taskContext = turnPlan.toLegacyTaskContext(request.message());
            boolean traceEnabled = true;
            AgentRunContext.setTraceEnabled(traceEnabled);
            logTurnRuntimePlan(runId, ai, turnPlan, traceEnabled);
            tracePublisher.publishPlanningDone(runId, String.valueOf(turnPlan.disposition()), turnPlan.taskType(), toolNameList(turnPlan.toolCalls()));
            if (turnPlan.usesTools()) {
                taskMemory = taskMemoryService.recordTask(
                        userId,
                        taskContext.planningMessage(),
                        taskContext.searchKeyword(),
                        taskContext.searchLimit(),
                        taskContext.avoidSongTitles(),
                        taskContext.preservePreviousSongContext()
                );
            }
            if (turnPlan.hasOnlyLocalWriteTools()) {
                handleMusioPlaylistAdd(runId, ai, userId, request.message(), history, taskMemory, turnPlan, traceEnabled);
                return;
            }
            PreludeContext preludeContext;
            if (traceEnabled && shouldUseRecommendationPrelude(turnPlan, taskContext)) {
                try (TraceHeartbeat ignored = progressHeartbeat(
                        runId,
                        "context.recommendation-candidates",
                        "context",
                        "生成推荐候选",
                        "还在根据你的场景挑选更合适的歌曲。",
                        "还在把推荐候选解析成可播放的真实歌曲。"
                )) {
                    preludeContext = recommendationPreludeContext(ai, taskContext, taskMemory);
                }
            } else {
                try (TraceHeartbeat ignored = progressHeartbeat(
                        runId,
                        "tool.execution",
                        "tool",
                        "执行音乐能力",
                        "还在等待音乐能力返回结果。",
                        "还在整理本轮工具结果。"
                )) {
                    preludeContext = turnPlan.usesTools()
                            ? agentLoopPreludeContext(ai, runId, userId, request.message(), history, taskMemory, taskContext, turnPlan)
                            : PreludeContext.empty();
                }
            }
            if (turnPlan.hasLocalWriteTools()) {
                LocalPlaylistAddResult playlistAddResult = executeMusioPlaylistAdd(
                        runId,
                        history,
                        taskMemory,
                        turnPlan,
                        preludeContext.songs()
                );
                preludeContext = withPlaylistAddResult(preludeContext, playlistAddResult);
            }
            logComposerPolicy(runId, ai, turnPlan, preludeContext);
            Prompt prompt = conversationPrompt(history, request.message(), taskContext.promptContext(), preludeContext);

            AgentAnswerStreamGuard answerGuard = new AgentAnswerStreamGuard();
            boolean[] composeStarted = {false};
            if (!preludeContext.answerPrefix().isBlank()) {
                publishAnswerText(runId, ai, preludeContext.answerPrefix(), traceEnabled, composeStarted);
            }
            publishSongCards(runId, preludeContext.songs());
            AgentLlmLogger.logRequest("final_answer", ai, prompt);
            try (TraceHeartbeat ignored = progressHeartbeat(
                    runId,
                    "compose.answer",
                    "compose",
                    "生成回答",
                    "还在把音乐结果组织成更自然的回复。",
                    "还在生成回答内容，马上继续给你。"
            )) {
                chatModelFactory.chatClient(ai)
                        .prompt(prompt)
                        .stream()
                        .content()
                        .doOnNext(chunk -> {
                            AgentLlmLogger.logStreamChunk("final_answer", ai, chunk);
                            publishAnswerDelta(runId, ai, answerGuard, chunk, traceEnabled, composeStarted);
                        })
                        .blockLast();
            }
            answerGuard.finish(rawToolProtocolFallback()).ifPresent(text -> publishAnswerText(runId, ai, text, traceEnabled, composeStarted));
            String answerText = answerGuard.visibleAnswer();
            AgentLlmLogger.logResponse("final_answer.visible", ai, answerText);
            if (answerGuard.rawToolProtocolSuppressed()) {
                log.warn("Agent run {} suppressed raw tool protocol output from model {}", runId, ai.model());
            }
            if (traceEnabled) {
                if (!composeStarted[0]) {
                    tracePublisher.publishComposeRunning(runId);
                }
                tracePublisher.publishComposeDone(runId);
            }

            answerText = combineAnswer(preludeContext.answerPrefix(), answerText);
            conversationHistoryService.appendTurn(userId, request.message(), answerText, preludeContext.songs());

            eventBus.publish(runId, AgentEvent.of("done", Map.of("runId", runId)));
        } catch (Exception e) {
            log.warn("Agent run {} failed", runId, e);
            eventBus.publish(runId, AgentEvent.of("agent_error", Map.of(
                    "runId", runId,
                    "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    "aiProvider", ai.provider(),
                    "aiModel", ai.model()
            )));
        } finally {
            AgentRunContext.clear();
        }
    }

    @PreDestroy
    public void shutdownProgressExecutor() {
        progressExecutor.shutdownNow();
    }

    private void handleMusioPlaylistAdd(
            String runId,
            MusioConfig.Ai ai,
            String userId,
            String userMessage,
            List<ConversationHistoryMessage> history,
            AgentTaskMemory taskMemory,
            AgentTurnPlan turnPlan,
            boolean traceEnabled
    ) {
        LocalPlaylistAddResult result = executeMusioPlaylistAdd(runId, history, taskMemory, turnPlan, List.of());

        boolean[] composeStarted = {false};
        if (result.success()) {
            publishSongCards(runId, List.of(result.song()));
        }
        publishAnswerText(runId, ai, result.answerText(), traceEnabled, composeStarted);
        if (traceEnabled) {
            if (!composeStarted[0]) {
                tracePublisher.publishComposeRunning(runId);
            }
            tracePublisher.publishComposeDone(runId);
        }
        conversationHistoryService.appendTurn(
                userId,
                userMessage,
                result.answerText(),
                result.success() ? List.of(result.song()) : List.of()
        );
        eventBus.publish(runId, AgentEvent.of("done", Map.of("runId", runId)));
    }

    private LocalPlaylistAddResult executeMusioPlaylistAdd(
            String runId,
            List<ConversationHistoryMessage> history,
            AgentTaskMemory taskMemory,
            AgentTurnPlan turnPlan,
            List<Song> currentSongs
    ) {
        AgentToolCall call = firstToolCall(turnPlan, "add_song_to_musio_playlist");
        Map<String, Object> arguments = call == null || call.arguments() == null ? Map.of() : call.arguments();
        String playlistId = text(arguments, "playlistId").isBlank() ? "default" : text(arguments, "playlistId");
        Map<String, Object> input = musioPlaylistAddInput(playlistId, arguments);
        publishToolStart(runId, "add_song_to_musio_playlist", input);
        tracePublisher.publishToolRunning(runId, "add_song_to_musio_playlist", input);

        LocalPlaylistAddResult result;
        try {
            Song song = resolveMusioPlaylistSong(arguments, history, taskMemory, currentSongs);
            if (song == null) {
                result = LocalPlaylistAddResult.failure("还没能确定要收藏哪一首歌。你可以告诉我歌名，或先让我推荐/搜索出歌曲卡片。");
            } else {
                boolean existed = playlistContains(playlistId, song.id());
                MusioPlaylist playlist = musioPlaylistService.addSong(playlistId, song);
                result = LocalPlaylistAddResult.success(song, playlist, existed);
            }
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            result = LocalPlaylistAddResult.failure("收藏失败：" + message);
        }

        publishToolResult(runId, "add_song_to_musio_playlist", result.toolResult());
        if (result.success()) {
            tracePublisher.publishToolDone(runId, "add_song_to_musio_playlist", result.toolResult());
        } else {
            tracePublisher.publishToolError(runId, "add_song_to_musio_playlist", result.message());
        }

        return result;
    }

    private Song resolveMusioPlaylistSong(
            Map<String, Object> arguments,
            List<ConversationHistoryMessage> history,
            AgentTaskMemory taskMemory,
            List<Song> currentSongs
    ) {
        String songId = text(arguments, "songId");
        String songTitle = text(arguments, "songTitle");
        String artist = text(arguments, "artist");
        Integer songIndex = integer(arguments, "songIndex");
        List<Song> candidates = recentSongs(history, taskMemory, currentSongs);
        Song fromContext = resolveFromCandidates(candidates, songId, songTitle, artist, songIndex);
        if (fromContext != null) {
            return fromContext;
        }
        String query = String.join(" ", List.of(artist, songTitle).stream()
                .filter(value -> value != null && !value.isBlank())
                .toList()).strip();
        if (query.isBlank()) {
            return null;
        }
        List<AgentToolExecution> executions = toolExecutor.execute(new AgentToolPlan(List.of(
                new AgentToolCall("search_songs", Map.of("keyword", query, "limit", 1))
        ), 1.0));
        for (AgentToolExecution execution : executions) {
            List<Song> songs = songsFromResultJson(execution.resultJson());
            if (!songs.isEmpty()) {
                return songs.getFirst();
            }
        }
        return null;
    }

    private Song resolveFromCandidates(List<Song> candidates, String songId, String songTitle, String artist, Integer songIndex) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (songIndex != null && songIndex >= 1 && songIndex <= candidates.size()) {
            return candidates.get(songIndex - 1);
        }
        if (songId != null && !songId.isBlank()) {
            for (Song song : candidates) {
                if (song != null && songId.equals(song.id())) {
                    return song;
                }
            }
        }
        String normalizedTitle = normalizeSongText(songTitle);
        String normalizedArtist = normalizeSongText(artist);
        if (!normalizedTitle.isBlank()) {
            Song titleAndArtistMatch = candidates.stream()
                    .filter(song -> titleMatches(song, normalizedTitle))
                    .filter(song -> normalizedArtist.isBlank() || artistMatches(song, normalizedArtist))
                    .findFirst()
                    .orElse(null);
            if (titleAndArtistMatch != null) {
                return titleAndArtistMatch;
            }
            return candidates.stream()
                    .filter(song -> titleMatches(song, normalizedTitle))
                    .findFirst()
                    .orElse(null);
        }
        return candidates.getFirst();
    }

    private List<Song> recentSongs(List<ConversationHistoryMessage> history, AgentTaskMemory taskMemory, List<Song> currentSongs) {
        Map<String, Song> songsById = new LinkedHashMap<>();
        if (currentSongs != null) {
            for (Song song : currentSongs) {
                addSongCandidate(songsById, song);
            }
        }
        if (history != null) {
            for (int index = history.size() - 1; index >= 0; index--) {
                ConversationHistoryMessage message = history.get(index);
                if (message == null || !"assistant".equals(message.role())) {
                    continue;
                }
                for (Song song : message.songs()) {
                    addSongCandidate(songsById, song);
                }
            }
        }
        if (taskMemory != null && taskMemory.lastResultSongs() != null) {
            for (Song song : taskMemory.lastResultSongs()) {
                addSongCandidate(songsById, song);
            }
        }
        return List.copyOf(songsById.values());
    }

    private void addSongCandidate(Map<String, Song> songsById, Song song) {
        if (song == null || song.id() == null || song.id().isBlank()) {
            return;
        }
        songsById.putIfAbsent(song.id(), song);
    }

    private boolean playlistContains(String playlistId, String songId) {
        if (songId == null || songId.isBlank()) {
            return false;
        }
        return musioPlaylistService.get(playlistId).items().stream()
                .anyMatch(item -> songId.equals(item.providerTrackId()));
    }

    private AgentToolCall firstToolCall(AgentTurnPlan turnPlan, String toolName) {
        if (turnPlan == null || turnPlan.toolCalls() == null) {
            return null;
        }
        return turnPlan.toolCalls().stream()
                .filter(call -> call != null && toolName.equals(call.toolName()))
                .findFirst()
                .orElse(null);
    }

    private List<AgentStepAction> loopInitialActions(AgentTurnPlan turnPlan) {
        if (turnPlan == null || turnPlan.toolCalls() == null || turnPlan.toolCalls().isEmpty()) {
            return List.of();
        }
        return turnPlan.readOnlyLoopToolCalls().stream()
                .limit(1)
                .map(call -> new AgentStepAction(
                        AgentStepActionType.TOOL_CALL,
                        call.toolName(),
                        call.arguments() == null ? Map.of() : call.arguments(),
                        "执行已规划音乐能力",
                        turnPlan.confidence(),
                        "turn_planner_seed"
                ))
                .toList();
    }

    private Map<String, Object> musioPlaylistAddInput(String playlistId, Map<String, Object> arguments) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("playlistId", playlistId);
        copyTextArgument(arguments, input, "songId");
        copyTextArgument(arguments, input, "songTitle");
        copyTextArgument(arguments, input, "artist");
        Object songIndex = arguments.get("songIndex");
        if (songIndex instanceof Number number) {
            input.put("songIndex", number.intValue());
        }
        return input;
    }

    private void copyTextArgument(Map<String, Object> source, Map<String, Object> target, String key) {
        String value = text(source, key);
        if (!value.isBlank()) {
            target.put(key, value);
        }
    }

    private void publishToolStart(String runId, String toolName, Map<String, Object> input) {
        eventBus.publish(runId, AgentEvent.of("tool_start", Map.of(
                "runId", runId,
                "tool", toolName,
                "input", input
        )));
    }

    private void publishToolResult(String runId, String toolName, Map<String, Object> result) {
        eventBus.publish(runId, AgentEvent.of("tool_result", Map.of(
                "runId", runId,
                "tool", toolName,
                "summary", result.getOrDefault("summary", "Musio 歌单操作已完成。")
        )));
    }

    private boolean titleMatches(Song song, String normalizedTitle) {
        if (song == null || normalizedTitle == null || normalizedTitle.isBlank()) {
            return false;
        }
        String title = normalizeSongText(song.title());
        return !title.isBlank() && (title.equals(normalizedTitle) || title.contains(normalizedTitle) || normalizedTitle.contains(title));
    }

    private boolean artistMatches(Song song, String normalizedArtist) {
        if (song == null || song.artists() == null || normalizedArtist == null || normalizedArtist.isBlank()) {
            return false;
        }
        return song.artists().stream()
                .map(this::normalizeSongText)
                .anyMatch(artist -> !artist.isBlank()
                        && (artist.equals(normalizedArtist) || artist.contains(normalizedArtist) || normalizedArtist.contains(artist)));
    }

    private String normalizeSongText(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase()
                .replaceAll("[《》<>\\[\\]【】()（）\\s·・,，。.!！?？:：;；'\"“”‘’-]+", "")
                .strip();
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value instanceof String text ? text.strip() : "";
    }

    private Integer integer(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.strip());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private PreludeContext recommendationPreludeContext(
            MusioConfig.Ai ai,
            AgentTaskContext taskContext,
            AgentTaskMemory taskMemory
    ) {
        RecommendationResponse response = recommendationOrchestrator.recommend(
                ai,
                taskContext.planningMessage(),
                taskContext.searchLimit(),
                taskContext.avoidSongTitles(),
                taskMemory
        );
        log.info(
                "TURN_EXECUTOR stage=recommendation_executor runId={} userId={} disposition=USE_TOOLS taskType=recommend executionCount=1 songCardCount={} songCardTitles={}",
                AgentRunContext.runId().orElse("-"),
                AgentRunContext.userId().orElse("-"),
                response.songs() == null ? 0 : response.songs().size(),
                songTitles(response.songs())
        );
        return new PreludeContext("""

                本轮内部推荐器已根据用户请求和音乐画像生成候选，并把候选精确解析成真实歌曲卡片。
                本轮歌曲卡片顺序是：
                %s
                本轮推荐理由是：
                %s
                如果最终回答正文列出歌曲，必须严格按照上面的歌曲卡片顺序输出，不要重排、补歌或改写歌手。
                工具调用阶段已经结束；最终回答阶段不能再调用工具，也不能输出 <tool_call>、<function=...>、JSON 工具调用或任何工具调用协议文本。
                """.formatted(songTitles(response.songs()), recommendationReasons(response)),
                true,
                response.songs(),
                "");
    }

    private boolean shouldUseRecommendationPrelude(AgentTurnPlan turnPlan, AgentTaskContext taskContext) {
        if (!taskContext.recommendationPreludeAllowed()) {
            return false;
        }
        // Planner 的显式工具计划优先；legacy recommendation 不能覆盖已声明的 search_songs 等工具调用。
        return turnPlan.toolCalls() == null || turnPlan.toolCalls().isEmpty() || turnPlan.hasRecommendationTool();
    }

    private PreludeContext agentLoopPreludeContext(
            MusioConfig.Ai ai,
            String runId,
            String userId,
            String userMessage,
            List<ConversationHistoryMessage> history,
            AgentTaskMemory taskMemory,
            AgentTaskContext taskContext,
            AgentTurnPlan turnPlan
    ) {
        AgentLoopEvidence evidence = agentLoopRunner.run(ai, new AgentLoopState(
                runId,
                userId,
                userMessage,
                history,
                taskMemory,
                List.of(),
                0
        ), loopInitialActions(turnPlan));
        if (evidence.hasObservations()) {
            taskMemoryService.recordLoopEvidence(
                    userId,
                    evidence.targetSong(),
                    evidence.completedTaskType(),
                    evidence.observationSummaries()
            );
        }
        log.info(
                "TURN_EXECUTOR stage=agent_loop runId={} userId={} taskType={} observationCount={} songCardCount={} songCardTitles={}",
                runId,
                userId,
                taskContext.taskType(),
                evidence.observations().size(),
                evidence.songs().size(),
                songTitles(evidence.songs())
        );
        if (!evidence.hasObservations()) {
            if (taskContext.toolEvidenceExpected()) {
                return new PreludeContext("""

                        本轮 Agent loop 没有产生可执行的真实工具结果。
                        最终回答不得声称“已读取 QQ 音乐详情/评论/歌词/歌单”等没有真实发生的工具调用。
                        最终回答阶段不能再调用工具，也不能输出 <tool_call>、<function=...>、JSON 工具调用或任何工具调用协议文本。
                        必须直接用自然语言回答；如果缺少真实工具结果，请明确说明本轮没有拿到对应真实工具结果。
                        """, false, List.of(), "");
            }
            return PreludeContext.empty();
        }
        return new PreludeContext("""

                本轮 Agent loop 已根据工具 observation 逐步执行这些只读音乐能力：
                %s
                本轮歌曲卡片顺序是：
                %s
                最终回答必须基于这些真实 observations；不要声称调用了没有出现在上方结果里的工具。
                如果最终回答正文列出歌曲，必须严格按照上面的歌曲卡片顺序输出，不要重排、补歌或改写歌手。
                工具状态以“状态”行和 result.success 为准：success=true 的工具不得写成失败、HTTP 500 或没有拿到结果。
                如果评论、歌词或详情 observation 不存在，正文不得声称已经读取到对应内容。
                工具调用阶段已经结束；最终回答阶段不能再调用工具，也不能输出 <tool_call>、<function=...>、JSON 工具调用或任何工具调用协议文本。
                你必须直接生成面向用户的中文自然语言回答。
                """.formatted(loopExecutionContext(evidence.observations()), songTitles(evidence.songs())),
                true,
                evidence.songs(),
                "");
    }

    private PreludeContext withPlaylistAddResult(PreludeContext context, LocalPlaylistAddResult result) {
        PreludeContext base = context == null ? PreludeContext.empty() : context;
        if (result == null) {
            return base;
        }
        List<Song> songs = result.success()
                ? mergeSongs(base.songs(), List.of(result.song()))
                : base.songs();
        String text = (base.text() + "\n\n" + playlistAddContext(result)).strip();
        return new PreludeContext(text, true, songs, base.answerPrefix());
    }

    private String playlistAddContext(LocalPlaylistAddResult result) {
        Map<String, Object> toolResult = result.toolResult();
        return """

                本轮还执行了 Musio 本地歌单写入：
                工具：add_song_to_musio_playlist
                状态：%s
                摘要：%s
                结果 JSON：%s

                最终回答必须同时完成用户这轮请求中的所有部分：如果上方只读音乐 evidence 里有评论、歌词、详情或搜索结果，先基于它们回答；同时明确说明 Musio 歌单收藏结果。不要只回复歌单收藏结果。
                """.formatted(
                result.success() ? "SUCCESS" : "FAILURE",
                String.valueOf(toolResult.getOrDefault("summary", result.answerText())),
                writeJson(toolResult)
        ).strip();
    }

    private List<Song> mergeSongs(List<Song> primary, List<Song> extra) {
        Map<String, Song> songsById = new LinkedHashMap<>();
        if (primary != null) {
            for (Song song : primary) {
                addSongCandidate(songsById, song);
            }
        }
        if (extra != null) {
            for (Song song : extra) {
                addSongCandidate(songsById, song);
            }
        }
        return List.copyOf(songsById.values());
    }

    private void publishAnswerDelta(String runId, MusioConfig.Ai ai, AgentAnswerStreamGuard answerGuard, String chunk, boolean traceEnabled, boolean[] composeStarted) {
        answerGuard.accept(chunk).ifPresent(text -> publishAnswerText(runId, ai, text, traceEnabled, composeStarted));
    }

    private void publishAnswerText(String runId, MusioConfig.Ai ai, String text, boolean traceEnabled, boolean[] composeStarted) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (traceEnabled && !composeStarted[0]) {
            tracePublisher.publishComposeRunning(runId);
            composeStarted[0] = true;
        }
        eventBus.publish(runId, AgentEvent.of("agent_message_delta", Map.of(
                "runId", runId,
                "text", text,
                "aiProvider", ai.provider(),
                "aiModel", ai.model(),
                "systemPromptLoaded", !prompts.systemPrompt().isBlank()
        )));
    }

    private void publishSongCards(String runId, List<Song> songs) {
        if (songs == null || songs.isEmpty()) {
            return;
        }
        eventBus.publish(runId, AgentEvent.of("song_cards", Map.of(
                "runId", runId,
                "songs", songs
        )));
    }

    private Prompt conversationPrompt(
            List<ConversationHistoryMessage> history,
            String userMessage,
            String taskContext,
            PreludeContext preludeContext
    ) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(agentSystemPrompt() + taskContext + preludeContext.text()));
        if (preludeContext.evidenceBound()) {
            messages.add(new UserMessage("""
                    当前用户输入：
                    %s

                    请只根据系统消息中的本轮任务上下文和本轮工具 evidence 回答。
                    最近历史已经只用于 Router/Planner 判断上下文，不得把历史 assistant 文本当成本轮事实来源。
                    如果你在正文中列出歌曲，必须严格使用系统消息里的“本轮歌曲卡片顺序”，不要重排、补歌或改写歌手。
                    """.formatted(userMessage)));
        } else {
            for (ConversationHistoryMessage message : history) {
                if ("user".equals(message.role())) {
                    messages.add(new UserMessage(message.content()));
                } else if ("assistant".equals(message.role())) {
                    messages.add(new AssistantMessage(message.content()));
                }
            }
            messages.add(new UserMessage(userMessage));
        }
        return new Prompt(messages);
    }

    private List<Song> songsFromResultJson(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode songsNode = objectMapper.readTree(resultJson).path("songs");
            if (!songsNode.isArray() || songsNode.isEmpty()) {
                return List.of();
            }
            List<Song> songs = new ArrayList<>();
            for (JsonNode songNode : songsNode) {
                try {
                    songs.add(objectMapper.treeToValue(songNode, Song.class));
                } catch (Exception ignored) {
                    // Ignore malformed song cards; the natural language answer remains recoverable.
                }
            }
            return songs;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String loopExecutionContext(List<AgentObservation> observations) {
        StringBuilder builder = new StringBuilder();
        for (AgentObservation observation : observations) {
            builder.append("步骤：").append(observation.stepId()).append('\n');
            builder.append("工具：").append(observation.toolName()).append('\n');
            builder.append("参数：").append(writeJson(observation.arguments())).append('\n');
            builder.append("状态：").append(observation.status()).append('\n');
            builder.append("摘要：").append(observation.plannerSummary()).append('\n');
            builder.append("结果 JSON：").append(observation.resultJson()).append("\n\n");
        }
        return builder.toString().strip();
    }

    private String recommendationReasons(RecommendationResponse response) {
        if (response == null || response.result() == null || response.result().resolved() == null || response.result().resolved().isEmpty()) {
            return response == null || response.answerText() == null || response.answerText().isBlank()
                    ? "无"
                    : response.answerText().strip();
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < response.result().resolved().size(); index++) {
            var item = response.result().resolved().get(index);
            Song song = item.song();
            builder.append(index + 1)
                    .append(". ")
                    .append(song == null || song.title() == null || song.title().isBlank() ? "未知歌曲" : song.title())
                    .append(" - ")
                    .append(song == null || song.artists() == null || song.artists().isEmpty() ? "未知歌手" : String.join(" / ", song.artists()))
                    .append("：")
                    .append(item.reason() == null || item.reason().isBlank() ? "无" : item.reason())
                    .append('\n');
        }
        return builder.toString().strip();
    }

    private String combineAnswer(String prefix, String generatedText) {
        String fixed = prefix == null ? "" : prefix;
        String generated = generatedText == null ? "" : generatedText;
        if (fixed.isBlank()) {
            return generated;
        }
        if (generated.isBlank()) {
            return fixed.strip();
        }
        return (fixed.strip() + "\n\n" + generated.strip()).strip();
    }

    private String songTitles(List<Song> songs) {
        if (songs == null || songs.isEmpty()) {
            return "none";
        }
        return String.join("；", songs.stream()
                .map(song -> song.title() == null || song.title().isBlank() ? song.id() : song.title())
                .filter(title -> title != null && !title.isBlank())
                .toList());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void logTurnRuntimePlan(String runId, MusioConfig.Ai ai, AgentTurnPlan plan, boolean traceEnabled) {
        log.info(
                "TURN_RUNTIME stage=plan_validator runId={} userId={} provider={} model={} disposition={} taskType={} contextMode={} memoryUse={} toolCallCount={} toolNames={} validationStatus={} fallbackReason={} traceEnabled={}",
                runId,
                AgentRunContext.userId().orElse("-"),
                ai == null ? "" : ai.provider(),
                ai == null ? "" : ai.model(),
                plan.disposition(),
                plan.taskType(),
                plan.contextMode(),
                plan.memoryUse() == null ? "none" : plan.memoryUse().summary(),
                plan.toolCalls() == null ? 0 : plan.toolCalls().size(),
                toolNames(plan.toolCalls()),
                plan.usesTools() ? "accepted" : "respond_only",
                plan.fallbackReason(),
                traceEnabled
        );
    }

    private void logComposerPolicy(String runId, MusioConfig.Ai ai, AgentTurnPlan plan, PreludeContext preludeContext) {
        log.info(
                "TURN_COMPOSER stage=final_composer runId={} userId={} provider={} model={} disposition={} taskType={} evidenceBound={} allowHistoryAsContext={} allowClaimToolUse={} songCardCount={} songCardsProvided={}",
                runId,
                AgentRunContext.userId().orElse("-"),
                ai == null ? "" : ai.provider(),
                ai == null ? "" : ai.model(),
                plan.disposition(),
                plan.taskType(),
                preludeContext.evidenceBound(),
                !preludeContext.evidenceBound(),
                preludeContext.evidenceBound(),
                preludeContext.songs().size(),
                preludeContext.hasSongCards()
        );
    }

    private String toolNames(List<AgentToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return "none";
        }
        return String.join(",", calls.stream()
                .map(AgentToolCall::toolName)
                .filter(name -> name != null && !name.isBlank())
                .toList());
    }

    private List<String> toolNameList(List<AgentToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        return calls.stream()
                .map(AgentToolCall::toolName)
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }

    private TraceHeartbeat progressHeartbeat(String runId, String stepId, String stage, String title, String... messages) {
        if (messages == null || messages.length == 0) {
            return TraceHeartbeat.empty();
        }
        AtomicInteger counter = new AtomicInteger();
        ScheduledFuture<?> future = progressExecutor.scheduleAtFixedRate(() -> {
            int index = counter.getAndIncrement();
            tracePublisher.publishProgress(runId, stepId, stage, title, messages[index % messages.length], Map.of(
                    "heartbeat", index + 1
            ));
        }, 1500, 2500, TimeUnit.MILLISECONDS);
        return new TraceHeartbeat(future);
    }

    private record PreludeContext(String text, boolean evidenceBound, List<Song> songs, String answerPrefix) {
        static PreludeContext empty() {
            return new PreludeContext("", false, List.of(), "");
        }

        private PreludeContext {
            songs = songs == null ? List.of() : List.copyOf(songs);
            answerPrefix = answerPrefix == null ? "" : answerPrefix;
        }

        private boolean hasSongCards() {
            return !songs.isEmpty();
        }
    }

    private record TraceHeartbeat(ScheduledFuture<?> future) implements AutoCloseable {
        static TraceHeartbeat empty() {
            return new TraceHeartbeat(null);
        }

        @Override
        public void close() {
            if (future != null) {
                future.cancel(false);
            }
        }
    }

    private record LocalPlaylistAddResult(
            boolean success,
            Song song,
            MusioPlaylist playlist,
            boolean existed,
            String message
    ) {
        static LocalPlaylistAddResult success(Song song, MusioPlaylist playlist, boolean existed) {
            return new LocalPlaylistAddResult(true, song, playlist, existed, "");
        }

        static LocalPlaylistAddResult failure(String message) {
            return new LocalPlaylistAddResult(false, null, null, false, message == null || message.isBlank() ? "收藏失败。" : message);
        }

        String answerText() {
            if (!success) {
                return message;
            }
            String title = song.title() == null || song.title().isBlank() ? song.id() : song.title();
            String artists = song.artists() == null || song.artists().isEmpty() ? "" : " - " + String.join(" / ", song.artists());
            if (existed) {
                return "这首歌已经在 Musio 歌单里了：%s%s。".formatted(title, artists);
            }
            return "已帮你收藏到 Musio 歌单：%s%s。".formatted(title, artists);
        }

        Map<String, Object> toolResult() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", success);
            result.put("summary", answerText());
            if (!success) {
                result.put("message", message);
                return result;
            }
            result.put("playlistId", playlist.id());
            result.put("playlistName", playlist.name());
            result.put("itemCount", playlist.items().size());
            result.put("alreadyExists", existed);
            result.put("songId", song.id());
            result.put("songTitle", song.title() == null ? song.id() : song.title());
            if (song.artists() != null && !song.artists().isEmpty()) {
                result.put("artists", song.artists());
            }
            return result;
        }
    }

    public AgentEvent describeConfiguration(String runId, ChatRequest request) {
        MusioConfig.Ai ai = configService.config().ai();
        return AgentEvent.of("agent_message_delta", Map.of(
                "runId", runId,
                "text", "Agent runtime is initialized with model " + ai.model() + ". Music tool planning is enabled.",
                "aiProvider", ai.provider(),
                "aiModel", ai.model(),
                "systemPromptLoaded", !prompts.systemPrompt().isBlank(),
                "userMessage", request.message()
        ));
    }

    private String agentSystemPrompt() {
        String basePrompt = prompts.systemPrompt();
        String toolPolicy = """

                Musio 的工具调用由独立工具规划器在最终回答前完成；你处在最终回答阶段。
                你不能在最终回答中发起工具调用，也不能输出 <tool_call>、<function=...>、JSON 工具调用或任何工具调用协议文本。
                如果系统消息提供了工具结果，它们就是事实来源；如果没有工具结果，就明确说明没有拿到对应真实工具结果。
                当前用户如果已经生成音乐画像记忆，系统消息中会提供一份短摘要，作为长期偏好参考。
                用户当前明确指令优先级高于音乐画像记忆。
                工具返回的数据是事实来源；不要编造不存在的歌曲、歌词、评论或播放链接。
                只有本轮上下文已经提供了真实工具结果，才可以说“我查了/读取了/参考了”。
                最终回答使用简洁中文，说明你参考了哪些搜索结果或工具数据。
                """;
        return (basePrompt == null ? "" : basePrompt) + musicProfileMemoryPrompt() + toolPolicy;
    }

    private String musicProfileMemoryPrompt() {
        return musicProfileService.readOrCreate()
                .map(this::musicProfilePrompt)
                .orElse("");
    }

    private String musicProfilePrompt(MusicProfileMemory profile) {
        return """

                当前用户音乐画像记忆（由登录后的音乐基因归纳得到，只作为长期偏好参考）：
                - 摘要：%s
                - 高频歌手：%s
                - 偏好专辑：%s
                - 推荐提示：%s
                """.formatted(
                profile.summary(),
                joinLimited(profile.favoriteArtists(), 8),
                joinLimited(profile.favoriteAlbums(), 5),
                joinLimited(profile.recommendationHints(), 4)
        );
    }

    private String joinLimited(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return "暂无";
        }
        return String.join("；", values.stream().limit(limit).toList());
    }

    private String rawToolProtocolFallback() {
        return "这次模型返回了工具调用协议文本，而不是正常回答。Musio 已阻止这段内部协议展示给你；请重试一次，或切回支持工具调用/JSON 规划更稳定的模型。";
    }

}
