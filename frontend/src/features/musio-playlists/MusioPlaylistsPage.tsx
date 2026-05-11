import { useEffect, useMemo, useState } from "react";
import { EventLog, MusioPlaylist, MusioPlaylistItem, Song } from "../../shared/types";
import { musioPlaylistClient } from "./musioPlaylistClient";
import { MusioPlaylistDetail } from "./MusioPlaylistDetail";

type MusioPlaylistsPageProps = {
  currentSongId?: string | null;
  disabledReason?: string | null;
  onPlayPlaylist: (songs: Song[], startIndex: number, playlistName: string) => void;
  onAddSongsToQueue: (songs: Song[], sourceLabel: string) => void;
  onEvent: (event: EventLog) => void;
};

export function MusioPlaylistsPage({
  currentSongId,
  disabledReason,
  onPlayPlaylist,
  onAddSongsToQueue,
  onEvent
}: MusioPlaylistsPageProps) {
  const [playlists, setPlaylists] = useState<MusioPlaylist[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [operationItemId, setOperationItemId] = useState<string | null>(null);

  useEffect(() => {
    musioPlaylistClient.list()
      .then((items) => {
        setPlaylists(items);
        setSelectedId((current) => items.some((item) => item.id === current) ? current : items[0]?.id ?? null);
      })
      .catch(() => {
        setPlaylists([]);
        onEvent({ id: crypto.randomUUID(), name: "playlist", detail: "Musio 歌单读取失败" });
      })
      .finally(() => setLoading(false));
  }, []);

  const selected = playlists.find((item) => item.id === selectedId) ?? null;
  const selectedItems = useMemo(() => selected ? orderedPlaylistItems(selected) : [], [selected]);
  const selectedSongs = useMemo(() => selectedItems.map(playlistItemToSong), [selectedItems]);
  const totalTrackCount = useMemo(
    () => playlists.reduce((total, playlist) => total + playlist.items.length, 0),
    [playlists]
  );

  function playSelectedPlaylist(startIndex: number) {
    if (!selected || selectedSongs.length === 0) {
      return;
    }
    if (disabledReason) {
      onEvent({ id: crypto.randomUUID(), name: "source", detail: disabledReason });
      return;
    }
    onPlayPlaylist(selectedSongs, startIndex, selected.name);
  }

  function addSelectedSongsToQueue(songs: Song[], sourceLabel: string) {
    if (songs.length === 0) {
      return;
    }
    onAddSongsToQueue(songs, sourceLabel);
  }

  async function removeItem(item: MusioPlaylistItem) {
    setOperationItemId(item.id);
    try {
      const updated = await musioPlaylistClient.removeItem(item.playlistId, item.id);
      setPlaylists((current) => current.map((playlist) => playlist.id === updated.id ? updated : playlist));
      onEvent({ id: crypto.randomUUID(), name: "playlist", detail: `已从 Musio 歌单移除：${item.title || item.providerTrackId}` });
    } catch (error) {
      const detail = error instanceof Error && error.message ? error.message : "未知错误";
      onEvent({ id: crypto.randomUUID(), name: "playlist", detail: `移除失败：${detail}` });
    } finally {
      setOperationItemId(null);
    }
  }

  return (
    <section className="panel musio-playlists-panel nothing-playlists-panel">
      <div className="nothing-playlists-header">
        <div>
          <p className="eyebrow">LOCAL PLAYLIST STORAGE</p>
          <h2>Musio 歌单</h2>
        </div>
        <div className="nothing-playlist-metrics" aria-label="Musio 歌单统计">
          <span><strong>{loading ? "--" : playlists.length}</strong> PLAYLISTS</span>
          <span><strong>{loading ? "--" : totalTrackCount}</strong> TRACKS</span>
          <span className={disabledReason ? "offline" : "online"}>{disabledReason ? "SOURCE LOCKED" : "SOURCE READY"}</span>
        </div>
      </div>
      <div className="musio-playlists-layout">
        <div className="musio-playlist-list" aria-label="Musio 歌单列表">
          {playlists.length === 0 ? (
            <p className="empty-copy">{loading ? "读取 Musio 歌单中。" : "还没有 Musio 歌单。"}</p>
          ) : (
            playlists.map((playlist, index) => (
              <button
                type="button"
                key={playlist.id}
                className={playlist.id === selectedId ? "selected" : ""}
                onClick={() => setSelectedId(playlist.id)}
              >
                <span className="musio-playlist-index">{String(index + 1).padStart(2, "0")}</span>
                <span className="musio-playlist-name">{playlist.name}</span>
                <small>{playlist.items.length} TRACKS</small>
              </button>
            ))
          )}
        </div>
        <MusioPlaylistDetail
          playlist={selected}
          items={selectedItems}
          currentSongId={currentSongId}
          operationItemId={operationItemId}
          onPlayAll={() => playSelectedPlaylist(0)}
          onAddAllToQueue={() => addSelectedSongsToQueue(selectedSongs, selected?.name ?? "Musio 歌单")}
          onPlayItem={playSelectedPlaylist}
          onAddItemToQueue={(index) => {
            const song = selectedSongs[index];
            if (song) {
              addSelectedSongsToQueue([song], song.title || song.id);
            }
          }}
          onRemoveItem={removeItem}
        />
      </div>
    </section>
  );
}

function orderedPlaylistItems(playlist: MusioPlaylist) {
  return [...playlist.items].sort((first, second) => first.sortOrder - second.sortOrder);
}

function playlistItemToSong(item: MusioPlaylistItem): Song {
  return {
    id: item.providerTrackId,
    provider: item.provider,
    title: item.title || item.providerTrackId,
    artists: item.artists ?? [],
    album: item.album ?? null,
    durationSeconds: item.durationSeconds ?? null,
    artworkUrl: item.artworkUrl ?? null
  };
}
