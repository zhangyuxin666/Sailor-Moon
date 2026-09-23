package com.activityassistant.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryExecutorTest {
    @Test
    void retriesTransientFailureWithStableAction() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        String result = RetryExecutor.execute(() -> {
            if (attempts.incrementAndGet() < 3) throw new IOException("temporary");
            return "ok";
        }, exception -> exception instanceof IOException, 3, Duration.ZERO);
        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void doesNotRetryPermanentFailure() {
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> RetryExecutor.execute(() -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("bad request");
        }, exception -> exception instanceof IOException, 3, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(attempts).hasValue(1);
    }
}
