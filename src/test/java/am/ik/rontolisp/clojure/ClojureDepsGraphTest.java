package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;

import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.clojure.ClojureDepsEdn.Coord;
import am.ik.rontolisp.clojure.ClojureDepsEdn.DepsMap;
import am.ik.rontolisp.clojure.ClojureDepsEdn.Lib;
import am.ik.rontolisp.clojure.ClojureDepsGraph.Contribution;
import am.ik.rontolisp.clojure.ClojureDepsGraph.Dep;
import am.ik.rontolisp.clojure.ClojureDepsGraph.Root;
import am.ik.rontolisp.clojure.ClojureDepsGraph.Selected;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The selection: every expected order is the oracle's classpath ({@code clj} 1.12.6,
 * {@code clj -Srepro -Spath}, measured 2026-10-08) with its jars spelled as libraries.
 * Graphs of Maven coordinates run over a fake repository holding the same POMs the oracle
 * resolved from a {@code file:} repository (newest wins, the orphans of a replaced
 * version, exclusions narrowed by a second visit, scopes already filtered); graphs of
 * {@code :local/root} directories run through the real procurer over files in memory,
 * read back as the source path's roots.
 */
class ClojureDepsGraphTest {

	/** The POMs: each {@code lib version} with its dependencies. */
	private static final Map<String, List<Dep>> REPO = repo();

	private static Map<String, List<Dep>> repo() {
		Map<String, List<Dep>> repo = new LinkedHashMap<>();
		repo.put("org.clojure/clojure 1.12.6",
				List.of(mvn("org.clojure/spec.alpha", "0.5.238"), mvn("org.clojure/core.specs.alpha", "0.4.74")));
		repo.put("fake/a1 1.0", List.of(mvn("fake/c", "1.0")));
		repo.put("fake/b1 1.0", List.of(mvn("fake/c", "2.0")));
		repo.put("fake/a2 1.0", List.of(mvn("fake/x", "1.0")));
		repo.put("fake/b2 1.0", List.of(mvn("fake/x", "2.0")));
		repo.put("fake/x 1.0", List.of(mvn("fake/y", "1.0")));
		repo.put("fake/a3 1.0", List.of(mvn("fake/x", "1.0")));
		repo.put("fake/c3 1.0", List.of(mvn("fake/d3", "1.0")));
		repo.put("fake/d3 1.0", List.of(mvn("fake/e3", "1.0")));
		repo.put("fake/e3 1.0", List.of(mvn("fake/x", "2.0")));
		repo.put("fake/a4 1.0", List.of(mvn("fake/x4", "1.0", "fake/z4")));
		repo.put("fake/b4 1.0", List.of(mvn("fake/x4", "1.0")));
		repo.put("fake/b4c 1.0", List.of(mvn("fake/x4", "1.0", "fake/z4")));
		repo.put("fake/x4 1.0", List.of(mvn("fake/z4", "1.0")));
		repo.put("fake/a5 1.0", List.of(mvn("fake/c", "2.0")));
		repo.put("fake/a6 1.0", List.of(mvn("fake/c6", "1.0")));
		repo.put("fake/b6 1.0", List.of(mvn("fake/c6", "1.0.0")));
		repo.put("fake/a7 1.0", List.of(mvn("fake/c7", "2.0-beta1")));
		repo.put("fake/b7 1.0", List.of(mvn("fake/c7", "2.0-rc1")));
		repo.put("fake/a8 1.0", List.of(mvn("fake/x8", "1.0", "fake/z8")));
		repo.put("fake/x8 1.0", List.of(mvn("fake/z8", "1.0"), mvn("fake/w8", "1.0")));
		repo.put("fake.z/top 1.0", List.of(mvn("fake/m10", "1.0"), mvn("alpha/n10", "1.0")));
		repo.put("alpha/top 1.0", List.of(mvn("fake/m10", "1.0")));
		repo.put("fake/m10 1.0", List.of(mvn("fake/deep", "1.0")));
		repo.put("fake/a12 1.0", List.of(mvn("fake/x", "1.0")));
		repo.put("fake/b12 1.0", List.of(mvn("fake/k12", "1.0")));
		repo.put("fake/k12 1.0", List.of(mvn("fake/x", "2.0")));
		repo.put("fake/c12 1.0", List.of(mvn("fake/x", "1.0")));
		repo.put("fake/cy1 1.0", List.of(mvn("fake/cy2", "1.0")));
		repo.put("fake/cy2 1.0", List.of(mvn("fake/cy1", "1.0")));
		return repo;
	}

