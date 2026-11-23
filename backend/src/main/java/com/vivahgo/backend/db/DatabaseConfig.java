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
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * jOOQ & Database connection manager.
 * Manages connection pooling via HikariCP and provides a configured jOOQ DSLContext.
 * Supports both Neon libpq URIs (postgresql://...) and standard JDBC URLs (jdbc:postgresql://...).
 */
public class DatabaseConfig implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    private final HikariDataSource dataSource;
    private final DSLContext dslContext;

    public DatabaseConfig(DataSourceConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.jdbcUrl());
        if (config.username() != null && !config.username().isEmpty()) {
            hikariConfig.setUsername(config.username());
        }
        if (config.password() != null && !config.password().isEmpty()) {
            hikariConfig.setPassword(config.password());
        }
        hikariConfig.setMaximumPoolSize(config.maxPoolSize());
        hikariConfig.setMinimumIdle(config.minIdle());
        hikariConfig.setPoolName("VivahGoHikariPool");

        if (isNeonUrl(config.jdbcUrl())) {
            // Allow Neon computes to scale to zero cleanly and tolerate cold-start wakeups
            hikariConfig.setConnectionTimeout(30_000L);
            hikariConfig.setIdleTimeout(240_000L);
            hikariConfig.setMaxLifetime(1_200_000L);
            hikariConfig.setKeepaliveTime(0L);
        }

        this.dataSource = new HikariDataSource(hikariConfig);
        this.dslContext = DSL.using(this.dataSource, config.dialect());
        log.info("Database connection pool initialized: url={}, dialect={}, maxPool={}, minIdle={}",
                sanitizeUrlForLog(config.jdbcUrl()), config.dialect(), config.maxPoolSize(), config.minIdle());
    }

    public static DatabaseConfig fromEnv() {
        return new DatabaseConfig(resolveFromEnv(System.getenv(), false));
    }

    public static Optional<DatabaseConfig> fromDirectEnv() {
        String directUrl = System.getenv("DIRECT_DATABASE_URL");
        if (directUrl == null || directUrl.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new DatabaseConfig(resolveFromEnv(System.getenv(), true)));
    }

    public static DataSourceConfig resolveFromEnv(Map<String, String> env, boolean preferDirect) {
        String fallbackUser = env.getOrDefault("POSTGRES_USER", "vivahgo");
        String fallbackPass = env.getOrDefault("POSTGRES_PASSWORD", "password");

        String rawUrl = null;
        if (preferDirect) {
            String direct = env.get("DIRECT_DATABASE_URL");
            if (direct != null && !direct.isBlank()) {
                rawUrl = direct.trim();
            }
        }
        if (rawUrl == null) {
            String primary = env.get("DATABASE_URL");
            if (primary != null && !primary.isBlank()) {
                rawUrl = primary.trim();
            }
        }
        if (rawUrl == null) {
            String host = env.getOrDefault("POSTGRES_HOST", "localhost");
            String port = env.getOrDefault("POSTGRES_PORT", "5432");
            String db = env.getOrDefault("POSTGRES_DB", "vivahgo_db");
            rawUrl = String.format("jdbc:postgresql://%s:%s/%s", host, port, db);
        }

        int maxPool = preferDirect ? 2 : 10;
        return parseUrl(rawUrl, fallbackUser, fallbackPass, maxPool);
    }

    public static DataSourceConfig parseUrl(String rawUrl, String fallbackUser, String fallbackPass, int maxPoolSize) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("Database URL must not be blank");
        }

        String trimmed = rawUrl.trim();
        String jdbcUrl;
        String user = fallbackUser;
        String pass = fallbackPass;

        if (trimmed.startsWith("postgresql://") || trimmed.startsWith("postgres://")) {
            URI uri = URI.create(trimmed);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("Database URL is missing host");
            }

            if (uri.getRawUserInfo() != null && !uri.getRawUserInfo().isEmpty()) {
                String rawUserInfo = uri.getRawUserInfo();
                int sep = rawUserInfo.indexOf(':');
                if (sep >= 0) {
                    user = URLDecoder.decode(rawUserInfo.substring(0, sep), StandardCharsets.UTF_8);
                    pass = URLDecoder.decode(rawUserInfo.substring(sep + 1), StandardCharsets.UTF_8);
                } else {
                    user = URLDecoder.decode(rawUserInfo, StandardCharsets.UTF_8);
                    pass = "";
                }
            }

            StringBuilder sb = new StringBuilder("jdbc:postgresql://").append(host);
            if (uri.getPort() > 0) {
                sb.append(':').append(uri.getPort());
            }
            String path = uri.getRawPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                sb.append("/neondb");
            } else {
                sb.append(path);
            }

            String normalizedQuery = normalizeJdbcQueryParams(uri.getRawQuery(), host);
            if (!normalizedQuery.isEmpty()) {
                sb.append('?').append(normalizedQuery);
            }
            jdbcUrl = sb.toString();
        } else if (trimmed.startsWith("jdbc:postgresql://")) {
            int qIdx = trimmed.indexOf('?');
            if (qIdx >= 0) {
                String base = trimmed.substring(0, qIdx);
                String query = trimmed.substring(qIdx + 1);
                String normalizedQuery = normalizeJdbcQueryParams(query, base);
                jdbcUrl = normalizedQuery.isEmpty() ? base : base + "?" + normalizedQuery;
            } else if (isNeonUrl(trimmed)) {
                jdbcUrl = trimmed + "?sslmode=require";
            } else {
                jdbcUrl = trimmed;
            }
        } else {
            jdbcUrl = trimmed;
        }

        int minIdle = isNeonUrl(jdbcUrl) ? 0 : Math.min(2, maxPoolSize);
        return new DataSourceConfig(jdbcUrl, user, pass, SQLDialect.POSTGRES, maxPoolSize, minIdle);
    }

    private static String normalizeJdbcQueryParams(String rawQuery, String hostOrUrl) {
        List<String> params = new ArrayList<>();
        boolean hasSslMode = false;

        if (rawQuery != null && !rawQuery.isBlank()) {
            for (String pair : rawQuery.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String key = eq >= 0 ? pair.substring(0, eq) : pair;
                String val = eq >= 0 ? pair.substring(eq + 1) : "";

                if ("channel_binding".equals(key)) {
                    // pgjdbc uses camelCase channelBinding instead of libpq's channel_binding
                    params.add("channelBinding=" + val);
                } else {
                    if ("sslmode".equalsIgnoreCase(key)) {
                        hasSslMode = true;
                    }
                    params.add(pair);
                }
            }
        }

        if (!hasSslMode && isNeonUrl(hostOrUrl)) {
            params.add("sslmode=require");
        }

        return String.join("&", params);
    }

    static boolean isNeonUrl(String url) {
        return url != null && url.toLowerCase().contains(".neon.tech");
    }

    static String sanitizeUrlForLog(String url) {
        if (url == null) {
            return "";
        }
        return url.replaceAll("(?i)(password=)[^&]+", "$1***")
                .replaceAll("(://[^:/@]+:)[^@]+(@)", "$1***$2");
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
