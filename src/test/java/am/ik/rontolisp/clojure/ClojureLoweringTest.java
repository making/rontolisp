package am.ik.rontolisp.clojure;

import java.util.List;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lowering table of {@code .kb/clojure-frontend.md}, row by row, as the Common Lisp
 * the front end emits. What the emitted forms DO on each backend is
 * {@code ClojureSpecE2eTest}'s business.
 */
class ClojureLoweringTest {

	private static String lowered(String source) {
		List<LispVal> forms = Clojure.read(source, null);
		return forms.stream().map(LispVal::print).collect(Collectors.joining("\n"));
	}

	@Test
	void everyIdentifierManglesBehindThePrefix() {
		assertThat(lowered("(def x 1) x")).isEqualTo("(SETQ |c%x| 1)\n|c%x|");
		assertThat(ClojureLowering.mangle("a:b")).isEqualTo("c%a%cb");
		assertThat(ClojureLowering.mangle("a%b")).isEqualTo("c%a%%b");
	}

	@Test
	void defnIsADefunCalledDirectly() {
		assertThat(lowered("(defn f [x] x) (f 1)")).isEqualTo("(DEFUN |c%f| (|c%x|) |c%x|)\n(|c%f| 1)");
		assertThat(lowered("(defn f [x] x) f")).isEqualTo("(DEFUN |c%f| (|c%x|) |c%x|)\n#'|c%f|");
	}

	@Test
	void letIsSequentialAndLoopIsALabelsSelfCall() {
		assertThat(lowered("(let [x 1 y x] y)")).isEqualTo("(LET* ((|c%x| 1) (|c%y| |c%x|)) |c%y|)");
		assertThat(lowered("(loop [a 0] (recur 1))")).contains("LABELS");
		assertThatThrownBy(() -> Clojure.read("(recur 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("recur outside loop");
	}

	@Test
	void fnAndAnonFnAreLambdas() {
		assertThat(lowered("((fn [a b] (+ a b)) 1 2)"))
			.isEqualTo("(FUNCALL (LAMBDA (|c%a| |c%b|) (+ |c%a| |c%b|)) 1 2)");
		assertThat(lowered("(map #(* % %) '(1 2))")).contains("MAPCAR").contains("NTH");
		assertThatThrownBy(() -> Clojure.read("%", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("outside the anon form");
	}

	@Test
	void coreCallsLowerToTheirCommonLispNames() {
		assertThat(lowered("(map + '(1 2))")).isEqualTo("(MAPCAR #'+ '(1 2))");
		assertThat(lowered("(filter odd? '(1 2 3))")).contains("REMOVE-IF-NOT");
		assertThat(lowered("(reduce + 0 '(1 2))")).contains(":INITIAL-VALUE");
		assertThat(lowered("(= 1 1)")).isEqualTo("(EQUAL 1 1)");
		assertThat(lowered("(cond (= 1 2) :one :else :fallback)")).contains(":FALLBACK");
	}

	@Test
	void vectorsQuotesAndKeywords() {
		assertThat(lowered("[1 :a]")).isEqualTo("(VECTOR 1 :A)");
		assertThat(lowered("'a")).isEqualTo("'|c%a|");
		assertThat(lowered(":a")).isEqualTo(":A");
		assertThat(lowered("(str \"a\" 1)")).contains("CONCATENATE");
	}

	@Test
	void mapsAndUnknownNamesAreRefusedByName() {
		assertThatThrownBy(() -> Clojure.read("{:a 1}", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("map literal");
		assertThatThrownBy(() -> Clojure.read("(nope 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name");
	}

	@Test
	void falseFoldsIntoNil() {
		assertThat(lowered("false")).isEqualTo("NIL");
		assertThat(lowered("true")).isEqualTo("T");
	}

	@Test
	void nsDefinesNothing() {
		assertThat(lowered("(ns foo) (def x 1) x")).isEqualTo("(SETQ |c%x| 1)\n|c%x|");
		assertThat(lowered("(ns foo (:require [clojure.string :as s])) (def x 1) x"))
			.isEqualTo("(SETQ |c%x| 1)\n|c%x|");
	}

	@Test
	void nthTakesTheCollectionFirst() {
		assertThat(lowered("(nth '(1 2 3) 1)")).isEqualTo("(NTH 1 '(1 2 3))");
	}

	@Test
	void quotTruncatesTowardZero() {
		assertThat(lowered("(quot 7 2)")).isEqualTo("(TRUNCATE 7 2)");
	}

}
