import {
  ChatHistoryMessage,
  ChatRunResponse,
  Lyrics,
  LoginStartResult,
  LoginStatus,
  MusicGeneSnapshot,
  MusioPlaylist,
  PlayerState,
  PlayerStateSyncPayload,
  ProviderStatus,
  Song,
  SongComment,
  SongUrl,
  SystemStatus
} from "./types";

const API_BASE = "";

export type ChatSourceContext = {
  selectedSources: string[];
  activeSource: string;
};

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: {
      "Content-Type": "application/json",
      ...(init?.headers ?? {})
    },
    ...init
  });

  if (!response.ok) {
    throw new Error(await errorMessage(response));
  }

  return (await response.json()) as T;
}

async function errorMessage(response: Response): Promise<string> {
  try {
    const text = await response.text();
    if (!text) {
      return `HTTP ${response.status}`;
    }
    const json = JSON.parse(text) as { detail?: unknown; message?: unknown };
    if (typeof json.detail === "string" && json.detail.trim()) {
      return json.detail.trim();
    }
    if (typeof json.message === "string" && json.message.trim()) {
      return json.message.trim();
    }
    return text;
  } catch {
    return `HTTP ${response.status}`;
  }
}

export const api = {
  status: () => request<SystemStatus>("/api/system/status"),
  providers: () => request<ProviderStatus[]>("/api/providers"),
  providerStatus: (provider: string) => request<ProviderStatus>(`/api/providers/${provider}/status`),
  providerMusicGene: (provider: string) => request<MusicGeneSnapshot>(`/api/providers/${provider}/music-gene`),
  startProviderLogin: (provider: string) => request<LoginStartResult>(`/api/providers/${provider}/login/start`, { method: "POST" }),
  providerLoginStatus: (provider: string, sessionId: string) =>
    request<LoginStatus>(`/api/providers/${provider}/login/${sessionId}/status`),
  logoutProvider: (provider: string) => request<LoginStatus>(`/api/providers/${provider}/logout`, { method: "POST" }),
  startLogin: () => request<LoginStartResult>("/api/auth/qqmusic/qr"),
  loginStatus: (sessionId: string) => request<LoginStatus>(`/api/auth/qqmusic/qr/${sessionId}/status`),
  startChat: (message: string, displayMessage?: string, sourceContext?: ChatSourceContext) =>
    request<ChatRunResponse>("/api/chat", {
      method: "POST",
      body: JSON.stringify({
        userId: "local",
        message,
        displayMessage,
        selectedSources: sourceContext?.selectedSources,
        activeSource: sourceContext?.activeSource
      })
    }),
  confirmChatRun: (runId: string, actionId: string, approved: boolean, selectedSongIds: string[] = []) =>
    request<ChatRunResponse>(`/api/chat/runs/${encodeURIComponent(runId)}/confirm`, {
      method: "POST",
      body: JSON.stringify({
        actionId,
        approved,
        editedInput: {
          selectedSongIds,
          reason: approved ? "approved" : "cancelled"
        }
      })
    }),
  chatHistory: (userId = "local") => request<ChatHistoryMessage[]>(`/api/chat/history/${encodeURIComponent(userId)}`),
  search: (keyword: string, limit = 5) =>
    request<Song[]>(`/api/music/search?keyword=${encodeURIComponent(keyword)}&limit=${limit}`),
  songUrl: (songId: string) => request<SongUrl>(`/api/music/songs/${encodeURIComponent(songId)}/url`),
  lyrics: (songId: string) => request<Lyrics>(`/api/music/songs/${encodeURIComponent(songId)}/lyrics`),
  comments: (songId: string) => request<SongComment[]>(`/api/music/songs/${encodeURIComponent(songId)}/comments`),
  playerState: () => request<PlayerState>("/api/player/state"),
  syncPlayerState: (state: PlayerStateSyncPayload) =>
    request<PlayerState>("/api/player/state", {
      method: "POST",
      body: JSON.stringify(state)
    }),
  musioPlaylists: () => request<MusioPlaylist[]>("/api/musio/playlists"),
  addSongToMusioPlaylist: (playlistId: string, song: Song) =>
    request<MusioPlaylist>(`/api/musio/playlists/${encodeURIComponent(playlistId)}/items`, {
      method: "POST",
      body: JSON.stringify(song)
    }),
  removeMusioPlaylistItem: (playlistId: string, itemId: string) =>
    request<MusioPlaylist>(`/api/musio/playlists/${encodeURIComponent(playlistId)}/items/${encodeURIComponent(itemId)}`, {
      method: "DELETE"
    })
};
