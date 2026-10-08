package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;

import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * One {@code deps.edn} read the way the oracle's tools.deps reads it ({@code clj}
 * 1.12.6's {@code clojure.tools.deps.edn/read-deps}): one EDN value, validated against
 * the oracle's spec ({@code clojure.tools.deps.specs}) and refused in its words
 * ({@code Error validating deps in <file>. Found: <value>, expected: <pred>, in:
 * <path>}), its unqualified lib names canonicalized ({@code foo} is {@code foo/foo}). The
 * whole map is read: {@code :paths}, {@code :deps}, {@code :aliases}, {@code :mvn/repos},
 * {@code :mvn/local-repo}, {@code :tools/usage}, {@code :deps/prep-lib}. Any other key is
 * ignored, like the oracle's open map (measured 2026-10-08: {@code :foo},
 * {@code :foo/bar} and {@code :deps/whatever} all pass). Several maps merge left to right
 * like {@code merge-edns}: a map value merges into the one before it, anything else
 * replaces it.
 */
final class ClojureDepsEdn {

	/**
	 * The oracle's root {@code deps.edn} ({@code clojure/tools/deps/deps.edn} of
	 * {@code clj} 1.12.6), the first map every project and every {@code :local/root}
	 * dependency merges: the default {@code :paths ["src"]} and the
	 * {@code org.clojure/clojure} dependency come from it.
	 */
	private static final String ROOT_TEXT = """
			{
			  :paths ["src"]

			  :deps {
			    org.clojure/clojure {:mvn/version "1.12.6"}
			  }

			  :aliases {
			    :deps {:replace-paths []
			           :replace-deps {org.clojure/tools.deps.cli {:mvn/version "0.31.162"}}
			           :ns-default clojure.tools.deps.cli.api
			           :ns-aliases {help clojure.tools.deps.cli.help}}
			    :test {:extra-paths ["test"]}
			  }

			  :mvn/repos {
			    "central" {:url "https://repo1.maven.org/maven2/"}
			    "clojars" {:url "https://repo.clojars.org/"}
			  }
			}
			""";

	/** The keys whose presence names a coordinate's type, per type. */
	private static final Map<String, List<String>> TYPE_KEYS = typeKeys();

	/** The map of a {@code deps.edn} that holds nothing (an empty file, {@code nil}). */
	static final DepsMap EMPTY = new DepsMap(null, null, null, null, null, null);

	/** The oracle's root map ({@link #ROOT_TEXT}), read once the fields above exist. */
	static final DepsMap ROOT = read(ROOT_TEXT, "clojure/tools/deps/deps.edn");

	private ClojureDepsEdn() {
	}

	private static Map<String, List<String>> typeKeys() {
		Map<String, List<String>> keys = new LinkedHashMap<>();
		keys.put("mvn", List.of(":mvn/version"));
		keys.put("local", List.of(":local/root"));
		keys.put("git", List.of(":git/url", ":git/sha", ":git/tag", ":sha"));
		return keys;
	}

	/**
	 * A library's name, a qualified symbol: {@code org.clojure/clojure}. Ordered like the
	 * oracle's symbols: by namespace, then by name.
	 *
	 * @param ns the part before the slash
	 * @param name the part after it
	 */
	record Lib(String ns, String name) implements Comparable<Lib> {

		/**
		 * The lib a symbol names: an unqualified one is canonicalized to
		 * {@code name/name}, like the oracle (which warns {@code DEPRECATED: Libs must be
		 * qualified}).
		 * @param symbol the symbol's spelling
		 * @return the lib
		 */
		static Lib of(String symbol) {
			int slash = symbol.indexOf('/');
			if (slash <= 0 || slash == symbol.length() - 1) {
				return new Lib(symbol, symbol);
			}
			return new Lib(symbol.substring(0, slash), symbol.substring(slash + 1));
		}

		/**
		 * The lib without a classifier ({@code gluegen-rt$natives-linux} is
		 * {@code gluegen-rt}), what an exclusion names.
		 * @return the base lib
		 */
		Lib base() {
			int dollar = this.name.indexOf('$');
			return dollar < 0 ? this : new Lib(this.ns, this.name.substring(0, dollar));
		}

