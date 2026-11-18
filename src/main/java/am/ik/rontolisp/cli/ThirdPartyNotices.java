package am.ik.rontolisp.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * {@code THIRD-PARTY-NOTICES.txt} read as data: a preamble, then one entry per project
 * whose code or data some files of rontolisp hold. An entry opens with a line of 80
 * {@code =}, its name on the next line, then {@code Key: value} fields (a value continued
 * on lines indented by two spaces, one path per line for {@code Files}), a line of 80
 * {@code -}, and the licence text up to the next entry.
 *
 * @param preamble the text before the first entry
 * @param entries the entries in file order
 */
record ThirdPartyNotices(String preamble, List<Entry> entries) {

	/** The resource the jar and the native binary carry the file as. */
	static final String RESOURCE = "META-INF/THIRD-PARTY-NOTICES.txt";

	/** What a compiled program may carry ({@code Ships: output}). */
	static final String OUTPUT = "output";

	/** What only the rontolisp jar and native binary carry ({@code Ships: tool}). */
	static final String TOOL = "tool";

	static final String RULE = "=".repeat(80);

	static final String DASH = "-".repeat(80);

	ThirdPartyNotices {
		entries = List.copyOf(entries);
	}

	/**
	 * One project.
	 *
	 * @param name the name a file's header cites it by
	 * @param fields the fields by key, a continued value joined with newlines
	 * @param text the licence text
	 * @param raw the whole entry as written, from its name to its last line
	 */
	record Entry(String name, Map<String, String> fields, String text, String raw) {

		Entry {
			fields = Map.copyOf(fields);
		}

		/**
		 * A field's value.
		 * @param key the key
		 * @return the value, or {@code null} when the entry has none
		 */
		@Nullable String field(String key) {
			return this.fields.get(key);
		}

		/**
		 * The repository paths the entry lists under {@code Files}.
		 * @return the paths, in order
		 */
		List<String> files() {
			String files = this.fields.get("Files");
			return files == null ? List.of() : files.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
		}

	}

	/**
	 * Parses the file.
	 * @param text the file's text
	 * @return the notices
	 * @throws IllegalArgumentException when an entry has no name, no field separator or a
	 * line that is no field
	 */
	static ThirdPartyNotices parse(String text) {
		String[] parts = text.split("(?m)^" + RULE + "\\R");
		List<Entry> entries = new ArrayList<>();
		for (int i = 1; i < parts.length; i++) {
			String raw = parts[i];
			int dash = raw.indexOf("\n" + DASH + "\n");
			if (dash < 0) {
				throw new IllegalArgumentException("third-party notice " + i + " has no line of 80 '-'");
			}
			List<String> head = raw.substring(0, dash).lines().toList();
			if (head.isEmpty() || head.getFirst().isBlank()) {
				throw new IllegalArgumentException("third-party notice " + i + " has no name");
			}
			Map<String, String> fields = new LinkedHashMap<>();
			String key = null;
			for (String line : head.subList(1, head.size())) {
				if (line.startsWith("  ") && key != null) {
					fields.merge(key, line.strip(), (value, more) -> value.isEmpty() ? more : value + "\n" + more);
					continue;
				}
				int colon = line.indexOf(':');
				if (colon <= 0) {
					throw new IllegalArgumentException(
							"third-party notice " + head.getFirst() + ": not a field: '" + line + "'");
				}
				key = line.substring(0, colon);
				fields.put(key, line.substring(colon + 1).strip());
			}
			entries.add(new Entry(head.getFirst().strip(), fields, raw.substring(dash + DASH.length() + 2), raw));
		}
		return new ThirdPartyNotices(parts[0], entries);
	}

	/**
	 * The entries whose {@code Ships} is one of {@code ships}.
	 * @param ships the values to keep
	 * @return those entries, in order
	 */
	List<Entry> shipping(String... ships) {
		List<String> wanted = List.of(ships);
		return this.entries.stream().filter(entry -> wanted.contains(entry.field("Ships"))).toList();
	}

}
