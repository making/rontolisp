package am.ik.rontolisp.clojure;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.testsupport.CliStack;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The oracle's two default data readers against the JDK classes the oracle calls, over
 * seeded random inputs: the read-time half ({@link ClojureDefaultReaders}, the source
 * reader's {@code #inst} and {@code #uuid}) and the run-time half ({@code clojure.lisp},
 * {@code read-string}'s and {@code clojure.edn}'s), the latter on the interpreter (it is
 * one Common Lisp definition every backend compiles; {@code clojure-spec.yaml}'s
 * {@code inst-*} and {@code uuid-*} cases pin that the backends agree). The oracle reads
 * a timestamp through {@code clojure.instant}'s pattern, its {@code validated} checks and
 * a lenient {@code GregorianCalendar} at the offset's zone, prints a {@code Date} through
 * a UTC {@code SimpleDateFormat}, and reads a UUID through {@code UUID.fromString}.
 */
class ClojureDefaultReadersTest {

	/**
	 * The oracle's timestamp pattern: what clj 1.12.6 matches (measured 2026-10-08),
	 * including the backtracking that reads {@code 2020-05:30} as an offset.
	 */
	private static final Pattern TIMESTAMP = Pattern.compile(
			"(\\d\\d\\d\\d)(?:-(\\d\\d)(?:-(\\d\\d)(?:[T](\\d\\d)(?::(\\d\\d)(?::(\\d\\d)(?:[.](\\d+))?)?)?)?)?)?(?:[Z]|([-+])(\\d\\d):(\\d\\d))?");

	private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

	@Test
	void theReadTimeHalfMatchesTheOraclesPattern() {
		Random random = new Random(41);
		for (int i = 0; i < 20_000; i++) {
			String text = timestampish(random);
			long[] expected = oracleFields(text);
			long[] actual = ClojureDefaultReaders.parse(text);
			assertThat(actual).as(text).isEqualTo(expected);
		}
	}

	@Test
	void theReadTimeHalfComputesTheOraclesCalendar() {
		Random random = new Random(43);
		for (int i = 0; i < 20_000; i++) {
			long[] f = validFields(random);
			long expected = oracleMillis(f);
			assertThat(ClojureDefaultReaders.millis(f[0], f[1], f[2], f[3], f[4], f[5], f[6] / 1_000_000, f[7], f[8],
					f[9]))
				.as(java.util.Arrays.toString(f))
				.isEqualTo(expected);
			assertThat(ClojureDefaultReaders.instantString(expected)).isEqualTo(oraclePrinted(expected));
			assertThat(ClojureDefaultReaders.dateString(expected)).isEqualTo(oracleToString(expected));
		}
	}

	@Test
	void theReadTimeHalfValidatesInTheOraclesOrder() {
		assertThat(ClojureDefaultReaders.validate(new long[] { 2021, 2, 29, 0, 0, 0, 0, 0, 0, 0 }))
			.isEqualTo("(<= 1 days (days-in-month months (leap-year? years)))");
		assertThat(ClojureDefaultReaders.validate(new long[] { 2020, 13, 0, 24, 60, 61, -1, 2, 24, 60 }))
			.isEqualTo("(<= 1 months 12)");
		assertThat(ClojureDefaultReaders.validate(new long[] { 2020, 12, 1, 23, 59, 60, 0, 1, 23, 60 }))
			.isEqualTo("(<= 0 offset-minutes 59)");
		assertThat(ClojureDefaultReaders.validate(new long[] { 1600, 2, 29, 23, 59, 60, 999_999_999, -1, 23, 59 }))
			.isNull();
		assertThat(ClojureDefaultReaders.validate(new long[] { 1900, 2, 29, 0, 0, 0, 0, 0, 0, 0 })).isNotNull();
	}

	@Test
	void theReadTimeHalfReadsUuidsLikeFromString() {
		Random random = new Random(47);
		for (int i = 0; i < 20_000; i++) {
			String text = uuidish(random);
			String expected;
			try {
				UUID uuid = UUID.fromString(text);
				expected = uuid.getMostSignificantBits() + " " + uuid.getLeastSignificantBits() + " " + uuid;
			}
			catch (IllegalArgumentException ex) {
				expected = ex.getMessage();
			}
			String actual;
			try {
				long[] halves = ClojureDefaultReaders.uuidOf(text);
				actual = halves[0] + " " + halves[1] + " " + ClojureDefaultReaders.uuidString(halves[0], halves[1]);
			}
			catch (IllegalArgumentException ex) {
				actual = ex.getMessage();
			}
			assertThat(actual).as(text).isEqualTo(expected);
		}
	}

