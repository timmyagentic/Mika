# Use Mika from a computer

[简体中文](computer.md) | English

AI tools on your computer (or you, in a terminal) can hand a job to Mika on your phone, check progress, answer its follow-up questions and get the result back. The computer says what to get done; Mika on the phone decides how. This page helps you pick an entry point and a connection, then points you to the step-by-step guides.

AI agents: go straight to the [agent quick path](agent-quickstart.en.md).

On the phone: install the APK ([Releases](https://github.com/wangzan101/Mika/releases/latest)), open Mika once and add a model. If Mika only needs to chat, browse the web and work with files, Android 11 is enough; to let it operate phone apps, also finish the phone-control setup in the [setup guide](setup-guide.en.md) (Android 12+).

## Step 1: pick an entry point (CLI + Skill or MCP)

Both entry points reach the same task service on the phone. **Normally one is enough**; you can use both if you need to connect different clients.

| Your situation | Pick | Read |
| --- | --- | --- |
| Your computer agent can run commands and supports Skills | **Skill**: includes its own CLI script, so no separate CLI install and no MCP configuration | [Install the Skill](computer-install.en.md#skill-install) |
| You want to submit tasks from a terminal or script | **CLI**: the `mika` command | [Install the CLI](computer-install.en.md#cli-install), [connection and task commands](cli.en.md) |
| You use a client that speaks MCP | **MCP**: Mika acts as an MCP server | [Computer access (MCP)](mcp.en.md) |

Not sure? If your agent supports Skills, use the Skill; otherwise use MCP.

## Step 2: pick a connection (USB or Wi-Fi / VPN)

Both entry points work over either connection. In Settings › Connect a computer on the phone, "CLI / Skill" and "MCP" share the same connection selector.

| | USB (recommended) | Wi-Fi / private VPN |
| --- | --- | --- |
| When | The phone is on a cable to the computer | No cable, or you're away from the phone (remote use) |
| How to turn it on | One command on the computer, without touching the phone; the CLI's `connect` does it for you | Turn on "network access" in Settings › Connect a computer on the phone; off by default |
| The computer needs | adb (Android platform-tools), with the phone listed as `device` in `adb devices` | To be on the same Wi-Fi as the phone, or in the same private VPN network |
| Where it listens | On the phone itself only; not reachable over Wi-Fi | Accepts only LAN and private VPN addresses; public addresses and mobile data are refused |

Each connection has its own switch and token, and both can be on at once. The connection is plain HTTP, so use it only on networks you trust, and don't share the token. Payments and anything that needs your consent can only be handled on the phone; tasks from the computer never approve them for you.

## Step 3: follow the guide

- **CLI or Skill**: install it with [install, update and remove](computer-install.en.md), then read [connection and task commands](cli.en.md).
- **MCP**: follow [Computer access (MCP)](mcp.en.md), which covers both USB and Wi-Fi / VPN step by step.
- **Let an AI agent on your computer set it up**: with the phone connected over USB, give it [Install with an AI agent on your computer](install-with-agent.en.md). It installs Mika on the phone first; if you also want computer access, it then sets up the entry point you chose.

## Task desk (`mika ui`)

Connect the phone and run `mika ui` to start tasks, follow their progress, answer questions, add instructions and cancel tasks in your browser. Recent remote tasks started from other entry points show up too. Handle phone takeovers on the phone itself. The desk follows the browser's language and supports Simplified Chinese and English.

To get alerts while working in another window, open the phone connection details at the bottom left, turn on **Desktop notifications** and allow notifications in your browser. The desk alerts you when a task needs an answer or a phone takeover, finishes or fails. Click a notification to open the exact turn. Existing states and ordinary progress updates stay quiet.

Notifications are off by default and hidden tabs normally pause reads. With notifications on, keep at least one desk tab open in the same browser. Background tabs can deliver alerts, and opening extra tabs does not duplicate them. Your computer must allow the browser to show notifications. If the computer sleeps, the browser suspends the page or the phone disconnects, the desk reads the latest state when the connection resumes. Closing all desk tabs or quitting the browser pauses alerts. Reopening the desk starts from the current state.

The sidebar groups the latest 50 remote tasks by conversation. Read each turn in the center and use the composer to continue after a turn ends. Select **View process** to expand the phone’s report and the updates observed in this tab above that turn’s reply. Select **Hide process** to collapse it. The composer keeps your current draft. Select **New chat** to start a separate conversation.

On a phone with task recovery, the desk matches a missing submission receipt by ID when the connection returns. Choose **Find task** if the task is outside the recent list. If the phone has no record yet, choose **Retry with the same ID**. The tab keeps the pending request across a reload until you recover it, retry successfully or dismiss it.

- With several ADB devices, choose one in the page or start with `mika ui --device SERIAL`; Wi-Fi / private VPN connections use the usual `--url` and `--token-file`.
- With only the Skill installed, run `python3 <skills-directory>/mika/scripts/mika_cli.py ui`.
- If no browser opens, add `--no-open` and open the printed link in a browser on this computer.
- The desk listens only on `127.0.0.1` on a random port and prints a new link on every start. The secret in the link is what grants access, so keep it to yourself. Ctrl+C in the terminal closes the desk; tasks already on the phone keep running.
