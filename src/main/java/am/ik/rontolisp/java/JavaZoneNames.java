package am.ik.rontolisp.java;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

/**
 * The names of the regions of the tz database in words, as a compiled program carries
 * them ({@code zone-names.txt}, generated from a running JDK by
 * {@code JavaZoneRulesTest}; {@code .kb/java-frontend.md}, "java.time: the names"): what
 * a formatter writes for each in the four kinds of locale the library tells apart, and
 * which names a JDK reads as it. A program carries the names of the regions it holds
 * ({@link JavaZones}), so the tables the library's {@code java.time.format.ZoneNames}
 * reads are made here for each program. The names of UTC, GMT and the fixed zones of the
 * tz database are the library's rules.
 */
final class JavaZoneNames {

	private static final String RESOURCE = "zone-names.txt";

	/** What separates the fields of an entry of a program's table. */
	static final char FIELD = '\u0001';

	/** What ends an entry of a program's table. */
	static final char ENTRY = '\u0002';

	/** The names written for a zone in each kind of locale: six each, the root first. */
	private static final int NAMES = 24;

	private static final class Holder {

		static final JavaZoneNames SHIPPED = read();

	}

	/**
	 * Each region's names (the root's six, English, British and Canadian, the last two
	 * empty where English has the same) and the spans where the time a JDK writes the
	 * name of departs from the rules.
	 */
	private final Map<String, String[]> written;

	/**
	 * The names a JDK reads as each region: a digit each for the kind of locale, the
	 * style (0 short, 1 full) and the time (0 standard, 1 daylight, 2 generic), then the
	 * name.
	 */
	private final Map<String, List<String>> read;

	private JavaZoneNames(Map<String, String[]> written, Map<String, List<String>> read) {
		this.written = written;
		this.read = read;
	}

	/** The names the jar ships. */
	static JavaZoneNames shipped() {
		return Holder.SHIPPED;
	}

	private static JavaZoneNames read() {
		String text;
		try (InputStream in = JavaZoneNames.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException(RESOURCE + " is not in the jar");
			}
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return parse(text);
	}

	/**
	 * Reads the file: comment lines, then for a region a line {@code w id|24 names|spans}
	 * and a line {@code r id|entry|...} of the names read as it.
	 */
	static JavaZoneNames parse(String text) {
		Map<String, String[]> written = new TreeMap<>();
		Map<String, List<String>> read = new TreeMap<>();
		for (String line : text.split("\n")) {
			if (line.length() < 2 || line.startsWith("# ")) {
				continue;
			}
			String[] fields = line.substring(2).split("\\|", -1);
			if (line.startsWith("w ")) {
				if (fields.length != NAMES + 2) {
					throw new IllegalStateException("a line of " + RESOURCE + " has no " + NAMES + " names: " + line);
				}
				written.put(fields[0], java.util.Arrays.copyOfRange(fields, 1, fields.length));
			}
			else if (line.startsWith("r ")) {
				read.put(fields[0], List.of(java.util.Arrays.copyOfRange(fields, 1, fields.length)));
			}
		}
		return new JavaZoneNames(written, read);
	}

	/** What ends the groups of a program's table of the names written. */
	static final char GROUPS = '\u0003';

	/**
	 * The names a program writes for the regions it holds: the groups of six names a kind
	 * of locale writes for a region, each once, its names separated by {@link #FIELD},
	 * ended by {@link #ENTRY}, the last by {@link #GROUPS}; then for each region its id,
	 * the number of its group of each kind (the root, English, British, Canadian) and its
	 * spans, separated by {@link #FIELD}, ended by {@link #ENTRY}. A name of the root's
	 * that is English's is empty, and so is one of its names of standard and daylight
	 * time in full that is its city's with {@code (+0)} or {@code (+1)}, as a British or
	 * a Canadian one that is English's is. A metazone's names are its zones', so of 562
	 * regions. 2,248 groups 827 differ: 90,405 characters written out, 53,865 so.
	 * @param held the regions the program holds, or {@code null} for all of them
	 */
	String written(@Nullable Collection<String> held) {
		Map<String, Integer> groups = new LinkedHashMap<>();
		StringBuilder entries = new StringBuilder();
		for (String id : held == null ? this.written.keySet() : new TreeSet<>(held)) {
			String[] names = this.written.get(id);
			if (names == null) {
				continue;
			}
			entries.append(id);
			for (int kind = 0; kind < 4; kind++) {
				StringBuilder group = new StringBuilder();
				for (int i = kind * 6; i < kind * 6 + 6; i++) {
					String name = names[i];
					if (i < 6) {
						String city = names[5];
						boolean derived = (i == 1 && name.equals(city + " (+0)"))
								|| (i == 3 && name.equals(city + " (+1)"));
						boolean english = i != 5 && i % 2 == 0 && name.equals(names[6 + i]);
						name = derived || english ? "" : name;
					}
					group.append(i == kind * 6 ? "" : FIELD).append(name);
				}
				Integer number = groups.putIfAbsent(group.toString(), groups.size());
				entries.append(FIELD).append(number == null ? groups.size() - 1 : number);
			}
			entries.append(FIELD).append(names[NAMES]).append(ENTRY);
		}
		StringBuilder table = new StringBuilder();
		groups.keySet().forEach(group -> table.append(group).append(ENTRY));
		return table.append(GROUPS).append(entries).toString();
	}

