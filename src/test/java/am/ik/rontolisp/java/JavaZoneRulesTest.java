package am.ik.rontolisp.java;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneOffsetTransitionRule;
import java.time.zone.ZoneRules;
import java.time.zone.ZoneRulesProvider;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;

import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * Generates the regions of the tz database as the Java subset's {@code java.time} reads
 * them ({@code zone-rules.txt}, {@link JavaZones}) from the running JDK's public
 * {@code java.time.zone}, and pins the shipped file to them. A JDK whose tz database is
 * newer than the shipped one fails here, and the message names the regenerated file to
 * copy over it; an older one is skipped, saying so.
 *
 * <p>
 * Everything comes from running the JDK, nothing from reading it
 * ({@code .kb/java-frontend.md}, "java.time: the regions"): a zone's changes of offset
 * are its {@code getTransitions()}, the rules of the years after them its
 * {@code getTransitionRules()}, and its standard offset is asked every few days from 1800
 * to 2100, a change found to the second. What the library makes of the file is compared
 * with the JDK's answers by {@code JavaSpecE2eTest}'s region case and by the walk of
 * every region ({@code .todo/artefacts}).
 */
class JavaZoneRulesTest {

	private static final String SHIPPED = "src/main/resources/am/ik/rontolisp/java/zone-rules.txt";

	private static final long DAY = 86400;

	/** A change of a zone's offsets: when, and what they are from then on. */
	record Change(long at, int wall, int standard) {
	}

	/**
	 * A rule a zone changes its offset by every year.
	 *
	 * @param day the day of the month, or counted from its end when negative
	 * @param dayOfWeek the day of the week the change waits for (or goes back to), 0 for
	 * none
	 * @param second the second of the day, 86400 for the day's end
	 * @param definition whose clock the time is read from: 0 UTC, 1 the wall clock before
	 * the change, 2 standard time
	 */
	record Rule(int month, int day, int dayOfWeek, int second, int definition, int standard, int before, int after) {
	}

	/** What the JDK knows of one zone. */
	record Zone(int wall, int standard, List<Change> changes, List<Rule> rules) {
	}

	/**
	 * Changes of {@code count} years in a row that one rule, read from the wall clock,
	 * gives.
	 */
	record Run(int year, int count, Rule rule) {
	}

	private static Zone ask(ZoneRules rules) {
		long from = LocalDate.of(1800, 1, 1).toEpochDay() * DAY;
		long to = LocalDate.of(2100, 1, 1).toEpochDay() * DAY;
		TreeMap<Long, Change> changes = new TreeMap<>();
		for (ZoneOffsetTransition transition : rules.getTransitions()) {
			long at = transition.toEpochSecond();
			changes.put(at, new Change(at, transition.getOffsetAfter().getTotalSeconds(),
					rules.getStandardOffset(transition.getInstant()).getTotalSeconds()));
		}
		// the standard offset has no list: asked every five days, a change to the second
		int base = rules.getStandardOffset(Instant.ofEpochSecond(from)).getTotalSeconds();
		int last = base;
		for (long second = from; second < to; second += 5 * DAY) {
			int now = rules.getStandardOffset(Instant.ofEpochSecond(second + 5 * DAY)).getTotalSeconds();
			if (now == last) {
				continue;
			}
			long low = second;
			long high = second + 5 * DAY;
			while (high - low > 1) {
				long middle = low + (high - low) / 2;
				if (rules.getStandardOffset(Instant.ofEpochSecond(middle)).getTotalSeconds() == last) {
					low = middle;
				}
				else {
					high = middle;
				}
			}
			int found = rules.getStandardOffset(Instant.ofEpochSecond(high)).getTotalSeconds();
			assertThat(found).as("one change of the standard offset in five days").isEqualTo(now);
			Change known = changes.get(high);
			if (known == null) {
				changes.put(high,
						new Change(high, rules.getOffset(Instant.ofEpochSecond(high)).getTotalSeconds(), now));
			}
			else {
				assertThat(known.standard()).isEqualTo(now);
			}
			last = now;
		}
		List<Rule> yearly = new ArrayList<>();
		for (ZoneOffsetTransitionRule rule : rules.getTransitionRules()) {
			int definition = switch (rule.getTimeDefinition()) {
				case UTC -> 0;
				case WALL -> 1;
				case STANDARD -> 2;
			};
			yearly.add(new Rule(rule.getMonth().getValue(), rule.getDayOfMonthIndicator(),
					rule.getDayOfWeek() == null ? 0 : rule.getDayOfWeek().getValue(),
					rule.isMidnightEndOfDay() ? 86400 : rule.getLocalTime().toSecondOfDay(), definition,
					rule.getStandardOffset().getTotalSeconds(), rule.getOffsetBefore().getTotalSeconds(),
					rule.getOffsetAfter().getTotalSeconds()));
		}
		return new Zone(rules.getOffset(Instant.ofEpochSecond(from)).getTotalSeconds(), base,
				List.copyOf(changes.values()), yearly);
	}

