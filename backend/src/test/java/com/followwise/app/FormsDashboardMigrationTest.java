package com.followwise.app;

import com.followwise.AbstractIntegrationTest;
import com.followwise.TestDbHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FormsDashboardMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        TestDbHelper.cleanDatabase(dataSource);
    }

    @Test
    void testV11IndexesExist() {
        // 1. ledger_entries (app_id, charge_id)
        List<String> idx1 = jdbcClient.sql("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'ledger_entries'
                  AND indexdef LIKE '%(app_id, charge_id)%'
                """).query(String.class).list();
        assertFalse(idx1.isEmpty(), "ledger_entries(app_id, charge_id) index should exist");

        // 2. ledger_entries (app_id, due_date) WHERE entry_type = 'CHARGE'
        List<String> idx2 = jdbcClient.sql("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'ledger_entries'
                  AND indexdef LIKE '%(app_id, due_date)%'
                  AND indexdef LIKE '%entry_type = ''CHARGE''%'
                """).query(String.class).list();
        assertFalse(idx2.isEmpty(), "ledger_entries(app_id, due_date) WHERE entry_type = 'CHARGE' should exist");

        // 3. ledger_entries (app_id, paid_on) WHERE entry_type = 'PAYMENT'
        List<String> idx3 = jdbcClient.sql("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'ledger_entries'
                  AND indexdef LIKE '%(app_id, paid_on)%'
                  AND indexdef LIKE '%entry_type = ''PAYMENT''%'
                """).query(String.class).list();
        assertFalse(idx3.isEmpty(), "ledger_entries(app_id, paid_on) WHERE entry_type = 'PAYMENT' should exist");

        // 4. document_requests (app_id, status, due_date)
        List<String> idx4 = jdbcClient.sql("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'document_requests'
                  AND indexdef LIKE '%(app_id, status, due_date)%'
                """).query(String.class).list();
        assertFalse(idx4.isEmpty(), "document_requests(app_id, status, due_date) should exist");

        // 5. document_requests (app_id, party_id)
        List<String> idx5 = jdbcClient.sql("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'document_requests'
                  AND indexdef LIKE '%(app_id, party_id)%'
                  AND indexdef NOT LIKE '%(app_id, party_id, status)%'
                  AND indexdef NOT LIKE '%(app_id, party_id, checklist_id, item_id)%'
                """).query(String.class).list();
        assertFalse(idx5.isEmpty(), "document_requests(app_id, party_id) should exist");

        // 6. parties (app_id, party_type) WHERE archived_at IS NULL
        List<String> idx6 = jdbcClient.sql("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'parties'
                  AND indexdef LIKE '%(app_id, party_type)%'
                  AND indexdef LIKE '%archived_at IS NULL%'
                """).query(String.class).list();
        assertFalse(idx6.isEmpty(), "parties(app_id, party_type) WHERE archived_at IS NULL should exist");
    }

    @Test
    void testLedgerEntriesImmutabilityTriggersPreserved() {
        UUID wid = UUID.randomUUID();
        UUID aid = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        UUID cid = UUID.randomUUID();
        UUID uid = UUID.fromString("00000000-0000-0000-0000-000000000001");

        // Setup workspace, app, party
        jdbcClient.sql("INSERT INTO workspaces (id, name, created_at) VALUES (:wid, 'WS', NOW())")
                .param("wid", wid).update();
        jdbcClient.sql("INSERT INTO apps (id, workspace_id, name, template_key, current_version, created_at, updated_at) VALUES (:aid, :wid, 'App', 'rental-property-manager', 1, NOW(), NOW())")
                .param("aid", aid).param("wid", wid).update();
        jdbcClient.sql("INSERT INTO parties (id, workspace_id, app_id, party_type, display_name, data, definition_version, version, created_by, updated_by, created_at, updated_at) VALUES (:pid, :wid, :aid, 'tenant', 'Alice', '{}'::jsonb, 1, 1, :uid, :uid, NOW(), NOW())")
                .param("pid", pid).param("wid", wid).param("aid", aid).param("uid", uid).update();

        // Insert a charge
        jdbcClient.sql("""
                INSERT INTO ledger_entries (
                    id, workspace_id, app_id, party_id, charge_id, entry_type,
                    amount_cents, currency, kind, label, due_date, created_by
                ) VALUES (
                    :cid, :wid, :aid, :pid, :cid, 'CHARGE',
                    100000, 'USD', 'recurring', 'Rent', CURRENT_DATE, :uid
                )
                """)
                .param("cid", cid).param("wid", wid).param("aid", aid).param("pid", pid).param("uid", uid).update();

        // Attempt UPDATE -> must fail
        assertThrows(DataAccessException.class, () ->
                jdbcClient.sql("UPDATE ledger_entries SET label = 'Modified' WHERE id = :cid")
                        .param("cid", cid).update()
        );

        // Attempt DELETE -> must fail
        assertThrows(DataAccessException.class, () ->
                jdbcClient.sql("DELETE FROM ledger_entries WHERE id = :cid")
                        .param("cid", cid).update()
        );

        // Attempt TRUNCATE -> must fail
        assertThrows(DataAccessException.class, () ->
                jdbcClient.sql("TRUNCATE TABLE ledger_entries").update()
        );
    }
}
