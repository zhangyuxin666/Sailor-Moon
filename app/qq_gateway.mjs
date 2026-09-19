import { QQBot, mentionGate } from "@tencent-connect/qqbot-nodejs";

const appId = process.env.QQ_BOT_APP_ID;
const appSecret = process.env.QQ_BOT_APP_SECRET;
const gatewayToken = process.env.QQ_GATEWAY_TOKEN;
const backendUrl = process.env.QQ_GATEWAY_BACKEND_URL || "http://127.0.0.1:8001";

if (!appId || !appSecret || !gatewayToken) {
  throw new Error("QQ_BOT_APP_ID、QQ_BOT_APP_SECRET 和 QQ_GATEWAY_TOKEN 必须配置");
}

const bot = new QQBot({ appId, appSecret, logger: console });
bot.use(mentionGate({ requireMentionInGroup: true, ignoreOtherMentions: true }));

bot.on("ready", () => console.log("[ActivityAssistant] QQ Node 网关已连接"));
bot.on("error", error => console.error("[ActivityAssistant] QQ 网关错误", error));
bot.on("rawEvent", context => {
  if (context.eventType?.includes("MESSAGE")) {
    console.log(`[ActivityAssistant] 收到原始事件 ${context.eventType}`);
  }
});

bot.on("message", async (_context, message) => {
  if (message.kind !== "group") return;
  console.log(`[ActivityAssistant] 群消息 ${message.rawEventType}: ${message.content}`);
  try {
    const response = await fetch(`${backendUrl}/internal/qq/events`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Gateway-Token": gatewayToken,
      },
      body: JSON.stringify({
        event_type: message.rawEventType || "GROUP_MESSAGE_CREATE",
        group_openid: message.groupOpenid,
        sender_openid: message.senderId || "",
        sender_name: message.senderName || "",
        content: message.content || "",
        message_id: message.messageId,
        timestamp: message.timestamp || new Date().toISOString(),
      }),
    });
    if (!response.ok) throw new Error(`backend HTTP ${response.status}`);
    const data = await response.json();
    if (data.reply) await bot.sendText(message.replyTarget, data.reply);
  } catch (error) {
    console.error("[ActivityAssistant] 处理群消息失败", error);
  }
});

await bot.start();
await new Promise(() => {});
