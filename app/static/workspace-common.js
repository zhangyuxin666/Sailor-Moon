window.App = {
  async api(path, options = {}) {
    const response = await fetch(path, options);
    const type = response.headers.get("content-type") || "";
    const data = type.includes("json") ? await response.json() : await response.text();
    if (!response.ok) throw new Error(data.detail || data || "请求失败");
    return data;
  },
  escape(value) { const node=document.createElement("div"); node.textContent=String(value??""); return node.innerHTML; },
  date(value) { if(!value)return "未设置"; const date=new Date(value); return Number.isNaN(date.getTime())?value:new Intl.DateTimeFormat("zh-CN",{month:"short",day:"numeric",hour:"2-digit",minute:"2-digit"}).format(date); },
  toast(message, error=false) { const node=document.querySelector("#wsToast"); node.textContent=message; node.className=`ws-toast show ${error?"error":""}`; clearTimeout(this.toastTimer); this.toastTimer=setTimeout(()=>node.className="ws-toast",2600); },
  async init(active) {
    const auth=await this.api("/auth/status");
    if(!auth.account){location.replace("/");return null;}
    document.querySelectorAll(".ws-nav a").forEach(link=>link.classList.toggle("active",link.dataset.nav===active));
    const name=document.querySelector("#shellUserName"); if(name)name.textContent=auth.account.display_name;
    const role=document.querySelector("#shellUserRole"); if(role)role.textContent=auth.account.role==="manager"?"管理者":"参与者";
    const avatar=document.querySelector("#shellAvatar"); if(avatar)avatar.textContent=auth.account.display_name.slice(0,1);
    document.querySelectorAll("[data-manager-only]").forEach(node=>node.classList.toggle("hidden",auth.account.role!=="manager"));
    return auth.account;
  }
};
