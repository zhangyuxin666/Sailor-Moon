import { QQBot, mentionGate } from "@tencent-connect/qqbot-nodejs";
import { createServer } from "node:http";
import { IdempotentDeliveryCache } from "./delivery-cache.mjs";

const appId = process.env.QQ_BOT_APP_ID;
const appSecret = process.env.QQ_BOT_APP_SECRET;
const gatewayToken = process.env.QQ_GATEWAY_TOKEN;
const backendUrl = process.env.QQ_GATEWAY_BACKEND_URL || "http://127.0.0.1:8080";
const port = Number(process.env.QQ_GATEWAY_PORT || 3000);
const deliveryCache = new IdempotentDeliveryCache();

if (!appId || !appSecret || !gatewayToken) {
  throw new Error("QQ_BOT_APP_ID、QQ_BOT_APP_SECRET 和 QQ_GATEWAY_TOKEN 必须配置");
}

const bot = new QQBot({ appId, appSecret, logger: console });
bot.use(mentionGate({ requireMentionInGroup: true, ignoreOtherMentions: true }));
let botReady = false;

bot.on("ready", () => {
  botReady = true;
  console.log("[ActivityAssistant] QQ Node 网关已连接");
});
bot.on("error", error => {
  botReady = false;
  console.error("[ActivityAssistant] QQ 网关错误", error);
});
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

const server = createServer(async (request, response) => {
  if (request.method === "GET" && request.url === "/health") {
    return json(response, botReady ? 200 : 503, {
      status: botReady ? "ok" : "starting",
      service: "qq-gateway",
    });
  }
  if (request.method !== "POST" || request.url !== "/internal/send-group") {
    return json(response, 404, { detail: "not found" });
  }
  if (request.headers["x-gateway-token"] !== gatewayToken) {
    return json(response, 401, { detail: "invalid gateway token" });
  }
  try {
    const idempotencyKey = String(request.headers["idempotency-key"] || "");
    if (idempotencyKey.length < 8 || idempotencyKey.length > 200) {
      return json(response, 422, { detail: "valid Idempotency-Key is required" });
    }
    const body = await readJson(request);
    if (!body.group_openid || !body.content) {
      return json(response, 422, { detail: "group_openid and content are required" });
    }
    const target = {
      scope: "group",
      targetId: body.group_openid,
      ...(body.reply_to ? { msgId: body.reply_to } : {}),
    };
    const { result, repeated } = await deliveryCache.run(
      idempotencyKey,
      () => bot.sendText(target, body.content),
    );
    return json(response, 200, {
      id: result?.id || result?.message_id || null,
      status: "sent",
      idempotent: repeated,
    });
  } catch (error) {
    console.error("[ActivityAssistant] 主动消息发送失败", error);
    return json(response, 502, { detail: String(error?.message || error) });
  }
});
server.listen(port, "0.0.0.0", () => console.log(`[ActivityAssistant] QQ Gateway HTTP :${port}`));
bot.start().catch(error => {
  console.error("[ActivityAssistant] QQ Gateway 启动失败", error);
  process.exit(1);
});

function json(response, status, payload) {
  response.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  response.end(JSON.stringify(payload));
}

async function readJson(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 1024 * 1024) throw new Error("request body too large");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
}

await new Promise(() => {});
