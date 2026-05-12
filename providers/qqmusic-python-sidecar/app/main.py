from __future__ import annotations

import os
from typing import Any

import uvicorn
from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import JSONResponse
from qqmusic_api.core.exceptions import CredentialError, LoginExpiredError, NotLoginError, RatelimitedError

from .qqmusic_auth import QQMusicAuthService
from .qqmusic_client import QQMusicClient
from .schemas import (
    Comment,
    HealthResponse,
    LoginStartResult,
    LoginStatus,
    Lyrics,
    Playlist,
    Song,
    SongDetail,
    SongUrl,
    SourceCapability,
    SourceManifest,
    UserConnectionStatus,
    UserMusicGene,
    UserProfile,
)

app = FastAPI(title="Musio QQ Music Sidecar", version="0.1.0")
client = QQMusicClient()
auth_service = QQMusicAuthService()

_SOURCE_ID = "qqmusic"
_CAPABILITIES = [
    SourceCapability(
        name="search_songs",
        description="搜索歌曲、歌手、专辑或候选音乐",
        input_schema={"keyword": "string", "limit": "number", "excludedTitles": "string[]"},
        required=["keyword", "limit"],
        result_type="songs",
    ),
    SourceCapability(
        name="get_song_detail",
        description="读取歌曲详情",
        input_schema={"songId": "string"},
        required=["songId"],
        result_type="song_detail",
    ),
    SourceCapability(
        name="get_song_url",
        description="读取歌曲播放地址",
        input_schema={"songId": "string"},
        required=["songId"],
        result_type="song_url",
    ),
    SourceCapability(
        name="get_lyrics",
        description="读取歌曲歌词",
        input_schema={"songId": "string"},
        required=["songId"],
        result_type="lyrics",
    ),
    SourceCapability(
        name="get_hot_comments",
        description="读取歌曲热门评论",
        input_schema={"songId": "string", "limit": "number"},
        required=["songId"],
        result_type="comments",
    ),
    SourceCapability(
        name="get_user_playlists",
        description="读取当前用户歌单",
        input_schema={"limit": "number"},
        required=[],
        result_type="playlists",
    ),
    SourceCapability(
        name="get_playlist_songs",
        description="读取歌单歌曲",
        input_schema={"playlistId": "string", "limit": "number"},
        required=["playlistId"],
        result_type="songs",
    ),
]


@app.exception_handler(LoginExpiredError)
async def login_expired_handler(_request, _error: LoginExpiredError) -> JSONResponse:
    return JSONResponse(status_code=401, content={"detail": "QQ 音乐登录已过期，请重新登录。"})


@app.exception_handler(NotLoginError)
async def not_login_handler(_request, _error: NotLoginError) -> JSONResponse:
    return JSONResponse(status_code=401, content={"detail": "QQ 音乐尚未登录，请先登录。"})


@app.exception_handler(CredentialError)
async def credential_error_handler(_request, _error: CredentialError) -> JSONResponse:
    return JSONResponse(status_code=401, content={"detail": "QQ 音乐登录状态不可用，请重新登录。"})


@app.exception_handler(RatelimitedError)
async def ratelimited_handler(_request, _error: RatelimitedError) -> JSONResponse:
    return JSONResponse(status_code=429, content={"detail": "QQ 音乐触发风控，需要登录或完成安全验证后再试。"})


@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse()


@app.get("/manifest", response_model=SourceManifest)
async def manifest() -> SourceManifest:
    return SourceManifest(capabilities=_CAPABILITIES)


