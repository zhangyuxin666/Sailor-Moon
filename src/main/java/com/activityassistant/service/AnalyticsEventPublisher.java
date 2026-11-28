package com.activityassistant.service;

import com.activityassistant.config.RabbitEventConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Publishes dashboard invalidation events and falls back locally if RabbitMQ is unavailable. */
@Service
public class AnalyticsEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(AnalyticsEventPublisher.class);

    private final RabbitTemplate rabbit;
    private final AnalyticsStreamService stream;
    private final boolean rabbitEnabled;

    public AnalyticsEventPublisher(RabbitTemplate rabbit,
                                   AnalyticsStreamService stream,
                                   @Value("${app.messaging.rabbit-enabled:true}") boolean rabbitEnabled) {
        this.rabbit = rabbit;
        this.stream = stream;
        this.rabbitEnabled = rabbitEnabled;
    }

    public void publishChange(String sourceTable) {
        if (!rabbitEnabled) {
            stream.requestBroadcast();
            return;
        }
        try {
            rabbit.convertAndSend(RabbitEventConfig.EXCHANGE,
                    RabbitEventConfig.ANALYTICS_ROUTING_KEY, sourceTable == null ? "unknown" : sourceTable);
        } catch (AmqpException failure) {
            log.warn("RabbitMQ unavailable; broadcasting analytics update locally: {}", failure.getMessage());
            stream.requestBroadcast();
        }
    }
}
