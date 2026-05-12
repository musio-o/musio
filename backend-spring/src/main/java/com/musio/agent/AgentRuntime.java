package com.musio.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musio.agent.capability.AgentCapabilityManifest;
import com.musio.agent.capability.AgentCapabilityRegistry;
import com.musio.agent.capability.CapabilityEffect;
import com.musio.ai.SpringAiChatModelFactory;
import com.musio.agent.loop.AgentLoopEvidence;
import com.musio.agent.loop.AgentLoopOutcome;
import com.musio.agent.loop.AgentLoopRunner;
import com.musio.agent.loop.AgentLoopState;
import com.musio.agent.loop.AgentObservation;
import com.musio.agent.loop.AgentObservationStatus;
import com.musio.agent.loop.AgentStepAction;
import com.musio.agent.loop.AgentStepActionType;
import com.musio.agent.trace.AgentTracePublisher;
import com.musio.agent.recommendation.RecommendationSlot;
import com.musio.agent.recommendation.RecommendationSlots;
import com.musio.config.MusioConfig;
import com.musio.config.MusioConfigService;
import com.musio.events.AgentEventBus;
import com.musio.model.AgentEvent;
import com.musio.model.ChatConfirmation;
import com.musio.model.ChatRequest;
import com.musio.model.MusicProfileMemory;
import com.musio.model.AgentTaskMemory;
import com.musio.model.AgentTaskRecommendationSlot;
import com.musio.model.PendingLocalPlaylistAdd;
import com.musio.model.Song;
import com.musio.model.SourceContext;
import com.musio.memory.AgentTaskMemoryService;
import com.musio.memory.MusicProfileService;
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
import java.util.LinkedHashSet;
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
    private final AgentMemoryRouter memoryRouter;
    private final AgentLoopRunner agentLoopRunner;
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
            AgentMemoryRouter memoryRouter,
            AgentLoopRunner agentLoopRunner,
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
        this.memoryRouter = memoryRouter;
        this.agentLoopRunner = agentLoopRunner;
        this.policyGate = policyGate;
        this.objectMapper = objectMapper;
    }

    public void start(String runId, ChatRequest request) {
        MusioConfig.Ai ai = configService.config().ai();
        AgentRunContext.setRunId(runId);
        try {
            String userId = conversationHistoryService.normalizeUserId(request.userId());
            AgentRunContext.setUserId(userId);
            SourceContext sourceContext = request.sourceContext(userId);
            AgentRunContext.setSourceContext(sourceContext);
            List<ConversationHistoryMessage> history = conversationHistoryService.load(userId);
            AgentTaskMemory taskMemory = taskMemoryService.read(userId);
            AgentTaskMemory previousTaskMemory = taskMemory;
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
            turnPlan = memoryRouter == null
                    ? turnPlan
                    : memoryRouter.repairPlan(request.message(), turnPlan, taskMemory).orElse(turnPlan);
            AgentTaskContext taskContext = turnPlan.toLegacyTaskContext(request.message());
            List<RecommendationSlot> recommendationSlots = AgentGoalNormalizer.recommendationSlots(turnPlan, taskContext, request.message());
            int requestedSongCount = requestedSongCount(request.message(), taskContext, recommendationSlots);
            AgentGoal goal = AgentGoal.from(request.message(), turnPlan, taskContext, requestedSongCount, recommendationSlots);
            AgentCapabilityManifest capabilityManifest = policyGate.manifestFor(goal, turnPlan);
            boolean deferLocalPlaylistWrite = false;
            AgentCapabilityManifest executionCapabilityManifest = capabilityManifest;
            AgentGoal executionGoal = goal;
            boolean traceEnabled = true;
            AgentRunContext.setTraceEnabled(traceEnabled);
            logTurnRuntimePlan(runId, ai, turnPlan, traceEnabled);
            logAgentGoal(runId, ai, goal, executionCapabilityManifest);
            tracePublisher.publishPlanningDone(runId, String.valueOf(turnPlan.disposition()), turnPlan.taskType(), toolNameList(turnPlan.toolCalls()));
            if (isLocalPlaylistCancelIntent(request.message())) {
                handleLocalPlaylistCancel(runId, ai, userId, request.visibleMessage(), traceEnabled);
                return;
            }
            if (isLocalPlaylistConfirmationIntent(request.message(), history, taskMemory)) {
                handleConfirmedLocalPlaylistAdd(runId, ai, userId, request.message(), request.visibleMessage(), history, taskMemory, traceEnabled);
                return;
            }
            boolean shouldRunLoop = turnPlan.usesTools()
                    || taskContext.toolEvidenceExpected()
                    || goal.musicTask()
                    || goal.localWriteIntent()
                    || tracePublisher.shouldTraceUserMessage(request.message());
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
            if (shouldRunLoop) {
                try (TraceHeartbeat ignored = progressHeartbeat(
                        runId,
                        "tool.execution",
                        "tool",
                        "执行音乐能力",
                        "正在启动音乐能力 loop。",
                        "还在等待音乐能力返回结果。",
                        "还在根据工具结果决定下一步。"
                )) {
                    preludeContext = agentLoopPreludeContext(ai, runId, userId, request.message(), history, taskMemory, previousTaskMemory, taskContext, turnPlan, executionCapabilityManifest, requestedSongCount, executionGoal, goal.localWriteIntent());
                }
                tracePublisher.publishProgressDone(runId, "tool.execution", "tool", "执行音乐能力", "音乐能力阶段完成，准备整理回答。", Map.of());
            } else {
                preludeContext = PreludeContext.empty();
            }
            logComposerPolicy(runId, ai, turnPlan, preludeContext);
            Prompt prompt = conversationPrompt(history, request.message(), taskContext.promptContext(), preludeContext);

            AgentAnswerStreamGuard answerGuard = new AgentAnswerStreamGuard();
            boolean[] composeStarted = {false};
            if (!preludeContext.answerPrefix().isBlank()) {
                publishAnswerText(runId, ai, preludeContext.answerPrefix(), traceEnabled, composeStarted);
            }
            publishSongCards(runId, preludeContext.songs());
            publishConfirmationRequest(runId, preludeContext.confirmation());
            if (preludeContext.directAnswer()) {
                if (traceEnabled) {
                    if (!composeStarted[0]) {
                        tracePublisher.publishComposeRunning(runId);
                    }
                    tracePublisher.publishComposeDone(runId);
                }
                recordDirectPreludeMemory(userId, taskContext, preludeContext);
                conversationHistoryService.appendTurn(userId, request.visibleMessage(), preludeContext.answerPrefix(), preludeContext.songs(), preludeContext.confirmation());
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
            conversationHistoryService.appendTurn(userId, request.visibleMessage(), answerText, preludeContext.songs(), preludeContext.confirmation());

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

    static AgentCapabilityManifest readOnlyManifest(AgentCapabilityManifest manifest) {
        if (manifest == null || manifest.isEmpty()) {
            return AgentCapabilityManifest.empty();
        }
        return new AgentCapabilityManifest(manifest.capabilities().stream()
                .filter(capability -> capability.effect() == CapabilityEffect.READ)
                .toList());
    }

    static AgentGoal withoutLocalPlaylistWriteRequirement(AgentGoal goal) {
        if (goal == null || (!goal.localWriteIntent() && !goal.requiredOutcomes().contains(AgentRequiredOutcome.LOCAL_PLAYLIST_WRITE))) {
            return goal;
        }
        return new AgentGoal(
                goal.userMessage(),
                goal.effectiveRequest(),
                goal.taskType(),
                goal.contextMode(),
                goal.musicTask(),
                goal.toolEvidenceExpected(),
                false,
                goal.accountWriteIntent(),
                goal.requestedSongCount(),
                goal.recommendationSlots(),
                goal.avoidSongTitles(),
                goal.requiredOutcomes().stream()
                        .filter(outcome -> outcome != AgentRequiredOutcome.LOCAL_PLAYLIST_WRITE)
                        .toList()
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

    private void handleConfirmedLocalPlaylistAdd(
            String runId,
            MusioConfig.Ai ai,
            String userId,
            String userMessage,
            String visibleUserMessage,
            List<ConversationHistoryMessage> history,
            AgentTaskMemory taskMemory,
            boolean traceEnabled
    ) {
        PendingLocalPlaylistAdd pending = taskMemory == null ? null : taskMemory.pendingLocalPlaylistAdd();
        List<Song> selectedSongs = selectedPendingSongs(pending, userMessage);
        if (pending == null || selectedSongs.isEmpty()) {
            publishDirectAnswer(runId, ai, userId, visibleUserMessage, "我这边还没有待确认收藏的歌曲。你可以先让我推荐或搜索出歌曲，再告诉我收藏。", List.of(), traceEnabled);
            return;
        }
        Map<String, Object> addArguments = pendingAddArguments(pending, selectedSongs);
        AgentTurnPlan confirmPlan = new AgentTurnPlan(
                TurnDisposition.USE_TOOLS,
                "playlist",
                "refer_previous_song",
                userMessage,
                new AgentTurnMemoryUse(true, List.of("pendingLocalPlaylistAdd"), "用户确认执行本地 Musio 歌单写入。"),
                List.of(new AgentToolCall("add_song_to_musio_playlist", addArguments)),
                1.0,
                ""
        );
        AgentTaskContext taskContext = confirmPlan.toLegacyTaskContext(userMessage);
        AgentGoal goal = AgentGoal.from(userMessage, confirmPlan, taskContext, selectedSongs.size());
        AgentCapabilityManifest capabilityManifest = policyGate.manifestFor(goal, confirmPlan);
        AgentLoopOutcome outcome = agentLoopRunner.runOutcome(
                ai,
                new AgentLoopState(
                        runId,
                        userId,
                        userMessage,
                        history,
                        taskMemory,
                        List.of(),
                        0,
                        capabilityManifest,
                        selectedSongs.size(),
                        goal
                ),
                List.of(new AgentStepAction(
                        AgentStepActionType.TOOL_CALL,
                        AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST,
                        addArguments,
                        "收藏到 Musio 歌单",
                        1.0,
                        "pending_local_playlist_confirmation"
                ))
        );
        AgentLoopEvidence evidence = outcome.evidence();
        if (evidence.hasObservations()) {
            taskMemoryService.recordLoopEvidence(
                    userId,
                    evidence.targetSong(),
                    evidence.completedTaskType(),
                    evidence.observationSummaries()
            );
            recordStructuredLoopMemory(userId, goal, evidence);
        }
        taskMemoryService.clearPendingLocalPlaylistAdd(userId);
        List<Song> songs = evidence.songs().isEmpty() ? selectedSongs : evidence.songs();
        publishSongCards(runId, songs);
        publishDirectAnswer(runId, ai, userId, visibleUserMessage, localPlaylistConfirmationAnswer(outcome, pending), songs, traceEnabled);
    }

    private List<Song> selectedPendingSongs(PendingLocalPlaylistAdd pending, String userMessage) {
        if (pending == null || pending.songs().isEmpty()) {
            return List.of();
        }
        List<String> selectedIds = selectedSongIds(userMessage);
        if (selectedIds.isEmpty()) {
            return pending.songs();
        }
        Map<String, Song> songsById = new LinkedHashMap<>();
        for (Song song : pending.songs()) {
            songsById.put(song.id(), song);
        }
        List<Song> selected = new ArrayList<>();
        for (String selectedId : selectedIds) {
            Song song = songsById.get(selectedId);
            if (song != null) {
                selected.add(song);
            }
        }
        return selected;
    }

    private List<String> selectedSongIds(String userMessage) {
        String message = userMessage == null ? "" : userMessage.strip();
        int chineseSeparator = message.indexOf('：');
        int asciiSeparator = message.indexOf(':');
        int separator = chineseSeparator >= 0 && asciiSeparator >= 0
                ? Math.min(chineseSeparator, asciiSeparator)
                : Math.max(chineseSeparator, asciiSeparator);
        if (separator < 0 || separator >= message.length() - 1) {
            return List.of();
        }
        String rawIds = message.substring(separator + 1);
        List<String> ids = new ArrayList<>();
        for (String token : rawIds.split("[,，\\s]+")) {
            String id = token.strip();
            if (!id.isBlank() && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private Map<String, Object> pendingAddArguments(PendingLocalPlaylistAdd pending, List<Song> selectedSongs) {
        if (selectedSongs.size() == 1) {
            Song song = selectedSongs.getFirst();
            return Map.of(
                    "playlistId", pending.playlistId(),
                    "songId", song.id(),
                    "songTitle", song.title() == null ? "" : song.title(),
                    "artist", song.artists() == null ? "" : String.join(" / ", song.artists())
            );
        }
        return Map.of(
                "playlistId", pending.playlistId(),
                "songIds", selectedSongs.stream().map(Song::id).toList()
        );
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

    private String localPlaylistConfirmationAnswer(AgentLoopOutcome outcome, PendingLocalPlaylistAdd pending) {
        AgentLoopState finalState = outcome == null ? null : outcome.finalState();
        List<AgentObservation> observations = finalState == null ? List.of() : finalState.observations();
        for (int index = observations.size() - 1; index >= 0; index--) {
            AgentObservation observation = observations.get(index);
            if (!AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST.equals(observation.toolName())) {
                continue;
            }
            String summary = resultSummary(observation.resultJson());
            if (!summary.isBlank()) {
                return summary;
            }
            if (observation.status() != AgentObservationStatus.SUCCESS) {
                return "收藏没有完成：" + observation.plannerSummary();
            }
        }
        return "还没能确认收藏结果：" + songTitle(pending == null ? null : pending.song()) + "。";
    }

    private String resultSummary(String resultJson) {
        try {
            var root = objectMapper.readTree(resultJson == null ? "{}" : resultJson);
            String summary = root.path("summary").asText("");
            if (!summary.isBlank()) {
                return summary.strip();
            }
            String message = root.path("message").asText("");
            return message.isBlank() ? "" : message.strip();
        } catch (Exception e) {
            return "";
        }
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

    private String songTitle(Song song) {
        if (song == null) {
            return "未知歌曲";
        }
        String title = song.title() == null || song.title().isBlank() ? song.id() : song.title();
        String artists = song.artists() == null || song.artists().isEmpty() ? "" : " - " + String.join(" / ", song.artists());
        return title + artists;
    }

    private PreludeContext agentLoopPreludeContext(
            MusioConfig.Ai ai,
            String runId,
            String userId,
            String userMessage,
            List<ConversationHistoryMessage> history,
            AgentTaskMemory taskMemory,
            AgentTaskMemory previousTaskMemory,
            AgentTaskContext taskContext,
            AgentTurnPlan turnPlan,
            AgentCapabilityManifest capabilityManifest,
            int requestedSongCount,
            AgentGoal goal,
            boolean deferLocalPlaylistWrite
    ) {
        AgentLoopOutcome outcome = agentLoopRunner.runOutcome(ai, new AgentLoopState(
                runId,
                userId,
                userMessage,
                history,
                taskMemory,
                List.of(),
                0,
                capabilityManifest,
                requestedSongCount,
                goal
        ));
        AgentLoopEvidence evidence = outcome.evidence();
        PendingLocalPlaylistAdd pendingLocalPlaylistAdd = deferLocalPlaylistWrite
                ? savePendingLocalPlaylistAdd(userId, userMessage, evidence, taskMemory, previousTaskMemory)
                : null;
        if (evidence.hasObservations()) {
            taskMemoryService.recordLoopEvidence(
                    userId,
                    evidence.targetSong(),
                    evidence.completedTaskType(),
                    evidence.observationSummaries()
            );
            recordStructuredLoopMemory(userId, goal, evidence);
        }
        log.info(
                "TURN_EXECUTOR stage=agent_loop runId={} userId={} taskType={} outcome={} observationCount={} songCardCount={} songCardTitles={}",
                runId,
                userId,
                taskContext.taskType(),
                outcome.type(),
                evidence.observations().size(),
                evidence.songs().size(),
                songTitles(evidence.songs())
        );
        if (!evidence.hasObservations()) {
            if (pendingLocalPlaylistAdd != null) {
                return new PreludeContext("""

                        本轮用户表达了加入本地 Musio 歌单的意图，但本地歌单写入必须二次确认。
                        本轮尚未执行 add_song_to_musio_playlist，不能说已经加入歌单。
                        已保存待确认收藏目标：%s。
                        最终回答必须明确说明：尚未加入本地 Musio 歌单，可以点击确认按钮或回复“确认收藏”后写入。
                        工具调用阶段已经结束；最终回答阶段不能再调用工具，也不能输出 <tool_call>、<function=...>、JSON 工具调用或任何工具调用协议文本。
                        你必须直接生成面向用户的中文自然语言回答。
                        """.formatted(songTitle(pendingLocalPlaylistAdd.song())),
                        true,
                        List.of(pendingLocalPlaylistAdd.song()),
                        "",
                        confirmationFor(pendingLocalPlaylistAdd));
            }
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
                Loop outcome：%s
                Outcome reason：%s
                %s
                本轮歌曲卡片顺序是：
                %s
                最终回答必须基于这些真实 observations；不要声称调用了没有出现在上方结果里的工具。
                如果最终回答正文列出歌曲，必须严格按照上面的歌曲卡片顺序输出，不要重排、补歌或改写歌手。
                工具状态以“状态”行和 result.success 为准：success=true 的工具不得写成失败、HTTP 500 或没有拿到结果。
                如果评论、歌词或详情 observation 不存在，正文不得声称已经读取到对应内容。
                如果出现 add_song_to_musio_playlist observation，最终回答必须明确说明歌曲已加入本地 Musio 默认歌单，不要写成 QQ 音乐账号歌单。
                %s
                工具调用阶段已经结束；最终回答阶段不能再调用工具，也不能输出 <tool_call>、<function=...>、JSON 工具调用或任何工具调用协议文本。
                你必须直接生成面向用户的中文自然语言回答。
                """.formatted(
                        outcome.type(),
                        outcome.reason(),
                        loopExecutionContext(evidence.observations()),
                        songTitles(evidence.songs()),
                        pendingLocalPlaylistInstruction(pendingLocalPlaylistAdd)
                ),
                true,
                evidence.songs(),
                "",
                confirmationFor(pendingLocalPlaylistAdd));
    }

    private PendingLocalPlaylistAdd savePendingLocalPlaylistAdd(
            String userId,
            String userMessage,
            AgentLoopEvidence evidence,
            AgentTaskMemory taskMemory,
            AgentTaskMemory previousTaskMemory
    ) {
        List<Song> targetSongs = pendingLocalPlaylistSongs(userMessage, evidence, taskMemory, previousTaskMemory);
        if (targetSongs.isEmpty()) {
            taskMemoryService.clearPendingLocalPlaylistAdd(userId);
            return null;
        }
        PendingLocalPlaylistAdd pending = new PendingLocalPlaylistAdd("default", targetSongs, userMessage, null);
        taskMemoryService.recordPendingLocalPlaylistAdd(userId, pending);
        return pending;
    }

    private List<Song> pendingLocalPlaylistSongs(String userMessage, AgentLoopEvidence evidence, AgentTaskMemory taskMemory, AgentTaskMemory previousTaskMemory) {
        List<Song> evidenceSongs = evidence == null ? List.of() : evidence.songs();
        if (!evidenceSongs.isEmpty()) {
            int writeCount = pendingLocalPlaylistWriteCount(userMessage, evidenceSongs.size());
            return evidenceSongs.stream()
                    .filter(song -> song != null && song.id() != null && !song.id().isBlank())
                    .limit(writeCount <= 0 ? 1 : writeCount)
                    .toList();
        }
        Song fallback = pendingLocalPlaylistSong(evidence, taskMemory, previousTaskMemory);
        return fallback == null || fallback.id() == null || fallback.id().isBlank() ? List.of() : List.of(fallback);
    }

    private int pendingLocalPlaylistWriteCount(String userMessage, int availableSongCount) {
        if (availableSongCount <= 1) {
            return availableSongCount;
        }
        String normalized = normalizeConfirmationText(userMessage);
        if (containsAny(normalized, "全部加入", "全都加入", "都加入歌单", "都收藏", "这几首加入", "这些歌加入", "全部收藏")) {
            return availableSongCount;
        }
        int count = 0;
        count += countOccurrences(normalized, "加入歌单");
        count += countOccurrences(normalized, "添加到歌单");
        count += countOccurrences(normalized, "保存到歌单");
        count += countOccurrences(normalized, "收藏到歌单");
        if (count == 0) {
            count += countOccurrences(normalized, "收藏");
        }
        return Math.max(1, Math.min(availableSongCount, count));
    }

    private int countOccurrences(String value, String needle) {
        if (value == null || value.isBlank() || needle == null || needle.isBlank()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private Song pendingLocalPlaylistSong(AgentLoopEvidence evidence, AgentTaskMemory taskMemory, AgentTaskMemory previousTaskMemory) {
        if (evidence != null) {
            if (evidence.targetSong() != null) {
                return evidence.targetSong();
            }
            if (!evidence.songs().isEmpty()) {
                return evidence.songs().getFirst();
            }
        }
        Song fromCurrentMemory = memoryTargetSong(taskMemory);
        if (fromCurrentMemory != null) {
            return fromCurrentMemory;
        }
        return memoryTargetSong(previousTaskMemory);
    }

    private Song memoryTargetSong(AgentTaskMemory memory) {
        if (memory == null) {
            return null;
        }
        if (memory.lastTargetSong() != null) {
            return memory.lastTargetSong();
        }
        return memory.lastResultSongs().isEmpty() ? null : memory.lastResultSongs().getFirst();
    }

    private String pendingLocalPlaylistInstruction(PendingLocalPlaylistAdd pending) {
        if (pending == null || pending.songs().isEmpty()) {
            return "";
        }
        return """
                本轮用户表达了加入本地 Musio 歌单的意图，但本地歌单写入必须二次确认。
                本轮尚未执行 add_song_to_musio_playlist，不能说已经加入歌单。
                已保存待确认收藏目标：%s。
                最终回答必须明确说明：尚未加入本地 Musio 歌单，可以点击确认按钮或回复“确认收藏”后写入。
                """.formatted(songTitles(pending.songs())).strip();
    }

    private void recordStructuredLoopMemory(String userId, AgentGoal goal, AgentLoopEvidence evidence) {
        if (goal == null || evidence == null || !evidence.hasObservations()) {
            return;
        }
        taskMemoryService.recordStructuredEvidence(
                userId,
                goal.requiredOutcomes().stream()
                        .map(Enum::name)
                        .toList(),
                recommendationSlotMemories(goal.recommendationSlots(), evidence),
                successfulToolNames(evidence),
                goal.localWriteIntent() ? List.of(AgentCapabilityRegistry.ADD_SONG_TO_MUSIO_PLAYLIST) : List.of()
        );
    }

    private List<String> successfulToolNames(AgentLoopEvidence evidence) {
        if (evidence == null || evidence.observations().isEmpty()) {
            return List.of();
        }
        return evidence.observations().stream()
                .filter(observation -> observation.status() == AgentObservationStatus.SUCCESS)
                .map(AgentObservation::toolName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
    }

    private List<AgentTaskRecommendationSlot> recommendationSlotMemories(List<RecommendationSlot> recommendationSlots, AgentLoopEvidence evidence) {
        List<RecommendationSlot> slots = RecommendationSlots.normalize(recommendationSlots);
        if (slots.isEmpty() || evidence == null || evidence.observations().isEmpty()) {
            return List.of();
        }
        Map<String, SlotSongRefs> refsBySlot = new LinkedHashMap<>();
        for (RecommendationSlot slot : slots) {
            refsBySlot.put(slot.slotId(), new SlotSongRefs(new LinkedHashSet<>(), new LinkedHashSet<>()));
        }
        String singleSlotId = slots.size() == 1 ? slots.getFirst().slotId() : "";
        for (AgentObservation observation : evidence.observations()) {
            if (observation.status() != AgentObservationStatus.SUCCESS
                    || !AgentCapabilityRegistry.RECOMMEND_SONGS.equals(observation.toolName())
                    || observation.resultJson().isBlank()) {
                continue;
            }
            try {
                var root = objectMapper.readTree(observation.resultJson());
                readRecommendationSlotSongs(root.path("songs"), refsBySlot, singleSlotId);
                readRecommendationSlotSongs(root.path("recommendations"), refsBySlot, singleSlotId);
            } catch (Exception ignored) {
                // Structured memory is an optimization. The observation summary remains the durable evidence.
            }
        }
        List<AgentTaskRecommendationSlot> values = new ArrayList<>();
        for (RecommendationSlot slot : slots) {
            SlotSongRefs refs = refsBySlot.getOrDefault(slot.slotId(), new SlotSongRefs(new LinkedHashSet<>(), new LinkedHashSet<>()));
            values.add(new AgentTaskRecommendationSlot(
                    slot.slotId(),
                    slot.targetType(),
                    slot.target(),
                    slot.count(),
                    List.copyOf(refs.songIds()),
                    List.copyOf(refs.songTitles())
            ));
        }
        return values;
    }

    private void readRecommendationSlotSongs(com.fasterxml.jackson.databind.JsonNode node, Map<String, SlotSongRefs> refsBySlot, String fallbackSlotId) {
        if (!node.isArray()) {
            return;
        }
        for (com.fasterxml.jackson.databind.JsonNode item : node) {
            String slotId = item.path("slotId").asText("");
            if (slotId.isBlank()) {
                slotId = fallbackSlotId;
            }
            SlotSongRefs refs = refsBySlot.get(slotId);
            if (refs == null) {
                continue;
            }
            String songId = item.path("id").asText(item.path("songId").asText(""));
            String title = item.path("title").asText("");
            if (!songId.isBlank()) {
                refs.songIds().add(songId.strip());
            }
            if (!title.isBlank()) {
                refs.songTitles().add(title.strip());
            }
        }
    }

    private int requestedSongCount(String userMessage, AgentTaskContext taskContext, List<RecommendationSlot> recommendationSlots) {
        int slotTotal = RecommendationSlots.totalCount(recommendationSlots);
        if (slotTotal > 0) {
            return slotTotal;
        }
        int explicitCount = RecommendationSlots.explicitSongCount(userMessage);
        if (explicitCount > 0) {
            return explicitCount;
        }
        return taskContext == null ? 0 : Math.max(0, taskContext.searchLimit());
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

    private void publishConfirmationRequest(String runId, ChatConfirmation confirmation) {
        if (confirmation == null) {
            return;
        }
        eventBus.publish(runId, AgentEvent.of("confirmation_request", Map.of(
                "runId", runId,
                "confirmation", confirmation
        )));
    }

    private ChatConfirmation confirmationFor(PendingLocalPlaylistAdd pending) {
        if (pending == null || pending.songs().isEmpty()) {
            return null;
        }
        String title = pending.songs().size() > 1 ? "选择要收藏的歌曲" : "收藏到 Musio 歌单";
        String description = pending.songs().size() > 1
                ? "已为你准备 %s 首待加入本地 Musio 默认歌单的歌曲。".formatted(pending.songs().size())
                : "将《%s》加入本地 Musio 默认歌单。".formatted(songTitle(pending.song()));
        return new ChatConfirmation(
                "",
                "local_playlist_add",
                title,
                description,
                "确认收藏",
                "取消收藏",
                pending.song(),
                pending.songs(),
                pending.songs().size() > 1 ? "multiple" : "single",
                pending.songs().stream().map(Song::id).toList()
        );
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
                "TURN_RUNTIME stage=plan_validator runId={} userId={} provider={} model={} disposition={} taskType={} contextMode={} memoryUse={} requiredOutcomes={} toolCallCount={} toolNames={} validationStatus={} fallbackReason={} traceEnabled={}",
                runId,
                AgentRunContext.userId().orElse("-"),
                ai == null ? "" : ai.provider(),
                ai == null ? "" : ai.model(),
                plan.disposition(),
                plan.taskType(),
                plan.contextMode(),
                plan.memoryUse() == null ? "none" : plan.memoryUse().summary(),
                plan.requiredOutcomes() == null || plan.requiredOutcomes().isEmpty() ? "none" : plan.requiredOutcomes(),
                plan.toolCalls() == null ? 0 : plan.toolCalls().size(),
                toolNames(plan.toolCalls()),
                plan.disposition() == TurnDisposition.USE_TOOLS ? "accepted" : "respond_only",
                plan.fallbackReason(),
                traceEnabled
        );
    }

    private void logAgentGoal(String runId, MusioConfig.Ai ai, AgentGoal goal, AgentCapabilityManifest manifest) {
        log.info(
                "AGENT_GOAL stage=goal_analyzer runId={} userId={} provider={} model={} taskType={} contextMode={} musicTask={} toolEvidenceExpected={} localWriteIntent={} accountWriteIntent={} requestedSongCount={} requiredOutcomes={} manifestTools={}",
                runId,
                AgentRunContext.userId().orElse("-"),
                ai == null ? "" : ai.provider(),
                ai == null ? "" : ai.model(),
                goal == null ? "" : goal.taskType(),
                goal == null ? "" : goal.contextMode(),
                goal != null && goal.musicTask(),
                goal != null && goal.toolEvidenceExpected(),
                goal != null && goal.localWriteIntent(),
                goal != null && goal.accountWriteIntent(),
                goal == null ? 0 : goal.requestedSongCount(),
                goal == null || goal.requiredOutcomes().isEmpty() ? "none" : goal.requiredOutcomes(),
                manifest == null ? "none" : String.join(",", manifest.names())
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
        AtomicInteger counter = new AtomicInteger(1);
        tracePublisher.publishProgress(runId, stepId, stage, title, messages[0], Map.of(
                "heartbeat", 0
        ));
        ScheduledFuture<?> future = progressExecutor.scheduleAtFixedRate(() -> {
            int index = counter.getAndIncrement();
            tracePublisher.publishProgress(runId, stepId, stage, title, messages[index % messages.length], Map.of(
                    "heartbeat", index + 1
            ));
        }, 6000, 6000, TimeUnit.MILLISECONDS);
        return new TraceHeartbeat(future);
    }

    private record PreludeContext(String text, boolean evidenceBound, List<Song> songs, String answerPrefix, boolean directAnswer, ChatConfirmation confirmation) {
        static PreludeContext empty() {
            return new PreludeContext("", false, List.of(), "");
        }

        private PreludeContext(String text, boolean evidenceBound, List<Song> songs, String answerPrefix) {
            this(text, evidenceBound, songs, answerPrefix, false, null);
        }

        private PreludeContext(String text, boolean evidenceBound, List<Song> songs, String answerPrefix, ChatConfirmation confirmation) {
            this(text, evidenceBound, songs, answerPrefix, false, confirmation);
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

    private record SlotSongRefs(LinkedHashSet<String> songIds, LinkedHashSet<String> songTitles) {
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
