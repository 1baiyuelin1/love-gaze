"""love gaze's two-device API. Run with Gunicorn behind the app's own HTTPS service."""
from __future__ import annotations

import base64
from contextlib import contextmanager
import hashlib
import hmac
import json
import math
import os
from pathlib import Path
import sqlite3
import time
import uuid

ROLES = ("white", "champion")
MAX_BODY = 524288
KINDS = {"OPEN", "USAGE", "LEAVE", "LOCK", "LOCATION", "GAP", "PRESENCE"}


class Rejected(Exception):
    def __init__(self, code, message):
        self.code, self.message = code, message


def require(value, message):
    if not value:
        raise Rejected("INVALID_REQUEST", message)


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def is_uuid(value):
    try:
        return isinstance(value, str) and str(uuid.UUID(value)) == value
    except ValueError:
        return False


def validate_moment(moment, role):
    require(isinstance(moment, dict), "记录格式不正确")
    require(moment.get("v") == 1 and is_uuid(moment.get("id")), "记录编号不正确")
    require(moment.get("owner") == role and isinstance(moment.get("kind"), str) and moment["kind"] in KINDS, "记录身份或类型不正确")
    for key in ("at", "duration"):
        require(type(moment.get(key)) is int and 0 <= moment[key] <= 32_503_680_000_000, "记录时间不正确")
    require(type(moment.get("battery")) is int and -1 <= moment["battery"] <= 100, "电量不正确")
    for key, limit in (("package", 255), ("app", 120), ("place", 160), ("status", 120)):
        require(isinstance(moment.get(key), str) and len(moment[key]) <= limit, "记录文字不正确")
    if moment["kind"] == "LOCATION" or any(key in moment for key in ("lat", "lon", "accuracy")):
        for key, lo, hi in (("lat", -90, 90), ("lon", -180, 180), ("accuracy", 0, 100_000)):
            value = moment.get(key)
            require(type(value) in (int, float) and math.isfinite(value) and lo <= value <= hi, "位置不正确")
    require(len(canonical(moment).encode()) <= 4096, "单条记录过大")


def validate_avatar(avatar):
    require(isinstance(avatar, dict) and is_uuid(avatar.get("revision")), "头像版本不正确")
    data = avatar.get("data")
    require(isinstance(data, str) and len(data) <= 43692, "头像过大")
    if data == "":
        return
    try:
        raw = base64.b64decode(data, validate=True)
    except (ValueError, base64.binascii.Error):
        raise Rejected("INVALID_REQUEST", "头像格式不正确") from None
    require(4 <= len(raw) <= 32768 and raw.startswith(b"\xff\xd8") and raw.endswith(b"\xff\xd9"), "头像必须为小尺寸 JPEG")


