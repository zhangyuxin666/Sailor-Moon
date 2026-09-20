const state = { account: null, dashboard: null, currentClassId: null };
const $ = selector => document.querySelector(selector);

async function api(path, options = {}) {
  const response = await fetch(path, options);
  const contentType = response.headers.get("content-type") || "";
  const data = contentType.includes("json") ? await response.json() : await response.text();
  if (!response.ok) throw new Error(data.detail || data || "请求失败");
  return data;
}

function toast(message, error = false) {
  const node = $("#portalToast");
  node.textContent = message;
  node.className = `portal-toast show ${error ? "error" : ""}`;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => node.className = "portal-toast", 2600);
}

function formatDate(value) {
  return new Intl.DateTimeFormat("zh-CN", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}

async function loadDashboard() {
  const auth = await api("/auth/status");
  if (!auth.account) return location.replace("/login");
  state.account = auth.account;
  state.dashboard = await api("/portal/dashboard");
  state.currentClassId = state.currentClassId || state.dashboard.classes?.[0]?.id || null;
  render();
  if (!state.account.privacy_accepted) $("#privacyDialog").showModal();
  if (state.account.force_password_change) {
    $("#passwordDialog").showModal();
    $("[data-password-close]").classList.add("hidden");
  }
}

function render() {
  const manager = state.account.role === "manager";
  $("#userName").textContent = state.account.display_name;
  $("#userRole").textContent = manager ? "班级管理者" : `参与者 · ${state.account.student_no || ""}`;
  $("#userAvatar").textContent = state.account.display_name.slice(0, 1);
  $("#pageTitle").textContent = manager ? "班级管理总览" : `${state.account.display_name}的待办`;
  $("#managerActions").classList.toggle("hidden", !manager);
  $("#memberNav").classList.toggle("hidden", !manager);
  $("#auditPanel").classList.toggle("hidden", !manager);
  if (manager) renderManager(); else renderParticipant();
}

function renderManager() {
  const classes = state.dashboard.classes || [];
  const todos = state.dashboard.todos || [];
  $("#classCreator").classList.toggle("hidden", classes.length > 0);
  $("#members").classList.toggle("hidden", classes.length === 0);
  $("#currentClassLabel").textContent = classes[0]?.name || "";
  const memberCount = classes.reduce((sum, item) => sum + item.member_count, 0);
  const totalAssigned = todos.reduce((sum, item) => sum + item.total_count, 0);
  const totalDone = todos.reduce((sum, item) => sum + item.done_count, 0);
  const overdue = todos.filter(item => new Date(item.deadline) < new Date() && item.done_count < item.total_count).length;
  $("#overview").innerHTML = metricCards([
    [memberCount, "班级成员", "◎"], [todos.length, "全部待办", "▤"],
    [`${totalDone}/${totalAssigned}`, "完成进度", "✓"], [overdue, "已逾期待办", "!"],
  ]);
  renderTodoCards(todos, true);
}

function renderParticipant() {
  const todos = state.dashboard.todos || [];
  const activityTasks = (state.dashboard.activity_tasks || []).map(item => ({...item, source:"activity", class_name:`活动：${item.activity_title}`, deadline:item.event_time}));
  const allTasks = [...todos, ...activityTasks];
  const done = allTasks.filter(item => item.my_status === "done").length;
  const overdue = todos.filter(item => item.my_status !== "done" && new Date(item.deadline) < new Date()).length;
  $("#overview").innerHTML = metricCards([[allTasks.length - done, "待完成", "▤"], [done, "已完成", "✓"], [overdue, "已逾期", "!"], [allTasks.length, "全部任务", "◎"]]);
  $("#todoSectionTitle").textContent = "我的待办事项";
  renderTodoCards(allTasks, false);
}

function metricCards(items) {
  return items.map(([value, label, icon]) => `<article><span>${icon}</span><div><strong>${value}</strong><small>${label}</small></div></article>`).join("");
}

function renderTodoCards(todos, manager) {
  const filter = $("#todoFilter").value;
  const filtered = todos.filter(item => filter === "all" || (manager ? (filter === "done" ? item.done_count === item.total_count : item.done_count < item.total_count) : item.my_status === filter));
  $("#todoList").innerHTML = filtered.length ? filtered.map(item => {
    const complete = manager ? item.total_count > 0 && item.done_count === item.total_count : item.my_status === "done";
    const progress = manager ? `${item.done_count}/${item.total_count} 已完成` : (complete ? "已提交" : "待完成");
    const dataAttribute = item.source === "activity" ? `data-activity-task-id="${item.id}"` : `data-todo-id="${item.id}"`;
    return `<button class="portal-todo-card" ${dataAttribute}><span class="todo-kind">${item.source === "activity" ? "活动" : item.kind === "homework" ? "作业" : "待办"}</span><div><strong>${escapeHtml(item.title)}</strong><small>${escapeHtml(item.class_name)} · ${formatDate(item.deadline)} 截止</small></div><span class="todo-progress ${complete ? "done" : ""}">${progress}</span><b>›</b></button>`;
  }).join("") : '<div class="portal-empty">这里还没有待办事项</div>';
}

async function openTodo(todoId) {
  const detail = await api(`/todos/${todoId}`);
  const manager = state.account.role === "manager";
  if (manager) {
    const rows = detail.participants.map(person => `<tr><td>${escapeHtml(person.student_no || "-")}</td><td>${escapeHtml(person.display_name)}</td><td><span class="table-status ${person.status}">${person.status === "done" ? "已完成" : "未完成"}</span></td><td>${person.submitted_at ? formatDate(person.submitted_at) : "-"}</td><td>${person.original_filename ? `<a href="/todos/${todoId}/submissions/${person.account_id}/file">下载</a>` : "-"}</td><td><button class="text-action" data-reset-account="${person.account_id}">重置密码</button></td></tr>`).join("");
    $("#todoDialogContent").innerHTML = `<p class="portal-eyebrow">MANAGER VIEW</p><h2>${escapeHtml(detail.todo.title)}</h2><p class="dialog-description">${escapeHtml(detail.todo.description || "暂无说明")}</p><div class="dialog-toolbar"><button class="primary-action" data-remind-now="${todoId}">立即提醒未完成人员</button><label>定时提醒 <input id="remindAt" type="datetime-local"><button data-schedule-reminder="${todoId}">设置</button></label></div><div class="submission-table-wrap"><table><thead><tr><th>学号</th><th>姓名</th><th>状态</th><th>提交时间</th><th>文件</th><th>账号</th></tr></thead><tbody>${rows}</tbody></table></div>`;
  } else {
    const done = detail.assignment.status === "done";
    $("#todoDialogContent").innerHTML = `<p class="portal-eyebrow">MY TODO</p><h2>${escapeHtml(detail.todo.title)}</h2><p class="dialog-description">${escapeHtml(detail.todo.description || "暂无说明")}</p><div class="todo-meta">截止时间：${formatDate(detail.todo.deadline)} · 当前状态：${done ? "已完成" : "待完成"}</div><form id="submissionForm" data-todo-id="${todoId}"><label>提交说明<textarea name="note" rows="4" placeholder="填写完成情况或补充说明">${escapeHtml(detail.assignment.note || "")}</textarea></label><label>上传文件（最大 20MB）<input name="file" type="file"></label><button class="primary-action">${done ? "更新提交" : "确认完成并提交"}</button></form>`;
  }
  $("#todoDialog").showModal();
}

$("#classForm").addEventListener("submit", async event => { event.preventDefault(); try { await api("/classes", { method: "POST", headers: {"Content-Type":"application/json"}, body: JSON.stringify({name:$("#className").value.trim()}) }); event.target.reset(); await loadDashboard(); toast("班级创建成功"); } catch (error) { toast(error.message, true); } });
$("#rosterForm").addEventListener("submit", async event => { event.preventDefault(); const formData=new FormData(); const file=$("#rosterFile").files[0]; if(!file)return toast("请选择 Excel 文件",true); formData.append("file",file); try { const result=await api(`/classes/${state.currentClassId}/members/import-excel`,{method:"POST",body:formData}); event.target.reset(); await loadDashboard(); toast(`已导入 ${result.count} 名成员`); } catch(error){toast(error.message,true);} });
$("#todoForm").addEventListener("submit", async event => { event.preventDefault(); const body={title:$("#todoTitle").value.trim(),kind:$("#todoKind").value,deadline:new Date($("#todoDeadline").value).toISOString(),description:$("#todoDescription").value.trim()}; try { await api(`/classes/${state.currentClassId}/todos`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(body)}); event.target.reset(); await loadDashboard(); toast("已发布给全班并通知 QQ 群"); } catch(error){toast(error.message,true);} });
$("#todoFilter").addEventListener("change", render);
$("#refreshButton").addEventListener("click", loadDashboard);
$("#logoutButton").addEventListener("click", async () => { await api("/auth/logout",{method:"POST"}); location.replace("/login"); });
$("#passwordButton").addEventListener("click", () => $("#passwordDialog").showModal());
$("#acceptPrivacyButton").addEventListener("click", async () => { try { await api("/privacy/consent",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({accept:true})}); $("#privacyDialog").close(); state.account.privacy_accepted=true; toast("已记录隐私同意"); } catch(error){toast(error.message,true);} });
$("#passwordForm").addEventListener("submit", async event => { event.preventDefault(); const form=new FormData(event.target); try { await api("/auth/change-password",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(Object.fromEntries(form.entries()))}); event.target.reset(); $("#passwordDialog").close(); state.account.force_password_change=0; toast("密码已更新"); } catch(error){toast(error.message,true);} });
$("#loadAudit").addEventListener("click", loadAudit);
document.addEventListener("click", async event => { const todo=event.target.closest("[data-todo-id]"); if(todo) openTodo(todo.dataset.todoId).catch(error=>toast(error.message,true)); if(event.target.matches("[data-close]")) $("#todoDialog").close(); if(event.target.matches("[data-password-close]")) $("#passwordDialog").close(); const reset=event.target.closest("[data-reset-account]"); if(reset){const password=prompt("输入新的临时密码（至少 8 位）");if(password){try{await api(`/accounts/${reset.dataset.resetAccount}/reset-password`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({new_password:password})});toast("密码已重置，该成员下次登录必须修改密码");}catch(error){toast(error.message,true);}}} const remind=event.target.closest("[data-remind-now]"); if(remind){try{const result=await api(`/todos/${remind.dataset.remindNow}/remind`,{method:"POST"});toast(result.missing?.length?`已提醒 ${result.missing.length} 人`:result.message);await loadDashboard();}catch(error){toast(error.message,true);}} const schedule=event.target.closest("[data-schedule-reminder]"); if(schedule){const value=$("#remindAt").value;if(!value)return toast("请选择提醒时间",true);try{await api(`/todos/${schedule.dataset.scheduleReminder}/reminders`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({remind_at:new Date(value).toISOString()})});toast("定时提醒已设置");}catch(error){toast(error.message,true);}} });
document.addEventListener("click", async event => { const task=event.target.closest("[data-activity-task-id]"); if(!task)return; if(!confirm("确认已完成这项活动任务？"))return; try{await api(`/tasks/${task.dataset.activityTaskId}`,{method:"PATCH",headers:{"Content-Type":"application/json"},body:JSON.stringify({status:"done"})});await loadDashboard();toast("活动任务已完成");}catch(error){toast(error.message,true);} });
document.addEventListener("submit", async event => { if(event.target.id!=="submissionForm")return; event.preventDefault(); try { await api(`/todos/${event.target.dataset.todoId}/submit`,{method:"POST",body:new FormData(event.target)}); $("#todoDialog").close(); await loadDashboard(); toast("提交成功"); } catch(error){toast(error.message,true);} });
function escapeHtml(value){const node=document.createElement("div");node.textContent=String(value??"");return node.innerHTML;}
async function loadAudit(){try{const data=await api("/audit-logs?limit=50");$("#auditList").innerHTML=data.logs.length?data.logs.map(log=>`<div><strong>${escapeHtml(log.action)}</strong><span>${escapeHtml(log.resource_type||"")} ${escapeHtml(log.resource_id||"")}</span><small>${formatDate(log.created_at)}</small></div>`).join(""):'<p class="portal-empty">暂无操作记录</p>';}catch(error){toast(error.message,true);}}
loadDashboard().catch(error => { if(error.message.includes("登录")) location.replace("/login"); else toast(error.message,true); });
