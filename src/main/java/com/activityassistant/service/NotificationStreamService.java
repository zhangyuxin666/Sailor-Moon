package com.activityassistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 站内消息的 SSE 通道：按账号维护连接。
 * 数据库触发器发出 user_messages 通知后，由 {@link AnalyticsNotifier} 调用 {@link #push}，
 * 只推给收件人自己的连接。
 */
@Service
public class NotificationStreamService {
    private static final Logger log = LoggerFactory.getLogger(NotificationStreamService.class);

    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe(String accountId) {
        SseEmitter emitter = new SseEmitter(0L);
        Subscription subscription = new Subscription(emitter, accountId);
        subscriptions.add(subscription);

        emitter.onCompletion(() -> subscriptions.remove(subscription));
        emitter.onTimeout(() -> {
            subscriptions.remove(subscription);
            emitter.complete();
        });
        emitter.onError(error -> subscriptions.remove(subscription));

        try {
            emitter.send(SseEmitter.event().name("ready").data(Map.of("account", accountId)));
        } catch (IOException disconnected) {
            subscriptions.remove(subscription);
        }
        return emitter;
    }

    public void push(String accountId, Map<String, Object> payload) {
        for (Subscription subscription : subscriptions) {
            if (!subscription.accountId().equals(accountId)) continue;
            try {
                subscription.emitter().send(SseEmitter.event().name("message").data(payload));
            } catch (IOException | IllegalStateException disconnected) {
                subscriptions.remove(subscription);
                subscription.emitter().complete();
                log.debug("notification sse connection closed: {}", disconnected.getMessage());
            }
        }
    }

    /** 心跳：20 秒一条注释，保证中间代理不把空闲连接掐掉。 */
    @Scheduled(fixedDelay = 20_000)
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

    private record Subscription(SseEmitter emitter, String accountId) {
    }
}
