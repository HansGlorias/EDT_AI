"""Локальный MCP для S13/S15: потеря сессии или управляемое ожидание; без доступа к проекту."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading

SESSION = "fixture-session"
READY = threading.Event()
LOCK = threading.Lock()
CALLS = 0


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def send_json(self, value, status=200, session=False):
        data = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        if session:
            self.send_header("Mcp-Session-Id", SESSION)
        self.end_headers()
        try:
            self.wfile.write(data)
        except OSError:
            pass  # Отменённый клиент уже мог закрыть соединение.

    def do_DELETE(self):
        self.send_json({})

    def do_POST(self):
        global CALLS
        if self.path != "/mcp":
            self.send_json({}, 404)
            return
        request = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        method = request.get("method")
        print(json.dumps({"method": method}, ensure_ascii=False), flush=True)
        if method == "notifications/initialized":
            self.send_json({}, 202)
            return
        identifier = request.get("id")
        session = method == "initialize"
        if session:
            result = {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}},
                      "serverInfo": {"name": "Scenario Fixture", "version": "1"}}
        elif self.headers.get("Mcp-Session-Id") != SESSION:
            self.send_json({}, 404)
            return
        elif method == "tools/list":
            name = "fixture_read" if self.server.mode == "expire" else "fixture_wait"
            result = {"tools": [{"name": name, "description": "Фиксированное безопасное чтение для сценария",
                                "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
                                "annotations": {"readOnlyHint": True}}]}
        elif method == "tools/call":
            name = "fixture_read" if self.server.mode == "expire" else "fixture_wait"
            if request.get("params", {}).get("name") != name:
                self.send_json({"jsonrpc": "2.0", "id": identifier,
                                "error": {"code": -32602, "message": "Unknown tool"}})
                return
            with LOCK:
                CALLS += 1
                attempt = CALLS
            print(json.dumps({"tool": name, "attempt": attempt}), flush=True)
            if self.server.mode == "expire" and attempt == 1:
                self.send_json({"error": "expired fixture session"}, 404)
                return
            if self.server.mode == "wait" and attempt == 1:
                print("WAITING: нажмите Стоп в EDT, затем Enter здесь", flush=True)
                READY.wait()
            result = {"content": [{"type": "text", "text": "marker=готово-314"}]}
        else:
            self.send_json({"jsonrpc": "2.0", "id": identifier,
                            "error": {"code": -32601, "message": "Method not found"}})
            return
        self.send_json({"jsonrpc": "2.0", "id": identifier, "result": result}, session=session)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["expire", "wait"])
    parser.add_argument("--port", type=int, default=0)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    server.mode = args.mode
    print(f"http://localhost:{server.server_port}/mcp", flush=True)
    if args.mode == "wait":
        def release():
            try:
                input()
            except EOFError:
                return
            READY.set()
        threading.Thread(target=release, daemon=True).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        READY.set()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
