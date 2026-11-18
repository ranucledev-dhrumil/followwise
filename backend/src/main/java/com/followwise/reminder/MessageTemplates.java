package com.followwise.reminder;

import java.util.Locale;
import java.util.Map;

public final class MessageTemplates {

    private MessageTemplates() {}

    public record RenderedMessage(String subject, String bodyText) {}

    public static String sanitize(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c >= 0x20 && c != 0x7F && c != '<' && c != '>') {
                sb.append(c);
            }
        }
        String sanitized = sb.toString();
        return sanitized.length() > 120 ? sanitized.substring(0, 120) : sanitized;
    }

    public static String formatAmount(long cents) {
        boolean negative = cents < 0;
        long abs = Math.abs(cents);
        long dollars = abs / 100;
        long remainder = abs % 100;
        String formatted = String.format(Locale.US, "%,d.%02d", dollars, remainder);
        return (negative ? "-$" : "$") + formatted;
    }

    public static RenderedMessage render(String messageKey, Map<String, String> rawVars) {
        String party = sanitize(rawVars.get("party"));
        String workspace = sanitize(rawVars.get("workspace"));
        String app = sanitize(rawVars.get("app"));
        String label = sanitize(rawVars.get("label"));
        String amount = sanitize(rawVars.get("amount"));
        String due = sanitize(rawVars.get("due"));
        String days = sanitize(rawVars.get("days"));
        String item = sanitize(rawVars.get("item"));
        String expiry = sanitize(rawVars.get("expiry"));

        String f1 = "Sent on behalf of " + workspace + " by FollowWise. Reply to this email to reach " + workspace + ". To stop these reminders, ask " + workspace + " to pause them.";
        String f2 = "Sent by FollowWise for " + workspace + ". You can pause reminders for a person in the app.";

        return switch (messageKey) {
            case "charge_reminder_before" -> new RenderedMessage(
                    label + " of " + amount + " is due on " + due,
                    "Hello " + party + ", this is a friendly reminder that " + label + " of " + amount + " is due on " + due + ", in " + days + " days. If you have already paid, thank you and please ignore this message.\n\n" + f1
            );
            case "charge_reminder_due" -> new RenderedMessage(
                    label + " of " + amount + " is due today",
                    "Hello " + party + ", " + label + " of " + amount + " is due today (" + due + "). If you have already paid, thank you and please ignore this message.\n\n" + f1
            );
            case "charge_reminder_late" -> new RenderedMessage(
                    label + " of " + amount + " is " + days + " days overdue",
                    "Hello " + party + ", our records show that " + label + " of " + amount + " was due on " + due + " and is now " + days + " days overdue. Please arrange payment when you can. If you have already paid, please let " + workspace + " know by replying to this email.\n\n" + f1
            );
            case "charge_overdue_owner" -> new RenderedMessage(
                    "Overdue: " + party + " owes " + amount,
                    "In " + app + ": " + party + " has an overdue charge, " + label + " of " + amount + ", due on " + due + " (" + days + " days overdue).\n\n" + f2
            );
            case "doc_reminder_before" -> new RenderedMessage(
                    "Please send " + item + " by " + due,
                    "Hello " + party + ", " + workspace + " is waiting for " + item + ". It is due on " + due + ", in " + days + " days. Please send it when you can by replying to this email.\n\n" + f1
            );
            case "doc_reminder_due" -> new RenderedMessage(
                    "Please send " + item + " today",
                    "Hello " + party + ", " + workspace + " is waiting for " + item + ", due today (" + due + "). Please send it by replying to this email.\n\n" + f1
            );
            case "doc_reminder_late" -> new RenderedMessage(
                    item + " is " + days + " days overdue",
                    "Hello " + party + ", " + workspace + " is still waiting for " + item + ", which was due on " + due + " (" + days + " days ago).\n\n" + f1
            );
            case "doc_expiry_reminder" -> new RenderedMessage(
                    item + " expires on " + expiry,
                    "Hello " + party + ", the " + item + " on file with " + workspace + " expires on " + expiry + ", in " + days + " days. Please send an updated copy before then.\n\n" + f1
            );
            case "doc_overdue_owner" -> new RenderedMessage(
                    "Overdue: " + item + " from " + party,
                    "In " + app + ": " + party + " has not provided " + item + ". It was due on " + due + " (" + days + " days overdue).\n\n" + f2
            );
            case "lease_ending_owner" -> new RenderedMessage(
                    "Lease ending: " + party + " on " + expiry,
                    "In " + app + ": the lease for " + party + " ends on " + expiry + ", in " + days + " days.\n\n" + f2
            );
            default -> throw new IllegalArgumentException("Unknown message key: " + messageKey);
        };
    }
}