class SyncStore:
    def __init__(self, database, token_hashes):
        self.database = str(database)
        self.hashes = token_hashes
        if set(token_hashes) != set(ROLES):
            raise ValueError("Both fixed device credentials are required")
        with self.connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS devices(role TEXT PRIMARY KEY, installation TEXT NOT NULL,
                    presence TEXT, avatar TEXT, last_sync INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS events(seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    id TEXT NOT NULL UNIQUE, owner TEXT NOT NULL, payload TEXT NOT NULL);
                CREATE INDEX IF NOT EXISTS by_owner_seq ON events(owner, seq);
            """)

    @contextmanager
    def connect(self):
        connection = sqlite3.connect(self.database, timeout=5)
        try:
            with connection:
                yield connection
        finally:
            connection.close()

    def handle(self, request):
        require(isinstance(request, dict), "请求格式不正确")
        role = request.get("role")
        require(role in ROLES, "身份不正确")
        token = request.get("token")
        if not isinstance(token, str) or len(token) != 64 or not hmac.compare_digest(
            hashlib.sha256(token.encode()).hexdigest(), self.hashes[role]
        ):
            raise Rejected("CONNECTION_REJECTED", "连接码不匹配")
        installation = request.get("installation")
        require(is_uuid(installation), "设备标识不正确")
        action = request.get("action")
        require(action in ("claim", "sync"), "请求动作不正确")
        events = request.get("events", [])
        cursor = request.get("headSeen", 0)
        if action == "sync":
            require(isinstance(events, list) and len(events) <= 101, "上传批次过大")
            for event in events:
                validate_moment(event, role)
            require(sum(event["kind"] == "PRESENCE" for event in events) <= 1 and
                    sum(event["kind"] != "PRESENCE" for event in events) <= 100, "上传批次不正确")
            require(len({event["id"] for event in events}) == len(events), "记录编号重复")
            require(type(cursor) is int and 0 <= cursor <= 9_007_199_254_740_991, "接收进度不正确")
            require(request.get("archiveCursor", 0) == 0, "请使用新服务器的同步进度")
            if "avatar" in request:
                validate_avatar(request["avatar"])
        partner = "champion" if role == "white" else "white"
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            device = db.execute("SELECT installation,presence FROM devices WHERE role=?", (role,)).fetchone()
            if device and not hmac.compare_digest(device[0], installation):
                raise Rejected("CONNECTION_REJECTED", "这个身份已连接另一台安装设备，请勿卸载旧版")
            if not device:
                db.execute("INSERT INTO devices(role,installation) VALUES(?,?)", (role, installation))
            if action == "claim":
                return {"ok": True, "role": role}
            latest = db.execute("SELECT COALESCE(MAX(seq),0) FROM events WHERE owner=?", (partner,)).fetchone()[0]
            if cursor > latest:
                raise Rejected("CLOUD_RESET", "服务器接收进度不匹配，请检查数据库恢复情况")
            presence = json.loads(device[1]) if device and device[1] else None
            presence_up_to = None
            for event in events:
                payload = canonical(event)
                if event["kind"] == "PRESENCE":
                    presence_up_to = event["at"]
                    if presence is None or event["at"] >= presence["at"]:
                        db.execute("UPDATE devices SET presence=? WHERE role=?", (payload, role))
                else:
                    existing = db.execute("SELECT owner,payload FROM events WHERE id=?", (event["id"],)).fetchone()
                    if existing and existing != (role, payload):
                        raise Rejected("CONFLICT", "相同记录编号对应了不同内容")
                    db.execute("INSERT OR IGNORE INTO events(id,owner,payload) VALUES(?,?,?)", (event["id"], role, payload))
            if "avatar" in request:
                db.execute("UPDATE devices SET avatar=? WHERE role=?", (canonical(request["avatar"]), role))
            db.execute("UPDATE devices SET last_sync=? WHERE role=?", (int(time.time() * 1000), role))
            rows = db.execute("SELECT seq,payload FROM events WHERE owner=? AND seq>? ORDER BY seq LIMIT 500", (partner, cursor)).fetchall()
            head = rows[-1][0] if rows else cursor
            peer = db.execute("SELECT presence,avatar FROM devices WHERE role=?", (partner,)).fetchone()
            delta = {"events": [json.loads(row[1]) for row in rows], "presence": json.loads(peer[0]) if peer and peer[0] else None,
                     "headSeen": head, "archiveCursor": 0, "archiveCount": 0}
            # Preserve the phone's cached partner photo until that partner first uploads to this server.
            if peer and peer[1]:
                avatar = json.loads(peer[1])
                if avatar["revision"] != request.get("partnerAvatarRevision"):
                    delta["avatar"] = avatar
            return {"ok": True, "acknowledged": [event["id"] for event in events],
                    "presenceUpTo": presence_up_to, "partner": delta,
                    "avatarAcknowledged": request.get("avatar", {}).get("revision"),
                    "nextPollMs": 2000 if head < latest else 15000, "more": head < latest}


def make_app(store):
    def app(environ, start_response):
        status = "200 OK"
        try:
            path = environ.get("PATH_INFO", "")
            if path == "/health" and environ["REQUEST_METHOD"] == "GET":
                with store.connect() as db:
                    db.execute("SELECT 1").fetchone()
                result = {"ok": True, "service": "love-gaze", "version": "0.6.0"}
            elif path == "/v1/sync" and environ["REQUEST_METHOD"] == "POST":
                require(environ.get("CONTENT_TYPE", "").split(";")[0] == "application/json", "请发送 JSON")
                length = int(environ.get("CONTENT_LENGTH") or 0)
                require(0 < length <= MAX_BODY, "请求大小不正确")
                data = environ["wsgi.input"].read(length)
                require(len(data) == length, "请求未完整接收")
                result = store.handle(json.loads(data))
            else:
                status, result = "404 Not Found", {"ok": False, "code": "NOT_FOUND"}
        except Rejected as error:
            result = {"ok": False, "code": error.code, "message": error.message}
        except (ValueError, UnicodeError):
            status, result = "400 Bad Request", {"ok": False, "code": "INVALID_REQUEST", "message": "请求格式不正确"}
        except sqlite3.Error:
            status, result = "503 Service Unavailable", {"ok": False, "code": "TEMPORARILY_UNAVAILABLE", "message": "存储暂不可用，请稍后重试"}
        payload = canonical(result).encode()
        start_response(status, [("Content-Type", "application/json; charset=utf-8"),
                                ("Content-Length", str(len(payload))), ("Cache-Control", "no-store")])
        return [payload]
    return app


def production_app():
    config = json.loads(Path(os.environ.get("LOVE_GAZE_CONFIG", "/etc/love-gaze/devices.json")).read_text())
    return make_app(SyncStore(os.environ.get("LOVE_GAZE_DATABASE", "/var/lib/love-gaze/events.sqlite"), config["token_hashes"]))
