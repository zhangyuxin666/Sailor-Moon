package com.activityassistant.orchestration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工作流状态机的行为约束。
 *
 * 这里锁死三件事：状态如何在数据库和接口上表示、哪些状态是终态、
 * 以及非法转移必须被拒绝。T2 的暂停恢复、T4 的取消都依赖这些规则。
 */
class RunStatusTest {

    @Test
    void parsesEveryWireValue() {
        assertThat(RunStatus.fromWire("pending")).contains(RunStatus.PENDING);
        assertThat(RunStatus.fromWire("running")).contains(RunStatus.RUNNING);
        assertThat(RunStatus.fromWire("waiting_input")).contains(RunStatus.WAITING_INPUT);
        assertThat(RunStatus.fromWire("succeeded")).contains(RunStatus.SUCCEEDED);
        assertThat(RunStatus.fromWire("failed")).contains(RunStatus.FAILED);
        assertThat(RunStatus.fromWire("cancelled")).contains(RunStatus.CANCELLED);
    }

    @Test
    void rejectsUnknownWireValue() {
        assertThat(RunStatus.fromWire("finished")).isEmpty();
        assertThat(RunStatus.fromWire(null)).isEmpty();
        assertThatThrownBy(() -> RunStatus.parse("retrying")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacyStatusesAreNoLongerValid() {
        // V3 迁移把 queued 和 retrying 合并进 pending，代码不能再认它们。
        assertThat(RunStatus.fromWire("queued")).isEmpty();
        assertThat(RunStatus.fromWire("retrying")).isEmpty();
    }

    @Test
    void cancelledAndSucceededAreTerminal() {
        assertThat(RunStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(RunStatus.SUCCEEDED.isTerminal()).isTrue();
        assertThat(RunStatus.RUNNING.isTerminal()).isFalse();
        assertThat(RunStatus.WAITING_INPUT.isTerminal()).isFalse();
        assertThat(RunStatus.FAILED.isTerminal()).isFalse();
    }

    @Test
    void cancelledRunCannotChangeStatus() {
        for (RunStatus next : RunStatus.values()) {
            if (next == RunStatus.CANCELLED) continue;
            assertThat(RunStatus.CANCELLED.canTransitionTo(next))
                    .as("取消后不允许再变成 %s", next)
                    .isFalse();
        }
    }

    @Test
    void repeatedWriteOfSameStatusIsAllowed() {
        // 同状态重复写入是幂等的：执行阶段会多次刷新 phase，但状态一直是 running。
        assertThat(RunStatus.RUNNING.canTransitionTo(RunStatus.RUNNING)).isTrue();
        assertThat(RunStatus.CANCELLED.canTransitionTo(RunStatus.CANCELLED)).isTrue();
        assertThat(RunStatus.RUNNING.canTransitionTo(null)).isFalse();
    }

    @Test
    void succeededRunCannotReturnToRunning() {
        assertThat(RunStatus.SUCCEEDED.canTransitionTo(RunStatus.RUNNING)).isFalse();
        assertThat(RunStatus.SUCCEEDED.canTransitionTo(RunStatus.PENDING)).isFalse();
    }

    @Test
    void runningCanPauseResumeAndFinish() {
        assertThat(RunStatus.RUNNING.canTransitionTo(RunStatus.WAITING_INPUT)).isTrue();
        assertThat(RunStatus.WAITING_INPUT.canTransitionTo(RunStatus.RUNNING)).isTrue();
        assertThat(RunStatus.RUNNING.canTransitionTo(RunStatus.SUCCEEDED)).isTrue();
        assertThat(RunStatus.WAITING_INPUT.canTransitionTo(RunStatus.CANCELLED)).isTrue();
        assertThat(RunStatus.PENDING.canTransitionTo(RunStatus.RUNNING)).isTrue();
        assertThat(RunStatus.PENDING.canTransitionTo(RunStatus.CANCELLED)).isTrue();
    }

    @Test
    void failedRunCanBeRequeuedOrResumed() {
        assertThat(RunStatus.FAILED.canTransitionTo(RunStatus.PENDING)).isTrue();
        assertThat(RunStatus.FAILED.canTransitionTo(RunStatus.RUNNING)).isTrue();
        assertThat(RunStatus.FAILED.canTransitionTo(RunStatus.CANCELLED)).isTrue();
    }
}
