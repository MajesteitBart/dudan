"""Tests for aight_upload.py. From the repository root:

    python -m unittest tools/hermes-upload/test_aight_upload.py

Each test starts the server on a free port in a thread, with its own temporary upload directory.
"""

import hashlib
import http.client
import json
import logging
import mimetypes
import os
import socket
import sys
import tempfile
import threading
import time
import unittest
from collections import namedtuple
from pathlib import Path
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import aight_upload as au  # noqa: E402

KEY = "test-key-0123456789abcdef"
MiB = 1024 * 1024

# Keep the server's warnings (401s and the like, which the tests cause on purpose) out of the output.
LOGGER = logging.getLogger("aight-upload")
LOGGER.addHandler(logging.NullHandler())
LOGGER.propagate = False


def wait_for(condition, timeout=5.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if condition():
            return True
        time.sleep(0.02)
    return False


def read_head(sock):
    """Reads one response head from a raw socket: (status, headers, bytes read past the head)."""
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = sock.recv(65536)
        if not chunk:
            break
        data += chunk
    head, _, rest = data.partition(b"\r\n\r\n")
    lines = head.decode("latin-1").split("\r\n")
    status = int(lines[0].split(" ", 2)[1])
    headers = {}
    for line in lines[1:]:
        name, _, value = line.partition(":")
        headers[name.strip().lower()] = value.strip()
    return status, headers, rest


def read_response(sock):
    status, headers, rest = read_head(sock)
    length = int(headers.get("content-length", 0))
    while len(rest) < length:
        chunk = sock.recv(65536)
        if not chunk:
            break
        rest += chunk
    return status, headers, json.loads(rest[:length]) if length else None


class ServerTestCase(unittest.TestCase):
    max_bytes = 4 * MiB
    chunk_bytes = 64 * 1024

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = os.path.join(self._tmp.name, "uploads", "aight")
        self.store = au.Store(self.dir, max_bytes=self.max_bytes, chunk_bytes=self.chunk_bytes, retention_days=30)
        self.server = au.UploadServer(("127.0.0.1", 0), self.store, KEY)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        self.thread.start()
        self.conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)

    def tearDown(self):
        self.conn.close()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(5)
        self._tmp.cleanup()

    @property
    def root(self):
        return Path(os.path.realpath(self.dir))

    def request(self, method, path, body=None, headers=None, key=KEY):
        all_headers = {} if key is None else {"Authorization": f"Bearer {key}"}
        all_headers.update(headers or {})
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
            all_headers.setdefault("Content-Type", "application/json")
        self.conn.request(method, path, body=body, headers=all_headers)
        response = self.conn.getresponse()
        data = response.read()
        if data:
            self.assertEqual(response.getheader("Content-Type"), "application/json")
        return response.status, (json.loads(data) if data else None), response

    def create(self, name="notes.txt", size=10, **fields):
        status, body, _ = self.request("POST", "/uploads", {"name": name, "size": size, **fields})
        self.assertEqual(status, 201, body)
        return body

    def put(self, upload_id, offset, data):
        return self.request("PUT", f"/uploads/{upload_id}?offset={offset}", body=data)

    def upload(self, name, data, chunk=None, **fields):
        """Uploads data in chunks. Returns the upload id and the last response body."""
        upload_id = self.create(name=name, size=len(data), **fields)["id"]
        chunk = chunk or len(data)
        body = None
        for start in range(0, len(data), chunk):
            status, body, _ = self.put(upload_id, start, data[start:start + chunk])
            self.assertEqual(status, 200, body)
        return upload_id, body

    def raw(self, head):
        sock = socket.create_connection(("127.0.0.1", self.port), timeout=10)
        sock.sendall(head.encode("latin-1"))
        return sock

    def assert_error(self, status, body, expected_status, code):
        self.assertEqual(status, expected_status, body)
        self.assertEqual(body["error"]["code"], code)
        self.assertIsInstance(body["error"]["message"], str)
        self.assertTrue(body["error"]["message"])


