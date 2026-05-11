import { api } from "../../shared/api";

export const musioPlaylistClient = {
  list: api.musioPlaylists,
  removeItem: api.removeMusioPlaylistItem
};
