package com.followwise.reminder;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageTemplatesTest {

    @Test
    void testAllTenTemplatesRender() {
        String ws = "Acme Property Management";
        String app = "Rental Manager";
        String party = "Alice Smith";
        String label = "June Rent";
        String amount = MessageTemplates.formatAmount(125000L); // $1,250.00
        String due = "2026-06-15";
        String days = "3";
        String item = "Tax Document";
        String expiry = "2026-06-30";

        Map<String, String> vars = Map.of(
                "workspace", ws,
                "app", app,
                "party", party,
                "label", label,
                "amount", amount,
                "due", due,
                "days", days,
                "item", item,
                "expiry", expiry
        );

        // 1. charge_reminder_before
        var t1 = MessageTemplates.render("charge_reminder_before", vars);
        assertEquals("June Rent of $1,250.00 is due on 2026-06-15", t1.subject());
        assertTrue(t1.bodyText().contains("Hello Alice Smith, this is a friendly reminder that June Rent of $1,250.00 is due on 2026-06-15, in 3 days."));
        assertTrue(t1.bodyText().contains("Sent on behalf of Acme Property Management by FollowWise."));

        // 2. charge_reminder_due
        var t2 = MessageTemplates.render("charge_reminder_due", vars);
        assertEquals("June Rent of $1,250.00 is due today", t2.subject());
        assertTrue(t2.bodyText().contains("Hello Alice Smith, June Rent of $1,250.00 is due today (2026-06-15)."));

        // 3. charge_reminder_late
        var t3 = MessageTemplates.render("charge_reminder_late", vars);
        assertEquals("June Rent of $1,250.00 is 3 days overdue", t3.subject());
        assertTrue(t3.bodyText().contains("Hello Alice Smith, our records show that June Rent of $1,250.00 was due on 2026-06-15 and is now 3 days overdue."));

        // 4. charge_overdue_owner
        var t4 = MessageTemplates.render("charge_overdue_owner", vars);
        assertEquals("Overdue: Alice Smith owes $1,250.00", t4.subject());
        assertTrue(t4.bodyText().contains("In Rental Manager: Alice Smith has an overdue charge, June Rent of $1,250.00, due on 2026-06-15 (3 days overdue)."));
        assertTrue(t4.bodyText().contains("Sent by FollowWise for Acme Property Management."));

        // 5. doc_reminder_before
        var t5 = MessageTemplates.render("doc_reminder_before", vars);
        assertEquals("Please send Tax Document by 2026-06-15", t5.subject());
        assertTrue(t5.bodyText().contains("Hello Alice Smith, Acme Property Management is waiting for Tax Document. It is due on 2026-06-15, in 3 days."));

        // 6. doc_reminder_due
        var t6 = MessageTemplates.render("doc_reminder_due", vars);
        assertEquals("Please send Tax Document today", t6.subject());
        assertTrue(t6.bodyText().contains("Hello Alice Smith, Acme Property Management is waiting for Tax Document, due today (2026-06-15)."));

        // 7. doc_reminder_late
        var t7 = MessageTemplates.render("doc_reminder_late", vars);
        assertEquals("Tax Document is 3 days overdue", t7.subject());
        assertTrue(t7.bodyText().contains("Hello Alice Smith, Acme Property Management is still waiting for Tax Document, which was due on 2026-06-15 (3 days ago)."));

        // 8. doc_expiry_reminder
        var t8 = MessageTemplates.render("doc_expiry_reminder", vars);
        assertEquals("Tax Document expires on 2026-06-30", t8.subject());
        assertTrue(t8.bodyText().contains("Hello Alice Smith, the Tax Document on file with Acme Property Management expires on 2026-06-30, in 3 days."));

        // 9. doc_overdue_owner
        var t9 = MessageTemplates.render("doc_overdue_owner", vars);
        assertEquals("Overdue: Tax Document from Alice Smith", t9.subject());
        assertTrue(t9.bodyText().contains("In Rental Manager: Alice Smith has not provided Tax Document. It was due on 2026-06-15 (3 days overdue)."));

        // 10. lease_ending_owner
        var t10 = MessageTemplates.render("lease_ending_owner", vars);
        assertEquals("Lease ending: Alice Smith on 2026-06-30", t10.subject());
        assertTrue(t10.bodyText().contains("In Rental Manager: the lease for Alice Smith ends on 2026-06-30, in 3 days."));
    }

    @Test
    void testSanitization() {
        String dirtyParty = "Alice\r\n<script>alert('xss')</script>\tSmith\u0000";
        String longLabel = "A".repeat(200);

        Map<String, String> vars = Map.of(
                "workspace", "Workspace\nName",
                "app", "App\rName",
                "party", dirtyParty,
                "label", longLabel,
                "amount", "$100.00",
                "due", "2026-06-15",
                "days", "0",
                "item", longLabel,
                "expiry", "2026-06-15"
        );

        var rendered = MessageTemplates.render("charge_reminder_due", vars);

        assertFalse(rendered.subject().contains("\r"));
        assertFalse(rendered.subject().contains("\n"));
        assertFalse(rendered.subject().contains("<"));
        assertFalse(rendered.subject().contains(">"));
        assertTrue(rendered.subject().length() < 300);

        assertFalse(rendered.bodyText().contains("<script>"));
        assertFalse(rendered.bodyText().contains("\u0000"));
        // Label must be truncated to 120 chars
        assertTrue(rendered.bodyText().contains("A".repeat(120)));
        assertFalse(rendered.bodyText().contains("A".repeat(121)));
    }

    @Test
    void testCurrencyFormatting() {
        assertEquals("$0.00", MessageTemplates.formatAmount(0));
        assertEquals("$0.05", MessageTemplates.formatAmount(5));
        assertEquals("$0.50", MessageTemplates.formatAmount(50));
        assertEquals("$1.00", MessageTemplates.formatAmount(100));
        assertEquals("$1,234.56", MessageTemplates.formatAmount(123456));
        assertEquals("-$12.34", MessageTemplates.formatAmount(-1234));
    }
}
