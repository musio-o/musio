import { FormEvent, KeyboardEvent } from "react";
import type { Dispatch, SetStateAction } from "react";
import { Code2, Coffee, Leaf, Music2, SendHorizontal, Shuffle } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { EventLog, Song } from "../../shared/types";
import { AgentMessageList } from "./AgentMessageList";
import { chatClient } from "./chatClient";
import { ChatMessage, mergeMessageSongs, mergeTraceStep } from "./chatTypes";

type AgentChatPanelProps = {
  busy: boolean;
  disabledReason?: string | null;
  message: string;
  messages: ChatMessage[];
  onBusyChange: (busy: boolean) => void;
  onMessageChange: (message: string) => void;
  onMessagesChange: Dispatch<SetStateAction<ChatMessage[]>>;
  onEvent: (event: EventLog) => void;
  onPlaySong: (song: Song) => void;
  onAddToQueue: (song: Song) => void;
  onFavoriteSong: (song: Song) => void;
};

const quickPrompts: Array<{ label: string; prompt: string; Icon: LucideIcon }> = [
  { label: "提升专注力的音乐", prompt: "给我推荐 5 首适合提升专注力的音乐。", Icon: Music2 },
  { label: "深夜学习 / 写代码", prompt: "给我推荐 5 首适合深夜学习和写代码听的歌。", Icon: Code2 },
  { label: "咖啡馆氛围 BGM", prompt: "给我推荐 5 首像咖啡馆背景音乐一样松弛的歌。", Icon: Coffee },
  { label: "Chill 轻松的旋律", prompt: "给我推荐 5 首轻松但不分心的 Chill 旋律。", Icon: Leaf },
  { label: "换一批", prompt: "换一批更冷静、更低干扰的推荐。", Icon: Shuffle }
];

export function AgentChatPanel({
  busy,
  disabledReason,
  message,
  messages,
  onBusyChange,
  onMessageChange,
  onMessagesChange,
  onEvent,
  onPlaySong,
  onAddToQueue,
  onFavoriteSong
}: AgentChatPanelProps) {
  async function startChat(event: FormEvent) {
    event.preventDefault();
    await submitCurrentDraft();
  }

  async function submitCurrentDraft() {
    if (!message.trim()) {
      return;
    }

    const userText = message.trim();
    onMessageChange("");
    await submitText(userText);
  }

  async function submitText(userText: string) {
    if (!userText.trim()) {
      return;
    }

    const userMessage: ChatMessage = {
      id: crypto.randomUUID(),
      role: "user",
      content: userText,
      state: "done"
    };

    onMessagesChange((current) => [...current, userMessage]);
    onBusyChange(true);
    try {
      const run = await chatClient.startChat(userText);
      const agentMessageId = crypto.randomUUID();
      onMessagesChange((current) => [
        ...current,
        {
          id: agentMessageId,
          role: "agent",
          content: "",
          state: "streaming",
          runId: run.runId
        }
      ]);
      onEvent({ id: crypto.randomUUID(), name: "run", detail: `已创建任务：${run.runId}` });
      chatClient.openRunEvents(run.runId, {
        onMessageDelta: (detail, eventRunId) => {
          onMessagesChange((current) =>
            current.map((item) =>
              item.role === "agent" && item.runId === (eventRunId ?? run.runId)
                ? { ...item, content: item.content + detail, state: "streaming" }
              : item
            )
          );
        },
        onTraceStep: (step) => {
          onMessagesChange((current) =>
            current.map((item) =>
              item.role === "agent" && item.runId === step.runId
                ? { ...item, traceSteps: mergeTraceStep(item.traceSteps, step) }
                : item
            )
          );
          onEvent({ id: crypto.randomUUID(), name: "trace_step", detail: `${step.title}: ${step.summary ?? step.status}` });
        },
        onToolStart: (detail) => onEvent({ id: crypto.randomUUID(), name: "tool_start", detail }),
        onToolResult: (detail) => onEvent({ id: crypto.randomUUID(), name: "tool_result", detail }),
        onSongCards: (songs, eventRunId) => {
          onMessagesChange((current) =>
            current.map((item) =>
              item.role === "agent" && item.runId === (eventRunId ?? run.runId)
                ? { ...item, songs: mergeMessageSongs(item.songs, songs) }
                : item
            )
          );
          onEvent({ id: crypto.randomUUID(), name: "song_cards", detail: `收到 ${songs.length} 首歌曲` });
        },
        onConfirmationRequest: (confirmation, eventRunId) => {
          onMessagesChange((current) =>
            current.map((item) =>
              item.role === "agent" && item.runId === (eventRunId ?? run.runId)
                ? { ...item, confirmation: { ...confirmation, status: "pending" } }
                : item
            )
          );
          onEvent({ id: crypto.randomUUID(), name: "confirmation_request", detail: confirmation.title || "等待确认" });
        },
        onError: (detail) => {
          onMessagesChange((current) =>
            current.map((item) =>
              item.role === "agent" && item.runId === run.runId
                ? { ...item, content: item.content || detail, state: "error" }
                : item
            )
          );
          onEvent({ id: crypto.randomUUID(), name: "agent_error", detail });
          onBusyChange(false);
        },
        onDone: () => {
          onMessagesChange((current) =>
            current.map((item) =>
              item.role === "agent" && item.runId === run.runId ? { ...item, state: "done" } : item
            )
          );
          onBusyChange(false);
        }
      });
    } catch (error) {
      const detail = error instanceof Error ? error.message : "未知错误";
      onMessagesChange((current) => [
        ...current,
        {
          id: crypto.randomUUID(),
          role: "agent",
          content: detail,
          state: "error"
        }
      ]);
      onBusyChange(false);
      onEvent({
        id: crypto.randomUUID(),
        name: "error",
        detail
      });
    }
  }

  function handleConfirmationAction(messageId: string, action: "confirm" | "cancel", text: string) {
    if (busy || !text.trim()) {
      return;
    }

    onMessagesChange((current) =>
      current.map((item) =>
        item.id === messageId && item.confirmation
          ? { ...item, confirmation: { ...item.confirmation, status: action === "confirm" ? "confirmed" : "cancelled" } }
          : item
      )
    );
    void submitText(text.trim());
  }

  function handleTextareaKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key !== "Enter" || event.shiftKey || event.nativeEvent.isComposing) {
      return;
    }

    event.preventDefault();
    if (!busy && message.trim()) {
      void submitCurrentDraft();
    }
  }

  return (
    <section className="panel command-panel">
      {disabledReason ? <p className="access-note">{disabledReason}</p> : null}
      <AgentMessageList
        messages={messages}
        onPlaySong={onPlaySong}
        onAddToQueue={onAddToQueue}
        onFavoriteSong={onFavoriteSong}
        onConfirmationAction={handleConfirmationAction}
        confirmationBusy={busy}
      />
      <form onSubmit={startChat} className="prompt-form">
        <textarea
          placeholder="告诉 MUSIO 你想听什么，越具体越好..."
          value={message}
          onChange={(event) => onMessageChange(event.target.value)}
          onKeyDown={handleTextareaKeyDown}
        />
        <button type="submit" disabled={busy || !message.trim()}>
          <SendHorizontal size={18} />
        </button>
      </form>
      <div className="quick-prompt-row" aria-label="快捷推荐场景">
        {quickPrompts.map(({ label, prompt, Icon }) => (
          <button
            key={label}
            type="button"
            disabled={busy}
            onClick={() => onMessageChange(prompt)}
          >
            <Icon size={15} />
            <span>{label}</span>
          </button>
        ))}
      </div>
    </section>
  );
}