	private static final String SPECS = "org.clojure/core.specs.alpha 0.4.74, org.clojure/spec.alpha 0.5.238";

	@Test
	void theNewestVersionAcrossTheTreeWins() {
		assertThat(selected("fake/a1 1.0", "fake/b1 1.0"))
			.isEqualTo("fake/a1 1.0, fake/b1 1.0, org.clojure/clojure 1.12.6, fake/c 2.0, " + SPECS);
		assertThat(selected("fake/a7 1.0", "fake/b7 1.0"))
			.isEqualTo("fake/a7 1.0, fake/b7 1.0, org.clojure/clojure 1.12.6, fake/c7 2.0-rc1, " + SPECS);
	}

	@Test
	void aTopLevelDepWinsOverANewerTransitiveOne() {
		assertThat(selected("fake/a5 1.0", "fake/c 1.0"))
			.isEqualTo("fake/a5 1.0, fake/c 1.0, org.clojure/clojure 1.12.6, " + SPECS);
	}

	@Test
	void anEqualVersionSpelledDifferentlyKeepsTheFirstSeen() {
		assertThat(selected("fake/a6 1.0", "fake/b6 1.0"))
			.isEqualTo("fake/a6 1.0, fake/b6 1.0, org.clojure/clojure 1.12.6, fake/c6 1.0, " + SPECS);
		assertThat(selected("fake/b6 1.0", "fake/a6 1.0"))
			.isEqualTo("fake/a6 1.0, fake/b6 1.0, org.clojure/clojure 1.12.6, fake/c6 1.0.0, " + SPECS);
	}

	@Test
	void whatOnlyAReplacedVersionBroughtInIsDropped() {
		// the older x's y was never queued under a selected parent...
		assertThat(selected("fake/a2 1.0", "fake/b2 1.0"))
			.isEqualTo("fake/a2 1.0, fake/b2 1.0, org.clojure/clojure 1.12.6, fake/x 2.0, " + SPECS);
		// ...or was selected and is deselected when the newer x turns up deeper
		assertThat(selected("fake/a3 1.0", "fake/c3 1.0")).isEqualTo("fake/a3 1.0, fake/c3 1.0, "
				+ "org.clojure/clojure 1.12.6, fake/d3 1.0, " + SPECS + ", fake/e3 1.0, fake/x 2.0");
		assertThat(selected("fake/a12 1.0", "fake/b12 1.0", "fake/c12 1.0")).isEqualTo("fake/a12 1.0, fake/b12 1.0, "
				+ "fake/c12 1.0, org.clojure/clojure 1.12.6, fake/k12 1.0, " + SPECS + ", fake/x 2.0");
	}

	@Test
	void exclusionsDropALibraryBelowTheCoordinateNamingThem() {
		// a POM's exclusion, and a top-level one reaching two levels down
		assertThat(selected("fake/a8 1.0"))
			.isEqualTo("fake/a8 1.0, org.clojure/clojure 1.12.6, fake/x8 1.0, " + SPECS + ", fake/w8 1.0");
		Map<String, Coord> top = new LinkedHashMap<>();
		top.put("fake/a3", coord(":mvn/version", "1.0", "fake/y"));
		assertThat(selected(top)).isEqualTo("fake/a3 1.0, org.clojure/clojure 1.12.6, fake/x 1.0, " + SPECS);
	}

	@Test
	void aSecondVisitWithoutTheExclusionQueuesWhatTheFirstCut() {
		String both = "fake/a4 1.0, fake/b4 1.0, org.clojure/clojure 1.12.6, fake/x4 1.0, " + SPECS + ", fake/z4 1.0";
		assertThat(selected("fake/a4 1.0", "fake/b4 1.0")).isEqualTo(both);
		assertThat(selected("fake/b4 1.0", "fake/a4 1.0")).isEqualTo(both);
		assertThat(selected("fake/a4 1.0", "fake/b4c 1.0"))
			.isEqualTo("fake/a4 1.0, fake/b4c 1.0, org.clojure/clojure 1.12.6, fake/x4 1.0, " + SPECS);
	}