	/** When a rule read from the wall clock changes the offset in a year. */
	private static long at(int year, Rule rule) {
		LocalDate date;
		if (rule.day() < 0) {
			date = LocalDate.of(year, rule.month(), YearMonth.of(year, rule.month()).lengthOfMonth() + 1 + rule.day());
			while (rule.dayOfWeek() != 0 && date.getDayOfWeek().getValue() != rule.dayOfWeek()) {
				date = date.minusDays(1);
			}
		}
		else {
			date = LocalDate.of(year, rule.month(), rule.day());
			while (rule.dayOfWeek() != 0 && date.getDayOfWeek().getValue() != rule.dayOfWeek()) {
				date = date.plusDays(1);
			}
		}
		return date.toEpochDay() * DAY + rule.second() - rule.before();
	}

	/**
	 * A zone's changes as runs of years one rule gives: for each change not yet in a run,
	 * the rule that goes on for the most years after it -- its day as it is, as the first
	 * such day of the week on or after one of the seven days before it, or as the last on
	 * or before one of the seven after it.
	 */
	private static List<Run> runs(Zone zone) {
		List<Change> changes = zone.changes();
		Map<Long, Integer> places = new HashMap<>();
		for (int i = 0; i < changes.size(); i++) {
			places.put(changes.get(i).at(), i);
		}
		boolean[] taken = new boolean[changes.size()];
		List<Run> runs = new ArrayList<>();
		for (int i = 0; i < changes.size(); i++) {
			if (taken[i]) {
				continue;
			}
			Change change = changes.get(i);
			int before = i == 0 ? zone.wall() : changes.get(i - 1).wall();
			long local = change.at() + before;
			Run best = null;
			List<Integer> members = List.of();
			// a change at midnight may be the end of the day before
			for (int back = 0; back <= (Math.floorMod(local, DAY) == 0 ? 1 : 0); back++) {
				LocalDate date = LocalDate.ofEpochDay(Math.floorDiv(local, DAY) - back);
				int second = (int) (local - date.toEpochDay() * DAY);
				int day = date.getDayOfMonth();
				int dayOfWeek = date.getDayOfWeek().getValue();
				List<int[]> days = new ArrayList<>();
				days.add(new int[] { day, 0 });
				for (int first = Math.max(1, day - 6); first <= day; first++) {
					days.add(new int[] { first, dayOfWeek });
				}
				for (int end = day; end <= Math.min(date.lengthOfMonth(), day + 6); end++) {
					days.add(new int[] { end - date.lengthOfMonth() - 1, dayOfWeek });
				}
				for (int[] candidate : days) {
					Rule rule = new Rule(date.getMonthValue(), candidate[0], candidate[1], second, 1, change.standard(),
							before, change.wall());
					List<Integer> found = new ArrayList<>(List.of(i));
					for (int year = date.getYear() + 1;; year++) {
						int length = YearMonth.of(year, rule.month()).lengthOfMonth();
						if (rule.day() > length || length + 1 + rule.day() < 1) {
							break;
						}
						Integer place = places.get(at(year, rule));
						if (place == null || taken[place] || changes.get(place - 1).wall() != before
								|| changes.get(place).wall() != change.wall()
								|| changes.get(place).standard() != change.standard()) {
							break;
						}
						found.add(place);
					}
					if (best == null || found.size() > members.size()) {
						best = new Run(date.getYear(), found.size(), rule);
						members = found;
					}
				}
			}
			assertThat(at(java.util.Objects.requireNonNull(best).year(), best.rule())).isEqualTo(change.at());
			members.forEach(place -> taken[place] = true);
			runs.add(best);
		}
		runs.sort(Comparator.comparingInt(Run::year));
		return runs;
	}

