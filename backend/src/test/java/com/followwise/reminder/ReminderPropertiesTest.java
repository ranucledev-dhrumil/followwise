package com.followwise.reminder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReminderPropertiesTest {

    @Test
    void testValidDefaults() {
        ReminderProperties props = new ReminderProperties();
        assertDoesNotThrow(props::validate);
    }

    @Test
    void testInvalidRemindersEnabled() {
        ReminderProperties props = new ReminderProperties();
        props.setRawRemindersEnabled("maybe");
        IllegalStateException e = assertThrows(IllegalStateException.class, props::validate);
        assertTrue(e.getMessage().contains("REMINDERS_ENABLED must be exactly 'true' or 'false'"));
    }

    @Test
    void testInvalidSendHour() {
        ReminderProperties props = new ReminderProperties();
        props.setSendHour(-1);
        IllegalStateException e = assertThrows(IllegalStateException.class, props::validate);
        assertTrue(e.getMessage().contains("app.reminder.send-hour must be between 0 and 23"));

        props.setSendHour(24);
        e = assertThrows(IllegalStateException.class, props::validate);
        assertTrue(e.getMessage().contains("app.reminder.send-hour must be between 0 and 23"));
    }

    @Test
    void testInvalidCatchupDays() {
        ReminderProperties props = new ReminderProperties();
        props.setCatchupDays(-1);
        IllegalStateException e = assertThrows(IllegalStateException.class, props::validate);
        assertTrue(e.getMessage().contains("app.reminder.catchup-days must be between 0 and 7"));

        props.setCatchupDays(8);
        e = assertThrows(IllegalStateException.class, props::validate);
        assertTrue(e.getMessage().contains("app.reminder.catchup-days must be between 0 and 7"));
    }

    @Test
    void testInvalidMaxRemindersPerAppRun() {
        ReminderProperties props = new ReminderProperties();
        props.setMaxRemindersPerAppRun(0);
        IllegalStateException e = assertThrows(IllegalStateException.class, props::validate);
        assertTrue(e.getMessage().contains("app.reminder.max-per-app-run must be between 1 and 1000"));

        props.setMaxRemindersPerAppRun(1001);
        e = assertThrows(IllegalStateException.class, props::validate);
        assertTrue(e.getMessage().contains("app.reminder.max-per-app-run must be between 1 and 1000"));
    }

    @Test
    void testInvalidMaxOutboxBacklog() {
        ReminderProperties props = new ReminderProperties();
        props.setMaxOutboxBacklog(0);
        IllegalStateException e = assertThrows(IllegalStateException.class, props::validate);
        assertTrue(e.getMessage().contains("app.reminder.max-outbox-backlog must be between 1 and 10000"));

        props.setMaxOutboxBacklog(10001);
        e = assertThrows(IllegalStateException.class, props::validate);
        assertTrue(e.getMessage().contains("app.reminder.max-outbox-backlog must be between 1 and 10000"));
    }

    @Test
    void testInvalidPartyDailyCap() {
        ReminderProperties props = new ReminderProperties();
        props.setMaxRemindersPerPartyPerDay(0);
        IllegalStateException e = assertThrows(IllegalStateException.class, props::validate);
        assertTrue(e.getMessage().contains("app.reminder.max-per-party-per-day must be between 1 and 20"));

        props.setMaxRemindersPerPartyPerDay(21);
        e = assertThrows(IllegalStateException.class, props::validate);
        assertTrue(e.getMessage().contains("app.reminder.max-per-party-per-day must be between 1 and 20"));
    }

    @Test
    void testInvalidOwnerDailyCap() {
        ReminderProperties props = new ReminderProperties();
        props.setMaxRemindersPerOwnerPerDay(0);
        IllegalStateException e = assertThrows(IllegalStateException.class, props::validate);
        assertTrue(e.getMessage().contains("app.reminder.max-per-owner-per-day must be between 1 and 50"));

        props.setMaxRemindersPerOwnerPerDay(51);
        e = assertThrows(IllegalStateException.class, props::validate);
        assertTrue(e.getMessage().contains("app.reminder.max-per-owner-per-day must be between 1 and 50"));
    }
}
