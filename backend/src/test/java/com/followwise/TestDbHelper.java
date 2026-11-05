package com.followwise;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class TestDbHelper {

    private TestDbHelper() {}

    public static void cleanDatabase(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SET session_replication_role = replica");
            stmt.execute("DELETE FROM ledger_entries");
            stmt.execute("DELETE FROM parties");
            stmt.execute("DELETE FROM records");
            stmt.execute("DELETE FROM app_definition_versions");
            stmt.execute("DELETE FROM apps");
            stmt.execute("DELETE FROM memberships");
            stmt.execute("DELETE FROM workspaces");
            stmt.execute("DELETE FROM auth_tokens WHERE user_id <> '00000000-0000-0000-0000-000000000001'");
            stmt.execute("DELETE FROM email_outbox");
            stmt.execute("DELETE FROM refresh_tokens");
            stmt.execute("DELETE FROM users WHERE id <> '00000000-0000-0000-0000-000000000001'");
            stmt.execute("INSERT INTO users (id, email, password_hash, failed_attempts, locked_until, created_at, updated_at) VALUES ('00000000-0000-0000-0000-000000000001', 'system@followwise.invalid', '{bcrypt}$2a$10$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA', 0, 'infinity', NOW(), NOW()) ON CONFLICT (id) DO NOTHING");
            stmt.execute("SET session_replication_role = DEFAULT");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clean database in test helper", e);
        }
    }
}
