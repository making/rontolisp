package am.ik.jdbc.postgresql;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.jspecify.annotations.Nullable;

/**
 * A date, a time or both as the server writes them in the ISO style (PostgreSQL
 * documentation, "Date/Time Output"): {@code 1997-12-17}, {@code 07:37:16.123456},
 * {@code 1997-12-17 07:37:16-08}, a year before 1 with {@code BC} after it, an offset of
 * hours, hours and minutes or hours, minutes and seconds.
 */
final class Moment {

	// The date, or null for a time alone.
	final @Nullable LocalDate date;

	// The time, or null for a date alone.
	final @Nullable LocalTime time;

	// The offset from UTC, or null where the text has none.
	final @Nullable ZoneOffset offset;

	private Moment(@Nullable LocalDate date, @Nullable LocalTime time, @Nullable ZoneOffset offset) {
		this.date = date;
		this.time = time;
		this.offset = offset;
	}

	/**
	 * Reads the server's text.
	 * @throws DateTimeException for a text that is no date and no time, {@code infinity}
	 * included
	 */
	static Moment parse(String text) {
		int length = text.length();
		boolean before = text.endsWith(" BC");
		if (before) {
			length -= 3;
		}
		int at = 0;
		LocalDate date = null;
		LocalTime time = null;
		ZoneOffset offset = null;
		int dash = text.indexOf('-', 1);
		if (dash > 0 && text.indexOf(':') != 2) {
			int year = number(text, 0, dash);
			int month = number(text, dash + 1, dash + 3);
			int day = number(text, dash + 4, dash + 6);
			date = LocalDate.of(before ? 1 - year : year, month, day);
			at = dash + 6;
			if (at < length && (text.charAt(at) == ' ' || text.charAt(at) == 'T')) {
				at++;
			}
		}
		if (at < length) {
			int hour = number(text, at, at + 2);
			int minute = number(text, at + 3, at + 5);
			int second = number(text, at + 6, at + 8);
			at += 8;
			int nanos = 0;
			if (at < length && text.charAt(at) == '.') {
				int digits = 0;
				at++;
				while (at < length && Character.isDigit(text.charAt(at))) {
					if (digits < 9) {
						nanos = nanos * 10 + (text.charAt(at) - '0');
						digits++;
					}
					at++;
				}
				while (digits++ < 9) {
					nanos *= 10;
				}
			}
			time = LocalTime.of(hour, minute, second, nanos);
			if (at < length) {
				int sign = text.charAt(at) == '-' ? -1 : 1;
				int hours = number(text, at + 1, at + 3);
				int minutes = at + 4 < length ? number(text, at + 4, at + 6) : 0;
				int seconds = at + 7 < length ? number(text, at + 7, at + 9) : 0;
				offset = ZoneOffset.ofTotalSeconds(sign * (hours * 3600 + minutes * 60 + seconds));
			}
		}
		if (date == null && time == null) {
			throw new DateTimeException("no date and no time: " + text);
		}
		return new Moment(date, time, offset);
	}

	private static int number(String text, int start, int end) {
		if (start >= end || end > text.length()) {
			throw new DateTimeException("no date and no time: " + text);
		}
		int value = 0;
		for (int i = start; i < end; i++) {
			int digit = text.charAt(i) - '0';
			if (digit < 0 || digit > 9) {
				throw new DateTimeException("no date and no time: " + text);
			}
			value = value * 10 + digit;
		}
		return value;
	}

	/** The date and time on the wall, midnight for a date alone. */
	LocalDateTime local() {
		if (this.date == null) {
			throw new DateTimeException("a time alone has no date");
		}
		LocalDateTime local = this.date.atTime(this.time != null ? this.time : LocalTime.MIDNIGHT);
		// A text with an offset is an instant: the wall is the one of where the program
		// runs.
		return this.offset == null ? local
				: local.atOffset(this.offset).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
	}

	/**
	 * The instant, the wall of a text with no offset being the one where the program
	 * runs.
	 */
	OffsetDateTime instant() {
		if (this.date == null) {
			throw new DateTimeException("a time alone has no date");
		}
		LocalDateTime local = this.date.atTime(this.time != null ? this.time : LocalTime.MIDNIGHT);
		return this.offset != null ? local.atOffset(this.offset)
				: local.atZone(ZoneId.systemDefault()).toOffsetDateTime();
	}

	/** The time of day, at the offset of the text or of where the program runs. */
	OffsetTime timeOfDay() {
		if (this.time == null) {
			throw new DateTimeException("a date alone has no time");
		}
		if (this.offset != null) {
			return this.time.atOffset(this.offset);
		}
		return this.time.atOffset(ZoneId.systemDefault().getRules().getOffset(Instant.EPOCH));
	}

	// Writing.

	/** A date as the server reads it. */
	static String text(LocalDate date) {
		StringBuilder text = new StringBuilder(16);
		date(text, date);
		return era(text, date).toString();
	}

	/** A time as the server reads it, its fraction as far as it is not zero. */
	static String text(LocalTime time) {
		return time(new StringBuilder(18), time).toString();
	}

	static String text(LocalDateTime dateTime) {
		StringBuilder text = new StringBuilder(32);
		date(text, dateTime.toLocalDate()).append(' ');
		time(text, dateTime.toLocalTime());
		return era(text, dateTime.toLocalDate()).toString();
	}

	static String text(OffsetDateTime dateTime) {
		StringBuilder text = new StringBuilder(40);
		date(text, dateTime.toLocalDate()).append(' ');
		time(text, dateTime.toLocalTime());
		offset(text, dateTime.getOffset());
		return era(text, dateTime.toLocalDate()).toString();
	}

	static String text(OffsetTime time) {
		return offset(time(new StringBuilder(24), time.toLocalTime()), time.getOffset()).toString();
	}

	private static StringBuilder date(StringBuilder text, LocalDate date) {
		int year = date.getYear() > 0 ? date.getYear() : 1 - date.getYear();
		for (int width = 1000; width > 1 && year < width; width /= 10) {
			text.append('0');
		}
		text.append(year).append('-');
		return two(two(text, date.getMonthValue()).append('-'), date.getDayOfMonth());
	}

	private static StringBuilder era(StringBuilder text, LocalDate date) {
		return date.getYear() > 0 ? text : text.append(" BC");
	}

	private static StringBuilder time(StringBuilder text, LocalTime time) {
		two(two(two(text, time.getHour()).append(':'), time.getMinute()).append(':'), time.getSecond());
		int nanos = time.getNano();
		if (nanos != 0) {
			text.append('.');
			for (int unit = 100_000_000; nanos != 0; unit /= 10) {
				text.append((char) ('0' + nanos / unit));
				nanos %= unit;
			}
		}
		return text;
	}

	private static StringBuilder offset(StringBuilder text, ZoneOffset offset) {
		int seconds = offset.getTotalSeconds();
		text.append(seconds < 0 ? '-' : '+');
		seconds = Math.abs(seconds);
		two(two(text, seconds / 3600).append(':'), seconds / 60 % 60);
		return seconds % 60 == 0 ? text : two(text.append(':'), seconds % 60);
	}

	private static StringBuilder two(StringBuilder text, int value) {
		return text.append((char) ('0' + value / 10)).append((char) ('0' + value % 10));
	}

}
