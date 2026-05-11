import { ChatConfirmation, Song } from "../../shared/types";

export type ChatMessageRole = "user" | "agent";

export type ChatMessageState = "pending" | "streaming" | "done" | "error";

export type TraceStepStage = "intent" | "context" | "tool" | "compose" | "render" | "error";

export type TraceStepStatus = "pending" | "running" | "done" | "error" | "skipped";

export type TraceStepVisibility = "user" | "debug";

export type TraceStep = {
  runId: string;
  stepId: string;
  stage: TraceStepStage;
  status: TraceStepStatus;
  visibility: TraceStepVisibility;
  title: string;
  summary?: string;
  safeData?: Record<string, unknown>;
  updatedAt?: number;
};

export type ChatConfirmationState = ChatConfirmation & {
  status?: "pending" | "confirmed" | "cancelled";
  selectedSongIds?: string[];
};

export type ChatMessage = {
  id: string;
  role: ChatMessageRole;
  content: string;
  state: ChatMessageState;
  runId?: string;
  traceSteps?: TraceStep[];
  songs?: Song[];
  confirmation?: ChatConfirmationState;
};

export function mergeTraceStep(current: TraceStep[] | undefined, next: TraceStep): TraceStep[] {
  const steps = current ?? [];
  const stampedNext = { ...next, updatedAt: Date.now() };
  const index = steps.findIndex((step) => step.stepId === next.stepId);
  if (index === -1) {
    return [...steps, stampedNext];
  }

  return steps.map((step, stepIndex) => (stepIndex === index ? { ...step, ...stampedNext } : step));
}

export function mergeMessageSongs(current: Song[] | undefined, next: Song[]): Song[] {
  const byId = new Map<string, Song>();
  for (const song of current ?? []) {
    byId.set(song.id, song);
  }
  for (const song of next) {
    byId.set(song.id, song);
  }
  return Array.from(byId.values());
}
