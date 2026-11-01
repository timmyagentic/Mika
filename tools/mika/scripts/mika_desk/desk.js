"use strict";

const $ = (id) => document.getElementById(id);
const labels = {
  starting: ["正在启动", "active"], running: ["进行中", "active"], paused: ["已暂停", ""],
  waiting_for_answer: ["等你回答", "attention"], waiting_on_phone: ["需要手机接管", "attention"],
  done: ["已返回结果", "good"], failed: ["未能完成", "bad"], cancelled: ["已取消", ""],
};
const terminal = new Set(["done", "failed", "cancelled"]);
const privateTicket = location.hash.slice(1) || sessionStorage.getItem("deskSession");
let ticket = privateTicket;
if (location.hash) {
  sessionStorage.setItem("deskSession", ticket);
  sessionStorage.removeItem("selectedTask");
  history.replaceState(null, "", location.pathname);
}
let overview = null;
let selectedId = sessionStorage.getItem("selectedTask");
let selected = null;
let online = false;
let openedForConnectionProblem = false;
let busy = false;
let replyKey = "";
const replyDrafts = new Map();
let updateTimer;
let observing = false;
let revision = 0;
let retryDelay = 0;
let writeWarning = "";

function message(id, text) {
  if ($(id).textContent !== text) $(id).textContent = text;
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

function taskMessage(task) {
  switch (task.status) {
    case "starting": return "任务已交给手机，正在等待启动。";
    case "running": return task.progress || "Mika 正在执行任务。";
    case "paused": return task.progress || task.waiting_for || "正在等待手机继续执行。";
    case "waiting_for_answer": return task.question || "Mika 正在等你回答。";
    case "waiting_on_phone":
      return task.message || task.progress || task.reason ||
        (task.waiting_for === "confirmation" ? "请在手机上确认这一步。" : "请在手机上接管这一步。");
    case "done": return task.answer || "手机未附上答复正文。";
    case "failed": return task.reason || task.failure_kind || "手机未附上失败原因。";
    case "cancelled": return "任务已停止。";
  }
}

function render() {
  const locked = busy || !online;
  $("refresh").disabled = busy;
  $("submit-goal").disabled = locked;
  $("pick-device").disabled = busy;
  $("submit-reply").disabled = locked;
  $("cancel").disabled = locked;
  $("goal").readOnly = busy;
  $("reply").readOnly = busy;

  const focusedRow = document.activeElement?.dataset.taskId;
  const focusedOption = $("options").contains(document.activeElement) ? document.activeElement.textContent : null;
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
    row.dataset.taskId = task.task_id;
    row.disabled = busy;
    row.setAttribute("aria-current", String(task.task_id === selectedId));
    const meta = element("span", "", "row-meta");
    const mark = element("span", "", "badge");
    badge(mark, task.status);
    meta.append(mark);
    if (task.started_at_ms) meta.append(element("small", new Date(task.started_at_ms).toLocaleString("zh-CN")));
    row.append(meta, element("span", task.goal || task.task_id, "task-title"));
    row.addEventListener("click", () => {
      invalidateUpdate();
      choose(task);
      render();
      scheduleUpdate();
      list.querySelector('[aria-current="true"]').focus({preventScroll: true});
    });
    list.append(row);
    if (focusedRow === task.task_id && !busy) row.focus({preventScroll: true});
  }

  const notes = $("task-notes");
  notes.replaceChildren();
  const task = selected;
  $("detail-empty").hidden = !!task;
  $("task-now").hidden = !task;
  $("task-info").hidden = !task;
  $("status").hidden = !task;
  const action = task?.status === "waiting_for_answer" ? "answer" :
    task?.status === "running" && overview?.capabilities.includes("task_steer") ? "steer" : null;
  $("respond").hidden = !action;
  $("cancel").hidden = !task || terminal.has(task.status);
  const key = action ? JSON.stringify([task.task_id, action, action === "answer" ? task.question : null]) : "";
  if (key !== replyKey) {
    if (replyKey && $("reply").value) replyDrafts.set(replyKey, $("reply").value);
    $("reply").value = replyDrafts.get(key) || "";
    replyDrafts.delete(key);
    replyKey = key;
  }
  if (!task) return;

  badge($("status"), task.status);
  $("task-now").dataset.status = task.status;
  message("task-step", task.status === "running" && task.step != null ? "第 " + task.step + " 步" : "");
  $("task-message").textContent = taskMessage(task);
  const handoff = [task.app, "打开手机上的 Mika 通知处理，完成后这里会自动更新。"].filter(Boolean).join(" · ");
  message("task-guidance", task.status === "waiting_on_phone" ? handoff : "");
  $("task-goal").textContent = task.goal || "远程任务";
  $("task-id").textContent = task.task_id;

  const fact = (title, value) => {
    const group = element("div", "", "fact");
    group.append(element("strong", title), element("p", value));
    notes.append(group);
  };
  if (task.status === "paused" && task.progress && task.waiting_for && task.waiting_for !== task.progress) {
    fact("等待条件", task.waiting_for);
  }
  if (task.status === "done") {
    const verification = task.verified === true ? "已核实" : task.verified === false ? "核实未通过" : "核实状态未知";
    fact("核实情况", [verification, task.verification_note, ...(task.unconfirmed || [])].filter(Boolean).join("\n"));
  }
  const steers = {received: "已收到", joined: "已加入任务上下文", not_applied: "未被采用"};
  if (task.steers?.length) fact("追加要求", task.steers.map((s, i) => (i + 1) + ". " + (steers[s.state] || s.state)).join("\n"));
  if (!action) return;
  $("respond").dataset.action = action;
  $("reply-label").textContent = action === "answer" ? "回答追问" : "追加要求";
  $("submit-reply").textContent = action === "answer" ? "发送回答" : "追加到此任务";
  $("reply").placeholder = action === "answer" ? "输入你的回答…" : "补充本次任务的要求…";
  $("reply").maxLength = action === "answer" ? 2000 : 4000;
  $("options").replaceChildren();
  for (const option of action === "answer" ? task.options || [] : []) {
    const button = element("button", option);
    button.type = "button";
    button.disabled = busy;
    button.addEventListener("click", () => { $("reply").value = option; $("reply").focus(); });
    $("options").append(button);
    if (focusedOption === option && !busy) button.focus({preventScroll: true});
  }
}

