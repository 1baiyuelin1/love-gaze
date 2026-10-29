import base64
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import uuid

from sync_app import Rejected, SyncStore, make_app


class SyncTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.tokens = {role: char * 64 for role, char in (("white", "a"), ("champion", "b"))}
        self.hashes = {role: hashlib.sha256(token.encode()).hexdigest() for role, token in self.tokens.items()}
        self.ids = {role: str(uuid.uuid4()) for role in self.tokens}
        self.path = Path(self.temp.name) / "events.sqlite"
        self.store = SyncStore(self.path, self.hashes)

    def tearDown(self):
        self.temp.cleanup()

    def request(self, role, events=None, **extra):
        return {"action": "sync", "role": role, "token": self.tokens[role], "installation": self.ids[role],
                "events": events or [], "headSeen": 0, "archiveCursor": 0, **extra}

    def event(self, role, **extra):
        return {"v": 1, "id": str(uuid.uuid4()), "owner": role, "at": 1000, "kind": "OPEN",
                "package": "com.tencent.mm", "app": "微信", "duration": 0, "place": "", "battery": 90, "status": "", **extra}

    def test_both_directions_and_duplicate_retry(self):
        a, b = self.event("white"), self.event("champion")
        self.store.handle(self.request("white", [a]))
        got = self.store.handle(self.request("champion", [b]))
        self.assertEqual([a], got["partner"]["events"])
        self.assertEqual([b], self.store.handle(self.request("white", [a]))["partner"]["events"])
        self.assertEqual([], self.store.handle(self.request("champion", headSeen=got["partner"]["headSeen"]))["partner"]["events"])
        with self.store.connect() as db:
            self.assertEqual(2, db.execute("SELECT COUNT(*) FROM events").fetchone()[0])

    def test_binding_and_tokens_survive_server_restart(self):
        self.store.handle(self.request("white"))
        restored = SyncStore(self.path, self.hashes)
        self.assertTrue(restored.handle(self.request("white"))["ok"])
        with self.assertRaises(Rejected):
            restored.handle(self.request("white", installation=str(uuid.uuid4())))
        with self.assertRaises(Rejected):
            restored.handle(self.request("champion", token="c" * 64))

    def test_conflict_rolls_back_whole_batch(self):
        first, second = self.event("white"), self.event("white")
        self.store.handle(self.request("white", [first]))
        with self.assertRaises(Rejected):
            self.store.handle(self.request("white", [second, {**first, "app": "不同内容"}]))
        with self.store.connect() as db:
            self.assertEqual(1, db.execute("SELECT COUNT(*) FROM events").fetchone()[0])

    def test_large_history_migration_pages_without_skips(self):
        records = [self.event("white", at=i) for i in range(750)]
        for start in range(0, len(records), 100):
            self.store.handle(self.request("white", records[start:start+100]))
        first = self.store.handle(self.request("champion"))
        second = self.store.handle(self.request("champion", headSeen=first["partner"]["headSeen"]))
        self.assertTrue(first["more"])
        self.assertFalse(second["more"])
        self.assertEqual(records, first["partner"]["events"] + second["partner"]["events"])

    def test_latest_presence_is_separate_from_chat(self):
        new = self.event("white", kind="PRESENCE", at=2000, status="锁屏中")
        old = self.event("white", kind="PRESENCE", at=1000)
        self.store.handle(self.request("white", [new]))
        ack = self.store.handle(self.request("white", [old]))
        self.assertEqual(1000, ack["presenceUpTo"])
        peer = self.store.handle(self.request("champion"))["partner"]
        self.assertEqual(new, peer["presence"])
        self.assertEqual([], peer["events"])

    def test_avatar_migration_delta_and_deletion(self):
        self.assertNotIn("avatar", self.store.handle(self.request("champion"))["partner"])
        avatar = {"revision": str(uuid.uuid4()), "data": base64.b64encode(b"\xff\xd8test\xff\xd9").decode()}
        self.assertEqual(avatar["revision"], self.store.handle(self.request("white", avatar=avatar))["avatarAcknowledged"])
        self.assertEqual(avatar, self.store.handle(self.request("champion"))["partner"]["avatar"])
        self.assertNotIn("avatar", self.store.handle(self.request("champion", partnerAvatarRevision=avatar["revision"]))["partner"])
        deleted = {"revision": str(uuid.uuid4()), "data": ""}
        self.store.handle(self.request("white", avatar=deleted))
        self.assertEqual(deleted, self.store.handle(self.request("champion"))["partner"]["avatar"])

    def test_invalid_payload_does_not_claim_device(self):
        for record in (self.event("champion"), self.event("white", duration=-1), self.event("white", kind=[]),
                       self.event("white", kind="LOCATION", lat=float("nan"), lon=0, accuracy=1)):
            with self.assertRaises(Rejected):
                self.store.handle(self.request("white", [record]))
        with self.store.connect() as db:
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM devices").fetchone()[0])

    def test_http_health_auth_and_request_limits(self):
        app = make_app(self.store)
        def call(path, method, body=b"", **extra):
            statuses = []
            env = {"PATH_INFO": path, "REQUEST_METHOD": method, "CONTENT_TYPE": "application/json",
                   "CONTENT_LENGTH": str(len(body)), "wsgi.input": io.BytesIO(body), **extra}
            data = b"".join(app(env, lambda status, headers: statuses.append(status)))
            return statuses[0], json.loads(data)
        self.assertEqual("200 OK", call("/health", "GET")[0])
        self.assertFalse(call("/v1/sync", "POST", b'{}')[1]["ok"])
        self.assertEqual("400 Bad Request", call("/v1/sync", "POST", b'not-json')[0])
        self.assertFalse(call("/v1/sync", "POST", b'{}', CONTENT_LENGTH="524289")[1]["ok"])


if __name__ == "__main__":
    unittest.main()