		@Override
		public int compareTo(Lib other) {
			int byNs = this.ns.compareTo(other.ns);
			return byNs != 0 ? byNs : this.name.compareTo(other.name);
		}

		@Override
		public String toString() {
			return this.ns + "/" + this.name;
		}

	}

	/**
	 * A coordinate as written: its keys in order, each value as read. Printed in the
	 * oracle's words by {@link #print}.
	 *
	 * @param entries the keys (keyword spellings, {@code :mvn/version}) and values
	 */
	record Coord(SequencedMap<String, LispVal> entries) {

		/**
		 * A key's value.
		 * @param key the keyword spelling
		 * @return the value, or null when absent
		 */
		@Nullable LispVal get(String key) {
			return this.entries.get(key);
		}

		/**
		 * A string-valued key's string.
		 * @param key the keyword spelling
		 * @return the string, or null when absent or not a string
		 */
		@Nullable String string(String key) {
			return this.entries.get(key) instanceof LispString string ? string.value() : null;
		}

		/**
		 * The coordinate with one key set, in place when present (the oracle's
		 * {@code assoc}), else last.
		 * @param key the keyword spelling
		 * @param value the value
		 * @return the new coordinate
		 */
		Coord with(String key, LispVal value) {
			SequencedMap<String, LispVal> copy = new LinkedHashMap<>(this.entries);
			copy.put(key, value);
			return new Coord(copy);
		}

		/**
		 * The libs {@code :exclusions} names, canonicalized.
		 * @return the libs, or null when the coordinate has no {@code :exclusions}
		 */
		@Nullable Set<Lib> exclusions() {
			LispVal value = this.entries.get(":exclusions");
			if (value == null) {
				return null;
			}
			Set<Lib> out = new LinkedHashSet<>();
			for (LispVal element : elements(value)) {
				if (element instanceof LispSymbol symbol) {
					out.add(Lib.of(symbol.name()));
				}
			}
			return out;
		}

		/**
		 * The coordinate's type, from the keys present: {@code mvn}, {@code local} or
		 * {@code git}, refused in the oracle's words when no type's key or several types'
		 * keys are present.
		 * @return the type
		 */
		String type() {
			List<String> matches = new ArrayList<>();
			for (Map.Entry<String, List<String>> type : TYPE_KEYS.entrySet()) {
				for (String key : type.getValue()) {
					if (this.entries.containsKey(key)) {
						matches.add(type.getKey());
						break;
					}
				}
			}
			if (matches.isEmpty()) {
				throw new LispReadException("Coord of unknown type: " + print());
			}
			if (matches.size() > 1) {
				throw new LispReadException("Coord type is ambiguous: " + print());
			}
			return matches.get(0);
		}

		/**
		 * The coordinate printed the way the oracle prints it in a refusal.
		 * @return the text
		 */
		String print() {
			List<LispVal> items = new ArrayList<>();
			items.add(new LispSymbol("%hash-map"));
			for (Map.Entry<String, LispVal> entry : this.entries.entrySet()) {
				items.add(new LispSymbol(entry.getKey()));
				items.add(entry.getValue());
			}
			return ClojureEdn.print(ClojureLowerUtil.list(items));
		}

	}

	/**
	 * One {@code :paths} member: a directory, or an alias keyword whose value names more.
	 *
	 * @param value the path, or the keyword's spelling
	 * @param alias whether it is an alias keyword
	 */
	record PathRef(String value, boolean alias) {
	}

	/**
	 * A library's {@code :deps/prep-lib}: what must exist below its root before the
	 * oracle uses it.
	 *
	 * @param ensure the directory, relative to the library's root
	 */
	record PrepLib(String ensure) {
	}