class AccessTests(ServerTestCase):
    def test_health(self):
        status, body, _ = self.request("GET", "/health")
        self.assertEqual(status, 200)
        self.assertEqual(body, {"ok": True, "version": 1, "max_bytes": self.max_bytes, "chunk_bytes": self.chunk_bytes})

    def test_missing_or_wrong_key_is_refused(self):
        upload_id = self.create()["id"]
        requests = [("GET", "/health", None), ("POST", "/uploads", {"name": "a.txt", "size": 1}),
                    ("GET", f"/uploads/{upload_id}", None), ("PUT", f"/uploads/{upload_id}?offset=0", b"x"),
                    ("DELETE", f"/uploads/{upload_id}", None), ("GET", "/nothing-here", None)]
        for key in (None, "wrong-key-0123456789abcdef", KEY.upper(), ""):
            for method, path, body in requests:
                with self.subTest(key=key, method=method, path=path):
                    status, reply, _ = self.request(method, path, body, key=key)
                    self.assert_error(status, reply, 401, "unauthorized")
        status, reply, _ = self.request("GET", "/health", key=None, headers={"Authorization": f"Basic {KEY}"})
        self.assert_error(status, reply, 401, "unauthorized")
        # Nothing was changed by the refused requests.
        status, reply, _ = self.request("GET", f"/uploads/{upload_id}")
        self.assertEqual((status, reply["offset"]), (200, 0))

    def test_browser_requests_are_refused(self):
        for origin in ("https://example.com", "null"):
            for method, path in (("GET", "/health"), ("POST", "/uploads"), ("OPTIONS", "/uploads")):
                with self.subTest(origin=origin, method=method):
                    status, body, _ = self.request(method, path, headers={"Origin": origin})
                    self.assert_error(status, body, 403, "browser_refused")

    def test_unknown_routes_and_methods(self):
        status, body, _ = self.request("GET", "/nope")
        self.assert_error(status, body, 404, "not_found")
        status, body, response = self.request("DELETE", "/health")
        self.assert_error(status, body, 405, "method_not_allowed")
        self.assertEqual(response.getheader("Allow"), "GET")
        status, body, _ = self.request("GET", "/uploads")
        self.assert_error(status, body, 405, "method_not_allowed")
        status, body, response = self.request("PATCH", f"/uploads/{'a' * 32}")
        self.assert_error(status, body, 405, "method_not_allowed")
        self.assertEqual(response.getheader("Allow"), "GET, PUT, DELETE")
        status, body, _ = self.request("GET", "/uploads/a/b")
        self.assert_error(status, body, 404, "not_found")

    def test_key_is_never_logged(self):
        wrong = "wrong-key-0123456789abcdef"
        with self.assertLogs("aight-upload", level="DEBUG") as logs:
            upload_id, _ = self.upload("secret.txt", b"data")
            self.request("GET", f"/uploads/{upload_id}")
            self.request("DELETE", f"/uploads/{upload_id}")
            self.request("GET", "/health", key=wrong)
        output = "\n".join(logs.output)
        self.assertIn(upload_id, output)
        self.assertNotIn(KEY, output)
        self.assertNotIn(wrong, output)


