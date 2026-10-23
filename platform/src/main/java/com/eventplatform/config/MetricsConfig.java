package com.eventplatform.config;

import org.springframework.context.annotation.Configuration;

/**
 * Minimal configuration to enable Micrometer metrics via Spring Actuator.
 * Additional common tags can be added here if needed.
 */
@Configuration
public class MetricsConfig {
    // No explicit customizer needed – Spring Boot will auto‑configure the MeterRegistry.
}
