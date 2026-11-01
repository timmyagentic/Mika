"use strict";

const $ = (id) => document.getElementById(id);
const labels = {
  starting: ["正在启动", "active"], running: ["进行中", "active"], paused: ["已暂停", ""],
  waiting_for_answer: ["等你回答", "attention"], waiting_on_phone: ["需要手机接管", "attention"],
  done: ["已返回结果", "good"], failed: ["未能完成", "bad"], cancelled: ["已取消", ""],
};
const terminal = new Set(["done", "failed", "cancelled"]);
const ticket = location.hash.slice(1) || sessionStorage.getItem("deskSession");
if (location.hash) {
  sessionStorage.setItem("deskSession", ticket);
  sessionStorage.removeItem("selectedTask");
  history.replaceState(null, "", location.pathname);
}
let overview = null;
let selectedId = sessionStorage.getItem("selectedTask");
let selected = null;
let online = false;
let busy = false;
let replyKey = "";

function message(id, text) {
  $(id).textContent = text;
  $(id).hidden = !text;
}

function element(tag, text, className) {
  const node = document.createElement(tag);
  node.textContent = text;
  if (className) node.className = className;
  return node;
}

function badge(node, status) {
  const [label, tone] = labels[status] || [status, ""];
  node.textContent = label;
  node.dataset.tone = tone;
}

function choose(task) {
  selected = task;
  selectedId = task?.task_id || null;
  if (selectedId) sessionStorage.setItem("selectedTask", selectedId);
  else sessionStorage.removeItem("selectedTask");
}

function render() {
  const locked = busy || !online;
  $("refresh").disabled = busy || !ticket;
  $("submit-goal").disabled = locked;
  $("pick-device").disabled = busy;
  $("submit-reply").disabled = locked;
  $("cancel").disabled = locked;
  $("goal").readOnly = busy;
  $("reply").readOnly = busy;

  const tasks = [...(overview?.tasks || [])];
  const priority = task => labels[task.status]?.[1] === "attention" ? 0 : 1;
  tasks.sort((a, b) => priority(a) - priority(b));
  $("count").textContent = tasks.length;
  const list = $("tasks");
  list.replaceChildren();
  if (!tasks.length) {
    list.append(element("p", online ? "最近还没有远程任务。可以从上方开始第一件事。" : "连接手机后，在这里查看近期任务。", "empty"));
  }
  for (const task of tasks) {
    const row = element("button", "", "task");
    row.type = "button";
    row.disabled = busy;
    row.setAttribute("aria-current", String(task.task_id === selectedId));
    const meta = element("span", "", "row-meta");
    const mark = element("span", "", "badge");
    badge(mark, task.status);
    meta.append(mark);
    if (task.started_at_ms) meta.append(element("small", new Date(task.started_at_ms).toLocaleString("zh-CN")));
    row.append(meta, element("span", task.goal || task.task_id, "task-title"));
    row.addEventListener("click", () => {
      choose(task);
      render();
      list.querySelector('[aria-current="true"]').focus({preventScroll: true});
    });
    list.append(row);
  }

  const facts = $("facts");
  facts.replaceChildren();
  const task = selected;
  $("status").hidden = !task;
  const action = task?.status === "waiting_for_answer" ? "answer" :
    task?.status === "running" && overview?.capabilities.includes("task_steer") ? "steer" : null;
  $("respond").hidden = !action;
  $("cancel").hidden = !task || terminal.has(task.status);
  const key = task?.task_id + "/" + action;
  if (key !== replyKey) $("reply").value = "";
  replyKey = key;
  if (!task) {
    facts.append(element("p", "选择一件任务，查看手机返回的进度或结果。", "empty"));
    return;
  }

  badge($("status"), task.status);
  facts.append(element("h3", task.goal || "远程任务"), element("small", "任务编号  " + task.task_id, "meta"));
  const fact = (title, value) => {
    if (!value) return;
    const group = element("div", "", "fact");
    group.append(element("strong", title), element("p", value));
    facts.append(group);
  };
  if (task.step != null || task.progress) fact(task.step != null ? "第 " + task.step + " 步" : "最近进度", task.progress || "正在执行");
  if (task.status === "paused") fact("等待条件", task.waiting_for || "等待手机继续");
  if (task.status === "waiting_for_answer") fact("Mika 的问题", task.question);
  if (task.status === "waiting_on_phone") {
    fact("请在手机上处理", [task.app, task.message || task.reason, "打开手机上的 Mika 通知，处理后回来刷新。"].filter(Boolean).join("\n"));
  }
  if (task.status === "done") {
    fact("手机返回的结果", task.answer || "手机未附上答复正文。");
    const verification = task.verified === true ? "已核实" : task.verified === false ? "核实未通过" : "核实状态未知";
    fact("核实情况", [verification, task.verification_note, ...(task.unconfirmed || [])].filter(Boolean).join("\n"));
  }
  if (task.status === "failed") fact("失败原因", task.reason || task.failure_kind || "手机未附上原因。");
  const steers = {received: "已收到", joined: "已加入任务上下文", not_applied: "未被采用"};
  if (task.steers?.length) fact("追加要求", task.steers.map((s, i) => (i + 1) + ". " + (steers[s.state] || s.state)).join("\n"));
  if (!action) return;
  $("respond").dataset.action = action;
  $("reply-label").textContent = action === "answer" ? "回答追问" : "追加要求";
  $("submit-reply").textContent = action === "answer" ? "发送回答" : "追加到此任务";
  $("reply").maxLength = action === "answer" ? 2000 : 4000;
  $("options").replaceChildren();
  for (const option of action === "answer" ? task.options || [] : []) {
    const button = element("button", option);
    button.type = "button";
    button.disabled = busy;
    button.addEventListener("click", () => { $("reply").value = option; $("reply").focus(); });
    $("options").append(button);
  }
}

