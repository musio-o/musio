import { api } from "../../shared/api";

export const playerClient = {
  state: api.playerState,
  syncState: api.syncPlayerState,
  songUrl: api.songUrl,
  streamUrl: (songId: string) => `/api/music/songs/${encodeURIComponent(songId)}/stream`,
  lyrics: api.lyrics
};
