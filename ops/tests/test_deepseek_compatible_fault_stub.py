import contextlib
import importlib.util
import io
import json
import threading
import time
import unittest
from http.client import HTTPConnection
from pathlib import Path
from urllib.request import urlopen


STUB_PATH = Path(__file__).with_name("deepseek-compatible-fault-stub.py")
SPEC = importlib.util.spec_from_file_location("myai_fault_stub", STUB_PATH)
assert SPEC is not None and SPEC.loader is not None
STUB = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(STUB)


class FaultStubTest(unittest.TestCase):
    def test_stalled_first_request_does_not_block_recovery_and_logs_no_payload(self):
        server = STUB.FaultDrillServer(("127.0.0.1", 0), 0.2)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        host, port = server.server_address
        output = io.StringIO()

        def post() -> tuple[int, dict[str, object]]:
            connection = HTTPConnection(host, port, timeout=3)
            body = b'{"messages":[{"content":"no-log-this-prompt"}]}'
            connection.request("POST", "/v1/chat/completions", body=body, headers={
                "Authorization": "Bearer myai-fault-drill-only",
                "Content-Type": "application/json",
            })
            response = connection.getresponse()
            payload = json.loads(response.read())
            connection.close()
            return response.status, payload

        try:
            with contextlib.redirect_stdout(output):
                first_result: list[tuple[int, dict[str, object]]] = []
                first = threading.Thread(target=lambda: first_result.append(post()), daemon=True)
                first.start()
                deadline = time.monotonic() + 2
                while server.snapshot()["activeRequests"] != 1 and time.monotonic() < deadline:
                    time.sleep(0.01)
                self.assertEqual(1, server.snapshot()["activeRequests"])

                self.assertEqual(200, post()[0])
                first.join(timeout=3)
                self.assertFalse(first.is_alive())
                self.assertEqual(200, first_result[0][0])

            self.assertEqual({"requestsReceived": 2, "activeRequests": 0}, server.snapshot())
            self.assertNotIn("myai-fault-drill-only", output.getvalue())
            self.assertNotIn("no-log-this-prompt", output.getvalue())
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
