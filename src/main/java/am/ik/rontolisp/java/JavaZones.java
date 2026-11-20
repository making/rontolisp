package am.ik.rontolisp.java;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

/**
 * The regions of the tz database as a compiled program carries them: the names
 * {@code java.time.ZoneId} knows beyond the ones it computes, each with the changes of
 * its offset ({@code zone-rules.txt}, generated from a running JDK's public
 * {@code java.time.zone} by {@code JavaZoneRulesTest}; {@code .kb/java-frontend.md},
 * "java.time: the regions"). A program holds the regions it names, so the table it reads
 * is made here for each program, from the names the lowering found
 * ({@link JavaClasses#holds}).
 *
 * <p>
 * A region's rules are a text this class never reads: what it knows is which names share
 * one, and how the library's {@code java.time.zone.ZoneChanges} finds a name.
 */
final class JavaZones {

	private static final String RESOURCE = "zone-rules.txt";

	/**
	 * The first character an area of the table's head is written as: the areas are
	 * numbered from it, and no name starts with one of them.
	 */
	private static final char AREA = '#';

	/**
	 * How many areas a table names at most: up to the first letter a name starts with.
	 */
	private static final int AREAS = 'A' - AREA;

	private static final class Holder {

		static final JavaZones SHIPPED = read();

	}

	private final String version;

	/** The rules of each name, as their text. */
	private final Map<String, String> rules;

	/** The zones {@code ZoneId.SHORT_IDS} maps its short names to. */
	private final Map<String, String> shortIds;

	private JavaZones(String version, Map<String, String> rules, Map<String, String> shortIds) {
		this.version = version;
		this.rules = rules;
		this.shortIds = shortIds;
	}

	/** The table the jar ships. */
	static JavaZones shipped() {
		return Holder.SHIPPED;
	}

	private static JavaZones read() {
		String text;
		try (InputStream in = JavaZones.class.getResourceAsStream(RESOURCE)) {
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
	 * Reads the file: comment lines, the version of the tz database, the short names of
	 * {@code ZoneId.SHORT_IDS} (each {@code name=zone}), then a line for each set of
	 * rules -- the names that have it and its text, separated by spaces.
	 */
	static JavaZones parse(String text) {
		String version = "";
		Map<String, String> rules = new TreeMap<>();
		Map<String, String> shortIds = new TreeMap<>();
		for (String line : text.split("\n")) {
			if (line.isEmpty() || line.startsWith("# ")) {
				continue;
			}
			String[] words = line.split(" ");
			if (words[0].equals("tzdb")) {
				version = words[1];
				continue;
			}
			if (words[0].equals("short")) {
				for (int i = 1; i < words.length; i++) {
					int equals = words[i].indexOf('=');
					shortIds.put(words[i].substring(0, equals), words[i].substring(equals + 1));
				}
				continue;
			}
			for (int i = 0; i < words.length - 1; i++) {
				rules.put(words[i], words[words.length - 1]);
			}
		}
		return new JavaZones(version, rules, shortIds);
	}

	/** The version of the tz database the table was made of. */
	String version() {
		return this.version;
	}

	/** The short names of {@code ZoneId.SHORT_IDS} and the zones they stand for. */
	Map<String, String> shortIds() {
		return this.shortIds;
	}

	/**
	 * The short names as the library reads them: {@code name=zone}, separated by spaces.
	 */
	String shortIdsText() {
		StringBuilder text = new StringBuilder();
		this.shortIds
			.forEach((name, zone) -> text.append(text.isEmpty() ? "" : " ").append(name).append('=').append(zone));
		return text.toString();
	}

	/** Whether a name is a region of the table. */
	boolean has(String name) {
		return this.rules.containsKey(name);
	}

	/** The regions of the table a text names, anywhere in it. */
	Set<String> within(String text) {
		Set<String> named = new TreeSet<>();
		for (int at = 0; at < text.length(); at++) {
			for (String name : this.rules.keySet()) {
				if (text.startsWith(name, at)) {
					named.add(name);
				}
			}
		}
		return named;
	}

	Set<String> names() {
		return this.rules.keySet();
	}

	/**
	 * The table a program holding some regions reads: the areas its names are shortened
	 * by, each ended by a comma, a space, then for each set of rules the names that have
	 * it, each ended by {@code !}, the rules and a space. An area is the part of a name
	 * up to its last slash, written in a name as one character.
	 * @param held the names the program holds, or {@code null} for all of them
	 */
	String table(@Nullable Collection<String> held) {
		Set<String> names = held == null ? this.rules.keySet() : new TreeSet<>(held);
		Map<String, List<String>> sets = new LinkedHashMap<>();
		Map<String, Integer> shared = new TreeMap<>();
		for (String name : names) {
			String text = this.rules.get(name);
			if (text == null) {
				throw new IllegalArgumentException(name + " is no region of the tz database");
			}
			sets.computeIfAbsent(text, first -> new ArrayList<>()).add(name);
			shared.merge(area(name), 1, Integer::sum);
		}
		// an area is worth naming when the names lose more than its own text adds
		List<String> areas = new ArrayList<>(shared.keySet());
		areas.removeIf(area -> area.isEmpty() || saved(shared, area) <= area.length() + 1);
		areas.sort((a, b) -> saved(shared, b) - saved(shared, a));
		if (areas.size() > AREAS) {
			areas = areas.subList(0, AREAS);
		}
		StringBuilder table = new StringBuilder();
		for (String area : areas) {
			table.append(area).append(',');
		}
		table.append(' ');
		for (Map.Entry<String, List<String>> set : sets.entrySet()) {
			for (String name : set.getValue()) {
				int at = areas.indexOf(area(name));
				table.append(at < 0 ? name : (char) (AREA + at) + name.substring(area(name).length())).append('!');
			}
			table.append(set.getKey()).append(' ');
		}
		return table.toString();
	}

	private static int saved(Map<String, Integer> shared, String area) {
		return shared.getOrDefault(area, 0) * (area.length() - 1);
	}

	private static String area(String name) {
		return name.substring(0, name.lastIndexOf('/') + 1);
	}

}
