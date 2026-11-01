#!/usr/bin/env python3
"""Mika's task API client. Standard library only; usable outside the source tree."""

import argparse
import contextlib
import http.client
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

VERSION = "0.1.0"
API = "/api/v1"
PHONE_FILES = "/sdcard/Android/data/com.wangzan101.mika/files"
INFO_FILE = PHONE_FILES + "/mcp-usb.json"
CONNECT_SCRIPT = PHONE_FILES + "/connect-computer.sh"
MAX_RESPONSE = 4 * 1024 * 1024
HANDOFF = {"waiting_for_answer", "waiting_on_phone"}
TERMINAL = {"done", "failed", "cancelled"}
STATUSES = TERMINAL | HANDOFF | {"starting", "running", "paused"}


class CliError(Exception):
    def __init__(self, code, message, exit_code=2, **details):
        super().__init__(message)
        self.code, self.exit_code, self.details = code, exit_code, details


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise CliError("arguments", message)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def clean_token(value):
    if not isinstance(value, str) or not value or not re.fullmatch(r"[A-Za-z0-9._~+/=-]+", value):
        raise CliError("credentials", "The connection token is missing or invalid; reconnect to Mika.")
    return value


def redact(value, secrets):
    if isinstance(value, str):
        for secret in secrets:
            value = value.replace(secret, "[redacted]")
        return value
    if isinstance(value, dict):
        return {k: redact(v, secrets) for k, v in value.items()}
    if isinstance(value, list):
        return [redact(v, secrets) for v in value]
    return value


class Client:
    def __init__(self, base, token, timeout=15):
        self.base, self.token, self.timeout = base, token, timeout
        # Local/private phone traffic should not be sent through an ambient HTTP proxy.
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(self, method, path="", body=None, *, mutation=False, timeout=None, task_id=None):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(self.base + path, data=data, method=method, headers={
            "Authorization": "Bearer " + self.token,
            "Accept": "application/json", "Content-Type": "application/json",
            "X-Mika-Client": "mika-cli/" + VERSION,
        })
        try:
            with self.opener.open(req, timeout=timeout or self.timeout) as response:
                raw = response.read(MAX_RESPONSE + 1)
                if len(raw) > MAX_RESPONSE or response.headers.get_content_type() != "application/json":
                    raise ValueError("invalid response")
                result = json.loads(raw)
                if not isinstance(result, dict) or "error" in result:
                    raise ValueError("invalid response")
                return result
        except urllib.error.HTTPError as exc:
            # Any server-side failure after a write may have happened after acceptance.
            if mutation and (exc.code >= 500 or 300 <= exc.code < 400):
                exc.close()
                self.unknown(task_id)
            message, code = "The phone rejected the request (HTTP %s)." % exc.code, "http_error"
            try:
                payload = json.loads(exc.read(MAX_RESPONSE))
                error = payload.get("error", {})
                if isinstance(error, dict) and isinstance(error.get("message"), str):
                    message = error["message"]
                if isinstance(error, dict) and isinstance(error.get("code"), str):
                    code = error["code"]
            except (ValueError, OSError, AttributeError):
                pass
            finally:
                exc.close()
            raise CliError(code, message, 1, http_status=exc.code) from None
        except (urllib.error.URLError, OSError, http.client.HTTPException, ValueError):
            if mutation:
                self.unknown(task_id)
            raise CliError("connection", "Could not read a valid response from Mika; check the connection and run mika connect.") from None
        except KeyboardInterrupt:
            if mutation:
                self.unknown(task_id)
            raise

    @staticmethod
    def unknown(task_id=None):
        details = {"task_id": task_id} if task_id else {}
        raise CliError("outcome_unknown", "The phone may have accepted the operation, but its response was not received. "
                       "It was not retried. Check task status or task list before submitting again.", 4, **details)

    def discover(self):
        try:
            info = self.request("GET")
        except CliError as exc:
            if exc.details.get("http_status") == 404:
                raise CliError("incompatible_api", "This Mika APK does not provide task API v1. Install a build with CLI support; no MCP fallback is used.") from None
            raise
        capabilities = info.get("capabilities")
        if (type(info.get("api_version")) is not int or info["api_version"] != 1
                or not isinstance(capabilities, list) or "tasks" not in capabilities):
            raise CliError("incompatible_api", "The phone does not advertise a compatible Mika task API v1.")
        return info