@app.post("/tools/{tool_name}")
async def execute_tool(tool_name: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
    arguments = _tool_arguments(payload)
    match tool_name:
        case "search_songs":
            keyword = _required_text(arguments, "keyword")
            limit = _int_arg(arguments, "limit", 5, 1, 50)
            songs = await client.search(keyword, limit)
            return _tool_result(tool_name, "songs", count=len(songs), songs=songs)
        case "get_song_detail":
            song_detail = await client.song(_required_text(arguments, "songId"))
            return _tool_result(tool_name, "song_detail", song=song_detail)
        case "get_song_url":
            song_url = await client.song_url(_required_text(arguments, "songId"))
            return _tool_result(tool_name, "song_url", songUrl=song_url)
        case "get_lyrics":
            song_lyrics = await client.lyrics(_required_text(arguments, "songId"))
            return _tool_result(tool_name, "lyrics", lyrics=song_lyrics)
        case "get_hot_comments":
            limit = _int_arg(arguments, "limit", 10, 1, 30)
            comments = (await client.comments(_required_text(arguments, "songId")))[:limit]
            return _tool_result(tool_name, "comments", count=len(comments), comments=comments)
        case "get_user_playlists":
            limit = _int_arg(arguments, "limit", 20, 1, 50)
            playlists = (await client.playlists())[:limit]
            return _tool_result(tool_name, "playlists", count=len(playlists), playlists=playlists)
        case "get_playlist_songs":
            limit = _int_arg(arguments, "limit", 20, 1, 50)
            songs = (await client.playlist_songs(_required_text(arguments, "playlistId")))[:limit]
            return _tool_result(tool_name, "songs", count=len(songs), songs=songs)
        case _:
            raise HTTPException(status_code=404, detail=f"Unknown QQ Music tool: {tool_name}")


@app.post("/auth/start", response_model=LoginStartResult)
async def auth_start() -> LoginStartResult:
    return auth_service.start_login()


@app.get("/auth/{session_id}/status", response_model=LoginStatus)
async def auth_status(session_id: str) -> LoginStatus:
    return auth_service.check_login(session_id)


@app.post("/auth/logout", response_model=LoginStatus)
async def auth_logout() -> LoginStatus:
    return auth_service.logout()


@app.get("/search", response_model=list[Song])
async def search(keyword: str, limit: int = Query(default=10, ge=1, le=50)) -> list[Song]:
    return await client.search(keyword, limit)


@app.get("/songs/{song_id}", response_model=SongDetail)
async def song(song_id: str) -> SongDetail:
    return await client.song(song_id)


@app.get("/songs/{song_id}/url", response_model=SongUrl)
async def song_url(song_id: str) -> SongUrl:
    return await client.song_url(song_id)


@app.get("/songs/{song_id}/lyrics", response_model=Lyrics)
async def lyrics(song_id: str) -> Lyrics:
    return await client.lyrics(song_id)


@app.get("/songs/{song_id}/comments", response_model=list[Comment])
async def comments(song_id: str) -> list[Comment]:
    return await client.comments(song_id)


@app.get("/users/me", response_model=UserProfile)
async def profile() -> UserProfile:
    return await client.profile()


@app.get("/users/me/status", response_model=UserConnectionStatus)
async def profile_status() -> UserConnectionStatus:
    return await client.connection_status()


@app.get("/users/me/music-gene", response_model=UserMusicGene)
async def music_gene() -> UserMusicGene:
    try:
        return await client.music_gene()
    except PermissionError as error:
        raise HTTPException(status_code=401, detail=str(error)) from error


@app.get("/users/me/playlists", response_model=list[Playlist])
async def playlists() -> list[Playlist]:
    return await client.playlists()


@app.get("/playlists/{playlist_id}/songs", response_model=list[Song])
async def playlist_songs(playlist_id: str) -> list[Song]:
    return await client.playlist_songs(playlist_id)


def _tool_arguments(payload: dict[str, Any] | None) -> dict[str, Any]:
    if not payload:
        return {}
    arguments = payload.get("arguments")
    if isinstance(arguments, dict):
        return arguments
    return payload


def _required_text(arguments: dict[str, Any], key: str) -> str:
    value = arguments.get(key)
    if not isinstance(value, str) or not value.strip():
        raise HTTPException(status_code=400, detail=f"Missing required argument: {key}")
    return value.strip()


def _int_arg(arguments: dict[str, Any], key: str, default: int, min_value: int, max_value: int) -> int:
    value = arguments.get(key, default)
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = default
    return max(min_value, min(max_value, parsed))


def _tool_result(tool_name: str, result_type: str, **values: Any) -> dict[str, Any]:
    return {
        "success": True,
        "sourceId": _SOURCE_ID,
        "toolName": tool_name,
        "resultType": result_type,
        **values,
    }


if __name__ == "__main__":
    host = os.environ.get("MUSIO_QQMUSIC_HOST", "127.0.0.1")
    port = int(os.environ.get("MUSIO_QQMUSIC_PORT", "18767"))
    uvicorn.run("app.main:app", host=host, port=port, reload=False)
