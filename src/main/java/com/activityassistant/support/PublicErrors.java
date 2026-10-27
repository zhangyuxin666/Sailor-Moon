package com.activityassistant.support;

/** Public run errors are categories, never transport responses, SQL or credentials. */
public final class PublicErrors {
    private static final String DEFAULT = "执行失败，请重试；若仍失败请联系管理员";
    private PublicErrors() {}

    public static String message(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        String text = String.valueOf(raw);
        if(text.startsWith("投递结果未知"))return "投递结果未知，已停止自动重发，请核对投递记录";
        if(text.contains("租约")||text.contains("任务超时"))return "执行租约到期或任务超时，请检查运行后重试";
        if (text.startsWith("AI 服务")) return "AI 服务暂时不可用，请稍后重试";
        if (text.startsWith("QQ Gateway") || text.startsWith("QQ 消息"))
            return "QQ 消息暂时未能送达，请检查群连接后重试";
        if (text.contains("负责人")) return "任务负责人已失效或缺少账号信息，请重新确认方案";
        if (text.contains("活动时间") || text.contains("时间格式"))
            return "活动时间无效或尚未确认，请补充后继续";
        if (text.contains("补充") || text.contains("缺失") || text.contains("关键信息"))
            return "请补充页面列出的所需信息后继续";
        if (text.contains("权限") || text.contains("无权") || text.contains("绑定"))
            return "当前账号或通知渠道无权执行此操作，请检查授权";
        return DEFAULT;
    }

    public static String failure(Throwable exception) {
        String safe = exception == null ? null : message(exception.getMessage());
        return safe == null ? DEFAULT : safe;
    }
}
