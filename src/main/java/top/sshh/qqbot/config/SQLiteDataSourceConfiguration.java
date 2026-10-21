package top.sshh.qqbot.config;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import top.sshh.qqbot.service.storage.LocalDataFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class SQLiteDataSourceConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(SQLiteDataSourceConfiguration.class);

    /** 在配置属性绑定前统一旧 H2 配置，防止 Hikari 二次绑定覆盖 SQLite URL。 */
    @Bean
    public static BeanFactoryPostProcessor legacyDatabaseCompatibility(ConfigurableEnvironment environment) {
        return beanFactory -> {
            String url = environment.getProperty("spring.datasource.url", "").trim();
            String hikariUrl = environment.getProperty("spring.datasource.hikari.jdbc-url", "").trim();
            boolean legacy = url.startsWith("jdbc:h2:") || hikariUrl.startsWith("jdbc:h2:")
                    || "org.h2.Driver".equals(environment.getProperty("spring.datasource.driver-class-name"))
                    || "org.h2.Driver".equals(environment.getProperty("spring.datasource.hikari.driver-class-name"))
                    || "org.hibernate.dialect.H2Dialect".equals(environment.getProperty("spring.jpa.database-platform"))
                    || "org.hibernate.dialect.H2Dialect".equals(environment.getProperty("spring.jpa.properties.hibernate.dialect"));
            if (!legacy) return;

            String sqliteUrl = url.startsWith("jdbc:sqlite:") ? url
                    : hikariUrl.startsWith("jdbc:sqlite:") ? hikariUrl
                    : "jdbc:sqlite:" + LocalDataFiles.databasePath().toAbsolutePath().normalize()
                    + "?busy_timeout=10000&journal_mode=WAL&synchronous=FULL&transaction_mode=IMMEDIATE";
            Map<String, Object> overrides = new HashMap<>();
            overrides.put("spring.datasource.url", sqliteUrl);
            overrides.put("spring.datasource.hikari.jdbc-url", sqliteUrl);
            overrides.put("spring.datasource.driver-class-name", "org.sqlite.JDBC");
            overrides.put("spring.datasource.hikari.driver-class-name", "org.sqlite.JDBC");
            overrides.put("spring.jpa.database-platform", "org.sqlite.hibernate.dialect.SQLiteDialect");
            overrides.put("spring.jpa.properties.hibernate.dialect", "org.sqlite.hibernate.dialect.SQLiteDialect");
            if (!environment.containsProperty("spring.datasource.hikari.maximum-pool-size")) {
                overrides.put("spring.datasource.hikari.maximum-pool-size", 1);
            }
            if (!environment.containsProperty("spring.datasource.hikari.minimum-idle")) {
                overrides.put("spring.datasource.hikari.minimum-idle", 1);
            }
            environment.getPropertySources().addFirst(new MapPropertySource("sqliteLegacyCompatibility", overrides));
            logger.info("检测到旧 H2 数据库配置，已自动切换为 SQLite 配置。");
        };
    }

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) throws IOException {
        Files.createDirectories(LocalDataFiles.databasePath().toAbsolutePath().getParent());
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }
}
