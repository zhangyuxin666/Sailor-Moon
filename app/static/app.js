const state = {
  userId: "",
  account: null,
  activityId: localStorage.getItem("activity-id") || "",
  runId: "",
  detail: null,
};

const $ = (selector) => document.querySelector(selector);
const labels = {
  generate_plan: "生成活动策划",
  create_form: "创建报名问卷",
  assign_tasks: "分配任务并通知",
  create_calendar_event: "创建日历事件",
  schedule_reminder: "安排定时提醒",
  publish_qq: "发布到 QQ 活动群",
};

function toast(message, type = "success") {
  const node = $("#toast");
  node.textContent = message;
  node.className = `toast show ${type === "error" ? "error" : ""}`;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => node.className = "toast", 2800);
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
  });
  const contentType = response.headers.get("content-type") || "";
  const data = contentType.includes("application/json") ? await response.json() : await response.text();
  if (!response.ok) {
    const message = data?.detail || data || `请求失败（${response.status}）`;
    throw new Error(Array.isArray(message) ? message.map(item => item.msg).join("；") : message);
  }
  return data;
}

function formatDate(value) {
  if (!value) return "时间待定";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("zh-CN", {
    month: "long", day: "numeric", weekday: "short", hour: "2-digit", minute: "2-digit",
  }).format(date);
}

function setRunStatus(title, detail, mode = "loading") {
  const box = $("#runStatus");
  box.classList.remove("hidden", "error");
  if (mode === "error") box.classList.add("error");
  $("#runStatusTitle").textContent = title;
  $("#runStatusDetail").textContent = detail;
}

async function launchActivity(event) {
  event.preventDefault();
  const text = $("#activityText").value.trim();
  const classId = $("#classSelect").value;
  if (!text || !classId) return toast("请填写活动需求并选择班级", "error");
  $("#launchButton").disabled = true;
  setRunStatus("正在创建活动", "需求已提交，等待 Agent 接手…");
  $("#workspace").classList.add("hidden");

  try {
    const result = await api("/activities", {
      method: "POST",
      body: JSON.stringify({ text, class_id: classId, publish_to_qq: $("#publishToQq").checked }),
    });
    state.activityId = result.activity_id;
    state.runId = result.run_id;
    localStorage.setItem("activity-id", state.activityId);
    await pollRun();
  } catch (error) {
    setRunStatus("活动创建失败", error.message, "error");
    toast(error.message, "error");
  } finally {
    $("#launchButton").disabled = false;
  }
}

async function pollRun() {
  const startedAt = Date.now();
  while (Date.now() - startedAt < 120000) {
    const run = await api(`/runs/${state.runId}`);
    const copy = {
      queued: ["正在排队", "Worker 即将开始处理…"],
      running: ["Agent 正在执行", "生成策划并创建问卷、任务、日历与提醒…"],
    }[run.status];
    if (copy) setRunStatus(copy[0], copy[1]);
    if (run.status === "succeeded") {
      setRunStatus("活动方案已生成", "所有自动化步骤均已完成。", "done");
      await loadActivity();
      setTimeout(() => $("#runStatus").classList.add("hidden"), 1800);
      return;
    }
    if (run.status === "failed") throw new Error(run.error || "后台执行失败，请检查 Worker 日志");
    await new Promise(resolve => setTimeout(resolve, 1200));
  }
  throw new Error("执行时间较长，可稍后刷新页面继续查看");
}

async function loadActivity(silent = false) {
  if (!state.activityId) return;
  try {
    state.detail = await api(`/activities/${state.activityId}`);
    renderActivity(state.detail);
    if (!silent) toast("活动方案已准备好");
  } catch (error) {
    if (!silent) toast(error.message, "error");
  }
}

function renderActivity(detail) {
  const plan = detail.plan || {};
  $("#workspace").classList.remove("hidden");
  $("#activityTitle").textContent = plan.title || detail.activity.title;
  $("#activityStatus").textContent = detail.activity.status === "finished" ? "已复盘" : "准备就绪";
  $("#eventTime").textContent = formatDate(plan.event_time);
  $("#planDescription").textContent = plan.description || "暂无活动说明";
  $("#materialCount").textContent = (plan.materials || []).length;
  $("#materials").innerHTML = (plan.materials || []).map(item => `<span class="tag">${escapeHtml(item)}</span>`).join("") || '<span class="empty-copy">暂无物料</span>';
  $("#calendarLink").href = `/activities/${state.activityId}/calendar.ics`;
  renderTasks(detail.tasks || []);
  renderSteps(detail.steps || []);
  renderReminder(detail.reminders?.[0]);
  renderDeliveries(detail.deliveries || []);
  refreshStats(true);
}