	@Test
	void theOrderIsTopDownThenByThePathFromTheTop() {
		// a group sorts by its namespace, alpha/ before fake.z/ before org.clojure/; a
		// library at two paths sits at the first
		assertThat(selected("fake.z/top 1.0", "alpha/top 1.0")).isEqualTo("alpha/top 1.0, fake.z/top 1.0, "
				+ "org.clojure/clojure 1.12.6, fake/m10 1.0, alpha/n10 1.0, " + SPECS + ", fake/deep 1.0");
	}

	@Test
	void aCycleStopsAtTheLibraryAlreadySelected() {
		assertThat(selected("fake/cy1 1.0"))
			.isEqualTo("fake/cy1 1.0, org.clojure/clojure 1.12.6, fake/cy2 1.0, " + SPECS);
	}

	@Test
	void aTopLevelLocalRootWinsOverAMavenOneAndTwoTransitiveOnesAreNoVersionsOfEachOther() {
		MemoryClojureFiles files = new MemoryClojureFiles(
				Map.of("locc/deps.edn", "{}", "la/deps.edn", "{:deps {fake/c {:local/root \"../locc\"}}}"));
		Map<String, Coord> top = new LinkedHashMap<>();
		top.put("fake/a1", coord(":mvn/version", "1.0"));
		top.put("fake/c", coord(":local/root", "locc"));
		assertThat(selected(top, files)).isEqualTo("fake/a1 1.0, fake/c locc, org.clojure/clojure 1.12.6, " + SPECS);
		Map<String, Coord> transitive = new LinkedHashMap<>();
		transitive.put("fake/a1", coord(":mvn/version", "1.0"));
		transitive.put("my/la", coord(":local/root", "la"));
		assertThatThrownBy(() -> selected(transitive, files)).isInstanceOf(LispReadException.class)
			.hasMessage("Unable to compare versions for fake/c: {:local/root \"locc\", :deps/manifest :deps, "
					+ ":deps/root \"locc\"} and {:mvn/version \"1.0\", :deps/manifest :mvn}");
	}

	@Test
	void localRootsGoOnTheSourcePathInTheOraclesOrder() {
		// a/ depends on b/ (default :paths of a, its own of b)
		assertThat(roots("{:deps {my/a {:local/root \"a\"}}}", "a/deps.edn", "{:deps {my/b {:local/root \"../b\"}}}",
				"b/deps.edn", "{:paths [\"src\" \"res\"]}"))
			.isEqualTo(": ., src, a/src, b/src, b/res");
		// top deps alphabetically, then each depth by the path from the top
		assertThat(
				roots("{:deps {z/z {:local/root \"z\"} a/a {:local/root \"a\"} m/m {:local/root \"m\"}}}", "z/deps.edn",
						"{:deps {b/b {:local/root \"../b\"}}}", "a/deps.edn", "{:deps {y/y {:local/root \"../y\"}}}",
						"m/deps.edn", "{:deps {}}", "b/deps.edn", "{}", "y/deps.edn", "{}"))
			.isEqualTo(": ., src, a/src, m/src, z/src, y/src, b/src");
		// one directory reached by two spellings and two parents is one library
		assertThat(roots("{:deps {my/a {:local/root \"a/../a\"} my/b {:local/root \"b\"}}}", "a/deps.edn",
				"{:deps {my/c {:local/root \"../c\"}}}", "b/deps.edn", "{:deps {my/c {:local/root \"../c/\"}}}",
				"c/deps.edn", "{}"))
			.isEqualTo(": ., src, a/src, b/src, c/src");
		// the shallowest path places a library reached at two depths
		assertThat(roots("{:deps {my/z {:local/root \"z\"} my/a {:local/root \"a\"}}}", "z/deps.edn",
				"{:deps {my/c {:local/root \"../c\"}}}", "a/deps.edn", "{:deps {my/q {:local/root \"../q\"}}}",
				"q/deps.edn", "{:deps {my/c {:local/root \"../c\"}}}", "c/deps.edn", "{}"))
			.isEqualTo(": ., src, a/src, z/src, q/src, c/src");
	}