class Adb:
    def __init__(self, executable="adb", device=None):
        self.executable, self.device = executable, device

    def run(self, *args, timeout=20):
        prefix = [self.executable] + (["-s", self.device] if self.device else [])
        try:
            result = subprocess.run(prefix + list(args), capture_output=True, text=True, encoding="utf-8", timeout=timeout)
        except FileNotFoundError:
            raise CliError("adb_unavailable", "adb was not found. Install Android platform-tools or provide --adb.") from None
        except (OSError, subprocess.TimeoutExpired, UnicodeError):
            raise CliError("adb", "ADB did not complete; check the selected phone's connection.") from None
        if result.returncode:
            # The official bootstrap script may print a token, including on failure.
            raise CliError("adb", "ADB could not complete the operation. Check the selected device; open Mika and run mika connect.")
        return result.stdout.strip()

    def devices(self):
        devices = []
        for line in self.run("devices").splitlines():
            # ADB uses a tab after the serial. Wireless service names can contain spaces.
            columns = line.split("\t", 1) if "\t" in line else line.split(maxsplit=1)
            if len(columns) == 2 and columns[0] != "List" and columns[1].strip():
                devices.append({"device": columns[0], "state": columns[1].split()[0]})
        return devices

    def select(self):
        if not self.device:
            online = [d["device"] for d in self.devices() if d["state"] == "device"]
            if len(online) != 1:
                raise CliError("device_selection", "Expected one online phone. Run mika devices and choose --device SERIAL.")
            self.device = online[0]
        if self.run("get-state") != "device":
            raise CliError("device_offline", "The selected phone is not online; the CLI will not switch devices.")


def base_url(raw):
    try:
        url = urllib.parse.urlsplit(raw)
        valid = (not any(ord(c) < 32 for c in raw) and url.scheme in {"http", "https"} and url.hostname and url.port != 0
                 and url.username is None and url.password is None and not url.query and not url.fragment
                 and url.path.rstrip("/") in {"", API})
    except ValueError:
        valid = False
    if not valid:
        raise CliError("arguments", "--url must be an HTTP(S) origin or /api/v1 URL, without credentials, query or fragment.")
    return urllib.parse.urlunsplit((url.scheme, url.netloc, API, "", ""))


@contextlib.contextmanager
def connection(args, secrets):
    if args.url:
        if args.device or not args.token_file:
            raise CliError("arguments", "Use --url with --token-file, without --device.")
        base = base_url(args.url)
        try:
            token = clean_token(Path(args.token_file).read_text(encoding="utf-8").strip())
        except (OSError, UnicodeError):
            raise CliError("credentials", "Could not read the token file.") from None
        secrets.append(token)
        yield Client(base, token, args.request_timeout), {"transport": "http"}
        return
    if args.token_file:
        raise CliError("arguments", "--token-file requires --url; ADB reads the current token from the phone.")
    adb = Adb(args.adb, args.device or os.environ.get("ANDROID_SERIAL"))
    adb.select()
    if args.command == "connect":
        adb.run("shell", "sh", CONNECT_SCRIPT, timeout=60)
    try:
        info = json.loads(adb.run("shell", "cat", INFO_FILE))
        if not isinstance(info, dict):
            raise ValueError()
    except ValueError:
        raise CliError("connection_info", "Mika's connection information is invalid; run mika connect.") from None
    token = clean_token(info.get("token"))
    secrets.append(token)
    boot = adb.run("shell", "settings", "get", "global", "boot_count")
    if not boot.isdigit() or info.get("boot") != boot:
        raise CliError("stale_connection", "The phone has rebooted; run mika connect to obtain current connection information.")
    if type(info.get("api_version")) is not int or info["api_version"] != 1:
        raise CliError("incompatible_api", "This Mika APK does not advertise task API v1; install a build with CLI support.")
    port = info.get("port")
    if type(port) is not int or not 1 <= port <= 65535:
        raise CliError("connection_info", "Mika's advertised port is invalid.")
    local = adb.run("forward", "tcp:0", "tcp:" + str(port))
    if not local.isascii() or not local.isdigit() or not 1 <= int(local) <= 65535:
        raise CliError("adb", "ADB did not return a valid temporary forwarding port.")
    try:
        yield Client("http://127.0.0.1:" + local + API, token, args.request_timeout), {"transport": "adb", "device": adb.device}
    finally:
        # Never remove all forwards or a mapping created by another invocation.
        try:
            adb.run("forward", "--remove", "tcp:" + local)
        except CliError:
            print("mika: could not confirm removal of the temporary ADB forward on port " + local + ".", file=sys.stderr)