class CreateTests(ServerTestCase):
    def test_create(self):
        body = self.create(name="report.pdf", size=1234)
        self.assertRegex(body["id"], r"^[0-9a-f]{32}$")
        self.assertEqual(body, {"id": body["id"], "offset": 0, "size": 1234, "chunk_bytes": self.chunk_bytes})
        partial = self.root / ".partial"
        self.assertEqual((partial / f"{body['id']}.part").stat().st_size, 0)
        self.assertTrue((partial / f"{body['id']}.json").is_file())
        status, state, _ = self.request("GET", f"/uploads/{body['id']}")
        self.assertEqual(status, 200)
        self.assertEqual(state, {"id": body["id"], "name": "report.pdf", "mime": mimetypes.guess_type("x.pdf")[0], "size": 1234,
                                 "offset": 0, "complete": False})

    def test_create_keeps_the_client_mime(self):
        upload_id = self.create(name="x.bin", size=3, mime="audio/ogg")["id"]
        self.assertEqual(self.request("GET", f"/uploads/{upload_id}")[1]["mime"], "audio/ogg")

    def test_too_large(self):
        status, body, _ = self.request("POST", "/uploads", {"name": "big.mov", "size": self.max_bytes + 1})
        self.assert_error(status, body, 413, "too_large")
        self.assertIn("4 MB", body["error"]["message"])
        self.assertEqual(self.create(name="edge.bin", size=self.max_bytes)["size"], self.max_bytes)

    def test_bad_input(self):
        bad = [
            {}, {"size": 10}, {"name": "", "size": 10}, {"name": "   ", "size": 10}, {"name": 5, "size": 10},
            {"name": "a.txt"}, {"name": "a.txt", "size": 0}, {"name": "a.txt", "size": -1},
            {"name": "a.txt", "size": "10"}, {"name": "a.txt", "size": 1.5}, {"name": "a.txt", "size": True},
            {"name": "a.txt", "size": 10, "sha256": "abc"}, {"name": "a.txt", "size": 10, "sha256": "g" * 64},
            {"name": "a.txt", "size": 10, "mime": 7}, [1, 2],
        ]
        for payload in bad:
            with self.subTest(payload=payload):
                status, body, _ = self.request("POST", "/uploads", payload)
                self.assert_error(status, body, 400, "invalid_request")
        for raw in (b"not json", b"\xff\xfe", b"", b"null"):
            with self.subTest(raw=raw):
                status, body, _ = self.request("POST", "/uploads", raw, headers={"Content-Type": "application/json"})
                self.assert_error(status, body, 400, "invalid_request")
        status, body, _ = self.request("POST", "/uploads", b"{" + b" " * au.MAX_JSON_BYTES + b"}")
        self.assert_error(status, body, 413, "request_too_large")
        self.assertEqual(list((self.root / ".partial").iterdir()), [])

    def test_not_enough_disk_space(self):
        usage = namedtuple("usage", "total used free")(100, 90, 10)
        with mock.patch.object(au.shutil, "disk_usage", return_value=usage):
            status, body, _ = self.request("POST", "/uploads", {"name": "a.bin", "size": 11})
        self.assert_error(status, body, 507, "insufficient_storage")


