package top.sshh.qqbot.service.storage;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.service.PriceTask;
import top.sshh.qqbot.service.ProductPriceResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SQLitePricePersistenceTest {
    @TempDir Path root;

    private String url() { return "jdbc:sqlite:" + root.resolve("qqbot.db")
            + "?busy_timeout=10000&journal_mode=WAL&synchronous=FULL"; }

    private SessionFactory open() {
        Configuration configuration = new Configuration().addAnnotatedClass(ProductPrice.class)
                .setProperty("hibernate.connection.driver_class", "org.sqlite.JDBC")
                .setProperty("hibernate.connection.url", url())
                .setProperty("hibernate.dialect", "org.sqlite.hibernate.dialect.SQLiteDialect")
                .setProperty("hibernate.hbm2ddl.auto", "update");
        configuration.setPhysicalNamingStrategy(new SpringPhysicalNamingStrategy());
        return configuration.buildSessionFactory();
    }

    @Test
    void identityDateQueriesLimitsAndUpdatesWorkAfterDatabaseReopens() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 21, 12, 0);
        try (SessionFactory factory = open(); Session session = factory.openSession()) {
            session.beginTransaction();
            ProductPrice first = price(now.minusMinutes(1), 900);
            ProductPrice latest = price(now, 980);
            session.persist(first);
            session.persist(latest);
            session.getTransaction().commit();
            assertNotNull(latest.getId());
            assertNotEquals(first.getId(), latest.getId());
        }
        try (SessionFactory factory = open(); Session session = factory.openSession()) {
            ProductPriceResponse repository = new JpaRepositoryFactory(session).getRepository(ProductPriceResponse.class);
            ProductPrice latest = repository.getFirstByNameOrderByTimeDesc("离火梧桐芝");
            assertEquals(980, latest.getPrice());
            assertEquals(7602500L, latest.getMarketMinPrice());
            assertEquals(11207500L, latest.getMarketMaxPrice());
            assertEquals("药材", latest.getCategory());
            assertEquals(now, latest.getTime());
            assertTrue(repository.existsByName("离火梧桐芝"));
            assertEquals(1, repository.findByNameAndTimeIsAfterOrderByTimeAsc("离火梧桐芝", now.minusSeconds(30)).size());
            assertEquals(2, repository.findTop3ByNameAndTimeIsBeforeOrderByTimeDesc("离火梧桐芝", now.plusSeconds(1)).size());
            session.beginTransaction();
            latest.setMarketMinPrice(8000000L);
            session.getTransaction().commit();
        }
        try (SessionFactory factory = open(); Session session = factory.openSession()) {
            ProductPriceResponse repository = new JpaRepositoryFactory(session).getRepository(ProductPriceResponse.class);
            assertEquals(8000000L, repository.getFirstByNameOrderByTimeDesc("离火梧桐芝").getMarketMinPrice());
        }
    }

    @Test
    void schemaUpdateAddsLimitColumnsToAnOlderSqliteTable() throws Exception {
        try (Connection connection = DriverManager.getConnection(url()); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE product_price (id INTEGER PRIMARY KEY, price INTEGER NOT NULL, "
                    + "name TEXT, code TEXT, time TIMESTAMP, buy_price DOUBLE)");
            statement.execute("INSERT INTO product_price (price, name, buy_price) VALUES (980, '离火梧桐芝', 0)");
        }
        try (SessionFactory factory = open(); Session session = factory.openSession()) {
            ProductPrice product = session.createQuery("from ProductPrice", ProductPrice.class).getSingleResult();
            assertEquals(980, product.getPrice());
            assertNull(product.getMarketMinPrice());
            session.beginTransaction();
            product.setMarketMinPrice(7600000L);
            product.setMarketMaxPrice(11200000L);
            session.getTransaction().commit();
        }
    }

    @Test
    void existingDatabaseSkipsStaleLegacyPriceFile() throws Exception {
        ProductPriceResponse repository = org.mockito.Mockito.mock(ProductPriceResponse.class);
        org.mockito.Mockito.when(repository.count()).thenReturn(1L);
        PriceTask task = new PriceTask();
        ReflectionTestUtils.setField(task, "productPriceResponse", repository);
        Path directory = root.resolve("properties");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("坊市价格.txt"), "旧文件无需解析");
        String old = PriceTask.targetDir;
        try {
            PriceTask.targetDir = root.toString();
            task.readPrice();
            org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).saveAll(org.mockito.ArgumentMatchers.any());
        } finally {
            PriceTask.targetDir = old;
        }
    }

    @Test
    void startupRepairsImageTitleNamesAndPreservesPriceHistoryAcrossReopen() {
        LocalDateTime oldTime = LocalDateTime.of(2026, 10, 20, 12, 0);
        LocalDateTime newTime = oldTime.plusDays(1);
        Long repairedId;
        try (SessionFactory factory = open(); Session session = factory.openSession()) {
            session.beginTransaction();
            ProductPrice old = price(oldTime, 420);
            old.setName("七彩月兰");
            ProductPrice malformed = price(newTime, 580);
            malformed.setName("!物品价格 #3321px #1724px七彩月兰");
            malformed.setMarketMinPrice(3800000L);
            malformed.setMarketMaxPrice(4600000L);
            malformed.setCode("keep-item-code");
            session.persist(old);
            session.persist(malformed);
            session.getTransaction().commit();
            repairedId = malformed.getId();

            ProductPriceResponse repository = new JpaRepositoryFactory(session).getRepository(ProductPriceResponse.class);
            PriceTask task = new PriceTask();
            ReflectionTestUtils.setField(task, "productPriceResponse", repository);
            session.beginTransaction();
            task.readPrice();
            session.getTransaction().commit();
        }
        try (SessionFactory factory = open(); Session session = factory.openSession()) {
            ProductPriceResponse repository = new JpaRepositoryFactory(session).getRepository(ProductPriceResponse.class);
            ProductPrice latest = repository.getFirstByNameOrderByTimeDesc("七彩月兰");
            assertNotNull(latest);
            assertEquals(repairedId, latest.getId());
            assertEquals(580, latest.getPrice());
            assertEquals(3800000L, latest.getMarketMinPrice());
            assertEquals(4600000L, latest.getMarketMaxPrice());
            assertEquals("keep-item-code", latest.getCode());
            assertEquals(newTime, latest.getTime());
            assertEquals(2, repository.count(), "保留两条行情历史，不删除或覆盖旧价格");
            assertFalse(repository.existsByName("!物品价格 #3321px #1724px七彩月兰"));
        }
    }

    private ProductPrice price(LocalDateTime time, int price) {
        ProductPrice product = new ProductPrice();
        product.setName("离火梧桐芝");
        product.setCategory("药材");
        product.setPrice(price);
        product.setTime(time);
        product.setMarketMinPrice(7602500L);
        product.setMarketMaxPrice(11207500L);
        return product;
    }
}
