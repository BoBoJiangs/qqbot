package top.sshh.qqbot.service.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.util.ReflectionTestUtils;
import com.alibaba.fastjson2.JSON;
import com.zaxxer.hikari.HikariDataSource;
import top.sshh.qqbot.config.SQLiteDataSourceConfiguration;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.service.ProductPriceResponse;
import top.sshh.qqbot.service.PriceTask;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;
import javax.persistence.EntityManagerFactory;

import static org.junit.jupiter.api.Assertions.*;

class SQLiteSpringConfigurationTest {
    @TempDir Path root;

    @Configuration
    @Import(SQLiteDataSourceConfiguration.class)
    @EntityScan(basePackageClasses = ProductPrice.class)
    @EnableJpaRepositories(basePackageClasses = ProductPriceResponse.class)
    static class PersistenceSlice { }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class))
                .withUserConfiguration(PersistenceSlice.class)
                .withPropertyValues("spring.datasource.url=jdbc:sqlite:" + root.resolve("spring.db")
                                + "?busy_timeout=10000&journal_mode=WAL&synchronous=FULL&transaction_mode=IMMEDIATE",
                        "spring.datasource.driver-class-name=org.sqlite.JDBC",
                        "spring.datasource.hikari.maximum-pool-size=1",
                        "spring.jpa.database-platform=org.sqlite.hibernate.dialect.SQLiteDialect",
                        "spring.jpa.hibernate.ddl-auto=update");
    }

    @Test
    void legacyHikariAddressDriverAndDialectCannotOverrideSqlite() {
        runner().withPropertyValues(
                "spring.datasource.hikari.jdbc-url=jdbc:h2:mem:db;DB_CLOSE_DELAY=-1",
                "spring.datasource.hikari.driver-class-name=org.h2.Driver",
                "spring.datasource.hikari.maximum-pool-size=2",
                "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    HikariDataSource pool = context.getBean(HikariDataSource.class);
                    assertTrue(pool.getJdbcUrl().startsWith("jdbc:sqlite:" + root.resolve("spring.db")));
                    assertEquals("org.sqlite.JDBC", pool.getDriverClassName());
                    assertEquals(2, pool.getMaximumPoolSize(), "保留连接池参数");
                    assertEquals("org.sqlite.hibernate.dialect.SQLiteDialect", context
                            .getBean(EntityManagerFactory.class).getProperties().get("hibernate.dialect"));
                    try (java.sql.Connection connection = pool.getConnection()) {
                        assertEquals("SQLite", connection.getMetaData().getDatabaseProductName());
                    }
                });
    }

    @Test
    void legacyStandardH2AddressUsesConfiguredLocalDatabaseFile() {
        runner().withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:db;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    HikariDataSource pool = context.getBean(HikariDataSource.class);
                    assertTrue(pool.getJdbcUrl().startsWith("jdbc:sqlite:"
                            + LocalDataFiles.databasePath().toAbsolutePath().normalize() + "?"));
                    assertEquals("org.sqlite.JDBC", pool.getDriverClassName());
                    try (java.sql.Connection connection = pool.getConnection()) {
                        assertEquals("SQLite", connection.getMetaData().getDatabaseProductName());
                    }
                });
    }

    @Test
    void legacyHibernateDialectDoesNotProduceH2SqlForSqlite() {
        runner().withPropertyValues("spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals("org.sqlite.hibernate.dialect.SQLiteDialect", context
                            .getBean(EntityManagerFactory.class).getProperties().get("hibernate.dialect"));
                });
    }

    @Test
    void springDataSourceAndJpaPersistAcrossApplicationContextRestarts() {
        ApplicationContextRunner runner = runner();
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            ProductPriceResponse prices = context.getBean(ProductPriceResponse.class);
            ProductPrice product = new ProductPrice();
            product.setName("离火梧桐芝");
            product.setPrice(980);
            product.setTime(LocalDateTime.now());
            product.setMarketMinPrice(7600000L);
            product.setMarketMaxPrice(11200000L);
            product.setId(42L);
            Path legacy = root.resolve("properties/坊市价格.txt");
            Files.createDirectories(legacy.getParent());
            Files.writeString(legacy, JSON.toJSONString(List.of(product)));
            PriceTask task = new PriceTask();
            ReflectionTestUtils.setField(task, "productPriceResponse", prices);
            String previous = PriceTask.targetDir;
            try {
                PriceTask.targetDir = root.toString();
                task.readPrice();
                ProductPrice imported = prices.getFirstByNameOrderByTimeDesc("离火梧桐芝");
                assertNotNull(imported);
                imported.setMarketMinPrice(8000000L);
                prices.save(imported);
                Files.writeString(legacy, "旧文件已过期，重启不应读取");
            } finally {
                PriceTask.targetDir = previous;
            }
        });
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            PriceTask task = new PriceTask();
            ReflectionTestUtils.setField(task, "productPriceResponse", context.getBean(ProductPriceResponse.class));
            String previous = PriceTask.targetDir;
            try {
                PriceTask.targetDir = root.toString();
                task.readPrice();
            } finally {
                PriceTask.targetDir = previous;
            }
            ProductPrice product = context.getBean(ProductPriceResponse.class)
                    .getFirstByNameOrderByTimeDesc("离火梧桐芝");
            assertNotNull(product);
            assertEquals(8000000L, product.getMarketMinPrice());
            assertEquals(11200000L, product.getMarketMaxPrice());
        });
    }
}