	/**
	 * A number in digits of five bits, the lowest first, a sixth bit saying more follow.
	 */
	private static void number(StringBuilder out, int value) {
		assertThat(value).isNotNegative();
		do {
			int digit = value & 31;
			value >>>= 5;
			digit += value == 0 ? 36 : 68;
			// no backslash
			out.append((char) (digit >= 92 ? digit + 1 : digit));
		}
		while (value != 0);
	}

	private static void rule(StringBuilder out, Rule rule, List<Integer> offsets) {
		number(out, rule.month() - 1 + 12 * rule.dayOfWeek());
		number(out, rule.day() > 0 ? rule.day() : 31 - rule.day());
		number(out, rule.second() % 900 == 0 ? rule.second() / 900 << 1 : rule.second() << 1 | 1);
		int count = offsets.size();
		number(out, offsets.indexOf(rule.before())
				+ count * (offsets.indexOf(rule.after()) + count * offsets.indexOf(rule.standard())));
	}

	/**
	 * A zone's rules as text ({@code java.time.zone.ZoneChanges} of the library reads
	 * it): its offsets, which two it starts with, the rules of the years after its last
	 * change, and its changes as runs.
	 */
	static String text(Zone zone) {
		List<Run> runs = runs(zone);
		List<Integer> offsets = new ArrayList<>();
		for (int offset : new int[] { zone.wall(), zone.standard() }) {
			if (!offsets.contains(offset)) {
				offsets.add(offset);
			}
		}
		List<Rule> all = new ArrayList<>(zone.rules());
		runs.forEach(run -> all.add(run.rule()));
		for (Rule rule : all) {
			for (int offset : new int[] { rule.before(), rule.after(), rule.standard() }) {
				if (!offsets.contains(offset)) {
					offsets.add(offset);
				}
			}
		}
		// a change is kept with the places of two offsets in three bits each
		// (ZoneChanges)
		assertThat(offsets).hasSizeLessThanOrEqualTo(8);
		StringBuilder out = new StringBuilder();
		number(out, offsets.size());
		for (int offset : offsets) {
			boolean quarters = offset % 900 == 0;
			int value = quarters ? offset / 900 : offset;
			number(out, ((value << 1) ^ (value >> 31)) << 1 | (quarters ? 0 : 1));
		}
		number(out, offsets.indexOf(zone.wall()) + offsets.size() * offsets.indexOf(zone.standard()));
		number(out, zone.rules().size());
		for (Rule rule : zone.rules()) {
			number(out, rule.definition());
			rule(out, rule, offsets);
		}
		int year = 1800;
		for (Run run : runs) {
			number(out, run.year() - year << 1 | (run.count() == 1 ? 1 : 0));
			if (run.count() > 1) {
				number(out, run.count() - 2);
			}
			rule(out, run.rule(), offsets);
			year = run.year();
		}
		return out.toString();
	}

	private static String version() {
		return ZoneRulesProvider.getVersions("UTC").lastKey();
	}