async function call(action, fields = {}) {
  const writing = !["overview", "select", "status"].includes(action);
  let response, data;
  try {
    response = await fetch("/desk", {
      method: "POST", headers: {"Content-Type": "application/json", "X-Mika-Desk": ticket},
      body: JSON.stringify({action, ...fields}),
    });
    if (response.headers.get("Content-Type")?.includes("application/json")) data = await response.json();
  } catch {
    throw {code: writing ? "outcome_unknown" : "desk_offline"};
  }
  if (data?.error) throw data.error;
  if (!response.ok || !data) {
    if (response.status === 403) throw {code: "session"};
    throw {code: writing && (response.ok || response.status >= 500) ? "outcome_unknown" : "desk_offline"};
  }
  return data;
}

const errors = {
  session: "请用本次 mika ui 打开的专用链接访问任务台。",
  device_selection: "请连接手机并刷新状态，或从已在线的设备中选择一台。",
  device_pinned: "本次任务台已选定手机，请刷新查看。",
  adb: "无法读取指定手机。请检查 ADB 连接，并确认已对这台手机运行 mika connect。",
  adb_unavailable: "找不到 adb，请先安装 Android platform-tools。",
  device_offline: "这台手机已离线。重新连接同一台手机后刷新。",
  stale_connection: "手机已重启。请重新对这台手机运行 mika connect，再刷新。",
  connection: "未能读取手机回复。请检查连接，然后刷新状态。",
  desk_offline: "无法读取本机任务台。请检查启动它的终端是否还在运行。",
  outcome_unknown: "操作可能已到达手机。请先刷新并核对任务，再决定下一步。",
};

async function refresh(deviceChoice) {
  const next = deviceChoice === undefined ? await call("overview") : await call("select", {device: deviceChoice});
  $("pick").hidden = true;
  let task;
  if (selectedId) task = next.tasks.find(t => t.task_id === selectedId);
  else task = next.tasks.find(t => labels[t.status]?.[1] === "attention") || next.tasks[0];
  if (selectedId && !task) {
    try { task = await call("status", {task_id: selectedId}); }
    catch (e) {
      if (e.http_status !== 404) throw e;
      message("notice", "手机已找不到上次选中的任务。进程重启后远程编号可能失效。");
    }
  }
  overview = next;
  choose(task);
  online = true;
  $("target").textContent = (next.transport === "adb" ? "ADB · " : "") + next.target;
  $("connection").textContent = "已连接" + (next.server_version ? " · " + next.server_version : "");
  const device = next.device;
  const state = device.ready === true ? "已解锁" : device.device_locked || device.keyguard_showing ? "已锁屏" :
    device.interactive === false ? "未亮屏" : "屏幕状态未知";
  $("device").textContent = state + (device.helper_available == null ? "" : device.helper_available ? " · 助手在线" : " · 助手离线");
  $("updated").textContent = "上次读取 " + new Date().toLocaleTimeString("zh-CN");
}

async function work(operation) {
  if (busy) return;
  busy = true;
  message("error", "");
  message("notice", "");
  render();
  try {
    await operation();
  } catch (e) {
    online = false;
    const devices = e.code === "device_selection" ? e.devices || [] : [];
    $("devices").replaceChildren(...devices.map(serial => new Option(serial, serial)));
    $("pick").hidden = !devices.length;
    $("connection").textContent = "需要刷新";
    $("device").textContent = "待重新读取";
    message("error", (errors[e.code] || e.message || "当前操作未完成，请刷新状态。") +
      (overview ? " 列表保留上次读取的内容。" : ""));
  } finally {
    busy = false;
    render();
  }
}

$("refresh").addEventListener("click", () => work(refresh));
$("pick").addEventListener("submit", event => {
  event.preventDefault();
  work(() => refresh($("devices").value));
});
$("compose").addEventListener("submit", event => {
  event.preventDefault();
  const goal = $("goal").value.trim();
  if (!online || !goal) return;
  work(async () => {
    const task = await call("run", {goal});
    choose(task);
    $("goal").value = "";
    message("notice", "手机已接收任务。刷新状态可查看后续进展。");
    await refresh();
  });
});
$("respond").addEventListener("submit", event => {
  event.preventDefault();
  const action = $("respond").dataset.action, text = $("reply").value.trim();
  if (!online || $("respond").hidden || !text) return;
  work(async () => {
    const receipt = await call(action, {task_id: selectedId, text});
    if (receipt.accepted) $("reply").value = "";
    message("notice", receipt.accepted ? (action === "answer" ? "手机已收到回答。" : "手机已收到追加要求，可刷新查看是否已加入上下文。") : "手机未接受这次操作。");
    await refresh();
  });
});
$("cancel").addEventListener("click", () => {
  if (!online || !confirm("向手机发送此任务的取消请求？")) return;
  work(async () => {
    const receipt = await call("cancel", {task_id: selectedId});
    message("notice", receipt.accepted ? "已送出取消请求，以手机返回的状态为准。" : "手机未接受取消请求，请查看当前状态。");
    await refresh();
  });
});

if (ticket) work(refresh);
else {
  $("connection").textContent = "等待机主打开";
  message("error", errors.session);
  render();
}
