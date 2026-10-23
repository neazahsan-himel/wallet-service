package com.neaz.wallet.shared.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Background jobs (outbox polling) run only when {@code wallet.scheduling.enabled} is true. Integration tests set it
 * to false and trigger the jobs by hand, so no timer races the assertions.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "wallet.scheduling.enabled", havingValue = "true")
public class SchedulingConfig {
}
