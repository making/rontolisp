package am.ik.jdbc.spin.postgres;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A value the host hands over read as a Java value, and what a column's type is called.
 * The host has converted the value already, to one of the cases of its {@code db-value};
 * a getter that asks for the value's own class takes it as it is, and every other reading
 * goes through the text the server would write for it (PostgreSQL documentation, "Data
 * Types": each type's output), so a value reads the same whatever driver handed it over.
 */
final class Values {

	private Values() {
	}

	// What a column is.

	/** The server's name of a column's type, as far as the host tells them apart. */
	static String name(Postgres.DbDataType type) {
		return switch (type) {
			case Postgres.DbDataType.Boolean() -> "bool";
			case Postgres.DbDataType.Int8() -> "char";
			case Postgres.DbDataType.Int16() -> "int2";
			case Postgres.DbDataType.Int32() -> "int4";
			case Postgres.DbDataType.Int64() -> "int8";
			case Postgres.DbDataType.Floating32() -> "float4";
			case Postgres.DbDataType.Floating64() -> "float8";
			case Postgres.DbDataType.Str() -> "text";
			case Postgres.DbDataType.Binary() -> "bytea";
			case Postgres.DbDataType.Date() -> "date";
			case Postgres.DbDataType.Time() -> "time";
			case Postgres.DbDataType.Datetime() -> "timestamp";
			case Postgres.DbDataType.Timestamp() -> "timestamptz";
			case Postgres.DbDataType.Uuid() -> "uuid";
			case Postgres.DbDataType.Jsonb() -> "jsonb";
			case Postgres.DbDataType.Decimal() -> "numeric";
			case Postgres.DbDataType.RangeInt32() -> "int4range";
			case Postgres.DbDataType.RangeInt64() -> "int8range";
			case Postgres.DbDataType.RangeDecimal() -> "numrange";
			case Postgres.DbDataType.ArrayInt32() -> "_int4";
			case Postgres.DbDataType.ArrayInt64() -> "_int8";
			case Postgres.DbDataType.ArrayDecimal() -> "_numeric";
			case Postgres.DbDataType.ArrayStr() -> "_text";
			case Postgres.DbDataType.Interval() -> "interval";
			case Postgres.DbDataType.Other(String name) -> name;
		};
	}

	/**
	 * The constant of {@link Types} a column is read as: the one of what
	 * {@code getObject} answers.
	 */
	static int jdbc(Postgres.DbDataType type) {
		return switch (type) {
			case Postgres.DbDataType.Boolean() -> Types.BOOLEAN;
			case Postgres.DbDataType.Int8() -> Types.SMALLINT;
			case Postgres.DbDataType.Int16() -> Types.SMALLINT;
			case Postgres.DbDataType.Int32() -> Types.INTEGER;
			case Postgres.DbDataType.Int64() -> Types.BIGINT;
			case Postgres.DbDataType.Floating32() -> Types.REAL;
			case Postgres.DbDataType.Floating64() -> Types.DOUBLE;
			case Postgres.DbDataType.Str() -> Types.VARCHAR;
			case Postgres.DbDataType.Binary() -> Types.BINARY;
			case Postgres.DbDataType.Date() -> Types.DATE;
			case Postgres.DbDataType.Time() -> Types.TIME;
			case Postgres.DbDataType.Datetime() -> Types.TIMESTAMP;
			case Postgres.DbDataType.Timestamp() -> Types.TIMESTAMP;
			case Postgres.DbDataType.Decimal() -> Types.NUMERIC;
			case Postgres.DbDataType.Other(String name) -> name.equals("name") ? Types.VARCHAR
					: name.equals("char") ? Types.CHAR : name.equals("xml") ? Types.SQLXML : Types.OTHER;
			default -> Types.OTHER;
		};
	}

	/** The name of the class {@code getObject} answers for a column. */
	static String className(Postgres.DbDataType type) {
		return switch (type) {
			case Postgres.DbDataType.Boolean() -> "java.lang.Boolean";
			case Postgres.DbDataType.Int8() -> "java.lang.Integer";
			case Postgres.DbDataType.Int16() -> "java.lang.Integer";
			case Postgres.DbDataType.Int32() -> "java.lang.Integer";
			case Postgres.DbDataType.Int64() -> "java.lang.Long";
			case Postgres.DbDataType.Floating32() -> "java.lang.Float";
			case Postgres.DbDataType.Floating64() -> "java.lang.Double";
			case Postgres.DbDataType.Binary() -> "[B";
			case Postgres.DbDataType.Date() -> "java.sql.Date";
			case Postgres.DbDataType.Time() -> "java.sql.Time";
			case Postgres.DbDataType.Datetime() -> "java.sql.Timestamp";
			case Postgres.DbDataType.Timestamp() -> "java.sql.Timestamp";
			case Postgres.DbDataType.Uuid() -> "java.util.UUID";
			case Postgres.DbDataType.Decimal() -> "java.math.BigDecimal";
			default -> "java.lang.String";
		};
	}

	/**
	 * The constant of {@link Types} of a type the server names ({@code pg_type.typname}),
	 * as the socket driver tells it: for a parameter and a column of the catalog, whose
	 * type the host does not convert.
	 */
	static int jdbc(String type) {
		return switch (type) {
			case "bool" -> Types.BOOLEAN;
			case "bytea" -> Types.BINARY;
			case "char", "bpchar" -> Types.CHAR;
			case "name", "text", "varchar", "unknown" -> Types.VARCHAR;
			case "int8", "oid" -> Types.BIGINT;
			case "int2" -> Types.SMALLINT;
			case "int4" -> Types.INTEGER;
			case "float4" -> Types.REAL;
			case "float8" -> Types.DOUBLE;
			case "numeric" -> Types.NUMERIC;
			case "date" -> Types.DATE;
			case "time", "timetz" -> Types.TIME;
			case "timestamp", "timestamptz" -> Types.TIMESTAMP;
			case "xml" -> Types.SQLXML;
			default -> Types.OTHER;
		};
	}

	/** The name of the class a value of a type the server names is read as. */
	static String className(String type) {
		return switch (type) {
			case "bool" -> "java.lang.Boolean";
			case "bytea" -> "[B";
			case "int8", "oid" -> "java.lang.Long";
			case "int2", "int4" -> "java.lang.Integer";
			case "float4" -> "java.lang.Float";
			case "float8" -> "java.lang.Double";
			case "numeric" -> "java.math.BigDecimal";
			case "date" -> "java.sql.Date";
			case "time", "timetz" -> "java.sql.Time";
			case "timestamp", "timestamptz" -> "java.sql.Timestamp";
			case "uuid" -> "java.util.UUID";
			default -> "java.lang.String";
		};
	}

	// A value as the server's text.

	/**
	 * The text the server writes for a value.
	 * @param value a value that is not NULL
	 * @param type the name of its column's type, for a refusal to name
	 */
	static String text(Postgres.DbValue value, String type) throws SQLException {
		return switch (value) {
			case Postgres.DbValue.Boolean(boolean flag) -> flag ? "t" : "f";
			case Postgres.DbValue.Int8(byte number) -> Integer.toString(number);
			case Postgres.DbValue.Int16(short number) -> Integer.toString(number);
			case Postgres.DbValue.Int32(int number) -> Integer.toString(number);
			case Postgres.DbValue.Int64(long number) -> Long.toString(number);
			case Postgres.DbValue.Floating32(float number) -> real(Float.toString(number), 6);
			case Postgres.DbValue.Floating64(double number) -> real(Double.toString(number), 15);
			case Postgres.DbValue.Str(String text) -> text;
			case Postgres.DbValue.Binary(byte[] octets) -> hex(octets);
			case Postgres.DbValue.Date(Postgres.Ymd date) -> Moment.text(date(date));
			case Postgres.DbValue.Time(Postgres.Hms time) -> Moment.text(time(time));
			case Postgres.DbValue.Datetime(Postgres.YmdHms dateTime) -> Moment.text(dateTime(dateTime));
			case Postgres.DbValue.Timestamp(long seconds) ->
				Moment.text(Instant.ofEpochSecond(seconds).atOffset(ZoneOffset.UTC));
			case Postgres.DbValue.Uuid(String text) -> text;
			case Postgres.DbValue.Jsonb(byte[] octets) -> new String(octets, StandardCharsets.UTF_8);
			case Postgres.DbValue.Decimal(String text) -> text;
			case Postgres.DbValue.RangeInt32(Postgres.Range32 range) ->
				range(range.lower().map(bound -> Integer.toString(bound.value())),
						range.lower().map(Postgres.Bound32::kind),
						range.upper().map(bound -> Integer.toString(bound.value())),
						range.upper().map(Postgres.Bound32::kind));
			case Postgres.DbValue.RangeInt64(Postgres.Range64 range) -> range(
					range.lower().map(bound -> Long.toString(bound.value())), range.lower().map(Postgres.Bound64::kind),
					range.upper().map(bound -> Long.toString(bound.value())),
					range.upper().map(Postgres.Bound64::kind));
			case Postgres.DbValue.RangeDecimal(Postgres.RangeDecimals range) -> range(
					range.lower().map(Postgres.BoundDecimal::value), range.lower().map(Postgres.BoundDecimal::kind),
					range.upper().map(Postgres.BoundDecimal::value), range.upper().map(Postgres.BoundDecimal::kind));
			case Postgres.DbValue.ArrayInt32(List<Optional<Integer>> elements) -> array(elements, false);
			case Postgres.DbValue.ArrayInt64(List<Optional<Long>> elements) -> array(elements, false);
			case Postgres.DbValue.ArrayDecimal(List<Optional<String>> elements) -> array(elements, false);
			case Postgres.DbValue.ArrayStr(List<Optional<String>> elements) -> array(elements, true);
			case Postgres.DbValue.Interval(Postgres.Interval interval) -> interval(interval);
			case Postgres.DbValue.DbNull() -> throw new IllegalStateException("NULL has no text");
			// The host hands over the octets the server sent, in the binary form of
			// the
			// type: for these types that form is the text.
			case Postgres.DbValue.Unsupported(byte[] octets) -> {
				if (type.equals("json") || type.equals("xml") || type.equals("name") || type.equals("char")) {
					yield new String(octets, StandardCharsets.UTF_8);
				}
				throw new java.sql.SQLFeatureNotSupportedException("The host reads no value of the type " + type
						+ ": cast the column in the statement (x::text), or take the server's octets with getBytes",
						"0A000");
			}
		};
	}

	static LocalDate date(Postgres.Ymd date) {
		return LocalDate.of(date.year(), date.month(), date.day());
	}

	static LocalTime time(Postgres.Hms time) {
		return LocalTime.of(time.hour(), time.minute(), time.second(), time.nanosecond());
	}

	static LocalDateTime dateTime(Postgres.YmdHms dateTime) {
		return LocalDateTime.of(dateTime.year(), dateTime.month(), dateTime.day(), dateTime.hour(), dateTime.minute(),
				dateTime.second(), dateTime.nanosecond());
	}

	// A floating-point number as the server writes it, from the text Java writes for
	// it (both the shortest digits that read back as the number): the digits in place
	// where the first is no further than four places behind the point and fewer than
	// `places` before it, else one digit, the rest, e, a sign and two digits or more.
	private static String real(String java, int places) {
		if (java.equals("NaN") || java.endsWith("Infinity")) {
			return java;
		}
		boolean negative = java.startsWith("-");
		int e = java.indexOf('E');
		String mantissa = java.substring(negative ? 1 : 0, e < 0 ? java.length() : e);
		int exponent = e < 0 ? 0 : Integer.parseInt(java.substring(e + 1));
		int point = mantissa.indexOf('.');
		StringBuilder digits = new StringBuilder(mantissa.substring(0, point)).append(mantissa, point + 1,
				mantissa.length());
		// The number is 0.digits times ten to the power of `scale`.
		int scale = point + exponent;
		int leading = 0;
		while (leading < digits.length() - 1 && digits.charAt(leading) == '0') {
			leading++;
		}
		digits.delete(0, leading);
		scale -= leading;
		while (digits.length() > 1 && digits.charAt(digits.length() - 1) == '0') {
			digits.setLength(digits.length() - 1);
		}
		StringBuilder text = new StringBuilder(negative ? "-" : "");
		if (digits.length() == 1 && digits.charAt(0) == '0') {
			return text.append('0').toString();
		}
		if (scale - 1 < -4 || scale - 1 >= places) {
			text.append(digits.charAt(0));
			if (digits.length() > 1) {
				text.append('.').append(digits, 1, digits.length());
			}
			int power = scale - 1;
			text.append('e').append(power < 0 ? '-' : '+');
			return text.append(Math.abs(power) < 10 ? "0" : "").append(Math.abs(power)).toString();
		}
		if (scale <= 0) {
			text.append("0.");
			for (int i = scale; i < 0; i++) {
				text.append('0');
			}
			return text.append(digits).toString();
		}
		if (digits.length() <= scale) {
			text.append(digits);
			for (int i = digits.length(); i < scale; i++) {
				text.append('0');
			}
			return text.toString();
		}
		return text.append(digits, 0, scale).append('.').append(digits, scale, digits.length()).toString();
	}

	/** The hex form of a bytea. */
	static String hex(byte[] octets) {
		StringBuilder text = new StringBuilder(2 + octets.length * 2).append("\\x");
		for (byte octet : octets) {
			text.append(Character.forDigit((octet >> 4) & 15, 16)).append(Character.forDigit(octet & 15, 16));
		}
		return text.toString();
	}

	// A range: a bound the host hands over none of is open and infinite, which is also
	// what it hands over of an empty range.
	private static String range(Optional<String> lower, Optional<Postgres.RangeBoundKind> lowerKind,
			Optional<String> upper, Optional<Postgres.RangeBoundKind> upperKind) {
		return (lowerKind.filter(kind -> kind == Postgres.RangeBoundKind.INCLUSIVE).isPresent() ? "[" : "(")
				+ lower.orElse("") + "," + upper.orElse("")
				+ (upperKind.filter(kind -> kind == Postgres.RangeBoundKind.INCLUSIVE).isPresent() ? "]" : ")");
	}

	// An array of one dimension: an element in double quotes where it is empty, the
	// word NULL, or holds a brace, a comma, a quote, a backslash or white space.
	private static <T> String array(List<Optional<T>> elements, boolean texts) {
		StringBuilder text = new StringBuilder("{");
		for (int i = 0; i < elements.size(); i++) {
			if (i > 0) {
				text.append(',');
			}
			Optional<T> element = elements.get(i);
			if (element.isEmpty()) {
				text.append("NULL");
				continue;
			}
			String value = String.valueOf(element.get());
			if (!texts || !quoted(value)) {
				text.append(value);
				continue;
			}
			text.append('"');
			for (int at = 0; at < value.length(); at++) {
				char c = value.charAt(at);
				if (c == '"' || c == '\\') {
					text.append('\\');
				}
				text.append(c);
			}
			text.append('"');
		}
		return text.append('}').toString();
	}

	private static boolean quoted(String element) {
		if (element.isEmpty() || element.equalsIgnoreCase("NULL")) {
			return true;
		}
		for (int i = 0; i < element.length(); i++) {
			char c = element.charAt(i);
			if (c == '{' || c == '}' || c == ',' || c == '"' || c == '\\' || Character.isWhitespace(c)) {
				return true;
			}
		}
		return false;
	}

	// An interval in the server's own style: years and months of the months, the days,
	// then the time; a part is plural unless it is 1, and a part that is not negative
	// has a plus sign after a negative one.
	private static String interval(Postgres.Interval interval) {
		StringBuilder text = new StringBuilder();
		boolean[] negative = { false };
		part(text, interval.months() / 12, "year", negative);
		part(text, interval.months() % 12, "mon", negative);
		part(text, interval.days(), "day", negative);
		long micros = interval.micros();
		if (micros != 0 || text.length() == 0) {
			if (text.length() > 0) {
				text.append(' ');
			}
			text.append(micros < 0 ? "-" : negative[0] ? "+" : "");
			long left = Math.abs(micros);
			long hours = left / 3_600_000_000L;
			text.append(hours < 10 ? "0" : "").append(hours).append(':');
			two(text, (int) (left / 60_000_000L % 60)).append(':');
			two(text, (int) (left / 1_000_000L % 60));
			int fraction = (int) (left % 1_000_000L);
			if (fraction != 0) {
				text.append('.');
				for (int unit = 100_000; fraction != 0; unit /= 10) {
					text.append((char) ('0' + fraction / unit));
					fraction %= unit;
				}
			}
		}
		return text.toString();
	}

	private static void part(StringBuilder text, int value, String unit, boolean[] negative) {
		if (value == 0) {
			return;
		}
		if (text.length() > 0) {
			text.append(' ');
		}
		text.append(value > 0 && negative[0] ? "+" : "").append(value).append(' ').append(unit);
		if (value != 1) {
			text.append('s');
		}
		negative[0] = value < 0;
	}

	private static StringBuilder two(StringBuilder text, int value) {
		return text.append((char) ('0' + value / 10)).append((char) ('0' + value % 10));
	}

	// A value as a Java value.

	static boolean bool(Postgres.DbValue value, String type) throws SQLException {
		if (value instanceof Postgres.DbValue.Boolean(boolean flag)) {
			return flag;
		}
		String text = text(value, type);
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
	static long integer(Postgres.DbValue value, String type, long min, long max) throws SQLException {
		long number;
		String text;
		switch (value) {
			case Postgres.DbValue.Int8(byte exact) -> {
				number = exact;
				text = null;
			}
			case Postgres.DbValue.Int16(short exact) -> {
				number = exact;
				text = null;
			}
			case Postgres.DbValue.Int32(int exact) -> {
				number = exact;
				text = null;
			}
			case Postgres.DbValue.Int64(long exact) -> {
				number = exact;
				text = null;
			}
			default -> {
				text = text(value, type);
				number = integer(text);
			}
		}
		if (number < min || number > max) {
			throw new SQLException("The value is out of range: " + (text != null ? text : Long.toString(number)),
					"22003");
		}
		return number;
	}

	private static long integer(String text) throws SQLException {
		String trimmed = text.trim();
		try {
			return Long.parseLong(trimmed);
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
				return whole.longValue();
			}
			catch (NumberFormatException again) {
				throw Failures.data("The value is no number: " + text);
			}
		}
	}

	static double real(Postgres.DbValue value, String type) throws SQLException {
		switch (value) {
			case Postgres.DbValue.Floating64(double exact) -> {
				return exact;
			}
			case Postgres.DbValue.Floating32(float exact) -> {
				// The decimal digits of the float, not its binary neighbour as a double.
				return Double.parseDouble(Float.toString(exact));
			}
			case Postgres.DbValue.Int8(byte exact) -> {
				return exact;
			}
			case Postgres.DbValue.Int16(short exact) -> {
				return exact;
			}
			case Postgres.DbValue.Int32(int exact) -> {
				return exact;
			}
			default -> {
				String trimmed = text(value, type).trim();
				try {
					return Double.parseDouble(trimmed);
				}
				catch (NumberFormatException ex) {
					if (trimmed.equals("t") || trimmed.equals("f")) {
						return trimmed.equals("t") ? 1 : 0;
					}
					throw Failures.data("The value is no number: " + trimmed);
				}
			}
		}
	}

	static float single(Postgres.DbValue value, String type) throws SQLException {
		return value instanceof Postgres.DbValue.Floating32(float exact) ? exact : (float) real(value, type);
	}

	static BigDecimal decimal(Postgres.DbValue value, String type) throws SQLException {
		String text = text(value, type);
		try {
			return new BigDecimal(text.trim());
		}
		catch (NumberFormatException ex) {
			throw Failures.data("The value is no decimal number: " + text);
		}
	}

	/** The octets of a value: a bytea's, a jsonb's text, or what the server sent. */
	static byte[] octets(Postgres.DbValue value, String type) throws SQLException {
		return switch (value) {
			case Postgres.DbValue.Binary(byte[] octets) -> octets;
			case Postgres.DbValue.Jsonb(byte[] octets) -> octets;
			case Postgres.DbValue.Unsupported(byte[] octets) -> octets;
			default -> text(value, type).getBytes(StandardCharsets.UTF_8);
		};
	}

	// The date, the time or both of a value: the host's fields, or the server's text.
	private static Moment moment(Postgres.DbValue value, String type) throws SQLException {
		switch (value) {
			case Postgres.DbValue.Date(Postgres.Ymd date) -> {
				return new Moment(date(date), null, null);
			}
			case Postgres.DbValue.Time(Postgres.Hms time) -> {
				return new Moment(null, time(time), null);
			}
			case Postgres.DbValue.Datetime(Postgres.YmdHms dateTime) -> {
				LocalDateTime local = dateTime(dateTime);
				return new Moment(local.toLocalDate(), local.toLocalTime(), null);
			}
			case Postgres.DbValue.Timestamp(long seconds) -> {
				LocalDateTime utc = LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC);
				return new Moment(utc.toLocalDate(), utc.toLocalTime(), ZoneOffset.UTC);
			}
			default -> {
				String text = text(value, type);
				try {
					return Moment.parse(text.trim());
				}
				catch (DateTimeException ex) {
					throw Failures.data("The value is no date and no time: " + text, ex);
				}
			}
		}
	}

	static Date date(Postgres.DbValue value, String type) throws SQLException {
		try {
			return Date.valueOf(moment(value, type).local().toLocalDate());
		}
		catch (DateTimeException ex) {
			throw Failures.data("The value is no date: " + text(value, type), ex);
		}
	}

	static Time time(Postgres.DbValue value, String type) throws SQLException {
		try {
			Moment moment = moment(value, type);
			// A value with no date has a time.
			LocalTime time = moment.date != null ? moment.local().toLocalTime()
					: moment.offset == null ? Objects.requireNonNull(moment.time)
							: moment.timeOfDay()
								.withOffsetSameInstant(ZoneId.systemDefault().getRules().getOffset(Instant.EPOCH))
								.toLocalTime();
			// Time.valueOf keeps the seconds; the milliseconds are added to them.
			return new Time(Time.valueOf(time.withNano(0)).getTime() + time.getNano() / 1_000_000);
		}
		catch (DateTimeException ex) {
			throw Failures.data("The value is no time: " + text(value, type), ex);
		}
	}

	static Timestamp timestamp(Postgres.DbValue value, String type) throws SQLException {
		try {
			return Timestamp.valueOf(moment(value, type).local());
		}
		catch (DateTimeException ex) {
			throw Failures.data("The value is no timestamp: " + text(value, type), ex);
		}
	}

	// The same three on the wall of a zone that is not where the program runs: a value
	// with no offset is that wall's, and the date or the time of one with an offset is
	// read there.

	static Date date(Postgres.DbValue value, String type, ZoneId zone) throws SQLException {
		try {
			Moment moment = moment(value, type);
			LocalDate date = moment.offset == null ? moment.date
					: moment.instant().atZoneSameInstant(zone).toLocalDate();
			if (date == null) {
				throw new DateTimeException("a time alone has no date");
			}
			return new Date(date.atStartOfDay(zone).toInstant().toEpochMilli());
		}
		catch (DateTimeException ex) {
			throw Failures.data("The value is no date: " + text(value, type), ex);
		}
	}

	static Time time(Postgres.DbValue value, String type, ZoneId zone) throws SQLException {
		try {
			Moment moment = moment(value, type);
			if (moment.offset != null && moment.date == null) {
				return time(value, type);
			}
			LocalTime time = moment.offset == null ? moment.time
					: moment.instant().atZoneSameInstant(zone).toLocalTime();
			if (time == null) {
				throw new DateTimeException("a date alone has no time");
			}
			return new Time(LocalDate.EPOCH.atTime(time).atZone(zone).toInstant().toEpochMilli());
		}
		catch (DateTimeException ex) {
			throw Failures.data("The value is no time: " + text(value, type), ex);
		}
	}

	static Timestamp timestamp(Postgres.DbValue value, String type, ZoneId zone) throws SQLException {
		try {
			Moment moment = moment(value, type);
			return Timestamp
				.from(moment.offset != null ? moment.instant().toInstant() : moment.local().atZone(zone).toInstant());
		}
		catch (DateTimeException ex) {
			throw Failures.data("The value is no timestamp: " + text(value, type), ex);
		}
	}

	/** A value as the class {@code getObject} answers its case as. */
	static Object natural(Postgres.DbValue value, String type) throws SQLException {
		return switch (value) {
			case Postgres.DbValue.Boolean(boolean flag) -> flag;
			case Postgres.DbValue.Int8(byte number) -> (int) number;
			case Postgres.DbValue.Int16(short number) -> (int) number;
			case Postgres.DbValue.Int32(int number) -> number;
			case Postgres.DbValue.Int64(long number) -> number;
			case Postgres.DbValue.Floating32(float number) -> number;
			case Postgres.DbValue.Floating64(double number) -> number;
			case Postgres.DbValue.Binary(byte[] octets) -> octets;
			case Postgres.DbValue.Date(Postgres.Ymd date) -> date(value, type);
			case Postgres.DbValue.Time(Postgres.Hms time) -> time(value, type);
			case Postgres.DbValue.Datetime(Postgres.YmdHms dateTime) -> timestamp(value, type);
			case Postgres.DbValue.Timestamp(long seconds) -> timestamp(value, type);
			case Postgres.DbValue.Uuid(String text) -> as(UUID.class, value, type);
			case Postgres.DbValue.Decimal(String text) -> decimal(value, type);
			default -> text(value, type);
		};
	}

	/**
	 * A value as the class asked for.
	 * @param value a value that is not NULL
	 */
	static Object as(Class<?> asked, Postgres.DbValue value, String type) throws SQLException {
		try {
			if (asked == String.class) {
				return text(value, type);
			}
			if (asked == Integer.class) {
				return (int) integer(value, type, Integer.MIN_VALUE, Integer.MAX_VALUE);
			}
			if (asked == Long.class) {
				return integer(value, type, Long.MIN_VALUE, Long.MAX_VALUE);
			}
			if (asked == Short.class) {
				return (short) integer(value, type, Short.MIN_VALUE, Short.MAX_VALUE);
			}
			if (asked == Byte.class) {
				return (byte) integer(value, type, Byte.MIN_VALUE, Byte.MAX_VALUE);
			}
			if (asked == Boolean.class) {
				return bool(value, type);
			}
			if (asked == Double.class) {
				return real(value, type);
			}
			if (asked == Float.class) {
				return single(value, type);
			}
			if (asked == BigDecimal.class) {
				return decimal(value, type);
			}
			if (asked == BigInteger.class) {
				return decimal(value, type).toBigInteger();
			}
			if (asked == UUID.class) {
				return UUID.fromString(text(value, type));
			}
			if (asked == Date.class) {
				return date(value, type);
			}
			if (asked == Time.class) {
				return time(value, type);
			}
			if (asked == Timestamp.class || asked == java.util.Date.class) {
				return timestamp(value, type);
			}
			if (asked == LocalDate.class) {
				return moment(value, type).local().toLocalDate();
			}
			if (asked == LocalTime.class) {
				Moment moment = moment(value, type);
				return moment.date != null ? moment.local().toLocalTime() : Objects.requireNonNull(moment.time);
			}
			if (asked == LocalDateTime.class) {
				return moment(value, type).local();
			}
			if (asked == OffsetDateTime.class) {
				return moment(value, type).instant();
			}
			if (asked == ZonedDateTime.class) {
				return moment(value, type).instant().toZonedDateTime();
			}
			if (asked == Instant.class) {
				return moment(value, type).instant().toInstant();
			}
			if (asked == OffsetTime.class) {
				return moment(value, type).timeOfDay();
			}
		}
		catch (DateTimeException | IllegalArgumentException ex) {
			throw Failures.data("The value is no " + asked.getSimpleName() + ": " + text(value, type), ex);
		}
		throw Failures.unsupported("A value of the type " + type + " read as " + asked.getName());
	}

}
