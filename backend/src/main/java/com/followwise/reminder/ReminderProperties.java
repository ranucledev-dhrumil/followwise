package com.followwise.reminder;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReminderProperties {

    private String rawRemindersEnabled;
    private boolean remindersEnabled;
    private int intervalMinutes;
    private int catchupDays;
    private int sendHour;
    private int maxRemindersPerPartyPerDay;
    private int maxRemindersPerOwnerPerDay;
    private int maxRemindersPerAppRun;
    private int maxOutboxBacklog;
    private int maxCandidatesScannedPerRule = 5000;
    private int pageSize = 200;

    public ReminderProperties() {
        this("true", 15, 2, 9, 2, 10, 100, 200);
    }

    public ReminderProperties(
            @Value("${app.reminders-enabled:false}") String rawRemindersEnabled,
            @Value("${app.reminder.interval-minutes:15}") int intervalMinutes,
            @Value("${app.reminder.catchup-days:2}") int catchupDays,
            @Value("${app.reminder.send-hour:9}") int sendHour,
            @Value("${app.reminder.max-per-party-per-day:2}") int maxRemindersPerPartyPerDay,
            @Value("${app.reminder.max-per-owner-per-day:10}") int maxRemindersPerOwnerPerDay,
            @Value("${app.reminder.max-per-app-run:100}") int maxRemindersPerAppRun,
            @Value("${app.reminder.max-outbox-backlog:200}") int maxOutboxBacklog
    ) {
        this.rawRemindersEnabled = rawRemindersEnabled;
        this.remindersEnabled = "true".equals(rawRemindersEnabled);
        this.intervalMinutes = intervalMinutes;
        this.catchupDays = catchupDays;
        this.sendHour = sendHour;
        this.maxRemindersPerPartyPerDay = maxRemindersPerPartyPerDay;
        this.maxRemindersPerOwnerPerDay = maxRemindersPerOwnerPerDay;
        this.maxRemindersPerAppRun = maxRemindersPerAppRun;
        this.maxOutboxBacklog = maxOutboxBacklog;
    }

    @PostConstruct
    public void validate() {
        if (!"true".equals(rawRemindersEnabled) && !"false".equals(rawRemindersEnabled)) {
            throw new IllegalStateException("REMINDERS_ENABLED must be exactly 'true' or 'false'");
        }
        if (intervalMinutes < 5 || intervalMinutes > 120) {
            throw new IllegalStateException("app.reminder.interval-minutes must be between 5 and 120");
        }
        if (catchupDays < 0 || catchupDays > 7) {
            throw new IllegalStateException("app.reminder.catchup-days must be between 0 and 7");
        }
        if (sendHour < 0 || sendHour > 23) {
            throw new IllegalStateException("app.reminder.send-hour must be between 0 and 23");
        }
        if (maxRemindersPerPartyPerDay < 1 || maxRemindersPerPartyPerDay > 20) {
            throw new IllegalStateException("app.reminder.max-per-party-per-day must be between 1 and 20");
        }
        if (maxRemindersPerOwnerPerDay < 1 || maxRemindersPerOwnerPerDay > 50) {
            throw new IllegalStateException("app.reminder.max-per-owner-per-day must be between 1 and 50");
        }
        if (maxRemindersPerAppRun < 1 || maxRemindersPerAppRun > 1000) {
            throw new IllegalStateException("app.reminder.max-per-app-run must be between 1 and 1000");
        }
        if (maxOutboxBacklog < 1 || maxOutboxBacklog > 10000) {
            throw new IllegalStateException("app.reminder.max-outbox-backlog must be between 1 and 10000");
        }
    }

    public boolean isRemindersEnabled() {
        return remindersEnabled;
    }

    public void setRemindersEnabled(boolean remindersEnabled) {
        this.remindersEnabled = remindersEnabled;
        this.rawRemindersEnabled = String.valueOf(remindersEnabled);
    }

    public int getIntervalMinutes() {
        return intervalMinutes;
    }

    public void setIntervalMinutes(int intervalMinutes) {
        this.intervalMinutes = intervalMinutes;
    }

    public int getCatchupDays() {
        return catchupDays;
    }

    public void setCatchupDays(int catchupDays) {
        this.catchupDays = catchupDays;
    }

    public int getSendHour() {
        return sendHour;
    }

    public void setSendHour(int sendHour) {
        this.sendHour = sendHour;
    }

    public int getMaxRemindersPerPartyPerDay() {
        return maxRemindersPerPartyPerDay;
    }

    public void setMaxRemindersPerPartyPerDay(int maxRemindersPerPartyPerDay) {
        this.maxRemindersPerPartyPerDay = maxRemindersPerPartyPerDay;
    }

    public int getMaxRemindersPerOwnerPerDay() {
        return maxRemindersPerOwnerPerDay;
    }

    public void setMaxRemindersPerOwnerPerDay(int maxRemindersPerOwnerPerDay) {
        this.maxRemindersPerOwnerPerDay = maxRemindersPerOwnerPerDay;
    }

    public int getMaxRemindersPerAppRun() {
        return maxRemindersPerAppRun;
    }

    public void setMaxRemindersPerAppRun(int maxRemindersPerAppRun) {
        this.maxRemindersPerAppRun = maxRemindersPerAppRun;
    }

    public int getMaxOutboxBacklog() {
        return maxOutboxBacklog;
    }

    public void setMaxOutboxBacklog(int maxOutboxBacklog) {
        this.maxOutboxBacklog = maxOutboxBacklog;
    }

    public int getMaxCandidatesScannedPerRule() {
        return maxCandidatesScannedPerRule;
    }

    public void setMaxCandidatesScannedPerRule(int maxCandidatesScannedPerRule) {
        this.maxCandidatesScannedPerRule = maxCandidatesScannedPerRule;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public void setRawRemindersEnabled(String rawRemindersEnabled) {
        this.rawRemindersEnabled = rawRemindersEnabled;
    }
}
