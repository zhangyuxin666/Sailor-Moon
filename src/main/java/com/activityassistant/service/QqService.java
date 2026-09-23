package com.activityassistant.service;

import com.activityassistant.config.AppProperties;
import com.activityassistant.integration.AiClient;
import com.activityassistant.integration.QqGatewayClient;
import com.activityassistant.persistence.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class QqService {
    private static final Pattern BINDING = Pattern.compile("绑定\\s*(\\d{6})");
    private final Db db;
    private final AppProperties properties;
    private final QqGatewayClient gateway;
    private final AiClient ai;
    private final SecureRandom random = new SecureRandom();

    public QqService(Db db, AppProperties properties, QqGatewayClient gateway, AiClient ai) {
        this.db = db;
        this.properties = properties;
        this.gateway = gateway;
        this.ai = ai;
    }

    public Map<String, Object> status(String userId) {
        List<Map<String, Object>> groups = db.list("SELECT group_openid, group_label, status, last_seen_at FROM qq_bindings " +
                "WHERE user_id=? AND status='active' ORDER BY created_at", userId);
        boolean online = properties.qqConfigured() && gateway.healthy();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", properties.qqConfigured());
        result.put("gateway", Map.of("status", online ? "online" : "offline", "detail", online ? "Node.js Gateway 已连接" : "Gateway 未启动"));
        result.put("groups", groups);
        result.put("binding_code", properties.qqConfigured() && groups.isEmpty() ? bindingCode(userId) : null);
        result.put("public_base_url", properties.publicBaseUrl().replaceAll("/$", ""));
        return result;
    }

    @Transactional
    public String processInbound(String groupOpenid, String senderOpenid, String senderName,
                                 String content, String messageId, String timestamp) {
        if (db.one("SELECT 1 FROM qq_inbound_events WHERE message_id=?", messageId).isPresent()) return null;
        String now = timestamp == null || timestamp.isBlank() ? LocalDateTime.now().toString() : timestamp;
        db.update("INSERT INTO qq_inbound_events (message_id, group_openid, sender_openid, sender_name, content, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                messageId, groupOpenid, senderOpenid, senderName, content, now);
        db.update("UPDATE qq_bindings SET last_seen_at=? WHERE group_openid=?", now, groupOpenid);
        Matcher matcher = BINDING.matcher(content == null ? "" : content.trim());
        if (matcher.find()) {
            String userId = bind(matcher.group(1), groupOpenid);
            return userId == null ? "绑定码无效或已过期，请回到活动管家网页获取新绑定码。"
                    : "绑定成功！活动管家已连接此群，网页用户 " + userId + " 创建活动后会自动发布。";
        }
        String command = commandReply(groupOpenid, content == null ? "" : content.trim());
        if (command != null) return command;
        String organizationId = db.one("SELECT ac.organization_id FROM activity_channels ch JOIN activity_classes ac ON ac.activity_id=ch.activity_id " +
                "WHERE ch.group_openid=? ORDER BY ch.created_at DESC LIMIT 1", groupOpenid)
                .map(row -> String.valueOf(row.get("organization_id"))).orElse("");
        return ai.reply(content, activityContext(groupOpenid), organizationId);
    }

    public Map<String, Object> sendGroup(String groupOpenid, String content) {
        return sendGroup(groupOpenid, content, "qq:" + sha256(groupOpenid + "\n" + content));
    }

    public Map<String, Object> sendGroup(String groupOpenid, String content, String idempotencyKey) {
        String recentMessage = db.one("SELECT message_id FROM qq_inbound_events WHERE group_openid=? ORDER BY created_at DESC LIMIT 1", groupOpenid)
                .map(row -> String.valueOf(row.get("message_id"))).orElse(null);
        return gateway.sendGroup(groupOpenid, content, recentMessage, idempotencyKey);
    }

    public String attachActivity(String userId, String activityId) {
        String group = db.one("SELECT group_openid FROM qq_bindings WHERE user_id=? AND status='active' ORDER BY created_at LIMIT 1", userId)
                .map(row -> String.valueOf(row.get("group_openid"))).orElse(null);
        if (group != null) db.update("INSERT INTO activity_channels (activity_id, user_id, group_openid, created_at) VALUES (?, ?, ?, ?)",
                activityId, userId, group, LocalDateTime.now().toString());
        return group;
    }

    private String bindingCode(String userId) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> existing = db.one("SELECT code, expires_at FROM qq_binding_codes WHERE user_id=? ORDER BY created_at DESC LIMIT 1", userId).orElse(null);
        if (existing != null && LocalDateTime.parse(String.valueOf(existing.get("expires_at"))).isAfter(now)) return String.valueOf(existing.get("code"));
        db.update("DELETE FROM qq_binding_codes WHERE user_id=?", userId);
        String code;
        do code = String.valueOf(100000 + random.nextInt(900000));
        while (db.one("SELECT 1 FROM qq_binding_codes WHERE code=?", code).isPresent());
        db.update("INSERT INTO qq_binding_codes (code, user_id, expires_at, created_at) VALUES (?, ?, ?, ?)",
                code, userId, now.plusMinutes(30).toString(), now.toString());
        return code;
    }

    private String bind(String code, String groupOpenid) {
        Map<String, Object> row = db.one("SELECT user_id, expires_at FROM qq_binding_codes WHERE code=?", code).orElse(null);
        if (row == null || !LocalDateTime.parse(String.valueOf(row.get("expires_at"))).isAfter(LocalDateTime.now())) return null;
        String userId = String.valueOf(row.get("user_id"));
        String now = LocalDateTime.now().toString();
        db.update("INSERT INTO qq_bindings (group_openid, user_id, group_label, status, last_seen_at, created_at) " +
                        "VALUES (?, ?, '已绑定 QQ 群', 'active', ?, ?) ON CONFLICT (group_openid) DO UPDATE SET " +
                        "user_id=EXCLUDED.user_id, group_label=EXCLUDED.group_label, status='active', last_seen_at=EXCLUDED.last_seen_at",
                groupOpenid, userId, now, now);
        db.update("DELETE FROM qq_binding_codes WHERE code=?", code);
        return userId;
    }

    private String commandReply(String groupOpenid, String content) {
        if (content.contains("你是什么") || content.contains("你是谁") || content.contains("介绍一下"))
            return "我是活动管家，一个社团和班级活动组织助手。\n我可以自动发布报名、同步任务、催办负责人、发送定时提醒和汇总活动进度。\n发送“帮助”查看可用指令。";
        if (List.of("帮助", "help", "/help", "菜单").contains(content))
            return "【活动管家指令】\n• 活动状态：查看最近活动进度\n• 报名链接：获取最近活动报名入口\n• 帮助：查看本指令列表\n也可以直接用自然语言问我活动相关问题。";
        if (List.of("活动状态", "进度", "当前活动").contains(content)) {
            Map<String, Object> activity = latestActivity(groupOpenid);
            if (activity == null) return "本群还没有活动，请先在活动管家网页发起活动。";
            List<Map<String, Object>> counts = db.list("SELECT status, COUNT(*) AS count FROM tasks WHERE activity_id=? GROUP BY status", activity.get("id"));
            long total = counts.stream().mapToLong(row -> ((Number) row.get("count")).longValue()).sum();
            long done = counts.stream().filter(row -> "done".equals(row.get("status"))).mapToLong(row -> ((Number) row.get("count")).longValue()).sum();
            long registrations = db.one("SELECT COUNT(*) AS count FROM registrations r JOIN forms f ON f.id=r.form_id WHERE f.activity_id=?", activity.get("id"))
                    .map(row -> ((Number) row.get("count")).longValue()).orElse(0L);
            return "【" + activity.get("title") + "】\n状态：" + activity.get("status") + "\n任务：已完成 " + done + "/" + total + "\n报名：" + registrations + " 人";
        }
        if (List.of("报名链接", "报名", "怎么报名").contains(content)) {
            Map<String, Object> form = db.one("SELECT f.id, a.title FROM forms f JOIN activities a ON a.id=f.activity_id " +
                    "JOIN activity_channels c ON c.activity_id=a.id WHERE c.group_openid=? ORDER BY a.created_at DESC LIMIT 1", groupOpenid).orElse(null);
            return form == null ? "本群目前没有可用的活动报名表。" : "【" + form.get("title") + "】报名链接：" +
                    properties.publicBaseUrl().replaceAll("/$", "") + "/forms/" + form.get("id");
        }
        return null;
    }

    private String activityContext(String groupOpenid) {
        Map<String, Object> activity = latestActivity(groupOpenid);
        if (activity == null) return "本群尚未创建活动。";
        List<Map<String, Object>> tasks = db.list("SELECT title, assignee, status FROM tasks WHERE activity_id=?", activity.get("id"));
        long count = db.one("SELECT COUNT(*) AS count FROM registrations r JOIN forms f ON f.id=r.form_id WHERE f.activity_id=?", activity.get("id"))
                .map(row -> ((Number) row.get("count")).longValue()).orElse(0L);
        String taskText = tasks.isEmpty() ? "暂无任务" : tasks.stream()
                .map(task -> task.get("title") + "（" + task.get("assignee") + "，" + task.get("status") + "）")
                .reduce((a, b) -> a + "；" + b).orElse("");
        return "活动：" + activity.get("title") + "；状态：" + activity.get("status") + "；报名人数：" + count + "；任务：" + taskText;
    }

    private Map<String, Object> latestActivity(String groupOpenid) {
        return db.one("SELECT a.id, a.title, a.status FROM activities a JOIN activity_channels c ON c.activity_id=a.id " +
                "WHERE c.group_openid=? ORDER BY a.created_at DESC LIMIT 1", groupOpenid).orElse(null);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
