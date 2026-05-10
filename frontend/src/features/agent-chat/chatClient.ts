import { api } from "../../shared/api";
import { AgentEvent, ChatConfirmation, Song } from "../../shared/types";
import { TraceStep, TraceStepStage, TraceStepStatus, TraceStepVisibility } from "./chatTypes";

type AgentRunHandlers = {
  onMessageDelta: (detail: string, runId?: string) => void;
  onTraceStep: (step: TraceStep) => void;
  onToolStart: (detail: string) => void;
  onToolResult: (detail: string) => void;
  onSongCards: (songs: Song[], runId?: string) => void;
  onConfirmationRequest: (confirmation: ChatConfirmation, runId?: string) => void;
  onError: (detail: string) => void;
  onDone: () => void;
};

export const chatClient = {
  startChat: api.startChat,
  openRunEvents
};

function openRunEvents(runId: string, handlers: AgentRunHandlers): EventSource {
  const source = new EventSource(`/api/chat/runs/${runId}/events`);
  source.onmessage = (event) => {
    handlers.onMessageDelta(event.data, runId);
  };
  source.addEventListener("agent_message_delta", (event) => {
    const agentEvent = parseAgentEvent((event as MessageEvent).data);
    const text = typeof agentEvent?.data?.text === "string" ? agentEvent.data.text : (event as MessageEvent).data;
    const eventRunId = typeof agentEvent?.data?.runId === "string" ? agentEvent.data.runId : runId;
    handlers.onMessageDelta(text, eventRunId);
  });
  source.addEventListener("trace_step", (event) => {
    const traceStep = parseTraceStep(parseAgentEvent((event as MessageEvent).data), runId);
    if (traceStep) {
      handlers.onTraceStep(traceStep);
    }
  });
  source.addEventListener("tool_start", (event) => {
    handlers.onToolStart(formatToolEvent(parseAgentEvent((event as MessageEvent).data)));
  });
  source.addEventListener("tool_result", (event) => {
    handlers.onToolResult(formatToolEvent(parseAgentEvent((event as MessageEvent).data)));
  });
  source.addEventListener("song_cards", (event) => {
    const agentEvent = parseAgentEvent((event as MessageEvent).data);
    const songs = Array.isArray(agentEvent?.data?.songs) ? (agentEvent.data.songs as Song[]) : [];
    const eventRunId = typeof agentEvent?.data?.runId === "string" ? agentEvent.data.runId : runId;
    handlers.onSongCards(songs, eventRunId);
  });
  source.addEventListener("confirmation_request", (event) => {
    const agentEvent = parseAgentEvent((event as MessageEvent).data);
    const confirmation = parseConfirmation(agentEvent);
    if (confirmation) {
      const eventRunId = typeof agentEvent?.data?.runId === "string" ? agentEvent.data.runId : runId;
      handlers.onConfirmationRequest(confirmation, eventRunId);
    }
  });
  source.addEventListener("agent_error", (event) => {
    const agentEvent = parseAgentEvent((event as MessageEvent).data);
    const detail = typeof agentEvent?.data?.message === "string" ? agentEvent.data.message : (event as MessageEvent).data;
    handlers.onError(detail);
    source.close();
  });
  source.addEventListener("done", () => {
    handlers.onDone();
    source.close();
  });
  source.onerror = () => {
    handlers.onError("SSE 连接已中断。");
    source.close();
  };
  return source;
}

function parseConfirmation(event: AgentEvent | null): ChatConfirmation | null {
  const confirmation = event?.data?.confirmation;
  if (!isRecord(confirmation)) {
    return null;
  }
  const type = typeof confirmation.type === "string" ? confirmation.type : "local_playlist_add";
  const title = typeof confirmation.title === "string" ? confirmation.title : "收藏到 Musio 歌单";
  const description = typeof confirmation.description === "string" ? confirmation.description : "";
  const confirmText = typeof confirmation.confirmText === "string" ? confirmation.confirmText : "确认收藏";
  const cancelText = typeof confirmation.cancelText === "string" ? confirmation.cancelText : "取消收藏";
  const song = isSong(confirmation.song) ? confirmation.song : null;
  const songs = Array.isArray(confirmation.songs)
    ? confirmation.songs.filter(isSong)
    : song ? [song] : [];
  const selectionMode = typeof confirmation.selectionMode === "string" ? confirmation.selectionMode : songs.length > 1 ? "multiple" : "single";
  const defaultSelectedSongIds = Array.isArray(confirmation.defaultSelectedSongIds)
    ? confirmation.defaultSelectedSongIds.filter((id): id is string => typeof id === "string" && id.trim().length > 0)
    : songs.map((item) => item.id);
  return { type, title, description, confirmText, cancelText, song: song ?? songs[0] ?? null, songs, selectionMode, defaultSelectedSongIds };
}

function isSong(value: unknown): value is Song {
  if (!isRecord(value)) {
    return false;
  }
  return typeof value.id === "string"
    && typeof value.title === "string"
    && Array.isArray(value.artists);
}

function parseAgentEvent(raw: string): AgentEvent | null {
  try {
    return JSON.parse(raw) as AgentEvent;
  } catch {
    return null;
  }
}

function formatToolEvent(event: AgentEvent | null): string {
  if (!event?.data) {
    return "工具事件";
  }

  const tool = typeof event.data.tool === "string" ? event.data.tool : "工具";
  const summary = typeof event.data.summary === "string" ? event.data.summary : "";
  if (summary) {
    return `${tool}: ${summary}`;
  }

  const input = event.data.input ? JSON.stringify(event.data.input) : "";
  return input ? `${tool}: ${input}` : tool;
}

function parseTraceStep(event: AgentEvent | null, fallbackRunId: string): TraceStep | null {
  const data = event?.data;
  if (!data) {
    return null;
  }

  const stepId = typeof data.stepId === "string" ? data.stepId : "";
  const title = typeof data.title === "string" ? data.title : "";
  const stage = typeof data.stage === "string" && isTraceStage(data.stage) ? data.stage : null;
  const status = typeof data.status === "string" && isTraceStatus(data.status) ? data.status : null;
  const visibility = typeof data.visibility === "string" && isTraceVisibility(data.visibility) ? data.visibility : null;
  if (!stepId || !title || !stage || !status || !visibility) {
    return null;
  }

  return {
    runId: typeof data.runId === "string" ? data.runId : fallbackRunId,
    stepId,
    stage,
    status,
    visibility,
    title,
    summary: typeof data.summary === "string" ? data.summary : undefined,
    safeData: isRecord(data.safeData) ? data.safeData : undefined
  };
}

function isTraceStage(value: string): value is TraceStepStage {
  return ["intent", "context", "tool", "compose", "render", "error"].includes(value);
}

function isTraceStatus(value: string): value is TraceStepStatus {
  return ["pending", "running", "done", "error", "skipped"].includes(value);
}

function isTraceVisibility(value: string): value is TraceStepVisibility {
  return ["user", "debug"].includes(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}
