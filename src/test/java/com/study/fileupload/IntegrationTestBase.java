package com.study.fileupload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 통합 테스트 공통 기반.
 *
 * 전제: docker compose up -d 로 로컬 PostgreSQL(55432)이 떠 있어야 한다.
 * (Docker Desktop 29.x와 docker-java 호환 문제로 Testcontainers 대신 compose DB를 사용 —
 *  PROMPT_LOG.md 참고. 테스트 전용 fileupload_test DB를 자동 생성해 운영 데이터와 분리한다)
 *
 * - 실제 PostgreSQL에 schema.sql을 그대로 적용 — DB CHECK/UNIQUE 제약까지 함께 검증
 * - 저장소는 로컬 임시 디렉터리
 * - 각 테스트 전에 데이터를 초기화한다
 */
public abstract class IntegrationTestBase {

    static final String HOST = System.getenv().getOrDefault("DB_HOST", "localhost");
    static final String PORT = System.getenv().getOrDefault("DB_PORT", "55432");
    static final String USER = System.getenv().getOrDefault("DB_USER", "fileupload");
    static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "fileupload");
    static final String TEST_DB = "fileupload_test";
    static final String TEST_URL = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + TEST_DB;

    static final Path STORAGE_DIR;

    static {
        try {
            bootstrapTestDatabase();
            STORAGE_DIR = Files.createTempDirectory("upload-test-storage");
        } catch (SQLException | IOException e) {
            throw new IllegalStateException(
                    "통합 테스트용 DB를 준비하지 못했습니다. docker compose up -d 로 PostgreSQL(55432)을 먼저 띄워 주세요.", e);
        }
    }

    /** fileupload_test DB가 없으면 만들고, 스키마가 없으면 schema.sql을 그대로 적용한다. */
    private static void bootstrapTestDatabase() throws SQLException, IOException {
        String adminUrl = "jdbc:postgresql://" + HOST + ":" + PORT + "/fileupload";
        try (Connection admin = DriverManager.getConnection(adminUrl, USER, PASSWORD);
             Statement statement = admin.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                    "SELECT 1 FROM pg_database WHERE datname = '" + TEST_DB + "'")) {
                if (!rs.next()) {
                    statement.execute("CREATE DATABASE " + TEST_DB);
                }
            }
        }
        try (Connection test = DriverManager.getConnection(TEST_URL, USER, PASSWORD);
             Statement statement = test.createStatement()) {
            try (ResultSet rs = test.getMetaData().getTables(null, "public", "upload_policy", null)) {
                if (!rs.next()) {
                    statement.execute(Files.readString(Path.of("schema.sql")));
                }
            }
        }
    }

    @DynamicPropertySource
    static void baseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> TEST_URL);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("storage.mode", () -> "local");
        registry.add("storage.local.dir", () -> STORAGE_DIR.toString());
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM file_upload");
        jdbc.update("DELETE FROM ip_block");
        jdbc.update("DELETE FROM policy_change_log");
        jdbc.update("DELETE FROM extension_rule WHERE rule_type = 'CUSTOM'");
        jdbc.update("UPDATE extension_rule SET blocked = false, version = 0");
    }
}
