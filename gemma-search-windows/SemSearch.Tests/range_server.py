"""Static files with HTTP Range support for the RemoteZip test; drops the first large range once mid-way,
and never answers for paths containing "stall" (a filtered host).

usage: python3 range_server.py <port> <dir>
"""
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = sys.argv[2]
dropped = set()


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        if "stall" in self.path:
            time.sleep(3600)
            return
        path = os.path.join(ROOT, os.path.basename(self.path))
        if not os.path.isfile(path):
            self.send_response(404)
            self.end_headers()
            return
        size = os.path.getsize(path)
        rng = self.headers.get("Range")
        if not rng:
            self.send_response(200)
            self.send_header("Content-Length", str(size))
            self.end_headers()
            with open(path, "rb") as f:
                self.wfile.write(f.read())
            return
        a, b = rng.split("=")[1].split("-")
        if a == "":
            start, end = max(0, size - int(b)), size - 1
        else:
            start, end = int(a), min(size - 1, int(b) if b else size - 1)
        with open(path, "rb") as f:
            f.seek(start)
            data = f.read(end - start + 1)
        self.send_response(206)
        self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        key = (path, start)
        if len(data) > 100_000 and key not in dropped:
            dropped.add(key)
            self.wfile.write(data[: len(data) // 2])
            self.wfile.flush()
            self.connection.shutdown(2)
            return
        self.wfile.write(data)


ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
