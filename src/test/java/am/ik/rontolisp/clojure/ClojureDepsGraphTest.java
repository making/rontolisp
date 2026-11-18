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
		// the graphs of 2026-11-18, one group each: what the reference leaves open
		repo.put("fake.q1/a 1", List.of(mvn("fake.q1/k", "1")));
		repo.put("fake.q1/k 1", List.of(mvn("fake.q1/x", "2")));
		repo.put("fake.q1/b 1", List.of(mvn("fake.q1/m", "1")));
		repo.put("fake.q1/m 1", List.of(mvn("fake.q1/k", "2")));
		repo.put("fake.q1/c 1", List.of(mvn("fake.q1/n", "1")));
		repo.put("fake.q1/n 1", List.of(mvn("fake.q1/p", "1")));
		repo.put("fake.q1/p 1", List.of(mvn("fake.q1/q", "1")));
		repo.put("fake.q1/q 1", List.of(mvn("fake.q1/x", "1")));
		repo.put("fake.q4/a 1", List.of(mvn("fake.q4/p", "1")));
		repo.put("fake.q4/p 1", List.of(mvn("fake.q4/k", "1")));
		repo.put("fake.q4/k 1", List.of(mvn("fake.q4/x", "1")));
		repo.put("fake.q4/b 1", List.of(mvn("fake.q4/q", "1")));
		repo.put("fake.q4/q 1", List.of(mvn("fake.q4/k", "1")));
		repo.put("fake.q4/c 1", List.of(mvn("fake.q4/r", "1")));
		repo.put("fake.q4/r 1", List.of(mvn("fake.q4/p", "2")));
		repo.put("fake.q4b/a 1", List.of(mvn("fake.q4b/p", "1")));
		repo.put("fake.q4b/p 1", List.of(mvn("fake.q4b/k", "1")));
		repo.put("fake.q4b/k 1", List.of(mvn("fake.q4b/x", "1")));
		repo.put("fake.q4b/b 1", List.of(mvn("fake.q4b/q", "1")));
		repo.put("fake.q4b/q 1", List.of(mvn("fake.q4b/k", "1")));
		repo.put("fake.q4b/c 1", List.of(mvn("fake.q4b/r", "1")));
		repo.put("fake.q4b/r 1", List.of(mvn("fake.q4b/s", "1")));
		repo.put("fake.q4b/s 1", List.of(mvn("fake.q4b/t", "1")));
		repo.put("fake.q4b/t 1", List.of(mvn("fake.q4b/p", "2")));
		repo.put("fake.q8/a 1", List.of(mvn("fake.q8/b", "1")));
		repo.put("fake.q8/b 1", List.of(mvn("fake.q8/c", "1")));
		repo.put("fake.q8/c 1", List.of(mvn("fake.q8/b", "2")));
		repo.put("fake.q8/b 2", List.of(mvn("fake.q8/d", "1")));
		repo.put("fake.p1/a 1", List.of(mvn("fake.p1/b", "1")));
		repo.put("fake.p1/b 1", List.of(mvn("fake.p1/c", "1")));
		repo.put("fake.p1/c 1", List.of(mvn("fake.p1/b", "2")));
		repo.put("fake.p1/b 2", List.of(mvn("fake.p1/d", "1")));
		repo.put("fake.p1/e 1", List.of(mvn("fake.p1/h", "1")));
		repo.put("fake.p1/h 1", List.of(mvn("fake.p1/i", "1")));
		repo.put("fake.p1/i 1", List.of(mvn("fake.p1/b", "2")));
		repo.put("fake.r1/c 1", List.of(mvn("fake.r1/f", "1"), mvn("fake.r1/b", "1")));
		repo.put("fake.r1/f 1", List.of(mvn("fake.r1/g", "2")));
		repo.put("fake.r1/g 2", List.of(mvn("fake.r1/f", "2")));
		repo.put("fake.r1/f 2", List.of(mvn("fake.r1/w", "1")));
		repo.put("fake.r1/b 1", List.of(mvn("fake.r1/a", "1")));
		repo.put("fake.r1/a 1", List.of(mvn("fake.r1/g", "3")));
		repo.put("fake.r6/a 1", List.of(mvn("fake.r6/x", "1")));
		repo.put("fake.r6/b 1", List.of(mvn("fake.r6/x", "1")));
		repo.put("fake.r6/x 1", List.of(mvn("fake.r6/y", "1"), mvn("fake.r6/w", "1")));
		repo.put("fake.r4/a 1", List.of(mvn("fake.r4/k", "1")));
		repo.put("fake.r4/k 1", List.of(mvn("fake.r4/x", "2", "fake.r4/y")));
		repo.put("fake.r4/x 2", List.of(mvn("fake.r4/y", "1"), mvn("fake.r4/w", "1")));
		repo.put("fake.r4/b 1", List.of(mvn("fake.r4/m", "1")));
		repo.put("fake.r4/m 1", List.of(mvn("fake.r4/k", "2")));
		repo.put("fake.r4/c 1", List.of(mvn("fake.r4/n", "1")));
		repo.put("fake.r4/n 1", List.of(mvn("fake.r4/p", "1")));
		repo.put("fake.r4/p 1", List.of(mvn("fake.r4/q", "1")));
		repo.put("fake.r4/q 1", List.of(mvn("fake.r4/x", "2", "fake.r4/w")));
		repo.put("fake.r4/d 1", List.of(mvn("fake.r4/r", "1")));
		repo.put("fake.r4/r 1", List.of(mvn("fake.r4/s", "1")));
		repo.put("fake.r4/s 1", List.of(mvn("fake.r4/t", "1")));
		repo.put("fake.r4/t 1", List.of(mvn("fake.r4/u", "1")));
		repo.put("fake.r4/u 1", List.of(mvn("fake.r4/x", "2")));
		repo.put("fake.q7/a 1", List.of(mvn("fake.q7/k", "1")));
		repo.put("fake.q7/k 1", List.of(mvn("fake.q7/x", "1")));
		repo.put("fake.q7/b 1", List.of(mvn("fake.q7/m", "1")));
		repo.put("fake.q7/m 1", List.of(mvn("fake.q7/n", "1")));
		repo.put("fake.q7/n 1", List.of(mvn("fake.q7/k", "2")));
		repo.put("fake.q7/k 2", List.of(mvn("fake.q7/o", "1")));
		repo.put("fake.q7/o 1", List.of(mvn("fake.q7/x", "1")));
		repo.put("fake.s1/a 1", List.of(mvn("fake.s1/k", "1")));
		repo.put("fake.s1/k 1", List.of(mvn("fake.s1/x", "1.0")));
		repo.put("fake.s1/b 1", List.of(mvn("fake.s1/m", "1")));
		repo.put("fake.s1/m 1", List.of(mvn("fake.s1/x", "1.0.0")));
		repo.put("fake.s1/c 1", List.of(mvn("fake.s1/n", "1")));
		repo.put("fake.s1/n 1", List.of(mvn("fake.s1/o", "1")));
		repo.put("fake.s1/o 1", List.of(mvn("fake.s1/k", "2")));
		repo.put("fake.s1/x 1.0", List.of(mvn("fake.s1/y", "1")));
		repo.put("fake.s1/x 1.0.0", List.of(mvn("fake.s1/w", "1")));
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
		// the second spelling adds no route either: x 1.0 hangs from k 1 alone and goes
		// with it when k 2 turns up, though m's x 1.0.0 was offered in between
		assertThat(selected("fake.s1/a 1", "fake.s1/b 1", "fake.s1/c 1"))
			.isEqualTo("fake.s1/a 1, fake.s1/b 1, fake.s1/c 1, org.clojure/clojure 1.12.6, fake.s1/m 1, "
					+ "fake.s1/n 1, " + SPECS + ", fake.s1/o 1, fake.s1/k 2");
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
		// a library dropped that way has no claim left: x 2 went with k 1, so the older
		// x 1 arriving two waves later is taken
		assertThat(selected("fake.q1/a 1", "fake.q1/b 1", "fake.q1/c 1"))
			.isEqualTo("fake.q1/a 1, fake.q1/b 1, fake.q1/c 1, org.clojure/clojure 1.12.6, fake.q1/m 1, "
					+ "fake.q1/n 1, " + SPECS + ", fake.q1/k 2, fake.q1/p 1, fake.q1/q 1, fake.q1/x 1");
		// k hangs from p 1 and from q. Its dependency x was offered below p 1: decided
		// after p 2 replaced p 1 it is taken, k still being held by q...
		assertThat(selected("fake.q4/a 1", "fake.q4/b 1", "fake.q4/c 1"))
			.isEqualTo("fake.q4/a 1, fake.q4/b 1, fake.q4/c 1, org.clojure/clojure 1.12.6, fake.q4/q 1, "
					+ "fake.q4/r 1, " + SPECS + ", fake.q4/k 1, fake.q4/p 2, fake.q4/x 1");
		// ...and taken before p 2 turns up it is dropped with p 1, its one route going
		// through it, while k stays
		assertThat(selected("fake.q4b/a 1", "fake.q4b/b 1", "fake.q4b/c 1"))
			.isEqualTo("fake.q4b/a 1, fake.q4b/b 1, fake.q4b/c 1, org.clojure/clojure 1.12.6, fake.q4b/q 1, "
					+ "fake.q4b/r 1, " + SPECS + ", fake.q4b/k 1, fake.q4b/s 1, fake.q4b/t 1, fake.q4b/p 2");
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
		// only a coordinate's own exclusions are narrowed that way: y, excluded by the
		// top-level a two levels up, stays out though b reaches x without excluding it
		Map<String, Coord> inherited = new LinkedHashMap<>();
		inherited.put("fake.r6/a", coord(":mvn/version", "1", "fake.r6/y"));
		inherited.put("fake.r6/b", coord(":mvn/version", "1"));
		assertThat(selected(inherited))
			.isEqualTo("fake.r6/a 1, fake.r6/b 1, org.clojure/clojure 1.12.6, fake.r6/x 1, " + SPECS + ", fake.r6/w 1");
		// x 2 (excluding y) goes with k 1; taken again below q (excluding w) it offers y
		// and starts over, so u's x 2, excluding nothing, brings w
		assertThat(selected("fake.r4/a 1", "fake.r4/b 1", "fake.r4/c 1", "fake.r4/d 1"))
			.isEqualTo("fake.r4/a 1, fake.r4/b 1, fake.r4/c 1, fake.r4/d 1, org.clojure/clojure 1.12.6, "
					+ "fake.r4/m 1, fake.r4/n 1, fake.r4/r 1, " + SPECS + ", fake.r4/x 2, fake.r4/k 2, fake.r4/p 1, "
					+ "fake.r4/s 1, fake.r4/q 1, fake.r4/t 1, fake.r4/u 1, fake.r4/y 1, fake.r4/w 1");
	}

	@Test
	void theOrderIsTopDownThenByThePathFromTheTop() {
		// a group sorts by its namespace, alpha/ before fake.z/ before org.clojure/; a
		// library at two paths sits at the first
		assertThat(selected("fake.z/top 1.0", "alpha/top 1.0")).isEqualTo("alpha/top 1.0, fake.z/top 1.0, "
				+ "org.clojure/clojure 1.12.6, fake/m10 1.0, alpha/n10 1.0, " + SPECS + ", fake/deep 1.0");
		// a route that no longer stands still places: x is held four levels down below
		// k 2 and sits where the replaced k 1 first brought it
		assertThat(selected("fake.q7/a 1", "fake.q7/b 1"))
			.isEqualTo("fake.q7/a 1, fake.q7/b 1, org.clojure/clojure 1.12.6, fake.q7/m 1, " + SPECS
					+ ", fake.q7/x 1, fake.q7/n 1, fake.q7/k 2, fake.q7/o 1");
	}

	@Test
	void aCycleStopsAtTheLibraryAlreadySelected() {
		assertThat(selected("fake/cy1 1.0"))
			.isEqualTo("fake/cy1 1.0, org.clojure/clojure 1.12.6, fake/cy2 1.0, " + SPECS);
		// a newer version met below its own older one: b 2 replaces b 1 and stays
		// selected, c goes with b 1, and b 2, held by nothing now, brings nothing...
		assertThat(selected("fake.q8/a 1"))
			.isEqualTo("fake.q8/a 1, org.clojure/clojure 1.12.6, " + SPECS + ", fake.q8/b 2");
		// ...until another library holds it: i's b 2 arrives in the same wave, so d is in
		assertThat(selected("fake.p1/a 1", "fake.p1/e 1"))
			.isEqualTo("fake.p1/a 1, fake.p1/e 1, org.clojure/clojure 1.12.6, fake.p1/h 1, " + SPECS
					+ ", fake.p1/i 1, fake.p1/b 2, fake.p1/d 1");
		// held means by the version that brought it: g 2 went with f 1, and the g 3 taken
		// since never had f 2 below it, so f 2's w stays out
		assertThat(selected("fake.r1/c 1")).isEqualTo("fake.r1/c 1, org.clojure/clojure 1.12.6, fake.r1/b 1, " + SPECS
				+ ", fake.r1/a 1, fake.r1/g 3, fake.r1/f 2");
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
		// what is turned away is never opened: a directory with no manifest below a
		// top-level library of the same name, or below an exclusion two levels up
		assertThat(roots("{:deps {my/a {:local/root \"a\"} my/c {:local/root \"c\"}}}", "a/deps.edn",
				"{:deps {my/c {:local/root \"../bare\"}}}", "c/deps.edn", "{}", "bare/README", ""))
			.isEqualTo(": ., src, a/src, c/src");
		assertThat(roots("{:deps {my/a {:local/root \"a\" :exclusions [my/c]}}}", "a/deps.edn",
				"{:deps {my/b {:local/root \"../b\"}}}", "b/deps.edn", "{:deps {my/c {:local/root \"../bare\"}}}",
				"bare/README", ""))
			.isEqualTo(": ., src, a/src, b/src");
		// a nil coordinate its own parent's coordinate excludes is never decided
		assertThat(roots("{:deps {my/a {:local/root \"a\" :exclusions [my/c]}}}", "a/deps.edn", "{:deps {my/c nil}}"))
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
		// several are named in the order the walk took them, not the source path's
		String prep = "{:deps/prep-lib {:ensure \"target/classes\" :alias :build :fn compile}}";
		assertRoots("{:deps {my/z {:local/root \"z\"} my/a {:local/root \"a\"}}}",
				"The following libs must be prepared before use: [my/z my/a my/m]", "z/deps.edn",
				"{:deps {my/m {:local/root \"../m\"}} :deps/prep-lib {:ensure \"target/classes\" :alias :build "
						+ ":fn compile}}",
				"a/deps.edn", prep, "m/deps.edn", prep);
		// which of two refusals comes first, asked of clj 1.12.6 (2026-11-18). Every
		// top-level coordinate is checked before any library is opened...
		assertRoots("{:deps {my/a {:local/root \"a\"} my/b {:local/root \"nowhere\"}}}",
				"Local lib my/b not found: nowhere", "a/deps.edn", "{:deps {my/x {:local/root \"../missing\"}}}");
		// ...and every dependency of a wave is decided before the dependencies of one
		// taken in it are read: q's nil and c's second root come before p's missing x
		assertRoots("{:deps {my/a {:local/root \"a\"}}}", "Bad coordinate for library my/q, expected map: nil",
				"a/deps.edn", "{:deps {my/p {:local/root \"../p\"} my/q nil}}", "p/deps.edn",
				"{:deps {my/x {:local/root \"../missing\"}}}");
		assertRoots("{:deps {my/a {:local/root \"a\"} my/b {:local/root \"b\"}}}",
				"No known ancestor relationship between local versions for my/c: c2 and c1", "a/deps.edn",
				"{:deps {my/c {:local/root \"../c1\"}}}", "b/deps.edn",
				"{:deps {my/p {:local/root \"../p\"} my/c {:local/root \"../c2\"}}}", "p/deps.edn",
				"{:deps {my/x {:local/root \"../missing\"}}}", "c1/deps.edn", "{}", "c2/deps.edn", "{}");
		// a coordinate is checked where it is written, also one that is then turned
		// away: below a top-level library of the same name, below its parent's exclusion
		assertRoots("{:deps {my/a {:local/root \"a\"} my/c {:local/root \"c\"}}}", "Local lib my/c not found: missing",
				"a/deps.edn", "{:deps {my/c {:local/root \"../missing\"}}}", "c/deps.edn", "{}");
		assertRoots("{:deps {my/a {:local/root \"a\" :exclusions [my/c]}}}", "Local lib my/c not found: missing",
				"a/deps.edn", "{:deps {my/c {:local/root \"../missing\"}}}");
		// a nil coordinate is refused when decided, ahead of an exclusion from further up
		// the route and of a top-level library of the same name
		assertRoots("{:deps {my/a {:local/root \"a\" :exclusions [my/c]}}}",
				"Bad coordinate for library my/c, expected map: nil", "a/deps.edn",
				"{:deps {my/b {:local/root \"../b\"}}}", "b/deps.edn", "{:deps {my/c nil}}");
		assertRoots("{:deps {my/a {:local/root \"a\"} my/c {:local/root \"c\"}}}",
				"Bad coordinate for library my/c, expected map: nil", "a/deps.edn", "{:deps {my/c nil}}", "c/deps.edn",
				"{}");
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
