"""Browser regression for selected files, pasted images, per-conversation drafts and real downloads."""
import base64
import unittest
from pathlib import Path

from browser_desk_updates import browser_desk, expect, read_tasks, snapshot, sync_playwright
from test_mika_cli import DISCOVERY


@unittest.skipIf(sync_playwright is None, "Playwright is not installed")
class DeskFilesTest(unittest.TestCase):
    def test_rejected_upload_keeps_the_draft_and_never_submits(self):
        def respond(method, path, body):
            if path == "/api/v1":
                return 200, {**DISCOVERY, "capabilities": ["tasks", "task_files"]}, {}
            if path == "/api/v1/uploads":
                return 409, {"error": {"code": "conflict", "message": "Not enough space"}}, {}
            return read_tasks([], path)
        with browser_desk(respond) as (page, phone):
            expect(page.locator("#attach-file")).to_be_visible()
            page.locator("#file-picker").set_input_files({"name": "sample.csv", "mimeType": "text/csv", "buffer": b"1"})
            page.locator("#submit-goal").click()
            expect(page.locator("#notice")).to_contain_text("sample.csv 未传完")
            expect(page.locator("#attachments")).to_contain_text("sample.csv")
            expect(page.locator("#submit-goal")).to_be_enabled()
            self.assertFalse(any(method == "POST" and path == "/api/v1/tasks" for method, path, _ in phone.calls))

    def test_selection_paste_attachment_only_and_round_scoped_download(self):
        data = bytes(range(256)) * 1600
        image = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNwyzvxHwAE9gJ8c7jPEgAAAABJRU5ErkJggg==")
        uploads, submissions = {}, []
        tasks = [snapshot("old", "mcp-old", "Earlier task", answer="Done")]
        def respond(method, path, body):
            if path == "/api/v1":
                return 200, {**DISCOVERY, "capabilities": ["tasks", "task_files"]}, {}
            if path == "/api/v1/uploads":
                key = "upload-" + str(len(uploads))
                uploads[key] = (body, bytearray())
                return 201, {"upload_id": key}, {}
            if path.startswith("/api/v1/uploads/"):
                meta, contents = uploads[path.split("/")[-1]]
                self.assertEqual(len(contents), body["offset"])
                contents.extend(base64.b64decode(body["data"], validate=True))
                return 200, {"next_offset": len(contents)}, {}
            if path == "/api/v1/tasks" and method == "POST":
                submissions.append(body)
                task = snapshot("new", "mcp-next", "Files", answer="Exported",
                                files=[{"id": "out", "name": "核对结果.csv", "size": len(data)}])
                tasks.insert(0, task)
                return 201, task, {}
            if path == "/api/v1/tasks/new":
                return 200, tasks[0], {}
            if path.startswith("/api/v1/tasks/new/files/out?offset="):
                pos = int(path.split("=")[1])
                return 200, {"offset": pos, "data": base64.b64encode(data[pos:pos + 131072]).decode()}, {}
            return read_tasks(tasks, path)
        with browser_desk(respond) as (page, _):
            page.locator("#new-chat").click()
            with page.expect_file_chooser() as chooser:
                page.get_by_role("button", name="附加文件").click()
            chooser.value.set_files({"name": "输入.csv", "mimeType": "application/octet-stream", "buffer": data})
            page.locator("#goal").evaluate("""(field, encoded) => {
                const b = Uint8Array.from(atob(encoded), c => c.charCodeAt(0));
                const clip = new DataTransfer();
                clip.items.add(new File([b], "截图.png", {type:"image/png"}));
                field.dispatchEvent(new ClipboardEvent("paste", {clipboardData: clip, bubbles:true, cancelable:true}));
            }""", base64.b64encode(image).decode())
            expect(page.locator(".file-chip")).to_have_count(2)
            page.get_by_role("button", name="Earlier task").click()
            expect(page.locator("#attachments")).to_be_empty()
            page.locator("#new-chat").click()
            expect(page.locator(".file-chip")).to_have_count(2)
            page.locator("#submit-goal").click()
            button = page.get_by_role("button", name="下载 核对结果.csv")
            expect(button).to_be_enabled()
            self.assertEqual(1, len(submissions))
            self.assertEqual("", submissions[0]["goal"])
            self.assertEqual([("输入.csv", data), ("截图.png", image)],
                             [(uploads[k][0]["name"], bytes(uploads[k][1])) for k in submissions[0]["attachments"]])
            with page.expect_download() as saved:
                button.click()
            self.assertEqual("核对结果.csv", saved.value.suggested_filename)
            self.assertEqual(data, Path(saved.value.path()).read_bytes())
            expect(page.locator("#attachments")).to_be_empty()


if __name__ == "__main__":
    unittest.main()
