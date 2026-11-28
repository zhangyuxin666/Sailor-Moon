package com.activityassistant.service;

import com.activityassistant.security.AccountPrincipal;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 数据大屏的 SSE 通道：
 * - 每个浏览器持有一条连接，连接上记录它要看的范围（管理员 + 周期 + 班级）；
 * - 数据库有变化时由 {@link AnalyticsNotifier} 调用 {@link #broadcast()}，立即重算并推送；
 * - 每 10 秒的定时推送作为兜底，防止通知丢失。
 */
@Service
public class AnalyticsStreamService {
    private static final Logger log = LoggerFactory.getLogger(AnalyticsStreamService.class);
    private static final long EMITTER_TIMEOUT = 0L;

    private final AnalyticsService analytics;
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean broadcastPending = new AtomicBoolean();
    private final ScheduledExecutorService broadcastExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "analytics-sse-broadcast");
        thread.setDaemon(true);
        return thread;
    });

    public AnalyticsStreamService(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @PreDestroy
    void stop() {
        broadcastExecutor.shutdownNow();
    }

    public SseEmitter subscribe(AccountPrincipal actor, int days, String classId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT);
        Subscription subscription = new Subscription(emitter, actor, days, classId);
        subscriptions.add(subscription);

        emitter.onCompletion(() -> subscriptions.remove(subscription));
        emitter.onTimeout(() -> {
            subscriptions.remove(subscription);
            emitter.complete();
        });
        emitter.onError(error -> subscriptions.remove(subscription));

        // 连上先给一份当前数据，避免等到下一次事件才有内容。
        push(subscription, true);
        return emitter;
    }

    /** 数据库变更后调用：立即重算并推给所有连接。 */
    public void requestBroadcast() {
        if (!broadcastPending.compareAndSet(false, true)) return;
        broadcastExecutor.schedule(() -> {
            broadcastPending.set(false);
            broadcast();
        }, 300, TimeUnit.MILLISECONDS);
    }

    private void broadcast() {
        if (subscriptions.isEmpty()) return;
        for (Subscription subscription : subscriptions) {
            push(subscription, false);
        }
    }

    /** 兜底：即使没有收到通知，也定期推一次（正常情况下由数据库事件触发）。 */
    @Scheduled(fixedDelay = 30_000)
    void heartbeat() {
        for (Subscription subscription : subscriptions) {
            try {
                subscription.emitter().send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException disconnected) {
                subscriptions.remove(subscription);
                subscription.emitter().complete();
            }
        }
    }

    private void push(Subscription subscription, boolean initial) {
        try {
            Map<String, Object> snapshot = analytics.overview(
                    subscription.actor(), subscription.days(), subscription.classId());
            SseEmitter.SseEventBuilder event = SseEmitter.event()
                    .id(String.valueOf(sequence.incrementAndGet()))
                    .name("snapshot")
                    .data(snapshot);
            subscription.emitter().send(event);
        } catch (IOException | IllegalStateException disconnected) {
            subscriptions.remove(subscription);
            subscription.emitter().complete();
            log.debug("analytics sse connection closed: {}", disconnected.getMessage());
        } catch (RuntimeException failure) {
            log.warn("analytics sse push failed", failure);
            if (initial) {
                subscription.emitter().completeWithError(failure);
                subscriptions.remove(subscription);
            }
        }
    }

    private record Subscription(SseEmitter emitter, AccountPrincipal actor, int days, String classId) {
    }
}