	/**
	 * One {@code deps.edn} map; a key absent from the file is null.
	 *
	 * @param paths {@code :paths}
	 * @param deps {@code :deps}, each coordinate as written ({@code nil} is a null value)
	 * @param aliases {@code :aliases}, by keyword spelling, each value as read
	 * @param mvnRepos {@code :mvn/repos}, by repository id, each value as read
	 * @param mvnLocalRepo {@code :mvn/local-repo}
	 * @param prepLib {@code :deps/prep-lib}
	 */
	record DepsMap(@Nullable List<PathRef> paths, @Nullable SequencedMap<Lib, @Nullable Coord> deps,
			@Nullable SequencedMap<String, LispVal> aliases, @Nullable SequencedMap<String, LispVal> mvnRepos,
			@Nullable String mvnLocalRepo, @Nullable PrepLib prepLib) {
	}

	/**
	 * Reads one {@code deps.edn}: exactly one EDN value, validated against the oracle's
	 * spec. An empty file is the empty map, like the oracle's {@code read-edn}.
	 * @param text the file's text
	 * @param path its path, for the refusals
	 * @return the map
	 */
	static DepsMap read(String text, String path) {
		List<LispVal> datums;
		try {
			datums = ClojureReader.forEdn(text, path).readAll();
		}
		catch (LispReadException ex) {
			throw new LispReadException("Error reading edn. " + ex.getMessage());
		}
		if (datums.isEmpty()) {
			return EMPTY;
		}
		if (datums.size() > 1) {
			throw new LispReadException("Error reading edn. Expected edn to contain a single value. (" + path + ")");
		}
		LispVal value = datums.get(0);
		String duplicate = duplicateKey(value);
		if (duplicate != null) {
			throw new LispReadException("Error reading edn. Duplicate key: " + duplicate + " (" + path + ")");
		}
		if (ClojureLowerUtil.isSymbolNamed(value, "nil")) {
			return EMPTY;
		}
		new Validation(path).depsMap(value);
		return build(value);
	}

	/**
	 * Merges maps left to right like the oracle's {@code merge-edns}: a key's map value
	 * merges into the earlier one (a later entry replaces an earlier one in place), any
	 * other value replaces it, and an absent key keeps the earlier value.
	 * @param maps the maps, earliest first
	 * @return the merged map
	 */
	static DepsMap merge(List<DepsMap> maps) {
		List<PathRef> paths = null;
		SequencedMap<Lib, @Nullable Coord> deps = null;
		SequencedMap<String, LispVal> aliases = null;
		SequencedMap<String, LispVal> repos = null;
		String localRepo = null;
		PrepLib prepLib = null;
		for (DepsMap map : maps) {
			if (map.paths() != null) {
				paths = map.paths();
			}
			SequencedMap<Lib, @Nullable Coord> moreDeps = map.deps();
			if (moreDeps != null) {
				SequencedMap<Lib, @Nullable Coord> mergedDeps = new LinkedHashMap<>();
				if (deps != null) {
					mergedDeps.putAll(deps);
				}
				mergedDeps.putAll(moreDeps);
				deps = mergedDeps;
			}
			aliases = mergeMaps(aliases, map.aliases());
			repos = mergeMaps(repos, map.mvnRepos());
			if (map.mvnLocalRepo() != null) {
				localRepo = map.mvnLocalRepo();
			}
			if (map.prepLib() != null) {
				prepLib = map.prepLib();
			}
		}
		return new DepsMap(paths, deps, aliases, repos, localRepo, prepLib);
	}

	private static <K, V> @Nullable SequencedMap<K, V> mergeMaps(@Nullable SequencedMap<K, V> into,
			@Nullable SequencedMap<K, V> from) {
		if (from == null) {
			return into;
		}
		SequencedMap<K, V> out = into == null ? new LinkedHashMap<>() : new LinkedHashMap<>(into);
		out.putAll(from);
		return out;
	}

