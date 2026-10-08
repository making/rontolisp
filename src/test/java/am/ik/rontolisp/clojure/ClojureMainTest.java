package am.ik.rontolisp.clojure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@code -M}, {@code -X} and a project test run generate, over a project in memory.
 * The argument handling and the refusals' words are the oracle's ({@code clj} 1.12.6,
 * {@code clojure.main} and {@code clojure.run.exec}, measured 2026-10-08); that the
 * programs run alike on the four backends is {@code cli/ClojureDepsCommandLineTest}'s.
 */
class ClojureMainTest {

	private static final String DEPS = """
			{:paths ["src"]
			 :aliases {:dev {:extra-paths ["dev"] :main-opts ["-m" "my.app" "fromdev"]}
			           :x {:main-opts ["-m" "my.other"]}
			           :sc {:main-opts ["scripts/s.clj" "q"]}
			           :run {:exec-fn my.app/run :exec-args {:a 1 :b {:c 2}}}
			           :nsd {:ns-default my.app :exec-args {:a 9}}
			           :nsa {:ns-aliases {m my.app} :exec-fn m/run}
			           :e2 {:exec-args {:b 3 :c 4}}
			           :e3 {:exec-args nil}
			           :bad {:exec-fn :kw}}}
			""";

	private static MemoryClojureFiles files(String... aliases) {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("deps.edn", DEPS);
		return new MemoryClojureFiles(files).withAliases(aliases);
	}

	private static String program(ClojureMain.Resolved resolved) {
		return ((ClojureMain.Program) resolved.entry()).source();
	}

	private static String main(List<String> args, String... aliases) {
		return program(ClojureMain.main(files(aliases), args, false));
	}

	private static String exec(List<String> args, String... aliases) {
		return program(ClojureMain.exec(files(aliases), args, false));
	}

	@Test
	void mainRunsTheNamespaceTheLastMainOptsOrTheArgumentsName() {
		assertThat(main(List.of("-m", "my.app", "a", "b"))).isEqualTo("""
				;; clj -M -m my.app
				(require 'my.app)
				(set! *command-line-args* (seq (concat ["a" "b"] *command-line-args*)))
				(apply my.app/-main *command-line-args*)
				""");
		// :main-opts first, the arguments after them; the last alias naming some wins
		assertThat(main(List.of("a"), ":dev")).contains("(require 'my.app)").contains("[\"fromdev\" \"a\"]");
		assertThat(main(List.of("a"), ":dev", ":x")).contains("(require 'my.other)").contains("[\"a\"]");
		assertThat(main(List.of("a"), ":x", ":dev")).contains("(require 'my.app)");
		// no argument at all binds nothing: *command-line-args* stays nil
		assertThat(main(List.of("--main", "my.app"))).doesNotContain("set!");
		// an argument is a string, escaped
		assertThat(main(List.of("-m", "my.app", "say \"hi\"\n"))).contains("[\"say \\\"hi\\\"\\n\"]");
	}

	@Test
	void mainRunsAFileOrTheReplOrRefusesWhatItDoesNotRun() {
		assertThat(ClojureMain.main(files(":sc"), List.of("z"), false).entry())
			.isEqualTo(new ClojureMain.Script("scripts/s.clj", List.of("q", "z")));
		assertThat(ClojureMain.main(files(), List.of(), false).entry()).isEqualTo(new ClojureMain.Repl(List.of()));
		assertThat(ClojureMain.main(files(), List.of("-r", "x"), false).entry())
			.isEqualTo(new ClojureMain.Repl(List.of("x")));
		assertThatThrownBy(() -> main(List.of("-m"))).hasMessage("-m needs a namespace to run: -m my.app");
		assertThatThrownBy(() -> main(List.of("-m", "a b"))).hasMessage("-m takes a namespace name, got: a b");
		assertThatThrownBy(() -> main(List.of("-e", "(println 1)")))
			.hasMessage("clojure.main's -e is not supported: run a namespace (-m my.app) or a file");
	}

