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
            stmt.execute("SET session_replication_role = DEFAULT");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clean database in test helper", e);
        }
    }
}
