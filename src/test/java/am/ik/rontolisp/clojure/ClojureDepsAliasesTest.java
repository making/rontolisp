package am.ik.rontolisp.clojure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.reader.LispReadException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The selected {@code deps.edn} aliases applied to the source path. Every expected order
 * is the oracle's classpath over the same tree ({@code clj} 1.12.6,
 * {@code clj -Srepro -Spath -A:...} from a project directory beside the libraries,
 * measured 2026-10-08), here with the project in the working directory: {@code .} is the
 * root a read without a file adds first.
 */
class ClojureDepsAliasesTest {

	private static final String PROJECT = """
			{:paths ["src"]
			 :deps {my/a {:local/root "a1"} my/b {:local/root "b"}}
			 :aliases {:ov {:override-deps {my/a {:local/root "a2"} my/c {:local/root "d"}}}
			           :df {:extra-deps {my/n {:local/root "n"}} :default-deps {my/nil {:local/root "d"}}}
			           :n {:extra-deps {my/n {:local/root "n"}}}
			           :ex {:extra-deps {my/d {:local/root "d"}}}
			           :rd {:replace-deps {my/d {:local/root "d"}}}
			           :rp {:replace-paths ["dev"]}
			           :rp2 {:replace-paths ["t2" "dev"]}
			           :cpo {:classpath-overrides {my/a "over" my/b ""}}
			           :pa {:paths ["t2"] :deps {my/d {:local/root "d"}}}
			           :test {:main-opts ["-m" "x"]}
			           :ep {:extra-paths [:more "dev"]}
			           :more ["t2"]
			           :a {:extra-paths ["dev"]}
			           :b {:extra-paths ["t2"]}
			           :j {:jvm-opts ["-Dfoo=1"]}}}
			""";

	private static MemoryClojureFiles project() {
		return new MemoryClojureFiles(projectFiles());
	}

	private static Map<String, String> projectFiles() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("deps.edn", PROJECT);
		for (String lib : List.of("a1", "a2", "c", "d")) {
			files.put(lib + "/deps.edn", "{}");
		}
		files.put("b/deps.edn", "{:deps {my/c {:local/root \"../c\"}}}");
		files.put("n/deps.edn", "{:deps {my/nil nil}}");
		files.put("user/deps.edn", "{:aliases {:a {:extra-paths [\"user-a\"] :main-opts [\"-m\" \"u\"]} "
				+ ":u {:extra-paths [\"uu\"]}}}");
		return files;
	}

	private static String roots(String... aliases) {
		return new ClojureSourcePath(project().withAliases(aliases), null).describeRoots();
	}

	@Test
	void theResolveArgumentsChooseTheCoordinates() {
		assertThat(roots()).isEqualTo(": ., src, a1/src, b/src, c/src");
		// an override applies wherever the library appears, read from the project
		assertThat(roots(":ov")).isEqualTo(": ., src, a2/src, b/src, d/src");
		// a default stands in for a nil coordinate only
		assertThat(roots(":df")).isEqualTo(": ., src, a1/src, b/src, n/src, c/src, d/src");
		assertThatThrownBy(() -> roots(":n")).isInstanceOf(LispReadException.class)
			.hasMessage("Bad coordinate for library my/nil, expected map: nil");
		assertThat(roots(":ov", ":df")).isEqualTo(": ., src, a2/src, b/src, n/src, d/src");
		// an extra dep joins the top level
		assertThat(roots(":ex")).isEqualTo(": ., src, a1/src, b/src, d/src, c/src");
	}

	@Test
	void theToolArgumentsReplaceTheProjectsOwn() {
		// the root map's clojure stays: only the project's map is replaced
		assertThat(roots(":rd")).isEqualTo(": ., src, d/src");
		assertThat(roots(":rp")).isEqualTo(": ., dev, a1/src, b/src, c/src");
		assertThat(roots(":rp", ":rp2")).isEqualTo(": ., dev, t2, a1/src, b/src, c/src");
		// the older spellings :paths and :deps replace too
		assertThat(roots(":pa")).isEqualTo(": ., t2, d/src");
	}

	@Test
	void theClasspathArgumentsComeFirstOrStandInForALibrary() {
		// :extra-paths ahead of :paths, an alias keyword in them chased
		assertThat(roots(":ep")).isEqualTo(": ., t2, dev, src, a1/src, b/src, c/src");
		assertThat(roots(":b", ":a")).isEqualTo(": ., t2, dev, src, a1/src, b/src, c/src");
		// appended without repeats, in selection order
		assertThat(roots(":a", ":b")).isEqualTo(": ., dev, t2, src, a1/src, b/src, c/src");
		assertThat(roots(":b", ":a", ":b")).isEqualTo(": ., t2, dev, src, a1/src, b/src, c/src");
		// a blank override drops the library, not what it brought
		assertThat(roots(":cpo")).isEqualTo(": ., src, over, c/src");
	}

	@Test
	void anAliasThatIsNoMapOrNamesNothingTheClasspathReadsChangesNothing() {
		assertThat(roots(":more")).isEqualTo(": ., src, a1/src, b/src, c/src");
		assertThat(roots(":j")).isEqualTo(": ., src, a1/src, b/src, c/src");
		assertThat(roots(":nope")).isEqualTo(": ., src, a1/src, b/src, c/src");
	}

	@Test
	void anAliasTwoMapsDefineIsOneMap() {
		// the project's :test keeps the root map's :extra-paths ["test"]
		assertThat(roots(":test")).isEqualTo(": ., test, src, a1/src, b/src, c/src");
		// the project's :a replaces the user map's :extra-paths and keeps its :main-opts
		MemoryClojureFiles files = new MemoryClojureFiles(projectFiles(), Map.of(), "user", List.of(":a"));
		assertThat(new ClojureSourcePath(files, null).describeRoots()).isEqualTo(": ., dev, src, a1/src, b/src, c/src");
		ClojureMain.Resolved main = ClojureMain.main(files, List.of("arg"), true);
		assertThat(main.warnings())
			.containsExactly("WARNING: Use of :main-opts with -A is deprecated. Use -M instead.");
		assertThat(((ClojureMain.Program) main.entry()).source()).contains("(require 'u)")
			.contains("(apply u/-main *command-line-args*)");
		MemoryClojureFiles userOnly = new MemoryClojureFiles(projectFiles(), Map.of(), "user", List.of(":u"));
		assertThat(new ClojureSourcePath(userOnly, null).describeRoots())
			.isEqualTo(": ., uu, src, a1/src, b/src, c/src");
	}

	@Test
	void aSelectedAliasNoMapDefinesIsWarnedOf() {
		ClojureMain.Resolved resolved = ClojureMain.main(project().withAliases(":nope", ":a", ":nope", ":zz"),
				List.of(), false);
		assertThat(resolved.warnings())
			.containsExactly("WARNING: Specified aliases are undeclared and are not being used: [:nope :zz]");
		assertThat(resolved.entry()).isEqualTo(new ClojureMain.Repl(List.of()));
	}

	@Test
	void anAliasArgumentOfTheWrongShapeIsRefusedByName() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("deps.edn", "{:aliases {:x {:extra-deps [my/a]}}}");
		assertThatThrownBy(() -> new ClojureSourcePath(new MemoryClojureFiles(files).withAliases(":x"), null).roots())
			.isInstanceOf(LispReadException.class)
			.hasMessage("the aliases' :extra-deps must be a map of libraries to coordinates, got: [my/a]");
	}

}
