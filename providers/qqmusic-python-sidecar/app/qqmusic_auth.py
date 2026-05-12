from __future__ import annotations

import base64
import json
import logging
import os
import re
import time
import uuid
from dataclasses import dataclass, field
from http.cookiejar import CookieJar
from pathlib import Path
from typing import Any
from urllib import error, parse, request

from .schemas import LoginStartResult, LoginStatus

logger = logging.getLogger(__name__)

APP_ID = "716027609"
QQ_MUSIC_APP_ID = "100497308"
DAID = "383"
DEFAULT_QIMEI36 = "6c9d3cd110abca9b16311cee10001e717614"
USER_AGENT = "Mozilla/5.0 (Windows NT 11.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36 Edg/116.0.1938.54"
SESSION_TTL_SECONDS = 10 * 60

PTUI_CALLBACK_PATTERN = re.compile(r"ptuiCB\((.*?)\)")
QRSIG_COOKIE_PATTERN = re.compile(r"qrsig=([^;]+)")
WINDOWS_DRIVE_PATTERN = re.compile(r"^([A-Za-z]):[\\/](.*)$")


class QQMusicAuthService:
    def __init__(self) -> None:
        self._sessions: dict[str, QQMusicLoginSession] = {}

    def start_login(self) -> LoginStartResult:
        self._cleanup_sessions()
        session = QQMusicLoginSession.create()
        qr_code_data_url = self._generate_qr_code(session)
        self._sessions[session.session_id] = session
        return LoginStartResult(
            sessionId=session.session_id,
            state="NOT_SCANNED",
            qrCodeDataUrl=qr_code_data_url,
            message="Scan the QR code with QQ Music.",
        )

    def check_login(self, session_id: str) -> LoginStatus:
        self._cleanup_sessions()
        session = self._sessions.get(session_id)
        if session is None:
            return self._status(session_id, "EXPIRED", "QR login session expired.")

        try:
            status = self._check_login_status(session)
        except Exception as exc:
            logger.exception("QQ Music QR login failed while checking session %s", session_id)
            status = self._status(session_id, "FAILED", f"Failed to complete QQ Music login: {exc}")
        if status.state in {"DONE", "EXPIRED", "FAILED"}:
            self._sessions.pop(session_id, None)
        return status

    def logout(self) -> LoginStatus:
        self._sessions.clear()
        try:
            self._credential_path().unlink(missing_ok=True)
        except OSError as exc:
            return self._status("local", "FAILED", f"Failed to delete QQ Music credential: {exc}")
        return self._status("local", "LOGGED_OUT", "Logged out.")

    def _generate_qr_code(self, session: "QQMusicLoginSession") -> str:
        url = "https://ssl.ptlogin2.qq.com/ptqrshow?" + parse.urlencode({
            "appid": APP_ID,
            "e": "2",
            "l": "M",
            "s": "3",
            "d": "72",
            "v": "4",
            "t": str(time.time()),
            "daid": DAID,
            "pt_3rd_aid": QQ_MUSIC_APP_ID,
        })
        response = session.open(url, headers={"Referer": "https://xui.ptlogin2.qq.com/"})
        qrsig = self._extract_qrsig(response.headers.get_all("Set-Cookie", []))
        if not qrsig:
            raise RuntimeError("QQ Music QR response did not include qrsig.")
        session.qrsig = qrsig
        data = response.read()
        return "data:image/png;base64," + base64.b64encode(data).decode("ascii")

    def _check_login_status(self, session: "QQMusicLoginSession") -> LoginStatus:
        if not session.qrsig:
            return self._status(session.session_id, "FAILED", "QR login session is missing qrsig.")

        url = "https://ssl.ptlogin2.qq.com/ptqrlogin?" + parse.urlencode({
            "u1": "https://graph.qq.com/oauth2.0/login_jump",
            "ptqrtoken": str(_hash33(session.qrsig)),
            "ptredirect": "0",
            "h": "1",
            "t": "1",
            "g": "1",
            "from_ui": "1",
            "ptlang": "2052",
            "action": "0-0-" + str(int(time.time() * 1000)),
            "js_ver": "20102616",
            "js_type": "1",
            "pt_uistyle": "40",
            "aid": APP_ID,
            "daid": DAID,
            "pt_3rd_aid": QQ_MUSIC_APP_ID,
            "has_onekey": "1",
        })
        response = session.open(url, headers={
            "Referer": "https://xui.ptlogin2.qq.com/",
            "Cookie": "qrsig=" + parse.quote(session.qrsig),
        })
        response_text = response.read().decode("utf-8", errors="replace")
        match = PTUI_CALLBACK_PATTERN.search(response_text)
        if not match:
            return self._status(session.session_id, "FAILED", "QQ login status response was not recognized.")

        data = [item.strip().strip("'") for item in match.group(1).split(",")]
        if len(data) < 3:
            return self._status(session.session_id, "FAILED", "QQ login status response was incomplete.")
        state = _state_from_code(_int_value(data[0]))
        if state != "DONE":
            return self._status(session.session_id, state, _message_for_state(state))

        login_url = data[2]
        sigx = _extract_param(login_url, "ptsigx")
        uin = _extract_param(login_url, "uin")
        credential = self._authorize_qr_login(session, uin, sigx)
        self._write_credential(credential)
        return self._status(session.session_id, "DONE", "QQ Music login completed.")

    def _authorize_qr_login(self, session: "QQMusicLoginSession", uin: str, sigx: str) -> dict[str, Any]:
        normalized_uin = _normalize_uin(uin)
        p_skey = self._check_sig(session, uin, sigx)
        code = self._authorize(session, p_skey)
        return self._exchange_music_credential(session, normalized_uin, code)

    def _check_sig(self, session: "QQMusicLoginSession", uin: str, sigx: str) -> str:
        url = "https://ssl.ptlogin2.graph.qq.com/check_sig?" + parse.urlencode({
            "uin": uin,
            "pttype": "1",
            "service": "ptqrlogin",
            "nodirect": "0",
            "ptsigx": sigx,
            "s_url": "https://graph.qq.com/oauth2.0/login_jump",
            "ptlang": "2052",
            "ptredirect": "100",
            "aid": APP_ID,
            "daid": DAID,
            "j_later": "0",
            "low_login_hour": "0",
            "regmaster": "0",
            "pt_login_type": "3",
            "pt_aid": "0",
            "pt_aaid": "16",
            "pt_light": "0",
            "pt_3rd_aid": QQ_MUSIC_APP_ID,
        })
        response = session.open_no_redirect(url, headers={"Referer": "https://xui.ptlogin2.qq.com/"})
        for header in response.headers.get_all("Set-Cookie", []):
            if header.startswith("p_skey="):
                return header.split(";", 1)[0].split("=", 1)[1]
        for cookie in session.cookie_jar:
            if cookie.name == "p_skey" and cookie.value:
                return cookie.value
        raise RuntimeError("QQ check_sig response did not include p_skey.")

    def _authorize(self, session: "QQMusicLoginSession", p_skey: str) -> str:
        url = "https://graph.qq.com/oauth2.0/authorize"
        form = parse.urlencode({
            "response_type": "code",
            "client_id": QQ_MUSIC_APP_ID,
            "redirect_uri": "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https%3A%252F%252Fy.qq.com%252F",
            "scope": "get_user_info,get_app_friends",
            "state": "state",
            "switch": "",
            "from_ptlogin": "1",
            "src": "1",
            "update_auth": "1",
            "openapi": "1010_1030",
            "g_tk": str(_hash33(p_skey, 5381)),
            "auth_time": str(int(time.time()) * 1000),
            "ui": str(uuid.uuid4()),
        }).encode("utf-8")
        headers = {
            "Host": "graph.qq.com",
            "Accept": "*/*",
            "Accept-Encoding": "gzip, deflate",
            "Connection": "keep-alive",
            "User-Agent": USER_AGENT,
            "Referer": "https://y.qq.com/",
            "Content-Type": "application/x-www-form-urlencoded",
        }
        location = session.open_no_redirect(url, data=form, headers=headers).headers.get("Location", "")
        match = re.search(r"code=([^&]+)", location)
        if not match:
            raise RuntimeError("QQ authorize response did not include code.")
        return match.group(1)

    def _exchange_music_credential(self, session: "QQMusicLoginSession", uin: str, code: str) -> dict[str, Any]:
        url = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        payload = {
            "comm": {
                "cv": "13020508",
                "v": "13020508",
                "QIMEI36": DEFAULT_QIMEI36,
                "ct": "11",
                "tmeAppID": "qqmusic",
                "format": "json",
                "inCharset": "utf-8",
                "outCharset": "utf-8",
                "uid": uin,
                "tmeLoginType": "2",
            },
            "music.login.LoginServer.Login": {
                "module": "music.login.LoginServer",
                "method": "Login",
                "param": {"code": code},
            },
        }
        response = session.open(url, data=json.dumps(payload).encode("utf-8"), headers={
            "Host": "u.y.qq.com",
            "Accept": "*/*",
            "Connection": "keep-alive",
            "User-Agent": USER_AGENT,
            "Referer": "y.qq.com",
            "Content-Type": "application/json",
        })
        response_map = json.loads(response.read().decode("utf-8"))
        module_response = response_map.get("music.login.LoginServer.Login") or {}
        if _int_value(module_response.get("code")) != 0:
            raise RuntimeError(f"QQ Music login failed with code {_int_value(module_response.get('code'))}.")
        data = module_response.get("data") or module_response
        if not isinstance(data, dict):
            raise RuntimeError("QQ Music login response did not include credential data.")
        return self._credential_from_map(data)

    def _credential_from_map(self, source: dict[str, Any]) -> dict[str, Any]:
        extra = dict(source)
        musickey = str(extra.pop("musickey", "") or "")
        login_type = _int_value(extra.pop("loginType", 0))
        if login_type == 0:
            login_type = 1 if musickey.startswith("W_X") else 2
        credential = {
            "openid": str(extra.pop("openid", "") or ""),
            "refreshToken": str(extra.pop("refresh_token", "") or ""),
            "accessToken": str(extra.pop("access_token", "") or ""),
            "expiredAt": _epoch_value(extra.pop("expired_at", 0)),
            "musicid": str(extra.pop("musicid", "") or ""),
            "musickey": musickey,
            "unionid": str(extra.pop("unionid", "") or ""),
            "strMusicid": str(extra.pop("str_musicid", "") or ""),
            "refreshKey": str(extra.pop("refresh_key", "") or ""),
            "encryptUin": str(extra.pop("encryptUin", "") or ""),
            "loginType": login_type,
            "extraFields": extra,
        }
        return credential

    def _write_credential(self, credential: dict[str, Any]) -> None:
        path = self._credential_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(credential, ensure_ascii=False, indent=2), encoding="utf-8")

    def _credential_path(self) -> Path:
        configured = os.environ.get("MUSIO_QQMUSIC_CREDENTIALS")
        if configured:
            return _normalize_path(configured)
        return _musio_home() / "credentials" / "qqmusic.json"

    def _status(self, session_id: str, state: str, message: str) -> LoginStatus:
        return LoginStatus(
            sessionId=session_id,
            state=state,
            credentialStored=self._credential_path().is_file(),
            message=message,
        )

    def _cleanup_sessions(self) -> None:
        now = time.time()
        expired = [
            session_id
            for session_id, session in self._sessions.items()
            if now - session.created_at > SESSION_TTL_SECONDS
        ]
        for session_id in expired:
            self._sessions.pop(session_id, None)

    def _extract_qrsig(self, set_cookie_headers: list[str]) -> str:
        for header in set_cookie_headers:
            match = QRSIG_COOKIE_PATTERN.search(header)
            if match:
                return match.group(1)
        return ""


