package com.cde.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Every {@code @ConfigurationProperties} class in the application is bound and
 * validated at startup, so invalid or missing required configuration stops the
 * process here rather than surfacing as a failure on the first request that
 * happens to need it.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CdePlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(CdePlatformApplication.class, args);
    }
}
