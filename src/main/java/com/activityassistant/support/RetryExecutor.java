package com.activityassistant.support;

import java.time.Duration;
import java.util.function.Predicate;

public final class RetryExecutor {
    private RetryExecutor() {}

    public static <T> T execute(CheckedSupplier<T> action, Predicate<Exception> retryable,
                                int maxAttempts, Duration initialDelay) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (Exception exception) {
                last = exception;
                if (attempt == maxAttempts || !retryable.test(exception)) throw exception;
                long delay = initialDelay.toMillis() * (1L << (attempt - 1));
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
        throw last == null ? new IllegalStateException("retry action did not run") : last;
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
