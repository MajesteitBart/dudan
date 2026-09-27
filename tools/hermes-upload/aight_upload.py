#!/usr/bin/env python3
"""Resumable file uploads from aight to the Hermes host.

Hermes' API server only takes images and caps a request at 10 MB. Its messaging adapters handle other
files by saving them on the host and telling the agent where they are. aight does the same through
this service: the phone uploads the file in chunks, gets back its absolute path, and puts that path in
a text note in the chat turn. The agent then reads the file with its own tools.

A phone on mobile data loses connections all the time, so uploads resume. The phone creates an
upload, sends chunks with PUT at the offset the server last confirmed, and after a drop asks for the
current offset and carries on. Every chunk is fsynced before the offset moves, and a chunk cut off
halfway is rolled back, so the confirmed offset always matches the bytes on disk.

Protocol (JSON; every request needs "Authorization: Bearer <key>"):
  GET    /health                  {"ok", "version", "max_bytes", "chunk_bytes"}
  POST   /uploads                 {"name", "size", "mime"?, "sha256"?} -> {"id", "offset": 0, ...}
  GET    /uploads/<id>            state for resuming; "path" and "sha256" once complete
  PUT    /uploads/<id>?offset=N   raw bytes; the last chunk returns the absolute "path"
  DELETE /uploads/<id>            removes the upload and its file

The key is Hermes' own API key, so aight needs no new secret. First found wins: AIGHT_UPLOAD_KEY,
API_SERVER_KEY, then API_SERVER_KEY= in ~/.hermes/.env. The key is read once, at startup.

Environment (a command-line flag, where there is one, wins):
  AIGHT_UPLOAD_HOST            --host  address to listen on (default 0.0.0.0; the Tailscale IP is better)
  AIGHT_UPLOAD_PORT            --port  (default 8645)
  AIGHT_UPLOAD_DIR             --dir   where files land (default ~/.hermes/uploads/aight)
  AIGHT_UPLOAD_MAX_BYTES       largest file (default 262144000, 250 MiB)
  AIGHT_UPLOAD_CHUNK_BYTES     chunk size suggested to the client (default 8388608, 8 MiB)
  AIGHT_UPLOAD_RETENTION_DAYS  days to keep finished files (default 30; 0 keeps them forever)

Python 3.10+, standard library only.
"""

import argparse
import errno
import hashlib
import hmac
import json
import logging
import mimetypes
import os
import re
import secrets
import shutil
import signal
import socket
import socketserver
import sys
import threading
import time
import unicodedata
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

VERSION = 1
HERMES_ENV = Path.home() / ".hermes" / ".env"
DEFAULT_HOST = "0.0.0.0"
DEFAULT_PORT = 8645
DEFAULT_DIR = "~/.hermes/uploads/aight"
DEFAULT_MAX_BYTES = 250 * 1024 * 1024
DEFAULT_CHUNK_BYTES = 8 * 1024 * 1024
DEFAULT_RETENTION_DAYS = 30

MAX_CHUNK_BYTES = 16 * 1024 * 1024  # the largest PUT body accepted
MAX_JSON_BYTES = 64 * 1024
READ_BYTES = 64 * 1024
MIN_KEY_LENGTH = 16
# A phone that stalls mid-chunk (a tunnel, a network switch) holds a thread until this runs out.
SOCKET_TIMEOUT = 120
PARTIAL_TTL = 24 * 3600  # partial uploads untouched this long are removed
PRUNE_INTERVAL = 3600
DELETE_WAIT = 5  # seconds a DELETE waits for a chunk that is still being written
NAME_MAX_CHARS = 120
NAME_MAX_BYTES = 200  # keeps "<id>_<name>" under the usual 255-byte limit for a file name
EXT_MAX_CHARS = 16
O_BINARY = getattr(os, "O_BINARY", 0)  # Windows would otherwise translate newlines
NO_SPACE = {errno.ENOSPC, getattr(errno, "EDQUOT", errno.ENOSPC)}

