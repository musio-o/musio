import { CSSProperties, useEffect, useRef, useState } from "react";
import type { MouseEvent } from "react";
import { BookmarkPlus, Check, ChevronDown, ChevronRight, ListPlus, LoaderCircle, Play, X } from "lucide-react";
import { Song } from "../../shared/types";
import { ChatConfirmationState, ChatMessage, TraceStep } from "./chatTypes";
import { MarkdownContent } from "./MarkdownContent";

const MIN_READABLE_ANSWER_CHARS = 32;
const MIN_SENTENCE_HANDOFF_CHARS = 12;

type AgentMessageListProps = {
  messages: ChatMessage[];
  onPlaySong: (song: Song) => void;
  onAddToQueue: (song: Song) => void;
  onFavoriteSong: (song: Song) => void;
  onConfirmationAction: (messageId: string, action: "confirm" | "cancel", text: string, selectedSongIds: string[]) => void;
};

export function AgentMessageList({
  messages,
  onPlaySong,
  onAddToQueue,
  onFavoriteSong,
  onConfirmationAction
}: AgentMessageListProps) {
  const listRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight });
  }, [messages]);

  if (messages.length === 0) {
    return (
      <div className="chat-message-list empty">
        <p>和 Musio 说说你此刻想听什么。</p>
      </div>
    );
  }

  return (
    <div ref={listRef} className="chat-message-list" aria-live="polite">
      {messages.map((message) => {
        const progressBubbleVisible = isProgressBubbleVisible(message);
        const responseBodyVisible = hasAgentResponseBody(message);
        const answerStreaming = message.state === "streaming" && hasVisibleAnswerStarted(message);

        if (progressBubbleVisible) {
          return (
            <article key={message.id} className={`chat-message ${message.role} ${message.state} progress`}>
              <div className="chat-avatar">M</div>
              <AgentProgressBubble message={message} />
            </article>
          );
        }

        if (message.role === "agent" && !responseBodyVisible) {
          return null;
        }

        if (message.role === "agent") {
          return (
            <article key={message.id} className={`chat-message ${message.role} ${message.state}`}>
              <div className="chat-avatar">M</div>
              <div className="chat-response-stack">
                <div className="chat-bubble">
                  <span>MUSIO</span>
                  <RetainedTraceSteps
                    steps={message.traceSteps}
                    state={message.state}
                    forceExpanded={message.state !== "done" || Boolean(message.confirmation)}
                  />
                  {message.content.trim() ? <MarkdownContent text={message.content} /> : null}
                  <InlineSongCards
                    songs={message.state === "done" ? message.songs : undefined}
                    onPlaySong={onPlaySong}
                    onAddToQueue={onAddToQueue}
                    onFavoriteSong={onFavoriteSong}
                  />
                  {message.confirmation ? (
                    <ConfirmationActions
                      messageId={message.id}
                      confirmation={message.confirmation}
                      messageState={message.state}
                      onAction={onConfirmationAction}
                    />
                  ) : null}
                  {answerStreaming ? <small>正在回复</small> : null}
                  {message.state === "error" ? <small>回复中断</small> : null}
                </div>
              </div>
            </article>
          );
        }

        return (
          <article key={message.id} className={`chat-message ${message.role} ${message.state}`}>
            <div className="chat-bubble">
              <span>YOU</span>
              <p>{message.content}</p>
            </div>
            <div className="chat-avatar user">Y</div>
          </article>
        );
      })}
    </div>
  );
}

