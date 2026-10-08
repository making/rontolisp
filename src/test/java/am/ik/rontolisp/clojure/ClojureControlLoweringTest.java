package am.ik.rontolisp.clojure;

import java.util.List;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lowering of {@code case}, {@code condp}, {@code if-some}/{@code when-some},
 * {@code while}, {@code locking} and {@code with-redefs}: what each refuses when it
 * lowers, in the oracle's words where it has some, and the shapes a backend choice or a
 * redefinable var change. What the forms do on every backend is
 * {@code ClojureSpecE2eTest}'s business.
 */
class ClojureControlLoweringTest {

	private static String lowered(String source) {
		return print(Clojure.read(source, null));
	}

	private static String loweredForWasm(String source) {
		return print(Clojure.read(source, null, null, ClojureFiles.NONE, false));
	}

	private static String print(List<LispVal> forms) {
		return forms.stream().map(LispVal::print).collect(Collectors.joining("\n"));
	}

	private static void refused(String source, String message) {
		assertThatThrownBy(() -> Clojure.read(source, null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining(message);
	}

	@Test
	void caseRefusesADuplicateConstantAsTheOracleCompilesIt() {
		refused("(case 1 1 :a 1 :b)", "Duplicate case test constant: 1");
		refused("(case 1 1 :a 1N :b)", "Duplicate case test constant: 1");
		refused("(case 1 (1 2) :a 2 :b)", "Duplicate case test constant: 2");
		refused("(case 1 (x y) :a (z x) :b)", "Duplicate case test constant: x");
		refused("(case 1 \"s\" :a \"s\" :b)", "Duplicate case test constant: s");
		refused("(case 1 nil :a nil :b)", "Duplicate case test constant: ");
		refused("(case 1 [1] :a ((1)) :b)", "Duplicate case test constant: (1)");
		refused("(case 1 {:a 1 :b 2} :a {:b 2 :a 1} :b)", "Duplicate case test constant: {:b 2, :a 1}");
		refused("(case 1 #{1 2} :a #{2 1} :b)", "Duplicate case test constant: #{2 1}");
		refused("(case 1 -0.0 :a 0.0 :b :c)", "Duplicate case test constant: 0.0");
		// a double and an integer are two constants, and NaN is no duplicate of
		// itself, like the oracle's
		assertThat(lowered("(case 1 1.0 :a 1 :b)")).contains("(EQL |__clojure_0| 1.0)")
			.contains("(EQL |__clojure_0| 1)");
		assertThat(lowered("(case 1 ##NaN :a ##NaN :b :c)"))
			.contains("(IF NIL (LIST :C%KEYWORD \"a\") (IF NIL (LIST :C%KEYWORD \"b\")");
	}

	@Test
	void caseRefusesAnEmptyAlternativeListAndAMissingValue() {
		refused("(case 1 () :e :no)", "case test constant () lists no alternatives");
		refused("(case)", "Wrong number of args (0) passed to: clojure.core/case");
	}

	@Test
	void caseComparesEachConstantByItsKind() {
		String out = lowered(
				"(fn [x] (case x 1 :i \\a :c \"s\" :s :k :kw y :sym nil :nil false :f true :t ##NaN :nan [1] :v))");
		assertThat(out).contains("(EQL |__clojure_1| 1)")
			.contains("(EQUAL |__clojure_1| \"s\")")
			.contains("(EQUAL |__clojure_1| '(:C%KEYWORD \"k\"))")
			.contains("(EQ |__clojure_1| '|c%y|)")
			.contains("(NULL |__clojure_1|)")
			.contains("(EQ |__clojure_1| RONTOLISP::%CLOJURE-FALSE)")
			.contains("(EQ |__clojure_1| T)")
			.contains("(IF NIL (LIST :C%KEYWORD \"nan\")")
			.contains("(RONTOLISP::%CLOJURE-EQUAL |__clojure_1| (VECTOR '1))")
			.contains("RONTOLISP::%CLOJURE-ILLEGAL-ARGUMENT-EXCEPTION");
	}

	@Test
	void condpIfSomeAndWhenSomeRefuseInTheOraclesWords() {
		refused("(condp =)", "Wrong number of args (1) passed to: clojure.core/condp");
		refused("(if-some x 1 2)", "if-some requires a vector for its binding");
		refused("(if-some [x 1 y 2] x)", "if-some requires exactly 2 forms in binding vector");
		refused("(if-some [x] x)", "if-some requires exactly 2 forms in binding vector");
		refused("(if-some [x 1] 1 2 3)", "if-some requires 1 or 2 forms after binding vector");
		refused("(if-some [x 1])", "Wrong number of args (1) passed to: clojure.core/if-some");
		refused("(when-some [x 1 y 2] x)", "when-some requires exactly 2 forms in binding vector");
	}

	@Test
	void whileAndLockingBodiesAreNoRecurTail() {
		refused("(loop [i 0] (while (< i 3) (recur (inc i))))", "Can only recur from tail position");
		refused("(loop [i 0] (locking :k (recur (inc i))))", "Cannot recur across try");
	}

	@Test
	void lockingHoldsAMonitorOnlyWhereThreadsExist() {
		assertThat(lowered("(locking :k 1)")).contains("(RONTOLISP:WITH-MUTEX ((RONTOLISP::%CLOJURE-MONITOR");
		assertThat(loweredForWasm("(locking :k 1)")).doesNotContain("WITH-MUTEX").doesNotContain("%CLOJURE-MONITOR");
	}

	@Test
	void aDefnNoWithRedefsNamesStaysADirectCall() {
		assertThat(lowered("(defn f [] 1) (defn g [] (f))")).contains("(DEFUN |c%g| NIL (|c%f|))")
			.doesNotContain("SETQ |c%f|");
	}

	@Test
	void aDefnAWithRedefsNamesIsCalledThroughItsVar() {
		// the pre-scan finds the name anywhere in the file, below the definition too
		String out = lowered("(defn f [] 1) (defn g [] (f)) (defn t [] (with-redefs [f (fn [] 2)] (g)))");
		assertThat(out).contains("(SETQ |c%f| #'|c%f|)")
			.contains("(DEFUN |c%g| NIL (RONTOLISP::%CLOJURE-CALL |c%f| (LIST)))")
			.contains("(UNWIND-PROTECT (PROGN (SETQ |c%f| |__clojure_");
		// ^:redef, the oracle's own opt-out of direct linking, does the same
		assertThat(lowered("(defn ^:redef f [] 1) (defn g [] (f))")).contains("(SETQ |c%f| #'|c%f|)")
			.contains("(RONTOLISP::%CLOJURE-CALL |c%f| (LIST))");
	}

	@Test
	void withRedefsRefusesWhatHasNoVarRootToReplace() {
		refused("(let [h (fn [] 1)] (with-redefs [h (fn [] 2)] (h)))", "Unable to resolve var: h in this context");
		refused("(with-redefs [nope 1] 1)", "Unable to resolve var: nope in this context");
		refused("(with-redefs [inc dec] (inc 1))", "with-redefs of a clojure.core var is not supported: inc");
		refused("(defmacro mm [] 1) (with-redefs [mm (fn [] 2)] 3)", "with-redefs of a macro is not supported");
		refused("(defmulti area :shape) (with-redefs [area (fn [_] 0)] 1)", "only a def or defn var can be redefined");
		refused("(with-redefs f 1)", "with-redefs takes its bindings in a vector");
	}

	@Test
	void aSessionRefusesWithRedefsOfADefnAnEarlierInputCallsDirectly() {
		ClojureSession session = new ClojureSession();
		session.read("(defn f [] 1)");
		assertThatThrownBy(() -> session.read("(with-redefs [f (fn [] 2)] (f))")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("an earlier input defined it as a function called directly; define it ^:redef");
		// one marked ^:redef is redefinable from its first input
		ClojureSession marked = new ClojureSession();
		marked.read("(defn ^:redef f [] 1)");
		assertThat(print(marked.read("(with-redefs [f (fn [] 2)] (f))").get(0).forms())).contains("(UNWIND-PROTECT");
	}

}
