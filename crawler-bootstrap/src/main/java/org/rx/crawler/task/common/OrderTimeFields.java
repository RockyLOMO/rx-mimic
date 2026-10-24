package org.rx.crawler.task.common;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Order dates follow the platform receipt date; commission payout is a separate date. */
public final class OrderTimeFields {
    private OrderTimeFields() { }

    public static String settlementDate(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String value = raw.trim();
        try {
            if (value.matches("\\d{8}")) return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE).toString();
            if (value.length() >= 10) return LocalDate.parse(value.substring(0, 10)).toString();
        } catch (DateTimeParseException ignored) { }
        return "";
    }

    public static String nextMonthSettlementDate(String receiptTime) {
        String date = settlementDate(receiptTime);
        return date.isEmpty() ? "" : LocalDate.parse(date).plusMonths(1).withDayOfMonth(20).toString();
    }
}
