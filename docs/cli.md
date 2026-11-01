# 电脑端 CLI 与 Agent Skill

简体中文 | [English](cli.en.md)

> 从电脑使用 Mika，先看[从电脑使用 Mika](computer.md)：怎么在 CLI + Skill 和 MCP 之间选、怎么连。

CLI 与 MCP 是并列入口,共用手机端的远程任务服务。CLI 调用普通 HTTP 任务 API,不需要 MCP SDK、握手、工具发现或 MCP 客户端配置。Skill 告诉电脑 Agent 怎样使用 CLI、处理追问和报告真实结果;手机上的 Mika 仍负责规划和执行。

```mermaid
flowchart LR
    Skill["电脑 Agent + Skill"] --> CLI["Mika CLI"]
    Shell["用户 / Shell 脚本"] --> CLI
    Client["MCP 客户端"] --> MCP
    subgraph Phone["Android 手机 · 同一监听与鉴权"]
        API["RemoteTaskApi · /api/v1"]
        MCP["McpRpc + McpTaskTools · /mcp"]
        Shared["RemoteTaskService · 共用任务用例"]
        Hub["RemoteTaskHub · 同一任务索引"]
        Engine["TaskService / TaskRun / AgentLoop"]
        API --> Shared
        MCP --> Shared
        Shared --> Hub --> Engine
    end
    CLI -->|"HTTP，可经 ADB 转发"| API
```

## 安装与维护

安装、升级、回退和卸载见 [CLI / Skill 安装指南](computer-install.md)。CLI 与 MCP 通常二选一。手机需要 task API v1,官方 APK 从 v0.1.23 起提供。

只装 Skill 时,下面的 `mika` 代表 `python3 <技能目录>/mika/scripts/mika_cli.py`（Windows 用 `py -3`）。

## 连接手机

ADB 模式需要 Android platform-tools。数据线或已经配对并连接的无线 ADB 都可以；无线 ADB 需要这台电脑与手机完成配对、连接，并在 `adb devices` 中显示为 `device`。Mika 在手机内为启动助手完成的配对，不等于电脑已配对；它们也不同于下面的直接局域网访问。

```bash
mika devices --json
mika connect --device SERIAL --json
mika doctor --device SERIAL --json
```

只有一个在线设备时可省略 `--device`;也支持已有的 ANDROID_SERIAL 环境变量。多设备不猜目标,连接失败也不切换到另一台手机。`connect` 调用 Mika 写到手机上的官方启动脚本,打开电脑连接并启动所需助手;此动作不提交业务任务。其余命令只读取当前连接。

每次调用重新读取当前令牌、端口和开机编号,申请自己的 `adb forward tcp:0` 临时端口,完成后只删除该转发。令牌只保存在进程内存中,启动脚本的原始输出不转发到终端。CLI 退出不关闭手机服务,也不停止正在执行的任务。

CLI 会处理端口转发，无需照 MCP 步骤手工转发固定端口。拔插后先让同一台手机重新出现在 ADB 中；手机重启或电脑连接开关被关闭后，再运行 `connect`。

也可以使用手机设置里显式开启的局域网/私有网络连接:

```bash
mika --url http://PHONE:8765 --token-file /path/to/private-token-file doctor --json
```

令牌文件只包含访问令牌,由用户按私有文件保存。URL 使用服务器根地址或 `/api/v1`,不要填 `/mcp`;不允许 URL 内嵌凭据。CLI 不跟随 HTTP 重定向、不使用环境中的 HTTP 代理。服务器沿用私有网络限制;局域网 HTTP 的链路本身不加密,可使用 ADB 或可信私有组网。

## 任务命令

```bash
mika --device SERIAL task run --goal-file task.txt --json
mika --device SERIAL task status TASK_ID --json
mika --device SERIAL task wait TASK_ID --timeout 45 --json
mika --device SERIAL task answer TASK_ID --text-file answer.txt --json
mika --device SERIAL task steer TASK_ID --text "补充一下，只整理今天的消息" --json
mika --device SERIAL task cancel TASK_ID --json
mika --device SERIAL task list --limit 10 --json
```

简单文本也可用 `--goal` / `--text`;文件参数为 `-` 时读取标准输入。`run --conversation CONVERSATION_ID` 创建同一远程对话中的下一项任务,`answer` 只回答当前追问。保留现有对话的 mcp- 前缀以兼容已经发出的 ID;这个历史前缀不代表 CLI 走 MCP 协议。

`run` 返回任务 ID 即结束。`wait` 一次最多等待 50 秒,状态变化或任务结束时返回;如果已经在等回答或手机接管,立即交回。预算用完仍返回当前状态,不判失败、不取消、不重新提交。取消返回的 `accepted` 只表示请求已交给任务服务;必须查询到 cancelled 才能报告已经取消。

