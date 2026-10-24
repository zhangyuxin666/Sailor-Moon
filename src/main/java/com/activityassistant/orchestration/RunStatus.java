package com.activityassistant.orchestration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;
import java.util.Optional;

/**
 * 工作流运行状态。
 *
 * 注意它和 background_jobs.status 是两套状态：队列表关心“任务有没有被执行、试了几次”，
 * 这里关心“业务走到哪一步、是否在等用户补充信息、有没有被取消”。
 *
 * 取值同时受 V3 迁移里的 ck_agent_runs_status 约束保护，非法状态写不进数据库。
 */
public enum RunStatus {
    PENDING("pending"),
    RUNNING("running"),
    WAITING_INPUT("waiting_input"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String wire;

    RunStatus(String wire) {
        this.wire = wire;
    }

    /** 数据库与接口上使用的取值。 */
    @JsonValue
    public String wire() {
        return wire;
    }

    /** 严格解析，遇到未知状态直接报错，用于写入路径。 */
    @JsonCreator
    public static RunStatus parse(String value) {
        return fromWire(value).orElseThrow(
                () -> new IllegalArgumentException("未知的工作流状态: " + value));
    }

    /** 宽松解析，用于读取数据库或外部输入，未知状态返回空。 */
    public static Optional<RunStatus> fromWire(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (RunStatus status : values()) {
            if (status.wire.equals(normalized)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }

    /** 终态：不会再有后续执行。 */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == CANCELLED;
    }

    /**
     * 是否允许变成目标状态。
     *
     * 同状态重复写入视为幂等，直接允许；跨状态转移必须合法。
     * 关键不变量：succeeded 和 cancelled 不允许再变成任何其他状态。
     */
    public boolean canTransitionTo(RunStatus next) {
        if (next == null) {
            return false;
        }
        if (next == this) {
            return true;
        }
        return switch (this) {
            case PENDING -> next == RUNNING || next == CANCELLED;
            case RUNNING -> next == PENDING || next == WAITING_INPUT || next == SUCCEEDED
                    || next == FAILED || next == CANCELLED;
            case WAITING_INPUT -> next == PENDING || next == RUNNING || next == CANCELLED;
            case FAILED -> next == PENDING || next == RUNNING || next == CANCELLED;
            case SUCCEEDED, CANCELLED -> false;
        };
    }
}
