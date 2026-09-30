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

	private static final String FALSE_BINDING = "(SETQ RONTOLISP::%CLOJURE-FALSE '|false|)\n";

	@Test
	void everyIdentifierManglesBehindThePrefix() {
		assertThat(lowered("(def x 1) x")).isEqualTo(FALSE_BINDING + "(SETQ |c%x| 1)\n|c%x|");
		assertThat(ClojureLowering.mangle("a:b")).isEqualTo("c%a%cb");
		assertThat(ClojureLowering.mangle("a%b")).isEqualTo("c%a%%b");
	}

	@Test
	void defnIsADefunCalledDirectly() {
		assertThat(lowered("(defn f [x] x) (f 1)")).isEqualTo(FALSE_BINDING + "(DEFUN |c%f| (|c%x|) |c%x|)\n(|c%f| 1)");
		assertThat(lowered("(defn f [x] x) f")).isEqualTo(FALSE_BINDING + "(DEFUN |c%f| (|c%x|) |c%x|)\n#'|c%f|");
	}

	@Test
	void letIsSequentialAndLoopIsALabelsSelfCall() {
		assertThat(lowered("(let [x 1 y x] y)")).isEqualTo(FALSE_BINDING + "(LET* ((|c%x| 1) (|c%y| |c%x|)) |c%y|)");
		assertThat(lowered("(loop [a 0] (recur 1))")).contains("LABELS");
		assertThatThrownBy(() -> Clojure.read("(recur 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("recur outside loop");
	}

	@Test
	void fnAndAnonFnAreLambdas() {
		assertThat(lowered("((fn [a b] (+ a b)) 1 2)"))
			.isEqualTo(FALSE_BINDING + "(FUNCALL (LAMBDA (|c%a| |c%b|) (+ |c%a| |c%b|)) 1 2)");
		assertThat(lowered("(map #(* % %) '(1 2))")).contains("MAPCAR").contains("NTH");
		assertThatThrownBy(() -> Clojure.read("%", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("outside the anon form");
	}

	@Test
	void coreCallsLowerToTheirCommonLispNames() {
		assertThat(lowered("(map + '(1 2))")).isEqualTo(FALSE_BINDING + "(MAPCAR #'+ '(1 2))");
		assertThat(lowered("(filter odd? '(1 2 3))")).contains("REMOVE-IF-NOT");
		assertThat(lowered("(reduce + 0 '(1 2))")).contains(":INITIAL-VALUE");
		assertThat(lowered("(= 1 1)")).isEqualTo(FALSE_BINDING + "(IF (EQUAL 1 1) T RONTOLISP::%CLOJURE-FALSE)");
		assertThat(lowered("(cond (= 1 2) :one :else :fallback)")).contains(":FALLBACK");
	}

	@Test
	void vectorsQuotesAndKeywords() {
		assertThat(lowered("[1 :a]")).isEqualTo(FALSE_BINDING + "(VECTOR 1 :A)");
		assertThat(lowered("'a")).isEqualTo(FALSE_BINDING + "'|c%a|");
		assertThat(lowered(":a")).isEqualTo(FALSE_BINDING + ":A");
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
	void falseIsDistinctFromNil() {
		assertThat(lowered("false")).isEqualTo(FALSE_BINDING + "RONTOLISP::%CLOJURE-FALSE");
		assertThat(lowered("true")).isEqualTo(FALSE_BINDING + "T");
		assertThat(lowered("nil")).isEqualTo(FALSE_BINDING + "NIL");
		assertThat(lowered("'false")).isEqualTo(FALSE_BINDING + "'|false|");
	}

	@Test
	void conditionalsTreatFalseAndNilAsFalsey() {
		assertThat(lowered("(def x false) (if x 1 2)")).isEqualTo(FALSE_BINDING
				+ "(SETQ |c%x| RONTOLISP::%CLOJURE-FALSE)\n"
				+ "(LET ((|__clojure_0| |c%x|)) (IF (OR (NULL |__clojure_0|) (EQ |__clojure_0| RONTOLISP::%CLOJURE-FALSE)) 2 1))");
		assertThat(lowered("(def x 1) (when x x)"))
			.contains("(LET ((|__clojure_0| |c%x|)) (IF (OR (NULL |__clojure_0|)");
		assertThat(lowered("(and 1 2)")).isEqualTo(FALSE_BINDING
				+ "(LET ((|__clojure_0| 1)) (IF (OR (NULL |__clojure_0|) (EQ |__clojure_0| RONTOLISP::%CLOJURE-FALSE)) |__clojure_0| 2))");
		assertThat(lowered("(or nil 3)")).isEqualTo(FALSE_BINDING
				+ "(LET ((|__clojure_0| NIL)) (IF (OR (NULL |__clojure_0|) (EQ |__clojure_0| RONTOLISP::%CLOJURE-FALSE)) 3 |__clojure_0|))");
		assertThat(lowered("(and)")).isEqualTo(FALSE_BINDING + "T");
		assertThat(lowered("(or)")).isEqualTo(FALSE_BINDING + "NIL");
	}

	@Test
	void booleanBuiltinsAnswerTrueOrFalse() {
		assertThat(lowered("(nil? nil)")).isEqualTo(FALSE_BINDING + "(IF (NULL NIL) T RONTOLISP::%CLOJURE-FALSE)");
		assertThat(lowered("(false? nil)"))
			.isEqualTo(FALSE_BINDING + "(IF (EQ NIL RONTOLISP::%CLOJURE-FALSE) T RONTOLISP::%CLOJURE-FALSE)");
		assertThat(lowered("(true? nil)")).isEqualTo(FALSE_BINDING + "(IF (EQ NIL T) T RONTOLISP::%CLOJURE-FALSE)");
		assertThat(lowered("(boolean? nil)")).contains("(LET ((|__clojure_0| NIL)) (IF (OR (EQ |__clojure_0| T)");
		assertThat(lowered("(not nil)")).contains("(LET ((|__clojure_0| NIL)) (IF (OR (NULL |__clojure_0|)");
		assertThat(lowered("(map false? '(1))"))
			.isEqualTo(FALSE_BINDING + "(MAPCAR (LAMBDA (|c%pred|) (EQ |c%pred| RONTOLISP::%CLOJURE-FALSE)) '(1))");
	}

	@Test
	void printAndStrSpellTrueFalseNil() {
		assertThat(lowered("(str nil)")).isEqualTo(FALSE_BINDING
				+ "(CONCATENATE 'STRING (LET ((|__clojure_0| NIL)) (IF (EQ |__clojure_0| RONTOLISP::%CLOJURE-FALSE) \"false\" (IF (EQ |__clojure_0| T) \"true\" (IF (NULL |__clojure_0|) \"\" (PRINC-TO-STRING |__clojure_0|))))))");
		assertThat(lowered("(println true)")).contains("(PRINC (CONCATENATE 'STRING")
			.contains("\"true\"")
			.contains("PRINC-TO-STRING");
	}

	@Test
	void nsDefinesNothing() {
		assertThat(lowered("(ns foo) (def x 1) x")).isEqualTo(FALSE_BINDING + "(SETQ |c%x| 1)\n|c%x|");
		assertThat(lowered("(ns foo (:require [clojure.string :as s])) (def x 1) x"))
			.isEqualTo(FALSE_BINDING + "(SETQ |c%x| 1)\n|c%x|");
	}

	@Test
	void nthTakesTheCollectionFirst() {
		assertThat(lowered("(nth '(1 2 3) 1)")).isEqualTo(FALSE_BINDING + "(NTH 1 '(1 2 3))");
	}

	@Test
	void quotTruncatesTowardZero() {
		assertThat(lowered("(quot 7 2)")).isEqualTo(FALSE_BINDING + "(TRUNCATE 7 2)");
	}

}
