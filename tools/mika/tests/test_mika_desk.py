"""Exercise the installed-style CLI entry and its browser boundary against the phone API."""
from argparse import Namespace
from contextlib import contextmanager
import http.client
import json
import os
import socket
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import urlsplit

from test_mika_cli import api, DISCOVERY, SCRIPT, SNAPSHOT, TOKEN


@contextmanager
def desk(*args, env=None):
    with subprocess.Popen([sys.executable, str(SCRIPT), "ui", "--no-open", *args], stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, text=True, env=env) as proc:
        try:
            url = urlsplit(proc.stdout.readline().strip())
            if url.hostname != "127.0.0.1" or bool(url.fragment) == ("--port" in args):
                raise AssertionError("Desk did not print a local session link")
            yield url
        finally:
            proc.terminate()
            proc.wait(timeout=5)


def request(url, data=None, *, path="/desk", headers=None):
    origin = "http://" + url.netloc
    request_headers = {"Origin": origin, "X-Mika-Desk": url.fragment, "Content-Type": "application/json"}
    request_headers.update(headers or {})
    conn = http.client.HTTPConnection(url.hostname, url.port, timeout=5)
    try:
        conn.request("GET" if data is None else "POST", path, json.dumps(data) if data is not None else None,
                     request_headers)
        response = conn.getresponse()
        payload = response.read().decode()
        if TOKEN in payload:
            raise AssertionError("Phone token exposed in desk response")
        if response.getheader("Content-Type", "").startswith("application/json"):
            payload = json.loads(payload)
        return response.status, payload
    finally:
        conn.close()


class DeskTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.token = Path(temp.name) / "credential"
        self.token.write_text(TOKEN)

    def launch(self, phone):
        return desk("--url", f"http://127.0.0.1:{phone.server_port}", "--token-file", str(self.token))

    def test_page_and_task_operations_share_the_existing_service(self):
        def respond(method, path, body):
            if path.endswith("/tasks?limit=50"):
                return 200, {"tasks": [SNAPSHOT]}, {}
            if path.endswith(("/answer", "/cancel")):
                return 202, {"task_id": "t1", "accepted": True}, {}
            if path.endswith("/steer"):
                return 202, {"task_id": "t1", "accepted": True, "message_id": "s1"}, {}

        with api(respond) as phone, self.launch(phone) as url:
            self.assertIn("电脑任务台", request(url, path="/")[1])
            for path in ["/desk.css", "/desk.js"]:
                self.assertEqual(200, request(url, path=path)[0])
            code, overview = request(url, {"action": "overview"})
            self.assertEqual(200, code)
            self.assertEqual([SNAPSHOT], overview["tasks"])
            self.assertIn("task_steer", overview["capabilities"])
            self.assertEqual({}, overview["device"])
            self.assertEqual((200, SNAPSHOT), request(url, {"action": "run", "goal": "中文任务"}))
            self.assertEqual((200, SNAPSHOT), request(url, {"action": "status", "task_id": "t1"}))
            for action in ["answer", "steer", "cancel"]:
                fields = {} if action == "cancel" else {"text": "补充"}
                code, receipt = request(url, {"action": action, "task_id": "t1", **fields})
                self.assertEqual(200, code)
                self.assertTrue(receipt["accepted"])
                self.assertNotIn("status", receipt)
            self.assertEqual(["/api/v1/tasks", "/api/v1/tasks/t1/answer", "/api/v1/tasks/t1/steer",
                              "/api/v1/tasks/t1/cancel"], [path for method, path, _ in phone.calls if method == "POST"])

    def test_watch_returns_recent_tasks_after_a_bounded_read_even_if_the_watched_id_expired(self):
        for wait_status in (200, 404):
            def respond(method, path, body):
                if path.endswith("/wait"):
                    return wait_status, {**SNAPSHOT, "changed": False}, {}
                if path.endswith("/tasks?limit=50"):
                    return 200, {"tasks": [] if wait_status == 404 else [SNAPSHOT]}, {}

            with self.subTest(wait_status=wait_status), api(respond) as phone, self.launch(phone) as url:
                code, view = request(url, {"action": "watch", "task_id": "t1"})
                self.assertEqual(200, code)
                self.assertEqual([] if wait_status == 404 else [SNAPSHOT], view["tasks"])
                self.assertEqual(["/api/v1", "/api/v1/tasks/t1/wait", "/api/v1/tasks?limit=50"],
                                 [path for _, path, _ in phone.calls])
                method, _, bound = phone.calls[1]
                self.assertEqual("POST", method)
                self.assertTrue(1 <= bound["timeout_s"] <= 5)

    def test_browser_session_origin_host_and_command_allowlist(self):
        with api() as phone, self.launch(phone) as url:
            command = {"action": "run", "goal": "no"}
            for headers in [{"X-Mika-Desk": ""}, {"Origin": "https://other.invalid"}, {"Origin": "null"},
                            {"Host": "other.invalid"}, {"Content-Type": "text/plain"}]:
                self.assertIn(request(url, command, headers=headers)[0], (403, 415))
            for data in [{**command, "url": "http://other.invalid"}, {**command, "goal_file": "/etc/hosts"},
                         {"action": "connect"}, {"action": "status", "task_id": "../device"}]:
                self.assertEqual(400, request(url, data)[0])
            self.assertEqual(404, request(url, command, path="/mcp")[0])
            self.assertEqual(403, request(url, path="/", headers={"Host": "other.invalid"})[0])
            self.assertEqual([], phone.calls)

    @unittest.skipUnless(sys.platform == "darwin", "same-user fixed address is a macOS option")
    def test_fixed_address_opens_without_fragment_and_still_requires_a_session(self):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        with api() as phone, desk("--port", str(port), "--url", f"http://127.0.0.1:{phone.server_port}",
                                  "--token-file", str(self.token)) as url:
            self.assertEqual(port, url.port)
            self.assertFalse(url.fragment)
            self.assertIn("电脑任务台", request(url, path="/")[1])
            for source in [{"Origin": "https://other.invalid"}, {"Host": "other.invalid"}]:
                headers = {"X-Mika-Desk": "open", **source}
                self.assertEqual(403, request(url, {}, path="/session", headers=headers)[0])
            self.assertEqual(403, request(url, {"action": "run", "goal": "blocked"})[0])
            self.assertEqual([], phone.calls)
            code, session = request(url, {}, path="/session", headers={"X-Mika-Desk": "open"})
            self.assertEqual(200, code)
            code, task = request(url, {"action": "run", "goal": "once"}, headers={"X-Mika-Desk": session["session"]})
            self.assertEqual(200, code)
            self.assertEqual(SNAPSHOT, task)

    def test_address_session_is_unavailable_to_other_socket_owners_or_private_link_mode(self):
        with patch.object(sys, "path", [str(SCRIPT.parent), *sys.path]):
            from mika_desk import owner_connection
        client, server = ("127.0.0.1", 54321), ("127.0.0.1", 18765)
        # Our own end of the socket is visible even when another account connected.
        output = b"n127.0.0.1:18765->127.0.0.1:54321"
        with patch("mika_desk.subprocess.run", return_value=Namespace(returncode=0, stdout=output)) as run:
            with patch("mika_desk.os.getuid", return_value=501, create=True):
                self.assertFalse(owner_connection(client, server))
            self.assertIn("-u", run.call_args.args[0])
            self.assertIn("501", run.call_args.args[0])
        with api() as phone, self.launch(phone) as url:
            self.assertEqual(403, request(url, {}, path="/session", headers={"X-Mika-Desk": "open"})[0])
            self.assertEqual([], phone.calls)

    def test_lost_write_is_not_retried_and_old_phones_cannot_steer(self):
        def respond(method, path, body):
            if path == "/api/v1":
                return 200, {**DISCOVERY, "capabilities": ["tasks"]}, {}
            if method == "POST":
                return "drop"
        with api(respond) as phone, self.launch(phone) as url:
            code, outcome = request(url, {"action": "answer", "task_id": "t1", "text": "yes"})
            self.assertEqual(502, code)
            self.assertEqual("outcome_unknown", outcome["error"]["code"])
            self.assertEqual("t1", outcome["error"]["task_id"])
            _, denied = request(url, {"action": "steer", "task_id": "t1", "text": "update"})
            self.assertEqual("incompatible_api", denied["error"]["code"])
            self.assertEqual(1, sum(method == "POST" for method, _, _ in phone.calls))

    def test_target_choice_is_limited_to_online_devices_and_stays_pinned(self):
        with patch.object(sys, "path", [str(SCRIPT.parent), *sys.path]):
            import mika_desk
        args = Namespace(url=None, device=None, adb="adb", port=None)
        online = [{"device": "phone-a", "state": "device"}, {"device": "phone-b", "state": "device"},
                  {"device": "other", "state": "offline"}]
        with patch.dict(os.environ, {"ANDROID_SERIAL": ""}), mika_desk.Desk(args) as server:
            with patch.object(mika_desk.Adb, "devices", return_value=online) as listing:
                for choice in [None, "other", "unknown"]:
                    with self.assertRaises(mika_desk.CliError) as caught:
                        server.pin_target(choice)
                    self.assertEqual(["phone-a", "phone-b"], caught.exception.details["devices"])
                    self.assertFalse(args.device)
                server.pin_target("phone-b")
                listing.return_value = [online[0]]  # The chosen phone disconnected.
                server.pin_target(None)
                with self.assertRaises(mika_desk.CliError) as caught:
                    server.pin_target("phone-a")
                self.assertEqual("device_pinned", caught.exception.code)
                self.assertEqual("phone-b", args.device)

    def test_error_messages_redact_the_phone_token(self):
        with api(lambda *_: (401, {"error": {"message": "echo " + TOKEN}}, {})) as phone, self.launch(phone) as url:
            code, body = request(url, {"action": "overview"})
            self.assertEqual(401, code)
            self.assertIn("[redacted]", body["error"]["message"])