	@Test
	void topLevelWinsExclusionsAndCyclesHoldForLocalRoots() {
		assertThat(roots("{:deps {my/a {:local/root \"a\"} my/c {:local/root \"c2\"}}}", "a/deps.edn",
				"{:deps {my/c {:local/root \"../c1\"}}}", "c1/deps.edn", "{}", "c2/deps.edn", "{}"))
			.isEqualTo(": ., src, a/src, c2/src");
		assertThat(roots("{:deps {my/a {:local/root \"a\" :exclusions [my/c]}}}", "a/deps.edn",
				"{:deps {my/c {:local/root \"../c\"} my/d {:local/root \"../d\"}}}", "c/deps.edn", "{}", "d/deps.edn",
				"{}"))
			.isEqualTo(": ., src, a/src, d/src");
		// excluded under a, brought in by b
		assertThat(roots("{:deps {my/a {:local/root \"a\" :exclusions [my/c]} my/b {:local/root \"b\"}}}", "a/deps.edn",
				"{:deps {my/c {:local/root \"../c\"}}}", "b/deps.edn", "{:deps {my/c {:local/root \"../c\"}}}",
				"c/deps.edn", "{}"))
			.isEqualTo(": ., src, a/src, b/src, c/src");
		// an unqualified exclusion names name/name, two levels down
		assertThat(roots("{:deps {my/a {:local/root \"a\"}}}", "a/deps.edn",
				"{:deps {my/b {:local/root \"../b\" :exclusions [c]}}}", "b/deps.edn",
				"{:deps {c/c {:local/root \"../c\"} my/d {:local/root \"../d\"}}}", "c/deps.edn", "{}", "d/deps.edn",
				"{}"))
			.isEqualTo(": ., src, a/src, b/src, d/src");
		assertThat(roots("{:deps {my/a {:local/root \"a\"}}}", "a/deps.edn", "{:deps {my/b {:local/root \"../b\"}}}",
				"b/deps.edn", "{:deps {my/a {:local/root \"../a\"}}}"))
			.isEqualTo(": ., src, a/src, b/src");
		// a dependency's own org.clojure/clojure loses to the top-level one
		assertThat(roots("{:deps {my/a {:local/root \"a\"}}}", "a/deps.edn",
				"{:deps {org.clojure/clojure {:mvn/version \"1.10.0\"}}}"))
			.isEqualTo(": ., src, a/src");
	}

	@Test
	void aLocalRootReadsItsManifestLikeTheOracle() {
		// an empty deps.edn is the root map; :deps/root is no local coordinate's key
		assertThat(roots("{:deps {my/a {:local/root \"a\"}}}", "a/deps.edn", "")).isEqualTo(": ., src, a/src");
		assertThat(roots("{:deps {my/a {:local/root \"a\" :deps/root \"sub\"}}}", "a/deps.edn", "{:paths [\"top\"]}",
				"a/sub/deps.edn", "{:paths [\"sub-src\"]}"))
			.isEqualTo(": ., src, a/top");
		// :deps/manifest :deps without the file, a path outside the library, an
		// unqualified library name
		assertThat(roots("{:deps {my/a {:local/root \"a\" :deps/manifest :deps}}}", "a/README", ""))
			.isEqualTo(": ., src, a/src");
		assertThat(roots("{:deps {my/a {:local/root \"a\"}}}", "a/deps.edn", "{:paths [\"../shared\" \"src\"]}"))
			.isEqualTo(": ., src, shared, a/src");
		assertThat(roots("{:deps {foo {:local/root \"foo\"}}}", "foo/deps.edn", "{}")).isEqualTo(": ., src, foo/src");
		// the project itself as a dependency adds its own roots again
		assertThat(roots("{:paths [\"src\"] :deps {me/me {:local/root \".\"}}}")).isEqualTo(": ., src");
		assertThatThrownBy(() -> roots("{:deps {my/a {:local/root \"a\" :deps/manifest :lein}}}", "a/README", ""))
			.isInstanceOf(LispReadException.class)
			.hasMessage("Manifest type :lein not loaded for my/a in coordinate "
					+ "{:local/root \"a\", :deps/manifest :lein, :deps/root \"a\"}");
	}

