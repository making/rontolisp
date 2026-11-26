package am.ik.jdbc.postgresql;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * The server's text of a value read as a Java value, and a Java value written as the text
 * the server reads (PostgreSQL documentation, "Data Types": each type's input and
 * output).
 */
final class Values {

	private Values() {
	}

	// Reading.

	static boolean bool(String text) throws SQLException {
		switch (text.trim().toLowerCase()) {
			case "t", "true", "1", "y", "yes", "on" -> {
				return true;
			}
			case "f", "false", "0", "n", "no", "off" -> {
				return false;
			}
			default -> {
				try {
					return new BigDecimal(text.trim()).signum() != 0;
				}
				catch (NumberFormatException ex) {
					throw Failures.data("The value is no boolean: " + text);
				}
			}
		}
	}

	/** A whole number between two bounds; a fraction is cut off, as a cast cuts it. */
	static long integer(String text, long min, long max) throws SQLException {
		String trimmed = text.trim();
		long value;
		try {
			value = Long.parseLong(trimmed);
		}
		catch (NumberFormatException ex) {
			if (trimmed.equals("t") || trimmed.equals("f")) {
				return trimmed.equals("t") ? 1 : 0;
			}
			try {
				BigInteger whole = new BigDecimal(trimmed).toBigInteger();
				if (whole.bitLength() > 63) {
					throw new SQLException("The value is out of range: " + text, "22003");
				}
				value = whole.longValue();
			}
			catch (NumberFormatException again) {
				throw Failures.data("The value is no number: " + text);
			}
		}
		if (value < min || value > max) {
			throw new SQLException("The value is out of range: " + text, "22003");
		}
		return value;
	}

	static double real(String text) throws SQLException {
		String trimmed = text.trim();
		try {
			return Double.parseDouble(trimmed);
		}
		catch (NumberFormatException ex) {
			if (trimmed.equals("t") || trimmed.equals("f")) {
				return trimmed.equals("t") ? 1 : 0;
			}
			throw Failures.data("The value is no number: " + text);
		}
	}

	static BigDecimal decimal(String text) throws SQLException {
		try {
			return new BigDecimal(text.trim());
		}
		catch (NumberFormatException ex) {
			throw Failures.data("The value is no decimal number: " + text);
		}
	}

	/** The octets of a bytea in its hex form, {@code \x} and two digits an octet. */
	static byte[] octets(byte[] row, int start, int length) throws SQLException {
		byte[] octets;
		if (length >= 2 && row[start] == '\\' && row[start + 1] == 'x') {
			octets = new byte[(length - 2) / 2];
			for (int i = 0; i < octets.length; i++) {
				int high = Character.digit(row[start + 2 + 2 * i], 16);
				int low = Character.digit(row[start + 3 + 2 * i], 16);
				if (high < 0 || low < 0) {
					throw Failures.data("The value is no bytea in the hex form");
				}
				octets[i] = (byte) (high * 16 + low);
			}
			return octets;
		}
		// The escape form: an octet as it is, a backslash doubled or before three octal
		// digits.
		octets = new byte[length];
		int count = 0;
		for (int i = start; i < start + length; i++) {
			int octet = row[i];
			if (octet == '\\' && i + 1 < start + length) {
				if (row[i + 1] == '\\') {
					i++;
				}
				else if (i + 3 < start + length) {
					octet = (row[i + 1] - '0') * 64 + (row[i + 2] - '0') * 8 + (row[i + 3] - '0');
					i += 3;
				}
			}
			octets[count++] = (byte) octet;
		}
		byte[] exact = new byte[count];
		System.arraycopy(octets, 0, exact, 0, count);
		return exact;
	}

	static Moment moment(String text) throws SQLException {
		try {
			return Moment.parse(text.trim());
		}
		catch (DateTimeException ex) {
			throw Failures.data("The value is no date and no time: " + text, ex);
		}
	}

	static Date date(String text) throws SQLException {
		try {
			return Date.valueOf(moment(text).local().toLocalDate());
		}
		catch (DateTimeException ex) {
			throw Failures.data("The value is no date: " + text, ex);
		}
	}

	static Time time(String text) throws SQLException {
		try {
			Moment moment = moment(text);
			// A text with no date has a time.
			LocalTime time = moment.date != null ? moment.local().toLocalTime()
					: moment.offset == null ? Objects.requireNonNull(moment.time)
							: moment.timeOfDay()
								.withOffsetSameInstant(ZoneId.systemDefault().getRules().getOffset(Instant.EPOCH))
								.toLocalTime();
			// Time.valueOf keeps the seconds; the milliseconds are added to them.
			return new Time(Time.valueOf(time.withNano(0)).getTime() + time.getNano() / 1_000_000);
		}
		catch (DateTimeException ex) {
			throw Failures.data("The value is no time: " + text, ex);
		}
	}