ID_PATTERN = re.compile(r"[0-9a-f]{32}")
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
UNSAFE_CHARS = set('<>:"/\\|?*')
ENV_LINE = re.compile(r"\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)")

log = logging.getLogger("aight-upload")


class UploadError(Exception):
    """An error for the client, sent as {"error": {"message", "code"}} plus any extra fields."""

    def __init__(self, status, code, message, headers=None, **extra):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.headers = headers or {}
        self.extra = extra


class ClientGone(Exception):
    """The client went away in the middle of a request body."""


class ConfigError(Exception):
    pass


def safe_name(name):
    """Makes a client's file name safe to use as a file name inside the upload directory."""
    base = re.split(r"[/\\]", name)[-1]
    base = re.sub(r"\s+", " ", base)
    base = "".join(
        ch for ch in base
        if ch not in UNSAFE_CHARS and unicodedata.category(ch) not in ("Cc", "Cf", "Cs")
    )
    base = re.sub(r" {2,}", " ", base)
    base = re.sub(r"^[\s.]+|[\s.]+$", "", base)
    stem, ext = os.path.splitext(base)
    if len(ext) > EXT_MAX_CHARS:
        stem, ext = base, ""
    stem = stem[: NAME_MAX_CHARS - len(ext)]
    while stem and len((stem + ext).encode("utf-8")) > NAME_MAX_BYTES:
        stem = stem[:-1]
    stem = stem.rstrip(" .")
    return stem + ext if stem else "file"


def read_env_file(path):
    """Reads NAME=value lines from a dotenv file. Handles quotes, "export" and comments."""
    values = {}
    try:
        text = Path(path).read_text(encoding="utf-8", errors="replace")
    except OSError:
        return values
    for line in text.splitlines():
        match = ENV_LINE.fullmatch(line)
        if not match:
            continue  # blank lines, comments, anything else
        name, value = match.group(1), match.group(2).strip()
        if value[:1] in ("'", '"'):
            end = value.find(value[0], 1)
            value = value[1:end] if end > 0 else value[1:]
        else:
            value = re.split(r"\s+#", value, maxsplit=1)[0].strip()
        values[name] = value
    return values


def load_key(environ, env_file=None):
    """Returns (key, where it came from), or (None, None) when there is no key."""
    for name in ("AIGHT_UPLOAD_KEY", "API_SERVER_KEY"):
        value = (environ.get(name) or "").strip()
        if value:
            return value, f"${name}"
    env_file = HERMES_ENV if env_file is None else Path(env_file)
    value = read_env_file(env_file).get("API_SERVER_KEY", "").strip()
    if value:
        return value, str(env_file)
    return None, None


def megabytes(count):
    return f"{count / (1024 * 1024):.4g} MB"


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        while block := handle.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def make_private_dir(path):
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(path, 0o700)


def fsync_dir(path):
    """Makes a rename in the directory durable. Not possible on Windows, where it doesn't matter."""
    try:
        fd = os.open(path, os.O_RDONLY)
    except OSError:
        return
    try:
        os.fsync(fd)
    except OSError:
        pass
    finally:
        os.close(fd)


def age(now, *paths):
    """Seconds since the most recent change to any of the paths that exist."""
    times = []
    for path in paths:
        try:
            times.append(path.stat().st_mtime)
        except FileNotFoundError:
            pass
    return now - max(times) if times else 0


