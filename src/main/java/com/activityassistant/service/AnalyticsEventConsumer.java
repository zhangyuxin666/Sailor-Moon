package com.activityassistant.service;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Consumes invalidation events and refreshes the SSE clients connected to this API instance. */
@Service
@ConditionalOnProperty(name = "app.messaging.rabbit-enabled", havingValue = "true", matchIfMissing = true)
public class AnalyticsEventConsumer {
    private final AnalyticsStreamService stream;

    public AnalyticsEventConsumer(AnalyticsStreamService stream) {
        this.stream = stream;
    }

    @RabbitListener(queues = "#{analyticsEventsQueue.name}")
    public void onAnalyticsChanged(String sourceTable) {
        stream.requestBroadcast();
    }
}