async function call(action, fields = {}) {
  const writing = !["overview", "select", "status", "watch"].includes(action);
  const key = ticket;
  let response, data;
  try {
    response = await fetch("/desk", {
      method: "POST", headers: {"Content-Type": "application/json", "X-Mika-Desk": key},
      body: JSON.stringify({action, ...fields}),
    });
    if (response.headers.get("Content-Type")?.includes("application/json")) data = await response.json();
  } catch {
    throw {code: writing ? "outcome_unknown" : "desk_offline"};
  }
  if (data?.error) throw data.error;
  if (!response.ok || !data) {
    if (response.status === 403) {
      if (!privateTicket && ticket === key) ticket = null;
      throw {code: "session"};
    }
    throw {code: writing && (response.ok || response.status >= 500) ? "outcome_unknown" : "desk_offline"};
  }
  return data;
}

const errors = {
  session: privateTicket ? "专用链接已失效，请打开当前任务台的链接。" : "正在重新连接本机任务台会话。",
  device_selection: "请连接手机，或从已在线的设备中选择一台。",
  device_pinned: "本次任务台已选定手机，即将重新读取。",
  adb: "无法读取指定手机。请检查 ADB 连接，并确认已对这台手机运行 mika connect。",
  adb_unavailable: "找不到 adb，请先安装 Android platform-tools。",
  device_offline: "这台手机已离线，重新连接后会自动更新。",
  stale_connection: "手机已重启。请重新对这台手机运行 mika connect，任务台会自动重连。",
  connection: "未能读取手机回复，任务台会自动重试连接。",
  desk_offline: "无法读取本机任务台，请检查本机服务是否在运行。",
  outcome_unknown: "操作可能已到达手机。请核对任务列表，再决定是否重新提交。",
};

function connectionSummary(phase, text) {
  const details = $("connection-details");
  if (details.dataset.phase !== phase) {
    if (phase !== "connected" && !details.open) {
      details.open = true;
      openedForConnectionProblem = true;
    } else if (phase === "connected" && openedForConnectionProblem) {
      details.open = false;
      openedForConnectionProblem = false;
    }
    details.dataset.phase = phase;
  }
  $("connection-line").textContent = text;
}

async function refresh({device: deviceChoice, watchId, observedRevision} = {}) {
  // A read may finish after a click, a write, or a tab change. Only the
  // current view may apply it. Foreground operations are already serialized.
  const stale = () => observedRevision !== undefined && observedRevision !== revision;
  if (!ticket) {
    let response;
    try {
      response = await fetch("/session", {method: "POST", headers: {"X-Mika-Desk": "open"}});
    } catch {
      throw {code: "desk_offline"};
    }
    if (!response.ok) throw {code: "session"};
    const session = await response.json();
    if (stale()) return false;
    ticket = session.session;
  }
  let next;
  if (deviceChoice !== undefined) next = await call("select", {device: deviceChoice});
  else if (watchId) next = await call("watch", {task_id: watchId});
  else next = await call("overview");
  if (stale()) return false;
  let task;
  if (selectedId) task = next.tasks.find(t => t.task_id === selectedId);
  else task = next.tasks.find(t => labels[t.status]?.[1] === "attention") || next.tasks[0];
  if (selectedId && !task) {
    try { task = await call("status", {task_id: selectedId}); }
    catch (e) { if (e.http_status !== 404) throw e; }
    if (stale()) return false;
    if (!task) message("notice", "手机已找不到上次选中的任务。进程重启后远程编号可能失效。");
  }
  const changed = !online || JSON.stringify(next) !== JSON.stringify(overview) ||
    JSON.stringify(task || null) !== JSON.stringify(selected);
  overview = next;
  choose(task);
  online = true;
  retryDelay = 0;
  $("pick").hidden = true;
  message("error", writeWarning);
  $("target").textContent = (next.transport === "adb" ? "ADB · " : "") + next.target;
  $("connection").textContent = "已连接" + (next.server_version ? " · " + next.server_version : "");
  const device = next.device;
  const state = device.ready === true ? "已解锁" : device.device_locked || device.keyguard_showing ? "已锁屏" :
    device.interactive === false ? "未亮屏" : "屏幕状态未知";
  const helper = device.helper_available == null ? "" : device.helper_available ? "助手在线" : "助手离线";
  $("device").textContent = [state, helper].filter(Boolean).join(" · ");
  const brief = device.keyguard_showing || device.device_locked || device.interactive === false ? state : helper;
  connectionSummary("connected", ["手机已连接", brief].filter(Boolean).join(" · "));
  $("updated").textContent = "自动更新 · " + new Date().toLocaleTimeString("zh-CN");
  return changed;
}