	/**
	 * The directories {@code :paths} names, its alias keywords resolved through the
	 * aliases recursively like the oracle's {@code chase-key}: an alias's collection
	 * contributes its strings and resolves its keywords; a keyword naming no alias, and
	 * anything else in a collection, contributes nothing; a value that is no collection
	 * (a number, a keyword) is the oracle's {@code Don't know how to create ISeq from}.
	 * @param map the merged map
	 * @return the directories, as written
	 */
	static List<String> flattenPaths(DepsMap map) {
		List<String> out = new ArrayList<>();
		List<PathRef> paths = map.paths();
		if (paths == null) {
			return out;
		}
		Map<String, LispVal> aliases = map.aliases() == null ? Map.of() : map.aliases();
		for (PathRef ref : paths) {
			if (!ref.alias()) {
				out.add(ref.value());
			}
			else {
				chaseAlias(aliases, ref.value(), out, new HashSet<>());
			}
		}
		return out;
	}

	private static void chaseAlias(Map<String, LispVal> aliases, String alias, List<String> out, Set<String> seen) {
		LispVal value = aliases.get(alias);
		if (value == null || ClojureLowerUtil.isSymbolNamed(value, "nil")) {
			return;
		}
		if (!seen.add(alias)) {
			throw new LispReadException("the :paths alias " + alias + " names itself");
		}
		if (value instanceof LispString) {
			return; // a string is a seq of characters, none of which names an alias
		}
		List<LispVal> items = collectionItems(value);
		if (items == null) {
			throw new LispReadException("Don't know how to create ISeq from: " + hostClassOf(value));
		}
		for (LispVal item : items) {
			if (item instanceof LispString string) {
				out.add(string.value());
			}
			else if (item instanceof LispSymbol keyword && keyword.name().startsWith(":")) {
				chaseAlias(aliases, keyword.name(), out, seen);
			}
		}
		seen.remove(alias);
	}

	/** The members of a vector, list or set datum, or null for anything else. */
	private static @Nullable List<LispVal> collectionItems(LispVal value) {
		if (value instanceof LispNil) {
			return List.of();
		}
		List<LispVal> items = ClojureLowerUtil.items(value);
		if (items == null) {
			return null;
		}
		if (!items.isEmpty() && (items.get(0) == ClojureReader.VECTOR
				|| ClojureLowerUtil.isSymbolNamed(items.get(0), "%hash-set"))) {
			return items.subList(1, items.size());
		}
		if (!items.isEmpty() && ClojureLowerUtil.isSymbolNamed(items.get(0), "%hash-map")) {
			return List.of(); // its entries are pairs, none a path or an alias
		}
		return items;
	}

	private static String hostClassOf(LispVal value) {
		if (value instanceof LispInteger) {
			return "java.lang.Long";
		}
		if (value instanceof LispDouble) {
			return "java.lang.Double";
		}
		if (value instanceof LispChar) {
			return "java.lang.Character";
		}
		if (value instanceof LispSymbol symbol) {
			if (symbol.name().startsWith(":")) {
				return "clojure.lang.Keyword";
			}
			if (symbol.name().equals("true") || symbol.name().equals("false")) {
				return "java.lang.Boolean";
			}
			return "clojure.lang.Symbol";
		}
		return value.getClass().getSimpleName();
	}

	private static DepsMap build(LispVal value) {
		List<PathRef> paths = null;
		SequencedMap<Lib, @Nullable Coord> deps = null;
		SequencedMap<String, LispVal> aliases = null;
		SequencedMap<String, LispVal> repos = null;
		String localRepo = null;
		PrepLib prepLib = null;
		List<LispVal> kvs = mapEntries(value);
		for (int i = 0; i + 1 < kvs.size(); i += 2) {
			String key = ((LispSymbol) kvs.get(i)).name();
			LispVal v = kvs.get(i + 1);
			if (isNil(v)) {
				continue; // merge-or-replace: a nil value keeps the earlier one
			}
			switch (key) {
				case ":paths" -> {
					paths = new ArrayList<>();
					for (LispVal element : elements(v)) {
						paths.add(element instanceof LispString string ? new PathRef(string.value(), false)
								: new PathRef(((LispSymbol) element).name(), true));
					}
				}
				case ":deps" -> deps = libMap(v);
				case ":aliases" -> {
					aliases = new LinkedHashMap<>();
					List<LispVal> entries = mapEntries(v);
					for (int j = 0; j + 1 < entries.size(); j += 2) {
						aliases.put(((LispSymbol) entries.get(j)).name(), entries.get(j + 1));
					}
				}
				case ":mvn/repos" -> {
					repos = new LinkedHashMap<>();
					List<LispVal> entries = mapEntries(v);
					for (int j = 0; j + 1 < entries.size(); j += 2) {
						repos.put(((LispString) entries.get(j)).value(), entries.get(j + 1));
					}
				}
				case ":mvn/local-repo" -> localRepo = ((LispString) v).value();
				case ":deps/prep-lib" -> prepLib = new PrepLib(stringValue(v, ":ensure"));
				default -> {
					// :tools/usage and any key the oracle's map leaves open: nothing to
					// build a classpath from
				}
			}
		}
		return new DepsMap(paths, deps, aliases, repos, localRepo, prepLib);
	}

