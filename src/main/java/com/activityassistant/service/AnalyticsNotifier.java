package com.activityassistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listen to PostgreSQL notifications on two channels:
 * - analytics_changed: any write to dashboard-relevant tables, triggers a full recompute;
 * - user_messages: a single new in-app message, pushed only to its recipient.
 *
 * Uses a dedicated connection instead of borrowing from Hikari, and reconnects automatically.
 */
@Component
public class AnalyticsNotifier {
    private static final Logger log = LoggerFactory.getLogger(AnalyticsNotifier.class);
    private static final String ANALYTICS_CHANNEL = "analytics_changed";
    private static final String MESSAGE_CHANNEL = "user_messages";

    private final AnalyticsEventPublisher analyticsEvents;
    private final NotificationStreamService notifications;
    private final ObjectMapper json;
    private final String url;
    private final String username;
    private final String password;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService listenExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "pg-notifier-listen"));
    public AnalyticsNotifier(AnalyticsEventPublisher analyticsEvents,
                              NotificationStreamService notifications,
                              ObjectMapper json,
                              @Value("${spring.datasource.url}") String url,
                              @Value("${spring.datasource.username}") String username,
                              @Value("${spring.datasource.password}") String password) {
        this.analyticsEvents = analyticsEvents;
        this.notifications = notifications;
        this.json = json;
        this.url = url;
        this.username = username;
        this.password = password;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        listenExecutor.execute(this::listenLoop);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        listenExecutor.shutdownNow();
    }

    private void listenLoop() {
        while (running.get()) {
            try (Connection connection = DriverManager.getConnection(url, username, password)) {
                connection.setAutoCommit(true);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LISTEN " + ANALYTICS_CHANNEL);
                    statement.execute("LISTEN " + MESSAGE_CHANNEL);
                }
                PGConnection pg = connection.unwrap(PGConnection.class);
                log.info("postgres notifier listening on {}, {}", ANALYTICS_CHANNEL, MESSAGE_CHANNEL);
                while (running.get()) {
                    PGNotification[] batch = pg.getNotifications(1_000);
                    if (batch == null || batch.length == 0) continue;
                    for (PGNotification notification : batch) {
                        dispatch(notification);
                    }
                }
            } catch (Exception failure) {
                if (!running.get()) return;
                log.warn("postgres notifier connection lost, retrying: {}", failure.getMessage());
                sleep(3_000);
            }
        }
    }

    private void dispatch(PGNotification notification) {
        if (MESSAGE_CHANNEL.equals(notification.getName())) {
            handleMessage(notification.getParameter());
            return;
        }
        analyticsEvents.publishChange(notification.getParameter());
    }

    /** 消息通知带收件人，只推给对应账号的连接。 */
    private void handleMessage(String payload) {
        try {
            JsonNode node = json.readTree(payload);
            String recipient = node.path("to").asText("");
            long id = node.path("id").asLong(0);
            if (recipient.isBlank() || id <= 0) return;
            notifications.push(recipient, Map.of("id", id));
        } catch (Exception failure) {
            log.warn("invalid message notification: {}", payload);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }
}
