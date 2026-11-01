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

## Task desk for the phone owner

After connecting the phone, run `mika ui` to open the local task desk (currently in Simplified Chinese). Submit a task and leave the desk open to follow its progress, answer questions and see the result. The visible page also picks up recent tasks from other clients. Updates pause in a hidden tab and resume when you return. Steps that require your intervention on the phone are marked in the desk. The list shows recent computer-initiated tasks. Old task IDs can expire when the Mika process restarts.

For multiple ADB devices, choose the phone in the desk or pass `mika ui --device SERIAL` when starting it. A private network connection uses the usual `--url` and `--token-file`. With only the Skill installed, run `python3 <skill-directory>/scripts/mika_cli.py ui`. To open the browser yourself, pass `--no-open` and use the private link printed in the terminal on this computer. Keep the link private because it grants control of the desk. Ctrl+C closes the desk and leaves submitted tasks on the phone.

On macOS, run the service with `mika ui --port 18765 --no-open` to use a fixed address. Enter or bookmark `http://127.0.0.1:18765` in a local browser, including a new tab. The service binds to loopback and issues desk sessions only to connections owned by the current Mac user.