	/**
	 * A {@code lib -> coordinate} map ({@code :deps}), each unqualified lib name
	 * canonicalized; a later lib canonicalizing to an earlier one replaces it, like the
	 * oracle's {@code assoc}.
	 * @param value the map datum, already validated
	 * @return the libs and their coordinates
	 */
	static SequencedMap<Lib, @Nullable Coord> libMap(LispVal value) {
		SequencedMap<Lib, @Nullable Coord> out = new LinkedHashMap<>();
		List<LispVal> entries = mapEntries(value);
		for (int j = 0; j + 1 < entries.size(); j += 2) {
			Lib lib = Lib.of(((LispSymbol) entries.get(j)).name());
			LispVal coord = entries.get(j + 1);
			out.put(lib, isNil(coord) ? null : coord(coord));
		}
		return out;
	}

	private static Coord coord(LispVal value) {
		SequencedMap<String, LispVal> entries = new LinkedHashMap<>();
		List<LispVal> kvs = mapEntries(value);
		for (int i = 0; i + 1 < kvs.size(); i += 2) {
			String key = kvs.get(i) instanceof LispSymbol symbol ? symbol.name() : ClojureEdn.print(kvs.get(i));
			entries.put(key, kvs.get(i + 1));
		}
		return new Coord(entries);
	}

	private static String stringValue(LispVal map, String key) {
		List<LispVal> kvs = mapEntries(map);
		for (int i = 0; i + 1 < kvs.size(); i += 2) {
			if (ClojureLowerUtil.isSymbolNamed(kvs.get(i), key) && kvs.get(i + 1) instanceof LispString string) {
				return string.value();
			}
		}
		throw new IllegalStateException("validated " + key + " is missing");
	}

	/** The key/value items of a map datum ({@code (%hash-map k v ...)}). */
	static List<LispVal> mapEntries(LispVal value) {
		List<LispVal> items = ClojureLowerUtil.items(value);
		if (items == null || items.isEmpty()) {
			return List.of();
		}
		return items.subList(1, items.size());
	}

	/** The members of a vector, list or set datum; none for anything else. */
	static List<LispVal> elements(LispVal value) {
		List<LispVal> items = collectionItems(value);
		return items == null ? List.of() : items;
	}

	static boolean isNil(LispVal value) {
		return ClojureLowerUtil.isSymbolNamed(value, "nil");
	}

	private static boolean isMap(LispVal value) {
		List<LispVal> items = ClojureLowerUtil.items(value);
		return items != null && !items.isEmpty() && ClojureLowerUtil.isSymbolNamed(items.get(0), "%hash-map");
	}

	private static boolean isVector(LispVal value) {
		List<LispVal> items = ClojureLowerUtil.items(value);
		return items != null && !items.isEmpty() && items.get(0) == ClojureReader.VECTOR;
	}

	/** Whether a datum is a collection, the oracle's {@code coll?}. */
	private static boolean isCollection(LispVal value) {
		if (value instanceof LispNil) {
			return true; // the empty list
		}
		List<LispVal> items = ClojureLowerUtil.items(value);
		return items != null && (items.isEmpty() || !(items.get(0) == ClojureReader.TAGGED));
	}

