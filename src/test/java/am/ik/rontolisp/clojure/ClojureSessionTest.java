package am.ik.rontolisp.clojure;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.ClojureMacroTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClojureSessionTest {

	@Test
	void aLaterBufferCallsWhatAnEarlierOneDefined() {
		ClojureSession session = new ClojureSession();
		assertThat(session.read("(defn twice [x] (* 2 x))").get(0).forms().stream().map(LispVal::print).toList())
			.containsExactly("(SETQ RONTOLISP::%CLOJURE-FALSE '|false|)", "(DEFUN |c%twice| (|c%x|) (* 2 |c%x|))");
		List<ClojureTopLevel> call = session.read("(twice 21)");
		assertThat(call.get(0).forms().stream().map(LispVal::print).toList()).containsExactly("(|c%twice| 21)");
	}

	@Test
	void aLaterBufferMemoizesWhatAnEarlierOneDefined() {
		// (def p (memoize p)) in a later buffer captures the earlier buffer's
		// function cell, like the same two forms in one file.
		ClojureSession session = new ClojureSession();
		session.read("(defn b52sess [x] (* x 2))");
		List<String> redefined = session.read("(def b52sess (memoize b52sess))")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList();
		assertThat(redefined).anyMatch(form -> form.contains("#'|c%b52sess|"));
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
		session.read("(ns b19sess)");
		List<String> auto = session.read("::checking")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList();
		assertThat(auto).anyMatch(form -> form.contains("\"b19sess/checking\""));
		session.read("(in-ns 'other)");
		List<String> moved = session.read("::checking")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList();
		assertThat(moved).anyMatch(form -> form.contains("\"other/checking\""));
	}

	@Test
	void theTestRuntimeStartsOnceAheadOfTheFirstTestBuffer() {
		// clojure.test in a session: the runtime start travels ahead of the
		// buffer that first uses it, the test registers under the session's
		// namespace, and a later buffer runs it
		ClojureSession session = new ClojureSession();
		session.read("(ns b55sess (:require [clojure.test :refer :all]))");
		List<String> defined = session.read("(deftest b55-s (is (= 1 1)))")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList();
		assertThat(defined).filteredOn(form -> form.contains("%CLOJURE-TEST-INIT")).hasSize(1);
		assertThat(defined).anyMatch(form -> form.contains("(RONTOLISP::%CLOJURE-TEST-REGISTER \"b55sess\" \"b55-s\""));
		List<String> run = session.read("(run-tests)")
			.stream()
			.flatMap(top -> top.forms().stream())
			.map(LispVal::print)
			.toList();
		assertThat(run).noneMatch(form -> form.contains("%CLOJURE-TEST-INIT"));
		assertThat(run).anyMatch(form -> form.contains("(RONTOLISP::%CLOJURE-TEST-RUN-TESTS (LIST \"b55sess\")"));
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
	}

}