class UploadTests(ServerTestCase):
    def test_upload_in_three_chunks(self):
        data = os.urandom(250_000)
        digest = hashlib.sha256(data).hexdigest()
        created = self.create(name="holiday photos.zip", size=len(data), sha256=digest)
        upload_id = created["id"]
        status, body, _ = self.put(upload_id, 0, data[:100_000])
        self.assertEqual((status, body), (200, {"offset": 100_000, "complete": False}))
        status, body, _ = self.put(upload_id, 100_000, data[100_000:200_000])
        self.assertEqual((status, body), (200, {"offset": 200_000, "complete": False}))
        self.assertEqual(self.request("GET", f"/uploads/{upload_id}")[1]["offset"], 200_000)
        status, body, _ = self.put(upload_id, 200_000, data[200_000:])
        self.assertEqual(status, 200, body)
        path = Path(body["path"])
        zip_mime = mimetypes.guess_type("x.zip")[0]
        self.assertEqual(body, {
            "offset": len(data), "complete": True, "path": str(path), "name": "holiday photos.zip",
            "safe_name": "holiday photos.zip", "mime": zip_mime, "size": len(data), "sha256": digest,
        })
        self.assertTrue(path.is_absolute())
        self.assertEqual(path.parent, self.root)
        self.assertEqual(path.name, f"{upload_id[:12]}_holiday photos.zip")
        self.assertEqual(path.read_bytes(), data)
        self.assertFalse((self.root / ".partial" / f"{upload_id}.part").exists())
        if os.name == "posix":
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(self.root.stat().st_mode & 0o777, 0o700)
        # The finished upload still answers GET, now with its path and checksum.
        status, state, _ = self.request("GET", f"/uploads/{upload_id}")
        self.assertEqual(status, 200)
        self.assertEqual(state, {"id": upload_id, "name": "holiday photos.zip", "mime": zip_mime,
                                 "size": len(data), "offset": len(data), "complete": True, "path": str(path),
                                 "sha256": digest})

    def test_upload_without_checksum(self):
        data = b"hello, hermes\n"
        _, body = self.upload("hello.txt", data)
        self.assertEqual(body["sha256"], hashlib.sha256(data).hexdigest())
        self.assertEqual(Path(body["path"]).read_bytes(), data)

    def test_resume_after_offset_mismatch(self):
        data = os.urandom(90_000)
        upload_id = self.create(size=len(data))["id"]
        self.put(upload_id, 0, data[:30_000])
        sock = self.conn.sock
        # The client lost the first answer and sends the same chunk again.
        status, body, _ = self.put(upload_id, 0, data[:30_000])
        self.assert_error(status, body, 409, "offset_mismatch")
        self.assertEqual(body["offset"], 30_000)
        # The rejected body was drained, so the connection is still usable.
        self.assertIs(self.conn.sock, sock)
        status, body, _ = self.put(upload_id, 45_000, data[45_000:])
        self.assert_error(status, body, 409, "offset_mismatch")
        self.assertEqual(body["offset"], 30_000)
        state = self.request("GET", f"/uploads/{upload_id}")[1]
        self.assertEqual((state["offset"], state["complete"]), (30_000, False))
        status, final, _ = self.put(upload_id, state["offset"], data[state["offset"]:])
        self.assertEqual(status, 200)
        self.assertEqual(Path(final["path"]).read_bytes(), data)
        # A finished upload takes no more chunks.
        status, body, _ = self.put(upload_id, 0, b"x")
        self.assert_error(status, body, 409, "offset_mismatch")
        self.assertEqual(body["offset"], len(data))
        status, body, _ = self.put(upload_id, len(data), b"x")
        self.assert_error(status, body, 400, "invalid_request")

    def test_lost_final_answer(self):
        # The last chunk landed but the answer got lost. The client asks for the offset, finds the end
        # of the file, and an empty PUT there gives it the final answer again.
        data = os.urandom(10_000)
        upload_id, final = self.upload("lost.bin", data, chunk=4000)
        offset = self.request("GET", f"/uploads/{upload_id}")[1]["offset"]
        self.assertEqual(offset, len(data))
        status, body, _ = self.put(upload_id, offset, b"")
        self.assertEqual((status, body), (200, final))
        self.assertEqual(Path(body["path"]).read_bytes(), data)

    def test_checksum_mismatch_removes_the_upload(self):
        data = os.urandom(5000)
        upload_id = self.create(size=len(data), sha256="0" * 64)["id"]
        self.put(upload_id, 0, data[:2000])
        status, body, _ = self.put(upload_id, 2000, data[2000:])
        self.assert_error(status, body, 422, "checksum_mismatch")
        self.assertIn(hashlib.sha256(data).hexdigest(), body["error"]["message"])
        status, body, _ = self.request("GET", f"/uploads/{upload_id}")
        self.assert_error(status, body, 404, "not_found")
        self.assertEqual([p.name for p in self.root.iterdir()], [".partial"])
        self.assertEqual(list((self.root / ".partial").iterdir()), [])

    def test_uppercase_checksum_is_accepted(self):
        data = b"abc"
        _, body = self.upload("a.txt", data, sha256=hashlib.sha256(data).hexdigest().upper())
        self.assertEqual(body["sha256"], hashlib.sha256(data).hexdigest())

    def test_put_errors(self):
        upload_id = self.create(size=100)["id"]
        for query in ("", "?offset=", "?offset=abc", "?offset=-1", "?offset=1&offset=2"):
            with self.subTest(query=query):
                status, body, _ = self.request("PUT", f"/uploads/{upload_id}{query}", b"x")
                self.assert_error(status, body, 400, "invalid_request")
        status, body, _ = self.put(upload_id, 0, b"x" * 101)
        self.assert_error(status, body, 400, "invalid_request")
        status, body, _ = self.put("f" * 32, 0, b"x")
        self.assert_error(status, body, 404, "not_found")
        # No Content-Length.
        sock = self.raw(f"PUT /uploads/{upload_id}?offset=0 HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer {KEY}\r\n\r\n")
        with sock:
            status, _, body = read_response(sock)
        self.assert_error(status, body, 411, "length_required")
        # Over 16 MiB: refused before the body is read.
        sock = self.raw(f"PUT /uploads/{upload_id}?offset=0 HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer {KEY}\r\n"
                        f"Content-Length: {au.MAX_CHUNK_BYTES + 1}\r\n\r\n")
        with sock:
            status, headers, body = read_response(sock)
        self.assert_error(status, body, 413, "chunk_too_large")
        self.assertEqual(headers.get("connection"), "close")
        self.assertEqual(self.request("GET", f"/uploads/{upload_id}")[1]["offset"], 0)

    def test_expect_100_continue(self):
        data = os.urandom(20_000)
        upload_id = self.create(size=len(data))["id"]
        head = ("PUT /uploads/{id}?offset={offset} HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer {key}\r\n"
                "Content-Length: {length}\r\nExpect: 100-continue\r\n\r\n")
        # Wrong offset: the answer comes before the body is sent.
        sock = self.raw(head.format(id=upload_id, offset=5, key=KEY, length=len(data)))
        with sock:
            status, _, body = read_response(sock)
        self.assert_error(status, body, 409, "offset_mismatch")
        self.assertEqual(body["offset"], 0)
        # Wrong key: same.
        sock = self.raw(head.format(id=upload_id, offset=0, key="nope", length=len(data)))
        with sock:
            status, _, body = read_response(sock)
        self.assert_error(status, body, 401, "unauthorized")
        # Right offset: 100 Continue, then the body.
        sock = self.raw(head.format(id=upload_id, offset=0, key=KEY, length=len(data)))
        with sock:
            status, _, rest = read_head(sock)
            self.assertEqual((status, rest), (100, b""))
            sock.sendall(data)
            status, _, body = read_response(sock)
        self.assertEqual(status, 200, body)
        self.assertEqual(Path(body["path"]).read_bytes(), data)

    def test_disconnect_mid_chunk_rolls_back(self):
        data = os.urandom(300_000)
        upload_id = self.create(size=len(data))["id"]
        self.put(upload_id, 0, data[:100_000])
        part = self.root / ".partial" / f"{upload_id}.part"
        with self.assertLogs("aight-upload", level="WARNING") as logs:
            sock = self.raw(f"PUT /uploads/{upload_id}?offset=100000 HTTP/1.1\r\nHost: x\r\n"
                            f"Authorization: Bearer {KEY}\r\nContent-Length: 100000\r\n\r\n")
            sock.sendall(data[100_000:180_000])  # more than one 64 KiB read
            self.assertTrue(wait_for(lambda: part.stat().st_size > 100_000), "the server never wrote the bytes")
            sock.close()
            self.assertTrue(wait_for(lambda: any("rolled back" in line for line in logs.output)), logs.output)
        self.assertEqual(part.stat().st_size, 100_000)
        self.assertEqual(self.request("GET", f"/uploads/{upload_id}")[1]["offset"], 100_000)
        status, body, _ = self.put(upload_id, 100_000, data[100_000:])
        self.assertEqual(status, 200, body)
        self.assertEqual(body["sha256"], hashlib.sha256(data).hexdigest())

    def test_concurrent_writer_gets_409(self):
        data = os.urandom(200_000)
        upload_id = self.create(size=len(data))["id"]
        sock = self.raw(f"PUT /uploads/{upload_id}?offset=0 HTTP/1.1\r\nHost: x\r\n"
                        f"Authorization: Bearer {KEY}\r\nContent-Length: {len(data)}\r\n\r\n")
        with sock:
            sock.sendall(data[:50_000])
            self.assertTrue(wait_for(lambda: self.store.lock_for(upload_id).locked()))
            status, body, _ = self.put(upload_id, 0, data)
            self.assert_error(status, body, 409, "upload_busy")
            self.assertEqual(body["offset"], 0)
            sock.sendall(data[50_000:])
            status, _, body = read_response(sock)
        self.assertEqual(status, 200, body)
        self.assertEqual(Path(body["path"]).read_bytes(), data)

    def test_hostile_names_stay_inside_the_directory(self):
        cases = {
            "../../etc/passwd": "passwd",
            "..\\..\\Windows\\win.ini": "win.ini",
            "a\\b.txt": "b.txt",
            "/abs/path/c.txt": "c.txt",
            "...": "file",
            "..": "file",
            ".": "file",
            "dir/": "file",
            ".bashrc": "bashrc",
            " . .hidden. ": "hidden",
            'we<ir>d:"|?*.txt': "weird.txt",
            "tab\there\nnew  line.md": "tab here new line.md",
            "nul\x00byte\x1b.txt": "nulbyte.txt",
            "rtl‮exe.txt": "rtlexe.txt",
        }
        for name, expected in cases.items():
            with self.subTest(name=name):
                _, body = self.upload(name, b"data")
                self.assertEqual(body["safe_name"], expected)
                self.assertEqual(body["name"], name)
                path = Path(body["path"])
                self.assertEqual(path.parent, self.root)
                self.assertEqual(Path(os.path.realpath(path)).parent, self.root)
                self.assertEqual(path.read_bytes(), b"data")
        self.assertFalse((self.root.parent / "etc").exists())

    def test_long_names_are_cut_but_keep_their_extension(self):
        _, body = self.upload("x" * 300 + ".pdf", b"data")
        self.assertEqual(len(body["safe_name"]), 120)
        self.assertTrue(body["safe_name"].endswith("x.pdf"))
        _, body = self.upload("é" * 300 + ".txt", b"data")
        self.assertLessEqual(len(body["safe_name"].encode("utf-8")), au.NAME_MAX_BYTES)
        self.assertTrue(body["safe_name"].endswith(".txt"))
        self.assertTrue(Path(body["path"]).is_file())