	/**
	 * The first key a map or set datum holds twice, anywhere in the value, by its
	 * spelling -- the oracle's EDN reader's {@code Duplicate key}.
	 */
	private static @Nullable String duplicateKey(LispVal value) {
		List<LispVal> items = ClojureLowerUtil.items(value);
		if (items == null || items.isEmpty()) {
			return null;
		}
		boolean map = ClojureLowerUtil.isSymbolNamed(items.get(0), "%hash-map");
		boolean set = ClojureLowerUtil.isSymbolNamed(items.get(0), "%hash-set");
		if (map || set) {
			Set<String> seen = new HashSet<>();
			for (int i = 1; i < items.size(); i += map ? 2 : 1) {
				String spelling = ClojureEdn.print(items.get(i));
				if (!seen.add(spelling)) {
					return spelling;
				}
			}
		}
		for (LispVal item : items) {
			String inner = duplicateKey(item);
			if (inner != null) {
				return inner;
			}
		}
		return null;
	}

	/**
	 * The oracle's {@code clojure.tools.deps.specs/deps-map}, walked in map order: the
	 * first value its spec refuses is reported with the path to it, in the oracle's
	 * words.
	 */
	private static final class Validation {

		private final String file;

		Validation(String file) {
			this.file = file;
		}

		void depsMap(LispVal value) {
			List<LispVal> in = new ArrayList<>();
			if (!isMap(value)) {
				fail(value, "map?", in);
			}
			List<LispVal> kvs = mapEntries(value);
			for (int i = 0; i + 1 < kvs.size(); i += 2) {
				LispVal key = kvs.get(i);
				LispVal v = kvs.get(i + 1);
				if (!(key instanceof LispSymbol keyword)) {
					continue;
				}
				List<LispVal> at = List.of(key);
				switch (keyword.name()) {
					case ":paths" -> paths(v, at);
					case ":deps" -> libCoords(v, at);
					case ":aliases" -> aliases(v, at);
					case ":mvn/repos" -> repos(v, at);
					case ":mvn/local-repo" -> string(v, at);
					case ":tools/usage" -> toolsUsage(v, at);
					case ":deps/prep-lib" -> prepLib(v, at);
					default -> qualifiedKey(keyword.name(), v, at);
				}
			}
		}

		private void paths(LispVal value, List<LispVal> in) {
			if (!isVector(value)) {
				fail(value, "vector?", in);
			}
			List<LispVal> items = elements(value);
			for (int i = 0; i < items.size(); i++) {
				LispVal item = items.get(i);
				if (!(item instanceof LispString)
						&& !(item instanceof LispSymbol keyword && keyword.name().startsWith(":"))) {
					fail(item, "string?", with(in, new LispInteger(i)));
				}
			}
		}

		private void libCoords(LispVal value, List<LispVal> in) {
			if (!isMap(value)) {
				fail(value, "map?", in);
			}
			List<LispVal> kvs = mapEntries(value);
			for (int i = 0; i + 1 < kvs.size(); i += 2) {
				LispVal lib = kvs.get(i);
				if (!(lib instanceof LispSymbol symbol) || symbol.name().startsWith(":") || isNil(lib)
						|| symbol.name().equals("true") || symbol.name().equals("false")) {
					fail(lib, "symbol?", with(in, lib, new LispInteger(0)));
				}
				coord(kvs.get(i + 1), with(in, lib, new LispInteger(1)));
			}
		}

