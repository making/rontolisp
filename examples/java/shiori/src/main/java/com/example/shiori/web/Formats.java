// Shiori: how a value is written on a page.
package com.example.shiori.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

public final class Formats {

    private Formats() {
    }

    private static final String[] WEEKDAYS = { "日", "月", "火", "水", "木", "金", "土" };

    // 2026年1月31日
    public static String date(LocalDate date) {
        return (date == null) ? null : year(date) + "年" + date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
    }

    // 1月31日
    public static String monthAndDay(LocalDate date) {
        return date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
    }

    // 2026年1月
    public static String yearAndMonth(LocalDate date) {
        return year(date) + "年" + date.getMonthValue() + "月";
    }

    // 2026.01.31, as a stamp or a note's date reads
    public static String dotted(LocalDate date) {
        return (date == null) ? null
                : year(date) + "." + twoDigits(date.getMonthValue()) + "." + twoDigits(date.getDayOfMonth());
    }

    // The day of the week, as one character: 土
    public static String weekday(LocalDate date) {
        return WEEKDAYS[date.getDayOfWeek().getValue() % 7];
    }

    // 1,234,567
    public static String grouped(long number) {
        String digits = Long.toString(Math.abs(number));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                text.append(',');
            }
            text.append(digits.charAt(i));
        }
        return (number < 0) ? "-" + text : text.toString();
    }

    // Whether a text holds Japanese, which a quote then sets top to bottom.
    public static boolean japanese(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x3000) {
                return true;
            }
        }
        return false;
    }

    // A query string's value.
    public static String queryValue(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    // At least four digits, as a year is written.
    private static String year(LocalDate date) {
        String year = Integer.toString(date.getYear());
        return (year.length() < 4) ? "0".repeat(4 - year.length()) + year : year;
    }

    private static String twoDigits(int number) {
        return (number < 10) ? "0" + number : Integer.toString(number);
    }

}