	static Timestamp timestamp(String text) throws SQLException {
		try {
			return Timestamp.valueOf(moment(text).local());
		}
		catch (DateTimeException ex) {
			throw Failures.data("The value is no timestamp: " + text, ex);
		}
	}

	// The same three on the wall of a zone that is not where the program runs: a value
	// with no offset is that wall's, and the date or the time of one with an offset is
	// read there.

	static Date date(String text, ZoneId zone) throws SQLException {
		try {
			Moment moment = moment(text);
			LocalDate date = moment.offset == null ? moment.date
					: moment.instant().atZoneSameInstant(zone).toLocalDate();
			if (date == null) {
				throw new DateTimeException("a time alone has no date");
			}
			return new Date(date.atStartOfDay(zone).toInstant().toEpochMilli());
		}
		catch (DateTimeException ex) {
			throw Failures.data("The value is no date: " + text, ex);
		}
	}

	static Time time(String text, ZoneId zone) throws SQLException {
		try {
			Moment moment = moment(text);
			if (moment.offset != null && moment.date == null) {
				return time(text);
			}
			LocalTime time = moment.offset == null ? moment.time
					: moment.instant().atZoneSameInstant(zone).toLocalTime();
			if (time == null) {
				throw new DateTimeException("a date alone has no time");
			}
			return new Time(LocalDate.EPOCH.atTime(time).atZone(zone).toInstant().toEpochMilli());
		}
		catch (DateTimeException ex) {
			throw Failures.data("The value is no time: " + text, ex);
		}
	}

	static Timestamp timestamp(String text, ZoneId zone) throws SQLException {
		try {
			Moment moment = moment(text);
			return Timestamp
				.from(moment.offset != null ? moment.instant().toInstant() : moment.local().atZone(zone).toInstant());
		}
		catch (DateTimeException ex) {
			throw Failures.data("The value is no timestamp: " + text, ex);
		}
	}

	/**
	 * A value as the class asked for.
	 * @param text the server's text, not {@code null}
	 * @param type the column's type
	 */
	static Object as(Class<?> asked, String text, int type) throws SQLException {
		try {
			if (asked == String.class) {
				return text;
			}
			if (asked == Integer.class) {
				return (int) integer(text, Integer.MIN_VALUE, Integer.MAX_VALUE);
			}
			if (asked == Long.class) {
				return integer(text, Long.MIN_VALUE, Long.MAX_VALUE);
			}
			if (asked == Short.class) {
				return (short) integer(text, Short.MIN_VALUE, Short.MAX_VALUE);
			}
			if (asked == Byte.class) {
				return (byte) integer(text, Byte.MIN_VALUE, Byte.MAX_VALUE);
			}
			if (asked == Boolean.class) {
				return bool(text);
			}
			if (asked == Double.class) {
				return real(text);
			}
			if (asked == Float.class) {
				return (float) real(text);
			}
			if (asked == BigDecimal.class) {
				return decimal(text);
			}
			if (asked == BigInteger.class) {
				return decimal(text).toBigInteger();
			}
			if (asked == UUID.class) {
				return UUID.fromString(text);
			}
			if (asked == Date.class) {
				return date(text);
			}
			if (asked == Time.class) {
				return time(text);
			}
			if (asked == Timestamp.class || asked == java.util.Date.class) {
				return timestamp(text);
			}
			if (asked == LocalDate.class) {
				return moment(text).local().toLocalDate();
			}
			if (asked == LocalTime.class) {
				Moment moment = moment(text);
				return moment.date != null ? moment.local().toLocalTime() : Objects.requireNonNull(moment.time);
			}
			if (asked == LocalDateTime.class) {
				return moment(text).local();
			}
			if (asked == OffsetDateTime.class) {
				return moment(text).instant();
			}
			if (asked == ZonedDateTime.class) {
				return moment(text).instant().toZonedDateTime();
			}
			if (asked == Instant.class) {
				return moment(text).instant().toInstant();
			}
			if (asked == OffsetTime.class) {
				return moment(text).timeOfDay();
			}
		}
		catch (DateTimeException | IllegalArgumentException ex) {
			throw Failures.data("The value is no " + asked.getSimpleName() + ": " + text, ex);
		}
		throw Failures.unsupported("A value of the type " + TypeId.name(type) + " read as " + asked.getName());
	}

