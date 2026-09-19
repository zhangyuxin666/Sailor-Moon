const form = document.querySelector("#authForm");
let initialized = true;

async function request(path, options = {}) {
  const response = await fetch(path, { ...options, headers: { "Content-Type": "application/json", ...(options.headers || {}) } });
  const data = await response.json();
  if (!response.ok) throw new Error(data.detail || "请求失败");
  return data;
}

async function initialize() {
  const status = await request("/auth/status");
  if (status.account) return location.replace("/portal");
  initialized = status.initialized;
  if (!initialized) {
    document.querySelector("#authEyebrow").textContent = "INITIAL SETUP";
    document.querySelector("#authTitle").textContent = "创建管理者账号";
    document.querySelector("#authDescription").textContent = "首次使用，请先建立班级管理者账号。";
    document.querySelector("#displayNameField").classList.remove("hidden");
    document.querySelector("#organizationField").classList.remove("hidden");
    document.querySelector("#privacyField").classList.remove("hidden");
    document.querySelector("#displayName").required = true;
    document.querySelector("#authButton").childNodes[0].textContent = "完成初始化 ";
  }
}

form.addEventListener("submit", async event => {
  event.preventDefault();
  const errorBox = document.querySelector("#authError");
  const button = document.querySelector("#authButton");
  errorBox.classList.add("hidden");
  button.disabled = true;
  try {
    const body = { username: document.querySelector("#username").value.trim(), password: document.querySelector("#password").value };
    if (!initialized) {
      body.display_name = document.querySelector("#displayName").value.trim();
      body.organization_name = document.querySelector("#organizationName").value.trim();
      body.accept_privacy = document.querySelector("#acceptPrivacy").checked;
    }
    await request(initialized ? "/auth/login" : "/auth/bootstrap", { method: "POST", body: JSON.stringify(body) });
    location.replace("/portal");
  } catch (error) {
    errorBox.textContent = error.message;
    errorBox.classList.remove("hidden");
  } finally { button.disabled = false; }
});

initialize().catch(error => { document.querySelector("#authError").textContent = error.message; document.querySelector("#authError").classList.remove("hidden"); });
