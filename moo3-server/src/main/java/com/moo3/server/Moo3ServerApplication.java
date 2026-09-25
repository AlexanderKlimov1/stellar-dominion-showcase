package com.moo3.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Stellar Dominion — игровой сервер. Пакет и артефакт остались moo3: это внутреннее имя проекта. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Moo3ServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(Moo3ServerApplication.class, args);
    }
}