`steer` 向 `running` 的远程任务追加要求,保留同一个任务 ID 和上下文;也支持 `--text-file`(文件或 `-` 标准输入)。最多 4000 字符。返回 `accepted: true` 与 `message_id` 只表示收件箱已接收;用 `status` 查询 `steers` 中这条消息的 `state`: `received` 为已收到,`joined` 为已加入当前任务上下文,`not_applied` 为结束或取消前未用上。`joined` 不表示要求已经执行成功。正在执行的动作会先做完,追加要求不会撤销已做的事。

排队、暂停、追问、手机接管和终态任务拒绝追加(409);追问用 `answer`,接管/审批在手机上处理。任务结束竞态也不会把追加要求转成新任务。响应丢失时不要重复发送;查询同一任务,没有收到消息编号且无法确认时照实报告不确定。此入口需要 APK 的 `task_steer` 能力;旧 APK 会提示升级,不回退成新任务。

`--json` 将成功结果和错误都作为单个 JSON 对象写入 stdout;默认输出缩进 JSON,错误写入 stderr。连接选项和 `--json` 可以写在命令前后。可用 `--request-timeout` 调整 HTTP 超时;等待请求的读取预算至少是任务等待时间加 5 秒。

| 退出码 | 含义 |
| --- | --- |
| 0 | 调用成功;任务仍可能运行、等待用户或尚未完成取消。 |
| 1 | API 明确拒绝,或查询到任务 failed / cancelled。 |
| 2 | 输入、配置、连接或协议不兼容。 |
| 4 | 写入结果不明;可能已被手机接受,没有自动重试。 |
| 130 | 本地读取/等待被中断,没有请求取消手机任务。 |

响应丢失、写入遇到服务端错误、或写入过程中按 Ctrl-C 时,可能无法判断手机是否接受。已知任务 ID 就查同一任务;没有 ID 就查最近任务,仍不能确认则如实交回。查询失败也不自动重试。手机端任务索引仍在内存中,进程重启后可能找不到旧 ID;本 PR 不增加任务持久化或幂等提交保证。

`doctor` 只检查连接/API 和可用的设备状态,`model_configuration` 明确为 unverified。它不会调用模型或提交任务,也不能证明真实手机操作已经验收。结果缺少 `verified` 时表示核实情况未知。付款确认、手机接管和手机本地任务的访问边界与 MCP 共用同一套规则。

## 浏览器任务台

```bash
mika ui                      # 打开本机浏览器里的任务台
mika ui --device SERIAL --no-open
```

`mika ui` 在 `127.0.0.1` 的随机端口起一个只给本机用的页面，用同一套任务命令派任务、看进度、回答追问、追加要求和取消，界面按浏览器语言显示简体中文或英文。链接末尾 `#` 后面是本次启动的会话密钥，浏览器不会把它发给别处；服务端还核对 Host 和 Origin，只接受固定几种操作。它是给人用的交互界面，不支持 `--json`。详见[从电脑使用 Mika](computer.md#电脑任务台mika-ui)。

## HTTP 契约

以下路径都在 `/api/v1` 下,使用同一 Bearer 认证;POST 正文是 application/json 对象。API 通过结构化错误的 code/message 和 HTTP 状态返回失败,不返回 MCP 信封。未知字段会被拒绝。

| 方法与路径 | 输入 / 返回 |
| --- | --- |
| GET 根路径 | API 版本、服务器版本、能力和输入上限。 |
| GET /device | 只读设备状态;未提供设备接口时返回 503。 |
| POST /tasks | goal、可选 conversation;201 返回任务状态和 ID。 |
| GET /tasks?limit=10 | 最近远程任务;limit 范围 1–50。 |
| GET /tasks/ID | 当前任务状态。 |
| POST /tasks/ID/wait | timeout_s 范围 1–50,默认 30;返回状态与 changed。 |
| POST /tasks/ID/answer | text;返回 task_id 与 accepted。 |
| POST /tasks/ID/steer | text;202 返回 task_id、message_id 与 accepted。只接受 running 任务。 |
| POST /tasks/ID/cancel | 空对象;接受时 202,终态任务 200,返回 accepted。 |

业务校验失败为 400,任务不存在为 404,当前状态不能回答为 409,任务服务拒绝启动为 503。认证、来源和限流策略与 `/mcp` 共用。两种入口使用同一远程任务索引;持有同一访问权限的客户端可以跨入口查询和操作远程任务,仍不能访问手机本地发起的任务。

## 开发验证

```bash
./gradlew :core:test :core:cliTest :app:testDebugUnitTest :app:assembleDebug
# Python 可单独运行;指定解释器可用 -PmikaPython=/path/to/python3
python3 -m unittest discover -s tools/mika/tests -v
```

纯 JVM 测试覆盖共享服务、API/MCP 互操作、鉴权、状态与原有协议回归。`cliTest`(要 Python 3.10+,不在 `check` 里,需要时显式运行)运行真实 CLI 对 Kotlin HTTP 服务的契约测试(核对 CLI 只请求 `/api/v1`),以及假 ADB、丢响应、令牌变化、端口清理和独立 Skill 目录测试。这些都不需要真机或模型 Key。真机无线断连、手机重启和真实 Agent 执行需要另行验收,不能用这些隔离测试代替。