class DeleteTests(ServerTestCase):
    def test_delete_partial_upload(self):
        upload_id = self.create(size=100)["id"]
        self.put(upload_id, 0, b"x" * 40)
        status, body, _ = self.request("DELETE", f"/uploads/{upload_id}")
        self.assertEqual((status, body), (204, None))
        self.assertEqual(list((self.root / ".partial").iterdir()), [])
        status, body, _ = self.request("GET", f"/uploads/{upload_id}")
        self.assert_error(status, body, 404, "not_found")
        status, body, _ = self.request("DELETE", f"/uploads/{upload_id}")
        self.assert_error(status, body, 404, "not_found")

    def test_delete_finished_upload(self):
        upload_id, body = self.upload("done.txt", b"finished")
        status, _, _ = self.request("DELETE", f"/uploads/{upload_id}")
        self.assertEqual(status, 204)
        self.assertFalse(Path(body["path"]).exists())
        self.assertEqual([p.name for p in self.root.iterdir()], [".partial"])
        self.assertEqual(list((self.root / ".partial").iterdir()), [])

    def test_get_unknown_upload(self):
        for upload_id in ("0" * 32, "not-an-id", "A" * 32, "..%2F..%2Fetc"):
            with self.subTest(upload_id=upload_id):
                status, body, _ = self.request("GET", f"/uploads/{upload_id}")
                self.assert_error(status, body, 404, "not_found")