function renderDeliveries(deliveries) {
  const sent = deliveries.filter(item => item.status === "sent").length;
  const failed = deliveries.filter(item => item.status === "failed").length;
  $("#deliverySummary").innerHTML = `<i></i> ${sent} 成功${failed ? ` · ${failed} 失败` : ""}`;
  const kindLabels = { announcement: "活动发布", reminder: "定时提醒", manual: "手动催办" };
  $("#deliveryList").innerHTML = deliveries.length ? deliveries.map(item => `
    <div class="delivery-row">
      <span class="delivery-kind">${kindLabels[item.kind] || escapeHtml(item.kind)}</span>
      <span class="delivery-content">${escapeHtml(item.content)}</span>
      <span class="delivery-status ${item.status === "failed" ? "failed" : ""}">${item.status === "sent" ? "已送达" : "发送失败"}</span>
    </div>
  `).join("") : '<p class="empty-copy">还没有 QQ 投递记录</p>';
}

async function loadQQStatus() {
  try {
    const status = await api("/integrations/qq/status");
    const gateway = status.gateway || {};
    if (!status.configured) {
      $("#qqStatusTitle").textContent = "QQ 机器人待配置";
      $("#qqStatusDetail").textContent = "在 .env 填写 QQ_BOT_APP_ID 和 QQ_BOT_APP_SECRET";
      $("#qqBindingAction").innerHTML = '<span class="connection-dot error"></span>';
      return;
    }
    if (status.groups.length) {
      $("#qqStatusTitle").textContent = "QQ 活动群已绑定";
      $("#qqStatusDetail").textContent = `${status.groups[0].group_label} · ${gateway.detail || "网关状态未知"}`;
      $("#qqBindingAction").innerHTML = `<span class="connection-dot ${gateway.status === "online" ? "online" : "error"}"></span>`;
      return;
    }
    $("#qqStatusTitle").textContent = "等待绑定 QQ 群";
    $("#qqStatusDetail").textContent = "将机器人加入活动群，然后 @机器人发送下面的命令";
    $("#qqBindingAction").innerHTML = `<button class="binding-command" data-copy="绑定 ${status.binding_code}">@机器人 绑定 ${status.binding_code}</button>`;
  } catch (error) {
    $("#qqStatusTitle").textContent = "QQ 状态读取失败";
    $("#qqStatusDetail").textContent = error.message;
  }
}

async function sendManualMessage(event) {
  event.preventDefault();
  if (!state.activityId) return toast("请先创建活动", "error");
  const content = $("#messageContent").value.trim();
  const button = event.target.querySelector("button");
  button.disabled = true;
  try {
    await postActivityMessage(content);
    event.target.reset();
    await loadActivity(true);
    toast("消息已发送到 QQ 群");
  } catch (error) { toast(error.message, "error"); }
  finally { button.disabled = false; }
}

async function postActivityMessage(content) {
  return api(`/activities/${state.activityId}/messages`, {
    method: "POST",
    body: JSON.stringify({ content, request_id: crypto.randomUUID() }),
  });
}

async function nudgeTask(button) {
  const task = state.detail?.tasks?.find(item => item.id === button.dataset.nudgeTask);
  if (!task) return;
  button.disabled = true;
  try {
    await postActivityMessage(`【任务催办】请 ${task.assignee} 尽快完成「${task.title}」，完成后向活动负责人反馈进度。`);
    await loadActivity(true);
    toast(`已在 QQ 群催办 ${task.assignee}`);
  } catch (error) { toast(error.message, "error"); }
  finally { button.disabled = false; }
}

function renderTasks(tasks) {
  const done = tasks.filter(task => task.status === "done").length;
  $("#taskProgress").textContent = `${done}/${tasks.length}`;
  $("#taskList").innerHTML = tasks.map(task => `
    <label class="task-item ${task.status === "done" ? "done" : ""}">
      <input class="task-toggle" type="checkbox" data-task-id="${task.id}" ${task.status === "done" ? "checked" : ""}>
      <span><strong>${escapeHtml(task.title)}</strong><small>等待负责人执行</small></span>
      <span class="task-owner"><span class="assignee">${escapeHtml(task.assignee)}</span><button class="task-nudge" type="button" data-nudge-task="${task.id}">催一下</button></span>
    </label>
  `).join("") || '<p class="empty-copy">暂无任务</p>';
}

function renderSteps(steps) {
  $("#stepList").innerHTML = steps.map(step => `
    <div class="step-item"><span class="step-check">✓</span><div><strong>${labels[step.step] || escapeHtml(step.step)}</strong><small>${escapeHtml(step.detail || "执行完成")}</small></div></div>
  `).join("");
}

function renderReminder(reminder) {
  if (!reminder) {
    $("#reminderContent").innerHTML = '<p class="empty-copy">没有提醒任务</p>';
    $("#reminderState").textContent = "未安排";
    return;
  }
  const cancelled = reminder.status === "cancelled";
  $("#reminderState").textContent = cancelled ? "已取消" : reminder.status === "sent" ? "已发送" : "已安排";
  $("#reminderContent").innerHTML = `
    <div class="reminder-card"><strong>${formatDate(reminder.remind_at)}</strong><span>${escapeHtml(reminder.message)}</span>
    ${cancelled ? '<small>该提醒已取消</small>' : `<button class="danger-button" data-reminder-id="${reminder.id}" type="button">取消提醒</button>`}</div>`;
}

