# Use Mika from a computer

[简体中文](computer.md) | English

AI tools on your computer (or you, in a terminal) can hand a job to Mika on your phone, check progress, answer its follow-up questions and get the result back. The computer says what to get done; Mika on the phone decides how. This page helps you pick an entry point and a connection, then points you to the step-by-step guides.

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

If you would rather not type commands, connect the phone and run `mika ui`. Your browser opens a task desk where you can start tasks, follow their steps, answer questions, add instructions and cancel tasks; recent tasks started from other entry points show up too. Reads pause while the tab is hidden and resume when you come back; steps that need you still have to be handled on the phone. The desk follows the browser's language: Simplified Chinese or English.

- With several ADB devices, choose one in the page or start with `mika ui --device SERIAL`; Wi-Fi / private VPN connections use the usual `--url` and `--token-file`.
- With only the Skill installed, run `python3 <skills-directory>/mika/scripts/mika_cli.py ui`.
- If no browser opens, add `--no-open` and open the printed link in a browser on this computer.
- The desk listens only on `127.0.0.1` on a random port and prints a new link on every start. The secret in the link is what grants access, so keep it to yourself. Ctrl+C in the terminal closes the desk; tasks already on the phone keep running.
