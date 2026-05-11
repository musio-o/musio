import { ListPlus, Play, Trash2 } from "lucide-react";
import { MusioPlaylist, MusioPlaylistItem } from "../../shared/types";

type MusioPlaylistDetailProps = {
  playlist: MusioPlaylist | null;
  items: MusioPlaylistItem[];
  currentSongId?: string | null;
  operationItemId?: string | null;
  onPlayAll: () => void;
  onAddAllToQueue: () => void;
  onPlayItem: (index: number) => void;
  onAddItemToQueue: (index: number) => void;
  onRemoveItem: (item: MusioPlaylistItem) => void;
};

export function MusioPlaylistDetail({
  playlist,
  items,
  currentSongId,
  operationItemId,
  onPlayAll,
  onAddAllToQueue,
  onPlayItem,
  onAddItemToQueue,
  onRemoveItem
}: MusioPlaylistDetailProps) {
  if (!playlist) {
    return <p className="empty-copy">选择一个 Musio 歌单查看跨音乐源歌曲。</p>;
  }

  return (
    <div className="musio-playlist-detail">
      <div className="musio-playlist-detail-heading">
        <div className="musio-playlist-title-block">
          <span className="nothing-signal" aria-hidden="true" />
          <div>
            <h3>{playlist.name}</h3>
            <p>{playlist.description || `${items.length} 首歌曲`}</p>
          </div>
        </div>
        <div className="musio-playlist-readout" aria-label="歌单信息">
          <span>{String(items.length).padStart(2, "0")}</span>
          <small>TRACKS</small>
          <span>{formatPlaylistDate(playlist.updatedAt)}</span>
          <small>UPDATED</small>
        </div>
        <div className="musio-playlist-actions">
          <button className="primary" type="button" onClick={onPlayAll} disabled={items.length === 0}>
            <Play size={15} />
            <span>播放全部</span>
          </button>
          <button type="button" onClick={onAddAllToQueue} disabled={items.length === 0}>
            <ListPlus size={15} />
            <span>加入队列</span>
          </button>
        </div>
      </div>
      {items.length === 0 ? (
        <p className="empty-copy">这个歌单还没有歌曲。</p>
      ) : (
        <div className="song-list">
          {items.map((item, index) => (
            <article
              className={`song-row musio-playlist-row ${currentSongId === item.providerTrackId ? "active" : ""}`}
              key={item.id}
              tabIndex={0}
              onClick={() => onPlayItem(index)}
              onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  onPlayItem(index);
                }
              }}
            >
              <span className="musio-track-index">{String(index + 1).padStart(2, "0")}</span>
              <div className="musio-track-art">
                {item.artworkUrl ? <img src={item.artworkUrl} alt="" /> : <span>{trackInitial(item.title || item.providerTrackId)}</span>}
              </div>
              <div className="musio-track-copy">
                <strong>{item.title || item.providerTrackId}</strong>
                <span>{item.artists.join(", ") || "Unknown Artist"}</span>
                <small>{[item.provider, item.album, formatDuration(item.durationSeconds)].filter(Boolean).join(" / ")}</small>
              </div>
              <div className="song-action-cluster">
                <button
                  type="button"
                  title="播放"
                  aria-label={`播放 ${item.title || item.providerTrackId}`}
                  onClick={(event) => {
                    event.stopPropagation();
                    onPlayItem(index);
                  }}
                >
                  <Play size={15} />
                </button>
                <button
                  type="button"
                  title="加入队列"
                  aria-label={`加入队列 ${item.title || item.providerTrackId}`}
                  onClick={(event) => {
                    event.stopPropagation();
                    onAddItemToQueue(index);
                  }}
                >
                  <ListPlus size={15} />
                </button>
                <button
                  type="button"
                  title="移出 Musio 歌单"
                  aria-label={`移出 Musio 歌单 ${item.title || item.providerTrackId}`}
                  disabled={operationItemId === item.id}
                  onClick={(event) => {
                    event.stopPropagation();
                    onRemoveItem(item);
                  }}
                >
                  <Trash2 size={15} />
                </button>
              </div>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}

function trackInitial(title: string) {
  return title.trim().slice(0, 1).toUpperCase() || "M";
}

function formatDuration(durationSeconds?: number | null) {
  if (!durationSeconds || durationSeconds <= 0) {
    return "";
  }
  const minutes = Math.floor(durationSeconds / 60);
  const seconds = durationSeconds % 60;
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
}

function formatPlaylistDate(value?: string | null) {
  if (!value) {
    return "--";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "--";
  }
  return `${String(date.getMonth() + 1).padStart(2, "0")}.${String(date.getDate()).padStart(2, "0")}`;
}
