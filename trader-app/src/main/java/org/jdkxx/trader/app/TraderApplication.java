package org.jdkxx.trader.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point. All runtime configuration is external ({@code
 * --spring.config.additional-location=file:./config/}); the application.yml inside the jar holds
 * only environment-independent defaults.
 */
@SpringBootApplication(scanBasePackages = "org.jdkxx.trader")
@EnableScheduling
public class TraderApplication {

  public static void main(String[] args) {
    SpringApplication.run(TraderApplication.class, args);
  }
}
