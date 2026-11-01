"""Optional browser regression: with Playwright and Chrome installed, run this file directly."""
import copy
from contextlib import contextmanager
from pathlib import Path
import tempfile
import threading
import unittest

from playwright.sync_api import expect, sync_playwright

from test_mika_cli import api, DISCOVERY, SNAPSHOT, TOKEN
from test_mika_desk import desk


@contextmanager
def browser_desk(respond):
    with tempfile.TemporaryDirectory() as directory, api(respond) as phone, sync_playwright() as pw:
        token = Path(directory) / "credential"
        token.write_text(TOKEN)
        with desk("--url", f"http://127.0.0.1:{phone.server_port}", "--token-file", str(token)) as url:
            browser = pw.chromium.launch(channel="chrome")
            try:
                page = browser.new_page()
                page.goto(url.geturl())
                yield page, phone
            finally:
                browser.close()


class DeskUpdatesTest(unittest.TestCase):
    def test_phone_changes_arrive_while_the_owner_is_typing(self):
        task = {**SNAPSHOT, "step": 1, "progress": "first step"}
        tasks = [task]
        changed = threading.Condition()

        def update(**fields):
            with changed:
                task.update(fields)
                changed.notify_all()

        def respond(method, path, body):
            with changed:
                if path == "/api/v1":
                    return 200, DISCOVERY, {}
                if path.startswith("/api/v1/tasks?"):
                    return 200, {"tasks": copy.deepcopy(tasks)}, {}
                if path == "/api/v1/tasks/t1/wait":
                    before = task["status"]
                    changed.wait_for(lambda: task["status"] != before, timeout=body["timeout_s"])
                    return 200, {**task, "changed": task["status"] != before}, {}
            return 404, {}, {}

        with browser_desk(respond) as (page, phone):
            expect(page.locator("#status")).to_have_text("进行中")
            page.locator("#goal").fill("unfinished goal")
            reply = page.locator("#reply")
            reply.fill("unfinished steer")
            update(step=2, progress="second step")
            expect(page.locator("#facts")).to_contain_text("second step", timeout=9000)
            expect(reply).to_have_value("unfinished steer")
            expect(reply).to_be_focused()

            update(status="waiting_for_answer", question="Which one?")
            expect(page.locator("#facts")).to_contain_text("Which one?", timeout=9000)
            expect(reply).to_have_value("")
            reply.fill("unfinished answer")
            with changed:
                tasks.insert(0, {**SNAPSHOT, "task_id": "t2", "goal": "external task", "status": "done"})
            expect(page.locator("#tasks")).to_contain_text("external task", timeout=12000)
            expect(reply).to_have_value("unfinished answer")

            update(status="waiting_on_phone", message="Open the phone")
            expect(page.locator("#status")).to_have_text("需要手机接管", timeout=9000)
            expect(page.locator("#respond")).to_be_hidden()
            update(status="done", answer="phone result")
            expect(page.locator("#facts")).to_contain_text("phone result", timeout=9000)
            expect(page.locator("#goal")).to_have_value("unfinished goal")

            with changed:
                tasks.insert(0, {**SNAPSHOT, "task_id": "t3", "goal": "new while idle"})
            expect(page.locator("#tasks")).to_contain_text("new while idle", timeout=18000)
            writes = [path for method, path, _ in phone.calls if method == "POST" and not path.endswith("/wait")]
            self.assertEqual([], writes)

    def test_connection_disclosure_and_task_reading_order(self):
        available = True
        task = {**SNAPSHOT, "status": "waiting_for_answer", "question": "Which note?", "options": ["Monday"]}

        def respond(method, path, body):
            if not available:
                return 503, {"error": {"code": "connection", "message": "disconnected"}}, {}
            if path == "/api/v1":
                return 200, DISCOVERY, {}
            if path.startswith("/api/v1/tasks?"):
                return 200, {"tasks": [task]}, {}
            if path.endswith("/wait"):
                return 200, {**task, "changed": False}, {}
            return 404, {}, {}

        with browser_desk(respond) as (page, _):
            expect(page.locator("#facts")).to_contain_text("Which note?")
            target = page.locator("#target")
            expect(target).to_be_hidden(timeout=1500)
            disclosure = page.locator("summary").filter(has_text="连接详情")
            disclosure.focus()
            disclosure.press("Enter")
            expect(target).to_be_visible()
            page.locator("#refresh").click()
            expect(page.locator("#refresh")).to_be_enabled()
            expect(target).to_be_visible()
            disclosure.press("Enter")
            expect(target).to_be_hidden()

            question = page.get_by_text("Which note?", exact=True)
            send = page.get_by_role("button", name="发送回答")
            task_id = page.locator("#task-id")
            self.assertLess(question.bounding_box()["y"], send.bounding_box()["y"])
            self.assertLess(send.bounding_box()["y"], task_id.bounding_box()["y"])

            available = False
            page.locator("#refresh").click()
            expect(page.locator("#error")).to_be_visible()
            expect(target).to_be_visible()
            available = True
            page.locator("#refresh").click()
            expect(page.locator("#error")).to_be_hidden()
            expect(target).to_be_hidden()

    def test_system_appearance_switches_in_place_with_the_packaged_logo(self):
        def respond(method, path, body):
            if path == "/api/v1":
                return 200, DISCOVERY, {}
            if path.startswith("/api/v1/tasks?"):
                return 200, {"tasks": [SNAPSHOT]}, {}
            return 200, SNAPSHOT, {}

        with browser_desk(respond) as (page, _):
            expect(page.locator("#status")).to_have_text("进行中")
            logo = page.locator("header img")
            expect(logo).to_have_count(1, timeout=1500)
            self.assertGreater(logo.evaluate("async img => { await img.decode(); return img.naturalWidth; }"), 0)
            goal = page.locator("#goal")
            goal.fill("unfinished goal")
            surfaces = []
            for scheme in ("light", "dark", "light"):
                page.emulate_media(color_scheme=scheme)
                surfaces.append(page.locator(".panel").first.evaluate("el => getComputedStyle(el).backgroundColor"))
                expect(goal).to_have_value("unfinished goal")
                expect(goal).to_be_focused()
                expect(page.locator(".task[aria-current=true]")).to_have_count(1)
            self.assertNotEqual(surfaces[0], surfaces[1])
            self.assertEqual(surfaces[0], surfaces[2])

    def test_late_read_cannot_undo_a_write_and_a_lost_receipt_is_not_retried(self):
        tasks = [dict(SNAPSHOT)]
        watched = threading.Event()
        held = threading.Event()
        release = threading.Event()

        def respond(method, path, body):
            if path == "/api/v1":
                return 200, DISCOVERY, {}
            if path.endswith("/wait"):
                watched.set()
                return 200, {**SNAPSHOT, "changed": False}, {}
            if path.startswith("/api/v1/tasks?"):
                snapshot = copy.deepcopy(tasks)
                if watched.is_set() and not held.is_set():
                    held.set()
                    release.wait(timeout=15)  # Return the snapshot from before the submit.
                return 200, {"tasks": snapshot}, {}
            if path == "/api/v1/tasks" and method == "POST":
                task = {**SNAPSHOT, "task_id": "t" + str(len(tasks) + 1), "goal": body["goal"]}
                tasks.insert(0, task)
                return "drop" if body["goal"] == "uncertain" else (201, task, {})
            return 404, {}, {}

        with browser_desk(respond) as (page, phone):
            try:
                self.assertTrue(held.wait(timeout=8))
                goal = page.locator("#goal")
                goal.fill("new task")
                page.locator("#submit-goal").click()
                expect(page.locator("#facts")).to_contain_text("new task")
                # Catch even a transient rollback before the next good read.
                page.evaluate("""() => {
                    const list = document.getElementById("tasks");
                    new MutationObserver(() => {
                        if (!list.innerText.includes("new task")) list.dataset.rolledBack = "true";
                    }).observe(list, {childList: true});
                }""")
                with page.expect_response(lambda r: r.url.endswith("/desk") and r.request.post_data_json["action"] == "watch"):
                    release.set()
                expect(page.locator("#tasks")).to_contain_text("new task")
                expect(page.locator("#facts")).to_contain_text("new task")

                goal.fill("uncertain")
                page.locator("#submit-goal").click()
                expect(page.locator("#error")).to_contain_text("操作可能已到达手机")
                expect(page.locator("#tasks")).to_contain_text("uncertain", timeout=10000)
                expect(page.locator("#error")).to_contain_text("操作可能已到达手机")
                goals = [body["goal"] for method, path, body in phone.calls if method == "POST" and path == "/api/v1/tasks"]
                self.assertEqual(["new task", "uncertain"], goals)
                self.assertIsNone(page.locator("#tasks").get_attribute("data-rolled-back"))
            finally:
                release.set()


if __name__ == "__main__":
    unittest.main()
