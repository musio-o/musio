import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import { Activity, Cable, FileText, ListMusic, MessageCircle, MessageSquare, Play, Search, Terminal, Trash2 } from "lucide-react";
import { api } from "../shared/api";
import { EventLog, ProviderStatus, Song, SongComment, SystemStatus } from "../shared/types";
import { AgentChatPanel } from "../features/agent-chat/AgentChatPanel";
import { AgentEvents } from "../features/agent-chat/AgentEvents";
import { SongCards } from "../features/agent-chat/SongCards";
import type { ChatMessage } from "../features/agent-chat/chatTypes";
import { MusioPlaylistsPage } from "../features/musio-playlists/MusioPlaylistsPage";
import { PlayerShell } from "../features/player/PlayerShell";
import { PlayerSpectrum } from "../features/player/PlayerSpectrum";
import { usePlayerStore } from "../features/player/playerStore";
import { SourceSetupPage } from "../features/source-setup/SourceSetupPage";
import { AppRoute } from "./routes";

export function AppRouter() {
  const [route, setRoute] = useState<AppRoute>(() => initialRouteFromUrl());
  const [status, setStatus] = useState<SystemStatus | null>(null);
  const [providerStatuses, setProviderStatuses] = useState<ProviderStatus[]>([]);
  const [events, setEvents] = useState<EventLog[]>([]);
  const [songs, setSongs] = useState<Song[]>([]);
  const [chatDraft, setChatDraft] = useState("给我推荐 5 首适合深夜写代码听的歌。");
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([]);
  const [busy, setBusy] = useState(false);
  const [activeDrawer, setActiveDrawer] = useState<WorkbenchDrawer>("queue");
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [now, setNow] = useState(() => new Date());
  const selectedSources = useMemo(() => selectedSourcesFromUrl(), []);
  const player = usePlayerStore();

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1_000);
    return () => window.clearInterval(timer);
  }, []);

  const refreshProviderStatuses = useCallback(() => {
    api.providers()
      .then(setProviderStatuses)
      .catch(() => setProviderStatuses([]));
  }, []);

  useEffect(() => {
    api.status()
      .then(setStatus)
      .catch(() => setStatus(null));
    refreshProviderStatuses();
  }, [refreshProviderStatuses]);

  useEffect(() => {
    let cancelled = false;
    api.chatHistory("local")
      .then((history) => {
        if (cancelled || history.length === 0) {
          return;
        }
        const restoredMessages = history.map<ChatMessage>((item, index) => ({
          id: `history-${index}-${item.createdAt}`,
          role: item.role === "assistant" ? "agent" : "user",
          content: item.content,
          state: "done",
          songs: item.songs?.length ? item.songs : undefined,
          confirmation: item.confirmation
            ? {
              ...item.confirmation,
              status: "pending",
              selectedSongIds: item.confirmation.defaultSelectedSongIds ?? item.confirmation.songs?.map((song) => song.id)
            }
            : undefined
        }));
        setChatMessages((current) => current.length > 0 ? current : restoredMessages);
      })
      .catch(() => {
        // History restore is best-effort; an unavailable backend should not block local chatting.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const backendLabel = useMemo(() => status?.backend ?? "offline", [status]);
  const qqMusicStatus = useMemo(
    () => providerStatuses.find((item) => sourceKey(item.provider) === "qqmusic") ?? null,
    [providerStatuses]
  );
  const backendDisplayLabel = backendLabel === "ok" ? "正常" : "离线";
  const sourceStatusPills = useMemo(
    () => selectedSources.map((source) => sourceStatusPill(source, providerStatuses)),
    [providerStatuses, selectedSources]
  );
  const selectedSourcesLabel = useMemo(() => selectedSources.map(sourceDisplayName).join(" / "), [selectedSources]);
  const qqMusicConnectionLabel = qqMusicStatus ? providerConnectionLabel(qqMusicStatus) : "QQ 音乐状态未知";
  const musicOperationDisabledReason = qqMusicStatus?.authenticated
    ? null
    : `${qqMusicConnectionLabel}。需要先连接 QQ 音乐才能搜索和播放。`;
  const pageTitle = route === "setup" ? "音乐源激活" : route === "playlists" ? "Musio 歌单" : "播放控制台";
  const pageSubcopy = route === "setup"
    ? "激活本次启动选择的音乐源。允许部分登录，也可以进入受限模式。"
    : `${status ? `${status.aiProvider} / ${status.aiModel}` : "模型配置不可用"} · ${selectedSourcesLabel} · ${qqMusicConnectionLabel}`;
  const addEvent = useCallback((event: EventLog) => {
    setEvents((current) => [event, ...current]);
  }, []);

  function playSong(song: Song) {
    void player.playSong(song)
      .then(() => addEvent({ id: crypto.randomUUID(), name: "player", detail: `正在播放：${song.title || song.id}` }))
      .catch((error) => {
        const detail = error instanceof Error && error.message ? error.message : "未知错误";
        addEvent({ id: crypto.randomUUID(), name: "player", detail: `播放失败：${detail}` });
      });
  }

  function playPlaylistSongs(songs: Song[], startIndex: number, playlistName: string) {
    const song = songs[startIndex];
    void player.playSongs(songs, startIndex)
      .then(() => addEvent({
        id: crypto.randomUUID(),
        name: "player",
        detail: `已将播放队列切换为 ${playlistName}，正在播放：${song?.title || song?.id || "歌曲"}`
      }))
      .catch((error) => {
        const detail = error instanceof Error && error.message ? error.message : "未知错误";
        addEvent({ id: crypto.randomUUID(), name: "player", detail: `播放失败：${detail}` });
      });
  }

  function addToQueue(song: Song) {
    player.addToQueue(song);
    addEvent({ id: crypto.randomUUID(), name: "queue", detail: `已加入队列：${song.title || song.id}` });
  }

  function addSongsToQueue(songs: Song[], sourceLabel: string) {
    player.addSongsToQueue(songs);
    addEvent({
      id: crypto.randomUUID(),
      name: "queue",
      detail: songs.length === 1
        ? `已加入队列：${songs[0].title || songs[0].id}`
        : `已加入队列：${sourceLabel}（${songs.length} 首）`
    });
  }

  function favoriteSong(song: Song) {
    void api.addSongToMusioPlaylist("default", song)
      .then(() => addEvent({ id: crypto.randomUUID(), name: "favorite", detail: `已收藏到 Musio：${song.title || song.id}` }))
      .catch((error) => {
        const detail = error instanceof Error && error.message ? error.message : "未知错误";
        addEvent({ id: crypto.randomUUID(), name: "favorite", detail: `收藏失败：${detail}` });
      });
  }

  function openDrawer(drawer: WorkbenchDrawer) {
    setActiveDrawer(drawer);
    setDrawerOpen(true);
  }

  return (
    <main className={`app-shell ${route === "workbench" ? "app-shell-workbench" : ""}`}>
      <aside className="rail">
        <div className="brand-mark">
          <span>M</span>
        </div>
        <button
          className={`rail-button ${route === "setup" ? "active" : ""}`}
          aria-label="音乐源"
          onClick={() => setRoute("setup")}
        >
          <Cable size={20} />
        </button>
        <button
          className={`rail-button ${route === "workbench" ? "active" : ""}`}
          aria-label="Agent 工作台"
          onClick={() => setRoute("workbench")}
        >
          <MessageSquare size={20} />
        </button>
        <button
          className={`rail-button ${route === "playlists" ? "active" : ""}`}
          aria-label="Musio 歌单"
          onClick={() => setRoute("playlists")}
        >
          <ListMusic size={20} />
        </button>
      </aside>

      <section className={`workspace ${route === "workbench" ? "workspace-workbench" : ""}`}>
        {route !== "workbench" ? (
          <header className="topbar">
            <div>
              <p className="eyebrow">Musio 本地音乐 Agent</p>
              <h1>{pageTitle}</h1>
              <p className="config-line">{pageSubcopy}</p>
            </div>
            <div className={`status-pill ${backendLabel === "ok" ? "online" : ""}`}>
              <Activity size={16} />
              <span>{backendDisplayLabel}</span>
            </div>
          </header>
        ) : null}

        {route === "setup" ? (
          <SourceSetupPage
            busy={busy}
            selectedSources={selectedSources}
            onBusyChange={setBusy}
            onEvent={addEvent}
            onProviderStatusesChange={setProviderStatuses}
            onContinue={() => {
              refreshProviderStatuses();
              setRoute("workbench");
            }}
          />
        ) : route === "playlists" ? (
          <MusioPlaylistsPage
            currentSongId={player.state.currentSong?.id ?? null}
            disabledReason={musicOperationDisabledReason}
            onPlayPlaylist={playPlaylistSongs}
            onAddSongsToQueue={addSongsToQueue}
            onEvent={addEvent}
          />
        ) : (
          <section className="workbench-stage">
            <MagneticDotField />
            <TimeBackdrop now={now} />
            <section className={`radio-workbench ${drawerOpen ? "drawer-open" : ""}`}>
              <header className="radio-header">
                <div className="radio-brand">
                  <div className="radio-avatar">M</div>
                  <div>
                    <p>Musio FM</p>
                    <strong>Musio</strong>
                  </div>
                </div>
                <div className="radio-header-actions">
                  <span className={`radio-state ${player.state.currentSong && !player.state.paused ? "online" : ""}`}>
                    {player.state.currentSong && !player.state.paused ? "正在播放" : "待播放"}
                  </span>
                  {sourceStatusPills.map((item) => (
                    <span className={`radio-state ${item.healthy ? "online" : ""}`} key={item.key}>{item.label}</span>
                  ))}
                  <span>{status ? status.aiModel : "MODEL OFFLINE"}</span>
                </div>
              </header>
              <PlayerShell
                state={player.state}
                onTogglePaused={player.togglePaused}
                onPrevious={() => void player.previous()}
                onNext={() => void player.next()}
                onNextMode={player.nextMode}
              />
              <div className="spectrum-divider">
                <PlayerSpectrum levels={player.state.spectrumLevels} />
              </div>
              <div className="agent-workspace">
                <AgentChatPanel
                  busy={busy}
                  disabledReason={musicOperationDisabledReason}
                  message={chatDraft}
                  messages={chatMessages}
                  onBusyChange={setBusy}
                  onMessageChange={setChatDraft}
                  onMessagesChange={setChatMessages}
                  onEvent={addEvent}
                  onPlaySong={playSong}
                  onAddToQueue={addToQueue}
                  onFavoriteSong={favoriteSong}
                />
              </div>
              <footer className="radio-footer">
                <span>MUSIO FM</span>
                <span>{qqMusicConnectionLabel}</span>
              </footer>
            </section>
            <StatusRail
              activeDrawer={activeDrawer}
              onOpenDrawer={openDrawer}
            />
            <WorkbenchDrawerPanel
              open={drawerOpen}
              activeDrawer={activeDrawer}
              busy={busy}
              disabledReason={musicOperationDisabledReason}
              songs={songs}
              events={events}
              playerState={player.state}
              onClose={() => setDrawerOpen(false)}
              onSongs={setSongs}
              onBusyChange={setBusy}
              onEvent={addEvent}
              onPlaySong={playSong}
              onAddToQueue={addToQueue}
              onFavoriteSong={favoriteSong}
              onPlayQueueIndex={(index) => void player.playQueueIndex(index)}
              onRemoveFromQueue={player.removeFromQueue}
              onClearEvents={() => setEvents([])}
            />
          </section>
        )}
      </section>
    </main>
  );
}

type WorkbenchDrawer = "search" | "queue" | "lyrics" | "comments" | "trace";

type MagneticDotTone = "neutral" | "accent" | "cold";

type MagneticDot = {
  x: number;
  y: number;
  size: number;
  baseOpacity: number;
  pull: number;
  tone: MagneticDotTone;
};

function MagneticDotField() {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const dotsDataRef = useRef<MagneticDot[]>([]);
  const frameRef = useRef<number | null>(null);
  const gridSignatureRef = useRef("");
  const pointerRef = useRef({ active: false, x: 0, y: 0 });

  useEffect(() => {
    const canvas = canvasRef.current;
    const stage = canvas?.parentElement;
    if (!canvas || !stage) {
      return;
    }
    const canvasElement = canvas;
    const stageElement = stage;
    const context = canvasElement.getContext("2d");
    if (!context) {
      return;
    }
    const drawingContext = context;
    const staticCanvas = document.createElement("canvas");
    const staticContext = staticCanvas.getContext("2d");
    if (!staticContext) {
      return;
    }
    const staticDrawingContext = staticContext;
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    let canvasRect = canvasElement.getBoundingClientRect();
    let fieldWidth = Math.round(canvasRect.width);
    let fieldHeight = Math.round(canvasRect.height);

    function resizeAndBuildDots() {
      const bounds = canvasElement.getBoundingClientRect();
      const { width, height } = bounds;
      if (width <= 0 || height <= 0) {
        return;
      }
      const pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
      const canvasWidth = Math.round(width);
      const canvasHeight = Math.round(height);
      const signature = `${canvasWidth}:${canvasHeight}:${pixelRatio}`;

      canvasRect = bounds;
      fieldWidth = canvasWidth;
      fieldHeight = canvasHeight;

      if (gridSignatureRef.current === signature) {
        drawDots();
        return;
      }

      canvasElement.width = Math.max(1, Math.round(canvasWidth * pixelRatio));
      canvasElement.height = Math.max(1, Math.round(canvasHeight * pixelRatio));
      staticCanvas.width = canvasElement.width;
      staticCanvas.height = canvasElement.height;
      drawingContext.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
      staticDrawingContext.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);

      gridSignatureRef.current = signature;
      const nextDots: MagneticDot[] = [];
      const dotLayers: Array<{
        spacing: number;
        offsetX: number;
        offsetY: number;
        size: number;
        baseOpacity: number;
        pull: number;
        tone: MagneticDotTone;
      }> = [
        { spacing: 18, offsetX: 0, offsetY: 0, size: 0.9, baseOpacity: 0.18, pull: 0.22, tone: "neutral" },
        { spacing: 36, offsetX: 9, offsetY: 11, size: 0.85, baseOpacity: 0.13, pull: 0.28, tone: "accent" },
        { spacing: 72, offsetX: 18, offsetY: 6, size: 0.76, baseOpacity: 0.1, pull: 0.18, tone: "cold" }
      ];

      dotLayers.forEach((layer) => {
        for (let y = layer.offsetY; y <= canvasHeight + layer.spacing; y += layer.spacing) {
          for (let x = layer.offsetX; x <= canvasWidth + layer.spacing; x += layer.spacing) {
            nextDots.push({
              x,
              y,
              size: layer.size,
              baseOpacity: layer.baseOpacity,
              pull: layer.pull,
              tone: layer.tone
            });
          }
        }
      });

      dotsDataRef.current = nextDots;
      drawStaticDots();
      drawDots();
    }

    function drawStaticDots() {
      staticDrawingContext.clearRect(0, 0, fieldWidth, fieldHeight);
      dotsDataRef.current.forEach((dot) => {
        staticDrawingContext.beginPath();
        staticDrawingContext.fillStyle = dotFillStyle(dot.tone, dot.baseOpacity);
        staticDrawingContext.arc(dot.x, dot.y, dot.size, 0, Math.PI * 2);
        staticDrawingContext.fill();
      });
    }

    function drawDots() {
      frameRef.current = null;
      if (fieldWidth <= 0 || fieldHeight <= 0) {
        return;
      }
      const pointer = pointerRef.current;
      const data = dotsDataRef.current;
      const radius = clampNumber(Math.min(fieldWidth, fieldHeight) * 0.2, 110, 210);
      const maxPull = clampNumber(Math.min(fieldWidth, fieldHeight) * 0.022, 8, 18);
      const radiusSquared = radius * radius;

      drawingContext.clearRect(0, 0, fieldWidth, fieldHeight);
      drawingContext.drawImage(staticCanvas, 0, 0, fieldWidth, fieldHeight);
      if (reducedMotion || !pointer.active) {
        return;
      }

      data.forEach((dot) => {
        const baseX = dot.x;
        const baseY = dot.y;
        const deltaX = pointer.x - baseX;
        const deltaY = pointer.y - baseY;
        if (Math.abs(deltaX) > radius || Math.abs(deltaY) > radius) {
          return;
        }
        const distanceSquared = deltaX * deltaX + deltaY * deltaY;
        if (distanceSquared > radiusSquared) {
          return;
        }
        const distance = Math.sqrt(distanceSquared);
        const force = Math.max(0, 1 - distance / radius);
        const easedForce = force * force * (3 - 2 * force);
        if (easedForce < 0.012) {
          return;
        }
        const safeDistance = distance || 1;
        const attraction = maxPull * easedForce * dot.pull;
        const x = baseX + (deltaX / safeDistance) * attraction;
        const y = baseY + (deltaY / safeDistance) * attraction;
        const opacity = Math.min(0.42, dot.baseOpacity + easedForce * 0.16);
        const size = dot.size * (1 + easedForce * 0.32);

        drawingContext.beginPath();
        drawingContext.fillStyle = dotFillStyle(dot.tone, opacity);
        drawingContext.arc(x, y, size, 0, Math.PI * 2);
        drawingContext.fill();
      });
    }

    function scheduleMagneticFrame() {
      if (frameRef.current !== null) {
        return;
      }
      frameRef.current = window.requestAnimationFrame(drawDots);
    }

    function handlePointerMove(event: PointerEvent) {
      if (event.pointerType === "touch") {
        return;
      }

      const x = event.clientX - canvasRect.left;
      const y = event.clientY - canvasRect.top;
      pointerRef.current = { active: true, x, y };

      scheduleMagneticFrame();
    }

    function handlePointerLeave() {
      pointerRef.current = { ...pointerRef.current, active: false };

      scheduleMagneticFrame();
    }

    resizeAndBuildDots();
    const observer = new ResizeObserver(resizeAndBuildDots);
    observer.observe(canvasElement);
    stageElement.addEventListener("pointermove", handlePointerMove);
    stageElement.addEventListener("pointerleave", handlePointerLeave);

    return () => {
      observer.disconnect();
      stageElement.removeEventListener("pointermove", handlePointerMove);
      stageElement.removeEventListener("pointerleave", handlePointerLeave);
      if (frameRef.current !== null) {
        window.cancelAnimationFrame(frameRef.current);
      }
    };
  }, []);

  return (
    <canvas className="magnetic-dot-field" ref={canvasRef} aria-hidden="true" />
  );
}

function dotFillStyle(tone: MagneticDotTone, opacity: number) {
  switch (tone) {
    case "accent":
      return `rgba(125, 245, 184, ${opacity})`;
    case "cold":
      return `rgba(192, 213, 226, ${opacity * 0.92})`;
    default:
      return `rgba(232, 238, 235, ${opacity})`;
  }
}

function TimeBackdrop({ now }: { now: Date }) {
  const [hour, minute] = formatClockParts(now);
  return (
    <div className="time-backdrop" aria-hidden="true">
      <div className="time-flank time-flank-left">
        <span>HOUR</span>
        <DotMatrixValue value={hour} />
        <small>{formatWeekday(now)}</small>
      </div>
      <div className="time-flank time-flank-right">
        <span>MIN</span>
        <DotMatrixValue value={minute} />
        <small>{formatCompactDate(now)}</small>
      </div>
    </div>
  );
}

function DotMatrixValue({ value, small = false }: { value: string; small?: boolean }) {
  return (
    <div className={`dot-matrix-time ${small ? "small" : ""}`}>
      {[...value].map((character, characterIndex) => (
        <span className="dot-matrix-character" key={`${character}-${characterIndex}`}>
          {(DOT_MATRIX_DIGITS[character] ?? DOT_MATRIX_DIGITS["0"]).flatMap((row, rowIndex) =>
            [...row].map((cell, columnIndex) => (
              <i
                className={cell === "1" ? "active" : ""}
                key={`${rowIndex}-${columnIndex}`}
              />
            ))
          )}
        </span>
      ))}
    </div>
  );
}

const DOT_MATRIX_DIGITS: Record<string, string[]> = {
  "0": [
    "0011100",
    "0110110",
    "1100011",
    "1100011",
    "1100011",
    "1100011",
    "1100011",
    "0110110",
    "0011100"
  ],
  "1": [
    "0001100",
    "0011100",
    "0111100",
    "0001100",
    "0001100",
    "0001100",
    "0001100",
    "0001100",
    "0111110"
  ],
  "2": [
    "0011110",
    "0110011",
    "1100011",
    "0000011",
    "0000110",
    "0001100",
    "0011000",
    "0110000",
    "1111111"
  ],
  "3": [
    "0111110",
    "0000011",
    "0000011",
    "0000110",
    "0011110",
    "0000011",
    "0000011",
    "1100011",
    "0111110"
  ],
  "4": [
    "0000110",
    "0001110",
    "0011110",
    "0110110",
    "1100110",
    "1111111",
    "0000110",
    "0000110",
    "0001111"
  ],
  "5": [
    "1111111",
    "1100000",
    "1100000",
    "1111100",
    "0000110",
    "0000011",
    "0000011",
    "1100110",
    "0111100"
  ],
  "6": [
    "0001110",
    "0011000",
    "0110000",
    "1100000",
    "1111100",
    "1100110",
    "1100011",
    "0110011",
    "0011110"
  ],
  "7": [
    "1111111",
    "0000011",
    "0000110",
    "0000110",
    "0001100",
    "0001100",
    "0011000",
    "0011000",
    "0011000"
  ],
  "8": [
    "0011110",
    "0110011",
    "1100011",
    "0110011",
    "0011110",
    "0110011",
    "1100011",
    "0110011",
    "0011110"
  ],
  "9": [
    "0011110",
    "0110011",
    "1100011",
    "1100011",
    "0111111",
    "0000011",
    "0000110",
    "0001100",
    "0111000"
  ]
};

function StatusRail({ activeDrawer, onOpenDrawer }: {
  activeDrawer: WorkbenchDrawer;
  onOpenDrawer: (drawer: WorkbenchDrawer) => void;
}) {
  return (
    <aside className="status-rail" aria-label="Musio system rail">
      <nav className="drawer-rail" aria-label="Workbench drawers">
        <DrawerButton drawer="search" activeDrawer={activeDrawer} onOpenDrawer={onOpenDrawer} icon={<Search size={16} />} label="SEARCH" />
        <DrawerButton drawer="queue" activeDrawer={activeDrawer} onOpenDrawer={onOpenDrawer} icon={<ListMusic size={16} />} label="QUEUE" />
        <DrawerButton drawer="lyrics" activeDrawer={activeDrawer} onOpenDrawer={onOpenDrawer} icon={<FileText size={16} />} label="LYRICS" />
        <DrawerButton drawer="comments" activeDrawer={activeDrawer} onOpenDrawer={onOpenDrawer} icon={<MessageCircle size={16} />} label="COMMENTS" />
        <DrawerButton drawer="trace" activeDrawer={activeDrawer} onOpenDrawer={onOpenDrawer} icon={<Terminal size={16} />} label="TRACE" />
      </nav>
    </aside>
  );
}

function DrawerButton({
  drawer,
  activeDrawer,
  onOpenDrawer,
  icon,
  label
}: {
  drawer: WorkbenchDrawer;
  activeDrawer: WorkbenchDrawer;
  onOpenDrawer: (drawer: WorkbenchDrawer) => void;
  icon: ReactNode;
  label: string;
}) {
  return (
    <button type="button" className={drawer === activeDrawer ? "active" : ""} onClick={() => onOpenDrawer(drawer)} aria-label={label} title={label}>
      {icon}
    </button>
  );
}

function WorkbenchDrawerPanel({
  open,
  activeDrawer,
  busy,
  disabledReason,
  songs,
  events,
  playerState,
  onClose,
  onSongs,
  onBusyChange,
  onEvent,
  onPlaySong,
  onAddToQueue,
  onFavoriteSong,
  onPlayQueueIndex,
  onRemoveFromQueue,
  onClearEvents
}: {
  open: boolean;
  activeDrawer: WorkbenchDrawer;
  busy: boolean;
  disabledReason?: string | null;
  songs: Song[];
  events: EventLog[];
  playerState: ReturnType<typeof usePlayerStore>["state"];
  onClose: () => void;
  onSongs: (songs: Song[]) => void;
  onBusyChange: (busy: boolean) => void;
  onEvent: (event: EventLog) => void;
  onPlaySong: (song: Song) => void;
  onAddToQueue: (song: Song) => void;
  onFavoriteSong: (song: Song) => void;
  onPlayQueueIndex: (index: number) => void;
  onRemoveFromQueue: (songId: string) => void;
  onClearEvents: () => void;
}) {
  return (
    <aside className={`workbench-drawer ${open ? "open" : ""}`} aria-label="Musio side drawer">
      <div className="drawer-heading">
        <div>
          <span>DRAWER</span>
          <strong>{drawerTitle(activeDrawer)}</strong>
        </div>
        <button type="button" onClick={onClose}>CLOSE</button>
      </div>
      <div className="drawer-body">
        {activeDrawer === "search" ? (
          <SongCards
            busy={busy}
            disabledReason={disabledReason}
            songs={songs}
            onSongs={onSongs}
            onBusyChange={onBusyChange}
            onEvent={onEvent}
            onPlaySong={onPlaySong}
            onAddToQueue={onAddToQueue}
            onFavoriteSong={onFavoriteSong}
          />
        ) : activeDrawer === "queue" ? (
          <QueuePanel state={playerState} onPlayQueueIndex={onPlayQueueIndex} onRemoveFromQueue={onRemoveFromQueue} />
        ) : activeDrawer === "lyrics" ? (
          <LyricsPanel state={playerState} />
        ) : activeDrawer === "comments" ? (
          <CommentsPanel state={playerState} disabledReason={disabledReason} onEvent={onEvent} />
        ) : (
          <AgentEvents events={events} onClear={onClearEvents} />
        )}
      </div>
    </aside>
  );
}

function QueuePanel({
  state,
  onPlayQueueIndex,
  onRemoveFromQueue
}: {
  state: ReturnType<typeof usePlayerStore>["state"];
  onPlayQueueIndex: (index: number) => void;
  onRemoveFromQueue: (songId: string) => void;
}) {
  return (
    <section className="panel queue-panel">
      <div className="panel-heading">
        <h2>播放队列</h2>
        <span>{state.queue.length} tracks</span>
      </div>
      <div className="queue-list">
        {state.queue.length === 0 ? (
          <p className="empty-copy">[QUEUE EMPTY]</p>
        ) : (
          state.queue.map((song, index) => (
            <article key={song.id} className={`queue-row ${index === state.currentIndex ? "active" : ""}`}>
              <span>{String(index + 1).padStart(2, "0")}</span>
              <div>
                <strong>{song.title || song.id}</strong>
                <small>{song.artists?.join(", ") || song.provider || "QQ 音乐"}</small>
              </div>
              <button type="button" title="播放" aria-label={`播放 ${song.title || song.id}`} onClick={() => onPlayQueueIndex(index)}>
                <Play size={14} />
              </button>
              <button type="button" title="移出队列" aria-label={`移出队列 ${song.title || song.id}`} onClick={() => onRemoveFromQueue(song.id)}>
                <Trash2 size={14} />
              </button>
            </article>
          ))
        )}
      </div>
    </section>
  );
}

function LyricsPanel({ state }: { state: ReturnType<typeof usePlayerStore>["state"] }) {
  const activeLineRef = useRef<HTMLParagraphElement | null>(null);
  const hasSyncedLyrics = state.lyricLines.length > 0;

  useEffect(() => {
    if (!hasSyncedLyrics || state.activeLyricIndex < 0) {
      return;
    }
    activeLineRef.current?.scrollIntoView({ block: "center", behavior: "smooth" });
  }, [hasSyncedLyrics, state.activeLyricIndex, state.currentSong?.id]);

  return (
    <section className="panel lyrics-panel">
      <div className="panel-heading">
        <h2>歌词</h2>
        <span>{state.currentSong ? "current" : "empty"}</span>
      </div>
      <p className="lyrics-track">{state.currentSong ? `${state.currentSong.title} - ${state.currentSong.artists?.join(", ")}` : "[NO TRACK]"}</p>
      {hasSyncedLyrics ? (
        <div className="lyrics-list" aria-label="同步歌词">
          {state.lyricLines.map((line, index) => {
            const active = index === state.activeLyricIndex;
            return (
              <p
                key={`${line.timeSeconds}-${index}`}
                ref={active ? activeLineRef : undefined}
                className={`lyrics-row ${active ? "active" : ""}`}
                aria-current={active ? "true" : undefined}
              >
                <span>{formatLyricTime(line.timeSeconds)}</span>
                <strong>{line.text}</strong>
              </p>
            );
          })}
        </div>
      ) : (
        <pre>{state.lyricsText || state.lyricLine || "[LYRICS UNAVAILABLE]"}</pre>
      )}
    </section>
  );
}

function formatLyricTime(timeSeconds: number) {
  const safeSeconds = Math.max(0, Math.floor(timeSeconds));
  const minutes = Math.floor(safeSeconds / 60);
  const seconds = safeSeconds % 60;
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
}

function CommentsPanel({
  state,
  disabledReason,
  onEvent
}: {
  state: ReturnType<typeof usePlayerStore>["state"];
  disabledReason?: string | null;
  onEvent: (event: EventLog) => void;
}) {
  const [comments, setComments] = useState<SongComment[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const songId = state.currentSong?.id ?? "";

  useEffect(() => {
    setComments([]);
    setError(null);
    if (!songId || disabledReason) {
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    api.comments(songId)
      .then((result) => {
        if (cancelled) {
          return;
        }
        setComments(result);
        onEvent({ id: crypto.randomUUID(), name: "comments", detail: `读取热门评论 ${result.length} 条` });
      })
      .catch((fetchError) => {
        if (cancelled) {
          return;
        }
        const detail = fetchError instanceof Error && fetchError.message ? fetchError.message : "未知错误";
        setError(detail);
        onEvent({ id: crypto.randomUUID(), name: "comments", detail: `评论读取失败：${detail}` });
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [disabledReason, onEvent, songId]);

  return (
    <section className="panel comments-panel">
      <div className="panel-heading">
        <h2>评论</h2>
        <span>{comments.length} hot</span>
      </div>
      <p className="lyrics-track">{state.currentSong ? `${state.currentSong.title} - ${state.currentSong.artists?.join(", ")}` : "[NO TRACK]"}</p>
      {disabledReason ? <p className="access-note">{disabledReason}</p> : null}
      {!state.currentSong ? <p className="empty-copy">[PLAY A TRACK TO LOAD COMMENTS]</p> : null}
      {loading ? <p className="empty-copy">[LOADING COMMENTS]</p> : null}
      {error ? <p className="access-note">{error}</p> : null}
      {!loading && !error && state.currentSong && comments.length === 0 ? <p className="empty-copy">[NO COMMENTS]</p> : null}
      <div className="comment-list">
        {comments.map((comment) => (
          <article className="comment-row" key={comment.id}>
            <div className="comment-row-meta">
              <strong>{comment.authorName || "QQ MUSIC USER"}</strong>
              <span>{formatCommentDate(comment.createdAt)}</span>
            </div>
            <p>{comment.text}</p>
            <small>{(comment.likedCount ?? 0).toLocaleString()} LIKES</small>
          </article>
        ))}
      </div>
    </section>
  );
}

function drawerTitle(drawer: WorkbenchDrawer) {
  switch (drawer) {
    case "search":
      return "SEARCH";
    case "queue":
      return "QUEUE";
    case "lyrics":
      return "LYRICS";
    case "comments":
      return "COMMENTS";
    case "trace":
      return "TRACE";
  }
}

function formatClockParts(date: Date) {
  return new Intl.DateTimeFormat("en-US", {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false
  }).format(date).split(":");
}

function formatCompactDate(date: Date) {
  return new Intl.DateTimeFormat("en-US", {
    month: "short",
    day: "2-digit"
  }).format(date).toUpperCase();
}

function formatWeekday(date: Date) {
  return new Intl.DateTimeFormat("en-US", { weekday: "short" }).format(date).toUpperCase();
}

function formatCommentDate(value?: string | null) {
  if (!value) {
    return "UNKNOWN";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "UNKNOWN";
  }
  return new Intl.DateTimeFormat("en-US", {
    month: "short",
    day: "2-digit"
  }).format(date).toUpperCase();
}

function clampNumber(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}

function sourceKey(source: string) {
  const normalized = source.replace(/[_-]/g, "").toLowerCase();
  if (normalized === "qqmusic" || normalized === "qq") {
    return "qqmusic";
  }
  return normalized;
}

function providerConnectionLabel(status: ProviderStatus) {
  if (status.authenticated) {
    return status.musicGeneState === "READY" ? "QQ 音乐已连接，音乐基因已就绪" : "QQ 音乐已连接，音乐基因待生成";
  }
  switch (status.connectionState) {
    case "EXPIRED":
      return "QQ 音乐登录已过期";
    case "UNVERIFIED":
      return "QQ 音乐等待校验";
    case "NOT_LOGGED_IN":
      return "QQ 音乐未连接";
    default:
      return "QQ 音乐未连接";
  }
}

function sourceStatusPill(source: string, providerStatuses: ProviderStatus[]) {
  const key = sourceKey(source);
  const providerStatus = providerStatuses.find((item) => sourceKey(item.provider) === key);
  const displayName = sourceShortName(source);
  if (!providerStatus) {
    if (providerStatuses.length === 0) {
      return { key, label: `${displayName} 检查中`, healthy: false };
    }
    return { key, label: `${displayName} 未开放`, healthy: false };
  }
  if (providerStatus.authenticated) {
    return { key, label: `${displayName} 正常`, healthy: true };
  }
  if (providerStatus.connectionState === "COMING_SOON" || providerStatus.connectionState === "SANDBOX_RESERVED") {
    return { key, label: `${displayName} 未开放`, healthy: false };
  }
  if (providerStatus.connectionState === "EXPIRED") {
    return { key, label: `${displayName} 过期`, healthy: false };
  }
  if (providerStatus.connectionState === "UNVERIFIED") {
    return { key, label: `${displayName} 待校验`, healthy: false };
  }
  return { key, label: `${displayName} 未连接`, healthy: false };
}

function sourceShortName(source: string) {
  switch (sourceKey(source)) {
    case "qqmusic":
      return "QQ";
    case "netease":
      return "网易云";
    case "local":
      return "本地";
    default:
      return source;
  }
}

function sourceDisplayName(source: string) {
  switch (source) {
    case "qqmusic":
      return "QQ 音乐";
    case "netease":
      return "网易云音乐";
    case "local":
      return "本地音乐";
    default:
      return source;
  }
}

function initialRouteFromUrl(): AppRoute {
  return new URLSearchParams(window.location.search).has("sources") ? "setup" : "workbench";
}

function selectedSourcesFromUrl(): string[] {
  const params = new URLSearchParams(window.location.search);
  const raw = params.get("sources");
  if (!raw) {
    return ["qqmusic"];
  }
  return raw.split(",")
    .map((item) => item.trim().toLowerCase())
    .filter(Boolean);
}