	/** The class {@code getObject} answers a type as. */
	static Class<?> natural(int type) {
		return switch (type) {
			case TypeId.BOOL -> Boolean.class;
			case TypeId.INT2, TypeId.INT4 -> Integer.class;
			case TypeId.INT8, TypeId.OID -> Long.class;
			case TypeId.FLOAT4 -> Float.class;
			case TypeId.FLOAT8 -> Double.class;
			case TypeId.NUMERIC -> BigDecimal.class;
			case TypeId.DATE -> Date.class;
			case TypeId.TIME, TypeId.TIMETZ -> Time.class;
			case TypeId.TIMESTAMP, TypeId.TIMESTAMPTZ -> Timestamp.class;
			case TypeId.UUID -> UUID.class;
			default -> String.class;
		};
	}

	// Writing.

	/** The hex form of a bytea. */
	static String hex(byte[] octets) {
		StringBuilder text = new StringBuilder(2 + octets.length * 2).append("\\x");
		for (byte octet : octets) {
			text.append(Character.forDigit((octet >> 4) & 15, 16)).append(Character.forDigit(octet & 15, 16));
		}
		return text.toString();
	}

	/**
	 * A timestamp with the offset of where the program runs, so that the server reads the
	 * instant into a column with a time zone and the wall's fields into one without.
	 */
	static String text(Timestamp timestamp) {
		LocalDateTime local = timestamp.toLocalDateTime();
		ZoneOffset offset = ZoneId.systemDefault().getRules().getOffset(timestamp.toInstant());
		return Moment.text(local.atOffset(offset));
	}

	/** A timestamp with the offset of a zone that is not where the program runs. */
	static String text(Timestamp timestamp, ZoneId zone) {
		return Moment.text(timestamp.toInstant().atZone(zone).toOffsetDateTime());
	}

	/**
	 * The type a value is sent as, {@link TypeId#UNSPECIFIED} for one the server is left
	 * to tell from where the parameter stands.
	 * @return -1 for a value of a class the driver does not send
	 */
	static int type(Object value) {
		if (value instanceof String || value instanceof Character) {
			return TypeId.VARCHAR;
		}
		if (value instanceof Integer) {
			return TypeId.INT4;
		}
		if (value instanceof Long) {
			return TypeId.INT8;
		}
		if (value instanceof Short || value instanceof Byte) {
			return TypeId.INT2;
		}
		if (value instanceof Boolean) {
			return TypeId.BOOL;
		}
		if (value instanceof Double) {
			return TypeId.FLOAT8;
		}
		if (value instanceof Float) {
			return TypeId.FLOAT4;
		}
		if (value instanceof BigDecimal || value instanceof BigInteger) {
			return TypeId.NUMERIC;
		}
		if (value instanceof byte[]) {
			return TypeId.BYTEA;
		}
		if (value instanceof UUID) {
			return TypeId.UUID;
		}
		if (value instanceof Date || value instanceof LocalDate) {
			return TypeId.DATE;
		}
		if (value instanceof Time || value instanceof LocalTime) {
			return TypeId.TIME;
		}
		if (value instanceof OffsetTime) {
			return TypeId.TIMETZ;
		}
		if (value instanceof LocalDateTime) {
			return TypeId.TIMESTAMP;
		}
		if (value instanceof OffsetDateTime || value instanceof ZonedDateTime || value instanceof Instant) {
			return TypeId.TIMESTAMPTZ;
		}
		if (value instanceof java.util.Date) {
			return TypeId.UNSPECIFIED;
		}
		return -1;
	}

	/**
	 * A value as the text the server reads; the value is of a class {@link #type} knows.
	 */
	static String text(Object value) {
		if (value instanceof byte[] octets) {
			return hex(octets);
		}
		if (value instanceof BigDecimal decimal) {
			return decimal.toPlainString();
		}
		if (value instanceof Date date) {
			return Moment.text(date.toLocalDate());
		}
		if (value instanceof Time time) {
			return Moment.text(time.toLocalTime().plusNanos(Math.floorMod(time.getTime(), 1000L) * 1_000_000L));
		}
		if (value instanceof Timestamp timestamp) {
			return text(timestamp);
		}
		if (value instanceof java.util.Date date) {
			return text(new Timestamp(date.getTime()));
		}
		if (value instanceof LocalDate date) {
			return Moment.text(date);
		}
		if (value instanceof LocalTime time) {
			return Moment.text(time);
		}
		if (value instanceof LocalDateTime dateTime) {
			return Moment.text(dateTime);
		}
		if (value instanceof OffsetDateTime dateTime) {
			return Moment.text(dateTime);
		}
		if (value instanceof ZonedDateTime dateTime) {
			return Moment.text(dateTime.toOffsetDateTime());
		}
		if (value instanceof Instant instant) {
			return Moment.text(instant.atOffset(ZoneOffset.UTC));
		}
		if (value instanceof OffsetTime time) {
			return Moment.text(time);
		}
		return value.toString();
	}

}