class Store:
    """Uploads on disk.

    <dir>/.partial/<id>.part   bytes received so far
    <dir>/.partial/<id>.json   state of the upload; kept after completion so GET still answers
    <dir>/<id[:12]>_<name>     the finished file
    """

    def __init__(self, directory, max_bytes=DEFAULT_MAX_BYTES, chunk_bytes=DEFAULT_CHUNK_BYTES,
                 retention_days=DEFAULT_RETENTION_DAYS):
        directory = Path(directory).expanduser()
        make_private_dir(directory)
        self.root = Path(os.path.realpath(directory))
        self.partial_dir = self.root / ".partial"
        make_private_dir(self.partial_dir)
        self.max_bytes = max_bytes
        self.chunk_bytes = chunk_bytes
        self.retention = retention_days * 86400  # seconds; 0 keeps finished files
        self._locks = {}
        self._locks_guard = threading.Lock()

    def lock_for(self, upload_id):
        with self._locks_guard:
            return self._locks.setdefault(upload_id, threading.Lock())

    def _meta_path(self, upload_id):
        return self.partial_dir / f"{upload_id}.json"

    def _part_path(self, upload_id):
        return self.partial_dir / f"{upload_id}.part"

    def final_path(self, meta):
        path = self.root / f"{meta['id'][:12]}_{meta['safe_name']}"
        if Path(os.path.realpath(path)).parent != self.root:
            raise UploadError(500, "storage_error", "Refusing a file name that points outside the upload directory.")
        return path

    def load(self, upload_id):
        try:
            with open(self._meta_path(upload_id), encoding="utf-8") as handle:
                return json.load(handle)
        except FileNotFoundError:
            return None
        except (OSError, ValueError) as exc:
            log.error("upload %s: unreadable metadata: %s", upload_id, exc)
            return None

    def save(self, meta):
        """Replaces the metadata in one step, so a crash never leaves half a file behind."""
        target = self._meta_path(meta["id"])
        temp = target.with_name(f"{target.name}.{secrets.token_hex(4)}.tmp")
        fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | O_BINARY, 0o600)
        try:
            with os.fdopen(fd, "wb") as handle:
                handle.write(json.dumps(meta).encode("utf-8"))
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temp, target)
        except BaseException:
            temp.unlink(missing_ok=True)
            raise

    def create(self, name, size, mime=None, sha256=None):
        try:
            free = shutil.disk_usage(self.root).free
        except OSError:
            free = None
        if free is not None and free < size:
            raise UploadError(507, "insufficient_storage",
                              f"The host has {megabytes(free)} free, not enough for this file.")
        upload_id = secrets.token_hex(16)
        clean = safe_name(name)
        now = time.time()
        meta = {
            "id": upload_id,
            "name": name,
            "safe_name": clean,
            "mime": mime or mimetypes.guess_type(clean)[0] or "application/octet-stream",
            "size": size,
            "offset": 0,
            "complete": False,
            "expected_sha256": sha256,
            "sha256": None,
            "path": None,
            "created_at": now,
            "updated_at": now,
        }
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | O_BINARY
        os.close(os.open(self._part_path(upload_id), flags, 0o600))
        self.save(meta)
        return meta

    def write_chunk(self, meta, source, length):
        """Writes length bytes from source at the committed offset. Call with the upload's lock held.

        Returns the updated metadata. If the client goes away halfway, the partial file is cut back to
        the committed offset, so a resume starts clean.
        """
        upload_id, start = meta["id"], meta["offset"]
        try:
            handle = open(self._part_path(upload_id), "r+b", buffering=0)
        except FileNotFoundError:
            self._remove(meta)
            raise UploadError(404, "not_found", "The upload's partial file is gone. Start a new upload.") from None
        with handle:
            handle.truncate(start)  # bytes past the committed offset come from an interrupted chunk
            handle.seek(start)
            received = 0
            try:
                while received < length:
                    try:
                        data = source.read(min(READ_BYTES, length - received))
                    except OSError as exc:  # timeout, reset
                        raise ClientGone(str(exc) or type(exc).__name__) from exc
                    if not data:
                        raise ClientGone("connection closed")
                    view = memoryview(data)
                    while view:
                        view = view[handle.write(view):]
                    received += len(data)
                os.fsync(handle.fileno())
            except ClientGone as exc:
                self._roll_back(handle, start)
                log.warning("upload %s: connection lost after %d of %d bytes (%s); rolled back to offset %d",
                            upload_id, received, length, exc, start)
                raise
            except OSError as exc:
                self._roll_back(handle, start)
                log.error("upload %s: writing to disk failed: %s", upload_id, exc)
                if exc.errno in NO_SPACE:
                    raise UploadError(507, "insufficient_storage", "The host's disk is full.") from exc
                raise UploadError(500, "storage_error",
                                  f"Could not write the file on the host: {exc.strerror or exc}") from exc
        meta = dict(meta, offset=start + length, updated_at=time.time())
        if meta["offset"] == meta["size"]:
            return self._finish(meta)
        self.save(meta)
        return meta

    @staticmethod
    def _roll_back(handle, offset):
        try:
            handle.truncate(offset)
            os.fsync(handle.fileno())
        except OSError as exc:
            log.error("could not roll a partial file back to offset %d: %s", offset, exc)

    def _finish(self, meta):
        part = self._part_path(meta["id"])
        digest = sha256_file(part)
        expected = meta.get("expected_sha256")
        if expected and digest != expected:
            self._remove(meta)
            raise UploadError(422, "checksum_mismatch",
                              f"The received file has SHA-256 {digest}, not {expected}. "
                              "The upload was removed; send the file again.")
        final = self.final_path(meta)
        os.replace(part, final)
        fsync_dir(self.root)
        meta.update(complete=True, sha256=digest, path=str(final), completed_at=time.time())
        self.save(meta)
        return meta

    def delete(self, upload_id):
        """Removes an upload in any state. Returns its metadata, or None if there is no such upload."""
        if self.load(upload_id) is None:
            return None  # Don't grow the lock table for ids that don't exist.
        lock = self.lock_for(upload_id)
        if not lock.acquire(timeout=DELETE_WAIT):
            meta = self.load(upload_id)
            if meta is None:
                return None
            raise busy_error(meta)
        try:
            meta = self.load(upload_id)
            if meta is not None:
                self._remove(meta)
            return meta
        finally:
            lock.release()

    def _remove(self, meta):
        """Deletes an upload's files. Call with its lock held."""
        upload_id = meta["id"]
        paths = [self._part_path(upload_id)]
        if meta.get("complete"):
            try:
                paths.append(self.final_path(meta))
            except UploadError:
                pass
        paths.append(self._meta_path(upload_id))  # last, so the upload stays findable if a delete fails
        for path in paths:
            path.unlink(missing_ok=True)
        with self._locks_guard:
            self._locks.pop(upload_id, None)

    def prune(self, now=None):
        """Removes finished uploads past the retention and partial uploads idle for a day."""
        now = time.time() if now is None else now
        removed = 0
        for meta_path in sorted(self.partial_dir.glob("*.json")):
            upload_id = meta_path.stem
            if not ID_PATTERN.fullmatch(upload_id):
                continue
            lock = self.lock_for(upload_id)
            if not lock.acquire(blocking=False):
                continue  # a chunk is being written right now
            try:
                meta = self.load(upload_id)
                if meta is None:
                    continue
                if meta.get("complete"):
                    if self.retention <= 0:
                        continue
                    final = self.final_path(meta)
                    expired = age(now, final if final.exists() else meta_path) > self.retention
                else:
                    expired = age(now, self._part_path(upload_id), meta_path) > PARTIAL_TTL
                if expired:
                    self._remove(meta)
                    removed += 1
                    log.info("upload %s expired: removed %s", upload_id, meta.get("path") or "the partial upload")
            except (OSError, UploadError) as exc:
                log.error("pruning upload %s failed: %s", upload_id, exc)
            finally:
                lock.release()
        # Leftovers of a crash: a part file whose metadata was never written, or a half-written temp file.
        for path in self.partial_dir.iterdir():
            orphan = path.suffix == ".tmp" or (
                path.suffix == ".part" and not self._meta_path(path.stem).exists())
            if orphan and age(now, path) > PARTIAL_TTL:
                path.unlink(missing_ok=True)
                log.info("removed leftover %s", path.name)
        return removed


