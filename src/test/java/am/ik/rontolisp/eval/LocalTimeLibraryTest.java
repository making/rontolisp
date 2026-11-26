package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The WASI targets' zone reader, {@code localtime.lisp}: for a TZif file under
 * {@code /usr/share/zoneinfo} (by name, {@code :name} or path) and for a POSIX rule
 * string, the offset and daylight flag {@code java.time} answers for the same zone -- the
 * interpreter's and the JVM's source -- on both sides of every transition and in January
 * and July of every year, through the footer's rule past the files' last transition; and
 * spliced, in the arm the target needs, into a WASM program that names the codec and
 * nothing else.
 */
class LocalTimeLibraryTest {

	private static final Path ZONEINFO = Path.of("/usr/share/zoneinfo");

	/**
	 * TZ value, the java.time zone it must agree with, and the first year compared. The
	 * POSIX rules from the year their zone has kept them.
	 */
	private record Zone(String tz, String java, int from) {
	}

	private static final List<Zone> ZONES = List.of(new Zone("America/New_York", "America/New_York", 1972),
			new Zone("Australia/Sydney", "Australia/Sydney", 1972), new Zone(":Asia/Kolkata", "Asia/Kolkata", 1972),
			new Zone(ZONEINFO.resolve("Australia/Lord_Howe").toString(), "Australia/Lord_Howe", 1982),
			new Zone("Europe/London", "Europe/London", 1972), new Zone("America/Sao_Paulo", "America/Sao_Paulo", 1972),
			new Zone("EST5EDT,M3.2.0,M11.1.0", "America/New_York", 2008),
			new Zone("AEST-10AEDT,M10.1.0,M4.1.0/3", "Australia/Sydney", 2009),
			new Zone("<+1030>-10:30<+11>-11,M10.1.0,M4.1.0", "Australia/Lord_Howe", 2009),
			new Zone("GMT0BST,M3.5.0/1,M10.5.0", "Europe/London", 1997), new Zone("JST-9", "Asia/Tokyo", 1972),
			new Zone("<-03>3", "America/Sao_Paulo", 2020));

	@Test
	void theZoneReaderAnswersWhatJavaTimeAnswersAroundEveryTransition() {
		assumeTrue(Files.isRegularFile(ZONEINFO.resolve("America/New_York")), "no zone files in " + ZONEINFO);
		LispEvaluator evaluator = evaluator();
		for (Zone zone : ZONES) {
			ZoneRules rules = ZoneId.of(zone.java()).getRules();
			List<Long> instants = instants(rules, zone.from(), 2046);
			List<Long> expected = instants.stream().map(t -> encoded(rules, t)).toList();
			assertThat(offsets(evaluator, zone.tz(), instants)).as(zone.tz()).isEqualTo(expected);
		}
	}

	@Test
	void julianAndZeroBasedRuleDaysCountTheLeapDayAsPosixSays() {
		LispEvaluator evaluator = evaluator();
		// 2024 is a leap year: Jn never counts February 29 (J60 is March 1), n does (59
		// is February 29). Daylight saving time starts at 00:00 that day.
		long feb29 = LocalDate.of(2024, 2, 29).toEpochSecond(LocalTime.MIDNIGHT, ZoneOffset.UTC);
		long mar1 = feb29 + 86400;
		assertThat(offsets(evaluator, "AAA0BBB,J60/0,J300/0", List.of(mar1 - 1, mar1))).containsExactly(0L,
				3600L * 2 + 1);
		assertThat(offsets(evaluator, "AAA0BBB,59/0,300/0", List.of(feb29 - 1, feb29))).containsExactly(0L,
				3600L * 2 + 1);
		// What reads as neither a zone file nor a rule is no zone (UTC, one level up).
		assertThat(evaluator.eval(LispReader.readFromString("(list (rontolisp::%tz-zone \"\")"
				+ " (rontolisp::%tz-zone \"No/Such_Zone\") (rontolisp::%tz-zone \"UTC+\"))"))
			.print()).isEqualTo("(NIL NIL NIL)");
	}

