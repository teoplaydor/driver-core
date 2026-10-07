"""Minimal Hugging Face Hub stand-in for HfRepoTest: model info API + resolve with Range,
and a one-time mid-file disconnect to exercise resumable downloads.

usage: python3 mock_hub.py <port>
"""
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

FILES = {
    "config.json": 300, "tokenizer.json": 40_000, "tokenizer_config.json": 500, "processor_config.json": 200,
    "preprocessor_config.json": 60, "README.md": 100,
    "onnx/model.onnx": 9000, "onnx/model.onnx_data": 90_000, "onnx/model_fp16.onnx": 9000,
    "onnx/model_q4.onnx": 5000, "onnx/model_q4.onnx_data": 300_000, "onnx/model_q4.onnx_data_1": 120_000,
    "onnx/model_q4f16.onnx": 5000, "onnx/vision_encoder.onnx": 8000, "onnx/vision_encoder_q4.onnx": 150_000,
    "onnx/audio_encoder_q4.onnx": 70_000,
}
REPO = "onnx-community/embeddinggemma-2-ONNX"
cut_once = {"onnx/model_q4.onnx_data"}


def content(name, size):
    seed = sum(name.encode())
    return bytes((seed + i * 31) % 251 for i in range(size))


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path.startswith("/api/models/"):
            repo = self.path[len("/api/models/"):].split("?")[0]
            if repo != REPO:
                self.send_response(401)
                self.end_headers()
                return
            body = json.dumps({"id": REPO, "siblings": [
                {"rfilename": n, "size": s, **({"lfs": {"size": s}} if s > 10000 else {})} for n, s in FILES.items()]})
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body.encode())
            return
        prefix = f"/{REPO}/resolve/main/"
        if self.path.startswith(prefix):
            name = self.path[len(prefix):]
            data = content(name, FILES[name])
            start = 0
            rng = self.headers.get("Range")
            if rng:
                start = int(rng.split("=")[1].split("-")[0])
                self.send_response(206)
                self.send_header("Content-Range", f"bytes {start}-{len(data) - 1}/{len(data)}")
            else:
                self.send_response(200)
            self.send_header("Content-Length", str(len(data) - start))
            self.end_headers()
            if name in cut_once and start == 0:
                cut_once.discard(name)
                self.wfile.write(data[: len(data) // 3])
                self.wfile.flush()
                self.connection.shutdown(2)
                return
            self.wfile.write(data[start:])
            return
        self.send_response(404)
        self.end_headers()


ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
