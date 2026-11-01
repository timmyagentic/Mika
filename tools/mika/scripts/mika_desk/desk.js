"use strict";

const $ = (id) => document.getElementById(id);

// Each entry is [Simplified Chinese, English]. Chinese browsers get Chinese; every other language gets English.
const STRINGS = {
  title: ["电脑任务台 · Mika", "Task desk · Mika"],
  workspace: ["个人工作区", "Personal workspace"],
  heading: ["电脑任务台", "Task desk"],
  intro: ["把任务交给手机，查看进展，处理需要你的问题。", "Hand tasks to your phone, follow their progress and answer its questions."],
  autoUpdate: ["自动更新", "Updates automatically"],
  refreshNow: ["立即更新", "Update now"],
  connectingPhone: ["正在连接手机", "Connecting to the phone"],
  connectionDetails: ["连接详情", "Connection details"],
  deviceForSession: ["本次使用的设备", "Phone for this session"],
  chooseDevice: ["选择设备", "Choose phone"],
  deviceStatus: ["设备状态", "Phone status"],
  connectedPhone: ["连接的手机", "Phone"],
  waitingToRead: ["等待读取", "Not read yet"],
  taskService: ["任务服务", "Task service"],
  connecting: ["正在连接", "Connecting"],
  phoneState: ["手机状态", "Phone state"],
  unknown: ["未知", "Unknown"],
  modelSetup: ["模型配置", "Model setup"],
  unverified: ["未验证", "Not verified"],
  newTask: ["新建任务", "New task"],
  goalPlaceholder: ["描述任务，Enter 发送，Shift+Enter 换行", "Describe the task. Enter sends, Shift+Enter adds a line"],
  submitGoal: ["交给手机", "Send to phone"],
  recentTasks: ["近期任务", "Recent tasks"],
  recentHint: ["显示最近 50 个远程任务，需要你处理的在前。", "The latest 50 remote tasks; ones that need you come first."],
  taskDetail: ["任务详情", "Task details"],
  detailEmpty: ["选择一件任务，查看手机返回的进度或结果。", "Pick a task to see the progress or result the phone reports."],
  taskGoal: ["任务目标", "Goal"],
  taskId: ["任务编号", "Task ID"],
  cancelTask: ["取消任务", "Cancel task"],
  starting: ["正在启动", "Starting"],
  running: ["进行中", "Running"],
  paused: ["已暂停", "Paused"],
  waiting_for_answer: ["等你回答", "Needs your answer"],
  waiting_on_phone: ["需要手机接管", "Needs you on the phone"],
  done: ["已返回结果", "Result returned"],
  failed: ["未能完成", "Not completed"],
  cancelled: ["已取消", "Cancelled"],
  msgStarting: ["任务已交给手机，正在等待启动。", "The phone has the task and is about to start."],
  msgRunning: ["Mika 正在执行任务。", "Mika is working on the task."],
  msgPaused: ["正在等待手机继续执行。", "Waiting for the phone to continue."],
  msgQuestion: ["Mika 正在等你回答。", "Mika is waiting for your answer."],
  msgConfirm: ["请在手机上确认这一步。", "Confirm this step on the phone."],
  msgHandoff: ["请在手机上接管这一步。", "Take over this step on the phone."],
  msgNoAnswer: ["手机未附上答复正文。", "The phone returned no answer text."],
  msgNoReason: ["手机未附上失败原因。", "The phone gave no reason for the failure."],
  msgCancelled: ["任务已停止。", "The task stopped."],
  emptyOnline: ["最近还没有远程任务。可以从上方开始第一件事。", "No remote tasks yet. Start one above."],
  emptyOffline: ["连接手机后，在这里查看近期任务。", "Recent tasks appear here once the phone is connected."],
  handoffGuide: ["打开手机上的 Mika 通知处理，完成后这里会自动更新。", "Open Mika's notification on the phone; this page updates when you are done."],
  remoteTask: ["远程任务", "Remote task"],
  stepN: ["第 {n} 步", "Step {n}"],
  waitingFor: ["等待条件", "Waiting for"],
  verified: ["已核实", "Verified"],
  verifyFailed: ["核实未通过", "Verification failed"],
  verifyUnknown: ["核实状态未知", "Verification unknown"],
  verification: ["核实情况", "Verification"],
  steerReceived: ["已收到", "Received"],
  steerJoined: ["已加入任务上下文", "Added to the task"],
  steerNotApplied: ["未被采用", "Not used"],
  steers: ["追加要求", "Added instructions"],
  answerLabel: ["回答追问", "Answer the question"],
  steerLabel: ["追加要求", "Add instructions"],
  answerSend: ["发送回答", "Send answer"],
  steerSend: ["追加到此任务", "Add to this task"],
  answerPlaceholder: ["输入你的回答…", "Type your answer…"],
  steerPlaceholder: ["补充本次任务的要求…", "Add requirements for this task…"],
  errSession: ["专用链接已失效，请打开当前任务台的链接。", "This private link is no longer valid. Open the link the running desk printed."],
  errDeviceSelection: ["请连接手机，或从已在线的设备中选择一台。", "Connect a phone, or choose one of the online phones."],
  errDevicePinned: ["本次任务台已选定手机，即将重新读取。", "This desk already uses another phone; reading it again."],
  errAdb: ["无法读取指定手机。请检查 ADB 连接，并确认已对这台手机运行 mika connect。", "Cannot read the selected phone. Check the ADB connection and run mika connect for this phone."],
  errAdbUnavailable: ["找不到 adb，请先安装 Android platform-tools。", "adb was not found. Install Android platform-tools first."],
  errDeviceOffline: ["这台手机已离线，重新连接后会自动更新。", "The phone is offline. The desk updates once it reconnects."],
  errStale: ["手机已重启。请重新对这台手机运行 mika connect，任务台会自动重连。", "The phone restarted. Run mika connect for it again; the desk reconnects by itself."],
  errConnection: ["未能读取手机回复，任务台会自动重试连接。", "No reply from the phone. The desk keeps retrying."],
  errDeskOffline: ["无法读取本机任务台，请检查本机服务是否在运行。", "Cannot reach the local desk. Check that mika ui is still running."],
  errOutcomeUnknown: ["操作可能已到达手机。请核对任务列表，再决定是否重新提交。", "The request may have reached the phone. Check the task list before sending it again."],
  errFallback: ["暂时无法更新任务。", "Cannot update tasks right now."],
  keptList: ["列表保留上次读取的内容。", "The list shows the last successful read."],
  connected: ["已连接", "Connected"],
  unlocked: ["已解锁", "Unlocked"],
  locked: ["已锁屏", "Locked"],
  screenOff: ["未亮屏", "Screen off"],
  screenUnknown: ["屏幕状态未知", "Screen state unknown"],
  helperOnline: ["助手在线", "Helper online"],
  helperOffline: ["助手离线", "Helper offline"],
  phoneConnected: ["手机已连接", "Phone connected"],
  awaitingChoice: ["等待选机", "Waiting for a choice"],
  awaitingReconnect: ["等待重新连接", "Waiting to reconnect"],
  rereading: ["正在重新读取手机状态", "Reading the phone again"],
  pickPhone: ["请选择本次使用的手机", "Choose the phone for this session"],
  waitPhone: ["等待手机重新连接", "Waiting for the phone to reconnect"],
  toReread: ["待重新读取", "Waiting to read again"],
  taskGone: ["手机已找不到上次选中的任务。进程重启后远程编号可能失效。", "The phone no longer knows the selected task. Remote task IDs can expire when Mika restarts."],
  taskAccepted: ["手机已接收任务，后续进展会自动更新。", "The phone accepted the task. Progress updates here automatically."],
  answerAccepted: ["手机已收到回答。", "The phone received your answer."],
  steerAccepted: ["手机已收到追加要求，采用情况会自动更新。", "The phone received your instructions. Whether they are used updates here."],
  notAccepted: ["手机未接受这次操作。", "The phone did not accept this request."],
  confirmCancel: ["向手机发送此任务的取消请求？", "Ask the phone to cancel this task?"],
  cancelSent: ["已送出取消请求，以手机返回的状态为准。", "Cancel request sent. The task's state on the phone is what counts."],
  cancelRejected: ["手机未接受取消请求，请查看当前状态。", "The phone did not accept the cancel request. Check the current state."],
};
const lang = (navigator.languages?.[0] || navigator.language || "").toLowerCase().startsWith("zh") ? 0 : 1;
const t = (key, vars = {}) => STRINGS[key][lang].replace(/\{(\w+)\}/g, (_, name) => vars[name]);
document.documentElement.lang = lang === 0 ? "zh-CN" : "en";
document.title = t("title");
for (const node of document.querySelectorAll("[data-i18n]")) node.textContent = t(node.dataset.i18n);
for (const node of document.querySelectorAll("[data-i18n-placeholder]")) node.placeholder = t(node.dataset.i18nPlaceholder);
for (const node of document.querySelectorAll("[data-i18n-label]")) node.setAttribute("aria-label", t(node.dataset.i18nLabel));

