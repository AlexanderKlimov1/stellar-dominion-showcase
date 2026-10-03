package com.sddnw.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Stellar Dominion — игровой сервер. Пакет — {@code com.sddnw} (Stellar Dominion: Direction New
 * World), и так же зовутся артефакт, каталоги, база и параметры настройки.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
// Планировщик — ради пульса балансовых прогонов (BalanceRunProgress.beat и sweep).
@EnableScheduling
public class SddnwServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SddnwServerApplication.class, args);
    }
}