	private static String shortIds() {
		List<String> pairs = new ArrayList<>();
		new TreeMap<>(ZoneId.SHORT_IDS).forEach((name, zone) -> pairs.add(name + "=" + zone));
		return String.join(" ", pairs);
	}

	/** The generated file: a line for each set of rules, the names that have it first. */
	static String text() {
		Map<String, List<String>> sets = new TreeMap<>();
		Map<Zone, String> texts = new HashMap<>();
		JavaZones none = JavaZones.parse("");
		for (String id : new TreeSet<>(ZoneId.getAvailableZoneIds())) {
			ZoneId zone = ZoneId.of(id);
			// the names the library computes are no line: UTC, Etc/GMT+5 and the like
			if (zone.getRules().isFixedOffset() && !id.startsWith("SystemV/")) {
				continue;
			}
			assertThat(none.has(id)).isFalse();
			sets.computeIfAbsent(texts.computeIfAbsent(ask(zone.getRules()), JavaZoneRulesTest::text),
					rules -> new ArrayList<>())
				.add(id);
		}
		List<String> lines = new ArrayList<>();
		sets.forEach((rules, names) -> lines.add(String.join(" ", names) + " " + rules));
		lines.sort(Comparator.naturalOrder());
		return "# The regions of the tz database (java.time of the experimental Java subset): the changes\n"
				+ "# of each one's offset, as a running JDK's public java.time.zone answers them. Generated by\n"
				+ "# JavaZoneRulesTest, which fails when a JDK knows a newer tz database: regenerate the\n"
				+ "# file there, never by hand. A line is the names sharing one set of rules, then the rules\n"
				+ "# (.kb/java-frontend.md, \"java.time: the regions\").\n"
				+ "# Third-party: the tz database, which is in the public domain.\n"
				+ "# Notice: THIRD-PARTY-NOTICES.txt, \"tz database\".\n" + "tzdb " + version() + "\n"
				+ "# The short names ZoneId.SHORT_IDS maps to zones.\n" + "short " + shortIds() + "\n"
				+ String.join("\n", lines) + "\n";
	}