	/**
	 * A region's names read, each a digit for the kind of locale, the style and the time
	 * before the name, made fewer: a name read alike in several kinds is one entry, its
	 * first character the kinds' mask after {@code @}; a city of the root's read in full
	 * as the zone as a whole, with {@code (+0)} as standard time and with {@code (+1)} as
	 * daylight time is one entry of the time 3.
	 */
	private static List<String> compact(List<String> entries) {
		Set<String> all = new HashSet<>(entries);
		Map<String, Integer> masks = new LinkedHashMap<>();
		for (String entry : entries) {
			String rest = entry.substring(1);
			if (entry.startsWith("012") && all.contains("010" + entry.substring(3) + " (+0)")
					&& all.contains("011" + entry.substring(3) + " (+1)")) {
				rest = "13" + entry.substring(3);
			}
			else if (entry.startsWith("010") && entry.endsWith(" (+0)")
					&& all.contains("012" + entry.substring(3, entry.length() - 5))
					&& all.contains("011" + entry.substring(3, entry.length() - 5) + " (+1)")) {
				continue;
			}
			else if (entry.startsWith("011") && entry.endsWith(" (+1)")
					&& all.contains("012" + entry.substring(3, entry.length() - 5))
					&& all.contains("010" + entry.substring(3, entry.length() - 5) + " (+0)")) {
				continue;
			}
			masks.merge(rest, 1 << (entry.charAt(0) - '0'), (a, b) -> a | b);
		}
		List<String> compact = new ArrayList<>();
		masks.forEach((rest, mask) -> compact.add((char) ('@' + mask) + rest));
		return compact;
	}

	/**
	 * The names a program reads as the regions it holds: for each region the names a JDK
	 * reads as it, then -- where the region a JDK reads a name as is not held -- the
	 * names it writes for the regions it holds, read as the first of them that writes it.
	 * Each region's id, then its entries, separated by {@link #FIELD}, ended by
	 * {@link #ENTRY}.
	 * @param held the regions the program holds, or {@code null} for all of them
	 */
	String read(@Nullable Collection<String> held) {
		Set<String> regions = held == null ? this.written.keySet() : new TreeSet<>(held);
		Map<String, List<String>> entries = new LinkedHashMap<>();
		// what a JDK reads as any region, and what as the regions held
		Set<String> readable = new HashSet<>();
		this.read.values()
			.forEach(list -> list
				.forEach(entry -> readable.add(entry.charAt(0) + "" + entry.charAt(1) + entry.substring(3))));
		Set<String> known = new HashSet<>();
		for (String id : regions) {
			for (String entry : this.read.getOrDefault(id, List.of())) {
				entries.computeIfAbsent(id, first -> new ArrayList<>()).add(entry);
				known.add(entry.charAt(0) + "" + entry.charAt(1) + entry.substring(3));
			}
		}
		for (String id : regions) {
			String[] names = this.written.get(id);
			if (names == null) {
				continue;
			}
			// a name is read as the time of the last of its places (a JDK's way)
			Map<String, Character> own = new LinkedHashMap<>();
			for (int i = 0; i < NAMES; i++) {
				int kind = i / 6;
				String name = names[i].isEmpty() && kind > 1 ? names[6 + i % 6] : names[i];
				if (name.startsWith("GMT") || name.startsWith("UT")) {
					// an offset, or UTC: never read as a name
					continue;
				}
				String key = kind + "" + (i % 2) + name;
				if (readable.contains(key) && !known.contains(key)) {
					own.put(key, (char) ('0' + i % 6 / 2));
				}
			}
			own.forEach((key, time) -> {
				entries.computeIfAbsent(id, first -> new ArrayList<>())
					.add(key.substring(0, 2) + time + key.substring(2));
				known.add(key);
			});
		}
		StringBuilder table = new StringBuilder();
		entries.forEach((id, list) -> {
			table.append(id);
			compact(list).forEach(entry -> table.append(FIELD).append(entry));
			table.append(ENTRY);
		});
		return table.toString();
	}

}