class PruneTests(ServerTestCase):
    def age_by(self, seconds, *paths):
        when = time.time() - seconds
        for path in paths:
            os.utime(path, (when, when))

    def test_recovers_a_final_file_renamed_before_metadata_save(self):
        data = b"finished before metadata"
        meta = self.store.create("recovered.txt", len(data))
        upload_id = meta["id"]
        part = self.store._part_path(upload_id)
        part.write_bytes(data)
        final = self.store.final_path(meta)
        os.replace(part, final)

        self.assertEqual(self.store.prune(), 0)
        recovered = self.store.load(upload_id)
        self.assertTrue(recovered["complete"])
        self.assertEqual(recovered["path"], str(final))
        self.assertEqual(recovered["offset"], len(data))
        self.assertEqual(self.request("GET", f"/uploads/{upload_id}")[1]["path"], str(final))

        self.age_by(31 * 86400, final, self.store._meta_path(upload_id))
        self.assertEqual(self.store.prune(), 1)
        self.assertFalse(final.exists())

    def test_immediate_retry_keeps_a_file_renamed_before_metadata_save(self):
        data = b"safe after interrupted completion"
        meta = self.store.create("retry.txt", len(data))
        upload_id = meta["id"]
        self.store._part_path(upload_id).write_bytes(data)
        final = self.store.final_path(meta)
        os.replace(self.store._part_path(upload_id), final)

        status, body, _ = self.put(upload_id, 0, data)
        self.assertEqual(status, 409)
        self.assertEqual(body["error"]["code"], "offset_mismatch")
        self.assertTrue(final.exists())
        status, body, _ = self.request("GET", f"/uploads/{upload_id}")
        self.assertEqual(status, 200)
        self.assertTrue(body["complete"])
        self.assertEqual(body["path"], str(final))

    def test_prune(self):
        partial = self.root / ".partial"
        old_id, old_done = self.upload("old.txt", b"old")
        _, new_done = self.upload("new.txt", b"new")
        stale_id = self.create(size=10)["id"]
        self.put(stale_id, 0, b"12345")
        fresh_id = self.create(size=10)["id"]
        orphan = partial / f"{'e' * 32}.part"
        orphan.write_bytes(b"left over")

        self.age_by(31 * 86400, old_done["path"], partial / f"{old_id}.json")
        self.age_by(25 * 3600, partial / f"{stale_id}.part", partial / f"{stale_id}.json", orphan)
        self.age_by(29 * 86400, new_done["path"])

        self.assertEqual(self.store.prune(), 2)
        self.assertFalse(Path(old_done["path"]).exists())
        self.assertEqual(self.request("GET", f"/uploads/{old_id}")[0], 404)
        self.assertEqual(self.request("GET", f"/uploads/{stale_id}")[0], 404)
        self.assertFalse((partial / f"{stale_id}.part").exists())
        self.assertFalse(orphan.exists())
        self.assertTrue(Path(new_done["path"]).exists())
        self.assertEqual(self.request("GET", f"/uploads/{fresh_id}")[0], 200)

    def test_partial_upload_touched_recently_survives(self):
        partial = self.root / ".partial"
        upload_id = self.create(size=10)["id"]
        self.age_by(25 * 3600, partial / f"{upload_id}.json")
        self.put(upload_id, 0, b"12345")  # touches the part file and rewrites the metadata
        self.assertEqual(self.store.prune(), 0)
        self.assertEqual(self.request("GET", f"/uploads/{upload_id}")[1]["offset"], 5)

    def test_retention_zero_keeps_finished_files(self):
        self.store.retention = 0
        _, done = self.upload("keep.txt", b"keep")
        self.age_by(3650 * 86400, done["path"])
        self.assertEqual(self.store.prune(), 0)
        self.assertTrue(Path(done["path"]).exists())


