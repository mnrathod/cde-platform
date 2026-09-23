"""
The HTTP surface, where a large file is the normal case.

A model passthrough hands back a file the converter did not have to open.
Reading it whole to do that is the §7.7 failure the service is most likely
to hit in production: a GLB of a real building runs to hundreds of
megabytes, and one per concurrent request is how this process dies serving
bytes it never needed to look at.
"""
import io
import os

import http_api


class RecordingSocket(io.BytesIO):
    """Stands in for the client connection, and remembers what was written."""

    def flush(self):
        pass


def passthrough(monkeypatch, tmp_path, size_bytes):
    """Drive one model passthrough and return (written, biggest single read)."""
    model = tmp_path / "building.glb"
    model.write_bytes(b"g" * size_bytes)

    monkeypatch.setattr(http_api, "convert", lambda *a, **k: {
        "success": True, "type": "model3d_passthrough",
        "ext": "glb", "filePath": str(model),
    })

    reads = []
    real_open = open

    def watching_open(path, *args, **kwargs):
        handle = real_open(path, *args, **kwargs)
        if str(path) != str(model):
            return handle

        class Watched:
            def read(self, size=-1):
                chunk = handle.read(size)
                reads.append(len(chunk))
                return chunk
            def __enter__(self): return self
            def __exit__(self, *exc): handle.close()
        return Watched()

    monkeypatch.setattr("builtins.open", watching_open)

    handler = http_api.Handler.__new__(http_api.Handler)
    handler.wfile = RecordingSocket()
    handler.path = "/convert"
    body = b'{"path": "/some/model.glb"}'
    handler.headers = {"Content-Length": str(len(body))}
    handler.rfile = io.BytesIO(body)
    handler.send_response = lambda *a, **k: None
    handler.send_header = lambda *a, **k: None
    handler.end_headers = lambda: None

    handler.do_POST()
    return handler.wfile.getvalue(), max(reads) if reads else 0


class TestServingAModelWithoutSwallowingIt:

    def test_the_whole_file_reaches_the_client(self, monkeypatch, tmp_path):
        written, _ = passthrough(monkeypatch, tmp_path, 300_000)
        assert written == b"g" * 300_000

    def test_it_is_never_held_whole_in_memory(self, monkeypatch, tmp_path):
        # The assertion that matters: not that the bytes arrive, but that no
        # single read pulled the file in. A `read_bytes()` here reads it all
        # in one call and passes every other test in this file.
        _, biggest = passthrough(monkeypatch, tmp_path, 300_000)
        assert 0 < biggest <= 64 * 1024, \
            f"one read took {biggest} bytes of a 300,000-byte model"

    def test_the_length_header_still_describes_the_whole_file(
            self, monkeypatch, tmp_path):
        # Streaming must not cost the client its Content-Length: without it a
        # download shows no progress and cannot be resumed.
        model = tmp_path / "building.glb"
        model.write_bytes(b"g" * 4096)
        monkeypatch.setattr(http_api, "convert", lambda *a, **k: {
            "success": True, "type": "model3d_passthrough",
            "ext": "glb", "filePath": str(model),
        })

        headers = {}
        handler = http_api.Handler.__new__(http_api.Handler)
        handler.wfile = RecordingSocket()
        handler.path = "/convert"
        body = b'{"path": "/some/model.glb"}'
        handler.headers = {"Content-Length": str(len(body))}
        handler.rfile = io.BytesIO(body)
        handler.send_response = lambda *a, **k: None
        handler.send_header = lambda name, value: headers.__setitem__(name, value)
        handler.end_headers = lambda: None

        handler.do_POST()
        assert headers["Content-Length"] == str(os.path.getsize(model))
