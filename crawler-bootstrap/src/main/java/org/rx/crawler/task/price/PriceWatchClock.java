package org.rx.crawler.task.price;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 业务窗口是 [06:00, 次日 01:00)。配置解析失败时窗口关闭，不回退成全天开放。
 * 业务日从 windowStart 起算，00:00 不重置访问预算。
 */
public final class PriceWatchClock {
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final ZoneId zone;
    private final LocalTime start;
    private final LocalTime end;
    private final String error;

    private PriceWatchClock(ZoneId zone, LocalTime start, LocalTime end, String error) {
        this.zone = zone;
        this.start = start;
        this.end = end;
        this.error = error;
    }

    public static PriceWatchClock parse(String zoneId, String startText, String endText) {
        ZoneId zone;
        try {
            zone = ZoneId.of(zoneId == null || zoneId.isBlank() ? "" : zoneId.trim());
        } catch (DateTimeException e) {
            return invalid("invalid timezone");
        }
        LocalTime start;
        LocalTime end;
        try {
            start = LocalTime.parse(startText == null ? "" : startText.trim(), HM);
            end = LocalTime.parse(endText == null ? "" : endText.trim(), HM);
        } catch (DateTimeParseException e) {
            return invalid("invalid window");
        }
        if (start.equals(end)) {
            return invalid("window start equals end");
        }
        return new PriceWatchClock(zone, start, end, null);
    }

    private static PriceWatchClock invalid(String error) {
        return new PriceWatchClock(null, null, null, error);
    }

    public boolean valid() {
        return error == null;
    }

    public String error() {
        return error;
    }

    public boolean open(Instant instant) {
        if (!valid()) {
            return false;
        }
        LocalTime time = instant.atZone(zone).toLocalTime();
        if (start.isBefore(end)) {
            return !time.isBefore(start) && time.isBefore(end);
        }
        return !time.isBefore(start) || time.isBefore(end);
    }

    /** 06:00 之前仍属前一个业务日。 */
    public LocalDate businessDate(Instant instant) {
        if (!valid()) {
            throw new IllegalStateException(error);
        }
        ZonedDateTime zoned = instant.atZone(zone);
        LocalDate date = zoned.toLocalDate();
        if (zoned.toLocalTime().isBefore(start)) {
            return date.minusDays(1);
        }
        return date;
    }

    /** 预算用尽后的下一次业务日开始。 */
    public Instant nextBusinessStart(Instant instant) {
        LocalDate business = businessDate(instant);
        return business.plusDays(1).atTime(start).atZone(zone).toInstant();
    }

    /** 当前若已过窗，返回今天的 windowStart；仍在窗口内则返回原时刻。 */
    public Instant nextOpen(Instant instant) {
        if (!valid()) {
            throw new IllegalStateException(error);
        }
        if (open(instant)) {
            return instant;
        }
        ZonedDateTime zoned = instant.atZone(zone);
        return zoned.toLocalDate().atTime(start).atZone(zone).toInstant();
    }

    public boolean notifyHour(Instant instant, int startHour, int endHour) {
        if (!valid() || startHour < 0 || endHour > 24 || startHour >= endHour) {
            return false;
        }
        int hour = instant.atZone(zone).getHour();
        return hour >= startHour && hour < endHour;
    }

    public Instant nextNotifyStart(Instant instant, int startHour) {
        ZonedDateTime zoned = instant.atZone(zone);
        if (zoned.getHour() < startHour) {
            return zoned.toLocalDate().atTime(startHour, 0).atZone(zone).toInstant();
        }
        return zoned.toLocalDate().plusDays(1).atTime(startHour, 0).atZone(zone).toInstant();
    }
}