class SafeNameTests(unittest.TestCase):
    def test_examples(self):
        self.assertEqual(au.safe_name("report.final.pdf"), "report.final.pdf")
        self.assertEqual(au.safe_name("a/b/../c.txt"), "c.txt")
        self.assertEqual(au.safe_name("   "), "file")
        self.assertEqual(au.safe_name("x." + "y" * 40), "x." + "y" * 40)  # too long to be an extension
        self.assertEqual(au.safe_name("\ud800bad.txt"), "bad.txt")
        self.assertEqual(au.safe_name("report'. It is saved at [other].pdf"), "report. It is saved at other.pdf")


class ConfigTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.env_file = Path(self._tmp.name) / ".env"

    def test_env_file_parsing(self):
        self.env_file.write_text(
            "# API_SERVER_KEY=commented-out-000000\n"
            "OPENROUTER_API_KEY=sk-other\n"
            "export API_SERVER_KEY=\"quoted-key-0123456789\"  # the Hermes key\n"
            "SINGLE='single quoted # not a comment'\n"
            "PLAIN=value # comment\n"
            "  SPACED = spaced value  \n"
            "not a line\n",
            encoding="utf-8",
        )
        values = au.read_env_file(self.env_file)
        self.assertEqual(values["API_SERVER_KEY"], "quoted-key-0123456789")
        self.assertEqual(values["SINGLE"], "single quoted # not a comment")
        self.assertEqual(values["PLAIN"], "value")
        self.assertEqual(values["SPACED"], "spaced value")
        self.assertEqual(au.read_env_file(Path(self._tmp.name) / "missing"), {})

    def test_key_precedence(self):
        self.env_file.write_text("API_SERVER_KEY=from-file-0123456789\n", encoding="utf-8")
        self.assertEqual(au.load_key({}, self.env_file)[0], "from-file-0123456789")
        self.assertEqual(au.load_key({"API_SERVER_KEY": "from-env-0123456789"}, self.env_file)[0],
                         "from-env-0123456789")
        environ = {"API_SERVER_KEY": "from-env-0123456789", "AIGHT_UPLOAD_KEY": "own-key-0123456789"}
        self.assertEqual(au.load_key(environ, self.env_file), ("from-env-0123456789", "$API_SERVER_KEY"))
        self.assertEqual(au.load_key({}, Path(self._tmp.name) / "missing"), (None, None))

    def test_config_from_env_and_flags(self):
        config = au.parse_config([], {})
        self.assertEqual((config.host, config.port, config.dir), ("0.0.0.0", 8645, "~/.hermes/uploads/aight"))
        self.assertEqual((config.max_bytes, config.chunk_bytes, config.retention_days), (262144000, 8388608, 30))
        environ = {"AIGHT_UPLOAD_HOST": "100.64.0.1", "AIGHT_UPLOAD_PORT": "9000", "AIGHT_UPLOAD_RETENTION_DAYS": "0"}
        config = au.parse_config([], environ)
        self.assertEqual((config.host, config.port, config.retention_days), ("100.64.0.1", 9000, 0))
        config = au.parse_config(["--port", "9100", "--host", "127.0.0.1"], environ)
        self.assertEqual((config.host, config.port), ("127.0.0.1", 9100))
        for bad in ({"AIGHT_UPLOAD_PORT": "x"}, {"AIGHT_UPLOAD_CHUNK_BYTES": str(32 * MiB)},
                    {"AIGHT_UPLOAD_MAX_BYTES": "0"}, {"AIGHT_UPLOAD_RETENTION_DAYS": "-1"}):
            with self.subTest(env=bad), self.assertRaises(au.ConfigError):
                au.parse_config([], bad)

    def test_refuses_to_start_without_a_usable_key(self):
        missing = Path(self._tmp.name) / "missing.env"
        clean = {k: v for k, v in os.environ.items() if k not in ("AIGHT_UPLOAD_KEY", "API_SERVER_KEY")}
        argv = ["--dir", str(Path(self._tmp.name) / "uploads"), "--host", "127.0.0.1", "--port", "0"]
        with mock.patch.object(au, "HERMES_ENV", missing), mock.patch.dict(os.environ, clean, clear=True):
            self.assertEqual(au.main(argv), 2)
            os.environ["API_SERVER_KEY"] = "too-short"
            self.assertEqual(au.main(argv), 2)
        self.assertFalse((Path(self._tmp.name) / "uploads").exists())


if __name__ == "__main__":
    unittest.main()