const tones = {
  starting: "active", running: "active", paused: "", waiting_for_answer: "attention", waiting_on_phone: "attention",
  done: "good", failed: "bad", cancelled: "",
};
const terminal = new Set(["done", "failed", "cancelled"]);
// The session secret arrives once in the link's fragment and then lives only in this tab.
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
  node.textContent = status in tones ? t(status) : status;
  node.dataset.tone = tones[status] || "";
}

function choose(task) {
  selected = task;
  selectedId = task?.task_id || null;
  if (selectedId) sessionStorage.setItem("selectedTask", selectedId);
  else sessionStorage.removeItem("selectedTask");
}

function taskMessage(task) {
  switch (task.status) {
    case "starting": return t("msgStarting");
    case "running": return task.progress || t("msgRunning");
    case "paused": return task.progress || task.waiting_for || t("msgPaused");
    case "waiting_for_answer": return task.question || t("msgQuestion");
    case "waiting_on_phone":
      return task.message || task.progress || task.reason ||
        t(task.waiting_for === "confirmation" ? "msgConfirm" : "msgHandoff");
    case "done": return task.answer || t("msgNoAnswer");
    case "failed": return task.reason || task.failure_kind || t("msgNoReason");
    case "cancelled": return t("msgCancelled");
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
  const priority = task => tones[task.status] === "attention" ? 0 : 1;
  tasks.sort((a, b) => priority(a) - priority(b));
  $("count").textContent = tasks.length;
  const list = $("tasks");
  list.replaceChildren();
  if (!tasks.length) {
    list.append(element("p", t(online ? "emptyOnline" : "emptyOffline"), "empty"));
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
    if (task.started_at_ms) meta.append(element("small", new Date(task.started_at_ms).toLocaleString()));
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
  message("task-step", task.status === "running" && task.step != null ? t("stepN", {n: task.step}) : "");
  $("task-message").textContent = taskMessage(task);
  const handoff = [task.app, t("handoffGuide")].filter(Boolean).join(" · ");
  message("task-guidance", task.status === "waiting_on_phone" ? handoff : "");
  $("task-goal").textContent = task.goal || t("remoteTask");
  $("task-id").textContent = task.task_id;

  const fact = (title, value) => {
    const group = element("div", "", "fact");
    group.append(element("strong", title), element("p", value));
    notes.append(group);
  };
  if (task.status === "paused" && task.progress && task.waiting_for && task.waiting_for !== task.progress) {
    fact(t("waitingFor"), task.waiting_for);
  }
  if (task.status === "done") {
    const verification = t(task.verified === true ? "verified" : task.verified === false ? "verifyFailed" : "verifyUnknown");
    fact(t("verification"), [verification, task.verification_note, ...(task.unconfirmed || [])].filter(Boolean).join("\n"));
  }
  const steers = {received: t("steerReceived"), joined: t("steerJoined"), not_applied: t("steerNotApplied")};
  if (task.steers?.length) fact(t("steers"), task.steers.map((s, i) => (i + 1) + ". " + (steers[s.state] || s.state)).join("\n"));
  if (!action) return;
  $("respond").dataset.action = action;
  $("reply-label").textContent = t(action === "answer" ? "answerLabel" : "steerLabel");
  $("submit-reply").textContent = t(action === "answer" ? "answerSend" : "steerSend");
  $("reply").placeholder = t(action === "answer" ? "answerPlaceholder" : "steerPlaceholder");
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
  let response, data;
  try {
    response = await fetch("/desk", {
      method: "POST", headers: {"Content-Type": "application/json", "X-Mika-Desk": ticket || ""},
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
  session: "errSession", device_selection: "errDeviceSelection", device_pinned: "errDevicePinned", adb: "errAdb",
  adb_unavailable: "errAdbUnavailable", device_offline: "errDeviceOffline", stale_connection: "errStale",
  connection: "errConnection", desk_offline: "errDeskOffline", outcome_unknown: "errOutcomeUnknown",
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
  let next;
  if (deviceChoice !== undefined) next = await call("select", {device: deviceChoice});
  else if (watchId) next = await call("watch", {task_id: watchId});
  else next = await call("overview");
  if (stale()) return false;
  let task;
  if (selectedId) task = next.tasks.find(item => item.task_id === selectedId);
  else task = next.tasks.find(item => tones[item.status] === "attention") || next.tasks[0];
  if (selectedId && !task) {
    try { task = await call("status", {task_id: selectedId}); }
    catch (e) { if (e.http_status !== 404) throw e; }
    if (stale()) return false;
    if (!task) message("notice", t("taskGone"));
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
  $("connection").textContent = t("connected") + (next.server_version ? " · " + next.server_version : "");
  const device = next.device;
  const state = t(device.ready === true ? "unlocked" : device.device_locked || device.keyguard_showing ? "locked" :
    device.interactive === false ? "screenOff" : "screenUnknown");
  const helper = device.helper_available == null ? "" : t(device.helper_available ? "helperOnline" : "helperOffline");
  $("device").textContent = [state, helper].filter(Boolean).join(" · ");
  const brief = device.keyguard_showing || device.device_locked || device.interactive === false ? state : helper;
  connectionSummary("connected", [t("phoneConnected"), brief].filter(Boolean).join(" · "));
  $("updated").textContent = t("autoUpdate") + " · " + new Date().toLocaleTimeString();
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
  $("connection").textContent = t(picking ? "awaitingChoice" : "awaitingReconnect");
  let summary = t("rereading");
  if (picking) summary = t("pickPhone");
  else if (e.code === "device_offline" || e.code === "device_selection") summary = t("waitPhone");
  connectionSummary(picking ? "choose" : "retry", summary);
  $("device").textContent = t("toReread");
  const detail = errors[e.code] ? t(errors[e.code]) : e.message || t("errFallback");
  if (e.code === "outcome_unknown") writeWarning = detail;
  message("error", [writeWarning !== detail && writeWarning, detail, overview && t("keptList")].filter(Boolean).join(" "));
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
    message("notice", t("taskAccepted"));
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
    message("notice", t(receipt.accepted ? (action === "answer" ? "answerAccepted" : "steerAccepted") : "notAccepted"));
    await refresh();
  });
});
$("cancel").addEventListener("click", () => {
  if (!online || !confirm(t("confirmCancel"))) return;
  work(async () => {
    const receipt = await call("cancel", {task_id: selectedId});
    message("notice", t(receipt.accepted ? "cancelSent" : "cancelRejected"));
    await refresh();
  });
});

document.addEventListener("visibilitychange", () => {
  invalidateUpdate();
  scheduleUpdate(true);
});
render();
scheduleUpdate(true);