function ConfirmationActions({
  messageId,
  confirmation,
  messageState,
  onAction
}: {
  messageId: string;
  confirmation: ChatConfirmationState;
  messageState: ChatMessage["state"];
  onAction: (messageId: string, action: "confirm" | "cancel", text: string, selectedSongIds: string[]) => void;
}) {
  const confirmationSongs = confirmationSongsForDisplay(confirmation);
  const initialSelectedIds = confirmation.selectedSongIds
    ?? confirmation.defaultSelectedSongIds
    ?? confirmationSongs.map((song) => song.id);
  const [selectedSongIds, setSelectedSongIds] = useState(initialSelectedIds);

  const expired = confirmation.status === "expired" || ((messageState === "done" || messageState === "error") && (confirmation.status ?? "pending") === "pending");
  const submitting = confirmation.status === "submitting";
  const confirmed = confirmation.status === "confirmed";
  const cancelled = confirmation.status === "cancelled";
  const resolved = submitting || confirmed || cancelled || expired;
  const actionable = !resolved;
  const statusClass = submitting
    ? "status-submitting"
    : confirmed
      ? "status-confirmed"
      : cancelled
        ? "status-cancelled"
        : expired
          ? "status-expired"
          : "status-pending";
  const multiple = confirmationSongs.length > 1;
  const statusText = confirmed
    ? "已确认"
    : cancelled
      ? "已取消"
      : submitting
        ? "处理中"
        : expired
          ? "任务已结束"
          : "";

  function lockLocalControls(event: MouseEvent<HTMLButtonElement>) {
    event.currentTarget.parentElement
      ?.querySelectorAll("button")
      .forEach((button) => {
        button.disabled = true;
      });
  }

  return (
    <div className={`chat-confirmation ${statusClass} ${resolved ? "resolved" : ""}`} aria-label={confirmation.title || "等待确认"}>
      <div className="chat-confirmation-copy">
        <strong>{confirmation.title || "需要确认"}</strong>
        {confirmation.description ? <span>{confirmation.description}</span> : null}
      </div>
      {confirmationSongs.length > 0 ? (
        <div className="chat-confirmation-options">
          {confirmationSongs.map((song) => {
            const checked = selectedSongIds.includes(song.id);
            return (
              <label key={song.id} className={`chat-confirmation-option ${checked ? "selected" : ""}`}>
                <input
                  type={multiple ? "checkbox" : "radio"}
                  checked={checked}
                  disabled={resolved}
                  onChange={() => {
                    setSelectedSongIds((current) => {
                      if (!multiple) {
                        return [song.id];
                      }
                      return current.includes(song.id)
                        ? current.filter((id) => id !== song.id)
                        : [...current, song.id];
                    });
                  }}
                />
                <span>{song.title || song.id}</span>
                <small>{song.artists?.join(" / ") || song.provider || "QQ 音乐"}</small>
              </label>
            );
          })}
        </div>
      ) : null}
      {actionable ? (
        <div className="chat-confirmation-actions">
          <button
            type="button"
            className="cancel"
            onClick={(event) => {
              if (resolved) {
                return;
              }
              lockLocalControls(event);
              onAction(messageId, "cancel", confirmation.cancelText || "取消收藏", []);
            }}
          >
            <X size={15} />
            <span>取消</span>
          </button>
          <button
            type="button"
            className="confirm"
            disabled={selectedSongIds.length === 0}
            onClick={(event) => {
              if (resolved || selectedSongIds.length === 0) {
                return;
              }
              lockLocalControls(event);
              onAction(messageId, "confirm", confirmation.confirmText || "确认收藏", selectedSongIds);
            }}
          >
            <Check size={15} />
            <span>确认</span>
          </button>
        </div>
      ) : (
        <div className="chat-confirmation-state" aria-live="polite">
          {submitting ? <LoaderCircle size={15} /> : confirmed ? <Check size={15} /> : <X size={15} />}
          <span>{statusText}</span>
        </div>
      )}
    </div>
  );
}

function confirmationSongsForDisplay(confirmation: ChatConfirmationState): Song[] {
  if (confirmation.songs?.length) {
    return confirmation.songs;
  }
  return confirmation.song ? [confirmation.song] : [];
}