def text_input(text, filename, label):
    if filename is not None:
        try:
            text = sys.stdin.read() if filename == "-" else Path(filename).read_text(encoding="utf-8")
        except (OSError, UnicodeError):
            raise CliError("input", "Could not read the " + label + " file as UTF-8.") from None
    if not text or not text.strip():
        raise CliError("input", label + " must not be empty.")
    return text


def task_snapshot(result, client, *, mutation=False, task_id=None):
    if (not isinstance(result.get("task_id"), str) or not result["task_id"]
            or not isinstance(result.get("status"), str) or result["status"] not in STATUSES):
        if mutation:
            client.unknown(task_id)
        raise CliError("protocol", "Mika returned an invalid task snapshot.")
    if task_id is not None and result["task_id"] != task_id:
        raise CliError("protocol", "Mika returned a different task ID.")
    return result


def recent_tasks(client, limit):
    result = client.request("GET", "/tasks?limit=" + str(limit))
    if not isinstance(result.get("tasks"), list):
        raise CliError("protocol", "Mika returned an invalid task list.")
    for task in result["tasks"]:
        if not isinstance(task, dict):
            raise CliError("protocol", "Mika returned an invalid task list.")
        task_snapshot(task, client)
    return result


def execute(args, secrets):
    # Validate inputs before opening a connection or invoking the bootstrap script.
    if args.command == "task":
        if args.action == "run":
            args.goal = text_input(args.goal, args.goal_file, "goal")
        if args.action in {"answer", "steer"}:
            args.text = text_input(args.text, args.text_file, args.action)
        if hasattr(args, "task_id") and not re.fullmatch(r"[A-Za-z0-9_-]+", args.task_id):
            raise CliError("arguments", "task_id must be an ID returned by Mika.")
    if args.command == "devices":
        if args.url or args.token_file:
            raise CliError("arguments", "devices lists ADB devices; it does not use --url or --token-file.")
        return {"devices": Adb(args.adb).devices()}
    with connection(args, secrets) as (client, transport):
        info = client.discover()
        if args.command == "connect":
            return {"connected": True, **transport, "api_version": info["api_version"], "server_version": info.get("server_version")}
        if args.command == "doctor":
            device = client.request("GET", "/device") if "device_state" in info.get("capabilities", []) else {"status": "unsupported"}
            return {"connection": "ok", **transport, "api_version": 1, "device_state": device, "model_configuration": "unverified"}
        action = args.action
        if action == "steer" and "task_steer" not in info.get("capabilities", []):
            raise CliError("incompatible_api", "This Mika APK does not support task steering; update the phone app.")
        path = "/tasks" + ("/" + args.task_id if hasattr(args, "task_id") else "")
        if action == "run":
            body = {"goal": args.goal}
            if args.conversation is not None:
                body["conversation"] = args.conversation
            return task_snapshot(client.request("POST", path, body, mutation=True), client, mutation=True)
        if action == "list":
            return recent_tasks(client, args.limit)
        if action in {"status", "wait"}:
            result = task_snapshot(client.request("GET", path), client, task_id=args.task_id)
            if action == "status":
                return result
            if result["status"] in HANDOFF | TERMINAL:
                return {**result, "changed": False}
            return task_snapshot(client.request("POST", path + "/wait", {"timeout_s": args.timeout},
                                               timeout=max(client.timeout, args.timeout + 5)), client, task_id=args.task_id)
        body = {"text": args.text} if action in {"answer", "steer"} else {}
        result = client.request("POST", path + "/" + action, body, mutation=True, task_id=args.task_id)
        if result.get("task_id") != args.task_id or type(result.get("accepted")) is not bool:
            client.unknown(args.task_id)
        if action == "steer" and (result["accepted"] is not True or
                                  not isinstance(result.get("message_id"), str) or not result["message_id"]):
            client.unknown(args.task_id)
        return result


def bounded_int(low, high):
    def parse(value):
        try:
            number = int(value)
        except ValueError:
            raise argparse.ArgumentTypeError("must be an integer") from None
        if not low <= number <= high:
            raise argparse.ArgumentTypeError("must be between %s and %s" % (low, high))
        return number
    return parse