def describe(meta):
    view = {key: meta[key] for key in ("id", "name", "mime", "size", "offset", "complete")}
    if meta["complete"]:
        view["path"] = meta["path"]
        view["sha256"] = meta["sha256"]
    return view


def busy_error(meta):
    return UploadError(409, "upload_busy",
                       "Another request is still sending a chunk for this upload. Try again in a few seconds.",
                       offset=meta["offset"])


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"  # keep-alive, so a phone sends all its chunks over one connection
    server_version = f"aight-upload/{VERSION}"
    sys_version = ""
    timeout = SOCKET_TIMEOUT
    # Per-request state, reset in parse_request.
    _body_done = True
    _query = ""

    @property
    def store(self):
        return self.server.store

    def parse_request(self):
        self._body_done = False
        self._query = ""
        return super().parse_request()

    def __getattr__(self, name):
        # http.server looks up do_<METHOD>. Every method goes through _dispatch, which answers 405
        # for methods a route doesn't take, instead of http.server's HTML 501.
        if name.startswith("do_"):
            return self._dispatch
        raise AttributeError(name)

    def _dispatch(self):
        try:
            self._check_access()
            action, upload_id = self._route()
            action(upload_id)
        except UploadError as exc:
            self._send_error(exc)
        except ClientGone:
            self.close_connection = True  # nobody is left to answer
        except Exception:  # noqa: BLE001 - answer, rather than drop the connection
            log.exception("%s %s failed", self.command, self._log_path())
            self.close_connection = True
            self._send_error(UploadError(500, "internal_error",
                                         "The upload service hit an unexpected error; see its log on the host."))

    do_GET = do_POST = do_PUT = do_DELETE = _dispatch

    def handle_expect_100(self):
        """Vets a request before the client sends its body. A client that sends
        "Expect: 100-continue" learns about a wrong offset or a busy upload without uploading the
        chunk first."""
        try:
            self._check_access()
            action, upload_id = self._route()
            if action == self._put:
                meta, _ = self._check_put(upload_id)
                if self.store.lock_for(upload_id).locked():
                    raise busy_error(meta)
        except UploadError as exc:
            self._body_done = True  # the client holds the body back; don't wait for it
            self.close_connection = True
            self._send_error(exc)
            return False
        return super().handle_expect_100()

    def _check_access(self):
        if "Origin" in self.headers:
            raise UploadError(403, "browser_refused", "Browsers can't use this service.")
        scheme, _, token = self.headers.get("Authorization", "").partition(" ")
        expected = self.server.key.encode("utf-8")
        if scheme.lower() != "bearer" or not hmac.compare_digest(token.strip().encode("utf-8"), expected):
            raise UploadError(401, "unauthorized", "Missing or wrong API key.")

    def _route(self):
        parts = urlsplit(self.path)
        path = parts.path.rstrip("/") or "/"
        self._query = parts.query
        upload_id = None
        if path == "/health":
            actions = {"GET": self._health}
        elif path == "/uploads":
            actions = {"POST": self._create}
        elif path.startswith("/uploads/") and path.count("/") == 2:
            upload_id = path[len("/uploads/"):]
            if not ID_PATTERN.fullmatch(upload_id):
                raise UploadError(404, "not_found", "No such upload.")
            actions = {"GET": self._status, "PUT": self._put, "DELETE": self._delete}
        else:
            raise UploadError(404, "not_found", "No such endpoint.")
        action = actions.get(self.command)
        if action is None:
            raise UploadError(405, "method_not_allowed", f"Use {' or '.join(actions)} here.",
                              headers={"Allow": ", ".join(actions)})
        return action, upload_id

    # Endpoints

    def _health(self, _):
        self._send_json(200, {"ok": True, "version": VERSION, "max_bytes": self.store.max_bytes,
                              "chunk_bytes": self.store.chunk_bytes})

    def _create(self, _):
        body = self._read_json()
        name, size, mime, sha256 = (body.get(key) for key in ("name", "size", "mime", "sha256"))
        if not isinstance(name, str) or not name.strip():
            raise UploadError(400, "invalid_request", "name must be a non-empty string.")
        if isinstance(size, bool) or not isinstance(size, int) or size <= 0:
            raise UploadError(400, "invalid_request", "size must be a positive whole number of bytes.")
        if size > self.store.max_bytes:
            raise UploadError(413, "too_large",
                              f"The file is {megabytes(size)}; the limit is {megabytes(self.store.max_bytes)}.")
        if mime is not None and (not isinstance(mime, str) or len(mime) > 255):
            raise UploadError(400, "invalid_request", "mime must be a string of at most 255 characters.")
        if sha256 is not None:
            if not isinstance(sha256, str) or not SHA256_PATTERN.fullmatch(sha256.lower()):
                raise UploadError(400, "invalid_request", "sha256 must be 64 hexadecimal characters.")
            sha256 = sha256.lower()
        meta = self.store.create(name, size, (mime or "").strip() or None, sha256)
        log.info("upload %s created by %s: %r, %d bytes", meta["id"], self.client_address[0], name, size)
        self._send_json(201, {"id": meta["id"], "offset": 0, "size": size, "chunk_bytes": self.store.chunk_bytes})

    def _status(self, upload_id):
        meta = self.store.load(upload_id)
        if meta is None:
            raise UploadError(404, "not_found", "No such upload.")
        self._send_json(200, describe(meta))

    def _check_put(self, upload_id):
        """Returns (metadata, chunk length) for a PUT, or raises the error to answer with."""
        meta = self.store.load(upload_id)
        if meta is None:
            raise UploadError(404, "not_found", "No such upload.")
        values = parse_qs(self._query).get("offset", [])
        if len(values) != 1 or not (values[0].isascii() and values[0].isdigit()):
            raise UploadError(400, "invalid_request", "Pass the chunk's position as ?offset=<bytes>.")
        offset = int(values[0])
        length = self._content_length()
        if length > MAX_CHUNK_BYTES:
            raise UploadError(413, "chunk_too_large",
                              f"A chunk can be at most {MAX_CHUNK_BYTES} bytes; this one is {length}.")
        # A finished upload sits at offset == size, so any chunk for it gets a 409 or a 400 here.
        if offset != meta["offset"]:
            raise UploadError(409, "offset_mismatch", f"The upload is at offset {meta['offset']}, not {offset}.",
                              offset=meta["offset"])
        if offset + length > meta["size"]:
            raise UploadError(400, "invalid_request",
                              f"The chunk runs past the end of the file ({meta['size']} bytes).")
        return meta, length

    def _put(self, upload_id):
        meta, length = self._check_put(upload_id)
        lock = self.store.lock_for(upload_id)
        if not lock.acquire(blocking=False):
            raise busy_error(meta)
        try:
            meta, length = self._check_put(upload_id)  # again, now that no other request can change it
            # An empty PUT at the end of a finished upload repeats the final answer. A client whose
            # connection dropped before that answer arrived gets the path this way.
            finished_now = not meta["complete"]
            if finished_now:
                self._body_done = True  # write_chunk reads the body; if it fails, the connection closes
                try:
                    meta = self.store.write_chunk(meta, self.rfile, length)
                except UploadError:
                    self.close_connection = True
                    raise
        finally:
            lock.release()
        if not meta["complete"]:
            self._send_json(200, {"offset": meta["offset"], "complete": False})
            return
        if finished_now:
            log.info("upload %s complete: %s (%d bytes, sha256 %s)", meta["id"], meta["path"], meta["size"],
                     meta["sha256"])
        self._send_json(200, {
            "offset": meta["size"],
            "complete": True,
            "path": meta["path"],
            "name": meta["name"],
            "safe_name": meta["safe_name"],
            "mime": meta["mime"],
            "size": meta["size"],
            "sha256": meta["sha256"],
        })

    def _delete(self, upload_id):
        meta = self.store.delete(upload_id)
        if meta is None:
            raise UploadError(404, "not_found", "No such upload.")
        log.info("upload %s deleted by %s", upload_id, self.client_address[0])
        self._discard_body()
        self.send_response(204)
        if self.close_connection:
            self.send_header("Connection", "close")
        self.end_headers()

    # Request bodies

    def _content_length(self):
        if self.headers.get("Transfer-Encoding"):
            self._body_done = True
            self.close_connection = True  # http.server can't read a chunked body
            raise UploadError(411, "length_required", "Send the body with a Content-Length, not chunked.")
        raw = self.headers.get("Content-Length")
        if raw is None:
            raise UploadError(411, "length_required", "Content-Length is required.")
        raw = raw.strip()
        if not (raw.isascii() and raw.isdigit()):
            self._body_done = True
            self.close_connection = True
            raise UploadError(400, "invalid_request", "Content-Length must be a whole number.")
        return int(raw)

    def _read_json(self):
        length = self._content_length()
        if length > MAX_JSON_BYTES:
            raise UploadError(413, "request_too_large", "The request body is over 64 KiB.")
        self._body_done = True
        try:
            data = self.rfile.read(length)
        except OSError as exc:
            raise ClientGone(str(exc)) from exc
        if len(data) < length:
            raise ClientGone("connection closed")
        try:
            body = json.loads(data.decode("utf-8")) if data else None
        except ValueError:
            body = None
        if not isinstance(body, dict):
            raise UploadError(400, "invalid_request", "The body must be a JSON object.")
        return body

    def _discard_body(self):
        """Reads and drops a body that went unused, so the connection can carry the next request.

        Answering before the client has sent its body and then closing makes the kernel reset the
        connection, and the client may never see the answer: a phone with an outdated key would get a
        network error instead of a 401. Bodies over the chunk limit get the answer and a closed
        connection instead.
        """
        if self._body_done:
            return
        self._body_done = True
        raw = (self.headers.get("Content-Length") or "0").strip()
        if self.headers.get("Transfer-Encoding") or not (raw.isascii() and raw.isdigit()):
            self.close_connection = True
            return
        remaining = int(raw)
        if remaining > MAX_CHUNK_BYTES:
            self.close_connection = True
            return
        try:
            while remaining:
                data = self.rfile.read(min(READ_BYTES, remaining))
                if not data:
                    break
                remaining -= len(data)
        except OSError:
            pass
        if remaining:
            self.close_connection = True

    # Responses

    def _send_json(self, status, body, headers=None):
        self._discard_body()
        data = json.dumps(body).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            for name, value in (headers or {}).items():
                self.send_header(name, value)
            if self.close_connection:
                self.send_header("Connection", "close")
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(data)
        except OSError:
            self.close_connection = True

    def _send_error(self, exc):
        log.warning("%s %s %s: %d %s: %s", self.client_address[0], self.command, self._log_path(), exc.status,
                    exc.code, exc.message)
        self._send_json(exc.status, {"error": {"message": exc.message, "code": exc.code}, **exc.extra},
                        exc.headers)

    def send_error(self, code, message=None, explain=None):
        """Used by http.server for requests it can't parse; answers in JSON like everything else."""
        self._body_done = True
        self.close_connection = True
        codes = {400: "invalid_request", 404: "not_found", 408: "timeout", 414: "uri_too_long",
                 431: "headers_too_large", 505: "http_version_not_supported"}
        text = message or self.responses.get(code, ("Error",))[0]
        log.warning("%s: %d %s", self.client_address[0], code, text)
        self._send_json(code, {"error": {"message": text, "code": codes.get(code, "http_error")}})

    def _log_path(self):
        return urlsplit(self.path or "").path[:200]

    def log_message(self, format, *args):  # noqa: A002 - http.server's signature
        log.debug("%s %s", self.address_string(), format % args)

    def log_error(self, format, *args):  # noqa: A002 - timeouts on idle keep-alive connections, mostly
        log.debug("%s %s", self.address_string(), format % args)


class UploadServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, store, key):
        self.address_family = socket.AF_INET6 if ":" in address[0] else socket.AF_INET
        self.store = store
        self.key = key
        super().__init__(address, Handler)

    def server_bind(self):
        # HTTPServer.server_bind looks up the host's FQDN, which can stall on a machine without DNS.
        socketserver.TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address[:2]

    def handle_error(self, request, client_address):
        exc = sys.exc_info()[1]
        if isinstance(exc, (ConnectionError, TimeoutError)):
            log.debug("%s: %s", client_address[0], exc)
        else:
            log.exception("error while handling a request from %s", client_address[0])


def prune_forever(store, stop):
    while not stop.wait(PRUNE_INTERVAL):
        try:
            store.prune()
        except Exception:  # noqa: BLE001 - keep pruning next hour
            log.exception("pruning failed")


def env_int(environ, name, default):
    raw = (environ.get(name) or "").strip()
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError:
        raise ConfigError(f"{name} must be a whole number, not {raw!r}") from None


def parse_config(argv, environ):
    parser = argparse.ArgumentParser(description="Resumable file uploads from aight to the Hermes host.")
    parser.add_argument("--host", default=environ.get("AIGHT_UPLOAD_HOST") or DEFAULT_HOST,
                        help="address to listen on; use the host's Tailscale IP (default: %(default)s)")
    parser.add_argument("--port", type=int, default=env_int(environ, "AIGHT_UPLOAD_PORT", DEFAULT_PORT),
                        help="port to listen on (default: %(default)s)")
    parser.add_argument("--dir", default=environ.get("AIGHT_UPLOAD_DIR") or DEFAULT_DIR,
                        help="where uploaded files are saved (default: %(default)s)")
    parser.add_argument("--verbose", action="store_true", help="log every request")
    config = parser.parse_args(argv)
    config.max_bytes = env_int(environ, "AIGHT_UPLOAD_MAX_BYTES", DEFAULT_MAX_BYTES)
    config.chunk_bytes = env_int(environ, "AIGHT_UPLOAD_CHUNK_BYTES", DEFAULT_CHUNK_BYTES)
    config.retention_days = env_int(environ, "AIGHT_UPLOAD_RETENTION_DAYS", DEFAULT_RETENTION_DAYS)
    if not 0 <= config.port <= 65535:
        raise ConfigError(f"port {config.port} is out of range")
    if config.max_bytes < 1:
        raise ConfigError("AIGHT_UPLOAD_MAX_BYTES must be at least 1")
    if not 1 <= config.chunk_bytes <= MAX_CHUNK_BYTES:
        raise ConfigError(f"AIGHT_UPLOAD_CHUNK_BYTES must be between 1 and {MAX_CHUNK_BYTES}")
    if config.retention_days < 0:
        raise ConfigError("AIGHT_UPLOAD_RETENTION_DAYS can't be negative")
    return config


