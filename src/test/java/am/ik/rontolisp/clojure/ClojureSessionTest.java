package am.ik.rontolisp.clojure;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.ClojureMacroTime;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.eval.SourceSession;
import am.ik.rontolisp.reader.Features;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClojureSessionTest {

	/** How an input's forms start: under the *e record, their value the *1 record. */
	private static final String INPUT = "(HANDLER-BIND ((ERROR #'RONTOLISP::%CLOJURE-REPL-ERROR))"
			+ " (RONTOLISP::%CLOJURE-REPL-RESULT (PROGN ";

	/** An input of the given forms, as the session evaluates it. */
	private static String input(String forms) {
		return INPUT + forms + ")))";
	}

	@Test
	void aLaterBufferCallsWhatAnEarlierOneDefined() {
		ClojureSession session = new ClojureSession();
		assertThat(session.read("(defn twice [x] (* 2 x))").get(0).forms().stream().map(LispVal::print).toList())
			.hasSize(2)
			.startsWith("(SETQ RONTOLISP::%CLOJURE-FALSE '|false|)")
			.last()
			.asString()
			.startsWith(INPUT + "(DEFUN |c%twice| (|c%x|) (* 2 |c%x|)) (RONTOLISP::%CLOJURE-VAR \"user/twice\"");
		List<ClojureTopLevel> call = session.read("(twice 21)");
		assertThat(call.get(0).forms().stream().map(LispVal::print).toList()).containsExactly(input("(|c%twice| 21)"));
	}

	@Test
	void anInputRecordsItsValueAndAnNsInputNil() {
		// the oracle's REPL records every input's value as *1 and an exception as *e;
		// an ns shows nothing and records nil, after switching *ns*
		ClojureSession session = new ClojureSession();
		session.read("1");
		assertThat(forms(session.read("(ns sess.a)")))
			.contains(input("(SETQ RONTOLISP::%CLOJURE-NS (RONTOLISP::%CLOJURE-NS-OBJECT \"sess.a\")) NIL"));
		assertThat(forms(session.read("(in-ns 'sess.b)"))).containsExactly(
				input("(PROGN (SETQ RONTOLISP::%CLOJURE-NS (RONTOLISP::%CLOJURE-NS-OBJECT \"sess.b\")) NIL)"));
	}

	@Test
	void aDeclaredNameStaysOpenForALaterBuffer() {
		// a later buffer may define what an earlier one only declared: the declare
		// stores the unbound root only into an unbound cell, a call stays direct and a
		// value read takes the function cell once a defn filled it
		ClojureSession session = new ClojureSession();
		assertThat(forms(session.read("(declare later)"))).anyMatch(form -> form
			.contains("(UNLESS (BOUNDP '|c%later|) (SETQ |c%later| (RONTOLISP::%CLOJURE-UNBOUND \"user/later\")))"));
		assertThat(forms(session.read("(later 1)"))).contains(input("(|c%later| 1)"));
		assertThat(forms(session.read("(map later [1])")))
			.anyMatch(form -> form.contains("(IF (FBOUNDP '|c%later|) #'|c%later| |c%later|)"));
	}

	@Test
	void aSetOfAssertInOneBufferSwitchesOffTheAssertsOfTheNext() {
		// the oracle reads *assert* where assert expands, which in a REPL is when the
		// later input is read
		ClojureSession session = new ClojureSession();
		assertThat(forms(session.read("(assert false)"))).anyMatch(form -> form.contains("Assert failed: false"));
		session.read("(set! *assert* false)");
		assertThat(forms(session.read("(assert false)"))).containsExactly(input("NIL"));
		session.read("(set! *assert* true)");
		assertThat(forms(session.read("(assert false)"))).anyMatch(form -> form.contains("Assert failed: false"));
	}

	/** What the interpreter prints running the buffers through one session. */
	private static String runSession(String... buffers) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		SourceSession session = new SourceSession(SourceLanguage.CLOJURE);
		for (String buffer : buffers) {
			for (SourceSession.Step step : session.read(buffer, Features.INTERPRETER)) {
				for (LispVal form : step.forms()) {
					evaluator.eval(form);
				}
			}
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	private static List<String> forms(List<ClojureTopLevel> tops) {
		return tops.stream().flatMap(top -> top.forms().stream()).map(LispVal::print).toList();
	}

	@Test
	void theFalseBindingGoesAheadOfTheRuntimesOfTheFirstBuffer() {
		// the specials travel ahead of the buffer that first uses one, and a flag's
		// root is the false object, so the false binding goes ahead of them too
		assertThat(runSession("(prn *print-meta* *print-dup*)")).isEqualTo("false false\n");
		assertThat(forms(new ClojureSession().read("(prn *print-meta*)")).get(0))
			.isEqualTo("(SETQ RONTOLISP::%CLOJURE-FALSE '|false|)");
	}

	@Test
	void aLaterBufferDefmultiOfAMultimethodKeepsTheEarlierOne() {
		// the oracle's defmulti defines only when the var holds no multimethod, so a
		// REPL re-entry changes nothing (neither the dispatch function nor the table)
		ClojureSession session = new ClojureSession();
		session.read("(defmulti dispatch :k)");
		List<String> again = session.read("(defmulti dispatch :j :default :other)")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList();
		assertThat(again).noneMatch(form -> form.contains("dispatch"));
		session.read("(defn dispatch [x] x)");
		List<String> redefined = session.read("(defmulti dispatch :j)")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList();
		assertThat(redefined).anyMatch(form -> form.contains("|c%dispatch%methods|"));
	}

	@Test
	void aLaterBufferMemoizesWhatAnEarlierOneDefined() {
		// (def p (memoize p)) in a later buffer captures the earlier buffer's
		// function cell, like the same two forms in one file.
		ClojureSession session = new ClojureSession();
		session.read("(defn memoized [x] (* x 2))");
		List<String> redefined = session.read("(def memoized (memoize memoized))")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList();
		assertThat(redefined).anyMatch(form -> form.contains("#'|c%memoized|"));
	}

	@Test
	void aLaterBufferVarSeesTheMetadataAnEarlierOneRecorded() {
		// the metadata a definition recorded travels with the session, so a #'
		// typed later carries the earlier buffer's docstring and evaluated store
		ClojureSession session = new ClojureSession();
		session.read("(defn ^{:test (fn [] 1)} documented \"Sess doc.\" [x] x)");
		List<String> var = session.read("(:doc (meta #'documented))")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList();
		assertThat(var).anyMatch(form -> form.contains("(RONTOLISP::%CLOJURE-VAR \"user/documented\"")
				&& form.contains("|c%documented%meta|"));
	}

	@Test
	void aBufferRegistersForReadingOnlyTheRecordClassesItAdds() {
		// a session that reads registers each record class once, ahead of the
		// buffer that adds it, so a literal typed later reads back as the record
		ClojureSession session = new ClojureSession();
		session.read("(defrecord RecS [a])");
		List<String> reading = session.read("(read-string \"#user.RecS{:a 1}\")")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList();
		assertThat(reading.get(0))
			.isEqualTo("(RONTOLISP::%CLOJURE-READ-REGISTER '((\"user.RecS\" \"RecS\" (\"a\") T)))");
		List<String> later = session.read("(defrecord RecT [b]) (read-string \"1\")")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList();
		assertThat(later.get(0)).isEqualTo("(RONTOLISP::%CLOJURE-READ-REGISTER '((\"user.RecT\" \"RecT\" (\"b\") T)))");
		assertThat(session.read("(read-string \"2\")")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList()).noneMatch(form -> form.contains("READ-REGISTER"));
	}

	@Test
	void aLaterBufferExpandsAMacroAnEarlierOneDefined() {
		ClojureSession session = new ClojureSession();
		session.setMacroEvaluator(ClojureMacroTime.create());
		List<String> defined = session.read("(defmacro sx-unless [c t] (list 'if c nil t))")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList();
		assertThat(defined).anyMatch(form -> form.contains("|c%sx-unless%macro|"));
		List<String> call = session.read("(sx-unless false 42)")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList();
		assertThat(call).anyMatch(form -> form.contains(" 42 NIL))"));
	}

	@Test
	void aLaterBufferSeesTheNsAnEarlierOneDeclared() {
		ClojureSession session = new ClojureSession();
		session.read("(ns autons)");
		List<String> auto = session.read("::checking")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList();
		assertThat(auto).anyMatch(form -> form.contains("\"autons/checking\""));
		session.read("(in-ns 'other)");
		List<String> moved = session.read("::checking")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList();
		assertThat(moved).anyMatch(form -> form.contains("\"other/checking\""));
	}

	@Test
	void aBufferDefinesThePredicateOfEachClassItCatchesFirst() {
		// a catch's predicate travels ahead of the buffer that first catches its class,
		// with the exception reader while no buffer built an exception; the exception
		// runtime's own reader replaces it once one does
		ClojureSession session = new ClojureSession();
		List<String> first = forms(session.read("(try 1 (catch IllegalStateException e 2))"));
		assertThat(first).filteredOn(form -> form.startsWith("(DEFUN |C%E-CATCHES-java.lang.IllegalStateException|"))
			.hasSize(1);
		assertThat(first).contains("(DEFUN C%E-PARTS (|c|) (DECLARE (IGNORE |c|)) NIL)");
		List<String> again = forms(session.read("(try 3 (catch IllegalStateException e 4))"));
		assertThat(again).noneMatch(form -> form.contains("DEFUN"));
		List<String> built = forms(session.read("(try (throw (ex-info \"m\" {})) (catch Exception e 5))"));
		assertThat(built).anyMatch(form -> form.startsWith("(DEFINE-CONDITION C%E-EXCEPTION"))
			.anyMatch(form -> form.startsWith("(DEFUN |C%E-CATCHES-java.lang.Exception|"))
			.noneMatch(form -> form.contains("(DECLARE (IGNORE |c|)) NIL)"));
	}

	@Test
	void aBufferSpellingAThrowableOrStreamClassJoinsTheClassChainWalkOnce() {
		// a hierarchy runtime without a class spelling carries no class rows; the buffer
		// that first dispatches on a throwable class adds the class rows' isa? and
		// readers and the rows resolved so far, a later one only the rows it adds
		ClojureSession session = new ClojureSession();
		List<String> plain = forms(session.read("(defmulti f class) (defmethod f :default [x] :d)"));
		assertThat(plain).anyMatch(form -> form.startsWith("(DEFUN C%H-ISA? "))
			.noneMatch(form -> form.contains("%CLOJURE-CLASS-"));
		List<String> chained = forms(session.read("(defmethod f IllegalStateException [e] :ise)"));
		assertThat(chained)
			.anyMatch(form -> form.startsWith("(DEFUN C%H-ISA? ") && form.contains("(RONTOLISP::%CLOJURE-CLASS-ISA "))
			.anyMatch(form -> form.startsWith("(DEFUN C%H-ANCESTORS ")
					&& form.contains("(RONTOLISP::%CLOJURE-CLASS-ANCESTORS "))
			.anyMatch(form -> form.startsWith("(SETQ C%H-SUPERS ")
					&& form.contains("(\"java.lang.IllegalStateException\" \"java.lang.RuntimeException\")")
					&& form.contains("(\"java.lang.Throwable\" \"java.lang.Object\" \"java.io.Serializable\")"));
		List<String> streams = forms(session.read("(defmethod f java.io.Writer [w] :w)"));
		assertThat(streams).noneMatch(form -> form.contains("DEFUN"))
			.anyMatch(form -> form.startsWith("(SETQ C%H-SUPERS (APPEND ")
					&& form.contains("(\"java.io.StringWriter\" \"java.io.Writer\")")
					&& !form.contains("\"java.lang.Throwable\" \"java.lang.Object\""));
		// a reader in a later buffer brings every class class answers, the core kinds too
		List<String> read = forms(session.read("(ancestors (class 1))"));
		assertThat(read).anyMatch(form -> form.startsWith("(SETQ C%H-SUPERS (APPEND ")
				&& form.contains("(\"number\" . T)") && form.contains("\"java.lang.ArithmeticException\""));
	}

	@Test
	void aMacroOfALaterBufferCallsWhatAnEarlierOneDefined() {
		// the oracle's REPL evaluates each input before reading the next, so a macro body
		// calls a helper and reads a def of an earlier input
		assertThat(runSession("(defn sx-helper [x] (list 'inc x)) (def sx-n 10)",
				"(defmacro sx-m [x] (list '+ sx-n (sx-helper x)))", "(println (sx-m 1))"))
			.isEqualTo("12\n");
	}

	@Test
	void aSessionReadsAClassKeywordsSupersInALaterBuffer() {
		// the hierarchy runtime of a first buffer without a class takes the class rows'
		// readers when a later one spells a class, and a later record joins the rows
		assertThat(runSession("(derive :s/a :s/b) (println (parents :s/a) (ancestors :s/none))",
				"(println (parents NumberFormatException) (isa? (class \"a\") Object))",
				"(defrecord SR [x]) (println (ancestors SR) (ancestors (class (->SR 1))))"))
			.isEqualTo("#{:s/b} nil\n#{:java.lang.IllegalArgumentException} true\n"
					+ "#{:java.lang.Object} #{:java.lang.Object}\n");
	}

	@Test
	void aSessionWalksAHostClassObjectOnceABufferNamesTheHost() {
		// the hierarchy runtime of a buffer without the host takes the host class walk
		// when a later buffer builds a host object; a buffer after that reads it
		assertThat(runSession("(derive :s/a :s/b) (println (parents :s/a))", "(def l (java.util.ArrayList.))",
				"(println (count (parents (class l))) (isa? (class l) (class l)) (isa? :s/a :s/b))",
				"(println (isa? (class l) java.util.List))"))
			.isEqualTo("#{:s/b}\n5 true true\ntrue\n");
	}

	@Test
	void theTestRuntimeStartsOnceAheadOfTheFirstTestBuffer() {
		// clojure.test in a session: the runtime start travels ahead of the
		// buffer that first uses it, the test registers under the session's
		// namespace, and a later buffer runs it
		ClojureSession session = new ClojureSession();
		session.read("(ns testns (:require [clojure.test :refer :all]))");
		List<String> defined = session.read("(deftest a-test (is (= 1 1)))")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList();
		assertThat(defined).filteredOn(form -> form.contains("%CLOJURE-TEST-INIT")).hasSize(1);
		assertThat(defined).anyMatch(form -> form.contains("(RONTOLISP::%CLOJURE-TEST-REGISTER \"testns\" \"a-test\""));
		List<String> run = session.read("(run-tests)")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList();
		assertThat(run).noneMatch(form -> form.contains("%CLOJURE-TEST-INIT"));
		assertThat(run).anyMatch(form -> form.contains("(RONTOLISP::%CLOJURE-TEST-RUN-TESTS (LIST \"testns\")"));
	}

	@Test
	void aBufferRequiresAProjectNamespaceAndALaterOneCallsIt() {
		// the session reads from the working directory's source path (src without a
		// deps.edn); the namespace loads once, with the buffer that first requires it
		ClojureSession session = new ClojureSession(
				new MemoryClojureFiles(Map.of("src/app/lib.clj", "(ns app.lib) (defn f [x] (inc x))")));
		session.setMacroEvaluator(ClojureMacroTime.create());
		List<String> required = session.read("(require '[app.lib :as l])")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList();
		assertThat(required).anyMatch(form -> form.contains("(DEFUN |c%app.lib/f| (|c%x|) (+ |c%x| 1))"));
		assertThat(session.read("(l/f 1)").get(0).forms().stream().map(LispVal::print).toList())
			.containsExactly(input("(|c%app.lib/f| 1)"));
		List<String> again = session.read("(require 'app.lib)")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList();
		assertThat(again).noneMatch(form -> form.contains("DEFUN"));
	}

	@Test
	void aBufferRequiringAPrintingNamespaceLoadsItBehindAFlag() {
		// the namespace's statements ride with the buffer that first requires it, as an
		// init behind the loaded flag; a later :reload buffer calls it outright
		ClojureSession session = new ClojureSession(new MemoryClojureFiles(
				Map.of("src/app/lib.clj", "(ns app.lib) (println \"hi\") (def v 1) (defn f [x] x)")));
		session.setMacroEvaluator(ClojureMacroTime.create());
		List<String> required = session.read("(require '[app.lib :as l])")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList();
		assertThat(required).anyMatch(form -> form.contains("(DEFVAR |c%app.lib%loaded| NIL)"))
			.anyMatch(form -> form.contains("(SETQ |c%app.lib%init| (LAMBDA NIL (FUNCALL |c%app.lib%init-1|)))"))
			.anyMatch(form -> form.contains("(UNLESS |c%app.lib%loaded| (LET (" + LOADING
					+ ") (FUNCALL |c%app.lib%init|))" + " (SETQ |c%app.lib%loaded| T))"));
		List<String> reloaded = session.read("(require '[app.lib] :reload)")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList();
		assertThat(reloaded).anyMatch(form -> form
			.contains("(PROGN (LET (" + LOADING + ") (FUNCALL |c%app.lib%init|)) (SETQ |c%app.lib%loaded| T))"));
	}

	/** What a load of app.lib binds around its init. */
	private static final String LOADING = "(RONTOLISP::%CLOJURE-NS RONTOLISP::%CLOJURE-NS)"
			+ " (RONTOLISP::%CLOJURE-FILE \"app/lib.clj\") (RONTOLISP::%CLOJURE-SOURCE-PATH \"lib.clj\")";

	@Test
	void isCompleteCountsBracketsStringsAndComments() {
		assertThat(ClojureSession.isComplete("(defn f [x] x)")).isTrue();
		assertThat(ClojureSession.isComplete("(defn f [x]")).isFalse();
		assertThat(ClojureSession.isComplete("[1 2")).isFalse();
		assertThat(ClojureSession.isComplete("{:a 1")).isFalse();
		assertThat(ClojureSession.isComplete("\"abc")).isFalse();
		assertThat(ClojureSession.isComplete("(defn f [x] x) ; comment")).isTrue();
		assertThat(ClojureSession.isComplete("(a b")).isFalse();
		// Complete but wrong still answers true: the error belongs to read.
		assertThat(ClojureSession.isComplete("(nope 1)")).isTrue();
		assertThat(ClojureSession.isComplete("'")).isFalse();
		// a character literal's first character is no bracket, quote or comment
		assertThat(ClojureSession.isComplete("(str \\( \\[ \\{)")).isTrue();
		assertThat(ClojureSession.isComplete("[\\; \\\" 1]")).isTrue();
		assertThat(ClojureSession.isComplete("[\\)")).isFalse();
		// a trailing discard waits for the datum after it, like the oracle's REPL;
		// one inside a collection is part of it
		assertThat(ClojureSession.isComplete("#_ 1")).isFalse();
		assertThat(ClojureSession.isComplete("#_ 1 2")).isTrue();
		assertThat(ClojureSession.isComplete("[1 #_ 2]")).isTrue();
	}

	@Test
	void aSessionReadsReaderConditionalsLikeTheOraclesRepl() {
		// clj's REPL reads with {:read-cond :allow}: a branch taken, a splice, and an
		// input taking none waits for the next datum
		ClojureSession session = new ClojureSession();
		assertThat(session.read("#?(:cljs 1 :clj 2)").get(0).forms().stream().map(LispVal::print).toList())
			.contains(input("2"));
		assertThat(ClojureSession.isComplete("#?(:clj 1)")).isTrue();
		assertThat(ClojureSession.isComplete("#?(:cljs 1)")).isFalse();
		assertThat(ClojureSession.isComplete("#?(:cljs 1) 7")).isTrue();
		assertThat(ClojureSession.isComplete("#?")).isFalse();
		assertThat(ClojureSession.isComplete("#?@")).isFalse();
		assertThat(ClojureSession.isComplete("[#?@(:clj [1")).isFalse();
	}

}
