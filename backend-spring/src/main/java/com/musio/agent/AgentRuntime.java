package com.musio.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.capability.AgentCapabilityManifest;
import com.musio.agent.capability.CapabilityEffect;
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
import com.musio.model.PendingLocalPlaylistAdd;
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
    private final AgentPolicyGate policyGate;
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
            AgentPolicyGate policyGate,
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
        this.policyGate = policyGate;
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
            AgentCapabilityManifest capabilityManifest = policyGate.manifestFor(request.message(), turnPlan);
            int requestedSongCount = requestedSongCount(request.message(), taskContext);
            boolean traceEnabled = true;
            AgentRunContext.setTraceEnabled(traceEnabled);
            logTurnRuntimePlan(runId, ai, turnPlan, traceEnabled);
            tracePublisher.publishPlanningDone(runId, String.valueOf(turnPlan.disposition()), turnPlan.taskType(), toolNameList(turnPlan.toolCalls()));
            if (isLocalPlaylistCancelIntent(request.message())) {
                handleLocalPlaylistCancel(runId, ai, userId, request.message(), traceEnabled);
                return;
            }
            if (isLocalPlaylistConfirmationIntent(request.message(), history, taskMemory)) {
                handleConfirmedLocalPlaylistAdd(runId, ai, userId, request.message(), history, taskMemory, traceEnabled);
                return;
            }
            AgentTurnPlan directLocalPlaylistAddPlan = directLocalPlaylistAddPlan(request.message(), turnPlan, history);
            if (directLocalPlaylistAddPlan != null) {
                handleLocalPlaylistAddConfirmationRequest(runId, ai, userId, request.message(), history, taskMemory, directLocalPlaylistAddPlan, List.of(), traceEnabled);
                return;
            }
            AgentCapabilityManifest executionCapabilityManifest = capabilityManifest.allowsLocalWrite()
                    ? readOnlyManifest(capabilityManifest)
                    : capabilityManifest;
            boolean shouldRunLoop = turnPlan.usesTools() || taskContext.toolEvidenceExpected();
            if (shouldRunLoop) {
                taskMemory = taskMemoryService.recordTask(
                        userId,
                        taskContext.planningMessage(),
                        taskContext.searchKeyword(),
                        taskContext.searchLimit(),
                        taskContext.avoidSongTitles(),
                        taskContext.preservePreviousSongContext()
                );
            }
            PreludeContext preludeContext;
            if (shouldUseDeterministicRecommendCommentPlaylistFallback(request.message(), turnPlan, capabilityManifest, requestedSongCount)) {
                try (TraceHeartbeat ignored = progressHeartbeat(
                        runId,
                        "tool.deterministic-fallback",
                        "tool",
                        "执行复合音乐任务",
                        "还在搜索目标歌曲并读取热评。",
                        "还在保存待确认收藏目标。"
                )) {
                    preludeContext = recommendCommentPlaylistFastPath(runId, request.message(), history, taskMemory, taskContext, turnPlan, "deterministic_music_fallback");
                }
            } else if (shouldUseRecommendCommentPlaylistFastPath(request.message(), taskContext, capabilityManifest, requestedSongCount)) {
                try (TraceHeartbeat ignored = progressHeartbeat(
                        runId,
                        "tool.fast-path",
                        "tool",
                        "执行复合音乐任务",
                        "还在搜索目标歌曲并读取热评。",
                        "还在把歌曲加入本地 Musio 默认歌单。"
                )) {
                    preludeContext = recommendCommentPlaylistFastPath(runId, request.message(), history, taskMemory, taskContext, turnPlan, "fast_path");
                }
            } else if (traceEnabled && shouldUseRecommendationPrelude(turnPlan, taskContext)) {
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
                    preludeContext = shouldRunLoop
                            ? agentLoopPreludeContext(ai, runId, userId, request.message(), history, taskMemory, taskContext, turnPlan, executionCapabilityManifest, requestedSongCount)
                            : PreludeContext.empty();
                }
            }
            if (shouldExecutePlannedLocalPlaylistAdd(turnPlan, preludeContext)) {
                PendingLocalPlaylistAdd pending = savePendingLocalPlaylistAdd(userId, request.message(), history, taskMemory, turnPlan, preludeContext.songs());
                preludeContext = withPlaylistPendingConfirmation(preludeContext, pending);
            }
            logComposerPolicy(runId, ai, turnPlan, preludeContext);
            Prompt prompt = conversationPrompt(history, request.message(), taskContext.promptContext(), preludeContext);

            AgentAnswerStreamGuard answerGuard = new AgentAnswerStreamGuard();
            boolean[] composeStarted = {false};
            if (!preludeContext.answerPrefix().isBlank()) {
                publishAnswerText(runId, ai, preludeContext.answerPrefix(), traceEnabled, composeStarted);
            }
            publishSongCards(runId, preludeContext.songs());
            if (preludeContext.directAnswer()) {
                if (traceEnabled) {
                    if (!composeStarted[0]) {
                        tracePublisher.publishComposeRunning(runId);
                    }
                    tracePublisher.publishComposeDone(runId);
                }
                recordDirectPreludeMemory(userId, taskContext, preludeContext);
                conversationHistoryService.appendTurn(userId, request.message(), preludeContext.answerPrefix(), preludeContext.songs());
                eventBus.publish(runId, AgentEvent.of("done", Map.of("runId", runId)));
                return;
            }
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

    static AgentTurnPlan directLocalPlaylistAddPlan(
            String userMessage,
            AgentTurnPlan turnPlan,
            List<ConversationHistoryMessage> history
    ) {
        if (turnPlan != null && turnPlan.hasOnlyLocalWriteTools() && !requiresReadMusicEvidence(userMessage)) {
            return turnPlan;
        }
        if (turnPlan != null && turnPlan.usesTools() && !turnPlan.readOnlyLoopToolCalls().isEmpty()) {
            return null;
        }
        if (requiresReadMusicEvidence(userMessage) || !isLocalPlaylistConfirmationIntent(userMessage, history)) {
            return null;
        }
        return new AgentTurnPlan(
                TurnDisposition.USE_TOOLS,
                "playlist",
                "refer_previous_song",
                userMessage == null || userMessage.isBlank() ? "确认收藏上一轮歌曲" : userMessage.strip(),
                new AgentTurnMemoryUse(true, List.of("lastResultSongs", "lastTargetSong"), "用户确认收藏上一轮歌曲。"),
                List.of(new AgentToolCall("add_song_to_musio_playlist", Map.of(
                        "playlistId", "default",
                        "songIndex", 1
                ))),
                1.0,
                "local_playlist_confirmation"
        );
    }

    static boolean isLocalPlaylistConfirmationIntent(String userMessage, List<ConversationHistoryMessage> history, AgentTaskMemory taskMemory) {
        if (isLocalPlaylistConfirmationIntent(userMessage, history)) {
            return true;
        }
        return taskMemory != null
                && taskMemory.pendingLocalPlaylistAdd() != null
                && isPlainConfirmationIntent(userMessage);
    }

    static boolean isLocalPlaylistConfirmationIntent(String userMessage, List<ConversationHistoryMessage> history) {
        String normalized = normalizeConfirmationText(userMessage);
        if (normalized.isBlank()) {
            return false;
        }
        if (containsAny(normalized,
                "确认收藏",
                "确认加入",
                "确认添加",
                "确认保存",
                "可以收藏",
                "可以加入",
                "是的收藏",
                "好的收藏")) {
            return true;
        }
        if (!List.of("确认", "可以", "好的", "好", "是的", "嗯", "嗯嗯", "收藏吧", "加入吧", "添加吧", "保存吧").contains(normalized)) {
            return false;
        }
        return recentAssistantMentionedPlaylistAdd(history);
    }

    private static boolean isPlainConfirmationIntent(String userMessage) {
        String normalized = normalizeConfirmationText(userMessage);
        return List.of("确认", "可以", "好的", "好", "是的", "嗯", "嗯嗯", "确认吧", "可以的", "行", "行的").contains(normalized);
    }

    private static boolean isLocalPlaylistCancelIntent(String userMessage) {
        String normalized = normalizeConfirmationText(userMessage);
        return containsAny(normalized, "取消收藏", "不用收藏", "不用加入", "先不收藏", "不要收藏", "不收藏了", "算了");
    }

    private static boolean recentAssistantMentionedPlaylistAdd(List<ConversationHistoryMessage> history) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        int inspected = 0;
        for (int index = history.size() - 1; index >= 0 && inspected < 4; index--) {
            ConversationHistoryMessage message = history.get(index);
            if (message == null || !"assistant".equals(message.role())) {
                continue;
            }
            inspected++;
            String content = normalizeConfirmationText(message.content());
            if (containsAny(content, "收藏", "加入歌单", "添加到歌单", "保存到歌单", "musio歌单")) {
                return true;
            }
        }
        return false;
    }

    private static boolean requiresReadMusicEvidence(String userMessage) {
        String normalized = normalizeConfirmationText(userMessage);
        return containsAny(normalized,
                "推荐",
                "搜索",
                "找歌",
                "找一首",
                "评论",
                "热评",
                "歌词",
                "详情",
                "专辑",
                "歌手");
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeConfirmationText(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("\\s+", "");
    }

    private AgentCapabilityManifest readOnlyManifest(AgentCapabilityManifest manifest) {
        if (manifest == null || manifest.isEmpty()) {
            return AgentCapabilityManifest.empty();
        }
        return new AgentCapabilityManifest(manifest.capabilities().stream()
                .filter(capability -> capability.effect() != CapabilityEffect.LOCAL_WRITE)
                .toList());
    }

    private void handleConfirmedLocalPlaylistAdd(
            String runId,
            MusioConfig.Ai ai,
            String userId,
            String userMessage,
            List<ConversationHistoryMessage> history,
            AgentTaskMemory taskMemory,
            boolean traceEnabled
    ) {
        PendingLocalPlaylistAdd pending = taskMemory == null ? null : taskMemory.pendingLocalPlaylistAdd();
        if (pending == null || pending.song() == null) {
            publishDirectAnswer(runId, ai, userId, userMessage, "我这边还没有待确认收藏的歌曲。你可以先让我推荐或搜索出歌曲，再告诉我收藏。", List.of(), traceEnabled);
            return;
        }
        AgentTurnPlan confirmPlan = new AgentTurnPlan(
                TurnDisposition.USE_TOOLS,
                "playlist",
                "refer_previous_song",
                userMessage,
                new AgentTurnMemoryUse(true, List.of("pendingLocalPlaylistAdd"), "用户确认执行本地 Musio 歌单写入。"),
                List.of(new AgentToolCall("add_song_to_musio_playlist", Map.of(
                        "playlistId", pending.playlistId(),
                        "songId", pending.song().id()
                ))),
                1.0,
                ""
        );
        LocalPlaylistAddResult result = executeMusioPlaylistAdd(runId, history, taskMemory, confirmPlan, List.of(pending.song()));
        recordLocalPlaylistResult(userId, result);
        taskMemoryService.clearPendingLocalPlaylistAdd(userId);
        publishSongCards(runId, result.success() ? List.of(result.song()) : List.of(pending.song()));
        publishDirectAnswer(runId, ai, userId, userMessage, result.answerText(), result.success() ? List.of(result.song()) : List.of(), traceEnabled);
    }

    private void handleLocalPlaylistCancel(
            String runId,
            MusioConfig.Ai ai,
            String userId,
            String userMessage,
            boolean traceEnabled
    ) {
        taskMemoryService.clearPendingLocalPlaylistAdd(userId);
        publishDirectAnswer(runId, ai, userId, userMessage, "好的，我先不收藏到本地 Musio 歌单。", List.of(), traceEnabled);
    }

    private void handleLocalPlaylistAddConfirmationRequest(
            String runId,
            MusioConfig.Ai ai,
            String userId,
            String userMessage,
            List<ConversationHistoryMessage> history,
            AgentTaskMemory taskMemory,
            AgentTurnPlan turnPlan,
            List<Song> currentSongs,
            boolean traceEnabled
    ) {
        PendingLocalPlaylistAdd pending = savePendingLocalPlaylistAdd(userId, userMessage, history, taskMemory, turnPlan, currentSongs);
        List<Song> songs = pending == null || pending.song() == null ? List.of() : List.of(pending.song());
        publishSongCards(runId, songs);
        publishDirectAnswer(runId, ai, userId, userMessage, pendingConfirmationText(pending), songs, traceEnabled);
    }

    private void publishDirectAnswer(
            String runId,
            MusioConfig.Ai ai,
            String userId,
            String userMessage,
            String answerText,
            List<Song> songs,
            boolean traceEnabled
    ) {
        boolean[] composeStarted = {false};
        publishAnswerText(runId, ai, answerText, traceEnabled, composeStarted);
        if (traceEnabled) {
            if (!composeStarted[0]) {
                tracePublisher.publishComposeRunning(runId);
            }
            tracePublisher.publishComposeDone(runId);
        }
        conversationHistoryService.appendTurn(userId, userMessage, answerText, songs);
        eventBus.publish(runId, AgentEvent.of("done", Map.of("runId", runId)));
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
        recordLocalPlaylistResult(userId, result);
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

    private List<AgentStepAction> loopInitialActions(AgentTurnPlan turnPlan, int requestedSongCount) {
        if (turnPlan == null || turnPlan.toolCalls() == null || turnPlan.toolCalls().isEmpty()) {
            return List.of();
        }
        return turnPlan.readOnlyLoopToolCalls().stream()
                .limit(1)
                .map(call -> new AgentStepAction(
                        AgentStepActionType.TOOL_CALL,
                        call.toolName(),
                        loopInitialArguments(call, requestedSongCount),
                        "执行已规划音乐能力",
                        turnPlan.confidence(),
                        "turn_planner_seed"
                ))
                .toList();
    }

    private Map<String, Object> loopInitialArguments(AgentToolCall call, int requestedSongCount) {
        if (call == null || call.arguments() == null || call.arguments().isEmpty()) {
            return Map.of();
        }
        Map<String, Object> arguments = new LinkedHashMap<>(call.arguments());
        if ("search_songs".equals(call.toolName()) && requestedSongCount > 0) {
            arguments.put("limit", Math.min(integer(arguments, "limit") == null ? requestedSongCount : integer(arguments, "limit"), requestedSongCount));
        }
        return arguments;
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

    private boolean shouldUseRecommendCommentPlaylistFastPath(
            String userMessage,
            AgentTaskContext taskContext,
            AgentCapabilityManifest capabilityManifest,
            int requestedSongCount
    ) {
        String normalized = normalizeIntentText(userMessage);
        boolean wantsOneSong = requestedSongCount == 1;
        boolean wantsRecommendationOrSearch = normalized.contains("推荐") || normalized.contains("搜索") || normalized.contains("找");
        boolean wantsComments = normalized.contains("评论") || normalized.contains("热评");
        boolean allowsLocalWrite = capabilityManifest != null && capabilityManifest.allowsLocalWrite();
        boolean musicTask = taskContext != null && taskContext.toolEvidenceExpected();
        return wantsOneSong && wantsRecommendationOrSearch && wantsComments && allowsLocalWrite && musicTask;
    }

    static boolean shouldUseDeterministicRecommendCommentPlaylistFallback(
            String userMessage,
            AgentTurnPlan turnPlan,
            AgentCapabilityManifest capabilityManifest,
            int requestedSongCount
    ) {
        if (turnPlan != null && turnPlan.usesTools()) {
            return false;
        }
        String normalized = normalizeIntentText(userMessage);
        if (normalized.isBlank()) {
            return false;
        }
        boolean wantsOneSong = requestedSongCount == 1
                || normalized.contains("一首")
                || normalized.contains("1首")
                || normalized.contains("一支")
                || normalized.contains("一曲");
        boolean wantsRecommendationOrSearch = containsAny(normalized, "推荐", "搜索", "找");
        boolean wantsComments = containsAny(normalized, "评论", "热评");
        boolean wantsPlaylistAdd = containsAny(normalized, "加入歌单", "加到歌单", "添加到歌单", "收藏", "保存");
        boolean allowsLocalWrite = capabilityManifest != null && capabilityManifest.allowsLocalWrite();
        return wantsOneSong && wantsRecommendationOrSearch && wantsComments && wantsPlaylistAdd && allowsLocalWrite;
    }

    private boolean shouldExecutePlannedLocalPlaylistAdd(AgentTurnPlan turnPlan, PreludeContext preludeContext) {
        if (turnPlan == null || !turnPlan.hasLocalWriteTools() || turnPlan.hasOnlyLocalWriteTools()) {
            return false;
        }
        if (preludeContext == null || preludeContext.directAnswer()) {
            return false;
        }
        return !preludeHasLocalPlaylistWrite(preludeContext);
    }

    private boolean preludeHasLocalPlaylistWrite(PreludeContext preludeContext) {
        return preludeContext != null
                && preludeContext.text() != null
                && preludeContext.text().contains("工具：add_song_to_musio_playlist");
    }

    private void recordDirectPreludeMemory(String userId, AgentTaskContext taskContext, PreludeContext preludeContext) {
        if (preludeContext == null || preludeContext.songs().isEmpty()) {
            return;
        }
        taskMemoryService.recordResultSongs(userId, preludeContext.songs());
        String completedTaskType = preludeHasLocalPlaylistWrite(preludeContext)
                ? "playlist"
                : taskContext == null || taskContext.taskType().isBlank() ? "search" : taskContext.taskType();
        taskMemoryService.recordLoopEvidence(
                userId,
                preludeContext.songs().getFirst(),
                completedTaskType,
                List.of("本轮直接回答已返回歌曲 " + preludeContext.songs().size() + " 首：" + songTitles(preludeContext.songs()))
        );
    }

    private void recordLocalPlaylistResult(String userId, LocalPlaylistAddResult result) {
        if (result == null || !result.success()) {
            return;
        }
        taskMemoryService.recordLoopEvidence(
                userId,
                result.song(),
                "playlist",
                List.of(result.answerText())
        );
    }

    private PendingLocalPlaylistAdd savePendingLocalPlaylistAdd(
            String userId,
            String userMessage,
            List<ConversationHistoryMessage> history,
            AgentTaskMemory taskMemory,
            AgentTurnPlan turnPlan,
            List<Song> currentSongs
    ) {
        AgentToolCall call = firstToolCall(turnPlan, "add_song_to_musio_playlist");
        Map<String, Object> arguments = call == null || call.arguments() == null ? Map.of() : call.arguments();
        String playlistId = text(arguments, "playlistId").isBlank() ? "default" : text(arguments, "playlistId");
        Song song = resolveMusioPlaylistSong(arguments, history, taskMemory, currentSongs);
        if (song == null) {
            return null;
        }
        PendingLocalPlaylistAdd pending = new PendingLocalPlaylistAdd(playlistId, song, userMessage, java.time.Instant.now());
        taskMemoryService.recordPendingLocalPlaylistAdd(userId, pending);
        return pending;
    }

    private String pendingConfirmationText(PendingLocalPlaylistAdd pending) {
        if (pending == null || pending.song() == null) {
            return "我还没能确定要收藏哪一首歌。你可以先让我推荐或搜索出歌曲卡片，我会等你确认后再加入本地 Musio 歌单。";
        }
        return "我已经找到这首歌：**%s**。\n\n如果你确定要加入本地 Musio 默认歌单，请回复“确认收藏”。".formatted(songTitle(pending.song()));
    }

    private PreludeContext recommendCommentPlaylistFastPath(
            String runId,
            String userMessage,
            List<ConversationHistoryMessage> history,
            AgentTaskMemory taskMemory,
            AgentTaskContext taskContext,
            AgentTurnPlan turnPlan,
            String stageName
    ) {
        String keyword = fastPathSearchKeyword(userMessage, taskContext, turnPlan);
        Map<String, Object> searchArguments = Map.of("keyword", keyword, "limit", 1);
        log.info(
                "TURN_EXECUTOR stage={} runId={} userId={} requestedSongCount=1 keyword={} commentLimit=1 playlistId=default",
                stageName,
                runId,
                AgentRunContext.userId().orElse("-"),
                keyword
        );
        String searchResultJson = toolExecutor.executeTool("search_songs", searchArguments)
                .orElseGet(() -> failureJson("search_songs_not_executable"));
        List<Song> songs = songsFromResultJson(searchResultJson).stream().limit(1).toList();
        if (songs.isEmpty()) {
            return new PreludeContext("""

                    本轮命中复合音乐任务快路径，但 search_songs 没有返回可用歌曲。
                    工具：search_songs
                    参数：%s
                    结果 JSON：%s

                    最终回答不得声称已经推荐、读取评论或加入歌单；请说明没有找到可收藏的真实歌曲结果。
                    """.formatted(writeJson(searchArguments), searchResultJson), true, List.of(), "");
        }

        Song song = songs.getFirst();
        Map<String, Object> commentArguments = Map.of("songId", song.id(), "limit", 1);
        String commentsResultJson = toolExecutor.executeTool("get_hot_comments", commentArguments)
                .orElseGet(() -> failureJson("get_hot_comments_not_executable"));

        AgentTurnPlan pendingPlan = new AgentTurnPlan(
                TurnDisposition.USE_TOOLS,
                "playlist",
                "new_task",
                userMessage,
                AgentTurnMemoryUse.none("复合任务快路径已确定本轮目标歌曲。"),
                List.of(new AgentToolCall("add_song_to_musio_playlist", Map.of(
                        "playlistId", "default",
                        "songId", song.id()
                ))),
                1.0,
                ""
        );
        PendingLocalPlaylistAdd pending = savePendingLocalPlaylistAdd(
                AgentRunContext.userId().orElse("local"),
                userMessage,
                history,
                taskMemory,
                pendingPlan,
                songs
        );
        String directAnswer = fastPathAnswer(song, commentsResultJson, pending);
        log.info(
                "TURN_EXECUTOR stage={} runId={} userId={} searchStatus=SUCCESS commentStatus={} pendingPlaylistAdd={} songCardCount={} songCardTitles={}",
                stageName,
                runId,
                AgentRunContext.userId().orElse("-"),
                successStatus(commentsResultJson),
                pending != null && pending.song() != null,
                songs.size(),
                songTitles(songs)
        );

        return new PreludeContext("""

                本轮命中复合音乐任务快路径，已按确定性顺序执行：
                步骤：fast.search
                工具：search_songs
                参数：%s
                状态：SUCCESS
                摘要：搜索到 1 首目标歌曲：%s
                结果 JSON：%s

                步骤：fast.comments
                工具：get_hot_comments
                参数：%s
                状态：%s
                结果 JSON：%s

                步骤：fast.playlist.confirmation
                状态：PENDING_CONFIRMATION
                摘要：已保存待确认收藏目标；未写入本地 Musio 歌单。

                本轮歌曲卡片只有 1 首：%s
                最终回答只能推荐这一首歌，不能扩展成 5 首或更多。
                如果评论工具成功，只总结最热门的 1 条评论。
                最终回答必须明确说明：尚未加入本地 Musio 歌单，需要用户回复“确认收藏”后才会写入。
                工具调用阶段已经结束；最终回答阶段不能再调用工具，也不能输出任何工具调用协议文本。
                """.formatted(
                writeJson(searchArguments),
                songTitle(song),
                searchResultJson,
                writeJson(commentArguments),
                successStatus(commentsResultJson),
                commentsResultJson,
                songTitle(song)
        ), true, songs, directAnswer, true);
    }

    private String fastPathAnswer(Song song, String commentsResultJson, PendingLocalPlaylistAdd pending) {
        StringBuilder builder = new StringBuilder();
        builder.append("我给你推荐这首：**")
                .append(songTitle(song))
                .append("**。");
        String comment = firstCommentText(commentsResultJson);
        if (!comment.isBlank()) {
            builder.append("\n\n最热门的评论是：")
                    .append(comment);
        }
        builder.append("\n\n")
                .append(pendingConfirmationText(pending));
        return builder.toString();
    }

    private String firstCommentText(String commentsResultJson) {
        try {
            JsonNode comments = objectMapper.readTree(commentsResultJson == null ? "{}" : commentsResultJson).path("comments");
            if (comments.isArray() && !comments.isEmpty()) {
                String text = comments.get(0).path("text").asText("");
                return text == null ? "" : text.strip();
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }

    private String fastPathSearchKeyword(String userMessage, AgentTaskContext taskContext, AgentTurnPlan turnPlan) {
        AgentToolCall searchCall = firstToolCall(turnPlan, "search_songs");
        if (searchCall != null && !text(searchCall.arguments(), "keyword").isBlank()) {
            return text(searchCall.arguments(), "keyword");
        }
        String artist = artistFromRecommendRequest(userMessage);
        if (!artist.isBlank()) {
            return artist;
        }
        if (taskContext != null && !taskContext.searchKeyword().isBlank()) {
            return taskContext.searchKeyword();
        }
        return userMessage == null || userMessage.isBlank() ? "音乐推荐" : userMessage.strip();
    }

    private String artistFromRecommendRequest(String userMessage) {
        String text = userMessage == null ? "" : userMessage.replaceAll("\\s+", "");
        if (text.isBlank()) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("推荐(?:一首|1首)?(.+?)的(?:歌曲|歌)")
                .matcher(text);
        if (matcher.find()) {
            return matcher.group(1).strip();
        }
        return "";
    }

    private static String normalizeIntentText(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("\\s+", "");
    }

    private String successStatus(String resultJson) {
        try {
            return objectMapper.readTree(resultJson == null ? "{}" : resultJson).path("success").asBoolean(false)
                    ? "SUCCESS"
                    : "FAILURE";
        } catch (Exception e) {
            return "FAILURE";
        }
    }

    private String failureJson(String message) {
        return "{\"success\":false,\"message\":\"" + escapeJson(message) + "\"}";
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String songTitle(Song song) {
        if (song == null) {
            return "未知歌曲";
        }
        String title = song.title() == null || song.title().isBlank() ? song.id() : song.title();
        String artists = song.artists() == null || song.artists().isEmpty() ? "" : " - " + String.join(" / ", song.artists());
        return title + artists;
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
            AgentTurnPlan turnPlan,
            AgentCapabilityManifest capabilityManifest,
            int requestedSongCount
    ) {
        AgentLoopEvidence evidence = agentLoopRunner.run(ai, new AgentLoopState(
                runId,
                userId,
                userMessage,
                history,
                taskMemory,
                List.of(),
                0,
                capabilityManifest,
                requestedSongCount
        ), loopInitialActions(turnPlan, requestedSongCount));
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
            if (taskContext.toolEvidenceExpected() || capabilityManifest.allowsLocalWrite()) {
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

                本轮 Agent loop 已根据工具 observation 逐步执行这些音乐能力：
                %s
                本轮歌曲卡片顺序是：
                %s
                最终回答必须基于这些真实 observations；不要声称调用了没有出现在上方结果里的工具。
                如果最终回答正文列出歌曲，必须严格按照上面的歌曲卡片顺序输出，不要重排、补歌或改写歌手。
                工具状态以“状态”行和 result.success 为准：success=true 的工具不得写成失败、HTTP 500 或没有拿到结果。
                如果评论、歌词或详情 observation 不存在，正文不得声称已经读取到对应内容。
                如果出现 add_song_to_musio_playlist observation，最终回答必须明确说明歌曲已加入本地 Musio 默认歌单，不要写成 QQ 音乐账号歌单。
                工具调用阶段已经结束；最终回答阶段不能再调用工具，也不能输出 <tool_call>、<function=...>、JSON 工具调用或任何工具调用协议文本。
                你必须直接生成面向用户的中文自然语言回答。
                """.formatted(loopExecutionContext(evidence.observations()), songTitles(evidence.songs())),
                true,
                evidence.songs(),
                "");
    }

    private int requestedSongCount(String userMessage, AgentTaskContext taskContext) {
        int explicitCount = explicitSongCount(userMessage);
        if (explicitCount > 0) {
            return explicitCount;
        }
        return taskContext == null ? 0 : Math.max(0, taskContext.searchLimit());
    }

    private int explicitSongCount(String userMessage) {
        String text = userMessage == null ? "" : userMessage.replaceAll("\\s+", "");
        if (text.isBlank()) {
            return 0;
        }
        java.util.regex.Matcher digitMatcher = java.util.regex.Pattern
                .compile("(\\d{1,2})(首|个|支|曲)")
                .matcher(text);
        if (digitMatcher.find()) {
            try {
                return Math.max(1, Math.min(20, Integer.parseInt(digitMatcher.group(1))));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        if (text.contains("一首") || text.contains("1首") || text.contains("一支") || text.contains("一曲") || text.contains("一个")) {
            return 1;
        }
        return 0;
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

    private PreludeContext withPlaylistPendingConfirmation(PreludeContext context, PendingLocalPlaylistAdd pending) {
        PreludeContext base = context == null ? PreludeContext.empty() : context;
        List<Song> songs = pending == null || pending.song() == null
                ? base.songs()
                : mergeSongs(base.songs(), List.of(pending.song()));
        String text = (base.text() + "\n\n" + playlistPendingConfirmationContext(pending)).strip();
        return new PreludeContext(text, true, songs, base.answerPrefix());
    }

    private String playlistPendingConfirmationContext(PendingLocalPlaylistAdd pending) {
        return """

                本轮用户表达了加入本地 Musio 歌单的意图，但系统策略要求所有本地 Musio 歌单写入都必须先确认。
                状态：PENDING_CONFIRMATION
                待确认目标：%s

                最终回答不得声称已经加入歌单。必须说明：尚未写入本地 Musio 歌单，用户回复“确认收藏”后才会真正加入。
                """.formatted(pending == null ? "未能确定歌曲" : songTitle(pending.song())).strip();
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

    private record PreludeContext(String text, boolean evidenceBound, List<Song> songs, String answerPrefix, boolean directAnswer) {
        static PreludeContext empty() {
            return new PreludeContext("", false, List.of(), "");
        }

        private PreludeContext(String text, boolean evidenceBound, List<Song> songs, String answerPrefix) {
            this(text, evidenceBound, songs, answerPrefix, false);
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
