export type SystemStatus = {
  backend: string;
  qqMusicSidecarBaseUrl: string;
  configPath: string;
  aiProvider: string;
  aiModel: string;
  aiApiKeyConfigured: boolean;
  checkedAt: string;
};

export type LoginStartResult = {
  sessionId: string;
  provider: string;
  state: string;
  qrCodeDataUrl: string | null;
  message: string;
};

export type LoginStatus = {
  sessionId: string;
  provider: string;
  state: string;
  credentialStored: boolean;
  message: string;
};

export type ChatRunResponse = {
  runId: string;
  state: string;
  message: string;
};

export type ChatHistoryMessage = {
  role: "user" | "assistant";
  content: string;
  createdAt: string;
  songs?: Song[];
  confirmation?: ChatConfirmation | null;
};

export type Song = {
  id: string;
  provider?: string;
  title: string;
  artists: string[];
  album?: string | null;
  durationSeconds?: number | null;
  artworkUrl?: string | null;
};

export type ChatConfirmation = {
  actionId?: string;
  type: "local_playlist_add" | string;
  title: string;
  description: string;
  confirmText: string;
  cancelText: string;
  song?: Song | null;
  songs?: Song[];
  selectionMode?: "single" | "multiple" | string;
  defaultSelectedSongIds?: string[];
};

export type SongUrl = {
  songId: string;
  provider: string;
  url: string | null;
  expiresInSeconds: number | null;
};

export type Lyrics = {
  songId: string;
  provider: string;
  plainText: string;
  syncedText: string;
};

export type SyncedLyricLine = {
  timeSeconds: number;
  text: string;
};

export type SongComment = {
  id: string;
  songId: string;
  provider: string;
  authorName: string;
  text: string;
  likedCount?: number | null;
  createdAt?: string | null;
};

export type Playlist = {
  id: string;
  provider: string;
  name: string;
  songCount?: number | null;
  artworkUrl?: string | null;
};

export type EventLog = {
  id: string;
  name: string;
  detail: string;
};

export type AgentEvent = {
  type: string;
  data?: Record<string, unknown>;
  createdAt?: string;
};

export type ProviderStatus = {
  provider: string;
  displayName: string;
  available: boolean;
  authenticated: boolean;
  credentialStored: boolean;
  loginMethod: string;
  message: string;
  connectionState: string;
  musicGeneState: string;
  musicGeneStatus?: MusicGeneStatus;
};

export type MusicGeneStatus = {
  state: string;
  provider: string;
  accountKey?: string | null;
  userId?: string | null;
  euin?: string | null;
  generatedAt?: string | null;
  profileGeneratedAt?: string | null;
  sourceGeneGeneratedAt?: string | null;
  profileSynced: boolean;
  staleReason?: string | null;
  message: string;
};

export type MusicGeneSnapshot = {
  provider: string;
  accountKey: string;
  userId: string;
  euin: string;
  generatedAt: string;
  data: Record<string, unknown>;
};

export type PlaybackMode = "SEQUENTIAL" | "REPEAT_ONE" | "REPEAT_ALL" | "SHUFFLE";

export type PlayerState = {
  currentSong: Song | null;
  queue: Song[];
  currentIndex: number;
  paused: boolean;
  positionSeconds: number;
  durationSeconds: number | null;
  playbackMode: PlaybackMode;
  lyricLine: string;
  lyricsText: string;
  lyricLines: SyncedLyricLine[];
  activeLyricIndex: number;
  spectrumLevels: number[];
};

export type PlayerStateSyncPayload = Pick<
  PlayerState,
  "currentSong" | "queue" | "currentIndex" | "paused" | "positionSeconds" | "durationSeconds" | "playbackMode" | "lyricLine"
>;

export type MusioPlaylistItem = {
  id: string;
  playlistId: string;
  provider: string;
  providerTrackId: string;
  title: string;
  artists: string[];
  album?: string | null;
  durationSeconds?: number | null;
  artworkUrl?: string | null;
  sourceUrl?: string | null;
  sortOrder: number;
  createdAt: string;
};

export type MusioPlaylist = {
  id: string;
  name: string;
  description?: string | null;
  items: MusioPlaylistItem[];
  createdAt: string;
  updatedAt: string;
};
