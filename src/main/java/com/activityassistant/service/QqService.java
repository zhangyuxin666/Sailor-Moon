package com.activityassistant.service;

import com.activityassistant.config.AppProperties;
import com.activityassistant.integration.AiClient;
import com.activityassistant.integration.QqGatewayClient;
import com.activityassistant.persistence.mapper.QqMapper;
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
    private final QqMapper bindings;
    private final AppProperties properties;
    private final QqGatewayClient gateway;
    private final AiClient ai;
    private OutboundDeliveryService outbound;
    @org.springframework.beans.factory.annotation.Autowired
    public void outbound(OutboundDeliveryService outbound){this.outbound=outbound;}
    private final SecureRandom random = new SecureRandom();

    public QqService(QqMapper bindings, AppProperties properties, QqGatewayClient gateway, AiClient ai) {
        this.bindings = bindings;
        this.properties = properties;
        this.gateway = gateway;
        this.ai = ai;
    }

    public Map<String, Object> status(String userId) {
        List<Map<String, Object>> groups = bindings.listUserGroups(userId);
        boolean online = properties.qqConfigured() && gateway.healthy();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", properties.qqConfigured());
        result.put("gateway", Map.of("status", online ? "online" : "offline", "detail", online ? "Node.js Gateway 已连接" : "Gateway 未启动"));
        result.put("groups", groups);
        result.put("binding_code", properties.qqConfigured() && canBindGroups(userId) ? bindingCode(userId) : null);
        result.put("public_base_url", properties.publicBaseUrl().replaceAll("/$", ""));
        return result;
    }

    public List<Map<String, Object>> authorizedGroups(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) return List.of();
        return bindings.listAuthorizedGroups(organizationId);
    }

    public boolean isGroupAuthorized(String organizationId, String groupOpenid) {
        if (organizationId == null || organizationId.isBlank() || groupOpenid == null || groupOpenid.isBlank()) return false;
        return bindings.findAuthorization(organizationId, groupOpenid).isPresent();
    }

    @Transactional
    public String processInbound(String groupOpenid, String senderOpenid, String senderName,
                                 String content, String messageId, String timestamp) {
        if (bindings.findInbound(messageId).isPresent()) return null;
        String now = timestamp == null || timestamp.isBlank() ? LocalDateTime.now().toString() : timestamp;
        bindings.insertInbound(messageId, groupOpenid, senderOpenid, senderName, content, now);
        bindings.touchBinding(now, groupOpenid);
        Matcher matcher = BINDING.matcher(content == null ? "" : content.trim());
        if (matcher.find()) {
            String userId = bind(matcher.group(1), groupOpenid);
            return userId == null ? "绑定码无效或已过期，请回到活动管家网页获取新绑定码。"
                    : "绑定成功！活动管家已连接此群，网页用户 " + userId + " 创建活动后会自动发布。";
        }
        Map<String, Object> currentActivity = latestActivity(groupOpenid);
        String command = commandReply(content == null ? "" : content.trim(), currentActivity);
        if (command != null) return command;
        if (currentActivity == null) return "本群暂无可访问的活动。发送“帮助”查看可用指令。";
        String organizationId = String.valueOf(currentActivity.get("organization_id"));
        return ai.reply(content, activityContext(currentActivity), organizationId);
    }

    public Map<String, Object> sendGroup(String groupOpenid, String content) {
        return sendGroup(groupOpenid, content, "qq:" + sha256(groupOpenid + "\n" + content));
    }

    public Map<String, Object> sendGroup(String groupOpenid, String content, String idempotencyKey) {
        String recentMessage = bindings.findRecentMessage(groupOpenid)
                .map(row -> String.valueOf(row.get("message_id"))).orElse(null);
        return outbound==null?gateway.sendGroup(groupOpenid,content,recentMessage,idempotencyKey):outbound.send(groupOpenid,content,recentMessage,idempotencyKey);
    }

    public String attachActivity(String userId, String activityId) {
        String group = bindings.findFirstGroup(userId)
                .map(row -> String.valueOf(row.get("group_openid"))).orElse(null);
        if (group != null) bindings.insertChannel(activityId, userId, group, LocalDateTime.now().toString());
        return group;
    }

    private boolean canBindGroups(String userId) {
        if (userId == null || userId.isBlank()) return false;
        return bindings.findManager(userId).isPresent();
    }

    private String bindingCode(String userId) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> existing = bindings.findRecentCode(userId).orElse(null);
        if (existing != null && LocalDateTime.parse(String.valueOf(existing.get("expires_at"))).isAfter(now)) return String.valueOf(existing.get("code"));
        bindings.deleteUserCodes(userId);
        String code;
        do code = String.valueOf(100000 + random.nextInt(900000));
        while (bindings.findCode(code).isPresent());
        bindings.insertCode(code, userId, now.plusMinutes(30).toString(), now.toString());
        return code;
    }

    private String bind(String code, String groupOpenid) {
        Map<String, Object> row = bindings.lockCode(code).orElse(null);
        if (row == null || !LocalDateTime.parse(String.valueOf(row.get("expires_at"))).isAfter(LocalDateTime.now())) return null;
        String userId = String.valueOf(row.get("user_id"));
        if (!canBindGroups(userId)) return null;
        String now = LocalDateTime.now().toString();
        // The conflict predicate is evaluated by PostgreSQL under the row lock. A second
        // concurrent binding cannot steal a group after an earlier cross-organization check.
        int changed = bindings.bindGroup(groupOpenid, userId, now, now);
        if (changed == 0) return null;
        bindings.consumeCode(code);
        return userId;
    }

    private String commandReply(String content, Map<String, Object> activity) {
        if (content.contains("你是什么") || content.contains("你是谁") || content.contains("介绍一下"))
            return "我是活动管家，一个社团和班级活动组织助手。\n我可以自动发布报名、同步任务、催办负责人、发送定时提醒和汇总活动进度。\n发送“帮助”查看可用指令。";
        if (List.of("帮助", "help", "/help", "菜单").contains(content))
            return "【活动管家指令】\n• 活动状态：查看最近活动进度\n• 报名链接：获取最近活动报名入口\n• 帮助：查看本指令列表\n也可以直接用自然语言问我活动相关问题。";
        if (List.of("活动状态", "进度", "当前活动").contains(content)) {
            if (activity == null) return "本群还没有活动，请先在活动管家网页发起活动。";
            List<Map<String, Object>> counts = bindings.listTaskCounts(activity.get("id"));
            long total = counts.stream().mapToLong(row -> ((Number) row.get("count")).longValue()).sum();
            long done = counts.stream().filter(row -> "done".equals(row.get("status"))).mapToLong(row -> ((Number) row.get("count")).longValue()).sum();
            long registrations = bindings.countRegistrations(activity.get("id"))
                    .map(row -> ((Number) row.get("count")).longValue()).orElse(0L);
            return "【" + activity.get("title") + "】\n状态：" + activity.get("status") + "\n任务：已完成 " + done + "/" + total + "\n报名：" + registrations + " 人";
        }
        if (List.of("报名链接", "报名", "怎么报名").contains(content)) {
            Map<String, Object> form = activity == null ? null : bindings.findForm(activity.get("id")).orElse(null);
            return form == null ? "本群目前没有可用的活动报名表。" : "【" + form.get("title") + "】报名链接：" +
                    properties.publicBaseUrl().replaceAll("/$", "") + "/forms/" + form.get("id");
        }
        return null;
    }

    private String activityContext(Map<String, Object> activity) {
        if (activity == null) return "本群尚未创建活动。";
        List<Map<String, Object>> tasks = bindings.listTasks(activity.get("id"));
        long count = bindings.countRegistrations(activity.get("id"))
                .map(row -> ((Number) row.get("count")).longValue()).orElse(0L);
        String taskText = tasks.isEmpty() ? "暂无任务" : tasks.stream()
                .map(task -> task.get("title") + "（" + task.get("assignee") + "，" + task.get("status") + "）")
                .reduce((a, b) -> a + "；" + b).orElse("");
        return "活动：" + activity.get("title") + "；状态：" + activity.get("status") + "；报名人数：" + count + "；任务：" + taskText;
    }

    private Map<String, Object> latestActivity(String groupOpenid) {
        Map<String, Object> activity = bindings.findLatestActivity(groupOpenid).orElse(null);
        if (activity == null || !isGroupAuthorized(String.valueOf(activity.get("organization_id")), groupOpenid)) return null;
        return activity;
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