	@Test
	void execCallsTheFunctionWithTheMergedMap() {
		assertThat(exec(List.of(), ":run")).isEqualTo("""
				;; clj -X my.app/run
				(require 'my.app)
				(my.app/run (quote {:a 1, :b {:c 2}}))
				""");
		// a key path, a trailing map
		assertThat(exec(List.of(":a", "5", "[:b :d]", "3"), ":run")).contains("(quote {:a 5, :b {:c 2, :d 3}})")
			.contains("(set! *command-line-args* (seq (concat [\":a\" \"5\" \"[:b :d]\" \"3\"] *command-line-args*)))");
		assertThat(exec(List.of(":a", "5", "{:z 1}"), ":run")).contains("(quote {:a 5, :b {:c 2}, :z 1})");
		// the function on the command line wins; :ns-default and :ns-aliases qualify it
		assertThat(exec(List.of("my.app/run", ":q", "1"), ":run")).contains("(quote {:a 1, :b {:c 2}, :q 1})");
		assertThat(exec(List.of("run", ":k", "\"s\""), ":nsd")).contains("(my.app/run (quote {:a 9, :k \"s\"}))");
		assertThat(exec(List.of(), ":nsa")).contains("(my.app/run nil)");
		// both readings fit: the one with the function
		assertThat(exec(List.of("run", "b", "c", "{:m 1}"), ":nsd")).contains("(quote {:a 9, b c, :m 1})");
		assertThat(exec(List.of("{:m 1}", "{:n 2}"), ":run")).contains("(quote {:a 1, :b {:c 2}, {:m 1} {:n 2}})");
		// :exec-args merges across aliases, a nil one keeps what came before
		assertThat(exec(List.of(), ":run", ":e2", ":e3")).contains("(quote {:a 1, :b 3, :c 4})");
		assertThat(exec(List.of(":a", "nil", ":s", "x y"), ":run")).contains("(quote {:a nil, :b {:c 2}, :s x})");
	}

	@Test
	void execRefusesInTheOraclesWords() {
		assertThatThrownBy(() -> exec(List.of(":a"), ":run"))
			.hasMessage("Problem parsing arguments:  Insufficient input [:a]");
		assertThatThrownBy(() -> exec(List.of("nil"), ":run"))
			.hasMessage("Problem parsing arguments:  Insufficient input [nil]");
		assertThatThrownBy(() -> exec(List.of(), ":nsd"))
			.hasMessage("No function found on command line or in :exec-fn");
		assertThatThrownBy(() -> exec(List.of("a", "b"), ":nsd")).hasMessage("Key is missing value: b");
		assertThatThrownBy(() -> exec(List.of("unq"), ":run"))
			.hasMessage("Unqualified function can't be resolved: unq");
		assertThatThrownBy(() -> exec(List.of(":a", "\"open"), ":run")).hasMessage("Unreadable arg: \"\\\"open\"");
		assertThatThrownBy(() -> exec(List.of(), ":bad")).hasMessage("Expected function symbol: :kw");
	}

	@Test
	void aProjectTestRunRequiresEveryTestNamespaceBelowTheTestAliasesExtraPaths() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("deps.edn", "{:paths [\"src\"]}");
		files.put("test/b/z_test.clj", "(ns b.z-test)");
		files.put("test/a/deep/y_test.cljc", "; first\n(ns a.deep.y-test (:require [clojure.test]))");
		files.put("test/a/helper.clj", "(ns a.helper)");
		files.put("test/a/notes.txt", "(ns a.notes-test)");
		String source = program(ClojureMain.test(new MemoryClojureFiles(files).withAliases(":test"), null));
		assertThat(source).contains("(require 'clojure.test 'a.deep.y-test 'b.z-test)\n")
			.contains("(let [summary (clojure.test/run-tests 'a.deep.y-test 'b.z-test)]\n")
			.contains("(System/exit (if (and (pos? (:test summary)) (clojure.test/successful? summary)) 0 1))");
		assertThat(program(ClojureMain.test(new MemoryClojureFiles(files).withAliases(":test"), "test/a/helper.clj")))
			.contains("(require 'clojure.test 'a.helper)");
		assertThatThrownBy(() -> ClojureMain.test(new MemoryClojureFiles(files).withAliases(":nope"), null)).hasMessage(
				"the selected aliases [:nope] add no :extra-paths, where a project's test namespaces are looked for");
		files.remove("test/b/z_test.clj");
		files.remove("test/a/deep/y_test.cljc");
		assertThatThrownBy(() -> ClojureMain.test(new MemoryClojureFiles(files).withAliases(":test"), null))
			.hasMessage("no test namespace (a namespace whose name ends in -test) below test");
	}

}