@dataclass
class QQMusicLoginSession:
    session_id: str
    cookie_jar: CookieJar
    opener: request.OpenerDirector
    no_redirect_opener: request.OpenerDirector
    created_at: float = field(default_factory=time.time)
    qrsig: str = ""

    @classmethod
    def create(cls) -> "QQMusicLoginSession":
        cookie_jar = CookieJar()
        opener = request.build_opener(request.HTTPCookieProcessor(cookie_jar))
        no_redirect_opener = request.build_opener(
            request.HTTPCookieProcessor(cookie_jar),
            NoRedirectHandler(),
        )
        return cls(str(uuid.uuid4()), cookie_jar, opener, no_redirect_opener)

    def open(self, url: str, data: bytes | None = None, headers: dict[str, str] | None = None):
        req = request.Request(url, data=data, headers=headers or {}, method="POST" if data is not None else "GET")
        return self.opener.open(req, timeout=30)

    def open_no_redirect(self, url: str, data: bytes | None = None, headers: dict[str, str] | None = None):
        req = request.Request(url, data=data, headers=headers or {}, method="POST" if data is not None else "GET")
        try:
            return self.no_redirect_opener.open(req, timeout=30)
        except error.HTTPError as exc:
            if 300 <= exc.code < 400:
                return exc
            raise


