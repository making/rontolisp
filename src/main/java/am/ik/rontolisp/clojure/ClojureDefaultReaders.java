package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The read-time half of the oracle's two default data readers: {@code #inst}, its
 * {@code clojure.instant/read-instant-date}, and {@code #uuid}, its
 * {@code java.util.UUID/fromString}. A literal in source is read where the oracle reads
 * it, so a malformed one is a read error positioned after its string; the reader answers
 * the marker {@code (%inst ms)} or {@code (%uuid msb lsb)}, which the lowering builds as
 * the value ({@link #construction}). The run-time half -- {@code read-string},
 * {@code clojure.edn}, {@code clojure.instant} -- is {@code clojure.lisp}'s, which
 * computes the same answers ({@code ClojureDefaultReadersTest} pins both against the JDK
 * classes the oracle calls).
 *
 * <p>
 * A timestamp is matched the way the oracle's pattern matches it, its fields checked as
 * {@code clojure.instant/validated} checks them, and its instant computed as the oracle's
 * lenient {@code java.util.GregorianCalendar} computes it: the Julian calendar before
 * 1582-10-15, the Gregorian one from then on. The arithmetic is spelled out rather than
 * delegated to {@code GregorianCalendar}, so a host without time-zone data computes it
 * alike. A UUID is read like {@code UUID.fromString}: five groups of hex digits between
 * four dashes, each masked to its width.
 */
final class ClojureDefaultReaders {

	/** Marks an {@code #inst} literal: {@code (%inst ms)}. */
	static final LispSymbol INST = new LispSymbol("%inst");

	/** Marks a {@code #uuid} literal: {@code (%uuid msb lsb)}. */
	static final LispSymbol UUID = new LispSymbol("%uuid");

	/** The constructor an {@code #inst} literal lowers to ({@code clojure.lisp}). */
	static final String MAKE_INST = "RONTOLISP::%CLOJURE-MAKE-INST";

	/** The constructor a {@code #uuid} literal lowers to ({@code clojure.lisp}). */
	static final String MAKE_UUID = "RONTOLISP::%CLOJURE-MAKE-UUID";

	/**
	 * The run-time {@code read-instant-date} ({@code clojure.lisp}), which an
	 * {@code #inst} literal whose printed form no read takes lowers to.
	 */
	static final String READ_INSTANT = "RONTOLISP::%CLOJURE-INSTANT-READ-DATE";

	/** The day of 1582-10-15, the Gregorian calendar's first, counted from 1970-01-01. */
	static final long CUTOVER_DAY = -141427;

	private static final long DAY_MS = 86_400_000L;

	private static final String WEEKDAYS = "SunMonTueWedThuFriSat";

	private static final String MONTHS = "JanFebMarAprMayJunJulAugSepOctNovDec";

	private ClojureDefaultReaders() {
	}

	/**
	 * Whether the head marks an {@code #inst} or {@code #uuid} literal: the reader's own
	 * marker, or a symbol of its spelling (a macro answer decodes to one).
	 * @param head the head of a datum
	 * @return whether the datum is such a literal
	 */
	static boolean isMarker(LispVal head) {
		return ClojureLowerUtil.isSymbolNamed(head, INST.name()) || ClojureLowerUtil.isSymbolNamed(head, UUID.name());
	}

	/**
	 * The numbers of a well-formed marker: an {@code #inst}'s milliseconds, a
	 * {@code #uuid}'s two halves; null for anything else.
	 */
	private static long @Nullable [] numbers(List<LispVal> items) {
		if (items.isEmpty() || !isMarker(items.get(0))) {
			return null;
		}
		boolean inst = ClojureLowerUtil.isSymbolNamed(items.get(0), INST.name());
		if (items.size() != (inst ? 2 : 3)) {
			return null;
		}
		long[] numbers = new long[items.size() - 1];
		for (int i = 1; i < items.size(); i++) {
			if (!(items.get(i) instanceof LispInteger number)) {
				return null;
			}
			numbers[i - 1] = number.value();
		}
		return numbers;
	}

	/**
	 * The {@code #inst} literal of the read form.
	 * @param form the form after the tag
	 * @return the marker {@code (%inst ms)}
	 * @throws IllegalArgumentException in the oracle's words when the form is no valid
	 * timestamp string
	 */
	static LispVal instant(LispVal form) {
		if (!(form instanceof LispString text)) {
			throw new IllegalArgumentException("a timestamp needs a string");
		}
		long[] fields = parse(text.value());
		if (fields == null) {
			throw new IllegalArgumentException("Unrecognized date/time syntax: " + text.value());
		}
		String failed = validate(fields);
		if (failed != null) {
			throw new IllegalArgumentException("failed: " + failed);
		}
		return ClojureLowerUtil.list(INST, new LispInteger(millis(fields[0], fields[1], fields[2], fields[3], fields[4],
				fields[5], fields[6] / 1_000_000, fields[7], fields[8], fields[9])));
	}

	/**
	 * The {@code #uuid} literal of the read form.
	 * @param form the form after the tag
	 * @return the marker {@code (%uuid msb lsb)}
	 * @throws IllegalArgumentException in the oracle's words when the form is no UUID
	 * string
	 */
	static LispVal uuid(LispVal form) {
		if (!(form instanceof LispString text)) {
			throw new IllegalArgumentException("#uuid data reader expected string");
		}
		long[] halves = uuidOf(text.value());
		return ClojureLowerUtil.list(UUID, new LispInteger(halves[0]), new LispInteger(halves[1]));
	}

	/**
	 * The construction a marker lowers to: the run-time constructor over its numbers. An
	 * instant is the one the oracle's compiler embeds, which writes a {@code Date}
	 * constant as its {@code #inst} literal and reads that back when the code loads: a
	 * date before year 1 prints its year of the era and comes back that year AD, and one
	 * past year 9999 prints a fifth digit no read takes, so the code signals when it runs
	 * (the read of the printed text, here at run time too).
	 * @param items the marker's items, head included
	 * @return {@code (%clojure-make-inst ms)} or {@code (%clojure-make-uuid msb lsb)}
	 */
	static LispVal construction(List<LispVal> items) {
		long[] numbers = numbers(items);
		if (numbers == null) {
			throw new LispReadException("not an #inst or #uuid literal: " + ClojureLowerUtil.list(items).print());
		}
		if (numbers.length == 2) {
			return ClojureLowerUtil.list(new LispSymbol(MAKE_UUID), new LispInteger(numbers[0]),
					new LispInteger(numbers[1]));
		}
		String printed = instantString(numbers[0]);
		long[] fields = parse(printed);
		if (fields == null) {
			return ClojureLowerUtil.list(new LispSymbol(READ_INSTANT), LispString.literal(printed));
		}
		return ClojureLowerUtil.list(new LispSymbol(MAKE_INST), new LispInteger(millis(fields[0], fields[1], fields[2],
				fields[3], fields[4], fields[5], fields[6] / 1_000_000, fields[7], fields[8], fields[9])));
	}

	/**
	 * How the oracle prints the marker's value, {@code #inst "..."} or
	 * {@code #uuid "..."}, or null when the items are no marker.
	 * @param items a datum's items
	 * @return the printed value, or null
	 */
	static @Nullable String printed(List<LispVal> items) {
		long[] numbers = numbers(items);
		if (numbers == null) {
			return null;
		}
		return numbers.length == 1 ? "#inst \"" + instantString(numbers[0]) + "\""
				: "#uuid \"" + uuidString(numbers[0], numbers[1]) + "\"";
	}

	/**
	 * The oracle's {@code toString} of the marker's value, which a {@code Duplicate key}
	 * refusal names, or null when the items are no marker.
	 * @param items a datum's items
	 * @return the text, or null
	 */
	static @Nullable String text(List<LispVal> items) {
		long[] numbers = numbers(items);
		if (numbers == null) {
			return null;
		}
		return numbers.length == 1 ? dateString(numbers[0]) : uuidString(numbers[0], numbers[1]);
	}

	/**
	 * The ten fields {@code clojure.instant/parse-timestamp} hands its constructor --
	 * years, months, days, hours, minutes, seconds, nanoseconds, offset sign, offset
	 * hours, offset minutes, an omitted one its default -- matched the way the oracle's
	 * pattern matches: the most components first, then fewer while what follows them is
	 * no offset reaching the end (so {@code 2020-05:30} is 2020 at {@code -05:30}). A
	 * fraction keeps its first nine digits.
	 * @param s the text
	 * @return the fields, or null when the text is no timestamp
	 */
	static long @Nullable [] parse(String s) {
		int n = s.length();
		long year = digits(s, 0, 4);
		if (year < 0) {
			return null;
		}
		List<long[]> levels = new ArrayList<>();
		levels.add(new long[] { 4, year });
		int i = 4;
		String separators = "--T::";
		for (int k = 0; k < separators.length() && levels.size() == k + 1; k++) {
			long value = i < n && s.charAt(i) == separators.charAt(k) ? digits(s, i + 1, 2) : -1;
			if (value >= 0) {
				i += 3;
				levels.add(new long[] { i, value });
			}
		}
		if (levels.size() == 6 && i + 1 < n && s.charAt(i) == '.' && isDigit(s.charAt(i + 1))) {
			int j = i + 1;
			long nanos = 0;
			int count = 0;
			while (j < n && isDigit(s.charAt(j))) {
				if (count < 9) {
					nanos = nanos * 10 + (s.charAt(j) - '0');
				}
				count++;
				j++;
			}
			for (int k = Math.min(count, 9); k < 9; k++) {
				nanos *= 10;
			}
			levels.add(new long[] { j, nanos });
		}
		for (int level = levels.size() - 1; level >= 0; level--) {
			long[] offset = offset(s, (int) levels.get(level)[0]);
			if (offset != null) {
				long[] fields = { 0, 1, 1, 0, 0, 0, 0, offset[0], offset[1], offset[2] };
				for (int k = 0; k <= level; k++) {
					fields[k] = levels.get(k)[1];
				}
				return fields;
			}
		}
		return null;
	}

	/** The offset the rest of the text from {@code i} spells, or null when it is none. */
	private static long @Nullable [] offset(String s, int i) {
		int n = s.length();
		if (i == n || (i + 1 == n && s.charAt(i) == 'Z')) {
			return new long[] { 0, 0, 0 };
		}
		if (i + 6 == n && (s.charAt(i) == '+' || s.charAt(i) == '-') && s.charAt(i + 3) == ':') {
			long hours = digits(s, i + 1, 2);
			long minutes = digits(s, i + 4, 2);
			if (hours >= 0 && minutes >= 0) {
				return new long[] { s.charAt(i) == '-' ? -1 : 1, hours, minutes };
			}
		}
		return null;
	}

	/**
	 * The value of the {@code n} ASCII digits at {@code i}, or -1 when there are none.
	 */
	private static long digits(String s, int i, int n) {
		if (i + n > s.length()) {
			return -1;
		}
		long value = 0;
		for (int k = i; k < i + n; k++) {
			if (!isDigit(s.charAt(k))) {
				return -1;
			}
			value = value * 10 + (s.charAt(k) - '0');
		}
		return value;
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	/**
	 * The first of {@code clojure.instant/validated}'s checks the fields fail, as the
	 * oracle's message quotes it, or null when they pass. The years go unchecked; the
	 * days of a month are the Gregorian calendar's.
	 * @param f the ten fields
	 * @return the failed check, or null
	 */
	static @Nullable String validate(long[] f) {
		if (f[1] < 1 || f[1] > 12) {
			return "(<= 1 months 12)";
		}
		if (f[2] < 1 || f[2] > daysInMonth(f[1], f[0])) {
			return "(<= 1 days (days-in-month months (leap-year? years)))";
		}
		if (f[3] < 0 || f[3] > 23) {
			return "(<= 0 hours 23)";
		}
		if (f[4] < 0 || f[4] > 59) {
			return "(<= 0 minutes 59)";
		}
		if (f[5] < 0 || f[5] > (f[4] == 59 ? 60 : 59)) {
			return "(<= 0 seconds (if (= minutes 59) 60 59))";
		}
		if (f[6] < 0 || f[6] > 999_999_999) {
			return "(<= 0 nanoseconds 999999999)";
		}
		if (f[7] < -1 || f[7] > 1) {
			return "(<= -1 offset-sign 1)";
		}
		if (f[8] < 0 || f[8] > 23) {
			return "(<= 0 offset-hours 23)";
		}
		if (f[9] < 0 || f[9] > 59) {
			return "(<= 0 offset-minutes 59)";
		}
		return null;
	}

	private static long daysInMonth(long month, long year) {
		if (month != 2) {
			return month == 4 || month == 6 || month == 9 || month == 11 ? 30 : 31;
		}
		return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) ? 29 : 28;
	}

	/**
	 * The days from 1970-01-01 to the date of the Julian calendar, or of the proleptic
	 * Gregorian one, year 0 being 1 BC.
	 * @param year the year
	 * @param month the month, 1 to 12
	 * @param day the day of the month
	 * @param julian whether the date is the Julian calendar's
	 * @return the day
	 */
	static long daysFromCivil(long year, long month, long day, boolean julian) {
		long cycle = julian ? 4 : 400;
		long y = month <= 2 ? year - 1 : year;
		long era = Math.floorDiv(y, cycle);
		long yoe = y - era * cycle;
		long doy = (153 * (month > 2 ? month - 3 : month + 9) + 2) / 5 + day - 1;
		return era * (julian ? 1461 : 146097) + yoe * 365 + yoe / 4 - yoe / 100 + doy - (julian ? 719470 : 719468);
	}

	/**
	 * The date of the day counted from 1970-01-01, in the Julian calendar or the
	 * proleptic Gregorian one.
	 * @param days the day
	 * @param julian whether to answer the Julian calendar's date
	 * @return {@code {year, month, day}}
	 */
	static long[] civilFromDays(long days, boolean julian) {
		long cycle = julian ? 1461 : 146097;
		long z = days + (julian ? 719470 : 719468);
		long era = Math.floorDiv(z, cycle);
		long doe = z - era * cycle;
		long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
		long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
		long mp = (5 * doy + 2) / 153;
		long month = mp < 10 ? mp + 3 : mp - 9;
		return new long[] { yoe + era * (julian ? 4 : 400) + (month <= 2 ? 1 : 0), month,
				doy - (153 * mp + 2) / 5 + 1 };
	}

	/**
	 * The milliseconds of the date and time at the offset, as the oracle's lenient
	 * {@code GregorianCalendar} computes them: the time of day first (a 60th second
	 * carries into the next day), then the day, Julian before 1582 and Gregorian after;
	 * in 1582 the Gregorian day when it falls on or after the cutover, else the Julian
	 * one.
	 * @param years the year
	 * @param months the month
	 * @param days the day of the month
	 * @param hours the hour
	 * @param minutes the minute
	 * @param seconds the second
	 * @param millis the millisecond
	 * @param sign the offset's sign, -1, 0 or 1
	 * @param offsetHours the offset's hours
	 * @param offsetMinutes the offset's minutes
	 * @return the milliseconds since 1970-01-01T00:00:00Z
	 */
	static long millis(long years, long months, long days, long hours, long minutes, long seconds, long millis,
			long sign, long offsetHours, long offsetMinutes) {
		long time = ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis;
		long carry = Math.floorDiv(time, DAY_MS);
		long gregorian = daysFromCivil(years, months, days, false) + carry;
		long day = years > 1582 || (years == 1582 && gregorian >= CUTOVER_DAY) ? gregorian
				: daysFromCivil(years, months, days, true) + carry;
		// the zone is GMT+ unless the sign is negative, as the oracle spells it
		return day * DAY_MS + (time - carry * DAY_MS)
				- (sign < 0 ? -1 : 1) * (offsetHours * 60 + offsetMinutes) * 60_000;
	}

	/**
	 * The oracle's {@code GregorianCalendar} fields of the instant in UTC: year of the
	 * era, month, day, hour, minute, second, millisecond and weekday (0 for a Sunday),
	 * the date the Julian calendar's before 1582-10-15.
	 * @param ms the milliseconds since 1970-01-01T00:00:00Z
	 * @return the fields
	 */
	static long[] fields(long ms) {
		long day = Math.floorDiv(ms, DAY_MS);
		long time = ms - day * DAY_MS;
		long[] date = civilFromDays(day, day < CUTOVER_DAY);
		long year = date[0] < 1 ? 1 - date[0] : date[0];
		return new long[] { year, date[1], date[2], time / 3_600_000, time / 60_000 % 60, time / 1000 % 60, time % 1000,
				Math.floorMod(day + 4, 7) };
	}

	/**
	 * How the oracle's print-method spells a {@code java.util.Date} inside its
	 * {@code #inst} literal: {@code yyyy-MM-dd'T'HH:mm:ss.SSS-00:00} in UTC.
	 * @param ms the milliseconds
	 * @return the timestamp text
	 */
	static String instantString(long ms) {
		long[] f = fields(ms);
		return padded(f[0], 4) + "-" + padded(f[1], 2) + "-" + padded(f[2], 2) + "T" + padded(f[3], 2) + ":"
				+ padded(f[4], 2) + ":" + padded(f[5], 2) + "." + padded(f[6], 3) + "-00:00";
	}

	/**
	 * The oracle's {@code java.util.Date.toString} in UTC:
	 * {@code Wed Jan 01 00:00:00 UTC 2020}.
	 * @param ms the milliseconds
	 * @return the text
	 */
	static String dateString(long ms) {
		long[] f = fields(ms);
		int weekday = (int) f[7];
		int month = (int) f[1] - 1;
		return WEEKDAYS.substring(weekday * 3, weekday * 3 + 3) + " " + MONTHS.substring(month * 3, month * 3 + 3) + " "
				+ padded(f[2], 2) + " " + padded(f[3], 2) + ":" + padded(f[4], 2) + ":" + padded(f[5], 2) + " UTC "
				+ f[0];
	}

	private static String padded(long n, int width) {
		String digits = Long.toString(n);
		return "0".repeat(Math.max(0, width - digits.length())) + digits;
	}

	/**
	 * The halves of the UUID the text spells, read like {@code UUID.fromString}: at most
	 * 36 characters, five groups between exactly four dashes, each read like
	 * {@code Long.parseLong} in radix 16 and masked to its width.
	 * @param s the text
	 * @return {@code {mostSignificantBits, leastSignificantBits}}
	 * @throws IllegalArgumentException in the oracle's words when the text is no UUID
	 */
	static long[] uuidOf(String s) {
		int n = s.length();
		if (n > 36) {
			throw new IllegalArgumentException("UUID string too large");
		}
		List<Integer> dashes = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			if (s.charAt(i) == '-') {
				dashes.add(i);
			}
		}
		if (dashes.size() != 4) {
			throw new IllegalArgumentException("Invalid UUID string: " + s);
		}
		long g1 = group(s, 0, dashes.get(0)) & 0xffff_ffffL;
		long g2 = group(s, dashes.get(0) + 1, dashes.get(1)) & 0xffffL;
		long g3 = group(s, dashes.get(1) + 1, dashes.get(2)) & 0xffffL;
		long g4 = group(s, dashes.get(2) + 1, dashes.get(3)) & 0xffffL;
		long g5 = group(s, dashes.get(3) + 1, n) & 0xffff_ffff_ffffL;
		return new long[] { g1 << 32 | g2 << 16 | g3, g4 << 48 | g5 };
	}

	/**
	 * One group read like {@code Long.parseLong} in radix 16: an optional {@code +}, then
	 * ASCII hex digits worth at most {@code 2^63 - 1}; anything else is its
	 * {@code NumberFormatException} naming where the read stopped.
	 */
	private static long group(String s, int start, int end) {
		if (start == end) {
			throw new IllegalArgumentException("For input string: \"\" under radix 16");
		}
		int i = start;
		if (s.charAt(i) == '+') {
			i++;
		}
		int stop = i == end ? i : -1;
		long value = 0;
		while (stop < 0 && i < end) {
			int digit = hexDigit(s.charAt(i));
			if (digit < 0 || value > Long.MAX_VALUE / 16) {
				stop = i;
			}
			else {
				value = value * 16 + digit;
				i++;
			}
		}
		if (stop >= 0) {
			throw new IllegalArgumentException(
					"Error at index " + (stop - start) + " in: \"" + s.substring(start, end) + "\"");
		}
		return value;
	}

	/** The value of an ASCII hex digit, or -1. */
	private static int hexDigit(char c) {
		if (c >= '0' && c <= '9') {
			return c - '0';
		}
		if (c >= 'a' && c <= 'f') {
			return c - 'a' + 10;
		}
		return c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
	}

	/**
	 * The oracle's {@code UUID.toString}: its 32 hex digits lowercase, in groups of 8, 4,
	 * 4, 4 and 12.
	 * @param msb the most significant half
	 * @param lsb the least significant half
	 * @return the text
	 */
	static String uuidString(long msb, long lsb) {
		return hex(msb >>> 32, 8) + "-" + hex(msb >>> 16, 4) + "-" + hex(msb, 4) + "-" + hex(lsb >>> 48, 4) + "-"
				+ hex(lsb, 12);
	}

	private static String hex(long n, int width) {
		StringBuilder out = new StringBuilder();
		for (int shift = 4 * (width - 1); shift >= 0; shift -= 4) {
			out.append("0123456789abcdef".charAt((int) (n >>> shift & 15)));
		}
		return out.toString();
	}

}