function AgentProgressBubble({ message }: { message: ChatMessage }) {
  const visibleSteps = visibleTraceSteps(message.traceSteps);
  const currentStep = visibleSteps.length > 0
    ? currentTraceStep(visibleSteps)
    : fallbackTraceStep(message.runId);
  const narrativeSteps = progressNarrativeSteps(visibleSteps, currentStep);
  const currentNarrative = progressNarrativeText(currentStep, true);
  const currentDetail = progressDetailText(currentStep, currentNarrative);
  const active = shouldShowProgressWave(currentStep);

  return (
    <div className="chat-progress-bubble" role="status" aria-label="Musio 当前进展">
      <div className={`chat-progress-indicator ${currentStep.status}`} aria-hidden="true">
        <span />
      </div>
      <div className="chat-progress-copy">
        <div className="chat-progress-heading" aria-hidden="true">
          <span>MUSIO</span>
        </div>
        <strong>
          <TypewriterText
            key={`${currentStep.stepId}:${currentStep.status}:${currentNarrative}`}
            text={currentNarrative}
            identityKey={`${currentStep.stepId}:${currentStep.status}:progress-narrative:${currentNarrative}`}
          />
          {active ? <ProgressWave /> : null}
        </strong>
        {currentDetail ? (
          <p>
            <span>{currentDetail}</span>
          </p>
        ) : null}
        {narrativeSteps.length > 0 ? (
          <div className="chat-progress-narrative" aria-label="最近进展">
            {narrativeSteps.map((step, index) => (
              <div
                key={`${step.stepId}-${step.status}`}
                className={`chat-progress-narrative-item ${step.status}`}
                style={{ "--progress-index": index } as CSSProperties}
              >
                <i aria-hidden="true" />
                <span>{progressNarrativeText(step, false)}</span>
              </div>
            ))}
          </div>
        ) : null}
      </div>
    </div>
  );
}

