package com.activityassistant.orchestration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 所有智能体统一的返回结果。
 *
 * status 表示这次运行的运行状态；data 放结构化业务数据；
 * citations 放知识库来源；nextActions 告诉主控下一步该做什么（例如等用户补充信息）；
 * error 只在失败时有值。
 */
public record AgentResult(
        RunStatus status,
        Map<String, Object> data,
        List<String> citations,
        List<String> nextActions,
        String error) {

    public AgentResult {
        Objects.requireNonNull(status, "status");
        // 用 LinkedHashMap 复制：Map.copyOf 不接受 null 值，而业务数据里可能有 null。
        data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
        citations = citations == null ? List.of() : List.copyOf(citations);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
    }
}
