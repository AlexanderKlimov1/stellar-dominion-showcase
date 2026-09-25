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
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Два источника данных: ИГРА и ИСТОРИЯ ПРОГОНОВ — решение хозяина проекта 18.09.2026.
 * <p>
 * <b>Зачем их два.</b> Балансовый прогон играет сотни партий по пятьсот ходов и каждую
 * удаляет за собой: долговечность ему не нужна ни на секунду, а обращения к базе стоят
 * дорого — замерено, поток стоит на ожидании, и никаким числом потоков это не закрывается.
 * А вот ПРИГОВОРЫ прогонов нужны навсегда: в {@code balance_run} лежат снимки цен и
 * приговоры двух с половиной сотен прогонов, и весь способ работы стоит на сравнении
 * прогона с прогоном («невязка была 4,2, стала 4,5»). Потерять их значит потерять метод.
 * <p>
 * Отсюда разделение по СРОКУ ЖИЗНИ, а не по удобству:
 * <ul>
 *   <li><b>игра</b> — партии, планеты, флоты, учётные записи, слепки. В обычном режиме
 *       это Postgres, как и было; в режиме прогона ({@code balance}) — H2 в памяти, и
 *       партии умирают вместе с процессом, потому что в нём и живут;</li>
 *   <li><b>история прогонов</b> — {@code balance_run} и {@code balance_combination}.
 *       ВСЕГДА Postgres, в обоих режимах.</li>
 * </ul>
 * <b>Граница проверена и не пересекается ни одним внешним ключом:</b> {@code balance_run}
 * на партию не ссылается вовсе, а {@code game_save.game_id} намеренно без ключа — слепок
 * переживает свою партию. Поэтому разведение по двум источникам не рвёт ни одной связи.
 * <p>
 * <b>Почему конфигурация ручная, а не автоматическая.</b> Как только источников больше
 * одного, Spring Boot перестаёт угадывать, какой из них чей: нужен явный
 * {@code EntityManagerFactory} на каждый набор сущностей и явный менеджер транзакций.
 * Главным ({@code @Primary}) назван игровой — на нём идут все фазы хода, и {@code
 * @Transactional} без имени должен доставаться именно ему.
 */
@Configuration
@EnableJpaRepositories(
        basePackages = "com.moo3.server.repository",
        excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                type = org.springframework.context.annotation.FilterType.REGEX,
                pattern = "com\\.moo3\\.server\\.repository\\.history\\..*"),
        entityManagerFactoryRef = "gameEntityManagerFactory",
        transactionManagerRef = "gameTransactionManager")
public class PersistenceConfig {

    /**
     * Игровой источник. В обычном режиме — Postgres из {@code spring.datasource}; в режиме
     * балансового прогона профиль {@code balance} подменяет там адрес на H2 в памяти, и
     * ничего больше менять не нужно: сущности, репозитории и запросы те же самые.
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties gameDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * Настройки пула идут отдельной пометкой: {@code spring.datasource.url} понимает
     * {@link DataSourceProperties}, а {@code maximum-pool-size} — уже сам Hikari.
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource gameDataSource(
            @Qualifier("gameDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    public LocalContainerEntityManagerFactoryBean gameEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("gameDataSource") DataSource dataSource,
            JpaProperties properties) {
        Map<String, Object> settings = new HashMap<>(properties.getProperties());
        return builder
                .dataSource(dataSource)
                .packages("com.moo3.server.domain.entity")
                .persistenceUnit("game")
                .properties(settings)
                .build();
    }

    @Bean
    @Primary
    public PlatformTransactionManager gameTransactionManager(
            @Qualifier("gameEntityManagerFactory") EntityManagerFactory factory) {
        return new JpaTransactionManager(factory);
    }
}
