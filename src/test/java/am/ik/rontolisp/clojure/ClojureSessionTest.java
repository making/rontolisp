package am.ik.rontolisp.clojure;

import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.ClojureMacroTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClojureSessionTest {

	@Test
	void aLaterBufferCallsWhatAnEarlierOneDefined() {
		ClojureSession session = new ClojureSession();
		assertThat(session.read("(defn twice [x] (* 2 x))").get(0).forms().stream().map(LispVal::print).toList())
			.hasSize(3)
			.startsWith("(SETQ RONTOLISP::%CLOJURE-FALSE '|false|)", "(DEFUN |c%twice| (|c%x|) (* 2 |c%x|))")
			.last()
			.asString()
			.startsWith("(RONTOLISP::%CLOJURE-VAR \"user/twice\"");
		List<ClojureTopLevel> call = session.read("(twice 21)");
		assertThat(call.get(0).forms().stream().map(LispVal::print).toList()).containsExactly("(|c%twice| 21)");
	}

	@Test
	void aDeclaredNameStaysOpenForALaterBuffer() {
		// a later buffer may define what an earlier one only declared: the declare
		// stores the unbound root only into an unbound cell, a call stays direct and a
		// value read takes the function cell once a defn filled it
		ClojureSession session = new ClojureSession();
		assertThat(forms(session.read("(declare later)"))).anyMatch(form -> form
			.contains("(UNLESS (BOUNDP '|c%later|) (SETQ |c%later| (RONTOLISP::%CLOJURE-UNBOUND \"user/later\")))"));
		assertThat(forms(session.read("(later 1)"))).contains("(|c%later| 1)");
		assertThat(forms(session.read("(map later [1])")))
			.anyMatch(form -> form.contains("(IF (FBOUNDP '|c%later|) #'|c%later| |c%later|)"));
	}

	private static List<String> forms(List<ClojureTopLevel> tops) {
		return tops.stream().flatMap(top -> top.forms().stream()).map(LispVal::print).toList();
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
		assertThat(required).contains("(DEFUN |c%app.lib/f| (|c%x|) (+ |c%x| 1))");
		assertThat(session.read("(l/f 1)").get(0).forms().stream().map(LispVal::print).toList())
			.containsExactly("(|c%app.lib/f| 1)");
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
			.anyMatch(form -> form
				.contains("(UNLESS |c%app.lib%loaded| (FUNCALL |c%app.lib%init|) (SETQ |c%app.lib%loaded| T))"));
		List<String> reloaded = session.read("(require '[app.lib] :reload)")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList();
		assertThat(reloaded)
			.anyMatch(form -> form.contains("(PROGN (FUNCALL |c%app.lib%init|) (SETQ |c%app.lib%loaded| T))"));
	}

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

}