	@Test
	void aDependencyTheOracleRefusesIsRefusedInItsWords() {
		assertRoots("{:deps {my/a {:local/root \"a\"}}}",
				"Manifest file not found for my/a in coordinate " + "#:local{:root \"a\"}", "a/src/a.clj", "(ns a)");
		assertRoots("{:deps {my/a {:local/root \"nowhere\"}}}", "Local lib my/a not found: nowhere");
		assertRoots("{:deps {my/a {:local/root \"a\"}}}", "Local lib my/b not found: a/missing", "a/deps.edn",
				"{:deps {my/b {:local/root \"missing\"}}}");
		assertRoots("{:deps {my/a {:foo/version \"1\"}}}", "Coord of unknown type: #:foo{:version \"1\"}");
		assertRoots("{:deps {my/a {}}}", "Coord of unknown type: {}");
		assertRoots("{:deps {my/a {:mvn/version \"1\" :local/root \"a\"}}}",
				"Coord type is ambiguous: {:mvn/version \"1\", :local/root \"a\"}");
		assertRoots("{:deps {my/a nil}}", "Bad coordinate for library my/a, expected map: nil");
		assertRoots("{:deps {my/a {:local/root \"a\"} my/b {:local/root \"b\"}}}",
				"No known ancestor relationship between local versions for my/c: c2 and c1", "a/deps.edn",
				"{:deps {my/c {:local/root \"../c1\"}}}", "b/deps.edn", "{:deps {my/c {:local/root \"../c2\"}}}",
				"c1/deps.edn", "{}", "c2/deps.edn", "{}");
		assertRoots("{:deps {my/a {:local/root \"a\"}}}",
				"Error validating deps in a/deps.edn. Found: \"src\", expected: vector?, in: [:paths]", "a/deps.edn",
				"{:paths \"src\"}");
		assertRoots("{:deps {my/a {:local/root \"a\"}}}", "The following libs must be prepared before use: [my/a]",
				"a/deps.edn", "{:paths [\"src\" \"target/classes\"] :deps/prep-lib {:ensure \"target/classes\" "
						+ ":alias :build :fn compile}}");
		assertThat(roots("{:deps {my/a {:local/root \"a\"}}}", "a/deps.edn",
				"{:paths [\"src\" \"target/classes\"] :deps/prep-lib {:ensure \"target/classes\" :alias :build "
						+ ":fn compile}}",
				"a/target/classes/x.class", ""))
			.isEqualTo(": ., src, a/src, a/target/classes");
	}

	@Test
	void aGitCoordinateIsCheckedLikeTheOraclesWithoutTheRepository() {
		assertRoots("{:deps {my/g {:git/url \"https://example.invalid/g.git\"}}}",
				"Library my/g has coord with missing sha");
		assertRoots("{:deps {my/g {:git/url \"https://example.invalid/g.git\" :git/sha \"abc123\"}}}",
				"Library my/g has prefix sha, use full sha or add tag");
		assertRoots("{:deps {my/g {:git/sha \"0123456789012345678901234567890123456789\"}}}",
				"Failed to infer git url for: my/g");
		assertRoots("{:deps {io.github.someone/repo {:git/sha \"abc\"}}}",
				"Library io.github.someone/repo has prefix sha, use full sha or add tag");
		assertRoots("{:deps {my/g {:git/url \"https://example.invalid/g.git\" :sha \"" + "0".repeat(40)
				+ "\" :git/sha \"" + "0".repeat(40) + "\"}}}", "git coord has both :sha and :git/sha for my/g");
		assertRoots("{:deps {my/g {:git/url \"https://example.invalid/g.git\" :git/tag \"v1\" :tag \"v2\" "
				+ ":git/sha \"" + "0".repeat(40) + "\"}}}", "git coord has both :tag and :git/tag for v1");
	}