async function toggleTask(input) {
  input.disabled = true;
  try {
    const status = input.checked ? "done" : "pending";
    await api(`/tasks/${input.dataset.taskId}`, {
      method: "PATCH", body: JSON.stringify({ status }),
    });
    await loadActivity(true);
    toast(status === "done" ? "任务已完成" : "任务已恢复");
  } catch (error) {
    input.checked = !input.checked;
    toast(error.message, "error");
  } finally {
    input.disabled = false;
  }
}

async function submitRegistration(event) {
  event.preventDefault();
  const formId = state.detail?.forms?.[0]?.id;
  if (!formId) return toast("当前活动没有报名表", "error");
  try {
    await api(`/forms/${formId}/registrations`, {
      method: "POST",
      body: JSON.stringify({ name: $("#registrantName").value.trim(), contact: $("#registrantContact").value.trim(), extra: {} }),
    });
    event.target.reset();
    await refreshStats(true);
    toast("报名成功");
  } catch (error) { toast(error.message, "error"); }
}

async function refreshStats(silent = false) {
  if (!state.activityId) return;
  try {
    const stats = await api(`/activities/${state.activityId}/form-stats`);
    $("#signupCount").textContent = stats.count;
    $("#registrationList").innerHTML = stats.registrations.length ? stats.registrations.map(item => `
      <div class="registration-row"><strong>${escapeHtml(item.name)}</strong><span>${escapeHtml(item.contact)}</span><span>${formatDate(item.created_at)}</span></div>
    `).join("") : '<p class="empty-copy">还没有人报名</p>';
    if (!silent) toast("报名数据已刷新");
  } catch (error) {
    if (!silent) toast(error.message, "error");
  }
}

async function cancelReminder(button) {
  button.disabled = true;
  try {
    await api(`/reminders/${button.dataset.reminderId}/cancel`, { method: "POST" });
    await loadActivity(true);
    toast("提醒已取消");
  } catch (error) { toast(error.message, "error"); }
}

async function generateRecap() {
  const button = $("#recapButton");
  button.disabled = true;
  button.textContent = "生成中…";
  try {
    const result = await api(`/activities/${state.activityId}/recap`, { method: "POST" });
    $("#recapContent").textContent = result.recap;
    $("#activityStatus").textContent = "已复盘";
    toast("复盘已生成");
  } catch (error) { toast(error.message, "error"); }
  finally { button.disabled = false; button.textContent = "重新生成"; }
}

function escapeHtml(value) {
  const node = document.createElement("div");
  node.textContent = String(value ?? "");
  return node.innerHTML;
}

$("#launchForm").addEventListener("submit", launchActivity);
$("#registrationForm").addEventListener("submit", submitRegistration);
$("#messageForm").addEventListener("submit", sendManualMessage);
$("#refreshStats").addEventListener("click", () => refreshStats());
$("#recapButton").addEventListener("click", generateRecap);
document.addEventListener("click", event => {
  const example = event.target.closest("[data-example]");
  if (example) $("#activityText").value = example.dataset.example;
  const reminder = event.target.closest("[data-reminder-id]");
  if (reminder) cancelReminder(reminder);
  const navItem = event.target.closest(".nav-item");
  if (navItem) {
    document.querySelectorAll(".nav-item").forEach(item => item.classList.remove("active"));
    navItem.classList.add("active");
  }
  const copyButton = event.target.closest("[data-copy]");
  if (copyButton) navigator.clipboard.writeText(copyButton.dataset.copy).then(() => toast("绑定命令已复制"));
  const nudgeButton = event.target.closest("[data-nudge-task]");
  if (nudgeButton) nudgeTask(nudgeButton);
});
document.addEventListener("change", event => {
  if (event.target.matches("[data-task-id]")) toggleTask(event.target);
});
$("#activityHistory").addEventListener("change", event => {
  if (!event.target.value) return;
  state.activityId = event.target.value;
  localStorage.setItem("activity-id", state.activityId);
  loadActivity();
});

async function initializeWorkspace() {
  const auth = await api("/auth/status");
  if (!auth.account) return location.replace("/");
  if (auth.account.role !== "manager") return location.replace("/portal");
  state.account = auth.account;
  state.userId = auth.account.username;
  const [dashboard, history] = await Promise.all([api("/portal/dashboard"), api("/portal/activities")]);
  $("#classSelect").innerHTML = (dashboard.classes || []).map(item => `<option value="${item.id}">${escapeHtml(item.name)}</option>`).join("");
  $("#activityHistory").innerHTML = '<option value="">新建活动</option>' + (history.activities || []).map(item => `<option value="${item.id}">${escapeHtml(item.title)} · ${escapeHtml(item.status)}</option>`).join("");
  await loadQQStatus();
  if (state.activityId && history.activities.some(item => item.id === state.activityId)) await loadActivity(true);
}

initializeWorkspace().catch(error => toast(error.message, "error"));
setInterval(() => {
  loadQQStatus();
  if (state.activityId && !$("#workspace").classList.contains("hidden")) loadActivity(true);
}, 10000);