	@Test
	void theRunTimeHalfReadsPrintsAndValidatesLikeTheOracle() throws Exception {
		Random random = new Random(53);
		StringBuilder program = new StringBuilder(
				"(defn probe [s] (try (let [d (read-string (str \"#inst \\\"\" s \"\\\"\"))] (str (pr-str d) \" \" (inst-ms d) \" \" d)) (catch Exception e (ex-message e))))\n");
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 1500; i++) {
			String text = i % 3 == 0 ? timestampish(random) : printedTimestamp(validFields(random), random);
			program.append("(println (probe ").append(literal(text)).append("))\n");
			expected.add(oracleRead(text));
		}
		assertThat(interpret(program.toString()).lines().toList()).containsExactlyElementsOf(expected);
	}

	@Test
	void theRunTimeHalfReadsUuidsLikeFromString() throws Exception {
		Random random = new Random(59);
		StringBuilder program = new StringBuilder(
				"(defn probe [s] (try (let [u (read-string (str \"#uuid \\\"\" s \"\\\"\"))] (str (pr-str u) \" \" (.getMostSignificantBits u) \" \" (.getLeastSignificantBits u) \" \" (= u (parse-uuid s)))) (catch Exception e (str (.getName (class e)) \": \" (ex-message e) \" \" (parse-uuid s)))))\n");
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 1500; i++) {
			String text = uuidish(random);
			program.append("(println (probe ").append(literal(text)).append("))\n");
			try {
				UUID uuid = UUID.fromString(text);
				expected.add("#uuid \"" + uuid + "\" " + uuid.getMostSignificantBits() + " "
						+ uuid.getLeastSignificantBits() + " true");
			}
			catch (IllegalArgumentException ex) {
				expected.add(ex.getClass().getName() + ": " + ex.getMessage() + " ");
			}
		}
		assertThat(interpret(program.toString()).lines().toList()).containsExactlyElementsOf(expected);
	}

	/** A string near the timestamp grammar: valid shapes and their likely breakages. */
	private static String timestampish(Random random) {
		StringBuilder s = new StringBuilder();
		s.append(digits(random, random.nextInt(12) == 0 ? 3 + random.nextInt(3) : 4));
		String[] parts = { "-", "-", "T", ":", ":" };
		for (int k = 0; k < parts.length && random.nextInt(6) != 0; k++) {
			s.append(random.nextInt(25) == 0 ? "/-t: ".charAt(random.nextInt(5)) : parts[k]);
			s.append(digits(random, random.nextInt(15) == 0 ? 1 + random.nextInt(3) : 2));
		}
		if (random.nextInt(3) == 0) {
			s.append('.').append(digits(random, random.nextInt(14)));
		}
		switch (random.nextInt(6)) {
			case 0 -> s.append('Z');
			case 1, 2 -> s.append(random.nextBoolean() ? '+' : '-')
				.append(digits(random, 2))
				.append(random.nextInt(10) == 0 ? "" : ":")
				.append(digits(random, random.nextInt(10) == 0 ? 1 : 2));
			case 3 -> {
				if (random.nextInt(4) == 0) {
					s.append("z ".charAt(random.nextInt(2)));
				}
			}
			default -> {
			}
		}
		return s.toString();
	}

	private static String digits(Random random, int n) {
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < n; i++) {
			s.append((char) ('0' + random.nextInt(10)));
		}
		return s.toString();
	}

	/** Ten fields validated would pass, with years, months and offsets at their edges. */
	private static long[] validFields(Random random) {
		long year = switch (random.nextInt(6)) {
			case 0 -> 1582;
			case 1 -> random.nextInt(4) == 0 ? random.nextInt(2) : 1580 + random.nextInt(4);
			case 2 -> random.nextInt(1000);
			case 3 -> 9990 + random.nextInt(10);
			default -> random.nextInt(10_000);
		};
		long month = 1 + random.nextInt(12);
		long leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) ? 1 : 0;
		long[] days = { 31, 28 + leap, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31 };
		long day = year == 1582 && month == 10 && random.nextBoolean() ? 1 + random.nextInt(20)
				: 1 + random.nextInt((int) days[(int) month - 1]);
		long hour = random.nextInt(24);
		long minute = random.nextInt(5) == 0 ? 59 : random.nextInt(60);
		long second = minute == 59 && random.nextInt(4) == 0 ? 60 : random.nextInt(60);
		long nanos = random.nextInt(3) == 0 ? 0 : random.nextInt(1_000_000_000);
		long sign = random.nextInt(3) - 1;
		long oh = sign == 0 && random.nextBoolean() ? 0 : random.nextInt(24);
		long om = sign == 0 && random.nextBoolean() ? 0 : random.nextInt(60);
		return new long[] { year, month, day, hour, minute, second, nanos, sign, oh, om };
	}

	/**
	 * The fields spelled as a timestamp, with an optional part left off here and there.
	 */
	private static String printedTimestamp(long[] f, Random random) {
		StringBuilder s = new StringBuilder(
				String.format("%04d-%02d-%02dT%02d:%02d:%02d", f[0], f[1], f[2], f[3], f[4], f[5]));
		if (f[6] != 0) {
			s.append('.').append(String.format("%09d", f[6]), 0, 1 + random.nextInt(9));
		}
		if (f[7] == 0) {
			s.append(random.nextBoolean() ? "Z" : "");
		}
		else {
			s.append(f[7] < 0 ? '-' : '+').append(String.format("%02d:%02d", f[8], f[9]));
		}
		return s.toString();
	}

	/** The fields the oracle's parse-timestamp passes on, or null where it refuses. */
	private static long @Nullable [] oracleFields(String text) {
		Matcher m = TIMESTAMP.matcher(text);
		if (!m.matches()) {
			return null;
		}
		String fraction = m.group(7);
		long nanos = 0;
		if (fraction != null) {
			String nine = (fraction + "000000000").substring(0, 9);
			nanos = Long.parseLong(nine);
		}
		return new long[] { Long.parseLong(m.group(1)), group(m, 2, 1), group(m, 3, 1), group(m, 4, 0), group(m, 5, 0),
				group(m, 6, 0), nanos, "-".equals(m.group(8)) ? -1 : "+".equals(m.group(8)) ? 1 : 0, group(m, 9, 0),
				group(m, 10, 0) };
	}

	private static long group(Matcher m, int i, long dflt) {
		return m.group(i) == null ? dflt : Long.parseLong(m.group(i));
	}

	/** What the oracle's read-instant-date answers for the fields: construct-date's. */
	private static long oracleMillis(long[] f) {
		GregorianCalendar calendar = new GregorianCalendar((int) f[0], (int) f[1] - 1, (int) f[2], (int) f[3],
				(int) f[4], (int) f[5]);
		calendar.set(Calendar.MILLISECOND, (int) (f[6] / 1_000_000));
		calendar.setTimeZone(
				TimeZone.getTimeZone(String.format("GMT%s%02d:%02d", f[7] < 0 ? "-" : "+", (int) f[8], (int) f[9])));
		return calendar.getTimeInMillis();
	}

	/** The oracle's #inst text of a Date: its print-date's UTC format. */
	private static String oraclePrinted(long ms) {
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS-00:00");
		format.setTimeZone(UTC);
		return format.format(new Date(ms));
	}

	/** The oracle's Date.toString with its default time zone UTC. */
	private static String oracleToString(long ms) {
		SimpleDateFormat format = new SimpleDateFormat("EEE MMM dd HH:mm:ss zzz y", Locale.US);
		format.setTimeZone(UTC);
		return format.format(new Date(ms));
	}

	/** The probe's line for the text: the read Date, or the oracle's refusal. */
	private static String oracleRead(String text) {
		long[] f = oracleFields(text);
		if (f == null) {
			return "Unrecognized date/time syntax: " + text;
		}
		String failed = ClojureDefaultReaders.validate(f);
		if (failed != null) {
			return "failed: " + failed;
		}
		long ms = oracleMillis(f);
		return "#inst \"" + oraclePrinted(ms) + "\" " + ms + " " + oracleToString(ms);
	}

	/** A string near the UUID grammar: groups of hex digits, signs, stray characters. */
	private static String uuidish(Random random) {
		int groups = random.nextInt(10) == 0 ? 4 + random.nextInt(3) * (random.nextBoolean() ? 1 : -1) : 5;
		StringBuilder s = new StringBuilder();
		int[] widths = { 8, 4, 4, 4, 12 };
		for (int g = 0; g < groups; g++) {
			if (g > 0) {
				s.append('-');
			}
			if (random.nextInt(30) == 0) {
				s.append('+');
			}
			int width = random.nextInt(4) == 0 ? random.nextInt(18) : widths[Math.min(g, 4)];
			for (int i = 0; i < width; i++) {
				s.append(random.nextInt(40) == 0 ? "gG +x_".charAt(random.nextInt(6))
						: "0123456789abcdefABCDEF".charAt(random.nextInt(22)));
			}
		}
		return s.toString();
	}

	private static String literal(String text) {
		return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private static String interpret(String program) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("clojure-default-readers", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : SourceLanguage.CLOJURE.read(program, Features.INTERPRETER, "probe.clj")) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

}