	@Test
	void theUserLevelMapMergesBetweenTheRootMapAndTheProjects() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("user/deps.edn", "{:paths [\"user-src\"] :deps {my/u {:local/root \"u\"}}}");
		files.put("u/deps.edn", "{}");
		files.put("p/deps.edn", "{}");
		files.put("deps.edn", "{:deps {my/p {:local/root \"p\"}}}");
		assertThat(new ClojureSourcePath(new MemoryClojureFiles(files, Map.of(), "user"), null).describeRoots())
			.isEqualTo(": ., user-src, p/src, u/src");
		files.put("deps.edn", "{:paths [\"src\"]}");
		assertThat(new ClojureSourcePath(new MemoryClojureFiles(files, Map.of(), "user"), null).describeRoots())
			.isEqualTo(": ., src, u/src");
		files.remove("deps.edn");
		assertThat(new ClojureSourcePath(new MemoryClojureFiles(files, Map.of(), "user"), null).describeRoots())
			.isEqualTo(": ., user-src, u/src");
		// no user directory, no user map
		assertThat(new ClojureSourcePath(new MemoryClojureFiles(files), null).describeRoots()).isEqualTo(": ., src");
	}

	private static String roots(String deps, String... more) {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("deps.edn", deps);
		for (int i = 0; i + 1 < more.length; i += 2) {
			files.put(more[i], more[i + 1]);
		}
		return new ClojureSourcePath(new MemoryClojureFiles(files), null).describeRoots();
	}

	private static void assertRoots(String deps, String refusal, String... more) {
		assertThatThrownBy(() -> roots(deps, more)).isInstanceOf(LispReadException.class).hasMessage(refusal);
	}

	private static Dep mvn(String lib, String version, String... exclusions) {
		return new Dep(Lib.of(lib), coord(":mvn/version", version, exclusions));
	}

	private static Coord coord(String key, String value, String... exclusions) {
		SequencedMap<String, LispVal> entries = new LinkedHashMap<>();
		entries.put(key, LispString.literal(value));
		if (exclusions.length > 0) {
			List<LispVal> items = new ArrayList<>();
			items.add(ClojureReader.VECTOR);
			for (String excluded : exclusions) {
				items.add(new LispSymbol(excluded));
			}
			entries.put(":exclusions", ClojureLowerUtil.list(items));
		}
		return new Coord(entries);
	}

	/**
	 * The selection under the root map's clojure, top deps given as {@code lib version}.
	 */
	private static String selected(String... libVersions) {
		Map<String, Coord> top = new LinkedHashMap<>();
		for (String libVersion : libVersions) {
			String[] parts = libVersion.split(" ");
			top.put(parts[0], coord(":mvn/version", parts[1]));
		}
		return selected(top);
	}

	private static String selected(Map<String, Coord> top) {
		return selected(top, new MemoryClojureFiles(Map.of()));
	}

	private static String selected(Map<String, Coord> top, MemoryClojureFiles files) {
		SequencedMap<Lib, @Nullable Coord> deps = new LinkedHashMap<>();
		DepsMap root = ClojureDepsEdn.ROOT;
		deps.putAll(Objects.requireNonNull(root.deps()));
		for (Map.Entry<String, Coord> entry : top.entrySet()) {
			deps.put(Lib.of(entry.getKey()), entry.getValue());
		}
		List<String> out = new ArrayList<>();
		for (Selected selected : ClojureDepsGraph.resolve(deps, null, new FakeRepository(files))) {
			String version = selected.coord().string(":mvn/version");
			out.add(selected.lib() + " " + (version != null ? version : selected.coord().string(":local/root")));
		}
		return String.join(", ", out);
	}

	/**
	 * Maven coordinates from {@link #REPO}; every other one through the real procurer.
	 */
	private static final class FakeRepository implements ClojureDepsGraph.Procurer {

		private final ClojureDepsProcurer local;

		FakeRepository(MemoryClojureFiles files) {
			this.local = new ClojureDepsProcurer(files);
		}

		@Override
		public Coord canonicalize(Lib lib, Coord coord, @Nullable String dir) {
			return this.local.canonicalize(lib, coord, dir);
		}

		@Override
		public Coord manifest(Lib lib, Coord coord) {
			return this.local.manifest(lib, coord);
		}

		@Override
		public List<Dep> children(Lib lib, Coord useCoord) {
			if (!useCoord.type().equals("mvn")) {
				return this.local.children(lib, useCoord);
			}
			return REPO.getOrDefault(lib + " " + useCoord.string(":mvn/version"), List.of());
		}

		@Override
		public Contribution contribution(Lib lib, Coord useCoord) {
			if (!useCoord.type().equals("mvn")) {
				return this.local.contribution(lib, useCoord);
			}
			return new Contribution(List.of(new Root(lib + "-" + useCoord.string(":mvn/version") + ".jar", true)),
					false, null);
		}

		@Override
		public boolean unprepped(Lib lib, Coord useCoord) {
			return this.local.unprepped(lib, useCoord);
		}

		@Override
		public int compareGit(Lib lib, Coord x, Coord y) {
			return 0;
		}

	}

}
