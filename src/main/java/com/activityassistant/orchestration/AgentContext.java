package com.activityassistant.orchestration;

/**
 * 所有智能体共享的调用上下文。
 *
 * 它回答四个问题：谁发起的、属于哪个组织、针对哪个班级和活动、这次是哪一次运行。
 * 敏感信息（密码、联系方式）不进入这里。
 *
 * 序列化时由全局 SNAKE_CASE 策略输出为 user_id、organization_id 等字段名。
 */
public record AgentContext(
        String userId,
        String organizationId,
        String classId,
        String activityId,
        String runId,
        String requestId) {
}
