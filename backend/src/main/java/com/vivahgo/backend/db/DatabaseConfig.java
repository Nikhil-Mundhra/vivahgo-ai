package com.vivahgo.backend.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;

/**
 * jOOQ & Database connection manager.
 * Manages connection pooling via HikariCP and provides a configured jOOQ DSLContext.
 */
public class DatabaseConfig implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    private final HikariDataSource dataSource;
    private final DSLContext dslContext;

    public DatabaseConfig(DataSourceConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.jdbcUrl());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setMaximumPoolSize(config.maxPoolSize());
        hikariConfig.setMinimumIdle(config.minIdle());
        hikariConfig.setPoolName("VivahGoHikariPool");

        this.dataSource = new HikariDataSource(hikariConfig);
        this.dslContext = DSL.using(this.dataSource, config.dialect());
        log.info("Database connection pool initialized: url={}, dialect={}", config.jdbcUrl(), config.dialect());
    }

    public static DatabaseConfig fromEnv() {
        String host = System.getenv().getOrDefault("POSTGRES_HOST", "localhost");
        String port = System.getenv().getOrDefault("POSTGRES_PORT", "5432");
        String db = System.getenv().getOrDefault("POSTGRES_DB", "vivahgo_db");
        String user = System.getenv().getOrDefault("POSTGRES_USER", "vivahgo");
        String pass = System.getenv().getOrDefault("POSTGRES_PASSWORD", "password");

        String jdbcUrl = System.getenv().getOrDefault("DATABASE_URL",
                String.format("jdbc:postgresql://%s:%s/%s", host, port, db));

        return new DatabaseConfig(new DataSourceConfig(jdbcUrl, user, pass, SQLDialect.POSTGRES, 10, 2));
    }

    public static DatabaseConfig inMemoryH2() {
        String jdbcUrl = "jdbc:h2:mem:vivahgo_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        DatabaseConfig config = new DatabaseConfig(new DataSourceConfig(jdbcUrl, "sa", "", SQLDialect.H2, 5, 1));
        config.initSchema();
        return config;
    }

    public void initSchema() {
        try (InputStream in = getClass().getResourceAsStream("/schema.sql")) {
            if (in == null) {
                log.warn("schema.sql resource not found");
                return;
            }
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                // Execute schema statements
                for (String statement : sql.split(";")) {
                    String trimmed = statement.trim();
                    if (!trimmed.isEmpty()) {
                        stmt.execute(trimmed);
                    }
                }
                log.info("Database schema initialized successfully.");
            }
        } catch (Exception e) {
            log.error("Failed to initialize database schema", e);
            throw new RuntimeException("Database schema initialization failed", e);
        }
    }

    public DSLContext dsl() {
        return dslContext;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("Database connection pool closed.");
        }
    }

    public record DataSourceConfig(
            String jdbcUrl,
            String username,
            String password,
            SQLDialect dialect,
            int maxPoolSize,
            int minIdle
    ) {}
}
