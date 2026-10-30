import {createServer} from 'node:http';
import {createHash} from 'node:crypto';
export function createGatewayServer({token:gatewayToken,journal:deliveryCache,ready,sendText}) {
const server = createServer(async (request, response) => {
  if (request.method === "GET" && request.url === "/health") {
    return json(response, ready() ? 200 : 503, {
      status: ready() ? "ok" : "starting",
      service: "qq-gateway",
    });
  }
  if(request.method==='GET'&&request.url.startsWith('/internal/deliveries/')) {
    if(request.headers['x-gateway-token']!==gatewayToken)return json(response,401,{detail:'invalid gateway token'});
    try{return json(response,200,deliveryCache.status(decodeURIComponent(request.url.slice('/internal/deliveries/'.length))));}
    catch{return json(response,503,{status:'unknown'});}
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
    if(!ready())return json(response,503,{detail:"gateway not ready",outcome:"failed"});
    const target = {
      scope: "group",
      targetId: body.group_openid,
      ...(body.reply_to ? { msgId: body.reply_to } : {}),
    };
    const { result, repeated } = await deliveryCache.run(
      idempotencyKey,
      createHash("sha256").update(body.group_openid+"\n"+body.content).digest("hex"),
      () => sendText(target, body.content),
    );
    return json(response, 200, {
      id: result?.id || result?.message_id || null,
      status: "sent",
      idempotent: repeated,
    });
  } catch (error) {
    console.error("[ActivityAssistant] 主动消息发送失败", error);
    return json(response, error?.outcome==='conflict'?409:502, { detail: "delivery requires reconciliation", outcome:error?.outcome||"unknown" });
  }
});
return server;
}
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