def request_timeout(value):
    try:
        number = float(value)
    except ValueError:
        raise argparse.ArgumentTypeError("must be a number") from None
    if not math.isfinite(number) or not 1 <= number <= 120:
        raise argparse.ArgumentTypeError("must be between 1 and 120 seconds")
    return number


def parser():
    common = Parser(add_help=False)
    for flag, help_text in [
        ("--device", "ADB serial; required when more than one phone is online"),
        ("--url", "Phone HTTP origin or /api/v1 URL; bypasses ADB"),
        ("--token-file", "UTF-8 file containing the token for --url"),
        ("--adb", "ADB executable (default: adb)"),
    ]:
        common.add_argument(flag, help=help_text, default=argparse.SUPPRESS)
    common.add_argument("--json", action="store_true", default=argparse.SUPPRESS, help="Compact JSON output, including errors")
    common.add_argument("--request-timeout", type=request_timeout, default=argparse.SUPPRESS, help="HTTP timeout in seconds (default: 15; bounded waits get at least timeout + 5)")
    p = Parser(prog="mika", description="Delegate tasks to Mika on Android using task API v1.", parents=[common])
    p.add_argument("--version", action="version", version="mika " + VERSION)
    sub = p.add_subparsers(dest="command", required=True)
    for name, help_text in [("connect", "Enable ADB computer access and verify task API"),
                            ("doctor", "Read connection and phone state without starting a task"),
                            ("devices", "List ADB devices")]:
        sub.add_parser(name, help=help_text, parents=[common])
    ui = sub.add_parser("ui", help="Open the phone owner's local task desk", parents=[common])
    ui.add_argument("--no-open", action="store_true", help="Print the desk URL instead of opening a browser")
    task = sub.add_parser("task", help="Run and inspect phone tasks", parents=[common])
    actions = task.add_subparsers(dest="action", required=True)
    run = actions.add_parser("run", parents=[common], help="Submit once; return the task ID immediately")
    goal = run.add_mutually_exclusive_group(required=True)
    goal.add_argument("--goal")
    goal.add_argument("--goal-file", help="UTF-8 file, or - for stdin")
    run.add_argument("--conversation", help="Continue a conversation returned by a previous remote task")
    for name in ["status", "wait", "answer", "steer", "cancel"]:
        cmd = actions.add_parser(name, parents=[common])
        cmd.add_argument("task_id")
        if name == "wait":
            cmd.add_argument("--timeout", type=bounded_int(1, 50), default=30, help="One bounded wait, 1–50 seconds; never cancels the task")
        if name in {"answer", "steer"}:
            answer = cmd.add_mutually_exclusive_group(required=True)
            answer.add_argument("--text")
            answer.add_argument("--text-file", help="UTF-8 file, or - for stdin")
    listing = actions.add_parser("list", parents=[common])
    listing.add_argument("--limit", type=bounded_int(1, 50), default=10)
    return p


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    secrets, code = [], 0
    compact = "--json" in argv
    try:
        args = parser().parse_args(argv)
        for key, default in {"device": None, "url": None, "token_file": None, "adb": "adb", "request_timeout": 15}.items():
            if not hasattr(args, key):
                setattr(args, key, default)
        if args.command == "ui":
            if compact:
                raise CliError("arguments", "The desk is interactive. Use mika ui without --json.")
            if bool(args.url) != bool(args.token_file) or (args.url and args.device):
                raise CliError("arguments", "Choose one ADB device or use --url with --token-file.")
            if args.url:
                base_url(args.url)
            try:
                from mika_desk import serve
            except ModuleNotFoundError:
                # A copied Skill folder run as `python3 -I .../mika_cli.py` leaves this folder off sys.path.
                sys.path.append(str(Path(__file__).resolve().parent))
                from mika_desk import serve
            return serve(args)
        result = execute(args, secrets)
        if isinstance(result.get("status"), str) and result["status"] in {"failed", "cancelled"}:
            code = 1
    except CliError as exc:
        result = {"error": {"code": exc.code, "message": str(exc), **exc.details}}
        code = exc.exit_code
    except KeyboardInterrupt:
        result = {"error": {"code": "interrupted", "message": "Stopped waiting locally; no task cancellation was requested."}}
        code = 130
    output = json.dumps(redact(result, secrets), ensure_ascii=False, separators=(",", ":") if compact else None, indent=None if compact else 2)
    print(output, file=sys.stdout if compact or code == 0 else sys.stderr)
    return code


if __name__ == "__main__":
    sys.exit(main())