def _stop(signum, frame):
    raise KeyboardInterrupt


def main(argv=None):
    try:
        config = parse_config(argv, os.environ)
    except ConfigError as exc:
        print(f"aight-upload: {exc}", file=sys.stderr)
        return 2  # the systemd unit doesn't restart on 2: retrying won't fix the configuration
    logging.basicConfig(level=logging.DEBUG if config.verbose else logging.INFO,
                        format="%(levelname)s %(message)s", stream=sys.stderr)
    key, source = load_key(os.environ)
    if not key:
        log.error("no API key: set API_SERVER_KEY in %s, or AIGHT_UPLOAD_KEY in the environment", HERMES_ENV)
        return 2
    if len(key) < MIN_KEY_LENGTH:
        log.error("the API key from %s is shorter than %d characters; use a longer one", source, MIN_KEY_LENGTH)
        return 2
    try:
        store = Store(config.dir, config.max_bytes, config.chunk_bytes, config.retention_days)
    except OSError as exc:
        log.error("can't use the upload directory %s: %s", config.dir, exc)
        return 1
    store.prune()
    try:
        server = UploadServer((config.host, config.port), store, key)
    except OSError as exc:
        log.error("can't listen on %s port %d: %s", config.host, config.port, exc)
        return 1  # the Tailscale address may not be up yet; systemd tries again
    stop = threading.Event()
    threading.Thread(target=prune_forever, args=(store, stop), name="prune", daemon=True).start()
    signal.signal(signal.SIGTERM, _stop)
    log.info("listening on %s port %d; files go to %s; key from %s; max %s; finished files kept %s",
             config.host, server.server_address[1], store.root, source, megabytes(store.max_bytes),
             f"{config.retention_days} days" if config.retention_days else "forever")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        server.server_close()
        log.info("stopped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
