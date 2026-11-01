"""A loopback-only browser desk for one Mika phone, using the existing CLI transport."""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib.resources import files
import json
import os
import re
import secrets
import sys
import threading
import webbrowser

from mika_cli import Adb, CliError, connection, execute, recent_tasks, redact


ASSETS = {"/": ("index.html", "text/html"), "/desk.css": ("desk.css", "text/css"),
          "/desk.js": ("desk.js", "text/javascript")}
FIELDS = {"overview": set(), "select": {"device"}, "run": {"goal"}, "status": {"task_id"}, "cancel": {"task_id"},
          "answer": {"task_id", "text"}, "steer": {"task_id", "text"}}


def command(data):
    if not isinstance(data, dict) or not all(isinstance(value, str) for value in data.values()):
        raise ValueError
    action = data.get("action")
    if action not in FIELDS or data.keys() != FIELDS[action] | {"action"}:
        raise ValueError
    if "task_id" in data and not re.fullmatch(r"[A-Za-z0-9_-]+", data["task_id"]):
        raise ValueError
    return action


class Desk(ThreadingHTTPServer):
    def __init__(self, args):
        super().__init__(("127.0.0.1", 0), Handler)
        self.args = args
        if not args.url and not args.device:
            args.device = os.environ.get("ANDROID_SERIAL")
        self.lock = threading.Lock()
        self.key = secrets.token_urlsafe(32)
        self.host = f"127.0.0.1:{self.server_port}"
        self.origin = "http://" + self.host

    def pin_target(self, choice):
        # A session owns one target even across disconnects and concurrent tabs.
        with self.lock:
            if self.args.url or self.args.device:
                if choice is not None and choice != self.args.device:
                    raise CliError("device_pinned", "The desk already has a target.", http_status=409)
                return
            online = [d["device"] for d in Adb(self.args.adb).devices() if d["state"] == "device"]
            if choice is None and len(online) == 1:
                choice = online[0]
            if choice not in online:
                raise CliError("device_selection", "Choose an online phone.", devices=online, http_status=409)
            self.args.device = choice

    def dispatch(self, action, data, sensitive):
        self.pin_target(data.get("device"))
        if action in {"overview", "select"}:
            with connection(self.args, sensitive) as (client, transport):
                info = client.discover()
                tasks = recent_tasks(client, 50)["tasks"]
                device = {}
                if "device_state" in info["capabilities"]:
                    try:
                        device = client.request("GET", "/device").get("device") or {}
                    except CliError:
                        # The optional screen/helper status can be unavailable on a
                        # phone that just returned a valid list of tasks.
                        pass
                return {"target": transport.get("device") or self.args.url,
                        "transport": transport["transport"], "server_version": info.get("server_version"),
                        "capabilities": info["capabilities"], "device": device, "tasks": tasks}

        args = argparse.Namespace(**{**vars(self.args), **data, "command": "task",
                                     "goal_file": None, "text_file": None, "conversation": None})
        return execute(args, sensitive)


class Handler(BaseHTTPRequestHandler):
    timeout = 10

    def log_message(self, *args):
        pass  # A desk's local session and phone activity do not belong in logs.

    def send(self, status, content, mime="text/plain"):
        self.send_response(status)
        self.send_header("Content-Type", mime + "; charset=utf-8")
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; "
                         "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
        self.end_headers()
        try:
            self.wfile.write(content)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Closing the page does not undo or repeat a phone operation.

    def do_GET(self):
        if self.headers.get("Host") != self.server.host:
            return self.send(403, b"Invalid host")
        asset = ASSETS.get(self.path)
        if not asset:
            return self.send(404, b"Not found")
        name, mime = asset
        self.send(200, files(__package__).joinpath(name).read_bytes(), mime)

    def do_POST(self):
        if (self.headers.get("Host") != self.server.host or self.headers.get("Origin") != self.server.origin
                or not secrets.compare_digest(self.headers.get("X-Mika-Desk", "").encode(), self.server.key.encode())):
            return self.send(403, b"Open the private link from mika ui")
        if self.path != "/desk":
            return self.send(404, b"Not found")
        if self.headers.get_content_type() != "application/json":
            return self.send(415, b"Expected JSON")
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 64 * 1024:
                return self.send(413, b"Invalid body size")
            data = json.loads(self.rfile.read(size))
            action = command(data)
            for value in data.values():
                value.encode("utf-8")  # Reject unpaired surrogates before contacting the phone.
        except (ValueError, RecursionError):
            return self.send(400, b"Invalid desk command")

        sensitive = []
        try:
            result = self.server.dispatch(action, data, sensitive)
            status = 200
        except CliError as exc:
            result = {"error": {"code": exc.code, "message": str(exc), **exc.details}}
            status = exc.details.get("http_status", 502)
            if not 400 <= status <= 599:
                status = 502
        self.send(status, json.dumps(redact(result, sensitive)).encode(), "application/json")


def serve(args):
    try:
        server = Desk(args)
    except OSError as exc:
        print(f"Could not start the local Mika desk: {exc}", file=sys.stderr)
        return 2
    with server:
        # The fragment never travels in HTTP requests and is a desk session key,
        # independent of the phone's own access token.
        link = server.origin + "/#" + server.key
        if args.no_open or not webbrowser.open(link):
            print(link, flush=True)
        print("Mika task desk is open locally. Press Ctrl+C to close it.", file=sys.stderr, flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
    return 0
