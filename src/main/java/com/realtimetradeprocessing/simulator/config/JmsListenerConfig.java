package com.realtimetradeprocessing.simulator.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;

@Configuration
@EnableJms
@ConditionalOnProperty(name = "trade.messaging.jms-listener-enabled", havingValue = "true", matchIfMissing = true)
public class JmsListenerConfig {
}