class NoRedirectHandler(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # type: ignore[no-untyped-def]
        return None


def _musio_home() -> Path:
    configured = os.environ.get("MUSIO_HOME")
    if configured:
        return _normalize_path(configured)
    config_path = _normalize_path(os.environ.get("MUSIO_CONFIG", "~/.musio/config.toml"))
    storage_home = _toml_value(config_path, "storage", "home")
    if storage_home:
        return _normalize_path(storage_home)
    return _normalize_path("~/.musio")


def _toml_value(path: Path, section: str, key: str) -> str | None:
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
        if name.strip() == key:
            return value.strip().strip('"').strip("'")
    return None


def _normalize_path(value: str) -> Path:
    expanded = os.path.expanduser(value)
    match = WINDOWS_DRIVE_PATTERN.match(expanded)
    if os.name != "nt" and match:
        drive = match.group(1).lower()
        rest = match.group(2).replace("\\", "/")
        return Path(f"/mnt/{drive}/{rest}")
    return Path(expanded)


def _hash33(value: str, seed: int = 0) -> int:
    result = seed
    for char in value:
        if seed == 0:
            result += (result << 5) + ord(char)
        else:
            result = (result << 5) + result + ord(char)
    return result & 0x7FFFFFFF


def _extract_param(url: str, key: str) -> str:
    parsed = parse.urlparse(url)
    values = parse.parse_qs(parsed.query)
    return values.get(key, [""])[0]


def _normalize_uin(uin: str) -> str:
    return uin[1:] if uin.startswith(("o", "O")) else uin


def _int_value(value: Any) -> int:
    if value is None or value == "":
        return 0
    return int(value)


def _epoch_value(value: Any) -> int:
    if value is None or value == "":
        return 0
    if isinstance(value, (int, float)):
        return int(value)
    return int(str(value))


def _state_from_code(code: int) -> str:
    return {
        0: "DONE",
        65: "EXPIRED",
        66: "NOT_SCANNED",
        67: "SCANNED",
    }.get(code, "FAILED")


def _message_for_state(state: str) -> str:
    return {
        "NOT_SCANNED": "Waiting for scan.",
        "SCANNED": "QR code scanned. Confirm login on your device.",
        "EXPIRED": "QR code expired.",
        "FAILED": "QR login failed.",
    }.get(state, state)
