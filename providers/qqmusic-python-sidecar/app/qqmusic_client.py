from __future__ import annotations

import json
import os
import re
from collections import Counter
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, AsyncIterator, Awaitable, Callable
from urllib.parse import urljoin

from qqmusic_api import Client, Credential
from qqmusic_api.modules.song import SongFileInfo, SongFileType

from .schemas import Comment, Lyrics, Playlist, Song, SongDetail, SongUrl, UserConnectionStatus, UserMusicGene, UserProfile

_SONG_ID_PREFIX = "qqmusic:"
_DEFAULT_STREAM_DOMAIN = "https://isure.stream.qqmusic.qq.com/"
_LRC_TIMESTAMP_PATTERN = re.compile(r"\[[0-9:.]+\]")
_WINDOWS_DRIVE_PATTERN = re.compile(r"^([A-Za-z]):[\\/](.*)$")
_MUSIC_GENE_LIKED_SONG_LIMIT = 80
_MUSIC_GENE_LIST_LIMIT = 30
_MUSIC_GENE_SIGNAL_LIMIT = 50


class QQMusicClient:
    """Thin adapter around qqmusic-api-python."""

    async def search(self, keyword: str, limit: int) -> list[Song]:
        async with self._client() as client:
            result = await client.search.search_by_type(keyword=keyword, num=limit)
            return [self._to_song(song) for song in result.song[:limit]]

    async def song(self, song_id: str) -> SongDetail:
        async with self._client() as client:
            track = await self._resolve_song(client, song_id)
            return self._to_song_detail(track)

    async def song_url(self, song_id: str) -> SongUrl:
        async with self._client() as client:
            track = await self._resolve_song(client, song_id)
            file_info = SongFileInfo(
                mid=track.mid,
                song_type=track.type,
                media_mid=track.file.media_mid or None,
            )
            for file_type in (SongFileType.MP3_320, SongFileType.MP3_128, SongFileType.ACC_96):
                response = await client.song.get_song_urls([file_info], file_type=file_type)
                if not response.data:
                    continue
                item = response.data[0]
                if item.result != 0 or not item.purl:
                    continue
                return SongUrl(
                    song_id=self._song_identity(track),
                    url=item.purl if item.purl.startswith("http") else urljoin(_DEFAULT_STREAM_DOMAIN, item.purl),
                    expires_in_seconds=response.expiration or None,
                )
            return SongUrl(song_id=self._song_identity(track))

    async def lyrics(self, song_id: str) -> Lyrics:
        async with self._client() as client:
            value = self._qqmusic_value(song_id)
            result = (await client.lyric.get_lyric(value, trans=True)).decrypt()
            synced_text = result.lyric or ""
            return Lyrics(
                song_id=song_id,
                plain_text=self._strip_lrc_timestamps(synced_text),
                synced_text=synced_text,
            )

    async def comments(self, song_id: str) -> list[Comment]:
        async with self._client() as client:
            track = await self._resolve_song(client, song_id)
            result = await client.comment.get_hot_comments(track.id, page_size=50)
            comments = [
                Comment(
                    id=item.cmid,
                    song_id=self._song_identity(track),
                    author_name=item.nick,
                    text=item.content,
                    liked_count=item.praise_num,
                    created_at=self._timestamp_to_iso(item.pub_time),
                )
                for item in result.comments
            ]
            return self._rank_hot_comments(comments)

    async def profile(self) -> UserProfile:
        credential = self._credential()
        if credential is None:
            return UserProfile(id="local", display_name="Local QQ Music user")

        async with self._client(credential) as client:
            if credential.encrypt_uin:
                homepage = await client.user.get_homepage(credential.encrypt_uin, credential=credential)
                return UserProfile(
                    id=str(credential.musicid or credential.str_musicid or "local"),
                    display_name=homepage.base_info.name or "QQ Music user",
                    avatar_url=homepage.base_info.avatar or None,
                )
            return UserProfile(
                id=str(credential.musicid or credential.str_musicid or "local"),
                display_name="QQ Music user",
            )

    async def connection_status(self) -> UserConnectionStatus:
        credential = self._credential()
        checked_at = self._now_iso()
        if credential is None:
            return UserConnectionStatus(
                state="NOT_LOGGED_IN",
                credential_stored=False,
                authenticated=False,
                message="QQ Music credential is not stored.",
                checked_at=checked_at,
            )

        user_id = str(credential.musicid or credential.str_musicid or "local")
        try:
            async with self._client(credential) as client:
                await client.user.get_vip_info(credential=credential)
                profile = await self._profile_from_credential(client, credential)
                return UserConnectionStatus(
                    state="CONNECTED",
                    credential_stored=True,
                    authenticated=True,
                    user_id=profile.id,
                    display_name=profile.display_name,
                    message="QQ Music credential is valid.",
                    checked_at=checked_at,
                )
        except Exception as error:
            return UserConnectionStatus(
                state="EXPIRED",
                credential_stored=True,
                authenticated=False,
                user_id=user_id,
                message=f"QQ Music credential validation failed: {type(error).__name__}",
                checked_at=checked_at,
            )

    async def music_gene(self) -> UserMusicGene:
        credential = self._credential()
        if credential is None:
            raise PermissionError("QQ Music credential is not stored.")
        if not credential.encrypt_uin:
            raise PermissionError("QQ Music credential does not contain encrypted UIN.")

        async with self._client(credential) as client:
            data = await self._build_music_gene(client, credential)
            return UserMusicGene(
                user_id=str(credential.musicid or credential.str_musicid or "local"),
                euin=credential.encrypt_uin,
                generated_at=self._now_iso(),
                data=data,
            )

    async def playlists(self) -> list[Playlist]:
        credential = self._credential()
        if credential is None or not credential.musicid:
            return []

        async with self._client(credential) as client:
            result = await client.user.get_created_songlist(credential.musicid, credential=credential)
            return [self._to_playlist(item) for item in result.playlists]

    async def playlist_songs(self, playlist_id: str) -> list[Song]:
        async with self._client() as client:
            result = await client.songlist.get_detail(int(self._qqmusic_value(playlist_id)), num=100, onlysong=True)
            return [self._to_song(song) for song in result.songs]

    async def _build_music_gene(self, client: Client, credential: Credential) -> dict[str, Any]:
        euin = credential.encrypt_uin
        errors: list[dict[str, str]] = []

        raw_report = await self._optional_music_gene_source(
            "qq_music_gene_report",
            errors,
            lambda: client.user.get_music_gene(euin, credential=credential),
        )
        liked_result = await self._optional_music_gene_source(
            "liked_songs",
            errors,
            lambda: client.user.get_fav_song(
                euin,
                page=1,
                num=_MUSIC_GENE_LIKED_SONG_LIMIT,
                credential=credential,
            ),
        )
        favorite_songlist_result = await self._optional_music_gene_source(
            "favorite_playlists",
            errors,
            lambda: client.user.get_fav_songlist(
                euin,
                page=1,
                num=_MUSIC_GENE_LIST_LIMIT,
                credential=credential,
            ),
        )
        favorite_album_result = await self._optional_music_gene_source(
            "favorite_albums",
            errors,
            lambda: client.user.get_fav_album(
                euin,
                page=1,
                num=_MUSIC_GENE_LIST_LIMIT,
                credential=credential,
            ),
        )
        follow_singer_result = await self._optional_music_gene_source(
            "follow_singers",
            errors,
            lambda: client.user.get_follow_singers(
                euin,
                page=1,
                num=_MUSIC_GENE_LIST_LIMIT,
                credential=credential,
            ),
        )

        created_result = None
        if credential.musicid:
            created_result = await self._optional_music_gene_source(
                "created_playlists",
                errors,
                lambda: client.user.get_created_songlist(credential.musicid, credential=credential),
            )

        liked_songs = list(getattr(liked_result, "songs", []) or [])
        created_playlists = list(getattr(created_result, "playlists", []) or [])
        favorite_playlists = list(getattr(favorite_songlist_result, "playlists", []) or [])
        favorite_albums = list(getattr(favorite_album_result, "albums", []) or [])
        follow_singers = list(getattr(follow_singer_result, "users", []) or [])

        top_artists = self._rank_top_artists(liked_songs, favorite_albums, follow_singers)
        top_albums = self._rank_top_albums(liked_songs, favorite_albums)
        source_signal_count = len(liked_songs) + len(favorite_albums) + len(follow_singers)

        raw: dict[str, Any] = {}
        if raw_report is not None:
            raw["qq_music_gene_report"] = self._jsonable(raw_report)

        return {
            "schema_version": "musio.music_gene.v1",
            "generated_from": [
                "qq_music_gene_report",
                "liked_songs",
                "created_playlists",
                "favorite_playlists",
                "favorite_albums",
                "follow_singers",
            ],
            "summary": {
                "confidence": self._music_gene_confidence(source_signal_count),
                "top_artists": top_artists,
                "top_albums": top_albums,
                "top_genre_ids": self._rank_numeric_song_attr(liked_songs, "genre"),
                "top_language_ids": self._rank_numeric_song_attr(liked_songs, "language"),
                "liked_song_count": self._response_total(liked_result, len(liked_songs)),
                "created_playlist_count": self._response_total(created_result, len(created_playlists)),
                "favorite_playlist_count": self._response_total(favorite_songlist_result, len(favorite_playlists)),
                "favorite_album_count": self._response_total(favorite_album_result, len(favorite_albums)),
                "follow_singer_count": self._response_total(follow_singer_result, len(follow_singers)),
            },
            "signals": {
                "liked_songs": [
                    self._compact_song_signal(song)
                    for song in liked_songs[:_MUSIC_GENE_SIGNAL_LIMIT]
                ],
                "created_playlists": [
                    self._compact_playlist_signal(playlist, "created")
                    for playlist in created_playlists[:_MUSIC_GENE_LIST_LIMIT]
                ],
                "favorite_playlists": [
                    self._compact_playlist_signal(playlist, "favorite")
                    for playlist in favorite_playlists[:_MUSIC_GENE_LIST_LIMIT]
                ],
                "favorite_albums": [
                    self._compact_album_signal(album)
                    for album in favorite_albums[:_MUSIC_GENE_LIST_LIMIT]
                ],
                "follow_singers": [
                    self._compact_relation_user_signal(user)
                    for user in follow_singers[:_MUSIC_GENE_LIST_LIMIT]
                ],
            },
            "raw": raw,
            "errors": errors,
        }

    async def _optional_music_gene_source(
        self,
        source: str,
        errors: list[dict[str, str]],
        factory: Callable[[], Awaitable[Any]],
    ) -> Any | None:
        try:
            return await factory()
        except Exception as error:
            errors.append(
                {
                    "source": source,
                    "type": type(error).__name__,
                    "message": str(error)[:240],
                },
            )
            return None

    def _compact_song_signal(self, song: Any) -> dict[str, Any]:
        data = self._jsonable(self._to_song(song))
        if isinstance(data, dict):
            return data
        return {"value": data}

    def _compact_playlist_signal(self, playlist: Any, source: str) -> dict[str, Any]:
        return self._compact_dict(
            {
                "id": f"{_SONG_ID_PREFIX}{getattr(playlist, 'id', '')}",
                "name": self._text(getattr(playlist, "title", "")),
                "source": source,
                "song_count": self._positive_int(getattr(playlist, "songnum", 0)),
                "play_count": self._positive_int(
                    getattr(playlist, "play_cnt", getattr(playlist, "listennum", 0)),
                ),
                "favorite_count": self._positive_int(getattr(playlist, "create_fav_cnt", 0)),
                "comment_count": self._positive_int(getattr(playlist, "comment_cnt", 0)),
                "owner_name": self._text(
                    getattr(playlist, "nick", None) or getattr(playlist, "nickname", None),
                ),
                "artwork_url": self._text(getattr(playlist, "picurl", "")),
            },
        )

    def _compact_album_signal(self, album: Any) -> dict[str, Any]:
        name = self._album_name(album)
        return self._compact_dict(
            {
                "id": f"qqmusic:album:{getattr(album, 'mid', '') or getattr(album, 'id', '')}",
                "mid": self._text(getattr(album, "mid", "")),
                "name": name,
                "artists": self._album_artist_names(album),
                "song_count": self._positive_int(getattr(album, "songnum", 0)),
                "published_at": self._text(
                    getattr(album, "time_public", None) or getattr(album, "pubtime", None),
                ),
                "artwork_url": self._cover_url(album),
            },
        )

    def _compact_relation_user_signal(self, user: Any) -> dict[str, Any]:
        return self._compact_dict(
            {
                "id": self._text(getattr(user, "mid", None) or getattr(user, "enc_uin", None)),
                "name": self._text(getattr(user, "name", "")),
                "description": self._text(getattr(user, "desc", "")),
                "avatar_url": self._text(getattr(user, "avatar_url", "")),
                "fan_count": self._positive_int(getattr(user, "fan_num", 0)),
            },
        )

    def _rank_top_artists(
        self,
        songs: list[Any],
        albums: list[Any],
        follow_singers: list[Any],
    ) -> list[dict[str, Any]]:
        scores: Counter[str] = Counter()
        evidence_counts: Counter[str] = Counter()

        for song in songs:
            for name in self._song_artist_names(song):
                scores[name] += 3
                evidence_counts[name] += 1
        for album in albums:
            for name in self._album_artist_names(album):
                scores[name] += 2
                evidence_counts[name] += 1
        for user in follow_singers:
            name = self._text(getattr(user, "name", ""))
            if name:
                scores[name] += 4
                evidence_counts[name] += 1

        return [
            {
                "name": name,
                "score": int(score),
                "evidence_count": int(evidence_counts[name]),
            }
            for name, score in scores.most_common(20)
        ]

    def _rank_top_albums(self, songs: list[Any], albums: list[Any]) -> list[dict[str, Any]]:
        scores: Counter[str] = Counter()
        metadata: dict[str, dict[str, Any]] = {}

        for album in albums:
            name = self._album_name(album)
            if not name:
                continue
            scores[name] += 3
            metadata.setdefault(
                name,
                {
                    "name": name,
                    "artists": self._album_artist_names(album),
                    "artwork_url": self._cover_url(album),
                },
            )

        for song in songs:
            album = getattr(song, "album", None)
            name = self._album_name(album)
            if not name:
                continue
            scores[name] += 1
            metadata.setdefault(
                name,
                {
                    "name": name,
                    "artists": self._album_artist_names(album),
                    "artwork_url": self._cover_url(album),
                },
            )

        return [
            {
                **metadata[name],
                "score": int(score),
            }
            for name, score in scores.most_common(20)
        ]

    def _rank_numeric_song_attr(self, songs: list[Any], attr: str) -> list[dict[str, int]]:
        counts: Counter[int] = Counter()
        for song in songs:
            value = getattr(song, attr, 0)
            if isinstance(value, int) and value > 0:
                counts[value] += 1
        return [{"id": int(value), "count": int(count)} for value, count in counts.most_common(15)]

    def _song_artist_names(self, song: Any) -> list[str]:
        return [
            name
            for artist in getattr(song, "singer", []) or []
            if (name := self._text(getattr(artist, "name", "")))
        ]

    def _album_artist_names(self, album: Any) -> list[str]:
        return [
            name
            for artist in getattr(album, "singers", []) or []
            if (name := self._text(getattr(artist, "name", "")))
        ]

    def _album_name(self, album: Any) -> str:
        if album is None:
            return ""
        return self._text(getattr(album, "name", None) or getattr(album, "title", None))

    def _cover_url(self, value: Any) -> str | None:
        if hasattr(value, "cover_url"):
            try:
                return value.cover_url() or None
            except Exception:
                return None
        return None

    def _response_total(self, response: Any | None, fallback: int) -> int:
        if response is None:
            return fallback
        value = getattr(response, "total", None)
        if isinstance(value, int) and value >= 0:
            return value
        return fallback

    def _music_gene_confidence(self, source_signal_count: int) -> str:
        if source_signal_count >= 50:
            return "high"
        if source_signal_count >= 10:
            return "medium"
        return "low"

    def _compact_dict(self, data: dict[str, Any]) -> dict[str, Any]:
        return {
            key: value
            for key, value in data.items()
            if value is not None and value != "" and value != [] and value != {}
        }

    def _positive_int(self, value: Any) -> int | None:
        if isinstance(value, bool):
            return None
        try:
            number = int(value)
        except (TypeError, ValueError):
            return None
        return number if number > 0 else None

    def _text(self, value: Any) -> str:
        if value is None:
            return ""
        return str(value).strip()

    def _rank_hot_comments(self, comments: list[Comment]) -> list[Comment]:
        return sorted(
            (comment for comment in comments if self._is_user_hot_comment(comment)),
            key=lambda comment: self._positive_int(comment.liked_count) or 0,
            reverse=True,
        )

    def _is_user_hot_comment(self, comment: Comment) -> bool:
        author = self._text(comment.author_name)
        text = self._text(comment.text)
        if not text:
            return False
        if author in {"Q音辅导员", "QQ音乐小助手", "QQ音乐"}:
            return False
        normalized = text.replace(" ", "").replace("\u3000", "")
        blocked_fragments = (
            "@元宝介绍下这首歌",
            "元宝介绍下这首歌",
            "介绍下这首歌",
            "介绍一下这首歌",
        )
        return not any(fragment in normalized for fragment in blocked_fragments)

    async def _profile_from_credential(self, client: Client, credential: Credential) -> UserProfile:
        if credential.encrypt_uin:
            homepage = await client.user.get_homepage(credential.encrypt_uin, credential=credential)
            return UserProfile(
                id=str(credential.musicid or credential.str_musicid or "local"),
                display_name=homepage.base_info.name or "QQ Music user",
                avatar_url=homepage.base_info.avatar or None,
            )
        return UserProfile(
            id=str(credential.musicid or credential.str_musicid or "local"),
            display_name="QQ Music user",
        )

    @asynccontextmanager
    async def _client(self, credential: Credential | None = None) -> AsyncIterator[Client]:
        device_path = self._device_path()
        device_path.parent.mkdir(parents=True, exist_ok=True)
        async with Client(
            credential=credential or self._credential(),
            device_path=device_path,
            enable_sign=False,
            proxy=os.environ.get("MUSIO_QQMUSIC_PROXY") or None,
        ) as client:
            yield client

    async def _resolve_song(self, client: Client, song_id: str):
        value = self._qqmusic_value(song_id)
        detail = await client.song.get_detail(value)
        return detail.track

    def _credential(self) -> Credential | None:
        path = self._credential_path()
        if not path.exists():
            return None

        data = self._read_json(path)
        extra = data.get("extraFields") if isinstance(data.get("extraFields"), dict) else {}
        merged = {**extra, **data}
        credential_data = {
            "openid": merged.get("openid", ""),
            "refresh_token": merged.get("refresh_token") or merged.get("refreshToken", ""),
            "access_token": merged.get("access_token") or merged.get("accessToken", ""),
            "expired_at": self._epoch_value(merged.get("expired_at") or merged.get("expiredAt")),
            "musicid": self._int_value(merged.get("musicid") or merged.get("str_musicid") or merged.get("strMusicid")),
            "musickey": merged.get("musickey", ""),
            "unionid": merged.get("unionid", ""),
            "str_musicid": merged.get("str_musicid") or merged.get("strMusicid") or str(merged.get("musicid", "")),
            "refresh_key": merged.get("refresh_key") or merged.get("refreshKey", ""),
            "musickeyCreateTime": self._int_value(merged.get("musickeyCreateTime")),
            "keyExpiresIn": self._int_value(merged.get("keyExpiresIn")),
            "encryptUin": merged.get("encryptUin", ""),
            "loginType": self._int_value(merged.get("loginType")),
        }
        return Credential.model_validate(credential_data)

    def _credential_path(self) -> Path:
        configured = os.environ.get("MUSIO_QQMUSIC_CREDENTIALS")
        if configured:
            return self._normalize_path(configured)
        return self._musio_home() / "credentials" / "qqmusic.json"

    def _device_path(self) -> Path:
        configured = os.environ.get("MUSIO_QQMUSIC_DEVICE_PATH")
        if configured:
            return self._normalize_path(configured)
        return self._musio_home() / "qqmusic-device.json"

    def _musio_home(self) -> Path:
        configured = os.environ.get("MUSIO_HOME")
        if configured:
            return self._normalize_path(configured)

        config_path = self._normalize_path(os.environ.get("MUSIO_CONFIG", "~/.musio/config.toml"))
        storage_home = self._toml_value(config_path, "storage", "home")
        if storage_home:
            return self._normalize_path(storage_home)
        return self._normalize_path("~/.musio")

    def _toml_value(self, path: Path, section: str, key: str) -> str | None:
        if not path.exists():
            return None

        current_section = ""
        for raw_line in path.read_text(encoding="utf-8").splitlines():
            line = raw_line.split("#", 1)[0].strip()
            if not line:
                continue
            if line.startswith("[") and line.endswith("]"):
                current_section = line[1:-1].strip()
                continue
            if current_section != section or "=" not in line:
                continue
            name, value = line.split("=", 1)
            if name.strip() != key:
                continue
            return value.strip().strip('"').strip("'")
        return None

    def _normalize_path(self, value: str) -> Path:
        value = os.path.expanduser(value)
        match = _WINDOWS_DRIVE_PATTERN.match(value)
        if os.name != "nt" and match:
            drive = match.group(1).lower()
            rest = match.group(2).replace("\\", "/")
            return Path(f"/mnt/{drive}/{rest}")
        return Path(value)

    def _read_json(self, path: Path) -> dict[str, Any]:
        with path.open("r", encoding="utf-8") as handle:
            payload = json.load(handle)
        if not isinstance(payload, dict):
            raise ValueError(f"Expected JSON object in {path}")
        return payload

    def _qqmusic_value(self, value: str) -> str | int:
        raw = value.removeprefix(_SONG_ID_PREFIX)
        return int(raw) if raw.isdigit() else raw

    def _to_song(self, song: Any) -> Song:
        return Song(
            id=self._song_identity(song),
            title=song.title or song.name,
            artists=[artist.name for artist in song.singer if artist.name],
            album=song.album.name or song.album.title or None,
            duration_seconds=song.interval or None,
            artwork_url=song.cover_url() or None,
        )

    def _to_song_detail(self, song: Any) -> SongDetail:
        base = self._to_song(song)
        return SongDetail(
            id=base.id,
            title=base.title,
            artists=base.artists,
            album=base.album,
            duration_seconds=base.duration_seconds,
            artwork_url=base.artwork_url,
            source_url=f"https://y.qq.com/n/ryqq/songDetail/{song.mid}" if song.mid else None,
        )

    def _to_playlist(self, playlist: Any) -> Playlist:
        return Playlist(
            id=f"{_SONG_ID_PREFIX}{playlist.id}",
            name=playlist.title,
            song_count=playlist.songnum or None,
            artwork_url=playlist.picurl or None,
        )

    def _song_identity(self, song: Any) -> str:
        return f"{_SONG_ID_PREFIX}{song.mid or song.id}"

    def _strip_lrc_timestamps(self, text: str) -> str:
        lines = []
        for line in text.splitlines():
            cleaned = _LRC_TIMESTAMP_PATTERN.sub("", line).strip()
            if cleaned:
                lines.append(cleaned)
        return "\n".join(lines)

    def _timestamp_to_iso(self, value: int) -> str | None:
        if not value:
            return None
        return datetime.fromtimestamp(value, UTC).isoformat().replace("+00:00", "Z")

    def _now_iso(self) -> str:
        return datetime.now(UTC).isoformat().replace("+00:00", "Z")

    def _jsonable(self, value: Any) -> Any:
        if hasattr(value, "model_dump"):
            return value.model_dump(mode="json", by_alias=True)
        if isinstance(value, dict):
            return {str(key): self._jsonable(item) for key, item in value.items()}
        if isinstance(value, list):
            return [self._jsonable(item) for item in value]
        if isinstance(value, tuple):
            return [self._jsonable(item) for item in value]
        if isinstance(value, datetime):
            return value.isoformat()
        return value

    def _int_value(self, value: Any) -> int:
        if value is None or value == "":
            return 0
        return int(value)

    def _epoch_value(self, value: Any) -> int:
        if value is None or value == "":
            return 0
        if isinstance(value, int):
            return value
        if isinstance(value, float):
            return int(value)
        try:
            return int(value)
        except (TypeError, ValueError):
            return int(datetime.fromisoformat(str(value).replace("Z", "+00:00")).timestamp())
