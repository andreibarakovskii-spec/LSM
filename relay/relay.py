#!/usr/bin/env python3
import json
import sqlite3
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

MAX_BODY = 64 * 1024
DEFAULT_TTL_SECONDS = 7 * 24 * 60 * 60


class MessageStore:
    def __init__(self, db_path="relay.db"):
        self.db_path = db_path
        self._init_db()

    def _connect(self):
        con = sqlite3.connect(self.db_path)
        con.row_factory = sqlite3.Row
        return con

    def _init_db(self):
        with self._connect() as con:
            con.execute(
                """
                CREATE TABLE IF NOT EXISTS messages (
                    id TEXT PRIMARY KEY,
                    recipient TEXT NOT NULL,
                    ciphertext TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL
                )
                """
            )
            con.execute("CREATE INDEX IF NOT EXISTS idx_messages_recipient ON messages(recipient, created_at)")

    def purge_expired(self, now=None):
        now = int(now or time.time())
        with self._connect() as con:
            cur = con.execute("DELETE FROM messages WHERE expires_at <= ?", (now,))
            return cur.rowcount

    def enqueue(self, message_id, recipient, ciphertext, ttl_seconds=DEFAULT_TTL_SECONDS, now=None):
        now = int(now or time.time())
        expires_at = now + max(60, min(int(ttl_seconds), DEFAULT_TTL_SECONDS))
        self.purge_expired(now)
        with self._connect() as con:
            con.execute(
                "INSERT OR IGNORE INTO messages(id, recipient, ciphertext, created_at, expires_at) VALUES(?,?,?,?,?)",
                (message_id, recipient, ciphertext, now, expires_at),
            )
        return expires_at

    def pending(self, recipient, limit=100, now=None):
        now = int(now or time.time())
        self.purge_expired(now)
        limit = max(1, min(int(limit), 100))
        with self._connect() as con:
            rows = con.execute(
                "SELECT id, ciphertext, created_at, expires_at FROM messages WHERE recipient=? ORDER BY created_at ASC LIMIT ?",
                (recipient, limit),
            ).fetchall()
        return [dict(row) for row in rows]

    def ack(self, recipient, message_id):
        with self._connect() as con:
            cur = con.execute("DELETE FROM messages WHERE recipient=? AND id=?", (recipient, message_id))
            return cur.rowcount == 1

    def count(self):
        with self._connect() as con:
            return con.execute("SELECT COUNT(*) FROM messages").fetchone()[0]


def make_handler(store):
    class Handler(BaseHTTPRequestHandler):
        server_version = "LSMRelay/0.1"

        def log_message(self, fmt, *args):
            # Never log request bodies/ciphertext.
            return

        def _json(self, status, payload):
            raw = json.dumps(payload, separators=(",", ":")).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(raw)

        def _read_json(self):
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > MAX_BODY:
                raise ValueError("invalid body size")
            return json.loads(self.rfile.read(length).decode("utf-8"))

        def do_POST(self):
            try:
                body = self._read_json()
                if self.path == "/v1/messages":
                    message_id = str(body["id"])
                    recipient = str(body["to"])
                    ciphertext = str(body["ciphertext"])
                    ttl = int(body.get("ttl_seconds", DEFAULT_TTL_SECONDS))
                    if not message_id or not recipient or not ciphertext:
                        raise ValueError("missing field")
                    expires_at = store.enqueue(message_id, recipient, ciphertext, ttl)
                    self._json(202, {"accepted": True, "id": message_id, "expires_at": expires_at})
                    return
                if self.path == "/v1/ack":
                    recipient = str(body["recipient"])
                    message_id = str(body["id"])
                    removed = store.ack(recipient, message_id)
                    self._json(200, {"acked": removed, "id": message_id})
                    return
                self._json(404, {"error": "not_found"})
            except (ValueError, KeyError, json.JSONDecodeError):
                self._json(400, {"error": "bad_request"})

        def do_GET(self):
            parsed = urlparse(self.path)
            if parsed.path == "/health":
                self._json(200, {"ok": True})
                return
            if parsed.path != "/v1/messages":
                self._json(404, {"error": "not_found"})
                return
            qs = parse_qs(parsed.query)
            recipient = (qs.get("recipient") or [""])[0]
            if not recipient:
                self._json(400, {"error": "recipient_required"})
                return
            items = store.pending(recipient)
            self._json(200, {"messages": items})

    return Handler


def main():
    import argparse
    parser = argparse.ArgumentParser(description="LSM opaque store-and-forward relay")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--db", default="relay.db")
    args = parser.parse_args()
    store = MessageStore(args.db)
    server = ThreadingHTTPServer((args.host, args.port), make_handler(store))
    server.serve_forever()


if __name__ == "__main__":
    main()
