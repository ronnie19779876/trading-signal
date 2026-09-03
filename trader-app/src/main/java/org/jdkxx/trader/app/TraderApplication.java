package org.jdkxx.trader.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动入口。运行配置一律外置：{@code --spring.config.additional-location=file:./config/}，
 * jar 内的 application.yml 只有与环境无关的默认值。
 */
@SpringBootApplication(scanBasePackages = "org.jdkxx.trader")
@EnableScheduling
public class TraderApplication {

    public static void main(String[] args) {
        SpringApplication.run(TraderApplication.class, args);
    }
}
