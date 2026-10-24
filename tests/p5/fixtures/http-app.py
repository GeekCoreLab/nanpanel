#!/usr/bin/env python3
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock

version = os.environ.get("APP_VERSION", "v1")
broken = os.environ.get("APP_BROKEN", "false").lower() == "true"
application = os.environ.get("NANPANEL_APPLICATION_NAME", "p5-demo")
counts = {"success": 0, "error": 0}
lock = Lock()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/metrics":
            with lock:
                body = (
                    "# TYPE nanpanel_http_requests_total counter\n"
                    f'nanpanel_http_requests_total{{app="{application}",version="{version}",outcome="success"}} {counts["success"]}\n'
                    f'nanpanel_http_requests_total{{app="{application}",version="{version}",outcome="error"}} {counts["error"]}\n'
                )
            self.respond(200, body, "text/plain; version=0.0.4")
            return

        status = 503 if broken else 200
        outcome = "error" if status >= 500 else "success"
        with lock:
            counts[outcome] += 1
        self.respond(status, f"{version}\n", "text/plain; charset=utf-8")

    def respond(self, status, body, content_type):
        data = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, _format, *_args):
        return


ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