function RetainedTraceSteps({ steps, state, forceExpanded = false }: { steps?: TraceStep[]; state: ChatMessage["state"]; forceExpanded?: boolean }) {
  const [expanded, setExpanded] = useState(forceExpanded);
  const visibleSteps = visibleTraceSteps(steps);
  useEffect(() => {
    if (forceExpanded) {
      setExpanded(true);
    }
  }, [forceExpanded]);

  if (visibleSteps.length === 0) {
    return null;
  }

  const displaySteps = retainedTraceSteps(visibleSteps);
  return (
    <div className={`trace-steps retained ${expanded ? "expanded" : ""}`} aria-label="Musio 本次处理过程">
      <button
        type="button"
        className="trace-toggle"
        aria-expanded={expanded}
        onClick={() => setExpanded((value) => !value)}
      >
        {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        <span>{expanded ? "收起处理过程" : "查看处理过程"}</span>
        <small>{retainedTraceMeta(visibleSteps, state)}</small>
      </button>
      {expanded ? (
        <div className="trace-history">
          {displaySteps.map((step, index) => (
            <RetainedTraceStepRow key={step.stepId} step={step} index={index} />
          ))}
        </div>
      ) : null}
    </div>
  );
}

function RetainedTraceStepRow({ step, index }: { step: TraceStep; index: number }) {
  const narrative = progressNarrativeText(step, step.status === "running" || step.status === "pending");
  const detail = progressDetailText(step, narrative);

  return (
    <div
      className={`trace-step ${step.status}`}
      style={{ "--trace-index": index } as CSSProperties}
    >
      <span className="trace-step-status">{traceStatusLabel(step.status)}</span>
      <div>
        <strong>{narrative}</strong>
        {detail ? <p>{detail}</p> : null}
      </div>
    </div>
  );
}

function isProgressBubbleVisible(message: ChatMessage) {
  return message.role === "agent"
    && message.state === "streaming"
    && !hasVisibleAnswerStarted(message)
    && !message.confirmation;
}

function hasAgentResponseBody(message: ChatMessage) {
  if (message.role !== "agent") {
    return true;
  }
  return hasRenderableAnswer(message)
    || message.state === "error"
    || Boolean(message.confirmation)
    || (message.state === "done" && Boolean(message.songs?.length))
    || (message.state === "done" && hasVisibleTraceSteps(message));
}

function hasVisibleAnswerStarted(message: ChatMessage) {
  const text = message.content.trim();
  return text.length >= MIN_READABLE_ANSWER_CHARS
    || (text.length >= MIN_SENTENCE_HANDOFF_CHARS && /[。！？.!?\n]/u.test(text));
}

function hasRenderableAnswer(message: ChatMessage) {
  const length = message.content.trim().length;
  if (length <= 0) {
    return false;
  }
  return message.state !== "streaming" || hasVisibleAnswerStarted(message);
}

function hasVisibleTraceSteps(message: ChatMessage) {
  return visibleTraceSteps(message.traceSteps).length > 0;
}

function InlineSongCards({
  songs,
  onPlaySong,
  onAddToQueue,
  onFavoriteSong
}: {
  songs?: Song[];
  onPlaySong: (song: Song) => void;
  onAddToQueue: (song: Song) => void;
  onFavoriteSong: (song: Song) => void;
}) {
  if (!songs?.length) {
    return null;
  }

  return (
    <div className="chat-song-card-list" aria-label="Musio 推荐歌曲">
      {songs.map((song, index) => (
        <article key={song.id} className="chat-song-card">
          <span className="chat-song-index">{index + 1}</span>
          <div className="chat-song-cover">
            {song.artworkUrl ? <img src={song.artworkUrl} alt="" /> : <span>{song.title?.slice(0, 1) || "M"}</span>}
          </div>
          <div className="chat-song-main">
            <strong>{song.title || song.id}</strong>
            <span>{song.artists?.join(", ") || song.provider || "QQ 音乐"}</span>
          </div>
          <div className="song-action-cluster">
            <button type="button" title="播放" aria-label={`播放 ${song.title || song.id}`} onClick={() => onPlaySong(song)}>
              <Play size={14} />
            </button>
            <button type="button" title="加入队列" aria-label={`加入队列 ${song.title || song.id}`} onClick={() => onAddToQueue(song)}>
              <ListPlus size={14} />
            </button>
            <button type="button" title="收藏到 Musio" aria-label={`收藏 ${song.title || song.id}`} onClick={() => onFavoriteSong(song)}>
              <BookmarkPlus size={14} />
            </button>
          </div>
        </article>
      ))}
    </div>
  );
}

function TypewriterText({ text, identityKey }: { text: string; identityKey: string }) {
  const [visibleLength, setVisibleLength] = useState(0);

  useEffect(() => {
    if (prefersReducedMotion()) {
      setVisibleLength(text.length);
      return;
    }

    setVisibleLength(0);
    const timer = window.setInterval(() => {
      setVisibleLength((current) => {
        if (current >= text.length) {
          window.clearInterval(timer);
          return current;
        }
        return current + 1;
      });
    }, 28);

    return () => window.clearInterval(timer);
  }, [identityKey, text]);

  const complete = visibleLength >= text.length;
  return (
    <span className="typewriter-text">
      {text.slice(0, visibleLength)}
      {!complete ? <span className="typewriter-caret" aria-hidden="true" /> : null}
    </span>
  );
}

function ProgressWave() {
  return (
    <span className="chat-progress-wave" aria-hidden="true">
      <i />
      <i />
      <i />
    </span>
  );
}

function prefersReducedMotion() {
  return typeof window !== "undefined"
    && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

function currentTraceStep(steps: TraceStep[]) {
  const byUpdatedAt = [...steps].sort((left, right) => stepUpdatedAt(right) - stepUpdatedAt(left));
  return byUpdatedAt.find((step) => step.status === "error")
    ?? byUpdatedAt.find((step) => step.status === "running")
    ?? byUpdatedAt.find((step) => step.status === "pending")
    ?? byUpdatedAt[0];
}

function visibleTraceSteps(steps?: TraceStep[]) {
  return steps?.filter((step) => step.visibility === "user") ?? [];
}

function fallbackTraceStep(runId?: string): TraceStep {
  return {
    runId: runId ?? "",
    stepId: "local.agent-start",
    stage: "context",
    status: "running",
    visibility: "user",
    title: "开始处理这条消息",
    summary: "正在建立本轮任务连接，马上同步具体进展。",
    updatedAt: Date.now()
  };
}

function progressNarrativeSteps(steps: TraceStep[], currentStep: TraceStep) {
  return [...steps]
    .filter((step) => step.stepId !== currentStep.stepId)
    .sort((left, right) => stepUpdatedAt(right) - stepUpdatedAt(left))
    .slice(0, 4)
    .reverse();
}

function stepUpdatedAt(step: TraceStep) {
  return step.updatedAt ?? 0;
}

function retainedTraceSteps(steps: TraceStep[]) {
  return [...steps].sort((left, right) => stepUpdatedAt(left) - stepUpdatedAt(right));
}

function retainedTraceMeta(steps: TraceStep[], state: ChatMessage["state"]) {
  const toolCount = steps.filter((step) => step.stage === "tool").length;
  const running = state === "streaming" || steps.some((step) => step.status === "running" || step.status === "pending");
  const base = `${steps.length} 步${toolCount > 0 ? ` · ${toolCount} 个音乐能力` : ""}`;
  return running ? `进行中 · ${base}` : base;
}

function traceStatusLabel(status: TraceStep["status"]) {
  switch (status) {
    case "done":
      return "完成";
    case "running":
      return "进行中";
    case "error":
      return "失败";
    case "skipped":
      return "跳过";
    default:
      return "等待";
  }
}

function progressSummary(step: TraceStep) {
  if (step.summary?.trim()) {
    return step.summary.trim();
  }
  switch (step.status) {
    case "done":
      return "这一步已经完成，正在衔接下一步。";
    case "error":
      return "这一步没有正常完成，正在返回可见错误。";
    case "skipped":
      return "这一步被跳过，继续处理后续内容。";
    case "pending":
      return "这一步正在排队等待执行。";
    default:
      return "正在推进当前 loop，完成后会开始输出正文。";
  }
}

function shouldShowProgressWave(step: TraceStep) {
  return step.status === "running" || step.status === "pending";
}

function progressNarrativeText(step: TraceStep, current: boolean) {
  const summary = progressSummary(step);
  if (current && (step.status === "running" || step.status === "pending")) {
    return sentenceWithoutPeriod(runningNarrative(step));
  }
  if (step.status === "done") {
    return sentenceWithoutPeriod(doneNarrative(step));
  }
  if (step.status === "skipped") {
    return sentenceWithoutPeriod(summary.startsWith("这一步") ? `我跳过了：${step.title}` : summary);
  }
  if (step.status === "error") {
    return sentenceWithoutPeriod(summary.startsWith("这一步") ? `这里遇到问题：${step.title}` : summary);
  }
  return sentenceWithoutPeriod(summary);
}

function progressDetailText(step: TraceStep, narrative: string) {
  const candidates = [
    progressSummary(step),
    safeDataProgressDetail(step),
    stageProgressDetail(step)
  ];

  for (const candidate of candidates) {
    const detail = sentenceWithoutPeriod(candidate);
    if (isUsefulProgressDetail(detail, narrative)) {
      return detail;
    }
  }

  return "";
}

function runningNarrative(step: TraceStep) {
  const title = step.title.trim();
  if (step.status === "pending") {
    return title ? `我正在等待：${title}。` : "我正在等待下一步。";
  }
  if (step.stage === "tool") {
    return title ? `我正在调用音乐能力：${title}。` : "我正在调用音乐能力。";
  }
  if (step.stage === "compose") {
    return "我正在整理最终回复。";
  }
  if (step.stage === "intent") {
    return "我正在理解这条音乐请求。";
  }
  if (step.stage === "context" && title.includes("下一步")) {
    return "我正在判断下一步动作。";
  }
  return title ? `我正在${title}。` : "我正在推进当前任务。";
}

function doneNarrative(step: TraceStep) {
  if (step.stage === "compose" && step.title.includes("正文")) {
    return "我已经整理好结果，马上开始正式回复。";
  }
  return `我完成了：${step.title}。`;
}

function safeDataProgressDetail(step: TraceStep) {
  const data = step.safeData ?? {};
  const loopStep = numberValue(data.loopStep);
  const observationCount = numberValue(data.observationCount);
  const action = stringValue(data.action);
  const tool = stringValue(data.tool);
  const keyword = stringValue(data.keyword);
  const count = numberValue(data.count);
  const songCount = numberValue(data.songCount);
  const resolvedCount = numberValue(data.resolvedCount);
  const unresolvedCount = numberValue(data.unresolvedCount);
  const toolNames = stringListValue(data.toolNames);
  const outcome = stringValue(data.outcome);

  if (toolNames.length > 0) {
    return `计划使用：${toolNames.map(toolLabel).join("、")}。`;
  }
  if (typeof resolvedCount === "number" || typeof unresolvedCount === "number") {
    return `已匹配 ${resolvedCount ?? 0} 首，可继续处理；未匹配 ${unresolvedCount ?? 0} 首。`;
  }
  if (typeof count === "number") {
    return `音乐能力返回了 ${count} 条结果。`;
  }
  if (typeof songCount === "number" && songCount > 0) {
    return `这一轮拿到 ${songCount} 首可用歌曲。`;
  }
  if (tool) {
    const toolText = toolLabel(tool);
    return keyword ? `${toolText} · 关键词「${keyword}」。` : `音乐能力 · ${toolText}。`;
  }
  if (action) {
    return actionDetail(action, loopStep, observationCount);
  }
  if (typeof loopStep === "number") {
    return loopDetail(loopStep, observationCount);
  }
  if (outcome) {
    return `音乐能力阶段已收束：${outcome.toLowerCase()}。`;
  }
  return "";
}

function stageProgressDetail(step: TraceStep) {
  if (step.status === "error") {
    return "这一步没有正常完成，会把可见错误带回正文。";
  }
  if (step.status === "skipped") {
    return "当前动作不需要执行，继续衔接后续步骤。";
  }
  switch (step.stage) {
    case "intent":
      return "先判断请求意图，再决定是否需要调用音乐能力。";
    case "context":
      return "结合当前请求和已有结果，选择下一步处理路径。";
    case "tool":
      return "等待本地音乐服务返回可用结果。";
    case "compose":
      return "把已拿到的信息整理成可以直接阅读的回复。";
    case "render":
      return "准备把结果渲染到当前对话里。";
    default:
      return "继续推进当前任务，完成后会切到正式回复。";
  }
}

function isUsefulProgressDetail(detail: string, narrative: string) {
  return Boolean(detail)
    && !sameProgressSentence(detail, narrative);
}

function actionDetail(action: string, loopStep?: number, observationCount?: number) {
  const prefix = typeof loopStep === "number" ? `第 ${loopStep} 轮 · ` : "";
  if (action === "TOOL_CALL") {
    return `${prefix}已经选定要调用的音乐能力。`;
  }
  if (action === "FINAL_ANSWER") {
    return `${prefix}已有信息足够，准备切到正式回复。`;
  }
  if (action === "REQUEST_CONFIRMATION") {
    return `${prefix}需要先让你确认后才能继续。`;
  }
  if (action === "UNSUPPORTED") {
    return `${prefix}当前请求超出可执行能力，准备说明限制。`;
  }
  return loopDetail(loopStep, observationCount);
}

function loopDetail(loopStep?: number, observationCount?: number) {
  if (typeof loopStep !== "number") {
    return "";
  }
  if (typeof observationCount === "number" && observationCount > 0) {
    return `第 ${loopStep} 轮 · 已有 ${observationCount} 条工具观察可参考。`;
  }
  return `第 ${loopStep} 轮 · 先根据请求意图选择下一步。`;
}

function toolLabel(toolName: string) {
  switch (toolName) {
    case "search_songs":
      return "搜索候选歌曲";
    case "get_user_music_profile":
      return "读取音乐偏好";
    case "get_song_detail":
      return "读取歌曲详情";
    case "get_lyrics":
      return "读取歌词";
    case "get_hot_comments":
      return "读取热门评论";
    case "get_user_playlists":
      return "读取歌单";
    case "get_playlist_songs":
      return "读取歌单歌曲";
    case "add_song_to_musio_playlist":
      return "收藏到 Musio";
    default:
      return toolName;
  }
}

function stringValue(value: unknown) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

function numberValue(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function stringListValue(value: unknown) {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === "string" && item.trim().length > 0).map((item) => item.trim())
    : [];
}

function sentenceWithoutPeriod(text: string) {
  return text.trim().replace(/[。.!！]+$/u, "");
}

function sameProgressSentence(left: string, right: string) {
  const normalizedLeft = normalizeProgressSentence(left);
  const normalizedRight = normalizeProgressSentence(right);
  if (!normalizedLeft || !normalizedRight) {
    return false;
  }
  return normalizedLeft === normalizedRight
    || (normalizedLeft.length >= 5 && normalizedRight.length >= 5 && (
      normalizedLeft.includes(normalizedRight) || normalizedRight.includes(normalizedLeft)
    ));
}

function normalizeProgressSentence(value: string) {
  return value
    .replace(/[。.!！？，,；;：:、·\s]/gu, "")
    .replace(/^我正在/u, "正在")
    .replace(/^我已经/u, "已")
    .replace(/^我完成了：/u, "")
    .replace(/^已完成：/u, "");
}
