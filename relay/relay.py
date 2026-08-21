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
            con.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id TEXT PRIMARY KEY,
                    sender TEXT NOT NULL DEFAULT '',
                    recipient TEXT NOT NULL,
                    ciphertext TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL
                )
            """)
            cols = {r[1] for r in con.execute("PRAGMA table_info(messages)")}
            if "sender" not in cols:
                con.execute("ALTER TABLE messages ADD COLUMN sender TEXT NOT NULL DEFAULT ''")
            con.execute("CREATE INDEX IF NOT EXISTS idx_messages_recipient ON messages(recipient, created_at)")
            con.execute("""
                CREATE TABLE IF NOT EXISTS receipts (
                    id TEXT NOT NULL,
                    sender TEXT NOT NULL,
                    acked_at INTEGER NOT NULL,
                    PRIMARY KEY(id, sender)
                )
            """)

    def purge_expired(self, now=None):
        now = int(now or time.time())
        with self._connect() as con:
            cur = con.execute("DELETE FROM messages WHERE expires_at <= ?", (now,))
            return cur.rowcount

    def enqueue(self, message_id, sender, recipient, ciphertext, ttl_seconds=DEFAULT_TTL_SECONDS, now=None):
        now = int(now or time.time())
        expires_at = now + max(60, min(int(ttl_seconds), DEFAULT_TTL_SECONDS))
        self.purge_expired(now)
        with self._connect() as con:
            con.execute(
                "INSERT OR IGNORE INTO messages(id,sender,recipient,ciphertext,created_at,expires_at) VALUES(?,?,?,?,?,?)",
                (message_id, sender, recipient, ciphertext, now, expires_at),
            )
        return expires_at

    def pending(self, recipient, limit=100, now=None):
        now = int(now or time.time())
        self.purge_expired(now)
        limit = max(1, min(int(limit), 100))
        with self._connect() as con:
            rows = con.execute(
                "SELECT id,sender,ciphertext,created_at,expires_at FROM messages WHERE recipient=? ORDER BY created_at ASC LIMIT ?",
                (recipient, limit),
            ).fetchall()
        return [dict(r) for r in rows]

    def ack(self, recipient, message_id, now=None):
        now = int(now or time.time())
        with self._connect() as con:
            row = con.execute("SELECT sender FROM messages WHERE recipient=? AND id=?", (recipient, message_id)).fetchone()
            if not row:
                return False
            sender = row["sender"]
            con.execute("DELETE FROM messages WHERE recipient=? AND id=?", (recipient, message_id))
            if sender:
                con.execute("INSERT OR REPLACE INTO receipts(id,sender,acked_at) VALUES(?,?,?)", (message_id, sender, now))
            return True

    def receipts(self, sender, limit=100):
        limit = max(1, min(int(limit), 100))
        with self._connect() as con:
            rows = con.execute("SELECT id,acked_at FROM receipts WHERE sender=? ORDER BY acked_at ASC LIMIT ?", (sender, limit)).fetchall()
        return [dict(r) for r in rows]

    def receipt_ack(self, sender, message_id):
        with self._connect() as con:
            cur = con.execute("DELETE FROM receipts WHERE sender=? AND id=?", (sender, message_id))
            return cur.rowcount == 1

    def count(self):
        with self._connect() as con:
            return con.execute("SELECT COUNT(*) FROM messages").fetchone()[0]


def make_handler(store):
    class Handler(BaseHTTPRequestHandler):
        server_version = "LSMRelay/0.2"
        def log_message(self, fmt, *args): return

        def _json(self, status, payload):
            raw = json.dumps(payload, separators=(",", ":")).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(raw)

        def _read_json(self):
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > MAX_BODY: raise ValueError("invalid body size")
            return json.loads(self.rfile.read(length).decode())

        def do_POST(self):
            try:
                body = self._read_json()
                if self.path == "/v1/messages":
                    mid, sender, recipient = str(body["id"]), str(body["from"]), str(body["to"])
                    ciphertext = str(body["ciphertext"])
                    ttl = int(body.get("ttl_seconds", DEFAULT_TTL_SECONDS))
                    if not mid or not sender or not recipient or not ciphertext: raise ValueError()
                    exp = store.enqueue(mid, sender, recipient, ciphertext, ttl)
                    return self._json(202, {"accepted": True, "id": mid, "expires_at": exp})
                if self.path == "/v1/ack":
                    removed = store.ack(str(body["recipient"]), str(body["id"]))
                    return self._json(200, {"acked": removed, "id": str(body["id"])})
                if self.path == "/v1/receipt-ack":
                    removed = store.receipt_ack(str(body["sender"]), str(body["id"]))
                    return self._json(200, {"removed": removed, "id": str(body["id"])})
                self._json(404, {"error":"not_found"})
            except (ValueError, KeyError, json.JSONDecodeError):
                self._json(400, {"error":"bad_request"})

        def do_GET(self):
            p = urlparse(self.path)
            if p.path == "/health": return self._json(200, {"ok":True})
            qs = parse_qs(p.query)
            if p.path == "/v1/messages":
                recipient = (qs.get("recipient") or [""])[0]
                if not recipient: return self._json(400, {"error":"recipient_required"})
                return self._json(200, {"messages": store.pending(recipient)})
            if p.path == "/v1/receipts":
                sender = (qs.get("sender") or [""])[0]
                if not sender: return self._json(400, {"error":"sender_required"})
                return self._json(200, {"receipts": store.receipts(sender)})
            self._json(404, {"error":"not_found"})
    return Handler


def main():
    import argparse
    p = argparse.ArgumentParser(description="LSM opaque store-and-forward relay")
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=8787)
    p.add_argument("--db", default="relay.db")
    a = p.parse_args()
    ThreadingHTTPServer((a.host, a.port), make_handler(MessageStore(a.db))).serve_forever()

if __name__ == "__main__": main()
