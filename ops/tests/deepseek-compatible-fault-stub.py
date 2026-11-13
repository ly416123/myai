#!/usr/bin/env python3
"""Deterministic DeepSeek-compatible endpoint for controlled worker recovery drills."""

from __future__ import annotations

import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class FaultDrillServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], first_request_delay: float):
        super().__init__(address, FaultDrillHandler)
        self.first_request_delay = first_request_delay
        self.state_lock = threading.Lock()
        self.request_count = 0
        self.active_requests = 0

    def snapshot(self) -> dict[str, int]:
        with self.state_lock:
            return {"requestsReceived": self.request_count, "activeRequests": self.active_requests}


class FaultDrillHandler(BaseHTTPRequestHandler):
    server: FaultDrillServer

    def log_message(self, _format: str, *_args: object) -> None:
        # The base handler logs URL, headers may be added by subclasses, and client data.
        # Keep this test service deliberately silent about request content and credentials.
        return

    def do_GET(self) -> None:
        if self.path != "/healthz":
            self.send_error(404)
            return
        self._send_json(200, self.server.snapshot())

    def do_POST(self) -> None:
        if self.path != "/v1/chat/completions":
            self.send_error(404)
            return
        if self.headers.get("Authorization") != "Bearer myai-fault-drill-only":
            self.send_error(401)
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.send_error(400)
            return
        if length < 0 or length > 2_000_000:
            self.send_error(413)
            return
        self.rfile.read(length)  # Consume but never parse, retain, or log the synthetic request body.

        with self.server.state_lock:
            self.server.request_count += 1
            request_number = self.server.request_count
            self.server.active_requests += 1

        print(f"provider_stub=request_received requestNumber={request_number}", flush=True)
        try:
            if request_number == 1:
                time.sleep(self.server.first_request_delay)
            response = {
                "id": f"fault-drill-{request_number}",
                "object": "chat.completion",
                "created": int(time.time()),
                "model": "deepseek-flash",
                "choices": [{
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": "Controlled worker recovery drill completed.",
                    },
                    "finish_reason": "stop",
                }],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
            }
            self._send_json(200, response)
        except (BrokenPipeError, ConnectionResetError):
            print(f"provider_stub=client_disconnected requestNumber={request_number}", flush=True)
        finally:
            with self.server.state_lock:
                self.server.active_requests -= 1

    def _send_json(self, status: int, value: dict[str, int] | dict[str, object]) -> None:
        body = json.dumps(value, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    host = os.environ.get("MYAI_FAULT_STUB_HOST", "0.0.0.0")
    port = int(os.environ.get("MYAI_FAULT_STUB_PORT", "18081"))
    delay = float(os.environ.get("MYAI_FAULT_STUB_FIRST_REQUEST_DELAY_SECONDS", "90"))
    if delay < 1 or delay > 600:
        raise SystemExit("first request delay must be between 1 and 600 seconds")
    server = FaultDrillServer((host, port), delay)
    print(f"provider_stub=ready port={port} firstRequestDelaySeconds={int(delay)}", flush=True)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    try:
        main()
    except OSError as failure:
        # Print only the exception type; host paths and user data are irrelevant to this test.
        print(f"provider_stub=start_failed exceptionType={type(failure).__name__}", file=sys.stderr)
        raise SystemExit(1) from None
