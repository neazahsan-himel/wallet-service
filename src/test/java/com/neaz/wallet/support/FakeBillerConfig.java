package com.neaz.wallet.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the placeholder biller with {@link FakeBiller} in every integration test. Imported by the shared base
 * class, so all ITs keep one cached application context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeBillerConfig {

    @Bean
    @Primary
    FakeBiller fakeBiller() {
        return new FakeBiller();
    }
}