function showProblem(e) {
  online = false;
  // Bad credentials share the phone's rate limit with other clients at this address.
  retryDelay = [401, 403, 429].includes(e.http_status) ? 60000 : Math.min(30000, retryDelay * 2 || 2000);
  const devices = e.code === "device_selection" ? e.devices || [] : [];
  const picker = $("devices");
  if (JSON.stringify(Array.from(picker.options, option => option.value)) !== JSON.stringify(devices)) {
    const choice = picker.value;
    picker.replaceChildren(...devices.map(serial => new Option(serial, serial)));
    if (devices.includes(choice)) picker.value = choice;
  }
  $("pick").hidden = !devices.length;
  const picking = devices.length > 0;
  $("connection").textContent = picking ? "等待选机" : "等待重新连接";
  let summary = "正在重新读取手机状态";
  if (picking) summary = "请选择本次使用的手机";
  else if (e.code === "device_offline" || e.code === "device_selection") summary = "等待手机重新连接";
  connectionSummary(picking ? "choose" : "retry", summary);
  $("device").textContent = "待重新读取";
  const detail = errors[e.code] || e.message || "暂时无法更新任务。";
  if (e.code === "outcome_unknown") writeWarning = detail;
  message("error", [writeWarning !== detail && writeWarning, detail, overview && "列表保留上次读取的内容。"].filter(Boolean).join(" "));
}

function activeTask() {
  if (selected && !terminal.has(selected.status)) return selected;
  return overview?.tasks.find(task => !terminal.has(task.status));
}

function invalidateUpdate() {
  revision++;
  clearTimeout(updateTimer);
}

function scheduleUpdate(immediate = false) {
  clearTimeout(updateTimer);
  if (busy || observing || document.hidden) return;
  const delay = retryDelay || (activeTask() ? 750 : 8000);
  updateTimer = setTimeout(() => observe(immediate), immediate ? 0 : delay);
}

async function observe(immediate) {
  if (busy || observing || document.hidden) return;
  observing = true;
  const observedRevision = revision;
  const watchId = online && !immediate ? activeTask()?.task_id : undefined;
  try {
    if (await refresh({watchId, observedRevision})) render();
  } catch (e) {
    if (observedRevision === revision) {
      showProblem(e);
      render();
    }
  } finally {
    observing = false;
    scheduleUpdate(observedRevision !== revision);
  }
}

async function work(operation) {
  if (busy) return;
  busy = true;
  invalidateUpdate();
  writeWarning = "";
  message("error", "");
  message("notice", "");
  render();
  try {
    await operation();
  } catch (e) {
    showProblem(e);
  } finally {
    busy = false;
    render();
    scheduleUpdate();
  }
}

$("refresh").addEventListener("click", () => work(refresh));
$("connection-details").querySelector("summary").addEventListener("click", () => {
  // Keep the owner's explicit choice across automatic reads and reconnection.
  openedForConnectionProblem = false;
});
$("pick").addEventListener("submit", event => {
  event.preventDefault();
  work(() => refresh({device: $("devices").value}));
});
$("goal").addEventListener("keydown", event => {
  if (event.key !== "Enter" || event.shiftKey || event.altKey || event.ctrlKey || event.metaKey ||
      event.isComposing || event.keyCode === 229) return;
  event.preventDefault();
  if (!event.repeat && !$("submit-goal").disabled && $("goal").value.trim()) {
    $("compose").requestSubmit($("submit-goal"));
  }
});
$("compose").addEventListener("submit", event => {
  event.preventDefault();
  const goal = $("goal").value.trim();
  if (!online || !goal) return;
  work(async () => {
    const task = await call("run", {goal});
    choose(task);
    $("goal").value = "";
    message("notice", "手机已接收任务，后续进展会自动更新。");
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
    message("notice", receipt.accepted ? (action === "answer" ? "手机已收到回答。" : "手机已收到追加要求，采用情况会自动更新。") : "手机未接受这次操作。");
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

document.addEventListener("visibilitychange", () => {
  invalidateUpdate();
  scheduleUpdate(true);
});
render();
scheduleUpdate(true);