		private void coord(LispVal value, List<LispVal> in) {
			if (isNil(value)) {
				return;
			}
			if (!isMap(value)) {
				fail(value, "map?", in);
			}
			List<LispVal> kvs = mapEntries(value);
			for (int i = 0; i + 1 < kvs.size(); i += 2) {
				if (!(kvs.get(i) instanceof LispSymbol key)) {
					continue;
				}
				LispVal v = kvs.get(i + 1);
				List<LispVal> at = with(in, key);
				switch (key.name()) {
					case ":exclusions" -> {
						if (!isCollection(v)) {
							fail(v, "coll?", at);
						}
						List<LispVal> entries = isMap(v) ? mapEntries(v) : List.of();
						if (entries.size() >= 2) {
							// a map's members are its entries, none of them a symbol
							fail(ClojureLowerUtil.list(ClojureReader.VECTOR, entries.get(0), entries.get(1)), "symbol?",
									with(at, new LispInteger(0)));
						}
						List<LispVal> excluded = elements(v);
						for (int j = 0; j < excluded.size(); j++) {
							LispVal lib = excluded.get(j);
							if (!(lib instanceof LispSymbol symbol) || symbol.name().startsWith(":")) {
								fail(lib, "symbol?", with(at, new LispInteger(j)));
							}
						}
					}
					default -> qualifiedKey(key.name(), v, at);
				}
			}
		}

		/**
		 * A qualified key the oracle's spec registers is checked wherever a map holds it
		 * ({@code s/keys} checks every registered key present).
		 */
		private void qualifiedKey(String key, LispVal value, List<LispVal> in) {
			switch (key) {
				case ":mvn/version", ":local/root", ":git/url", ":git/sha", ":git/tag", ":deps/root",
						":mvn/local-repo" ->
					string(value, in);
				case ":deps/manifest" -> {
					if (!(value instanceof LispSymbol keyword && keyword.name().startsWith(":"))) {
						fail(value, "keyword?", in);
					}
				}
				default -> {
					// open: any other key passes
				}
			}
		}

		private void aliases(LispVal value, List<LispVal> in) {
			if (!isMap(value)) {
				fail(value, "map?", in);
			}
			List<LispVal> kvs = mapEntries(value);
			for (int i = 0; i + 1 < kvs.size(); i += 2) {
				LispVal alias = kvs.get(i);
				if (!(alias instanceof LispSymbol keyword && keyword.name().startsWith(":"))) {
					fail(alias, "keyword?", with(in, alias, new LispInteger(0)));
				}
			}
		}

		private void repos(LispVal value, List<LispVal> in) {
			if (!isMap(value)) {
				fail(value, "map?", in);
			}
			List<LispVal> kvs = mapEntries(value);
			for (int i = 0; i + 1 < kvs.size(); i += 2) {
				LispVal id = kvs.get(i);
				if (!(id instanceof LispString)) {
					fail(id, "string?", with(in, id, new LispInteger(0)));
				}
				LispVal repo = kvs.get(i + 1);
				List<LispVal> at = with(in, id, new LispInteger(1));
				if (isNil(repo)) {
					continue;
				}
				if (!isMap(repo)) {
					fail(repo, "map?", at);
				}
				List<LispVal> entries = mapEntries(repo);
				for (int j = 0; j + 1 < entries.size(); j += 2) {
					LispVal key = entries.get(j);
					LispVal v = entries.get(j + 1);
					if (ClojureLowerUtil.isSymbolNamed(key, ":url")) {
						string(v, with(at, key));
					}
					else if (ClojureLowerUtil.isSymbolNamed(key, ":releases")
							|| ClojureLowerUtil.isSymbolNamed(key, ":snapshots")) {
						repoPolicy(v, with(at, key));
					}
				}
			}
		}

		private void repoPolicy(LispVal value, List<LispVal> in) {
			if (!isMap(value)) {
				fail(value, "map?", in);
			}
			List<LispVal> kvs = mapEntries(value);
			for (int i = 0; i + 1 < kvs.size(); i += 2) {
				LispVal key = kvs.get(i);
				LispVal v = kvs.get(i + 1);
				List<LispVal> at = with(in, key);
				if (ClojureLowerUtil.isSymbolNamed(key, ":enabled")
						&& !(ClojureLowerUtil.isSymbolNamed(v, "true") || ClojureLowerUtil.isSymbolNamed(v, "false"))) {
					fail(v, "boolean?", at);
				}
				if (ClojureLowerUtil.isSymbolNamed(key, ":update") && !(v instanceof LispInteger)
						&& !(v instanceof LispSymbol policy
								&& List.of(":daily", ":always", ":never").contains(policy.name()))) {
					fail(v, "#{:daily :always :never}", at);
				}
				if (ClojureLowerUtil.isSymbolNamed(key, ":checksum") && !(v instanceof LispSymbol policy
						&& List.of(":warn", ":fail", ":ignore").contains(policy.name()))) {
					fail(v, "#{:warn :fail :ignore}", at);
				}
			}
		}

