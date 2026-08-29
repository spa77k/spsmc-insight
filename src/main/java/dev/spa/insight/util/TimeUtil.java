package dev.spa.insight.util;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;

/**
 * 週と日の区切りを一箇所に閉じ込める。区切りはJSTの月曜0時（ISO週）で固定する。
 */
public final class TimeUtil {

    public static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private TimeUtil() {
    }

    public static ZonedDateTime at(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZONE);
    }

    public static String weekKey(long epochMillis) {
        return weekKey(at(epochMillis).toLocalDate());
    }

    public static String weekKey(LocalDate date) {
        int year = date.get(IsoFields.WEEK_BASED_YEAR);
        int week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return String.format("%04d-W%02d", year, week);
    }

    public static LocalDate weekStartDate(String weekKey) {
        int year = Integer.parseInt(weekKey.substring(0, 4));
        int week = Integer.parseInt(weekKey.substring(6));
        return LocalDate.of(year, 1, 4)
                .with(IsoFields.WEEK_BASED_YEAR, year)
                .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week)
                .with(DayOfWeek.MONDAY);
    }

    public static long weekStartMillis(String weekKey) {
        return weekStartDate(weekKey).atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    public static long weekEndMillis(String weekKey) {
        return weekStartDate(weekKey).plusWeeks(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    public static String previousWeekKey(long epochMillis) {
        return weekKey(at(epochMillis).toLocalDate().minusWeeks(1));
    }

    public static String dayKey(long epochMillis) {
        return at(epochMillis).toLocalDate().format(DAY);
    }

    public static String dayKey(LocalDate date) {
        return date.format(DAY);
    }

    public static LocalDate parseDay(String dayKey) {
        return LocalDate.parse(dayKey, DAY);
    }

    public static long dayStartMillis(String dayKey) {
        return parseDay(dayKey).atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    public static long daysBetween(String fromDayKey, String toDayKey) {
        return ChronoUnit.DAYS.between(parseDay(fromDayKey), parseDay(toDayKey));
    }

    public static String stamp(long epochMillis) {
        return at(epochMillis).format(STAMP);
    }

    /**
     * EssentialsX や進捗ファイルに現れる "yyyy-MM-dd HH:mm:ss Z" 形式を読む。
     * 解釈できない場合は 0 を返し、呼び出し側で欠測として扱う。
     */
    public static long parseLooseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        String text = raw.trim();
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (RuntimeException ignored) {
            // ISO形式ではないので下の書式を試す。
        }
        String[] patterns = {
                "yyyy-MM-dd HH:mm:ss Z",
                "yyyy-MM-dd HH:mm:ss XXX",
                "yyyy-MM-dd HH:mm:ss"
        };
        for (String pattern : patterns) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
                if (pattern.endsWith("Z") || pattern.endsWith("XXX")) {
                    return ZonedDateTime.parse(text, formatter).toInstant().toEpochMilli();
                }
                return LocalDateTime.parse(text, formatter).atZone(ZONE).toInstant().toEpochMilli();
            } catch (RuntimeException ignored) {
                // 次の書式を試す。
            }
        }
        return 0L;
    }
}
