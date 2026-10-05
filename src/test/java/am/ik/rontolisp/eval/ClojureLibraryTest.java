package am.ik.rontolisp.eval;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.clojure.Clojure;
import am.ik.rontolisp.clojure.ClojureArms;
import am.ik.rontolisp.clojure.ClojureFiles;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClojureLibraryTest {

	@Test
	void theLibraryDefinesThePrinterHelpers() {
		assertThat(ClojureLibrary.isClojureFunction("RONTOLISP::%CLOJURE-STR-OF")).isTrue();
		assertThat(ClojureLibrary.isClojureFunction("RONTOLISP::%CLOJURE-WRITE-DATUM")).isTrue();
		assertThat(ClojureLibrary.isClojureFunction("RONTOLISP::%CLOJURE-CALL")).isTrue();
		assertThat(ClojureLibrary.isClojureFunction("PRINC")).isFalse();
	}

	@Test
	void aProgramReferencingAPrinterHelperGetsTheLibrarySpliced() {
		List<LispVal> program = List.of(new am.ik.rontolisp.LispCons(
				new am.ik.rontolisp.LispSymbol("RONTOLISP::%CLOJURE-STR-OF"),
				new am.ik.rontolisp.LispCons(new am.ik.rontolisp.LispSymbol("X"), am.ik.rontolisp.LispNil.INSTANCE)));
		List<LispVal> processed = ClojureLibrary.process(program);
		assertThat(processed.size()).isGreaterThan(program.size());
		String text = processed.stream().map(LispVal::print).collect(Collectors.joining("\n"));
		assertThat(text).contains("DEFUN RONTOLISP::%CLOJURE-STR-OF");
	}

	@Test
	void aProgramBuildingNoSortedCollectionSplicesTheLibraryWithoutItsSortedArms() {
		// the interpreter's library keeps every arm (what a session reads next is
		// unknown); a compiled program that builds no sorted collection gets the library
		// with its arms folded away, and so its own forms
		assertThat(defun(ClojureLibrary.forms(), "RONTOLISP::%CLOJURE-STRICT-SEQ"))
			.contains("(RONTOLISP::%CLOJURE-SORTED-P COLL)");
		List<LispVal> plain = LispReader
			.readAllFromString("(rontolisp::%clojure-str-of (if (rontolisp::%clojure-sorted-p x) 1 x) \"\" nil)");
		List<LispVal> processed = ClojureLibrary.process(plain);
		assertThat(defun(processed, "RONTOLISP::%CLOJURE-STRICT-SEQ")).doesNotContain("SORTED");
		assertThat(processed.get(processed.size() - 1).print()).isEqualTo("(RONTOLISP::%CLOJURE-STR-OF X \"\" NIL)");
		List<LispVal> sorted = LispReader.readAllFromString(
				"(rontolisp::%clojure-str-of (rontolisp::%clojure-sorted-make t nil (list 1)) \"\" nil)");
		assertThat(defun(ClojureLibrary.process(sorted), "RONTOLISP::%CLOJURE-STRICT-SEQ"))
			.contains("(RONTOLISP::%CLOJURE-SORTED-P COLL)");
	}

	@Test
	void aProgramMakingNoMatcherSplicesNthWithoutItsMatcherArm() {
		// only re-matcher makes a matcher: a program naming it keeps nth's group arm,
		// any other compiles nth as before matchers were read
		assertThat(defun(ClojureLibrary.forms(), "RONTOLISP::%CLOJURE-NTH"))
			.contains("(RONTOLISP::%CLOJURE-MATCHER-VALUE-P COLL)");
		List<LispVal> plain = ClojureLibrary
			.process(Clojure.read("(println (nth [1 2] 1) (re-find #\"a\" \"a\"))", null));
		assertThat(defun(plain, "RONTOLISP::%CLOJURE-NTH")).doesNotContain("MATCHER");
		List<LispVal> matcher = ClojureLibrary
			.process(Clojure.read("(println (nth (re-matcher #\"(a)\" \"a\") 1))", null));
		assertThat(defun(matcher, "RONTOLISP::%CLOJURE-NTH")).contains("(RONTOLISP::%CLOJURE-MATCHER-VALUE-P COLL)");
	}

	@Test
	void vecRefusesANonCollectionAsRuntimeExceptionOnlyWhereAClassIsRead() {
		// the oracle's vec casts to an array before it seqs: the argument check is the
		// refusal family's view, so a program reading no class compiles the bare coercion
		List<LispVal> plain = Clojure.read("(println (vec 5))", null);
		assertThat(plain.get(plain.size() - 1).print()).contains("%CLOJURE-VEC-ARG");
		List<LispVal> processed = ClojureLibrary.process(plain);
		assertThat(processed.get(processed.size() - 1).print()).doesNotContain("%CLOJURE-VEC-ARG")
			.contains("%CLOJURE-REALIZE-ALL");
		List<LispVal> reading = ClojureLibrary
			.process(Clojure.read("(println (try (vec 5) (catch IllegalArgumentException e :x)))", null));
		assertThat(defun(reading, "RONTOLISP::%CLOJURE-VEC-ARG")).contains("(RONTOLISP::%CLOJURE-RUNTIME-EXCEPTION");
	}

	@Test
	void aProgramMakingNoUnboundRootSplicesTheLibraryWithoutItsUnboundArms() {
		// the unbound-root arms (the printer's, IFn's) go like the sorted ones: only a
		// program storing an unbound root (a declare, a value-less def) keeps them
		assertThat(defun(ClojureLibrary.forms(), "RONTOLISP::%CLOJURE-WRITE"))
			.contains("(RONTOLISP::%CLOJURE-UNBOUND-P X)");
		List<LispVal> plain = LispReader.readAllFromString("(rontolisp::%clojure-str-of x \"\" nil)");
		assertThat(defun(ClojureLibrary.process(plain), "RONTOLISP::%CLOJURE-WRITE")).doesNotContain("UNBOUND");
		List<LispVal> unbound = LispReader.readAllFromString(
				"(setq x (rontolisp::%clojure-unbound \"user/x\")) (rontolisp::%clojure-str-of x \"\" nil)");
		assertThat(defun(ClojureLibrary.process(unbound), "RONTOLISP::%CLOJURE-WRITE"))
			.contains("(RONTOLISP::%CLOJURE-UNBOUND-P X)");
	}

	@Test
	void aProgramMakingNoStreamSplicesTheLibraryWithoutItsStreamArms() {
		// the printer's and str's stream arms go like the unbound ones: only a program
		// that can hold a stream (a read of *out*, a StringWriter, ...) keeps them
		assertThat(defun(ClojureLibrary.forms(), "RONTOLISP::%CLOJURE-WRITE"))
			.contains("(RONTOLISP::%CLOJURE-STREAM-P X)");
		List<LispVal> plain = Clojure.read("(prn (str 1) (with-out-str (print 2)))", null);
		List<LispVal> processed = ClojureLibrary.process(plain);
		assertThat(defun(processed, "RONTOLISP::%CLOJURE-WRITE")).doesNotContain("STREAM-P");
		assertThat(defun(processed, "RONTOLISP::%CLOJURE-STR-OF")).doesNotContain("%CLOJURE-STREAM-P");
		List<LispVal> stream = Clojure.read("(prn *out*)", null);
		assertThat(defun(ClojureLibrary.process(stream), "RONTOLISP::%CLOJURE-WRITE"))
			.contains("(RONTOLISP::%CLOJURE-STREAM-P X)");
	}

	@Test
	void classOfAStreamIsAnArmAProgramMakingNoStreamSheds() {
		// the library keeps its own defuns (the tree-shaker prunes them later); the arm
		// is
		// in the program's forms
		List<LispVal> plain = Clojure.read("(prn (class 1) (class [1]))", null);
		assertThat(
				ClojureLibrary.process(plain).stream().map(LispVal::print).filter(text -> !text.startsWith("(DEFUN ")))
			.noneMatch(text -> text.contains("%CLOJURE-STREAM-CLASS"));
		List<LispVal> stream = Clojure.read("(prn (class *out*))", null);
		assertThat(
				ClojureLibrary.process(stream).stream().map(LispVal::print).filter(text -> !text.startsWith("(DEFUN ")))
			.anyMatch(text -> text.contains("(RONTOLISP::%CLOJURE-STREAM-CLASS"));
	}

	@Test
	void aProgramReadingNoStreamDepthShedsTheRebindingPairs() {
		// with-out-str, a binding of *out* and an agent action rebind the counters, which
		// only a #'*out* / #'*in* / #'*agent* site reads: without one the pairs go and
		// the
		// program compiles as before the counters existed
		List<LispVal> plain = Clojure.read("(println (with-out-str (print 1))) (binding [*out* *out*] (println 2))",
				null);
		assertThat(ClojureLibrary.process(plain).stream().map(LispVal::print))
			.noneMatch(text -> text.contains("%CLOJURE-OUT-DEPTH"));
		// a program naming no library function goes through the strip too
		List<LispVal> bare = LispReader.readAllFromString("(defvar rontolisp::%clojure-out-depth 0)"
				+ " (let ((*standard-output* s) (rontolisp::%clojure-out-depth (+ rontolisp::%clojure-out-depth 1)))"
				+ " (princ 1))");
		assertThat(ClojureLibrary.process(bare).stream().map(LispVal::print))
			.containsExactly("(LET ((*STANDARD-OUTPUT* S)) (PRINC 1))");
		List<LispVal> read = Clojure.read("(println (with-out-str (print (thread-bound? #'*out*))))", null);
		assertThat(ClojureLibrary.process(read).stream().map(LispVal::print))
			.anyMatch(text -> text.contains("(RONTOLISP::%CLOJURE-OUT-DEPTH (+ RONTOLISP::%CLOJURE-OUT-DEPTH 1))"))
			.anyMatch(text -> text.equals("(DEFVAR RONTOLISP::%CLOJURE-OUT-DEPTH 0)"));
	}

	@Test
	void aProgramReadingNoLoadSpecialShedsTheirSwitches() {
		// ns and in-ns switch *ns*, and a require rebinds *ns*, *file* and *source-path*
		// around a namespace's init; only a read of one keeps them, so a program reading
		// none compiles as before they had values
		List<LispVal> plain = Clojure.read("(ns a) (defn f [] 1) (in-ns 'b) (a/f) (do (in-ns 'c) (println 1))", null);
		assertThat(plain.stream().map(LispVal::print)).anyMatch(text -> text.contains("RONTOLISP::%CLOJURE-NS"));
		List<String> processed = ClojureLibrary.process(plain).stream().map(LispVal::print).toList();
		assertThat(processed).noneMatch(text -> text.matches("(?s).*RONTOLISP::%CLOJURE-NS[ )].*"))
			.contains("(|c%a/f|)")
			.anyMatch(text -> text.contains("(PROGN NIL (PROGN (RONTOLISP::%CLOJURE-WRITE-DATUM 1"));
		List<LispVal> read = Clojure.read("(ns a) (println (str *ns*))", null);
		assertThat(ClojureLibrary.process(read).stream().map(LispVal::print)).contains(
				"(DEFVAR RONTOLISP::%CLOJURE-NS (RONTOLISP::%CLOJURE-NS-OBJECT \"user\"))",
				"(SETQ RONTOLISP::%CLOJURE-NS (RONTOLISP::%CLOJURE-NS-OBJECT \"a\"))");
	}

	@Test
	void aProgramNamingNoPrintFlagSplicesTheLibraryWithoutItsPrintArms() {
		// the printer reads *print-length*, *print-level* and *print-readably* through
		// arms: the interpreter keeps them, a compiled program naming none of the three
		// gets the printer it had before they existed
		assertThat(defun(ClojureLibrary.forms(), "RONTOLISP::%CLOJURE-WRITE")).contains(
				"(RONTOLISP::%CLOJURE-PRINT-DEEP-P X)", "(RONTOLISP::%CLOJURE-PRINT-CUT-P X)",
				"RONTOLISP::%CLOJURE-WRITE-NESTED");
		List<LispVal> plain = LispReader.readAllFromString("(rontolisp::%clojure-str-of x \"\" nil)");
		List<LispVal> processed = ClojureLibrary.process(plain);
		assertThat(defun(processed, "RONTOLISP::%CLOJURE-WRITE")).doesNotContain("%CLOJURE-PRINT-",
				"%CLOJURE-WRITE-NESTED");
		assertThat(defun(processed, "RONTOLISP::%CLOJURE-PRINT")).doesNotContain("%CLOJURE-PRINT-READABLE");
		List<LispVal> flagged = Clojure.read("(binding [*print-length* 2] (prn [1 2 3]))", null);
		assertThat(defun(ClojureLibrary.process(flagged), "RONTOLISP::%CLOJURE-WRITE"))
			.contains("(RONTOLISP::%CLOJURE-PRINT-CUT-P X)", "RONTOLISP::%CLOJURE-WRITE-NESTED");
	}

	@Test
	void aProgramNamingNoPrintMetaAndMakingNoQualifiedKeySplicesThePrinterWithoutTheirArms() {
		assertThat(defun(ClojureLibrary.forms(), "RONTOLISP::%CLOJURE-WRITE"))
			.contains("RONTOLISP::%CLOJURE-PRINT-META-P", "(RONTOLISP::%CLOJURE-PRINT-NS-MAP-P X)");
		String plain = defun(ClojureLibrary.process(Clojure.read("(prn {:a 1} 'b)", null)),
				"RONTOLISP::%CLOJURE-WRITE");
		assertThat(plain).doesNotContain("%CLOJURE-PRINT-META-P", "%CLOJURE-PRINT-NS-MAP-P");
		String meta = defun(ClojureLibrary.process(Clojure.read("(binding [*print-meta* true] (prn [1]))", null)),
				"RONTOLISP::%CLOJURE-WRITE");
		assertThat(meta).contains("%CLOJURE-PRINT-META-P").doesNotContain("%CLOJURE-PRINT-NS-MAP-P");
		for (String qualified : List.of("(prn :a/b)", "(prn 'a/b)", "(prn ::b)", "(prn (keyword \"a\" \"b\"))",
				"(prn (read-string \"x\"))")) {
			assertThat(defun(ClojureLibrary.process(Clojure.read(qualified, null)), "RONTOLISP::%CLOJURE-WRITE"))
				.as(qualified)
				.contains("%CLOJURE-PRINT-NS-MAP-P")
				.doesNotContain("%CLOJURE-PRINT-META-P");
		}
	}

	@Test
	void everyLibraryFunctionBuildingAKeywordOrSymbolFromAComputedSpellingMakesANamespaceMap() {
		// a qualified key reaches a map only through a literal or one of these: a defun
		// interning a symbol or wrapping a computed spelling as a keyword, or calling one
		// that does. A program naming one keeps the namespace-map arm.
		Map<String, LispVal> defuns = new HashMap<>();
		for (LispVal form : ClojureLibrary.forms()) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head && head.name().equals("DEFUN")
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
				defuns.put(name.name(), rest.cdr());
			}
		}
		Set<String> builders = new TreeSet<>();
		defuns.forEach((name, body) -> {
			if (buildsIdent(body)) {
				builders.add(name);
			}
		});
		// class answers a class name as a keyword, and a class name has no slash (the
		// class rows' bases neither, nor a host class's keys); ns-name a namespace's name
		// as a symbol, which has none either
		Set<String> slashless = Set.of("RONTOLISP::%CLOJURE-EXCEPTION-CLASS", "RONTOLISP::%CLOJURE-CLASS-KEYWORDS",
				"RONTOLISP::%CLOJURE-HOST-CLASS-KEYS", "RONTOLISP::%CLOJURE-NS-NAME");
		builders.removeAll(slashless);
		boolean grew = true;
		while (grew) {
			grew = false;
			for (Map.Entry<String, LispVal> defun : defuns.entrySet()) {
				if (!builders.contains(defun.getKey()) && !slashless.contains(defun.getKey())
						&& mentionsAny(defun.getValue(), builders)) {
					builders.add(defun.getKey());
					grew = true;
				}
			}
		}
		assertThat(builders).contains("RONTOLISP::%CLOJURE-KEYWORD-2", "RONTOLISP::%CLOJURE-READ-STRING");
		assertThat(builders).allSatisfy(name -> assertThat(ClojureArms.Family.NAMESPACE_MAP.isProducer(name))
			.as(name + " builds a keyword or symbol")
			.isTrue());
	}

	/** Whether the code interns a symbol or wraps a computed spelling as a keyword. */
	private static boolean buildsIdent(LispVal code) {
		LispVal rest = code;
		while (rest instanceof LispCons cell) {
			if (cell.car() instanceof LispSymbol head
					&& (head.name().equals("INTERN") || head.name().equals(":C%KEYWORD")
							&& cell.cdr() instanceof LispCons next && !(next.car() instanceof LispString))) {
				return true;
			}
			if (buildsIdent(cell.car())) {
				return true;
			}
			rest = cell.cdr();
		}
		return false;
	}

	private static boolean mentionsAny(LispVal code, Set<String> names) {
		LispVal rest = code;
		while (rest instanceof LispCons cell) {
			if (mentionsAny(cell.car(), names)) {
				return true;
			}
			rest = cell.cdr();
		}
		return rest instanceof LispSymbol symbol && names.contains(symbol.name());
	}

	@Test
	void aProgramReadingNoConditionsClassSplicesEveryRefusalAsThePlainError() {
		// a refusal carries the class the oracle throws only where a catch by class,
		// class or instance? can read it: anywhere else it is the error it signalled
		// before, and the refusal's condition class is gone with it
		assertThat(defun(ClojureLibrary.forms(), "RONTOLISP::%CLOJURE-STRICT-SEQ"))
			.contains("(RONTOLISP::%CLOJURE-ILLEGAL-ARGUMENT-EXCEPTION \"seq needs a collection\")");
		for (String source : List.of("(println (first 5))", "(println (try (first 5) (catch Throwable e :x)))")) {
			List<LispVal> plain = ClojureLibrary.process(Clojure.read(source, null));
			assertThat(defun(plain, "RONTOLISP::%CLOJURE-STRICT-SEQ")).as(source)
				.contains("(ERROR \"seq needs a collection\")")
				.doesNotContain("EXCEPTION");
			assertThat(plain.stream().map(LispVal::print)).as(source)
				.noneMatch(text -> text.startsWith("(DEFINE-CONDITION RONTOLISP::%CLOJURE-REFUSAL "));
		}
		for (String source : List.of("(println (try (first 5) (catch IllegalArgumentException e :x)))",
				"(println (instance? Exception 5))", "(println (try (first 5) (catch Exception e (class e))))")) {
			List<LispVal> reading = ClojureLibrary.process(Clojure.read(source, null));
			assertThat(defun(reading, "RONTOLISP::%CLOJURE-STRICT-SEQ")).as(source)
				.contains("(RONTOLISP::%CLOJURE-ILLEGAL-ARGUMENT-EXCEPTION \"seq needs a collection\")");
		}
	}

	@Test
	void aProgramNamingNoJavaOperatorComparesWithoutTheHostCollectionArm() {
		// only a java: operator hands a program a host collection: without one, = and the
		// sorted = keep the bodies they had before a host collection counted
		assertThat(defun(ClojureLibrary.forms(), "RONTOLISP::%CLOJURE-EQUAL"))
			.contains("(RONTOLISP::%CLOJURE-HOST-EQUAL-P A B)");
		assertThat(defun(ClojureLibrary.forms(), "RONTOLISP::%CLOJURE-SORTED-EQUAL"))
			.contains("(RONTOLISP::%CLOJURE-HOST-EQUAL-P A B)");
		List<LispVal> plain = ClojureLibrary.process(Clojure.read("(prn (= [1] (sorted-set 1) (list 1)))", null));
		assertThat(defun(plain, "RONTOLISP::%CLOJURE-EQUAL")).doesNotContain("HOST").endsWith("(T (EQUAL A B))))");
		assertThat(defun(plain, "RONTOLISP::%CLOJURE-SORTED-EQUAL")).doesNotContain("HOST");
		List<LispVal> host = ClojureLibrary.process(Clojure.read("(prn (= [1] (java.util.ArrayList. [1])))", null));
		assertThat(defun(host, "RONTOLISP::%CLOJURE-EQUAL")).contains("(RONTOLISP::%CLOJURE-HOST-EQUAL-P A B)");
	}

	@Test
	void aProgramNamingNoJavaOperatorSeqsAndCountsWithoutTheHostCollectionArms() {
		// only a java: operator hands a program a host collection: without one, seq and
		// the lowered count, empty?, get and contains? keep what they lowered to before
		String verbs = "(prn (seq [1]) (count [1]) (empty? []) (get {1 2} 1) (contains? #{1} 1) (map :a [{:a 1}])"
				+ " (keys {1 2}) (vals {1 2}))";
		assertThat(defun(ClojureLibrary.forms(), "RONTOLISP::%CLOJURE-STRICT-SEQ"))
			.contains("(RONTOLISP::%CLOJURE-HOST-SEQABLE-P COLL)");
		List<LispVal> plain = ClojureLibrary.process(Clojure.read(verbs, null));
		assertThat(defun(plain, "RONTOLISP::%CLOJURE-STRICT-SEQ")).doesNotContain("HOST")
			.endsWith("(T (ERROR \"seq needs a collection\"))))");
		assertThat(program(plain, verbs)).doesNotContain("HOST");
		String withJava = "(def al (java.util.ArrayList. [1])) " + verbs;
		List<LispVal> host = ClojureLibrary.process(Clojure.read(withJava, null));
		assertThat(defun(host, "RONTOLISP::%CLOJURE-STRICT-SEQ")).contains("(RONTOLISP::%CLOJURE-HOST-SEQABLE-P COLL)");
		assertThat(program(host, withJava)).contains("(RONTOLISP::%CLOJURE-HOST-COUNT ",
				"(RONTOLISP::%CLOJURE-HOST-EMPTY-P ", "(RONTOLISP::%CLOJURE-HOST-GET ",
				"(RONTOLISP::%CLOJURE-HOST-CONTAINS-P ", "(RONTOLISP::%CLOJURE-HOST-KEYS ");
	}

	@Test
	void aProgramNamingNoJavaOperatorRunsTheMapVerbsWithoutTheHostMapArms() {
		// only a java: operator hands a program a host map: without one, find,
		// select-keys, reduce-kv, conj, merge and merge-with keep what they lowered to
		String verbs = "(prn (find {1 2} 1) (select-keys {1 2} [1]) (reduce-kv (fn [a k v] (+ a v)) 0 {1 2})"
				+ " (conj {} {1 2}) (merge {} {1 2}) (merge-with + {} {1 2}) (apply merge-with + [{} {1 2}]))";
		List<String> library = List.of("RONTOLISP::%CLOJURE-FIND", "RONTOLISP::%CLOJURE-KV-PAIRS",
				"RONTOLISP::%CLOJURE-MERGE-ENTRY-PLIST");
		List<LispVal> plain = ClojureLibrary.process(Clojure.read(verbs, null));
		for (String name : library) {
			assertThat(defun(ClojureLibrary.forms(), name)).contains("(RONTOLISP::%CLOJURE-HOST-SEQABLE-P ");
			assertThat(defun(plain, name)).doesNotContain("HOST");
		}
		assertThat(program(plain, verbs)).doesNotContain("HOST");
		String withJava = "(def hm (java.util.HashMap.)) " + verbs;
		List<LispVal> host = ClojureLibrary.process(Clojure.read(withJava, null));
		for (String name : library) {
			assertThat(defun(host, name)).contains("(RONTOLISP::%CLOJURE-HOST-SEQABLE-P ");
		}
		assertThat(program(host, withJava)).contains("(RONTOLISP::%CLOJURE-HOST-SELECT-KEYS ",
				"(RONTOLISP::%CLOJURE-HOST-ENTRY-PLIST ", "(RONTOLISP::%CLOJURE-HOST-TABLE ");
	}

	@Test
	void aProgramNamingNoJavaOperatorPrintsWithoutTheHostCollectionArm() {
		// only a java: operator hands a program a host collection: without one, the
		// printer keeps what it wrote before and nothing reaches the host writer
		String print = "(prn [1] {1 2} #{3} '(4))";
		String arm = "(RONTOLISP::%CLOJURE-HOST-SEQABLE-P X)";
		assertThat(defun(ClojureLibrary.forms(), "RONTOLISP::%CLOJURE-WRITE")).contains(arm);
		List<LispVal> plain = ClojureLibrary.process(Clojure.read(print, null));
		assertThat(defun(plain, "RONTOLISP::%CLOJURE-WRITE")).doesNotContain("HOST-SEQABLE")
			.doesNotContain("WRITE-HOST");
		String withJava = "(def al (java.util.ArrayList. [1])) " + print;
		List<LispVal> host = ClojureLibrary.process(Clojure.read(withJava, null));
		assertThat(defun(host, "RONTOLISP::%CLOJURE-WRITE")).contains(arm);
		assertThat(defun(host, "RONTOLISP::%CLOJURE-WRITE-HOST")).isNotEmpty();
	}

	@Test
	void aHostExceptionIsMadeOnlyByAJavaOperatorWhereTheHostIs() {
		// on the JVM a java: operator keeps the host-exception arms: throw signals a host
		// Throwable as itself, a catch takes a failed host call by the host's class and
		// binds the host's exception, and the program's exceptions are host-backed;
		// compiled for wasm, where java: is a call-time error, the same program lowers
		// and splices as one naming no java: operator
		String source = "(println (try (Integer/parseInt \"x\") (catch NumberFormatException e (.getMessage e))))"
				+ " (throw (Exception. \"m\"))";
		String arm = "(RONTOLISP::%CLOJURE-HOST-THROWABLE-P X)";
		assertThat(defun(ClojureLibrary.forms(), "RONTOLISP::%CLOJURE-THROW")).contains(arm);
		List<LispVal> host = ClojureLibrary.process(Clojure.read(source, null, null, ClojureFiles.NONE, true), true);
		assertThat(defun(host, "RONTOLISP::%CLOJURE-THROW")).contains(arm);
		assertThat(defun(host, "RONTOLISP::%CLOJURE-CATCHES")).contains("(RONTOLISP::%CLOJURE-HOST-FAILURE-P C)");
		assertThat(host.stream().map(LispVal::print)).anyMatch(text -> text.contains("(RONTOLISP::%CLOJURE-CAUGHT "))
			.anyMatch(text -> text.startsWith("(DEFUN C%E-HOST-OF "))
			.anyMatch(text -> text.startsWith("(DEFINE-CONDITION C%E-EXCEPTION (JAVA:JAVA-EXCEPTION)"));
		List<LispVal> wasm = ClojureLibrary.process(Clojure.read(source, null, null, ClojureFiles.NONE, false), false);
		assertThat(defun(wasm, "RONTOLISP::%CLOJURE-THROW")).doesNotContain("HOST");
		assertThat(defun(wasm, "RONTOLISP::%CLOJURE-CATCHES")).doesNotContain("HOST");
		assertThat(defun(wasm, "RONTOLISP::%CLOJURE-LISP-VALUE-P")).doesNotContain("INSTANCE");
		assertThat(defun(wasm, "RONTOLISP::%CLOJURE-CAUGHT")).doesNotContain("HOST");
		assertThat(wasm.stream().map(LispVal::print)).noneMatch(text -> text.contains("(RONTOLISP::%CLOJURE-CAUGHT "))
			.noneMatch(text -> text.contains("C%E-HOST"))
			.anyMatch(text -> text.startsWith("(DEFINE-CONDITION C%E-EXCEPTION (ERROR)"));
	}

	/** The processed forms of the program itself: the tail past the spliced library. */
	private static String program(List<LispVal> processed, String source) {
		int own = Clojure.read(source, null).size();
		return processed.subList(processed.size() - own, processed.size())
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"));
	}

	private static String defun(List<LispVal> forms, String name) {
		return forms.stream()
			.map(LispVal::print)
			.filter(text -> text.startsWith("(DEFUN " + name + " "))
			.findFirst()
			.orElseThrow();
	}

	@Test
	void aProgramWithoutAPrinterHelperIsReturnedUnchanged() {
		List<LispVal> program = List.of(new am.ik.rontolisp.LispCons(new am.ik.rontolisp.LispSymbol("PRINC"),
				new am.ik.rontolisp.LispCons(new am.ik.rontolisp.LispString("hi"), am.ik.rontolisp.LispNil.INSTANCE)));
		assertThat(ClojureLibrary.process(program)).isSameAs(program);
	}

}