		private void toolsUsage(LispVal value, List<LispVal> in) {
			if (!isMap(value)) {
				fail(value, "map?", in);
			}
			List<LispVal> kvs = mapEntries(value);
			for (int i = 0; i + 1 < kvs.size(); i += 2) {
				LispVal key = kvs.get(i);
				LispVal v = kvs.get(i + 1);
				if (ClojureLowerUtil.isSymbolNamed(key, ":ns-default") && !isSimpleSymbol(v)) {
					fail(v, "simple-symbol?", with(in, key));
				}
				if (ClojureLowerUtil.isSymbolNamed(key, ":ns-aliases")) {
					if (!isMap(v)) {
						fail(v, "map?", with(in, key));
					}
					List<LispVal> aliases = mapEntries(v);
					for (int j = 0; j + 1 < aliases.size(); j += 2) {
						for (int part = 0; part < 2; part++) {
							if (!isSimpleSymbol(aliases.get(j + part))) {
								fail(aliases.get(j + part), "simple-symbol?",
										with(in, key, aliases.get(j), new LispInteger(part)));
							}
						}
					}
				}
			}
		}

		private void prepLib(LispVal value, List<LispVal> in) {
			if (!isMap(value)) {
				fail(value, "map?", in);
			}
			List<LispVal> kvs = mapEntries(value);
			for (String required : List.of(":ensure", ":alias", ":fn")) {
				boolean present = false;
				for (int i = 0; i + 1 < kvs.size(); i += 2) {
					if (ClojureLowerUtil.isSymbolNamed(kvs.get(i), required)) {
						present = true;
					}
				}
				if (!present) {
					fail(value, "(contains? % " + required + ")", in);
				}
			}
			for (int i = 0; i + 1 < kvs.size(); i += 2) {
				LispVal key = kvs.get(i);
				LispVal v = kvs.get(i + 1);
				if (ClojureLowerUtil.isSymbolNamed(key, ":ensure")) {
					string(v, with(in, key));
				}
				if (ClojureLowerUtil.isSymbolNamed(key, ":alias")
						&& !(v instanceof LispSymbol alias && alias.name().startsWith(":"))) {
					fail(v, "keyword?", with(in, key));
				}
				if (ClojureLowerUtil.isSymbolNamed(key, ":fn")
						&& !(v instanceof LispSymbol fn && !fn.name().startsWith(":") && !isNil(v))) {
					fail(v, "symbol?", with(in, key));
				}
			}
		}

		private void string(LispVal value, List<LispVal> in) {
			if (!(value instanceof LispString)) {
				fail(value, "string?", in);
			}
		}

		private static boolean isSimpleSymbol(LispVal value) {
			return value instanceof LispSymbol symbol && !symbol.name().startsWith(":") && !isNil(value)
					&& symbol.name().indexOf('/') < 0;
		}

		private static List<LispVal> with(List<LispVal> in, LispVal... more) {
			List<LispVal> out = new ArrayList<>(in);
			out.addAll(List.of(more));
			return out;
		}

		private void fail(LispVal found, String expected, List<LispVal> in) {
			List<LispVal> path = new ArrayList<>();
			path.add(ClojureReader.VECTOR);
			path.addAll(in);
			throw new LispReadException("Error validating deps in " + this.file + ". Found: " + ClojureEdn.print(found)
					+ ", expected: " + expected + ", in: " + ClojureEdn.print(ClojureLowerUtil.list(path)));
		}

	}

}
