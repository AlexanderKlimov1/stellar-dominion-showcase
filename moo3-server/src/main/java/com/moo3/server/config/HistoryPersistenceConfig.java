package com.moo3.server.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * История балансовых прогонов — ВСЕГДА в Postgres, в любом режиме сервера.
 * <p>
 * Здесь лежат две таблицы: {@code balance_run} (снимок цен, приговоры, ладдер оракула на
 * каждый прогон) и {@code balance_combination} (память проверенных связок). Это не
 * состояние партии, а РАБОЧИЙ ЖУРНАЛ балансировки: в нём двести пятьдесят прогонов и
 * пятнадцать мегабайт, и каждый круг читает прошлые, чтобы сравнить невязку с прежней.
 * Партии живут минуты и удаляются за собой, а это — месяцами.
 * <p>
 * Поэтому источник у него свой и отдельно настраиваемый ({@code moo3.history.datasource}).
 * В обычном режиме он указывает на ту же базу, что и игра, — два соединения к одному
 * Postgres, и ничего не меняется. В режиме балансового прогона игра уезжает в H2, а
 * история остаётся здесь.
 * <p>
 * Транзакции у истории свои ({@code historyTransactionManager}): пульт пишет ход прогона
 * из своего потока, пока игровые партии считаются в других, — и смешивать эти транзакции
 * нельзя ни в коем случае, иначе откат партии уносил бы запись о прогоне.
 */
@Configuration
@EnableJpaRepositories(
        basePackages = "com.moo3.server.repository.history",
        entityManagerFactoryRef = "historyEntityManagerFactory",
        transactionManagerRef = "historyTransactionManager")
public class HistoryPersistenceConfig {

    @Bean
    @ConfigurationProperties("moo3.history.datasource")
    public DataSourceProperties historyDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("moo3.history.datasource.hikari")
    public DataSource historyDataSource(
            @Qualifier("historyDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean historyEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("historyDataSource") DataSource dataSource,
            JpaProperties properties) {
        Map<String, Object> settings = new HashMap<>(properties.getProperties());
        // Схему истории ведёт Liquibase по своему changelog'у, а не Hibernate: она живёт
        // месяцами и переезжает миграциями, как всякая долговечная таблица.
        settings.put("hibernate.hbm2ddl.auto", "validate");
        // Диалект у истории ВСЕГДА постгресовый, даже когда игра идёт на H2: колонки
        // приговоров объявлены как jsonb, и на другом диалекте их не прочитать.
        settings.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        return builder
                .dataSource(dataSource)
                .packages("com.moo3.server.domain.entity.history")
                .persistenceUnit("history")
                .properties(settings)
                .build();
    }

    @Bean
    public PlatformTransactionManager historyTransactionManager(
            @Qualifier("historyEntityManagerFactory") EntityManagerFactory factory) {
        return new JpaTransactionManager(factory);
    }
}
