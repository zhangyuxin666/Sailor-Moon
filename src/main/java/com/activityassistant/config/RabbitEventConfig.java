package com.activityassistant.config;

import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** RabbitMQ topology for backend domain events. */
@Configuration
public class RabbitEventConfig {
    public static final String EXCHANGE = "activity.events";
    public static final String ANALYTICS_ROUTING_KEY = "analytics.changed";

    @Bean
    TopicExchange activityEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    /**
     * Every API instance gets its own temporary queue. This is intentional: every
     * instance must notify the SSE clients connected to that instance.
     */
    @Bean
    Queue analyticsEventsQueue() {
        return new AnonymousQueue();
    }

    @Bean
    Binding analyticsEventsBinding(Queue analyticsEventsQueue, TopicExchange activityEventsExchange) {
        return BindingBuilder.bind(analyticsEventsQueue)
                .to(activityEventsExchange)
                .with("analytics.#");
    }
}
