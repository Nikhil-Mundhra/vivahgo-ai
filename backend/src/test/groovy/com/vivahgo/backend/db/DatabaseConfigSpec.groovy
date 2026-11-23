package com.vivahgo.backend.db

import org.jooq.SQLDialect
import spock.lang.Specification

class DatabaseConfigSpec extends Specification {

    def "parses Neon pooled postgresql:// URI into JDBC config with TLS and scale-to-zero defaults"() {
        given:
        def neonUrl = "postgresql://vivahgo_db_owner:s3cr%40t@ep-mute-night-pooler.c-5.ap-southeast-1.aws.neon.tech/vivahgo_db?sslmode=require&channel_binding=require"

        when:
        def cfg = DatabaseConfig.parseUrl(neonUrl, "fallback", "fallback", 10)

        then:
        cfg.jdbcUrl() == "jdbc:postgresql://ep-mute-night-pooler.c-5.ap-southeast-1.aws.neon.tech/vivahgo_db?sslmode=require&channelBinding=require"
        cfg.username() == "vivahgo_db_owner"
        cfg.password() == "s3cr@t"
        cfg.dialect() == SQLDialect.POSTGRES
        cfg.maxPoolSize() == 10
        cfg.minIdle() == 0
    }

    def "adds sslmode=require by default for Neon URLs without query string"() {
        when:
        def cfg = DatabaseConfig.parseUrl("postgresql://u:p@ep-test.ap-southeast-1.aws.neon.tech/vivahgo_db", "", "", 5)

        then:
        cfg.jdbcUrl() == "jdbc:postgresql://ep-test.ap-southeast-1.aws.neon.tech/vivahgo_db?sslmode=require"
        cfg.minIdle() == 0
    }

    def "preserves local JDBC URL and falls back to POSTGRES_* env vars"() {
        given:
        def env = [
                POSTGRES_HOST    : "postgres",
                POSTGRES_PORT    : "5432",
                POSTGRES_DB      : "vivahgo_db",
                POSTGRES_USER    : "vivahgo",
                POSTGRES_PASSWORD: "password"
        ]

        when:
        def cfg = DatabaseConfig.resolveFromEnv(env, false)

        then:
        cfg.jdbcUrl() == "jdbc:postgresql://postgres:5432/vivahgo_db"
        cfg.username() == "vivahgo"
        cfg.password() == "password"
        cfg.minIdle() == 2
    }

    def "prefers DIRECT_DATABASE_URL when preferDirect is true"() {
        given:
        def env = [
                DATABASE_URL       : "postgresql://u:p@ep-test-pooler.ap-southeast-1.aws.neon.tech/vivahgo_db?sslmode=require",
                DIRECT_DATABASE_URL: "postgresql://u:p@ep-test.ap-southeast-1.aws.neon.tech/vivahgo_db?sslmode=require"
        ]

        when:
        def pooled = DatabaseConfig.resolveFromEnv(env, false)
        def direct = DatabaseConfig.resolveFromEnv(env, true)

        then:
        pooled.jdbcUrl().contains("-pooler.")
        !direct.jdbcUrl().contains("-pooler.")
        direct.maxPoolSize() == 2
    }

    def "redacts credentials when logging URLs"() {
        expect:
        DatabaseConfig.sanitizeUrlForLog("postgresql://user:secret@host/db") == "postgresql://user:***@host/db"
        DatabaseConfig.sanitizeUrlForLog("jdbc:postgresql://host/db?user=u&password=secret&sslmode=require") == "jdbc:postgresql://host/db?user=u&password=***&sslmode=require"
    }
}
