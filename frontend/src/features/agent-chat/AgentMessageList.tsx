import { CSSProperties, useEffect, useRef, useState } from "react";
import { BookmarkPlus, ChevronDown, ChevronRight, ListPlus, Play } from "lucide-react";
import { Song } from "../../shared/types";
import { ChatMessage, TraceStep } from "./chatTypes";
import { MarkdownContent } from "./MarkdownContent";

type AgentMessageListProps = {
  messages: ChatMessage[];
  onPlaySong: (song: Song) => void;
  onAddToQueue: (song: Song) => void;
  onFavoriteSong: (song: Song) => void;
};

export function AgentMessageList({ messages, onPlaySong, onAddToQueue, onFavoriteSong }: AgentMessageListProps) {
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
        const initialAgentLoading = isInitialAgentLoading(message);
        const answerStreaming = message.state === "streaming" && message.content.trim().length > 0;

        if (initialAgentLoading) {
          return (
            <article key={message.id} className={`chat-message ${message.role} ${message.state} loading`}>
              <div className="chat-avatar">M</div>
              <MusioWaveLoading />
            </article>
          );
        }

        return (
          <article key={message.id} className={`chat-message ${message.role} ${message.state}`}>
            {message.role === "agent" ? <div className="chat-avatar">M</div> : null}
            <div className="chat-bubble">
              <span>{message.role === "agent" ? "MUSIO" : "YOU"}</span>
              {message.role === "agent" ? (
                <>
                  <TraceSteps steps={message.traceSteps} state={message.state} />
                  {message.content.trim() ? <MarkdownContent text={message.content} /> : null}
                  <InlineSongCards
                    songs={message.state === "done" ? message.songs : undefined}
                    onPlaySong={onPlaySong}
                    onAddToQueue={onAddToQueue}
                    onFavoriteSong={onFavoriteSong}
                  />
                </>
              ) : (
                <p>{message.content}</p>
              )}
              {answerStreaming ? <small>正在回复</small> : null}
              {message.state === "error" ? <small>回复中断</small> : null}
            </div>
            {message.role === "user" ? <div className="chat-avatar user">Y</div> : null}
          </article>
        );
      })}
    </div>
  );
}

function MusioWaveLoading() {
  return (
    <div className="musio-wave-loader" role="status" aria-label="Musio 正在准备回答">
      {"MUSIO".split("").map((letter, index) => (
        <span key={letter} style={{ "--letter-index": index } as CSSProperties}>
          {letter}
        </span>
      ))}
    </div>
  );
}

function isInitialAgentLoading(message: ChatMessage) {
  if (message.role !== "agent" || message.state !== "streaming" || message.content.trim().length > 0) {
    return false;
  }
  const visibleTraceSteps = message.traceSteps?.filter((step) => step.visibility === "user") ?? [];
  return visibleTraceSteps.length === 0 && !message.songs?.length;
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
      {songs.map((song) => (
        <article key={song.id} className="chat-song-card">
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

function TraceSteps({ steps, state }: { steps?: TraceStep[]; state: ChatMessage["state"] }) {
  const visibleSteps = steps?.filter((step) => step.visibility === "user") ?? [];
  if (visibleSteps.length === 0) {
    return null;
  }

  if (state === "done") {
    return <CompletedTraceSteps steps={visibleSteps} />;
  }

  const currentStep = currentTraceStep(visibleSteps);
  return (
    <div className="trace-steps" aria-label="Musio 当前执行过程">
      <p className="trace-title">{state === "error" ? "Musio 处理遇到问题" : "Musio 正在处理"}</p>
      <TraceStepRow step={currentStep} index={0} typewriter={state === "streaming"} />
    </div>
  );
}

function CompletedTraceSteps({ steps }: { steps: TraceStep[] }) {
  const [expanded, setExpanded] = useState(false);
  const toolSteps = steps.filter((step) => step.stage === "tool");

  return (
    <div className={`trace-steps completed ${expanded ? "expanded" : ""}`} aria-label="Musio 执行过程">
      <button type="button" className="trace-toggle" onClick={() => setExpanded((value) => !value)}>
        {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        <span>{expanded ? "收起执行过程" : "查看执行过程"}</span>
        <small>{toolSteps.length > 0 ? `${steps.length} 步 · ${toolSteps.length} 个工具` : `${steps.length} 步`}</small>
      </button>
      {expanded ? (
        <div className="trace-history">
          {steps.map((step, index) => (
            <TraceStepRow key={step.stepId} step={step} index={index} />
          ))}
        </div>
      ) : null}
    </div>
  );
}

function TraceStepRow({ step, index, typewriter = false }: { step: TraceStep; index: number; typewriter?: boolean }) {
  return (
    <div
      className={`trace-step ${step.status} ${typewriter ? "typewriter" : ""}`}
      style={{ "--trace-index": index } as CSSProperties}
    >
      <span className="trace-step-status">{traceStatusLabel(step.status)}</span>
      <div>
        <strong>
          {typewriter ? (
            <TypewriterText text={step.title} identityKey={`${step.stepId}:${step.status}:title:${step.title}`} />
          ) : step.title}
        </strong>
        {step.summary ? (
          <p>
            {typewriter ? (
              <TypewriterText text={step.summary} identityKey={`${step.stepId}:${step.status}:summary:${step.summary}`} />
            ) : step.summary}
          </p>
        ) : null}
      </div>
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

function prefersReducedMotion() {
  return typeof window !== "undefined"
    && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

function currentTraceStep(steps: TraceStep[]) {
  const reversed = [...steps].reverse();
  return reversed.find((step) => step.status === "error")
    ?? reversed.find((step) => step.status === "running")
    ?? reversed.find((step) => step.status === "pending")
    ?? reversed[0];
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
