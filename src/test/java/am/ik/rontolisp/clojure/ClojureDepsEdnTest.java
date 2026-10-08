package am.ik.rontolisp.clojure;

import java.util.List;
import java.util.Objects;

import am.ik.rontolisp.clojure.ClojureDepsEdn.DepsMap;
import am.ik.rontolisp.clojure.ClojureDepsEdn.Lib;
import am.ik.rontolisp.reader.LispReadException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading one {@code deps.edn}: what the oracle ({@code clj} 1.12.6, measured 2026-10-08
 * with {@code clj -Srepro -Spath}) accepts is accepted, what it refuses is refused in its
 * words ({@code Error validating deps in <file>. Found: ..., expected: ..., in: ...}),
 * and the {@code :paths} alias keywords resolve the way its {@code chase-key} does.
 */
class ClojureDepsEdnTest {

	@Test
	void aKeyTheOraclesOpenMapLeavesAloneIsAccepted() {
		DepsMap map = ClojureDepsEdn.read("{:paths [\"src\"] :foo 1 :foo/bar 2 :deps/whatever 3}", "deps.edn");
		assertThat(paths(map)).containsExactly("src");
	}

	@Test
	void anEmptyFileOrNilIsTheEmptyMap() {
		assertThat(ClojureDepsEdn.read("", "deps.edn")).isEqualTo(ClojureDepsEdn.EMPTY);
		assertThat(ClojureDepsEdn.read(" ;; nothing\n", "deps.edn")).isEqualTo(ClojureDepsEdn.EMPTY);
		assertThat(ClojureDepsEdn.read("nil", "deps.edn")).isEqualTo(ClojureDepsEdn.EMPTY);
		// the root map's :paths stands
		assertThat(paths(ClojureDepsEdn.EMPTY)).containsExactly("src");
	}

	@Test
	void aValueTheOraclesSpecRefusesIsNamedWithItsPath() {
		assertRefused("{:paths \"src\"}", "Found: \"src\", expected: vector?, in: [:paths]");
		assertRefused("{:paths [\"src\" #foo/bar \"x\"]}", "Found: #foo/bar \"x\", expected: string?, in: [:paths 1]");
		assertRefused("{:deps {my/a \"1.0\"}}", "Found: \"1.0\", expected: map?, in: [:deps my/a 1]");
		assertRefused("{:deps nil :paths nil}", "Found: nil, expected: map?, in: [:deps]");
		assertRefused("{:deps {\"my/a\" {:local/root \"a\"}}}",
				"Found: \"my/a\", expected: symbol?, in: [:deps \"my/a\" 0]");
		assertRefused("{:deps {my/a {:local/root \"a\" :exclusions my/c}}}",
				"Found: my/c, expected: coll?, in: [:deps my/a 1 :exclusions]");
		assertRefused("{:deps {my/a {:mvn/version 1}}}",
				"Found: 1, expected: string?, in: [:deps my/a 1 :mvn/version]");
		assertRefused("{:mvn/local-repo 1}", "Found: 1, expected: string?, in: [:mvn/local-repo]");
		assertRefused("{:mvn/repos {\"x\" {:url 1}}}", "Found: 1, expected: string?, in: [:mvn/repos \"x\" 1 :url]");
		assertRefused("[1 2]", "Found: [1 2], expected: map?, in: []");
	}

	@Test
	void theFileMustHoldOneValueWithoutARepeatedKey() {
		assertThatThrownBy(() -> ClojureDepsEdn.read("{:paths [\"src\"]} {:paths [\"x\"]}", "deps.edn"))
			.isInstanceOf(LispReadException.class)
			.hasMessage("Error reading edn. Expected edn to contain a single value. (deps.edn)");
		assertThatThrownBy(
				() -> ClojureDepsEdn.read("{:deps {my/a {:local/root \"a\"} my/a {:local/root \"b\"}}}", "deps.edn"))
			.isInstanceOf(LispReadException.class)
			.hasMessage("Error reading edn. Duplicate key: my/a (deps.edn)");
	}

	@Test
	void anUnqualifiedLibNameIsCanonicalized() {
		DepsMap map = ClojureDepsEdn.read("{:deps {foo {:local/root \"foo\" :exclusions [c]}}}", "deps.edn");
		assertThat(map.deps()).containsOnlyKeys(new Lib("foo", "foo"));
		assertThat(coordOf(map, "foo/foo").exclusions()).containsExactly(new Lib("c", "c"));
	}

	@Test
	void aCoordinatesKeysAreCheckedWhereTheOraclesSpecRegistersThem() {
		assertRefused("{:deps {my/a {:local/root 1}}}", "Found: 1, expected: string?, in: [:deps my/a 1 :local/root]");
		assertRefused("{:deps {my/a {:local/root \"a\" :exclusions [:k]}}}",
				"Found: :k, expected: symbol?, in: [:deps my/a 1 :exclusions 0]");
		assertRefused("{:deps {my/a {:local/root \"a\" :deps/manifest \"deps\"}}}",
				"Found: \"deps\", expected: keyword?, in: [:deps my/a 1 :deps/manifest]");
		assertRefused("{:aliases {\"x\" []}}", "Found: \"x\", expected: keyword?, in: [:aliases \"x\" 0]");
		assertRefused("{:tools/usage {:ns-default a.b/c}}",
				"Found: a.b/c, expected: simple-symbol?, in: [:tools/usage :ns-default]");
		// an unknown key of a coordinate is open, like the oracle's
		assertThat(coordOf(ClojureDepsEdn.read("{:deps {my/a {:local/root \"a\" :foo 1}}}", "deps.edn"), "my/a")
			.string(":local/root")).isEqualTo("a");
	}