	@Test
	void eachWasmTargetIsSplicedTheArmItsHostCanAnswer() {
		List<LispVal> naming = LispReader.readAllFromString("(print (get-decoded-time))", Features.WASM);
		List<LispVal> reader = LocalTimeLibrary.process(naming, WitExportDirective.Backend.WASM_GC, false, false);
		assertThat(reader).hasSize(LocalTimeLibrary.forms().size() + naming.size());
		assertThat(LocalTimeLibrary.process(naming, WitExportDirective.Backend.WASM_COMPONENT, false, false))
			.hasSize(reader.size());
		// --native asks its runner; --no-wasi has neither environment nor files.
		assertThat(LocalTimeLibrary.process(naming, WitExportDirective.Backend.WASM_GC, false, true).getFirst().print())
			.contains("RONTOLISP:WASM-IMPORT", "\"rltime\"", "\"local_offset\"");
		assertThat(LocalTimeLibrary.process(naming, WitExportDirective.Backend.WASM_GC, true, false)).hasSize(2);
		// Every call passing its zone as a literal never asks for the local one: UTC,
		// with no import behind it. A zone that may be nil at run time can.
		List<LispVal> zoned = LispReader.readAllFromString(
				"(print (list (decode-universal-time 0 0) (encode-universal-time 0 0 0 1 1 1970 -9)))", Features.WASM);
		assertThat(LocalTimeLibrary.process(zoned, WitExportDirective.Backend.WASM_GC, false, true)).hasSize(2);
		List<LispVal> computed = LispReader.readAllFromString("(defun f (tz) (decode-universal-time 0 tz))",
				Features.WASM);
		assertThat(LocalTimeLibrary.process(computed, WitExportDirective.Backend.WASM_GC, false, false))
			.hasSize(LocalTimeLibrary.forms().size() + 1);
		// The JVM asks java.time; a program without the codec, or with the reader
		// already in it, is left as it is.
		assertThat(LocalTimeLibrary.process(naming, WitExportDirective.Backend.OTHER, false, false)).isSameAs(naming);
		List<LispVal> plain = LispReader.readAllFromString("(print (get-universal-time))", Features.WASM);
		assertThat(LocalTimeLibrary.process(plain, WitExportDirective.Backend.WASM_GC, false, false)).isSameAs(plain);
		assertThat(LocalTimeLibrary.process(reader, WitExportDirective.Backend.WASM_GC, false, false)).isSameAs(reader);
	}

	private static LispEvaluator evaluator() {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		for (LispVal form : LocalTimeLibrary.forms()) {
			evaluator.eval(form);
		}
		return evaluator;
	}

	private static List<Long> offsets(LispEvaluator evaluator, String tz, List<Long> instants) {
		String call = "(let ((z (rontolisp::%tz-zone \"" + tz + "\")))"
				+ " (mapcar (lambda (u) (rontolisp::%tz-offset z u)) '("
				+ instants.stream().map(String::valueOf).collect(Collectors.joining(" ")) + ")))";
		List<Long> answers = new ArrayList<>();
		LispVal rest = evaluator.eval(LispReader.readFromString(call));
		while (rest instanceof LispCons cons) {
			answers.add(((LispInteger) cons.car()).value());
			rest = cons.cdr();
		}
		return answers;
	}

	// Each transition and the second before it, and January 15 and July 15 of each year.
	private static List<Long> instants(ZoneRules rules, int fromYear, int toYear) {
		long from = LocalDate.of(fromYear, 1, 1).toEpochSecond(LocalTime.MIDNIGHT, ZoneOffset.UTC);
		long to = LocalDate.of(toYear, 1, 1).toEpochSecond(LocalTime.MIDNIGHT, ZoneOffset.UTC);
		List<Long> instants = new ArrayList<>();
		ZoneOffsetTransition next = rules.nextTransition(Instant.ofEpochSecond(from - 1));
		while (next != null && next.toEpochSecond() < to) {
			instants.add(next.toEpochSecond() - 1);
			instants.add(next.toEpochSecond());
			next = rules.nextTransition(next.getInstant());
		}
		for (int year = fromYear; year < toYear; year++) {
			instants.add(LocalDate.of(year, 1, 15).toEpochSecond(LocalTime.NOON, ZoneOffset.UTC));
			instants.add(LocalDate.of(year, 7, 15).toEpochSecond(LocalTime.NOON, ZoneOffset.UTC));
		}
		return instants;
	}

	private static long encoded(ZoneRules rules, long seconds) {
		Instant instant = Instant.ofEpochSecond(seconds);
		return rules.getOffset(instant).getTotalSeconds() * 2L + (rules.isDaylightSavings(instant) ? 1 : 0);
	}

}