	private static String shipped(String resource) throws IOException {
		try (InputStream in = JavaZones.class.getResourceAsStream(resource)) {
			assertThat(in).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** The kinds of locale the library's words tell apart, in its order (Texts.kind). */
	private static final Locale[] KINDS = { Locale.ROOT, Locale.US, Locale.UK, Locale.CANADA };

	/**
	 * A zone's names in a locale, as a JDK writes them: standard time short and in full,
	 * daylight time, and the zone as a whole (generic).
	 */
	private static String[] names(String id, Locale locale) {
		TimeZone zone = TimeZone.getTimeZone(id);
		return new String[] { zone.getDisplayName(false, TimeZone.SHORT, locale),
				zone.getDisplayName(false, TimeZone.LONG, locale), zone.getDisplayName(true, TimeZone.SHORT, locale),
				zone.getDisplayName(true, TimeZone.LONG, locale), ZoneId.of(id).getDisplayName(TextStyle.SHORT, locale),
				ZoneId.of(id).getDisplayName(TextStyle.FULL, locale) };
	}

	/**
	 * Whether a JDK writes the name of the other time than the rules say at a second (the
	 * standard time's where they say daylight time, or the reverse), or {@code null}
	 * where the two names are one.
	 */
	private static @Nullable Boolean flipped(ZoneId zone, long second) {
		Instant instant = Instant.ofEpochSecond(second);
		ZonedDateTime at = instant.atZone(zone);
		String root = DateTimeFormatter.ofPattern("zzzz", Locale.ROOT).format(at);
		Boolean daylight = root.endsWith(" (+1)") ? Boolean.TRUE : root.endsWith(" (+0)") ? Boolean.FALSE : null;
		if (daylight == null) {
			String[] us = names(zone.getId(), Locale.US);
			if (us[1].equals(us[3])) {
				return null;
			}
			String written = DateTimeFormatter.ofPattern("zzzz", Locale.US).format(at);
			assertThat(written).as(zone + " at " + instant).isIn(us[1], us[3]);
			daylight = written.equals(us[3]);
		}
		return daylight != zone.getRules().isDaylightSavings(instant);
	}

	/**
	 * Where the time whose name a JDK writes departs from the rules: spans of seconds,
	 * each from its first to the one after its last, {@code Long.MAX_VALUE} for a span
	 * that does not end. The time is asked at each change of the zone's offsets and
	 * halfway to the next one, which must agree.
	 */
	private static List<long[]> flips(String id) {
		ZoneId zone = ZoneId.of(id);
		ZoneRules rules = zone.getRules();
		long from = LocalDate.of(1800, 1, 1).toEpochDay() * DAY;
		long to = LocalDate.of(2100, 1, 1).toEpochDay() * DAY;
		TreeSet<Long> changes = new TreeSet<>();
		changes.add(from);
		ask(rules).changes().forEach(change -> changes.add(change.at()));
		for (ZoneOffsetTransition next = rules.nextTransition(Instant.ofEpochSecond(from)); next != null
				&& next.toEpochSecond() < to; next = rules.nextTransition(next.getInstant())) {
			changes.add(next.toEpochSecond());
		}
		List<long[]> flips = new ArrayList<>();
		Long[] at = changes.toArray(new Long[0]);
		for (int i = 0; i < at.length; i++) {
			long start = at[i];
			long end = i + 1 < at.length ? at[i + 1] : to;
			Boolean first = flipped(zone, start);
			Boolean middle = flipped(zone, start + (end - start) / 2);
			assertThat(middle).as(id + " from " + Instant.ofEpochSecond(start)).isEqualTo(first);
			if (Boolean.TRUE.equals(first)) {
				long until = i + 1 < at.length ? end : Long.MAX_VALUE;
				long[] last = flips.isEmpty() ? null : flips.get(flips.size() - 1);
				if (last != null && last[1] == start) {
					last[1] = until;
				}
				else {
					flips.add(new long[] { start, until });
				}
			}
		}
		return flips;
	}

	/**
	 * The names a JDK reads as a zone in each kind of locale and style (z and zzzz), with
	 * the time each is a name of: 0 standard, 1 daylight, 2 generic.
	 */
	private static Map<String, List<String>> reads() {
		Map<String, List<String>> reads = new TreeMap<>();
		for (int kind = 0; kind < KINDS.length; kind++) {
			Locale locale = KINDS[kind];
			List<TreeSet<String>> candidates = List.of(new TreeSet<>(), new TreeSet<>());
			for (String[] row : java.text.DateFormatSymbols.getInstance(locale).getZoneStrings()) {
				for (int i = 1; i < row.length; i++) {
					candidates.get(i % 2).add(row[i]);
				}
			}
			for (String id : ZoneId.getAvailableZoneIds()) {
				String[] names = names(id, locale);
				for (int i = 0; i < names.length; i++) {
					candidates.get(i % 2).add(names[i]);
				}
			}
			for (int style = 0; style < 2; style++) {
				DateTimeFormatter formatter = DateTimeFormatter.ofPattern(style == 0 ? "z" : "zzzz", locale);
				for (String name : candidates.get(style)) {
					TemporalAccessor read;
					try {
						read = formatter.parse(name);
					}
					catch (DateTimeParseException none) {
						continue;
					}
					String text = read.toString();
					String type = text.substring(text.lastIndexOf(',') + 1);
					if (type.equals("-1")) {
						// an id or an offset, which the library reads as such
						continue;
					}
					assertThat(name).doesNotContain("|");
					reads
						.computeIfAbsent(String.valueOf(read.query(TemporalQueries.zoneId())),
								zone -> new ArrayList<>())
						.add(kind + "" + style + type + name);
				}
			}
		}
		return reads;
	}

	/**
	 * The names of a zone the library computes, as the library's {@code ZoneNames} has
	 * them: UTC's and GMT's, and those of a fixed zone of the tz database, which are its
	 * offset after GMT.
	 */
	private static String[] fixed(String id, int kind) {
		String name = id.startsWith("Etc/") ? id.substring(4) : id;
		String full;
		String brief;
		if (List.of("UTC", "UCT", "Universal", "Zulu").contains(name)) {
			brief = "UTC";
			full = kind == 0 ? "GMT" : "Coordinated Universal Time";
		}
		else if (List.of("GMT", "GMT0", "Greenwich", "GMT+0", "GMT-0").contains(name)) {
			brief = "GMT";
			full = kind == 0 ? "GMT" : "Greenwich Mean Time";
		}
		else {
			// Etc/GMT+5 is five hours behind
			int hours = Integer.parseInt(name.substring(4));
			brief = "GMT" + (name.charAt(3) == '+' ? "-" : "+") + (hours < 10 ? "0" : "") + hours + ":00";
			full = brief;
		}
		return new String[] { brief, full, brief, full, brief, full };
	}

	/**
	 * The names of the zones ({@code zone-names.txt}, {@link JavaZoneNames}): for each
	 * region a line {@code w} of its names in the four kinds of locale and the spans
	 * where the time written departs from its rules, and a line {@code r} of the names a
	 * JDK reads as it. The names of UTC, GMT and the fixed zones of the tz database are
	 * the library's rules, which this asserts. The names are a fresh JDK's: one that has
	 * written a zone's names for {@code ENGLISH} answers some British and Canadian ones
	 * differently after (measured 2026-11-20, 148 zones), so nothing asks ENGLISH first.
	 */
	static String names() {
		List<String> lines = new ArrayList<>();
		JavaZones regions = JavaZones.shipped();
		for (String id : new TreeSet<>(ZoneId.getAvailableZoneIds())) {
			String[] us = names(id, Locale.US);
			if (!regions.has(id)) {
				for (int kind = 0; kind < KINDS.length; kind++) {
					assertThat(names(id, KINDS[kind])).as(id + " in " + KINDS[kind]).containsExactly(fixed(id, kind));
				}
				continue;
			}
			StringBuilder line = new StringBuilder("w ").append(id);
			for (int kind = 0; kind < KINDS.length; kind++) {
				String[] names = names(id, KINDS[kind]);
				for (int i = 0; i < names.length; i++) {
					assertThat(names[i]).doesNotContain("|").isNotEmpty();
					line.append('|').append(kind > 1 && names[i].equals(us[i]) ? "" : names[i]);
				}
			}
			StringBuilder spans = new StringBuilder();
			for (long[] span : flips(id)) {
				spans.append(spans.isEmpty() ? "" : ",")
					.append(span[0])
					.append(',')
					.append(span[1] == Long.MAX_VALUE ? "" : Long.toString(span[1]));
			}
			lines.add(line.append('|').append(spans).toString());
		}
		reads().forEach((zone, names) -> {
			if (regions.has(zone)) {
				lines.add("r " + zone + "|" + String.join("|", names));
			}
			else {
				// a name read as a zone the library computes: UTC's in words
				assertThat(zone).isEqualTo("Etc/UTC");
				assertThat(names).containsExactlyInAnyOrder("112Coordinated Universal Time",
						"212Coordinated Universal Time", "312Coordinated Universal Time");
			}
		});
		return "# The names of the regions of the tz database in words (java.time of the experimental Java\n"
				+ "# subset): what a running JDK writes and reads for each in the four kinds of locale.\n"
				+ "# Generated by JavaZoneRulesTest from its public TimeZone, ZoneId and DateTimeFormatter;\n"
				+ "# regenerate it there, never by hand (.kb/java-frontend.md, \"java.time: the names\").\n"
				+ "# Third-party: Unicode CLDR data (Unicode-3.0), as a running JDK writes it, Copyright (c)\n"
				+ "# 1991-2026 Unicode, Inc.\n" + "# Notice: THIRD-PARTY-NOTICES.txt, \"Unicode\".\n"
				+ String.join("\n", lines) + "\n";
	}

	@Test
	void theShippedRegionsAreTheJdks() throws IOException {
		String shipped = shipped("zone-rules.txt");
		String held = JavaZones.parse(shipped).version();
		if (version().compareTo(held) < 0) {
			abort("this JDK's tz database is " + version() + ", older than the shipped " + held);
		}
		Map<String, String> generated = Map.of("zone-rules.txt", text(), "zone-names.txt", names());
		for (Map.Entry<String, String> file : new TreeMap<>(generated).entrySet()) {
			String text = shipped(file.getKey());
			if (!file.getValue().equals(text)) {
				Path out = Path.of("target", file.getKey());
				Files.createDirectories(out.getParent());
				Files.writeString(out, file.getValue());
				// A name written as an offset follows the JDK's clock: the same
				// tz database and other names mean a change has passed.
				assertThat(text)
					.as("copy " + out.toAbsolutePath() + " over " + SHIPPED.replace("zone-rules.txt", file.getKey())
							+ (version().equals(held)
									? " (the same tz database " + held + ": the clock passed a change)" : ""))
					.isEqualTo(file.getValue());
			}
		}
	}

	@Test
	void aProgramsTableHoldsTheRegionsItNames() {
		JavaZones zones = JavaZones.shipped();
		assertThat(zones.has("Europe/Paris")).isTrue();
		// a name the library computes, and one no database has
		assertThat(zones.has("Etc/GMT+5")).isFalse();
		assertThat(zones.has("Europe/Nowhere")).isFalse();
		// one region: its name whole, then its rules
		String paris = zones.table(List.of("Europe/Paris"));
		assertThat(paris).startsWith(" Europe/Paris!").endsWith(" ").hasSizeLessThan(400);
		// names sharing their rules are one entry, and an area several names have is
		// written once, ahead
		assertThat(zones.table(List.of("Asia/Kolkata", "Asia/Calcutta", "Asia/Tokyo", "Japan")))
			.startsWith("Asia/, #Calcutta!#Kolkata!")
			.contains(" #Tokyo!Japan!");
		String all = zones.table(null);
		assertThat(all).contains("Japan!").hasSizeLessThan(48_000);
		assertThat(all.chars()).allMatch(c -> c >= ' ' && c <= '~' && c != '"' && c != '\\');
		// A program that names no region holds none, though it reads a zoned text with an
		// offset alone; a constant brings its region alone, a name computed where the
		// program runs all, a static method's parameter what every call passes it. A
		// short name of SHORT_IDS brings the region it stands for, a zoned text the
		// regions it names, and so does a name the provider is asked for.
		String program = """
				import java.time.*;
				class Z {
				    static ZoneId zone(String id) { return ZoneId.of(id); }
				    public static void main(String[] args) {
				        System.out.println(ZonedDateTime.parse("2026-07-01T10:15+02:00").plusHours(1) + " " + %s);
				    }
				}
				""";
		for (String none : List.of("ZoneId.of(\"UTC\")", "lib.Reader.read(args[0])")) {
			assertThat(lower(program.formatted(none)))
				.noneMatch(form -> form.contains("java.time.Tz") || form.contains("java.time.zone.ZoneChanges"));
		}
		for (String tokyo : List.of("ZoneId.of(\"Asia/Tokyo\")", "zone(\"Asia/Tokyo\")",
				"ZoneId.of(\"JST\", ZoneId.SHORT_IDS)",
				"java.time.zone.ZoneRulesProvider.getRules(\"Asia/Tokyo\", false)",
				"ZonedDateTime.parse(\"2026-07-01T10:15+09:00[Asia/Tokyo]\")")) {
			assertThat(lower(program.formatted(tokyo))).as(tokyo)
				.anyMatch(form -> form.contains("\" Asia/Tokyo!"))
				.anyMatch(form -> form.contains("|java.time.zone.ZoneChanges.find(String)|"))
				.noneMatch(form -> form.contains("Paris!"));
		}
		// A library of the source path reaches a parse it may never make (YAVI's table of
		// types): only the program's own files bring regions by reading zoned texts.
		for (String every : List.of("ZoneId.of(args[1])", "zone(args[1])", "ZoneId.of(\"JST\", java.util.Map.of())",
				"ZonedDateTime.parse(args[1])")) {
			assertThat(lower(program.formatted(every))).as(every).anyMatch(form -> form.contains("Paris!"));
		}
		// The names in words a program carries are those of the regions it holds: what it
		// writes for each, a group of six names of a kind of locale written once however
		// many regions write it, and what a JDK reads as each -- a name a JDK reads as a
		// region the program does not hold read as the one it holds that writes it.
		JavaZoneNames names = JavaZoneNames.shipped();
		assertThat(names.written(List.of("Asia/Tokyo")))
			.startsWith("GMT+09:00\u0001\u0001GMT+10:00\u0001\u0001GMT+09:00\u0001Tokyo\u0002JST\u0001")
			.contains("\u0001Japan Standard Time\u0001")
			.endsWith("\u0003Asia/Tokyo\u00010\u00011\u00012\u00012\u0001\u0002");
		assertThat(names.written(List.of("Europe/Berlin", "Europe/Paris")))
			.endsWith("\u0003Europe/Berlin\u00010\u00011\u00012\u00012\u0001\u0002"
					+ "Europe/Paris\u00013\u00011\u00012\u00012\u0001\u0002");
		// (a name read alike in English, British and Canadian is one entry, N its kinds)
		assertThat(names.read(List.of("Asia/Tokyo"))).contains("\u0001N10Japan Standard Time\u0001");
		assertThat(names.read(List.of("Africa/Accra"))).startsWith("Africa/Accra\u0001")
			.contains("\u0001N12Accra Time");
		// (a JDK reads Accra's names as Abidjan's, and in British English as London's)
		assertThat(names.read(List.of("Africa/Accra", "Africa/Abidjan", "Europe/London")))
			.startsWith("Africa/Abidjan\u0001")
			.contains("\u0001J12Accra Time")
			.doesNotContain("Africa/Accra");
		assertThat(names.written(null)).hasSizeLessThan(54_000);
		assertThat(names.read(null)).hasSizeLessThan(27_000);
		// A session goes on holding what a buffer named: the zone lives on in a variable.
		JavaSession session = new JavaSession(JavaFiles.NONE);
		session.read("var tokyo = java.time.ZoneId.of(\"Asia/Tokyo\");");
		assertThat(session.read("java.time.Instant.EPOCH.atZone(tokyo)")
			.stream()
			.flatMap(step -> step.forms().stream())
			.map(LispVal::print)).anyMatch(form -> form.contains("|java.time.TzRegion.offsetAt(long)|"));
	}

	private static List<String> lower(String source) {
		JavaFiles files = new JavaFiles() {
			@Override
			public JavaFiles.@Nullable Source find(String from, String path) {
				return null;
			}

			@Override
			public JavaFiles.@Nullable Source library(String path) {
				return path.equals("lib/Reader.java") ? new JavaFiles.Source("lib.jar!/" + path, """
						package lib;
						public class Reader {
						    public static Object read(String text) {
						        return java.time.ZonedDateTime.parse(text);
						    }
						}
						""") : null;
			}
		};
		return Java.read(source, "Z.java", files).stream().map(LispVal::print).toList();
	}

}