	private static ClojureDepsEdn.Coord coordOf(DepsMap map, String lib) {
		return Objects.requireNonNull(Objects.requireNonNull(map.deps()).get(Lib.of(lib)));
	}

	@Test
	void aPathsAliasResolvesRecursivelyLikeTheOraclesChaseKey() {
		// measured: src, resources, more
		assertThat(paths(ClojureDepsEdn
			.read("{:paths [\"src\" :res] :aliases {:res [\"resources\" :more] :more [\"more\"]}}", "deps.edn")))
			.containsExactly("src", "resources", "more");
		// an alias no map defines, or one whose value is a map, adds nothing
		assertThat(paths(ClojureDepsEdn.read("{:paths [\"src\" :nope]}", "deps.edn"))).containsExactly("src");
		assertThat(paths(
				ClojureDepsEdn.read("{:paths [\"src\" :res] :aliases {:res {:extra-paths [\"x\"]}}}", "deps.edn")))
			.containsExactly("src");
		// one that is no collection is the oracle's own failure
		assertThatThrownBy(() -> paths(ClojureDepsEdn.read("{:paths [\"src\" :a] :aliases {:a 1}}", "deps.edn")))
			.isInstanceOf(LispReadException.class)
			.hasMessage("Don't know how to create ISeq from: java.lang.Long");
		assertThatThrownBy(() -> paths(ClojureDepsEdn.read("{:paths [:a] :aliases {:a [:b] :b [:a]}}", "deps.edn")))
			.isInstanceOf(LispReadException.class)
			.hasMessage("the :paths alias :a names itself");
	}

	@Test
	void aLaterMapMergesItsMapsAndReplacesTheRest() {
		DepsMap user = ClojureDepsEdn.read("{:paths [\"user-src\"] :deps {my/u {:local/root \"u\"}}}", "user/deps.edn");
		DepsMap project = ClojureDepsEdn.read("{:deps {my/p {:local/root \"p\"}}}", "deps.edn");
		DepsMap merged = ClojureDepsEdn.merge(List.of(ClojureDepsEdn.ROOT, user, project));
		assertThat(ClojureDepsEdn.flattenPaths(merged)).containsExactly("user-src");
		assertThat(Objects.requireNonNull(merged.deps()).keySet().stream().map(Lib::toString))
			.containsExactly("org.clojure/clojure", "my/u", "my/p");
		assertThat(merged.aliases()).containsKeys(":deps", ":test");
	}

	@Test
	void aCoordinatePrintsInTheOraclesWords() {
		DepsMap map = ClojureDepsEdn
			.read("{:deps {my/a {:local/root \"a\"} my/b {:mvn/version \"1\" :local/root \"b\"}}}", "deps.edn");
		assertThat(coordOf(map, "my/a").print()).isEqualTo("#:local{:root \"a\"}");
		assertThat(coordOf(map, "my/b").print()).isEqualTo("{:mvn/version \"1\", :local/root \"b\"}");
		assertThatThrownBy(() -> coordOf(map, "my/b").type()).isInstanceOf(LispReadException.class)
			.hasMessage("Coord type is ambiguous: {:mvn/version \"1\", :local/root \"b\"}");
	}

	@Test
	void aRepositorysPoliciesReachTheResolverAsTheOraclesRepoPolicyReadsThem() {
		// tools.deps' repo-policy (clj 1.12.6, 2026-10-08): :enabled defaults to true and
		// :update to :daily; an integer :update is handed to Maven as the string "5",
		// which Maven treats as never (measured: no metadata request two days on)
		DepsMap map = ClojureDepsEdn.read("""
				{:mvn/repos {"plain" {:url "https://plain.example/"}
				             "gated" {:url "https://gated.example/"
				                      :releases {:enabled false :update :always}
				                      :snapshots {:update 5}}
				             "gone" nil}}
				""", "deps.edn");
		List<ClojureRepositories.MavenRepository> repositories = ClojureDepsEdn
			.mavenSource(ClojureDepsEdn.merge(List.of(ClojureDepsEdn.ROOT, map)), ClojureFiles.NONE, null)
			.repositories();

		assertThat(repositories).extracting(ClojureRepositories.MavenRepository::id)
			.containsExactly("central", "clojars", "plain", "gated");
		ClojureRepositories.MavenPolicy defaults = ClojureRepositories.MavenPolicy.DEFAULT;
		assertThat(repositories.get(2).releases()).isEqualTo(defaults);
		assertThat(repositories.get(2).snapshots()).isEqualTo(defaults);
		assertThat(repositories.get(3).releases()).isEqualTo(new ClojureRepositories.MavenPolicy(false, "always"));
		assertThat(repositories.get(3).snapshots()).isEqualTo(new ClojureRepositories.MavenPolicy(true, "never"));
	}

	private static List<String> paths(DepsMap map) {
		return ClojureDepsEdn.flattenPaths(ClojureDepsEdn.merge(List.of(ClojureDepsEdn.ROOT, map)));
	}

	private static void assertRefused(String text, String problem) {
		assertThatThrownBy(() -> ClojureDepsEdn.read(text, "deps.edn")).isInstanceOf(LispReadException.class)
			.hasMessage("Error validating deps in deps.edn. " + problem);
	}

}
