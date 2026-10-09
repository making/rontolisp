package am.ik.rontolisp.clojure;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.ClojureMacroTime;
import am.ik.rontolisp.reader.LispReadException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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
	void aLocalNamedLikeASpecialBindsASymbolOfItsOwn() {
		// a local spelled like a user ^:dynamic var -- even one defined below it --
		// or like a stream alias binds c%name%local, so it stays lexical; the var
		// keeps its symbol, and a local named like nothing special is untouched
		assertThat(lowered("(defn f [*x*] *x*) (def ^:dynamic *x* 1)"))
			.contains("(DEFUN |c%f| (|c%*x*%local|) |c%*x*%local|)");
		assertThat(lowered("(def ^:dynamic *x* 1) (let [*x* 2] (binding [*x* 3] *x*))"))
			.contains("(LET* ((|c%*x*%local| 2)) (LET* ((|c%*x*| 3)");
		assertThat(lowered("(let [*out* 5] *out*)")).contains("(LET* ((|c%*out*%local| 5)) |c%*out*%local|)");
		assertThat(lowered("(defn f [*x*] *x*)")).contains("(DEFUN |c%f| (|c%*x*|) |c%*x*|)");
		assertThat(lowered("(ns other) (def ^:dynamic *x* 1) (defn f [*x*] *x*)")).contains("(|c%*x*|) |c%*x*|)");
	}

	@Test
	void aStringBoundIsTruncatedAtRunTimeUnlessItIsALiteralInteger() {
		// subs, .substring and .charAt take a double or ratio bound as its truncation:
		// a literal integer is the plain verb it was, any other bound goes through
		// %clojure-string-bound
		String bound = "(RONTOLISP::%CLOJURE-STRING-BOUND ";
		assertThat(lowered("(subs \"hello\" 1 3) (.substring \"hello\" 1) (.charAt \"hello\" 0)"))
			.doesNotContain("STRING-BOUND");
		assertThat(lowered("(defn f [s i j] (subs s i j))"))
			.contains("(RONTOLISP::%CLOJURE-SUBS |c%s| " + bound + "|c%i|) " + bound + "|c%j|))");
		assertThat(lowered("(defn f [s i] (subs s 1 i))")).contains("|c%s| 1 " + bound + "|c%i|))");
		// a literal number inside the int range is truncated at lower time
		assertThat(lowered("(defn f [s] (subs s 1.5 -2.5))")).contains("(RONTOLISP::%CLOJURE-SUBS |c%s| 1 -2)")
			.doesNotContain("STRING-BOUND");
		assertThat(lowered("(defn f [s] (subs s 3/2))")).contains("(RONTOLISP::%CLOJURE-SUBS |c%s| 1)");
		assertThat(lowered("(defn f [s] (subs s 1e20))")).contains(bound + "1.0e20)");
		assertThat(lowered("(defn f [s i] (.substring s i))")).contains("(RONTOLISP::%CLOJURE-SUBS");
		assertThat(lowered("(defn f [s i] (.charAt s i))")).contains("(RONTOLISP::%CLOJURE-CHAR-AT")
			.contains(bound + "|c%i|)");
		assertThat(lowered("(def g subs)")).contains(bound + "|c%subs-from|)");
	}

	@Test
	void aStringMethodOfAReceiverNotKnownToBeAStringIsTheReflectiveAlias() {
		// the oracle calls .substring / .charAt of a receiver it cannot type by
		// reflection, which refuses a non-number bound as another class than a typed
		// call (and subs) does: only the receiver a literal or a known class types
		assertThat(lowered("(defn f [s i] (.substring s i))")).contains("(RONTOLISP::%CLOJURE-SUBS-BY-REFLECTION ");
		assertThat(lowered("(defn f [s i] (.charAt s i))")).contains("(RONTOLISP::%CLOJURE-CHAR-AT-BY-REFLECTION ");
		assertThat(lowered("(defn f [i] (.substring \"hello\" i) (.charAt \"hello\" i))"))
			.contains("(RONTOLISP::%CLOJURE-SUBS |__clojure_")
			.contains("(RONTOLISP::%CLOJURE-CHAR-AT |__clojure_")
			.doesNotContain("BY-REFLECTION");
		assertThat(lowered("(defn f [s i] (subs s i))")).doesNotContain("BY-REFLECTION");
	}

	@Test
	void defLoneStringIsTheValueNotADocstring() {
		assertThat(lowered("(def x \"hello\") x")).isEqualTo(FALSE_BINDING + "(SETQ |c%x| \"hello\")\n|c%x|");
		assertThat(lowered("(def x \"doc\" 1) x")).isEqualTo(FALSE_BINDING + "(SETQ |c%x| 1)\n|c%x|");
	}

	@Test
	void defnIsADefunCalledDirectly() {
		assertThat(lowered("(defn f [x] x) (f 1)")).isEqualTo(FALSE_BINDING + "(DEFUN |c%f| (|c%x|) |c%x|)\n(|c%f| 1)");
		assertThat(lowered("(defn f [x] x) f")).isEqualTo(FALSE_BINDING + "(DEFUN |c%f| (|c%x|) |c%x|)\n#'|c%f|");
	}

	@Test
	void defAfterDefnCapturesTheFunctionCell() {
		// (def p (memoize p)) after a (defn p ...) captures the function cell:
		// the value lowers against the OLD FUNCTION binding (#'|c%p|), not the
		// still-unbound value cell, and only then does the name become a VARIABLE.
		assertThat(lowered("(defn p [x] x) (def p (memoize p))")).contains("#'|c%p|");
		assertThat(lowered("(defn p [x] x) (def p p)")).contains("(SETQ |c%p| #'|c%p|)");
		assertThat(lowered("(defn p [x] x) (defonce p (memoize p))")).contains("#'|c%p|");
	}

	@Test
	void aRedefinedDefnGetsAFreshNamePerDefinition() {
		// A later defn of the same name wins everywhere on the compiled
		// backends. Each definition lowers to its own defun (the first keeps the
		// bare name, later ones take a %defN suffix no identifier spells), the
		// call sites below each definition call the newest, and a value position
		// captures the definition current at that point.
		assertThat(lowered("(defn f [] 1) (def g f) (defn f [] 2) (g) (f)")).contains("(DEFUN |c%f| NIL 1)")
			.contains("(SETQ |c%g| #'|c%f|)")
			.contains("(DEFUN |c%f%def2| NIL 2)")
			.contains("(FUNCALL |c%g|)")
			.contains("(|c%f%def2|)");
		// a top-level call between the definitions calls the older one
		assertThat(lowered("(defn f [] 1) (f) (defn f [] 2) (f)")).contains("(|c%f|)").contains("(|c%f%def2|)");
		// a redefined dynamic defn installs its fresh function cell, so the
		// value cell always holds the newest
		assertThat(lowered("(defn ^:dynamic d [] 1) (defn ^:dynamic d [] 2)")).contains("(DEFPARAMETER |c%d| #'|c%d|)")
			.contains("(DEFUN |c%d%def2| NIL 2)")
			.contains("(DEFPARAMETER |c%d| #'|c%d%def2|)");
		// namespaces version their own names independently
		assertThat(lowered("(ns nsa) (defn f [] 1) (ns nsb) (defn f [] 2) (nsa/f)")).contains("(DEFUN |c%nsa/f| NIL 1)")
			.contains("(DEFUN |c%nsb/f| NIL 2)")
			.contains("(|c%nsa/f|)");
	}

	@Test
	void headPositionCallsToVariablesReachTheValueCell() {
		// a parameter may hold a collection, so its call goes through the prelude
		// dispatcher (which funcalls real functions); a let/def binding of a real
		// function stays a direct funcall; a declared-never-defined name calls its
		// unbound root, which signals like the oracle's
		assertThat(lowered("(defn call-it [f x] (f x))"))
			.contains("(DEFUN |c%call-it| (|c%f| |c%x|) (RONTOLISP::%CLOJURE-CALL |c%f| (LIST |c%x|)))");
		assertThat(lowered("(let [g inc] (g 1))")).contains("(FUNCALL |c%g| 1)");
		assertThat(lowered("(let [s #{:h}] (s :h))")).contains("RONTOLISP::%CLOJURE-CALL");
		assertThat(lowered("(def v (fn [x] x)) (v 1)")).contains("(FUNCALL |c%v| 1)");
		assertThat(lowered("(declare u) (u 1)")).contains("(SETQ |c%u| (RONTOLISP::%CLOJURE-UNBOUND \"user/u\"))",
				"(RONTOLISP::%CLOJURE-CALL |c%u| (LIST 1))");
		assertThat(lowered("(declare u) (defn u [x] x) (u 1)")).contains("(|c%u| 1)").doesNotContain("UNBOUND");
	}

	@Test
	void letIsSequentialAndLoopIsALabelsSelfCall() {
		assertThat(lowered("(let [x 1 y x] y)")).isEqualTo(FALSE_BINDING + "(LET* ((|c%x| 1) (|c%y| |c%x|)) |c%y|)");
		assertThat(lowered("(loop [a 0] (recur 1))")).contains("LABELS");
		assertThatThrownBy(() -> Clojure.read("(recur 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("recur outside loop");
	}

	@Test
	void letfnLowersToLabelsWithEveryNamePreScanned() {
		assertThat(lowered("(letfn [(f [x] x)] (f 1))"))
			.isEqualTo(FALSE_BINDING + "(LABELS ((|c%f| (|c%x|) |c%x|)) (|c%f| 1))");
		assertThat(lowered("(letfn [] 1)")).isEqualTo(FALSE_BINDING + "1");
		// mutual recursion: siblings call each other directly
		assertThat(lowered("(letfn [(e [n] (o n)) (o [n] n)] (e 1))")).contains("(|c%o| |c%n|)").contains("(|c%e| 1)");
		// an entry's name is a function value, like a named fn's
		assertThat(lowered("(letfn [(f [x] x)] f)")).contains("#'|c%f|");
		// an inner letfn shadows an outer variable: the call stays direct
		assertThat(lowered("(let [f 99] (letfn [(f [x] x)] (f 1)))"))
			.contains("(LABELS ((|c%f| (|c%x|) |c%x|)) (|c%f| 1))");
		assertThatThrownBy(() -> Clojure.read("(letfn [f [x] x] (f 1))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("a letfn binding takes a name and a function");
	}

	@Test
	void recurTargetsAnyEnclosingFnAndChecksItsArity() {
		// a named fn recurs through its labels self-binding
		assertThat(lowered("(fn f [n] (recur n))")).contains("(|c%f| |c%n|)");
		// an anonymous fn wraps itself in labels only when a recur reaches it
		assertThat(lowered("((fn [n] (recur n)) 1)")).contains("LABELS").contains("(|c%fn-0| |c%n|)");
		assertThat(lowered("((fn [n] n) 1)")).doesNotContain("LABELS");
		// a defn recurs through a direct call
		assertThat(lowered("(defn cd [n] (recur n))")).contains("(|c%cd| |c%n|)");
		// a multi-arity defn's fixed clause recurs to its own clause, never through
		// the dispatch: a direct self call (.kb/jvm-self-tail-calls.md)
		assertThat(lowered("(defn mc ([n] (recur n)) ([a b] a))"))
			.contains("(DEFUN |c%mc%1| (|c%n|) (|c%mc%1| |c%n|))");
		// a zero-arity defn recurs with no arguments
		assertThat(lowered("(defn zg [] (recur))")).contains("(|c%zg|)");
		// a recur in a call argument is not in tail position, like the oracle
		assertThatThrownBy(() -> Clojure.read("(loop [i 0] ((fn [j] j) (recur (inc i))))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Can only recur from tail position");
		assertThatThrownBy(() -> Clojure.read("(loop [a 0] (recur 1 2))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("wrong number of arguments passed to recur: expected 1, got 2");
		assertThatThrownBy(() -> Clojure.read("(defn wcr [a] (recur 1 2))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("wrong number of arguments passed to recur: expected 1, got 2");
		assertThatThrownBy(() -> Clojure.read("(defn vr [a & r] (recur a))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("wrong number of arguments passed to recur: expected 2, got 1");
	}

	@Test
	void recurToAVariadicClauseSplitsAWorker() {
		// a used variadic defn clause splits: a worker taking the rest as an
		// ordinary parameter (the recur call assigns exactly) plus the &rest head
		assertThat(lowered("(defn vr [a & r] (recur a r))"))
			.contains("(DEFUN |c%vr%*| (|c%a| |c%r|) (|c%vr%*| |c%a| |c%r|))")
			.contains("(DEFUN |c%vr| (|c%a| &REST |c%r|) (|c%vr%*| |c%a| |c%r|))");
		// an unused variadic keeps its single shape
		assertThat(lowered("(defn vu [a & r] a)"))
			.isEqualTo(FALSE_BINDING + "(DEFUN |c%vu| (|c%a| &REST |c%r|) |c%a|)");
		// a named fn splits into a worker plus its &rest head inside labels
		assertThat(lowered("(fn f [a & r] (recur a r))")).contains("(|c%f%*| (|c%a| |c%r|) (|c%f%*| |c%a| |c%r|))")
			.contains("(|c%f| (|c%a| &REST |c%r|) (|c%f%*| |c%a| |c%r|))");
		// a multi-arity defn's variadic clause recurs to its helper directly
		assertThat(lowered("(defn vm ([a] a) ([a & r] (recur a r)))")).contains("(|c%vm%*| |c%a| |c%r|)");
		// a multi-arity fn's variadic clause recurs to its worker directly
		assertThat(lowered("((fn g ([a] a) ([a & r] (recur a r))) 1)")).contains("(|c%g%*| |c%a| |c%r|)");
		// a letfn entry splits the same way
		assertThat(lowered("(letfn [(w [a & r] (recur a r))] (w 1 2))")).contains("(|c%w%*| |c%a| |c%r|)");
		// an anonymous fn wraps the split in labels only when a recur reaches it
		assertThat(lowered("((fn [a & r] (recur a r)) 1)")).contains("LABELS").contains("%*");
		assertThat(lowered("((fn [a & r] a) 1)")).doesNotContain("LABELS");
		// a stored method lambda splits the same way (decided 2026-10-01)
		assertThat(lowered("(defmulti m :shape) (defmethod m :a [a & r] (recur a r))")).contains("LABELS")
			.contains("%*")
			.contains("&REST");
		// an unused variadic method keeps its bare lambda (the defmulti prelude
		// carries its own labels, so the pin names the stored shape, not their
		// absence)
		assertThat(lowered("(defmulti m :shape) (defmethod m :a [a & r] a)"))
			.contains("(LAMBDA (|c%a| &REST |c%r|) |c%a|)")
			.doesNotContain("%*");
		// an extended protocol method is a fn: its recur passes the target too
		assertThat(lowered("(defprotocol P (foo [t a & r])) (extend-protocol P String (foo [t a & r] (recur t a r)))"))
			.contains("LABELS")
			.contains("%*");
		// the stored path keeps the arity, tail-position and try checks
		assertThatThrownBy(() -> Clojure.read("(defmulti m :shape) (defmethod m :a [a & r] (recur a))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("wrong number of arguments passed to recur: expected 2, got 1");
		assertThatThrownBy(() -> Clojure.read("(defmulti m :shape) (defmethod m :a [a & r] (recur a r) a)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Can only recur from tail position");
		assertThatThrownBy(() -> Clojure
			.read("(defmulti m :shape) (defmethod m :a [a & r] (try (recur a r) (catch Exception e :c)))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Cannot recur across try");
	}

	@Test
	void anInlineMethodRecurPassesEveryParameterButTheTarget() {
		// an inline method's recur passes every parameter but the target (oracle clj
		// 1.12.6.1673), so the method loops over the rest, the rest an ordinary
		// parameter there: no worker split
		assertThat(lowered("(defprotocol P (foo [t a & r])) (defrecord R [f] P (foo [t a & r] (recur a r)))"))
			.contains("(|c%a| |c%r|)")
			.doesNotContain("%*");
		assertThat(lowered("(defprotocol P (foo [t a & r])) (defrecord E [] P (foo [t a & r] (recur a r)))"))
			.contains("(|c%a| |c%r|)")
			.doesNotContain("%*");
		assertThat(lowered("(defprotocol P (foo [t a & r])) (reify P (foo [t a & r] (recur a r)))"))
			.contains("(|c%a| |c%r|)")
			.doesNotContain("%*");
		assertThatThrownBy(
				() -> Clojure.read("(defprotocol P (foo [t a])) (deftype D [] P (foo [t a] (recur t a)))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("wrong number of arguments passed to recur: expected 1, got 2");
		assertThatThrownBy(() -> Clojure
			.read("(defprotocol P (foo [t a] [t a b])) (reify P (foo [t a] a) (foo [t a b] (recur a)))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("wrong number of arguments passed to recur: expected 2, got 1");
	}

	@Test
	void recurNeedsTailPositionAndRespectsTheTryBarrier() {
		// oracle clj 1.12.6.1673: ((fn [n] (try (if (zero? n) :t (recur (dec n))))) 3)
		// -> UnsupportedOperationException compiling recur: Cannot recur across try
		assertThatThrownBy(() -> Clojure.read("((fn [n] (try (if (zero? n) :t (recur (dec n))))) 3)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Cannot recur across try");
		assertThatThrownBy(() -> Clojure.read("(loop [i 0] (try (recur (inc i))))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Cannot recur across try");
		// the barrier holds wherever the try sits, even outside tail position
		assertThatThrownBy(() -> Clojure.read("((fn [n] (try (recur n) (catch Exception e :c)) :after) 1)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Cannot recur across try");
		// a target opened inside the try still recurs through it
		assertThat(lowered("((fn [n] (try ((fn [m] (recur m)) n) (catch Exception e :c))) 1)")).contains("LABELS");
		// oracle clj 1.12.6.1673: ((fn [n] (recur n) n) 1)
		// -> UnsupportedOperationException compiling recur: Can only recur from tail
		// position (which also beats the arity check, like the barrier does)
		assertThatThrownBy(() -> Clojure.read("((fn [n] (recur n) n) 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Can only recur from tail position");
		// a catch body is never tail, like the oracle
		assertThatThrownBy(() -> Clojure.read("((fn [n] (try :a (catch Exception e (recur n)))) 1)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Can only recur from tail position");
		// a lazy-seq body is its own zero-arity recur target (the oracle's thunk is a
		// zero-argument function): a recur in its tail position checks against 0 --
		// oracle clj 1.12.6.1673: ((fn [n] (lazy-seq (if (zero? n) nil (recur (dec n)))))
		// 3)
		// -> IllegalArgumentException compiling recur: Mismatched argument count to
		// recur, expected: 0 args, got: 1
		assertThatThrownBy(() -> Clojure.read("((fn [n] (if (zero? n) :d (lazy-seq (recur (dec n))))) 1)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("wrong number of arguments passed to recur: expected 0, got 1");
		// while a recur outside the lazy-seq body's tail position is still the
		// oracle's tail refusal (verified: ((fn [n] (lazy-seq (recur) :after)) 3)
		// -> UnsupportedOperationException compiling recur: Can only recur from tail
		// position)
		assertThatThrownBy(() -> Clojure.read("((fn [n] (lazy-seq (recur) :after)) 1)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Can only recur from tail position");
		// the tail slots still lower: let/do bodies, cond arms, when, and/or tails,
		// and every clause of a multi-arity dispatch
		assertThat(lowered("((fn [n] (let [x 1] (recur n))) 1)")).contains("LABELS");
		assertThat(lowered("((fn [n] (do :x (recur n))) 1)")).contains("LABELS");
		assertThat(lowered("((fn [n] (cond (zero? n) :z :else (recur (dec n)))) 1)")).contains("LABELS");
		assertThat(lowered("((fn [n] (when (pos? n) (recur (dec n)))) 1)")).contains("LABELS");
		assertThat(lowered("((fn [n] (and true (recur n))) 1)")).contains("LABELS");
		assertThat(lowered("((fn [n] (or false (recur n))) 1)")).contains("LABELS");
		assertThat(lowered("((fn ([n] (if (zero? n) :m (recur (dec n)))) ([a b] (+ a b))) 3)")).contains("LABELS");
	}

	@Test
	void recurAcrossABindingOrWithOpenBodyTripsTheTryBarrier() {
		// oracle clj 1.12.6.1673: (def ^:dynamic *x* 1)
		// ((fn [n] (binding [*x* 2] (if (zero? n) :d (recur (dec n))))) 2)
		// -> Syntax error (UnsupportedOperationException) compiling recur:
		// Cannot recur across try (binding pushes its bindings in a try)
		assertThatThrownBy(() -> Clojure
			.read("(def ^:dynamic *d* 1) ((fn [n] (binding [*d* 2] (if (zero? n) :d (recur (dec n))))) 2)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Cannot recur across try");
		// oracle clj 1.12.6.1673: ((fn [n] (with-open [s 1] (recur n))) 1)
		// -> Syntax error (UnsupportedOperationException) compiling recur:
		// Cannot recur across try (with-open closes in a finally)
		assertThatThrownBy(() -> Clojure.read("((fn [n] (with-open [s 1] (recur n))) 1)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Cannot recur across try");
		// a target opened inside the body still recurs through it --
		// oracle: (def ^:dynamic *x* 1)
		// ((fn [n] (binding [*x* 2] (loop [i n] (if (zero? i) :d (recur (dec i)))))) 2)
		// -> :d
		assertThat(lowered(
				"(def ^:dynamic *d* 1) ((fn [n] (binding [*d* 2] (loop [i n] (if (zero? i) :d (recur (dec i)))))) 2)"))
			.contains("LABELS");
		assertThat(lowered("((fn [n] (with-open [s 1] (loop [i n] (if (zero? i) :d (recur (dec i)))))) 2)"))
			.contains("LABELS");
		// the inits stay outside the barrier: a recur there is the oracle's tail
		// refusal, not the barrier one --
		// oracle clj 1.12.6.1673: (def ^:dynamic *x* 1)
		// ((fn [n] (binding [*x* (recur n)] :d)) 1)
		// -> Syntax error (UnsupportedOperationException) compiling recur:
		// Can only recur from tail position
		assertThatThrownBy(() -> Clojure.read("(def ^:dynamic *d* 1) ((fn [n] (binding [*d* (recur n)] :d)) 1)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Can only recur from tail position");
		// an empty with-open vector is the oracle's bare do (no try), so a recur
		// there still lowers --
		// oracle clj 1.12.6.1673: (macroexpand-1 '(with-open [] (recur n)))
		// -> (do (recur n))
		assertThat(lowered("((fn [n] (with-open [] (recur n))) 1)")).contains("LABELS");
	}

	@Test
	void anInnerBindingShadowsAnOuterOneForCalls() {
		// a let vector shadows a defn: the call reads the value, like the oracle
		assertThat(lowered("(defn shf [x] x) (let [shf [1 2]] (shf 0))")).contains("%CLOJURE-CALL");
		// a let vector shadows an enclosing named fn too: the call reads the
		// value instead of calling the local function
		assertThat(lowered("((fn shf [x] (let [shf [1 2]] (shf 0))) 5)")).contains("%CLOJURE-CALL");
	}

	@Test
	void fnAndAnonFnAreLambdas() {
		assertThat(lowered("((fn [a b] (+ a b)) 1 2)"))
			.isEqualTo(FALSE_BINDING + "(FUNCALL (LAMBDA (|c%a| |c%b|) (+ |c%a| |c%b|)) 1 2)");
		assertThat(lowered("(map #(* % %) '(1 2))")).contains("%CLOJURE-MAP")
			.contains("(LAMBDA (|c%p1__1#|) (* |c%p1__1#| |c%p1__1#|))");
		assertThatThrownBy(() -> Clojure.read("%", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("%");
	}

	@Test
	void coreCallsLowerToTheirCommonLispNames() {
		assertThat(lowered("(map + '(1 2))")).contains("%CLOJURE-MAP").contains("#'+");
		assertThat(lowered("(map + '(1 2) '(3 4))")).contains("%CLOJURE-MAP");
		assertThat(lowered("(filter odd? '(1 2 3))")).contains("%CLOJURE-FILTER");
		assertThat(lowered("(reduce + 0 '(1 2))")).contains("%CLOJURE-REDUCE-INIT");
		assertThat(lowered("(apply max '(3 9 4))")).contains("APPLY").contains("%CLOJURE-SEQ");
		assertThat(lowered("(concat '(1 2) [3 4])")).contains("%CLOJURE-CONCAT");
		assertThat(lowered("(concat)")).isEqualTo(FALSE_BINDING + "NIL");
		assertThat(lowered("(= 1 1)")).contains("(RONTOLISP::%CLOJURE-EQUAL");
		assertThat(lowered("(= 1 1)")).contains("RONTOLISP::%CLOJURE-FALSE");
		assertThat(lowered("(cond (= 1 2) :one :else :fallback)")).contains(":C%KEYWORD").contains("fallback");
	}

	@Test
	void aLiteralScalarKeySkipsTheStructuralKeyRuntime() {
		// keyword, string and number keys key an equal table by =, so the common
		// lookup and literal stay plain gethash/plist-hash-table
		assertThat(lowered("(def m {:a 1 \"s\" 2 3 4}) (get m :a) (:a m) (contains? m 3) (assoc m :b 1)"))
			.doesNotContain("%CLOJURE-TABLE-KEY")
			.doesNotContain("%CLOJURE-STORE-KEY")
			.contains("PLIST-HASH-TABLE");
		assertThat(lowered("(def s #{:a}) (s :a) (contains? s :a)")).doesNotContain("%CLOJURE-SET-PUT")
			.doesNotContain("%CLOJURE-TABLE-KEY");
		// any other key goes through it: a lookup reads the stored = key, a store
		// writes the representative
		assertThat(lowered("(def m {[1 1] :a}) (get m [1 1]) (contains? m [1 1])"))
			.contains("(RONTOLISP::%CLOJURE-PLIST-TABLE NIL")
			.contains("(RONTOLISP::%CLOJURE-TABLE-KEY");
		assertThat(lowered("(def s #{[1]}) (conj s [2])")).contains("(RONTOLISP::%CLOJURE-SET-PUT");
		assertThat(lowered("(defn f [m k] (dissoc m k))")).contains("(REMHASH (RONTOLISP::%CLOJURE-TABLE-KEY");
		assertThat(lowered("(def f (memoize (fn [v] v)))")).contains("(RONTOLISP::%CLOJURE-MEMO-KEY");
	}

	@Test
	void seqsCoerceCollectionsThroughOneSharedView() {
		assertThat(lowered("(seq [1 2])")).contains("%CLOJURE-SEQ");
		assertThat(lowered("(first [1 2])")).contains("(CAR").contains("%CLOJURE-SEQ");
		assertThat(lowered("(rest [1 2])")).contains("(CDR").contains("%CLOJURE-SEQ");
		assertThat(lowered("(next [1 2])")).contains("(CDR").contains("%CLOJURE-SEQ");
		assertThat(lowered("(cons 0 [1 2])")).contains("%CLOJURE-CONS");
		assertThat(lowered("(first '(1 2))")).contains("(CAR").contains("%CLOJURE-SEQ");
		assertThat(lowered("(seq {:a 1})")).contains("%CLOJURE-SEQ");
	}

	@Test
	void takeDropAndFiniteRangeAreLazyAware() {
		assertThat(lowered("(take 2 [1 2])")).contains("%CLOJURE-TAKE");
		assertThat(lowered("(drop 2 [1 2])")).contains("%CLOJURE-DROP");
		assertThat(lowered("(range 3)")).contains("LABELS").contains("REVERSE");
		assertThat(lowered("(range 1 5 2)")).contains("LABELS");
		assertThatThrownBy(() -> Clojure.read("(range)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("infinite range is not supported: range needs an end");
		assertThatThrownBy(() -> Clojure.read("(take)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("take takes a count and a collection");
		assertThatThrownBy(() -> Clojure.read("(drop)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("drop takes a count and a collection");
	}

	@Test
	void lazySeqsLowerToMemoizedThunks() {
		assertThat(lowered("(lazy-seq (cons 1 nil))")).contains("%CLOJURE-MAKE-LAZY").contains("LAMBDA");
		// an unused body stays a bare lambda; a used one wraps itself in a labels
		// self-binding under a fresh name (the anonymous-fn shape), called with no
		// arguments -- the body is a zero-arity recur target
		assertThat(lowered("(lazy-seq (cons 1 nil))")).doesNotContain("LABELS");
		assertThat(lowered("(lazy-seq (if (zero? 1) nil (recur)))")).contains("LABELS").contains("(|c%fn-0|)");
		assertThat(lowered("(lazy-cat [0 1] [2])")).contains("%CLOJURE-CONCAT").contains("%CLOJURE-MAKE-LAZY");
		assertThat(lowered("(lazy-cat)")).isEqualTo(FALSE_BINDING + "NIL");
		assertThat(lowered("(repeat 3)")).contains("%CLOJURE-REPEAT");
		assertThat(lowered("(repeat 2 3)")).contains("%CLOJURE-REPEAT-N");
		assertThat(lowered("(cycle [1 2])")).contains("%CLOJURE-CYCLE");
		assertThat(lowered("(iterate inc 0)")).contains("%CLOJURE-ITERATE");
		assertThat(lowered("(repeatedly inc)")).contains("%CLOJURE-REPEATEDLY");
		assertThat(lowered("(repeatedly 2 inc)")).contains("%CLOJURE-REPEATEDLY-N");
		assertThatThrownBy(() -> Clojure.read("(repeat)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("repeat takes a value");
		assertThatThrownBy(() -> Clojure.read("(cycle)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("cycle takes one collection");
		assertThatThrownBy(() -> Clojure.read("(iterate inc)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("iterate takes a function and a value");
		assertThatThrownBy(() -> Clojure.read("(map)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("map takes a function and collections");
	}

	@Test
	void vectorsQuotesAndKeywords() {
		assertThat(lowered("[1 :a]")).isEqualTo(FALSE_BINDING + "(VECTOR 1 (LIST :C%KEYWORD \"a\"))");
		assertThat(lowered("'a")).isEqualTo(FALSE_BINDING + "'|c%a|");
		assertThat(lowered(":a")).isEqualTo(FALSE_BINDING + "(LIST :C%KEYWORD \"a\")");
		assertThat(lowered(":A")).isEqualTo(FALSE_BINDING + "(LIST :C%KEYWORD \"A\")");
		assertThat(lowered("(str \"a\" 1)")).contains("CONCATENATE");
	}

	@Test
	void keywordsKeepTheirCaseAndPrintWithColon() {
		assertThat(lowered("(= :a :A)")).contains("(RONTOLISP::%CLOJURE-EQUAL").contains(":C%KEYWORD");
		assertThat(lowered("(str :a)")).contains("RONTOLISP::%CLOJURE-STR-OF");
		assertThat(lowered("':a")).isEqualTo(FALSE_BINDING + "'(:C%KEYWORD \"a\")");
		assertThat(lowered(":a/b")).isEqualTo(FALSE_BINDING + "(LIST :C%KEYWORD \"a/b\")");
		assertThat(lowered("::foo")).isEqualTo(FALSE_BINDING + "(LIST :C%KEYWORD \"user/foo\")");
		assertThat(lowered("(ns auto) ::foo")).contains("(LIST :C%KEYWORD \"auto/foo\")");
		assertThat(lowered("(ns auto (:require [clojure.string :as str])) ::str/join"))
			.contains("(LIST :C%KEYWORD \"clojure.string/join\")");
		assertThat(lowered("(ns auto) '::foo")).contains("(:C%KEYWORD \"auto/foo\")");
		assertThat(lowered("(ns auto) (in-ns 'other) ::foo")).contains("(LIST :C%KEYWORD \"other/foo\")");
		assertThatThrownBy(() -> Clojure.read("::nope/kw", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid token: ::nope/kw");
		assertThatThrownBy(() -> Clojure.read(":", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("a keyword needs a name");
	}

	@Test
	void nsSkipsMetadataOnItsNameLikeDefAndDefn() {
		assertThat(lowered("(ns ^{:doc \"d\"} docns \"doc\" {:author :a}) ::foo"))
			.contains("(LIST :C%KEYWORD \"docns/foo\")");
		assertThat(lowered("(ns #^{:doc \"d\"} docns) ::foo")).contains("(LIST :C%KEYWORD \"docns/foo\")");
		assertThat(lowered("(ns ^:no-doc docns (:require [clojure.string :as s])) (s/join \",\" [\"a\"])"))
			.contains("CONCATENATE");
		assertThat(lowered("(defn #^String f [#^long x] x)")).isEqualTo(lowered("(defn f [x] x)"));
	}

	@Test
	void recordLiteralsBuildTheRecordOverTheQuotedBody() {
		// the body is data: built in place over the quoted values (no constructor
		// call), the declared fields first, nil until named
		String map = lowered("(defrecord R [a b]) #user.R{:b x :c (f 1)}");
		assertThat(map).contains("(LIST :C%RECORD (LIST :C%KEYWORD \"R\")")
			.contains("'|c%x|")
			.contains("'(|c%f| 1)")
			.contains("\"user.R\"")
			.doesNotContain("(|c%map->R|");
		assertThat(lowered("(defrecord R [a b]) #user.R[1 x]")).contains("'|c%x|").doesNotContain("(|c%->R|");
		// quoted and syntax-quoted literals are the same value
		String plain = lowered("(defrecord R [a]) #user.R[1]");
		assertThat(lowered("(defrecord R [a]) '#user.R[1]")).isEqualTo(plain);
		assertThat(lowered("(defrecord R [a]) `#user.R[1]")).isEqualTo(plain);
		// the class name is the one the record prints: namespace included
		assertThat(lowered("(ns my-app.core) (defrecord R [a]) #my_app.core.R[1]")).contains("\"my_app.core.R\"");
		assertThatThrownBy(() -> Clojure.read("(ns my-app.core) (defrecord R [a]) #user.R[1]", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("a record literal needs a defined record class, not user.R");
		assertThatThrownBy(() -> Clojure.read("(defrecord R [a b]) #user.R[1]", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Unexpected number of constructor arguments to class user.R: got 1");
		assertThatThrownBy(() -> Clojure.read("(deftype T [a]) #user.T[1]", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("a deftype literal is not supported yet: user.T");
	}

	@Test
	void keywordsInCallPositionAreMapLookups() {
		String prelude = "(def m {:a 1}) ";
		assertThat(lowered(prelude + "(:a m)")).contains("GETHASH").contains("COND");
		assertThat(lowered(prelude + "(:a m 9)")).contains("GETHASH").contains("9");
		assertThat(lowered("(map :a '({:a 1}))")).contains("%CLOJURE-MAP").contains("LAMBDA").contains("GETHASH");
		assertThatThrownBy(() -> Clojure.read("(:a)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining(":a takes a collection and an optional default");
		assertThat(lowered("(def m {:a 1}) (::foo m)")).contains("GETHASH").contains("user/foo");
	}

	@Test
	void keywordValuesTakeAnOptionalDefault() {
		assertThat(lowered("(map :a '({:a 1}))")).contains("&REST").contains("GETHASH");
		assertThat(lowered("(defmulti w39v :shape) (w39v {:shape :go} [1])")).contains("&REST");
	}

	@Test
	void mapsAndSetsBuildTables() {
		assertThat(lowered("{:a 1}")).contains("PLIST-HASH-TABLE").contains(":C%KEYWORD");
		assertThat(lowered("{}")).contains("PLIST-HASH-TABLE");
		assertThat(lowered("#{1 2}")).contains(":C%SET").contains("GETHASH");
		assertThat(lowered("#{ }")).contains(":C%SET");
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
		assertThat(lowered("(map false? '(1))")).contains("%CLOJURE-MAP")
			.contains("(LAMBDA (|c%pred|) (IF (EQ |c%pred| RONTOLISP::%CLOJURE-FALSE) T RONTOLISP::%CLOJURE-FALSE))");
		assertThat(lowered("(map odd? '(1 2))")).contains("%CLOJURE-MAP").contains("ODDP");
	}

	@Test
	void printAndStrSpellTrueFalseNil() {
		assertThat(lowered("(str nil)"))
			.isEqualTo(FALSE_BINDING + "(CONCATENATE 'STRING (RONTOLISP::%CLOJURE-STR-OF NIL \"\" NIL))");
		assertThat(lowered("(println true)"))
			.isEqualTo(FALSE_BINDING + "(PROGN (RONTOLISP::%CLOJURE-WRITE-DATUM T \"nil\" NIL) (TERPRI) NIL)");
		assertThat(lowered("(print true)"))
			.isEqualTo(FALSE_BINDING + "(PROGN (RONTOLISP::%CLOJURE-WRITE-DATUM T \"nil\" NIL) NIL)");
		assertThat(lowered("(pr-str nil)"))
			.isEqualTo(FALSE_BINDING + "(CONCATENATE 'STRING (RONTOLISP::%CLOJURE-STR-OF NIL \"nil\" T))");
	}

	@Test
	void mapVerbsLowerToTableOperations() {
		String prelude = "(def m {:a 1}) (def v [1]) (def s #{1}) (def c '(1)) ";
		assertThat(lowered(prelude + "(assoc m :a 1)")).contains("RONTOLISP::%CLOJURE-PLIST-TABLE");
		assertThat(lowered(prelude + "(dissoc m :a)")).contains("REMHASH");
		assertThat(lowered(prelude + "(get m :a)")).contains("GETHASH");
		assertThat(lowered(prelude + "(get m :a 9)")).contains("GETHASH");
		assertThat(lowered(prelude + "(contains? m :a)")).contains("GETHASH").contains("COND");
		assertThat(lowered(prelude + "(keys m)")).contains("MAPHASH");
		assertThat(lowered(prelude + "(vals m)")).contains("MAPHASH");
		assertThat(lowered(prelude + "(merge m m)")).contains("APPEND").contains("RONTOLISP::%CLOJURE-PLIST-TABLE");
		assertThat(lowered("(merge)")).isEqualTo(FALSE_BINDING + "NIL");
		assertThat(lowered(prelude + "(conj v 1)")).contains("COND").contains("COERCE");
		assertThat(lowered(prelude + "(disj s 1)")).contains("REMHASH").contains(":C%SET");
		assertThat(lowered(prelude + "(set c)")).contains("DOLIST").contains(":C%SET");
		assertThat(lowered(prelude + "(hash-map :a 1)")).contains("PLIST-HASH-TABLE");
		assertThat(lowered(prelude + "(array-map :a 1)")).contains("PLIST-HASH-TABLE");
		assertThat(lowered(prelude + "(count m)")).contains("HASH-TABLE-COUNT").contains("LENGTH");
		assertThat(lowered(prelude + "(empty? m)")).contains("HASH-TABLE-COUNT").contains("NULL");
	}

	@Test
	void mapVerbsRefuseBadShapesByName() {
		assertThatThrownBy(() -> Clojure.read("(assoc m :a)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("assoc takes a map and key/value pairs");
		assertThatThrownBy(() -> Clojure.read("(get m)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("get takes a map");
		assertThatThrownBy(() -> Clojure.read("(hash-map :a)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("hash-map takes key/value pairs");
		// (conj) is the oracle's init arity, [], what (transduce xf conj coll) starts
		// from
		assertThat(lowered("(conj)")).isEqualTo(FALSE_BINDING + "(VECTOR)");
		assertThatThrownBy(() -> Clojure.read("(count a b)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("count takes one collection");
	}

	@Test
	void transientsAreRefusedByName() {
		assertThatThrownBy(() -> Clojure.read("(assoc! m :a 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("transients are not supported yet: assoc!");
		assertThatThrownBy(() -> Clojure.read("(transient {})", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("transients are not supported yet: transient");
		assertThatThrownBy(() -> Clojure.read("(conj! t 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("transients are not supported yet: conj!");
	}

	@Test
	void quotedMapsAndSetsBuildTables() {
		assertThat(lowered("'{:a 1}")).contains("PLIST-HASH-TABLE").contains(":C%KEYWORD");
		assertThat(lowered("'#{1 2}")).contains(":C%SET").contains("GETHASH");
		assertThat(lowered("'{:a 1}")).contains("(LIST :C%KEYWORD \"a\")");
		assertThat(lowered("'#{:a}")).contains(":C%SET").contains("(LIST :C%KEYWORD \"a\")");
	}

	@Test
	void nsDefinesNothing() {
		// the ns form lowers to nothing but the switch of *ns* (which a program reading
		// no *ns* sheds, ClojureArmsTest); what follows defines into the namespace,
		// whose vars carry its name (user's keep the bare mangled name)
		String nsSwitch = "(DEFVAR RONTOLISP::%CLOJURE-NS (RONTOLISP::%CLOJURE-NS-OBJECT \"user\"))\n"
				+ "(SETQ RONTOLISP::%CLOJURE-NS (RONTOLISP::%CLOJURE-NS-OBJECT \"foo\"))\n";
		assertThat(lowered("(ns foo) (def x 1) x"))
			.isEqualTo(FALSE_BINDING + nsSwitch + "(SETQ |c%foo/x| 1)\n|c%foo/x|");
		assertThat(lowered("(ns foo (:require [clojure.string :as s])) (def x 1) x"))
			.isEqualTo(FALSE_BINDING + nsSwitch + "(SETQ |c%foo/x| 1)\n|c%foo/x|");
		assertThat(lowered("(def x 1) x")).isEqualTo(FALSE_BINDING + "(SETQ |c%x| 1)\n|c%x|");
	}

	@Test
	void nthIndexesTheSeqViewWithAnOptionalDefault() {
		// one spliced stepper: a vector indexes directly, a seq steps one realized level
		// at a time, so an infinite input answers; nth of two arguments is its own entry,
		// for a type implementing Indexed, which a program storing no such row calls as
		// the stepper (the indexed family's alias)
		assertThat(lowered("(nth '(1 2 3) 1)")).isEqualTo(FALSE_BINDING + "(RONTOLISP::%CLOJURE-NTH-2 '(1 2 3) 1 NIL)");
		List<LispVal> spliced = am.ik.rontolisp.eval.ClojureLibrary.process(Clojure.read("(nth '(1 2 3) 1)", null));
		assertThat(spliced.get(spliced.size() - 1).print()).isEqualTo("(RONTOLISP::%CLOJURE-NTH '(1 2 3) 1 NIL)");
		assertThat(lowered("(nth [10 20] 5 :nf)")).contains("(RONTOLISP::%CLOJURE-NTH (VECTOR 10 20) 5")
			.contains(":C%KEYWORD");
		assertThat(lowered("nth")).contains("(LAMBDA").contains("(RONTOLISP::%CLOJURE-NTH");
		assertThatThrownBy(() -> Clojure.read("(nth '(1 2 3))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("nth takes a collection, an index and an optional default");
	}

	@Test
	void quotTruncatesTowardZero() {
		assertThat(lowered("(quot 7 2)")).isEqualTo(FALSE_BINDING + "(TRUNCATE 7 2)");
		assertThat(lowered("quot")).contains("(LAMBDA").contains("TRUNCATE");
	}

	@Test
	void errorsCarryTheReadersPosition() {
		LispReadException unknown = catchThrowableOfType(() -> Clojure.read("(nope 1)", "prog.clj"),
				LispReadException.class);
		assertThat(unknown.getMessage()).isEqualTo("prog.clj:1:1: unknown name: nope");
		assertThat(unknown.location()).isNotNull();
		assertThat(unknown.location().line()).isEqualTo(1);
		assertThat(unknown.location().column()).isEqualTo(1);
		LispReadException secondLine = catchThrowableOfType(() -> Clojure.read("(def x 1)\n  (take)", "prog.clj"),
				LispReadException.class);
		assertThat(secondLine.getMessage()).isEqualTo("prog.clj:2:3: take takes a count and a collection");
		LispReadException nested = catchThrowableOfType(() -> Clojure.read("(let [x 1] (nope x))", "prog.clj"),
				LispReadException.class);
		assertThat(nested.getMessage()).isEqualTo("prog.clj:1:12: unknown name: nope");
		// without a file the message stays bare but the position is still recorded
		LispReadException bare = catchThrowableOfType(() -> Clojure.read("(nope 1)", null), LispReadException.class);
		assertThat(bare.getMessage()).isEqualTo("unknown name: nope");
		assertThat(bare.location()).isNotNull();
		assertThat(bare.location().line()).isEqualTo(1);
		assertThat(bare.location().column()).isEqualTo(1);
	}

	@Test
	void printPartsAreSpaceSeparatedAndPrIsReadable() {
		assertThat(lowered("(println \"x\" \"y\")")).contains("RONTOLISP::%CLOJURE-WRITE-DATUM")
			.contains("(WRITE-CHAR #\\Space)")
			.contains("(TERPRI)");
		assertThat(lowered("(print \"a\" \"b\")")).contains("RONTOLISP::%CLOJURE-WRITE-DATUM")
			.contains("(WRITE-CHAR #\\Space)")
			.doesNotContain("(TERPRI)");
		assertThat(lowered("(println \"x\")")).doesNotContain("(WRITE-CHAR");
		assertThat(lowered("(str \"a\" \"b\")")).doesNotContain("(WRITE-CHAR").contains("RONTOLISP::%CLOJURE-STR-OF");
		assertThat(lowered("(pr \"a\" 1)")).contains("RONTOLISP::%CLOJURE-WRITE-DATUM")
			.contains("(WRITE-CHAR #\\Space)")
			.contains("\"nil\" T");
		assertThat(lowered("(prn :a)")).contains("RONTOLISP::%CLOJURE-WRITE-DATUM").contains("(TERPRI)");
		assertThat(lowered("(pr-str \"a\" 1)")).contains("(CONCATENATE 'STRING")
			.contains("\" \"")
			.contains("\"nil\" T");
		assertThat(lowered("(map pr-str [1 2])")).contains("%CLOJURE-MAP").contains("CONCATENATE");
	}

	@Test
	void builtinsNameFunctionValues() {
		assertThat(lowered("(map inc '(1 2))")).contains("%CLOJURE-MAP").contains("(LAMBDA").contains("(+");
		assertThat(lowered("(map dec [1 2])")).contains("%CLOJURE-MAP").contains("(-");
		assertThat(lowered("(map str [1 2])")).contains("%CLOJURE-MAP").contains("CONCATENATE");
		assertThat(lowered("(map count [[1]])")).contains("%CLOJURE-MAP").contains("LENGTH");
		assertThat(lowered("(filter first [[1] []])")).contains("%CLOJURE-FILTER").contains("(CAR");
		assertThat(lowered("inc")).contains("(LAMBDA").contains("(+");
		assertThat(lowered("(apply + 1 '(2 3))")).contains("APPLY").contains("#'+");
	}

	@Test
	void destructuringBindsSequentialPatterns() {
		assertThat(lowered("(let [[a b] [1 2]] a)")).contains("LET*")
			.contains("(RONTOLISP::%CLOJURE-NTH")
			.contains("|c%a|");
		// the rest past the positions steps too, so a lazy rest stays lazy
		assertThat(lowered("(let [[a & r] [1 2 3]] r)")).contains("(RONTOLISP::%CLOJURE-DROP 1 ")
			.doesNotContain("NTHCDR");
		assertThat(lowered("(let [[a :as v] [1 2]] v)")).contains("LET*");
		assertThat(lowered("(let [{:keys [a b] :as m :or {a 9}} {:a 1}] a)")).contains("GETHASH")
			.contains(":C%KEYWORD");
		assertThat(lowered("(let [{s :s} {:s 1}] s)")).contains("GETHASH");
		assertThat(lowered("(let [{:strs [s]} {:s 1}] s)")).contains("GETHASH");
		assertThat(lowered("(loop [[a b] [1 2]] a)")).contains("LABELS").contains("(RONTOLISP::%CLOJURE-NTH");
		assertThat(lowered("((fn [[a b]] a) [1 2])")).contains("(RONTOLISP::%CLOJURE-NTH");
		assertThat(lowered("((fn [{:keys [a]}] a) {:a 1})")).contains("GETHASH");
		assertThatThrownBy(() -> Clojure.read("(let [[a &] [1]] a)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("a vector pattern & needs a single rest pattern after it");
		assertThatThrownBy(() -> Clojure.read("(let [{:keys a} {:a 1}] a)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("a map pattern :keys takes a vector of plain names");
	}

	@Test
	void threadingIsADatumRewriteAroundOneTemporary() {
		assertThat(lowered("(-> 5 inc)")).isEqualTo(FALSE_BINDING + "(+ 5 1)");
		assertThat(lowered("(->> 5 (conj [1]))")).contains("COERCE").contains("(VECTOR 1)");
		assertThat(lowered("(-> {:a 1} :a)")).contains("GETHASH");
		assertThat(lowered("(as-> 5 x (inc x))")).contains("LET*");
		assertThat(lowered("(doto 5 (inc))")).contains("(LET");
		assertThat(lowered("(cond-> 5 true inc)")).contains("(IF");
		assertThat(lowered("(some-> nil (inc))")).contains("NULL");
		assertThat(lowered("(list* 1 [2 3])")).contains("%CLOJURE-CONS").contains("(VECTOR 2 3)");
		assertThatThrownBy(() -> Clojure.read("(->)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("-> takes a value and forms");
		assertThatThrownBy(() -> Clojure.read("(cond-> 5 true)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("cond-> takes a value and test/form pairs");
	}

	@Test
	void multiArityDefnDispatchesByCount() {
		assertThat(lowered("(defn mar-f ([x] x) ([x y] (+ x y)))")).contains("(DEFUN")
			.contains("LENGTH")
			.contains("wrong number of arguments passed to: mar-f");
		assertThat(lowered("(defn mar-g ([x & xs] xs))")).contains("NTHCDR");
		assertThatThrownBy(() -> Clojure.read("(defn bad ([x] x) ([y] y))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("two clauses for arity 1");
		assertThatThrownBy(() -> Clojure.read("(defn bad ([x & a] x) ([y & b] y))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("at most one variadic clause");
		assertThatThrownBy(() -> Clojure.read("(let [x 1] (defn bad ([y] y) ([z w] z)))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("a multi-arity defn is only allowed at the top level");
	}

	@Test
	void namedAndMultiArityFn() {
		assertThat(lowered("((fn myfn [x] x) 1)")).contains("LABELS").contains("#'|c%myfn|");
		assertThat(lowered("((fn ([x] x) ([x y] y)) 1)")).contains("LENGTH").contains("(NTH");
		assertThatThrownBy(() -> Clojure.read("(fn)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("fn needs a parameter vector and a body");
	}

	@Test
	void declareRegistersForwardNames() {
		assertThat(lowered("(declare dcl-f) (defn dcl-g [] (dcl-f 1))")).contains("NIL").contains("|c%dcl-f|");
	}

	@Test
	void doseqDotimesForLowerToCoreLoops() {
		assertThat(lowered("(doseq [x [1]] x)")).contains("(DO ((").contains("|c%x|").contains("(PROGN");
		// each level steps through the seq view one realized level at a time, so a lazy
		// tail never reaches the element binding as wrapper internals
		assertThat(lowered("(doseq [x [1 2] y [3 4]] x)")).doesNotContain("DOLIST")
			.contains("RONTOLISP::%CLOJURE-SEQ-REST")
			.contains("(CAR ");
		assertThat(lowered("(dotimes [i 2] i)")).contains("DOTIMES").contains("TRUNCATE");
		// for hands the first collection and one step closure per level to the spliced
		// runtime: a lazy seq over a lazy first collection, the realized strict list
		// otherwise
		assertThat(lowered("(for [x [1]] x)")).matches("(?s).*\\(RONTOLISP::%CLOJURE-FOR \\(VECTOR 1\\) "
				+ "\\(LAMBDA \\((\\|__clojure_\\d+\\|)\\) \\(LET \\(\\(\\|c%x\\| \\1\\)\\) \\|c%x\\|\\)\\) 1\\)");
		// a later level's collection runs inside the outer step, consed onto its step
		assertThat(lowered("(for [x [1] y [2]] [x y])")).contains("(CONS (VECTOR 2) (LAMBDA (").endsWith(" 2)");
		assertThat(lowered("(for [x [1] :when x] x)")).contains(":C%FOR-SKIP").doesNotContain("DOLIST");
		assertThat(lowered("(for [x [1] :while x] x)")).contains(":C%FOR-STOP").doesNotContain("RETURN-FROM");
		assertThat(lowered("(doseq [x [1] :while x] x)")).contains("BLOCK").contains("RETURN-FROM");
		assertThat(lowered("(for [x [1] :let [y 2]] y)")).contains("LET*").contains("|c%y|");
		assertThat(lowered("(for [[a b] [[1 2]]] a)")).contains("(RONTOLISP::%CLOJURE-FOR ")
			.contains("LET*")
			.contains("(RONTOLISP::%CLOJURE-NTH ")
			.doesNotContain("DOLIST");
		// dorun/doall walk the seq to its end, so a lazy one realizes
		assertThat(lowered("(dorun [1])")).isEqualTo(FALSE_BINDING + "(RONTOLISP::%CLOJURE-DORUN (VECTOR 1))");
		assertThat(lowered("(dorun 2 [1])")).isEqualTo(FALSE_BINDING + "(RONTOLISP::%CLOJURE-DORUN-N 2 (VECTOR 1))");
		assertThat(lowered("(doall [1])")).isEqualTo(FALSE_BINDING + "(RONTOLISP::%CLOJURE-DOALL (VECTOR 1))");
		assertThat(lowered("(doall 2 [1])")).isEqualTo(FALSE_BINDING + "(RONTOLISP::%CLOJURE-DOALL-N 2 (VECTOR 1))");
		assertThatThrownBy(() -> Clojure.read("(for [x [1] :foo 1] x)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid 'for' keyword :foo");
		assertThatThrownBy(() -> Clojure.read("(doseq [x [1] :unless true] x)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid 'doseq' keyword :unless");
		assertThatThrownBy(() -> Clojure.read("(for [x [1]] 1 2)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("for takes a binding vector and a body");
		assertThatThrownBy(() -> Clojure.read("(for [] 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("for takes at least one binding pair");
		assertThatThrownBy(() -> Clojure.read("(dotimes [i] i)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("dotimes takes exactly one name and count");
		assertThatThrownBy(() -> Clojure.read("(dotimes [[a] 2] a)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("dotimes needs a plain name");
		assertThatThrownBy(() -> Clojure.read("(dorun)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("dorun takes a collection and an optional count");
		assertThatThrownBy(() -> Clojure.read("(doseq [x [1] :when] x)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("doseq :when takes a form after it");
	}

	@Test
	void defmultiIsATablePlusADispatcherDefun() {
		assertThat(lowered("(defmulti area :shape)")).contains("DEFUN |c%area|")
			.contains("GETHASH")
			.contains("|c%area%methods|");
		assertThat(lowered("(defmulti area :shape) (defmethod area :circle [m] 1)")).contains("SETF")
			.contains("LAMBDA");
		assertThatThrownBy(() -> Clojure.read("(defmethod area :circle [m] 1)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such multimethod: area");
		assertThatThrownBy(() -> Clojure.read("(prefer-method area :x)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("prefer-method takes a multimethod and two dispatch values");
		assertThatThrownBy(() -> Clojure.read("(prefer-method missing :x :y)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such multimethod: missing");
		assertThat(lowered("(defmulti area :shape) (prefer-method area :x :y)")).contains("C%H-PREFERRED")
			.contains("|c%area%prefers|");
		assertThat(lowered("(derive :a :b)")).contains("C%H-DERIVE").contains("C%H-GLOBAL");
		assertThat(lowered("(isa? :a :b)")).contains("C%H-ISA?");
		assertThat(lowered("(parents :a)")).contains("C%H-PARENTS");
		assertThat(lowered("(make-hierarchy)")).contains("C%H-EMPTY");
		assertThat(lowered("(def h (make-hierarchy)) (defmulti area :shape :hierarchy h)")).contains("C%H-DISPATCH");
		assertThat(lowered("(defmulti area :shape)")).contains("|c%area%object|");
		assertThat(lowered("(defmulti area class) (defmethod area String [s] s)")).contains("\"string\"");
		assertThat(lowered("(defmulti area class) (defmethod area Number [n] n)")).contains("\"number\"");
		assertThat(lowered("(defmulti area class) (defmethod area Long [n] n)")).contains("\"number\"");
		assertThat(lowered("(defmulti area class) (defmethod area java.util.Map [m] m)")).contains("\"map\"");
		assertThat(lowered("(defmulti area class) (defmethod area clojure.lang.IPersistentVector [v] v)"))
			.contains("\"vector\"");
		assertThat(lowered("(defmulti area class) (defmethod area java.util.Collection [c] c)")).contains("\"list\"");
		assertThat(lowered("(defmulti area class) (defmethod area nil [x] x)")).contains(":C%NIL");
		assertThat(lowered("(defmulti area identity) (defmethod area nil [x] x)")).contains(":C%NIL");
		assertThat(lowered("(defmulti area identity) (defmethod area :nil [x] x)")).contains("\"nil\"");
		assertThat(lowered("(defmulti w38 (fn [x] (class x))) (class nil)")).contains(":C%NIL").contains("\"nil\"");
		assertThat(lowered("(defmulti area class) (defmethod area Object [x] x)")).contains("|c%area%object|")
			.contains("\"object\"");
		assertThat(lowered("(defmulti area (fn [a b] [(class a) (class b)])) (defmethod area [Number Number] [a b] 1)"))
			.contains("VECTOR");
		assertThat(lowered("(defmulti area :t) (defmethod area ::k [x] x)")).contains("\"user/k\"");
		assertThat(lowered("(defmulti area :t) (remove-method area String)")).contains("\"string\"");
		assertThat(lowered("(defmulti area :t) (get-method area Number)")).contains("\"number\"");
		assertThat(lowered("(defmulti area :t) (methods area)")).contains("RONTOLISP::%CLOJURE-METHODS")
			.contains("|c%area%methods|");
		assertThatThrownBy(() -> Clojure.read("(methods missing)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such multimethod: missing");
		assertThatThrownBy(() -> Clojure.read("(defn plain [] 1) (methods plain)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such multimethod: plain");
		assertThatThrownBy(() -> Clojure.read("(defmulti area class) (defmethod area Instant [x] x)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: Instant");
		assertThatThrownBy(() -> Clojure.read("(isa? :a)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("isa? takes a child and a parent");
		assertThat(lowered("(ex-info \"m\" {:a 1})")).contains("RONTOLISP::%CLOJURE-EX-INFO")
			.contains("(DEFINE-CONDITION C%E-EXCEPTION")
			.contains("MAKE-CONDITION");
		assertThat(lowered("(def e (ex-info \"m\" {:a 1})) (ex-data e)")).contains("RONTOLISP::%CLOJURE-EX-DATA");
		assertThat(lowered("(def e (ex-info \"m\" {:a 1})) (ex-message e)")).contains("RONTOLISP::%CLOJURE-EX-MESSAGE");
		assertThat(lowered("(def e (ex-info \"m\" {:a 1})) (ex-cause e)")).contains("RONTOLISP::%CLOJURE-EX-CAUSE");
		// a construction carries its class chain, resolved at lowering time
		assertThat(lowered("(Exception. \"m\")"))
			.contains("(RONTOLISP::%CLOJURE-EXCEPTION-NEW-1 '(\"java.lang.Exception\" \"java.lang.Throwable\") \"m\")")
			.doesNotContain("JAVA:NEW");
		assertThat(lowered("(IllegalArgumentException. \"m\")")).contains(
				"'(\"java.lang.IllegalArgumentException\" \"java.lang.RuntimeException\" \"java.lang.Exception\" \"java.lang.Throwable\")");
		assertThat(lowered("(.getMessage (ex-info \"m\" {}))")).contains("RONTOLISP::%CLOJURE-EXCEPTION-METHOD")
			.doesNotContain("JAVA:CALL");
		assertThatThrownBy(() -> Clojure.read("(ex-info \"m\")", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("ex-info takes a message, a data map and an optional cause");
	}

	@Test
	void theClassWalkTakesTheDescendantsRefusalAndTheHostOnlyWhereTheProgramNeedsThem() {
		// descendants' refusal is an exception of the exception runtime, so it is in
		// the walk only where descendants is read (before, a program reading none
		// compiled C%E-NEW as an undefined call); the host class walk and its kinds
		// only where a java: operator can make a class object
		String spelled = lowered("(isa? (class '(1)) java.util.List)");
		assertThat(spelled).contains("(RONTOLISP::%CLOJURE-CLASS-ISA ")
			.doesNotContain("DESCENDANTS-REFUSAL")
			.doesNotContain("%CLOJURE-HOST-CLASS-")
			.doesNotContain("C%H-KINDS");
		assertThat(lowered("(descendants java.util.List)")).contains("(RONTOLISP::%CLOJURE-CLASS-DESCENDANTS-REFUSAL)");
		String host = lowered("(derive :a :b) (println (java.util.ArrayList.))");
		assertThat(host).contains("(RONTOLISP::%CLOJURE-HOST-CLASS-ISA ")
			.contains("(RONTOLISP::%CLOJURE-HOST-CLASS-ANCESTORS ")
			.contains("(SETQ C%H-KINDS '((\"Atom\" . \"atom\")")
			.doesNotContain("DESCENDANTS-REFUSAL");
		assertThat(lowered("(derive :a :b) (descendants (class (java.util.ArrayList.)))"))
			.contains("(RONTOLISP::%CLOJURE-HOST-NAMES-CLASS-P ");
		assertThat(lowered("(println (java.util.ArrayList.))")).doesNotContain("C%H-");
	}

	@Test
	void protocolsRecordsAndTypesLowerToTables() {
		// a protocol is a method-table global plus one dispatcher defun per method,
		// over the shared tag reader (the multimethod shape without the hierarchy
		// search); the protocol name answers its table
		assertThat(lowered("(defprotocol P (foo [x]) (bar [x y]))")).contains("C%PROTOCOL-TAG")
			.contains("|c%P%methods|")
			.contains("DEFUN |c%foo|")
			.contains("DEFUN |c%bar|")
			.contains("(SETQ |c%P| |c%P%methods|)");
		// a record is a (:C%RECORD tag fields table) wrapper with positional and map
		// constructors as mangled defuns, so constructor calls stay direct
		assertThat(lowered("(defprotocol P (foo [x])) (defrecord R [a] P (foo [_] a))")).contains(":C%RECORD")
			.contains("DEFUN |c%->R|")
			.contains("DEFUN |c%map->R|")
			.contains("GETHASH");
		assertThat(lowered("(defrecord R [a]) (->R 1)")).contains("(|c%->R| 1)");
		assertThat(lowered("(->R 1) (defrecord R [a])")).contains("(|c%->R| 1)");
		assertThat(lowered("(defrecord R [a]) (R. 1)")).contains("(|c%->R| 1)");
		assertThat(lowered("(defrecord R [a]) (map->R {:a 1})")).contains("|c%map->R|");
		// the record carries its host class name, the namespace munged - to _
		assertThat(lowered("(ns my-app.core) (defrecord R [a])")).contains("\"my_app.core.R\"");
		assertThat(lowered("(defrecord R [a])")).contains("\"user.R\"");
		assertThat(lowered("(T. 1)")).contains("JAVA:NEW");
		// a deftype shares the shape with an opaque tag and no map constructor
		assertThat(lowered("(deftype T [a])")).contains(":C%TYPE").contains("DEFUN |c%->T|");
		assertThat(lowered("(deftype T [a])")).doesNotContain("map->");
		assertThatThrownBy(() -> Clojure.read("(map->T {:a 1}) (deftype T [a])", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: map->T");
		// reify answers one fresh tag per evaluation with a row per method
		assertThat(lowered("(defprotocol P (foo [x])) (reify P (foo [_] 1))")).contains(":C%REIFY")
			.contains("GENSYM")
			.contains("|c%P%methods|");
		// extend-protocol/extend-type/extend are defmethod rows; satisfies? is table
		// membership; instance? of a type is tag equality
		assertThat(lowered("(defprotocol P (foo [x])) (extend-protocol P String (foo [s] s))"))
			.contains("|c%P%methods|");
		assertThat(lowered("(defprotocol P (foo [x])) (extend-type String P (foo [s] s))")).contains("|c%P%methods|");
		assertThat(lowered("(defprotocol P (foo [x])) (extend String P {:foo (fn [s] s)})")).contains("|c%P%methods|");
		assertThat(lowered("(defprotocol P (foo [x])) (satisfies? P 1)")).contains("C%PROTOCOL-TAG");
		assertThat(lowered("(defrecord R [a]) (instance? R 1)")).contains("C%PROTOCOL-TAG");
		assertThat(lowered("(defrecord R [a]) (.-a (->R 1))")).contains("GETHASH");
		// the stays-refused set: interfaces and code generation, a signature that is
		// no parameter vector, a computed metadata-extension flag, non-core extend
		// targets, unknown protocols and methods outside their protocols
		assertThatThrownBy(() -> Clojure.read("(gen-class)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("protocols are not supported yet: gen-class");
		assertThatThrownBy(() -> Clojure.read("(gen-interface)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("protocols are not supported yet: gen-interface");
		assertThatThrownBy(() -> Clojure.read("(definterface I (m [x]))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("protocols are not supported yet: definterface");
		assertThatThrownBy(() -> Clojure.read("(defprotocol Q (m ([x] 1) ([x y] 2)))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("a defprotocol method signature is its name over one parameter vector per arity");
		assertThatThrownBy(() -> Clojure.read("(defprotocol Q :extend-via-metadata yes (m [x]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("extend-via-metadata takes true or false, not yes");
		// the flag adds the inline table and the metadata lookup; without it neither
		assertThat(lowered("(defprotocol Q :extend-via-metadata true (m [x]))")).contains("|c%Q%inline|")
			.contains("%CLOJURE-META-METHOD")
			.contains("|c%user/m|");
		assertThat(lowered("(defprotocol Q :extend-via-metadata false (m [x]))")).doesNotContain("|c%Q%inline|")
			.doesNotContain("%CLOJURE-META-METHOD");
		assertThat(lowered("(defprotocol Q (m [x]))")).doesNotContain("%CLOJURE-META-METHOD");
		// a name no class has is the oracle's unresolved symbol, like instance?'s
		assertThatThrownBy(() -> Clojure.read("(defprotocol Q (m [x])) (extend-protocol Q Instant (m [x] 1))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: Instant");
		assertThatThrownBy(() -> Clojure.read("(extend-protocol Missing String (m [x] 1))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such protocol: Missing");
		assertThatThrownBy(() -> Clojure.read("(satisfies? Missing 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such protocol: Missing");
		assertThatThrownBy(() -> Clojure.read("(defprotocol Q (m [x])) (defrecord R [a] Q (nope [x] 1))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Can't define method not in interfaces: nope");
		assertThatThrownBy(() -> Clojure.read("(defrecord R [a] :load-ns true)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("defrecord option");
	}

	@Test
	void aProtocolMethodOfSeveralAritiesStoresOneLambdaApplyingTheArityOfTheCall() {
		// each arity lowers as a single-arity method would, behind one lambda applying
		// the
		// one whose count the call has; an inline body names the method once per arity,
		// an extension spells fn clauses
		String inline = lowered("(defprotocol Q (m [x] [x y] \"doc\")) (reify Q (m [_] 1) (m [_ y] y))");
		assertThat(inline).contains("(LAMBDA (|c%_|) 1)")
			.contains("(LAMBDA (|c%_| |c%y|) |c%y|)")
			.containsPattern(
					"\\(COND \\(\\(= \\|__clojure_\\d+\\| 1\\) \\(APPLY \\|__clojure_\\d+\\| \\|__clojure_\\d+\\|\\)\\)"
							+ " \\(\\(= \\|__clojure_\\d+\\| 2\\) \\(APPLY \\|__clojure_\\d+\\| \\|__clojure_\\d+\\|\\)\\)"
							+ " \\(T \\(RONTOLISP::%CLOJURE-ARITY-EXCEPTION \"wrong number of arguments passed to: m\"\\)\\)\\)");
		String extension = lowered("(defprotocol Q (m [x] [x y])) (extend-protocol Q String (m ([s] 1) ([s y] y)))");
		assertThat(extension).contains("(LAMBDA (|c%s|) 1)").contains("(LAMBDA (|c%s| |c%y|) |c%y|)");
		// a single arity stores its own lambda, as before
		assertThat(lowered("(defprotocol Q (m [x] [x y])) (reify Q (m [_] 1))")).doesNotContain("(LENGTH ");
		// the oracle's refusals: an arity the protocol does not declare, one twice, a
		// method declared twice or without a target, extension clauses of one arity
		assertThatThrownBy(() -> Clojure.read("(defprotocol Q (m [x])) (reify Q (m [_ y] y))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Can't define method not in interfaces: m");
		assertThatThrownBy(
				() -> Clojure.read("(defprotocol Q (m [x] [x y])) (deftype T [] Q (m [_] 1) (m [_] 2))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("duplicate method implementation: m");
		assertThatThrownBy(() -> Clojure.read("(defprotocol Q (m [x] [x y])) (reify Q (m ([_] 1) ([_ y] y)))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("an inline method takes one parameter vector, naming the method again");
		assertThatThrownBy(() -> Clojure.read("(defprotocol Q (m [x]) (m [x y]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Function m in protocol Q was redefined. Specify all arities in single definition.");
		assertThatThrownBy(() -> Clojure.read("(defprotocol Q (m [x] []))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Definition of function m in protocol Q must take at least one arg.");
		assertThatThrownBy(() -> Clojure.read("(defprotocol Q (m [x] [y]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("defprotocol Q declares m twice with 1 parameters");
		assertThatThrownBy(() -> Clojure
			.read("(defprotocol Q (m [x] [x y])) (extend-protocol Q String (m ([s] 1) ([t] 2)))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Can't have 2 overloads with same arity");
	}

	@Test
	void onlyAProtocolExtendedToAWalkedClassWalksPastAnExactMiss() {
		// no walked class: no walk in the dispatcher, satisfies? or the runtime
		String exact = lowered(
				"(defprotocol Q (m [x])) (extend-protocol Q String (m [s] 1) Object (m [_] 2))" + " (satisfies? Q 1)");
		assertThat(exact).doesNotContain("C%PROTOCOL-SUPER").doesNotContain("C%PROTOCOL-ROW");
		// a throwable target: the row under its class keyword, the dispatcher (lowered
		// above the extension, so the lowering starts over) and satisfies? walk, and the
		// runtime tests the class's chain
		String walked = lowered("(defprotocol Q (m [x])) (defprotocol O (o [x])) (satisfies? Q 1)"
				+ " (extend-protocol Q Throwable (m [_] 1))");
		assertThat(walked).contains("(GETHASH (LIST :C%KEYWORD \"java.lang.Throwable\")")
			.contains("(DEFUN C%PROTOCOL-SUPER (|x| |table| |method| |miss|)")
			.contains("(RONTOLISP::%CLOJURE-INSTANCE-OF |x| '(\"java.lang.Throwable\"))")
			.containsPattern("\\(C%PROTOCOL-SUPER \\(CAR \\|__clojure_\\d+\\|\\) \\|c%Q%methods\\|")
			.containsPattern("\\(C%PROTOCOL-SUPER \\|__clojure_\\d+\\| \\|c%Q%methods\\| NIL");
		// a protocol of the same program extended to no walked class keeps its dispatcher
		assertThat(walked)
			.doesNotContainPattern("\\(C%PROTOCOL-SUPER \\(CAR \\|__clojure_\\d+\\|\\) \\|c%O%methods\\|");
		// an interface over core kinds tests the kinds; a host class the host object
		assertThat(lowered("(defprotocol Q (m [x])) (extend clojure.lang.IRef Q {:m (fn [_] 1)})"))
			.contains("\"clojure.lang.IRef\"")
			.contains("(RONTOLISP::%CLOJURE-VAR-P |x|)");
		assertThat(lowered("(defprotocol Q (m [x])) (extend-type java.time.Instant Q (m [_] 1))"))
			.contains("(RONTOLISP::%CLOJURE-HOST-OBJECT-P |x| \"java.time.Instant\")");
		// extends? names the exact class
		assertThat(lowered("(defprotocol Q (m [x])) (extends? Q Exception)"))
			.contains("(GETHASH (LIST :C%KEYWORD \"java.lang.Exception\")");
	}

	@Test
	void theWalkTriesSuperclassesThenInterfacesEachAheadOfItsSupertypes() {
		// the classes (ARef unrelated to the throwables), then the interfaces, each
		// subtype first: IRef below IDeref by the kinds of value either holds
		assertThat(ClojureProtocolLowering
			.walkOrder(List.of("java.lang.Throwable", "clojure.lang.IDeref", "clojure.lang.IExceptionInfo",
					"java.lang.Exception", "clojure.lang.IRef", "clojure.lang.ARef", "java.lang.RuntimeException")))
			.containsExactly("clojure.lang.ARef", "java.lang.RuntimeException", "java.lang.Exception",
					"java.lang.Throwable", "clojure.lang.IExceptionInfo", "clojure.lang.IRef", "clojure.lang.IDeref");
		assertThat(ClojureProtocolLowering.walkOrder(List.of("java.util.Date", "java.io.Writer")))
			.containsExactly("java.util.Date", "java.io.Writer");
	}

	@Test
	void aTypedRowOfCollReduceOrIKVReduceIsStoredThroughTheLibrary() {
		// what reduce and reduce-kv hand a record, deftype or reify to: the store is the
		// reducible family's producer, so a program storing none folds every arm
		String reify = lowered("(require '[clojure.core.protocols :as p])"
				+ " (def r (reify p/CollReduce (coll-reduce [_ f init] init)))");
		assertThat(reify).contains(
				"(RONTOLISP::%CLOJURE-COLL-REDUCER-ROW |c%clojure.core.protocols/CollReduce%methods|" + " (CADR ");
		String record = lowered("(require '[clojure.core.protocols :as p]) (defrecord R [a])"
				+ " (extend-protocol p/IKVReduce R (kv-reduce [r f init] init))");
		assertThat(record).contains(
				"(RONTOLISP::%CLOJURE-KV-REDUCER-ROW |c%clojure.core.protocols/IKVReduce%methods| (LIST :C%KEYWORD \"R\")");
		// the namespace's own nil and Object rows, a core kind and any other protocol
		// store as before
		String core = lowered("(require '[clojure.core.protocols :as p])"
				+ " (extend-protocol p/CollReduce String (coll-reduce ([s f] 1) ([s f i] 2)))"
				+ " (defprotocol Q (m [x])) (reify Q (m [_] 1))");
		assertThat(core).doesNotContain("%CLOJURE-COLL-REDUCER-ROW").doesNotContain("%CLOJURE-KV-REDUCER-ROW");
	}

	@Test
	void aBodyImplementingAnInterfaceStoresItsRowThroughTheFamilyOfEach() {
		// one store per family under the type's tag: the interfaces it implements
		// (supers included) and a lambda per method, the body's or the oracle's
		// AbstractMethodError for one it leaves out
		String reify = lowered("(def r (reify clojure.lang.Counted (count [_] 3)))");
		assertThat(reify).contains("(RONTOLISP::%CLOJURE-COUNTED-ROW (CADR ")
			.contains("'(\"clojure.lang.Counted\") (LIST \"count\" (LAMBDA (");
		String indexed = lowered("(deftype T [n] clojure.lang.Indexed (nth [_ i] i))");
		assertThat(indexed).contains(
				"(RONTOLISP::%CLOJURE-INDEXED-ROW (LIST :C%KEYWORD \"T\") '(\"clojure.lang.Indexed\") (LIST \"nth\"")
			.contains(
					"(RONTOLISP::%CLOJURE-COUNTED-ROW (LIST :C%KEYWORD \"T\") '(\"clojure.lang.Counted\") (LIST \"count\"")
			.contains("(RONTOLISP::%CLOJURE-ABSTRACT-METHOD-ERROR \"does not define or inherit an implementation of"
					+ " the resolved method count of interface clojure.lang.Counted\")")
			.contains("(RONTOLISP::%CLOJURE-ABSTRACT-METHOD-ERROR \"does not define or inherit an implementation of"
					+ " the resolved method nth of interface clojure.lang.Indexed\")");
		// an Object override needs no group of its own, and a reify's names its class
		String object = lowered("(defprotocol P (m [x])) (reify P (m [_] 1) (toString [_] \"r\"))");
		assertThat(object).contains("(RONTOLISP::%CLOJURE-OBJECT-ROW (CADR ")
			.contains(" NIL (LIST \"toString\" (LAMBDA (")
			.contains("\"class\" \"user$reify\")");
		// a protocol-only body stores no interface row
		assertThat(lowered("(defprotocol P (m [x])) (reify P (m [_] 1)) (deftype U [] P (m [_] 2))"))
			.doesNotContain("-ROW (");
		// a group symbol resolves like the oracle's class names
		assertThat(lowered("(ns u (:import (clojure.lang IFn))) (reify IFn (invoke [_] 1))"))
			.contains("%CLOJURE-INVOKABLE-ROW");
		for (String[] refused : new String[][] {
				{ "(reify IFn (invoke [_] 1))", "Unable to resolve symbol: IFn in this context" },
				{ "(reify clojure.lang.IChunkedSeq (first [_] 1))",
						"clojure.lang.IChunkedSeq is not supported yet as an interface of reify" },
				{ "(reify clojure.lang.Foo)", "Unable to resolve classname: clojure.lang.Foo" },
				{ "(reify java.lang.String)", "only interfaces are supported, had: java.lang.String" },
				{ "(reify java.util.Deque)", "java.util.Deque is not supported yet as an interface of reify" },
				{ "(reify clojure.lang.Counted (cnt [_] 1))", "Can't define method not in interfaces: cnt" },
				{ "(reify clojure.lang.Counted (count [_ x] 1))", "Can't define method not in interfaces: count" },
				{ "(reify clojure.lang.Counted (count [_] 1) (count [_] 2))",
						"duplicate method implementation: count" },
				{ "(reify clojure.lang.IFn (invoke [_ & xs] xs))",
						"an interface method takes fixed parameters: invoke" },
				{ "(reify (toString [_] \"x\"))", "reify methods group under a protocol or interface name" },
				{ "(reify clojure.lang.IObj)", "Duplicate interface name \"clojure/lang/IObj\" in reify" },
				{ "(reify clojure.lang.IMeta (meta [_] {}))", "Duplicate method name \"meta\" in reify" },
				{ "(defrecord R [a] clojure.lang.ILookup)", "Duplicate interface name \"clojure/lang/ILookup\"" },
				{ "(defrecord R [a] clojure.lang.Counted (count [_] 1))",
						"Duplicate method name \"count\" in defrecord" },
				{ "(defrecord R [a] Object (equals [_ o] true))", "Duplicate method name \"equals\" in defrecord" } }) {
			assertThatThrownBy(() -> Clojure.read(refused[0], null)).as(refused[0])
				.isInstanceOf(LispReadException.class)
				.hasMessageContaining(refused[1]);
		}
	}

	@Test
	void aBodyImplementingACollectionInterfaceStoresTheRowsOfItsWholeClosure() {
		// IPersistentMap extends Iterable, Associative (IPersistentCollection, Seqable,
		// ILookup) and Counted: one store per family of the closure
		String map = lowered("(deftype M [m] clojure.lang.IPersistentMap (count [_] 0) (without [_ k] nil))");
		assertThat(map).contains(
				"(RONTOLISP::%CLOJURE-PERSISTENT-MAP-ROW (LIST :C%KEYWORD \"M\") '(\"clojure.lang.IPersistentMap\")")
			.contains("(RONTOLISP::%CLOJURE-ITERABLE-ROW (LIST :C%KEYWORD \"M\") '(\"java.lang.Iterable\")")
			.contains("(RONTOLISP::%CLOJURE-ASSOCIATIVE-ROW (LIST :C%KEYWORD \"M\")")
			.contains("(RONTOLISP::%CLOJURE-COLLECTION-ROW (LIST :C%KEYWORD \"M\")")
			.contains("(RONTOLISP::%CLOJURE-SEQABLE-ROW (LIST :C%KEYWORD \"M\")")
			.contains("(RONTOLISP::%CLOJURE-LOOKUP-ROW (LIST :C%KEYWORD \"M\")")
			.contains("(RONTOLISP::%CLOJURE-COUNTED-ROW (LIST :C%KEYWORD \"M\")")
			// an abstract method the body leaves out is the oracle's AbstractMethodError
			// ...
			.contains("the resolved method iterator of interface java.lang.Iterable\")")
			// ... a default one keeps the interface's, which no verb reads
			.doesNotContain("\"forEach\"")
			.doesNotContain("\"spliterator\"");
		// a java.util interface's equals and hashCode are Object's
		assertThat(lowered("(deftype S [] java.util.Set (equals [_ o] true))"))
			.contains("(RONTOLISP::%CLOJURE-OBJECT-ROW (LIST :C%KEYWORD \"S\") NIL (LIST \"equals\" (LAMBDA (")
			.doesNotContain("the resolved method equals");
		// a type hint on a method's name is metadata, like the oracle's
		assertThat(lowered("(deftype V [] clojure.lang.Counted (^long count [_] 1))"))
			.contains("(RONTOLISP::%CLOJURE-COUNTED-ROW (LIST :C%KEYWORD \"V\") '(\"clojure.lang.Counted\")"
					+ " (LIST \"count\" (LAMBDA (");
		assertThat(lowered("(defprotocol G (^String g [x])) (deftype W [] G (^String g [_] \"w\")) (g (W.))"))
			.contains("(|c%g| (|c%->W|))");
		// an instance call of a method several interfaces declare asks each one's test
		assertThat(lowered("(fn [x] (.containsKey x 1))"))
			.contains("(OR (RONTOLISP::%CLOJURE-IASSOCIATIVE-P |c%recv%%|)"
					+ " (RONTOLISP::%CLOJURE-ITRANSIENT-ASSOCIATIVE2-P |c%recv%%|) (RONTOLISP::%CLOJURE-JMAP-P |c%recv%%|))");
		// a MapEntry and a SeqIterator are built in place, no host class
		assertThat(lowered("(clojure.lang.MapEntry. :k 1)")).contains("(VECTOR (LIST :C%KEYWORD \"k\") 1)")
			.doesNotContain("JAVA:NEW");
		assertThat(lowered("(iterator-seq (clojure.lang.SeqIterator. (seq [1])))"))
			.contains("(RONTOLISP::%CLOJURE-ITERATOR-SEQ (RONTOLISP::%CLOJURE-SEQ-ITERATOR ");
		for (String[] refused : new String[][] {
				{ "(defrecord R [a] clojure.lang.IPersistentMap)",
						"Duplicate interface name \"clojure/lang/IPersistentMap\" in defrecord" },
				{ "(defrecord R [a] java.util.Map)", "Duplicate interface name \"java/util/Map\" in defrecord" },
				{ "(defrecord R [a] java.io.Serializable)",
						"Duplicate interface name \"java/io/Serializable\" in defrecord" },
				{ "(defrecord R [a] clojure.lang.IPersistentCollection (cons [_ x] nil))",
						"Duplicate method name \"cons\" in defrecord" },
				{ "(defrecord R [a] java.lang.Iterable (iterator [_] nil))",
						"Duplicate method name \"iterator\" in defrecord" },
				// a method of the class's own interfaces is found unnamed, like the
				// oracle's
				{ "(defrecord R [a] clojure.lang.IFn (assoc [_ k v] nil))",
						"Duplicate method name \"assoc\" in defrecord" },
				{ "(reify clojure.lang.Counted (count [_] 1) (withMeta [_ m] nil))",
						"Duplicate method name \"withMeta\" in reify" },
				// one the oracle's record leaves to the interface would override it there
				{ "(defrecord R [a] clojure.lang.IFn (assocEx [_ k v] nil))",
						"clojure.lang.IPersistentMap/assocEx is not supported yet as a method of defrecord" },
				{ "(defrecord R [a] java.lang.Iterable (forEach [_ f] nil))",
						"java.lang.Iterable/forEach is not supported yet as a method of defrecord" },
				{ "(defrecord R [a] clojure.lang.IFn (count [_ x] 1))",
						"Can't define method not in interfaces: count" },
				{ "(reify clojure.lang.ISeq (frist [_] 1))", "Can't define method not in interfaces: frist" },
				{ "(reify java.util.Map (size [_ x] 1))", "Can't define method not in interfaces: size" } }) {
			assertThatThrownBy(() -> Clojure.read(refused[0], null)).as(refused[0])
				.isInstanceOf(LispReadException.class)
				.hasMessageContaining(refused[1]);
		}
	}

	@Test
	void anExtensionToAnInterfaceKeyedByACoreKindReachesATypedValueBehindItsTest() {
		// IPersistentMap keys the core maps' row by :map; the same lambda also stands
		// under the interface's name, which the dispatcher walks to behind the
		// interface's own test
		String out = lowered("(defprotocol P (k [x])) (extend-protocol P clojure.lang.IPersistentMap (k [_] :map))");
		assertThat(out).contains("(LIST :C%KEYWORD \"map\")")
			.contains("(LIST :C%KEYWORD \"clojure.lang.IPersistentMap\")")
			.contains("(IF (OR (RONTOLISP::%CLOJURE-IMAP-P (CAR ")
			.contains("(C%PROTOCOL-SUPER (CAR ");
		// a program storing no IPersistentMap row folds the guard: no walk, no runtime
		String plain = prunedForms(
				"(defprotocol P (k [x])) (extend-protocol P clojure.lang.IPersistentMap (k [_] :map)) (println (k {}))");
		assertThat(plain).doesNotContain("C%PROTOCOL-SUPER").doesNotContain("%CLOJURE-IMAP-P");
		// one that does keeps both
		String typed = prunedForms(
				"(defprotocol P (k [x])) (extend-protocol P clojure.lang.IPersistentMap (k [_] :map))"
						+ " (deftype M [] clojure.lang.IPersistentMap (count [_] 0)) (println (k (M.)))");
		assertThat(typed).contains("(DEFUN C%PROTOCOL-SUPER ").contains("(RONTOLISP::%CLOJURE-IMAP-P ");
		// every record is an IPersistentMap: a program defining one guards the walk with
		// the record test too
		String records = prunedForms(
				"(defprotocol P (k [x])) (extend-protocol P clojure.lang.IPersistentMap (k [_] :map))"
						+ " (defrecord R [a]) (println (k (->R 1)))");
		assertThat(records).contains("(DEFUN C%PROTOCOL-SUPER ").contains("(RONTOLISP::%CLOJURE-RECORD-P (CAR ");
	}

	@Test
	void anExtensionToAnInterfaceOtherKindsImplementTooIsAWalkedClass() {
		// Sequential is spelled like :list, but a vector is one too: its row stands under
		// its own name alone, which every value reaches through the walk, so a more
		// specific interface the protocol is extended to (ISeq) answers ahead of it
		String out = prunedForms("(defprotocol P (k [x])) (extend-protocol P clojure.lang.Sequential (k [_] :s))"
				+ " (println (k [1]))");
		assertThat(out).contains("(LIST :C%KEYWORD \"clojure.lang.Sequential\")")
			.doesNotContain("(GETHASH (LIST :C%KEYWORD \"list\") ")
			.contains("(DEFUN C%PROTOCOL-SUPER ");
		// an interface spelled like the one kind whose values implement it keeps the key
		assertThat(prunedForms("(defprotocol P (k [x])) (extend-protocol P clojure.lang.IPersistentList (k [_] :l))"
				+ " (println (k (list 1)))"))
			.contains("(GETHASH (LIST :C%KEYWORD \"list\") ")
			.doesNotContain("C%PROTOCOL-SUPER");
	}

	@Test
	void tryIsAHandlerCaseInsideAnUnwindProtect() {
		assertThat(lowered("(try 1 (catch Exception e 2) (finally 3))")).contains("HANDLER-CASE")
			.contains("UNWIND-PROTECT")
			.contains("((AND ERROR (SATISFIES |C%E-CATCHES-java.lang.Exception|)) (|c%e|) 2)");
		// a Throwable catch takes every error untested, so it carries no predicate
		assertThat(lowered("(try 1 (catch Throwable e 2))")).contains("(ERROR (|c%e|) 2)")
			.doesNotContain("SATISFIES")
			.doesNotContain("C%E-PARTS");
		assertThat(lowered("(throw \"boom\")")).contains("RONTOLISP::%CLOJURE-THROW");
	}

	@Test
	void aCatchTestsItsClassThroughAPredicateOverTheClassChain() {
		String out = lowered(
				"(try 1 (catch IllegalArgumentException e 2) (catch java.io.IOException e 3) (catch Throwable t 4))");
		// one clause per catch, in order, the Throwable one plain ERROR
		assertThat(out)
			.contains("(HANDLER-CASE 1 ((AND ERROR (SATISFIES |C%E-CATCHES-java.lang.IllegalArgumentException|))"
					+ " (|c%e|) 2) ((AND ERROR (SATISFIES |C%E-CATCHES-java.io.IOException|)) (|c%e|) 3) (ERROR (|c%t|) 4))");
		// one predicate per caught class over its chain, and the exception reader a
		// program that builds no exception carries alone
		assertThat(out)
			.contains("(DEFUN |C%E-CATCHES-java.lang.IllegalArgumentException| (|c|) (RONTOLISP::%CLOJURE-CATCHES |c|"
					+ " '(\"java.lang.IllegalArgumentException\" \"java.lang.RuntimeException\" \"java.lang.Exception\""
					+ " \"java.lang.Throwable\")))")
			.contains("'(\"java.io.IOException\" \"java.lang.Exception\" \"java.lang.Throwable\")")
			.contains("(DEFUN C%E-PARTS (|c|) (DECLARE (IGNORE |c|)) NIL)")
			.doesNotContain("DEFINE-CONDITION");
		// a program that builds an exception carries the exception runtime's reader
		assertThat(lowered("(try (throw (ex-info \"m\" {})) (catch clojure.lang.ExceptionInfo e 1))"))
			.contains("(DEFINE-CONDITION C%E-EXCEPTION")
			.contains("|C%E-CATCHES-clojure.lang.ExceptionInfo|")
			.doesNotContain("(DECLARE (IGNORE |c|)) NIL)");
		// imports resolve, a class catches once however many clauses name it
		assertThat(lowered("(ns t (:import (java.io IOException))) (try 1 (catch IOException e 2))"
				+ " (try 3 (catch java.io.IOException e 4))"))
			.containsOnlyOnce("(DEFUN |C%E-CATCHES-java.io.IOException|");
	}

	@Test
	void classInstanceAndTheStackTraceMethodsReadAnExceptionsClass() {
		// class carries an exception arm; with no catch and no exception nothing can
		// reach it, so no reader travels (and the library splice strips the arm)
		assertThat(lowered("(def x 1) (class x)")).contains("((RONTOLISP::%CLOJURE-EXCEPTION-P")
			.contains("(RONTOLISP::%CLOJURE-EXCEPTION-CLASS")
			.doesNotContain("C%E-PARTS");
		// a catch of any class can hand class a condition: the reader travels
		assertThat(lowered("(try 1 (catch Throwable e (class e)))"))
			.contains("(DEFUN C%E-PARTS (|c|) (DECLARE (IGNORE |c|)) NIL)");
		// instance? of a throwable class tests the class chain
		assertThat(lowered("(def x 1) (instance? IllegalStateException x)"))
			.contains("(RONTOLISP::%CLOJURE-INSTANCE-OF |c%x| '(\"java.lang.IllegalStateException\""
					+ " \"java.lang.RuntimeException\" \"java.lang.Exception\" \"java.lang.Throwable\"))")
			.contains("(DEFUN C%E-PARTS");
		assertThatThrownBy(() -> Clojure.read("(instance? Foo 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: Foo");
		// the stack-trace methods answer from the library, with no java: call
		assertThat(lowered("(fn [e] (.printStackTrace e))")).contains("(RONTOLISP::%CLOJURE-PRINT-STACK-TRACE |c%e|)")
			.doesNotContain("JAVA:CALL");
		assertThat(lowered("(fn [e] (.getStackTrace e))")).contains("(RONTOLISP::%CLOJURE-STACK-TRACE |c%e|)")
			.doesNotContain("JAVA:CALL");
	}

	@Test
	void aCatchOfNoThrowableClassIsTheOraclesRefusal() {
		assertThatThrownBy(() -> Clojure.read("(try 1 (catch Foo e 2))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Unable to resolve classname: Foo");
		// ExceptionInfo is no default import, like the oracle's
		assertThatThrownBy(() -> Clojure.read("(try 1 (catch ExceptionInfo e 2))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Unable to resolve classname: ExceptionInfo");
		assertThatThrownBy(() -> Clojure.read("(try 1 (catch Object e 2))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Catch type is not a subclass of Throwable: java.lang.Object");
		assertThatThrownBy(() -> Clojure.read("(try 1 (catch String e 2))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Catch type is not a subclass of Throwable: java.lang.String");
		assertThatThrownBy(() -> Clojure.read("(require '[clojure.test :refer [is]]) (is (thrown? Foo (inc 1)))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Unable to resolve classname: Foo");
	}

	@Test
	void atomsAreTaggedCells() {
		assertThat(lowered("(atom 1)")).contains(":C%ATOM").contains("(VECTOR 1)");
		assertThat(lowered("(def a (atom 1)) @a")).contains("AREF");
		assertThat(lowered("(def a (atom 1)) (swap! a inc)")).contains("APPLY");
		assertThat(lowered("(def a (atom 1)) (compare-and-set! a 1 2)")).contains("EQL");
		assertThatThrownBy(() -> Clojure.read("(deref a 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("deref takes one or three arguments");
		assertThatThrownBy(() -> Clojure.read("(deref a 1 2 3)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("deref takes one or three arguments");
	}

	@Test
	void nsRequireWiresAliasesAndRefers() {
		assertThat(lowered("(ns t (:require [clojure.string :as s])) (s/join \",\" [\"a\"])")).contains("CONCATENATE");
		assertThat(lowered("(ns t (:require [clojure.string :as s :refer [join]])) (join \",\" [\"a\"])"))
			.contains("CONCATENATE");
		assertThatThrownBy(() -> Clojure.read("(ns t (:require [no.such.lib :as n]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Could not locate no/such/lib.clj");
		assertThatThrownBy(() -> Clojure.read("(s/join \",\" [\"a\"])", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: s/join");
	}

	@Test
	void bareRequireAcceptsQuotedLibspecs() {
		// the oracle's bare-require spelling: (quote spec) and 'spec wire like
		// the ns clause does; the unquoted vector stays accepted (a lenient
		// superset -- the oracle rejects it with a ClassNotFoundException)
		assertThat(lowered("(require '[clojure.string :as s]) (s/join \",\" [\"a\"])")).contains("CONCATENATE");
		assertThat(lowered("(require (quote [clojure.string :as s])) (s/join \",\" [\"a\"])")).contains("CONCATENATE");
		assertThat(lowered("(require '[clojure.string :refer [join]]) (join \",\" [\"a\"])")).contains("CONCATENATE");
		assertThat(lowered("(require '[clojure.string]) (clojure.string/join \",\" [\"a\"])")).contains("CONCATENATE");
		assertThat(lowered("(require [clojure.string :as s]) (s/join \",\" [\"a\"])")).contains("CONCATENATE");
		assertThat(lowered("(use '[clojure.string :only [upper-case]]) (upper-case \"hi\")")).contains("STRING-UPCASE");
		assertThatThrownBy(() -> Clojure.read("(require '[no.such.lib :as n])", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Could not locate no/such/lib.clj");
		assertThatThrownBy(() -> Clojure.read("(use '[no.such.lib :as n])", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Could not locate no/such/lib.clj");
	}

	@Test
	void bareRequireAcceptsPrefixLists() {
		// prefix lists wire each member under the prefix on the shared path:
		// quoted ('(prefix ...), the oracle's spelling) and bare (the
		// unquoted-vector leniency extended), :as/:refer/bare members, use and
		// the ns clauses included; a non-symbol head is refused
		assertThat(lowered("(require '(clojure [string :as s])) (s/join \",\" [\"a\"])")).contains("CONCATENATE");
		assertThat(lowered("(require (clojure [string :as s])) (s/join \",\" [\"a\"])")).contains("CONCATENATE");
		assertThat(lowered("(require '(clojure [string :refer [join]])) (join \",\" [\"a\"])")).contains("CONCATENATE");
		assertThat(lowered("(require '(clojure string)) (clojure.string/join \",\" [\"a\"])")).contains("CONCATENATE");
		assertThat(lowered("(use '(clojure [string :only [upper-case]])) (upper-case \"hi\")"))
			.contains("STRING-UPCASE");
		assertThat(lowered("(ns t (:require (clojure [string :as s]))) (s/join \",\" [\"a\"])"))
			.contains("CONCATENATE");
		assertThatThrownBy(() -> Clojure.read("(require '([clojure.string :as s]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("require takes library specs, not");
	}

	@Test
	void useOnlyAndExcludeNarrowTheRefers() {
		// :only wins over the use refer-all default, and :exclude subtracts from
		// it -- and from :refer :all -- like the oracle (clj 1.12.6.1673); the ns
		// :use clause shares the parser
		assertThat(lowered("(use '[clojure.string :only [upper-case]]) (upper-case \"hi\")")).contains("STRING-UPCASE");
		assertThatThrownBy(() -> Clojure.read("(use '[clojure.string :only [upper-case]]) (join \",\" [\"a\"])", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: join");
		assertThat(lowered("(use '[clojure.string :exclude [join]]) (upper-case \"hi\")")).contains("STRING-UPCASE");
		assertThatThrownBy(() -> Clojure.read("(use '[clojure.string :exclude [join]]) (join \",\" [\"a\"])", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: join");
		assertThat(lowered("(use '[clojure.string :only [upper-case join] :exclude [join]]) (upper-case \"hi\")"))
			.contains("STRING-UPCASE");
		assertThatThrownBy(() -> Clojure
			.read("(use '[clojure.string :only [upper-case join] :exclude [join]]) (join \",\" [\"a\"])", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: join");
		assertThat(lowered("(require '[clojure.string :refer :all :exclude [join]]) (upper-case \"hi\")"))
			.contains("STRING-UPCASE");
		assertThatThrownBy(() -> Clojure
			.read("(require '[clojure.string :refer :all :exclude [join]]) (join \",\" [\"a\"])", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: join");
		assertThat(lowered("(ns t (:use [clojure.string :only [upper-case]])) (upper-case \"hi\")"))
			.contains("STRING-UPCASE");
		assertThatThrownBy(
				() -> Clojure.read("(ns t (:use [clojure.string :only [upper-case]])) (join \",\" [\"a\"])", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: join");
	}

	@Test
	void interopLowersToTheJavaSurface() {
		assertThat(lowered("(.toUpperCase \"hi\")")).contains("JAVA:CALL").contains("toUpperCase");
		assertThat(lowered("(. \"hi\" toUpperCase)")).contains("JAVA:CALL");
		assertThat(lowered("(Math/max 3 7)")).contains("JAVA:STATIC").contains("java.lang.Math");
		// System/exit ends the process on every backend, wasm included
		for (String exit : List.of("(System/exit 3)", "(. System exit 3)", "(java.lang.System/exit 3)")) {
			assertThat(lowered(exit)).as(exit).contains("(%HOST-EXIT (LOGAND 3 255))").doesNotContain("JAVA:");
		}
		assertThat(lowered("(String. \"hi\")")).contains("JAVA:NEW").contains("java.lang.String");
		assertThat(lowered("(Integer/MAX_VALUE)")).contains("JAVA:FIELD");
		assertThat(lowered("(new String \"hi\")")).contains("JAVA:NEW");
		assertThat(lowered("((memfn toUpperCase) \"hi\")")).contains("LAMBDA").contains("toUpperCase");
		assertThatThrownBy(() -> Clojure.read("(memfn toUpperCase 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("memfn needs a plain name, not 1");
		assertThat(lowered("(proxy [java.util.function.Supplier] [] (get [] 42))")).contains("JAVA:PROXY")
			.contains("java.util.function.Supplier");
		// Several interfaces are one java:proxy, the callable last.
		assertThat(lowered("(proxy [java.util.function.Supplier java.lang.Runnable] [] (get [] 42) (run []))"))
			.contains("(JAVA:PROXY \"java.util.function.Supplier\" \"java.lang.Runnable\" (LAMBDA");
		assertThatThrownBy(() -> Clojure.read("(proxy [] [] (get [] 1))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("proxy takes at least one interface");
		// A superclass (the book's snake.clj JPanel) is a java:subclass: the
		// superclass, the quoted interfaces and methods, the constructor arguments
		// and the callable taking this first.
		assertThat(lowered(
				"(proxy [javax.swing.JPanel java.awt.event.ActionListener] [] (actionPerformed [e] nil) (toString [] \"p\"))"))
			.contains("(JAVA:SUBCLASS \"javax.swing.JPanel\"")
			.contains("'(\"java.awt.event.ActionListener\")")
			.contains("'(\"actionPerformed\" \"toString\")");
		// A class behind the first position is refused by name, before it runs.
		assertThatThrownBy(() -> Clojure.read("(proxy [java.io.File java.lang.String] [] (toString [] \"p\"))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("is a class, not an interface");
		// A final superclass is refused by name, before it runs.
		assertThatThrownBy(() -> Clojure.read("(proxy [java.lang.String] [] (toString [] \"p\"))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("proxy cannot extend final class java.lang.String");
		// A duplicate method is refused by name, before it runs.
		assertThatThrownBy(() -> Clojure.read("(proxy [java.io.File] [\"f\"] (getName [] 1) (getName [] 2))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("proxy defines method getName twice");
		// A proxy-super outside a proxy method is refused by name, before it runs.
		assertThatThrownBy(() -> Clojure.read("(proxy-super toString)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("proxy-super outside a proxy method");
		// java:proxy keeps Object's three, so a body for one would never run.
		assertThatThrownBy(() -> Clojure.read("(proxy [java.lang.Runnable] [] (run []) (toString [] \"p\"))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("proxy cannot override toString yet");
		assertThatThrownBy(() -> Clojure.read("(proxy [java.lang.Runnable] [] (equals [o] true))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("proxy cannot override equals yet");
		// The sequences test's File proxy is a java:subclass with constructor
		// arguments, this in its bodies and no refusal.
		assertThat(lowered("(proxy [java.io.File] [\"f\"] (lastModified [] (str this)) (toString [] \"f!\"))"))
			.contains("(JAVA:SUBCLASS \"java.io.File\"")
			.contains("'(\"lastModified\" \"toString\")")
			.contains("\"f\"");
	}

	@Test
	void aZeroArgumentStringWriterLowersToAStringOutputStream() {
		// A zero-argument (new java.io.StringWriter) -- the oracle's own
		// with-out-str construction -- is a string output stream on every backend,
		// never a java:new (which wasm refuses); .toString of one answers the
		// text so far without clearing it, so str reads it back twice.
		assertThat(lowered("(new java.io.StringWriter)")).contains("(RONTOLISP::%CLOJURE-STRING-WRITER)")
			.doesNotContain("JAVA:NEW");
		assertThat(lowered("(java.io.StringWriter.)")).contains("(RONTOLISP::%CLOJURE-STRING-WRITER)")
			.doesNotContain("JAVA:NEW");
		// an initial capacity keeps the host construction, like any other class
		assertThat(lowered("(new java.io.StringWriter 16)")).contains("JAVA:NEW").contains("java.io.StringWriter");
		assertThat(lowered("(let [s (new java.io.StringWriter)] (.toString s))")).contains(
				"((RONTOLISP::%CLOJURE-STREAM-P |__clojure_0|) (RONTOLISP::%CLOJURE-STREAM-STRING |__clojure_0|))");
	}

	@Test
	void setBangWritesADeftypeMutableFieldThroughItsSlot() {
		String out = lowered("(defprotocol P (bump! [c])) "
				+ "(deftype T [^:unsynchronized-mutable x ^:volatile-mutable y z] P (bump! [_] (set! x (inc x))))");
		// the constructor keeps the immutable field in the table, the mutable ones in
		// a slot vector behind it; the method reads and writes the slots
		assertThat(out).contains("(VECTOR |c%x| |c%y|)")
			.contains("(SYMBOL-MACROLET ((|c%x| (AREF")
			.contains("(SETF (AREF |__clojure_");
		// a closure copies the field at creation: a let* around the lambda
		assertThat(lowered("(defprotocol P (r [c])) (deftype T [^:unsynchronized-mutable x] P (r [_] (fn [] x)))"))
			.containsPattern("\\(LET\\* \\(\\(\\|c%x\\| \\(AREF \\|__clojure_\\d+\\| 0\\)\\)\\) \\(LAMBDA");
	}

	@Test
	void setBangRefusesEveryOtherTargetLikeTheOracle() {
		String proto = "(defprotocol P (m [c] ) (m2 [c v])) ";
		// ClojureScript's ^:mutable is no marker on the oracle
		assertThatThrownBy(() -> Clojure.read(proto + "(deftype T [^:mutable x] P (m [_] (set! x 1)))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Cannot assign to non-mutable: x");
		assertThatThrownBy(() -> Clojure.read(proto + "(deftype T [x] P (m [_] (set! x 1)))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Cannot assign to non-mutable: x");
		// a closure holds a copy; a parameter or a local shadows the field
		for (String body : List.of("((fn [] (set! x 1)))", "(#(set! x %) 1)", "(letfn [(g [] (set! x 1))] (g))",
				"(let [x 2] (set! x 1))", "(first (for [i [1]] (set! x i)))", "(first (lazy-seq (set! x 1)))",
				"(dosync (set! x 1))")) {
			assertThatThrownBy(
					() -> Clojure.read(proto + "(deftype T [^:unsynchronized-mutable x] P (m [_] " + body + "))", null))
				.as(body)
				.isInstanceOf(LispReadException.class)
				.hasMessageContaining("Cannot assign to non-mutable: x");
		}
		assertThatThrownBy(
				() -> Clojure.read(proto + "(deftype T [^:unsynchronized-mutable x] P (m2 [_ x] (set! x 1)))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Cannot assign to non-mutable: x");
		assertThatThrownBy(() -> Clojure.read("(fn [a] (set! a 1))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Cannot assign to non-mutable: a");
		assertThatThrownBy(() -> Clojure.read("(defrecord R [^:unsynchronized-mutable a])", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining(":volatile-mutable or :unsynchronized-mutable not supported for record fields");
		// a non-dynamic global signals at run time, after the value evaluates: the
		// oracle's IllegalStateException
		assertThat(lowered("(def y 1) (set! y 2)")).contains(
				"(PROGN 2 (RONTOLISP::%CLOJURE-ILLEGAL-STATE-EXCEPTION \"Can't change/establish root binding of: y with set\"))");
		// a thread-bound dynamic var sets the thread-local value inside a
		// binding (the depth counter beside the var says whether it is bound)
		// and signals the same error outside one, after the value evaluates
		assertThat(lowered("(def ^:dynamic *d* 1) (set! *d* 2)")).contains("%bound-depth")
			.contains("(SETQ |c%*d*|")
			.contains(
					"(RONTOLISP::%CLOJURE-ILLEGAL-STATE-EXCEPTION \"Can't change/establish root binding of: *d* with set\")");
		assertThat(lowered("(def ^:dynamic *d* 1) (binding [*d* 5] (set! *d* 2))")).contains("(LET*")
			.contains("(|c%*d*| 5)")
			.contains("(|c%*d*%bound-depth| (+ |c%*d*%bound-depth| 1))");
		// a clojure.main-bound flag is always thread-bound, so it assigns (*ns* too);
		// a flag clojure.main does not bind, and a stream, assign like a dynamic var
		assertThat(lowered("(set! *warn-on-reflection* true)"))
			.contains("(DEFVAR RONTOLISP::%CLOJURE-WARN-ON-REFLECTION RONTOLISP::%CLOJURE-FALSE)")
			.endsWith("(SETQ RONTOLISP::%CLOJURE-WARN-ON-REFLECTION T)");
		assertThat(lowered("(set! *unchecked-math* false)"))
			.endsWith("(SETQ RONTOLISP::%CLOJURE-UNCHECKED-MATH RONTOLISP::%CLOJURE-FALSE)");
		assertThat(lowered("(set! *print-dup* true)")).contains("(> RONTOLISP::%CLOJURE-PRINT-DUP-DEPTH 0)")
			.contains("(SETQ RONTOLISP::%CLOJURE-PRINT-DUP ")
			.contains(
					"(RONTOLISP::%CLOJURE-ILLEGAL-STATE-EXCEPTION \"Can't change/establish root binding of: *print-dup* with set\")");
		assertThat(lowered("(set! *out* *out*)")).contains("(> RONTOLISP::%CLOJURE-OUT-DEPTH 0)")
			.contains("(SETQ *STANDARD-OUTPUT* ");
		assertThat(lowered("(set! *ns* 1)")).endsWith("\n(SETQ RONTOLISP::%CLOJURE-NS 1)");
		// anything else names what is missing
		assertThatThrownBy(() -> Clojure.read("(set! *no-such-var* 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("set! of a var is not supported yet: *no-such-var*");
		assertThatThrownBy(() -> Clojure.read("(set! (.-f (Object.)) 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("set! of a host field is not supported yet");
		assertThatThrownBy(() -> Clojure.read("(set! 3 4)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Invalid assignment target");
		assertThatThrownBy(() -> Clojure.read("(def y 1) (set! y)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Malformed assignment, expecting (set! target val)");
	}

	@Test
	void staticMembersResolveByHostArity() {
		// a zero-argument static method is a static call, even in the (. Class m)
		// spelling; a field stays a field read, in call and dot-form alike
		assertThat(lowered("(System/currentTimeMillis)")).contains("JAVA:STATIC").doesNotContain("JAVA:FIELD");
		assertThat(lowered("(. System currentTimeMillis)")).contains("JAVA:STATIC").doesNotContain("JAVA:FIELD");
		assertThat(lowered("(Integer/MAX_VALUE)")).contains("JAVA:FIELD").doesNotContain("JAVA:STATIC");
		assertThat(lowered("(. Math PI)")).contains("JAVA:FIELD").doesNotContain("JAVA:STATIC");
		assertThat(lowered("(Math/PI)")).contains("JAVA:FIELD").doesNotContain("JAVA:STATIC");
		// calls with arguments keep the static call, answering T-or-false for booleans
		assertThat(lowered("(Integer/parseInt \"42\")")).contains("JAVA:STATIC");
		assertThat(lowered("(Character/isWhitespace \\a)")).contains("JAVA:STATIC").contains("IF");
	}

	@Test
	void staticMembersLowerAsValues() {
		// a static field as a value reads the field, through an import too
		assertThat(lowered("(ns awtimp (:import (java.awt.event KeyEvent))) KeyEvent/VK_LEFT")).contains("JAVA:FIELD")
			.contains("java.awt.event.KeyEvent");
		assertThat(lowered("Math/PI")).contains("JAVA:FIELD").contains("java.lang.Math");
		// a static method as a value is an arity-dispatching lambda over the static
		// call, so (every? Character/isWhitespace s) runs
		assertThat(lowered("(every? Character/isWhitespace \"   \")")).contains("LAMBDA")
			.contains("JAVA:STATIC")
			.contains("wrong number of arguments passed to: Character/isWhitespace");
		// a variadic-only member has no value form; an unknown member or class keeps
		// the field read, whose run-time error names what is missing
		assertThatThrownBy(() -> Clojure.read("String/format", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("String/format is variadic and has no value form");
		assertThat(lowered("Math/PII")).contains("JAVA:FIELD");
		assertThat(lowered("NoSuchClass/foo")).contains("JAVA:FIELD");
	}

	@Test
	void hostBooleansAnswerTorFalseForKnownReceivers() {
		// a construction literal of a class whose overloads at that arity all
		// answer a primitive boolean wraps the java:call in T-or-false: the IF
		// sits directly over the call (the STRINGP dispatch owns the outer one)
		assertThat(lowered("(println (.isEmpty (java.util.ArrayList.)))")).contains("(IF (JAVA:CALL");
		assertThat(lowered("(println (.contains (java.util.ArrayList. [1]) 2))")).contains("(IF (JAVA:CALL");
		// a let/if-let/when-let local bound to a construction carries the class;
		// a non-boolean answer and an unknown receiver keep the bare call
		assertThat(lowered("(let [al (java.util.ArrayList.)] (println (.isEmpty al)))")).contains("(IF (JAVA:CALL");
		assertThat(lowered("(println (if-let [al (java.util.ArrayList.)] (.isEmpty al) :e))"))
			.contains("(IF (JAVA:CALL");
		assertThat(lowered("(println (when-let [al (java.util.ArrayList.)] (.isEmpty al)))"))
			.contains("(IF (JAVA:CALL");
		assertThat(lowered("(println (.. (java.util.ArrayList. [1]) (subList 0 1) (isEmpty)))"))
			.contains("(IF (JAVA:CALL");
		assertThat(lowered("(println (.. (java.util.ArrayList. [1]) (subList 0 1) (size)))")).contains("JAVA:CALL")
			.doesNotContain("(IF (JAVA:CALL");
		assertThat(lowered("(let [al (java.util.ArrayList.)] (println (.size al)))")).contains("JAVA:CALL")
			.doesNotContain("(IF (JAVA:CALL");
		assertThat(lowered("(defn check-empty [x] (.isEmpty x))")).contains("JAVA:CALL")
			.doesNotContain("(IF (JAVA:CALL");
		// a shadowing binding hides the class again
		assertThat(lowered("(let [al (java.util.ArrayList.)] ((fn [al] (.isEmpty al)) 1))"))
			.doesNotContain("(IF (JAVA:CALL");
	}

	@Test
	void arraysLowerToTheCoreArrayForms() {
		assertThat(lowered("(make-array String 3)")).contains("MAKE-ARRAY");
		assertThat(lowered("(make-array String 2 2)")).contains("MAKE-ARRAY").contains("LIST");
		assertThatThrownBy(() -> Clojure.read("(make-array 1 2)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("make-array takes a class name");
		assertThat(lowered("(let [a20-v 1] (aget a20-v 0))")).contains("AREF");
		assertThat(lowered("(let [a20-v 1] (aset a20-v 0 1))")).contains("SETF").contains("AREF");
		assertThat(lowered("(let [a20-v 1] (alength a20-v))")).contains("ARRAY-DIMENSION");
	}

	@Test
	void inIsStandardInput() {
		// read as a value, *in* is the bound stream, or the standard input's stream
		// value where *standard-input* holds the t designator (Clojure's true); a
		// binding target is the variable itself
		assertThat(lowered("*in*")).contains("(RONTOLISP::%CLOJURE-IN)").doesNotContain("*STANDARD-INPUT*");
		assertThat(lowered("(binding [*in* *in*] 1)")).contains("(*STANDARD-INPUT* (RONTOLISP::%CLOJURE-IN))");
		assertThat(lowered("[*out* *err*]")).contains("(RONTOLISP::%CLOJURE-OUT)")
			.contains("(RONTOLISP::%CLOJURE-ERR)");
		assertThat(lowered("(.readLine *in*)")).contains("READ-LINE");
	}

	@Test
	void charactersAndRadixLowerAsThemselves() {
		assertThat(lowered("\\a")).contains("#\\a");
		assertThat(lowered("0xFF")).isEqualTo(FALSE_BINDING + "255");
		assertThat(lowered("1M")).isEqualTo(FALSE_BINDING + "1");
		assertThat(lowered("0.1M")).isEqualTo(FALSE_BINDING + "1/10");
	}

	private static String loweredWithMacros(String source) {
		List<LispVal> forms = Clojure.read(source, null, ClojureMacroTime.create());
		return forms.stream().map(LispVal::print).collect(Collectors.joining("\n"));
	}

	@Test
	void defmacroEmitsATableEntryAndRegistersTheExpander() {
		String out = loweredWithMacros("(defmacro mu-unless [c t] (list 'if c nil t))");
		assertThat(out).contains("PROGN")
			.contains("|c%mu-unless%macro|")
			.contains("LAMBDA")
			.contains("wrong number of arguments passed to macro: mu-unless");
	}

	@Test
	void macroCallsExpandAtLowerTime() {
		String out = loweredWithMacros("(defmacro mu-unless [c t] (list 'if c nil t)) (mu-unless false 42)");
		assertThat(out).contains("|c%mu-unless%macro|");
		// the call lowered to the if over the false value, answering 42 for false
		assertThat(out).contains("(LET ((|__clojure_").contains("RONTOLISP::%CLOJURE-FALSE").contains(" 42 NIL))");
	}

	@Test
	void aMacroBodyCallsAHelperDefinedAboveIt() {
		// the oracle evaluates each top-level form before the next compiles
		assertThat(loweredWithMacros("(defn mu-helper [x] (list 'inc x)) (defmacro mu-m [x] (mu-helper x)) (mu-m 1)"))
			.endsWith("(+ 1 1)");
	}

	@Test
	void aDefValueRunsAtMacroTimeOnlyWhenAnExpansionReadsIt() {
		// a def the body never reads is never built at lower time: the throwing one
		// below fails the compile only once a macro body reads it, naming the macro
		String unread = "(def mu-boom (throw (ex-info \"built\" {}))) (def mu-n 2) (defmacro mu-k [] mu-n) (mu-k)";
		assertThat(loweredWithMacros(unread)).endsWith("2");
		assertThatThrownBy(() -> loweredWithMacros(unread.replace("[] mu-n", "[] mu-boom")))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("in macro `mu-k`")
			.hasMessageContaining("built");
	}

	@Test
	void aRedefinitionReadsTheRootItSupersedesAtMacroTime() {
		// a lazy root reading its own var sees the earlier one, and a defonce keeps it
		assertThat(loweredWithMacros(
				"(def mu-r 1) (def mu-r (+ mu-r 10)) (defonce mu-r 99) (defmacro mu-read [] mu-r) (mu-read)"))
			.endsWith("11");
	}

	@Test
	void macroCallsAboveTheirDefinitionNameTheMissingExpander() {
		assertThatThrownBy(
				() -> Clojure.read("(mu-early 1) (defmacro mu-early [x] x)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("macro `mu-early` used before its definition");
		assertThatThrownBy(() -> Clojure.read("(mu-missing 1)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: mu-missing");
		assertThatThrownBy(() -> Clojure.read("(defmacro mu-noeval [x] x) (mu-noeval 1)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("cannot expand without a macro evaluator");
	}

	@Test
	void defmacroRefusesCoreFormsAndEnvironments() {
		assertThatThrownBy(() -> Clojure.read("(defmacro if [x] x)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("if cannot name a macro: it names a special form");
		assertThatThrownBy(() -> Clojure.read("(defmacro deref [x] x)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("deref cannot name a macro: the reader spells its own forms with it");
		assertThatThrownBy(() -> Clojure.read("(defmacro s/m [x] x)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("cannot name a macro");
		assertThatThrownBy(() -> Clojure.read("(defmacro mu-env [&form x] x)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("&form is not supported yet");
		assertThatThrownBy(() -> Clojure.read("(defmacro mu-env2 [x &env] x)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("&env is not supported yet");
		assertThatThrownBy(() -> Clojure.read("(defmacro)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("defmacro needs a name");
		assertThatThrownBy(() -> Clojure.read("(defmacro mu-short)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("defmacro needs a parameter vector and a body");
	}

	@Test
	void aCoreNamedMacroShadowsTheLoweringBelowItsDefinitionOnly() {
		String out = loweredWithMacros("""
				(defn f [] (with-out-str (print 1)))
				(defmacro with-out-str [& body] `(str "<" (clojure.core/with-out-str ~@body) ">"))
				(defn g [] (with-out-str (print 2)))
				(defn h [] (clojure.core/with-out-str (print 3)))""");
		String f = out.substring(out.indexOf("(DEFUN |c%f|"), out.indexOf("(PROGN (SETQ |c%with-out-str%macro|"));
		String g = out.substring(out.indexOf("(DEFUN |c%g|"), out.indexOf("(DEFUN |c%h|"));
		String h = out.substring(out.indexOf("(DEFUN |c%h|"));
		// above the definition the core row lowers; below it the macro expands
		// (its expansion reaching the core row through clojure.core/), and the
		// qualified spelling is the core row whatever the program defines
		assertThat(f).contains("MAKE-STRING-OUTPUT-STREAM").doesNotContain("\"<\"");
		assertThat(g).contains("MAKE-STRING-OUTPUT-STREAM").contains("\"<\"").contains("\">\"");
		assertThat(h).contains("MAKE-STRING-OUTPUT-STREAM").doesNotContain("\"<\"");
	}

	@Test
	void aCoreNamedMacroBelowAMacroKeepsTheCoreMeaningInItsSyntaxQuote() {
		// the oracle resolves a syntax-quoted symbol at read time: with-out-str is
		// clojure.core's until the program's macro is defined
		String out = loweredWithMacros("""
				(defmacro wrap [& body] `(with-out-str ~@body))
				(defmacro with-out-str [& body] `(do ~@body))
				(defmacro wrap2 [& body] `(with-out-str ~@body))""");
		String wrap = out.substring(0, out.indexOf("(PROGN (SETQ |c%with-out-str%macro|"));
		String wrap2 = out.substring(out.indexOf("(PROGN (SETQ |c%wrap2%macro|"));
		assertThat(wrap).contains("'|c%clojure.core/with-out-str|");
		assertThat(wrap2).contains("'|c%user/with-out-str|").doesNotContain("clojure.core");
	}

	@Test
	void aMacroShadowsADefinitionHeadForThePreScanBelowIt() {
		// declare above the macro forward-declares; below it the expansion defines
		String out = loweredWithMacros("""
				(declare d0)
				(defmacro declare [& names] `(do ~@(map (fn [n] (list 'def n :declared)) names)))
				(declare d1)
				(println d0 d1)""");
		assertThat(out).contains("(SETQ |c%d1| ");
	}

	@Test
	void clojureCoreSpellingsNameTheCoreVar() {
		assertThat(loweredWithMacros("(defn inc [x] x) (clojure.core/inc 1)")).contains("(+ 1 1)");
		assertThat(loweredWithMacros("(defn inc [x] x) (map clojure.core/inc [1])")).doesNotContain("#'|c%inc|");
		assertThat(loweredWithMacros("(list clojure.core/*out*)")).contains("(RONTOLISP::%CLOJURE-OUT)");
		assertThatThrownBy(() -> Clojure.read("(clojure.core/nope 1)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such var: clojure.core/nope");
		assertThatThrownBy(() -> Clojure.read("clojure.core/nope", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such var: clojure.core/nope");
		assertThatThrownBy(() -> Clojure.read("(clojure.core/bit-shift-left 1 2)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: clojure.core/bit-shift-left");
	}

	@Test
	void macrosHaveNoValue() {
		assertThatThrownBy(() -> Clojure.read("(defmacro mu-val [x] x) (list mu-val)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("is a macro, not a function");
		assertThatThrownBy(() -> Clojure.read("(defmacro mu-val2 [x] x) mu-val2", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("is a macro, not a function");
		assertThatThrownBy(
				() -> Clojure.read("(defmacro mu-val3 [x] x) (map mu-val3 '(1))", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("is a macro, not a function");
	}

	@Test
	void unquoteOutsideSyntaxQuoteIsAnError() {
		assertThatThrownBy(() -> Clojure.read("(list ~x)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unquote outside syntax-quote");
		assertThatThrownBy(() -> Clojure.read("~@x", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unquote-splicing outside syntax-quote");
		assertThatThrownBy(() -> Clojure.read("`~@x", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unquote-splicing outside a sequence");
		assertThat(lowered("(def spl-v [1]) `{:a ~@spl-v}")).contains("APPEND");
	}

	@Test
	void syntaxQuoteQualifiesSplicesAndGensyms() {
		String out = loweredWithMacros("(defmacro mu-sq [x] `(a ~x ~@'(1 2) s#))");
		assertThat(out).contains("(GENSYM \"s\")").contains("APPEND").contains("'|c%user/a|");
		String out2 = loweredWithMacros("(defmacro mu-doc \"docs\" [x] x) (mu-doc 1)");
		assertThat(out2).contains("|c%mu-doc%macro|");
	}

	@Test
	void gensymFreshnessAcrossTwoExpansions() {
		String out = loweredWithMacros(
				"(defmacro mu-bg [e] `(let [x# ~e] x#)) (def mu-a (mu-bg 1)) (def mu-b (mu-bg 2))");
		java.util.regex.Matcher found = java.util.regex.Pattern.compile("#:\\S+").matcher(out);
		java.util.Set<String> gensyms = new java.util.HashSet<>();
		while (found.find()) {
			gensyms.add(found.group());
		}
		assertThat(gensyms).hasSizeGreaterThanOrEqualTo(2);
	}

	@Test
	void macroexpandLowersToTheRuntimeExpander() {
		assertThat(lowered("(macroexpand-1 '(mu-x 1))")).contains("C%MACROEXPAND-1").contains("'");
		assertThat(lowered("(macroexpand '(mu-x 1))")).contains("(C%MACROEXPAND '");
		assertThat(lowered("macroexpand-1")).contains("LAMBDA").contains("C%MACROEXPAND-1");
		assertThat(lowered("macroexpand")).contains("LAMBDA").contains("C%MACROEXPAND");
		assertThatThrownBy(() -> Clojure.read("(macroexpand-1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("macroexpand-1 takes one form");
		assertThatThrownBy(() -> Clojure.read("(macroexpand 1 2)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("macroexpand takes one form");
	}

	@Test
	void multiArityMacrosDispatchByCount() {
		String out = loweredWithMacros(
				"(defmacro mu-ch ([x f] (list '. x f)) ([x f & m] (concat (list 'mu-ch (list '. x f)) m))) (mu-ch \"hi\" toUpperCase length)");
		assertThat(out).contains("wrong number of arguments passed to macro: mu-ch");
		assertThat(out).contains("STRING-UPCASE");
		assertThatThrownBy(() -> Clojure.read("(defmacro mu-dup ([x] x) ([y] y))", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("two clauses for arity 1");
		assertThatThrownBy(
				() -> Clojure.read("(defmacro mu-ar ([x] x) ([x y] y)) (mu-ar 1 2 3)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("in macro `mu-ar`")
			.hasMessageContaining("wrong number of arguments passed to macro: mu-ar");
	}

	@Test
	void gensymLowersToThePrimitive() {
		assertThat(lowered("(gensym)")).contains("(GENSYM)");
		assertThat(lowered("(gensym \"p\")")).contains("(GENSYM \"p\")");
		assertThat(lowered("gensym")).contains("#'GENSYM");
		assertThatThrownBy(() -> Clojure.read("(gensym 1 2)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("gensym takes an optional prefix");
	}

	@Test
	void readerMetadataDropsOnNamesAndAttachesToLiterals() {
		// reader metadata on a name or a local parses and drops: the object lowers as
		// itself; on a collection literal it attaches, like the oracle's reader
		assertThat(lowered("(def v 1) ^:k v")).contains("|c%v|").doesNotContain("META");
		assertThat(lowered("^:k [1]")).contains("%CLOJURE-PUT-META");
		assertThat(lowered("'^:k x")).contains("|c%x|").doesNotContain("META");
		// a with-meta call attaches at run time
		assertThat(lowered("(with-meta [1] {:a 1})")).contains("%CLOJURE-WITH-META");
		assertThat(lowered("(meta [1])")).contains("%CLOJURE-META");
		assertThatThrownBy(() -> Clojure.read("(with-meta 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/with-meta");
		assertThatThrownBy(() -> Clojure.read("^1 [1]", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Metadata must be Symbol,Keyword,String or Map");
	}

	@Test
	void varQuoteAnswersTheInternedVarWithItsDefinitionsMetadata() {
		// #'x is the interned var: its name, a closure reading the root, and the
		// metadata the newest definition recorded (all constants here, so the
		// site lowers the map itself)
		String hello = lowered("(defn hello \"Doc.\" [username] username) #'hello");
		assertThat(hello).contains("(RONTOLISP::%CLOJURE-VAR \"user/hello\" (LAMBDA NIL #'|c%hello|)")
			.contains("\"Doc.\"")
			.contains("\"arglists\"")
			.doesNotContain("|c%hello%meta|");
		assertThat(lowered("(def x 1) (var x)")).contains("(RONTOLISP::%CLOJURE-VAR \"user/x\" (LAMBDA NIL |c%x|)");
		// a redefinition's #' reads the newest definition's root and docstring
		String redefined = lowered("(defn h \"one\" [] 1) (defn h \"two\" [a] 2) #'h");
		assertThat(redefined).contains("(LAMBDA NIL #'|c%h%def2|)").contains("\"two\"");
		assertThat(redefined.substring(redefined.indexOf("%CLOJURE-VAR"))).doesNotContain("\"one\"");
		// metadata that evaluates (a :test fn) is stored beside the var where the
		// definition stands, ahead of it, and the site reads the store
		String busted = lowered("(defn ^{:test (fn [] (assert (nil? (busted))))} busted [] \"busted\") #'busted");
		assertThat(busted).contains("(SETQ |c%busted%meta|")
			.contains("(RONTOLISP::%CLOJURE-VAR \"user/busted\" (LAMBDA NIL #'|c%busted|) |c%busted%meta|)");
		assertThat(busted.indexOf("(SETQ |c%busted%meta|")).isLessThan(busted.indexOf("(DEFUN |c%busted|"));
		// a var is invoked through its root; test reads :test from its metadata
		assertThat(lowered("(defn f [x] x) (#'f 1)")).contains("(RONTOLISP::%CLOJURE-CALL (RONTOLISP::%CLOJURE-VAR");
		assertThat(lowered("(def x 1) (test #'x)")).contains("(RONTOLISP::%CLOJURE-VAR-TEST (RONTOLISP::%CLOJURE-VAR");
		assertThat(lowered("(map test [])")).contains("#'RONTOLISP::%CLOJURE-VAR-TEST-V");
		// only a dynamic var's site carries the binding-depth reader thread-bound? asks
		assertThat(lowered("(def ^:dynamic *d* 1) (thread-bound? #'*d*)"))
			.contains("(RONTOLISP::%CLOJURE-VAR-DYNAMIC \"user/*d*\" (LAMBDA NIL |c%*d*|)")
			.contains("(LAMBDA NIL |c%*d*%bound-depth|)")
			.contains("RONTOLISP::%CLOJURE-IS-THREAD-BOUND");
		assertThat(lowered("(def x 1) (thread-bound? #'x)")).doesNotContain("VAR-DYNAMIC");
		assertThat(lowered("(def x 1) #'x")).doesNotContain("VAR-DYNAMIC");
		assertThat(lowered("(map thread-bound? [])")).contains("#'RONTOLISP::%CLOJURE-IS-THREAD-BOUND-V");
		// a local is no var (the oracle resolves past it): under a shadowing local
		// the root is read through a top-level reader the local cannot shadow
		assertThat(lowered("(def x 1) (let [x 2] #'x)")).contains("(DEFUN |c%x%root| NIL |c%x|)")
			.contains("(LAMBDA NIL (|c%x%root|))");
		assertThatThrownBy(() -> Clojure.read("(let [q 1] #'q)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Unable to resolve var: q in this context");
		// a core name is the core var: its root the core value, its metadata :name/:ns
		// (a macro's :macro), a stream special's site the depth reader thread-bound? asks
		assertThat(lowered("#'str")).contains("(RONTOLISP::%CLOJURE-VAR \"clojure.core/str\" (LAMBDA NIL");
		assertThat(lowered("(defn str [] 1) #'clojure.core/str"))
			.contains("(RONTOLISP::%CLOJURE-VAR \"clojure.core/str\"");
		assertThat(lowered("(defn str [] 1) #'str")).contains("\"user/str\"").doesNotContain("clojure.core/str");
		assertThat(lowered("#'when")).contains("Can't take value of a macro: #'clojure.core/when")
			.contains("(LIST :C%KEYWORD \"macro\") T");
		assertThat(lowered("#'*out*"))
			.contains("(RONTOLISP::%CLOJURE-VAR-DYNAMIC \"clojure.core/*out*\" (LAMBDA NIL (RONTOLISP::%CLOJURE-OUT))")
			.contains("(LAMBDA NIL RONTOLISP::%CLOJURE-OUT-DEPTH)");
		assertThat(lowered("#'inc")).doesNotContain("VAR-DYNAMIC");
		// every binding of a stream or agent special rebinds its counter one deeper
		assertThat(lowered("(with-out-str (print 1))"))
			.contains("(RONTOLISP::%CLOJURE-OUT-DEPTH (+ RONTOLISP::%CLOJURE-OUT-DEPTH 1))")
			.contains("(DEFVAR RONTOLISP::%CLOJURE-OUT-DEPTH 0)");
		assertThat(lowered("(print 1)")).doesNotContain("DEPTH");
		assertThat(lowered("(binding [*in* *in*] 1)"))
			.contains("(RONTOLISP::%CLOJURE-IN-DEPTH (+ RONTOLISP::%CLOJURE-IN-DEPTH 1))");
		assertThat(lowered("(send (agent 0) inc)"))
			.contains("(RONTOLISP::%CLOJURE-AGENT-DEPTH (+ RONTOLISP::%CLOJURE-AGENT-DEPTH 1))");
		// *err* and the flags are core specials too; a flag clojure.main binds is
		// always thread-bound
		assertThat(lowered("#'*err*"))
			.contains("(RONTOLISP::%CLOJURE-VAR-DYNAMIC \"clojure.core/*err*\" (LAMBDA NIL (RONTOLISP::%CLOJURE-ERR))")
			.contains("(LAMBDA NIL RONTOLISP::%CLOJURE-ERR-DEPTH)");
		assertThat(lowered("#'*assert*")).contains(
				"(RONTOLISP::%CLOJURE-VAR-DYNAMIC \"clojure.core/*assert*\" (LAMBDA NIL RONTOLISP::%CLOJURE-ASSERT)")
			.contains("(LAMBDA NIL 1)")
			.contains("(DEFVAR RONTOLISP::%CLOJURE-ASSERT T)");
		assertThat(lowered("#'*ns*"))
			.contains("(RONTOLISP::%CLOJURE-VAR-DYNAMIC \"clojure.core/*ns*\" (LAMBDA NIL RONTOLISP::%CLOJURE-NS)")
			.contains("(LAMBDA NIL 1)");
		assertThatThrownBy(() -> Clojure.read("#'all-ns", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("var of a clojure.core var is not supported yet: #'clojure.core/all-ns");
		assertThatThrownBy(() -> Clojure.read("#'if", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Unable to resolve var: if in this context");
		assertThatThrownBy(() -> Clojure.read("(test)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/test");
	}

	@Test
	void conditionalBindingFormsLowerOverLet() {
		assertThat(lowered("(when-let [x 1] x)")).contains("LET*").contains("RONTOLISP::%CLOJURE-FALSE");
		assertThat(lowered("(when-let [[a b] [1 2]] (+ a b))")).contains("LET*").contains("%CLOJURE-NTH");
		assertThat(lowered("(if-let [x 1] x :e)")).contains("LET*").contains(":C%KEYWORD");
		assertThat(lowered("(when-not false 1)")).contains("IF");
		assertThat(lowered("(if-not nil 1 2)")).contains("IF");
		assertThat(lowered("(when-first [x [1]] x)")).contains("LET*").contains("CAR");
		assertThatThrownBy(() -> Clojure.read("(when-let [x] x)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("when-let takes a single binding pair");
		assertThatThrownBy(() -> Clojure.read("(if-let [x 1] a b c)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("if-let takes a binding vector");
	}

	@Test
	void collectionsAnswerCallsAndValues() {
		assertThat(lowered("(#{:h} :h)")).contains("GETHASH");
		assertThat(lowered("({:a 1} :a :d)")).contains("GETHASH");
		assertThat(lowered("([1 2] 0)")).contains("NTH");
		assertThat(lowered("(filter #{:h} [:h])")).contains("%CLOJURE-FILTER").contains("GETHASH");
		assertThatThrownBy(() -> Clojure.read("(#{:h})", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("a collection as a function takes a key");
	}

	@Test
	void aRuntimeWorkerTakesARealFunctionWrappedAtTheCallSite() {
		// A function form, a form yielding one (comp, partial, juxt, a transducer) or a
		// variable bound to one passes to a spliced worker as itself; any other value (a
		// set, map, parameter) is wrapped in the IFn dispatcher at the call site, so a
		// program passing only real functions never carries the dispatcher
		List<String> real = List.of("(map inc [1])", "(map + [1] [2])", "(filter odd? [1])", "(mapv inc [1])",
				"(filterv odd? [1])", "(mapcat list [1])", "(iterate inc 0)", "(repeatedly 2 (fn [] 1))",
				"(max-key inc 1 2)", "(juxt inc dec)", "(fnil inc 0)", "(every-pred odd?)", "(some-fn odd?)",
				"(reduce-kv assoc {} {})", "(update-vals {} inc)", "(update-keys {} inc)", "(partition-by odd? [1])",
				"(vary-meta [] assoc :a 1)", "(split-with odd? [1])", "(keep inc [1])", "(remove odd? [1])",
				"(map :a [{}])", "(into [] (map inc) [1])", "(transduce (map inc) + [1])",
				"(sequence (filter odd?) [1])", "(eduction (map inc) (filter odd?) [1])", "(completing + inc)",
				"(map (comp inc dec) [1])", "(map (partial + 1) [1])", "(filter (complement odd?) [1])",
				"(map (juxt inc) [1])", "(let [f (fn [x] x)] (map f [1]))", "(def g (comp inc)) (map g [1])",
				"(update {:a 1} :a (partial + 1))", "(assoc-in {} [:a :b] 1)", "((comp inc dec) 1)",
				"(clojure.string/replace \"a\" #\"a\" \"b\")", "(clojure.string/replace \"a\" #\"a\" (fn [m] m))");
		for (String source : real) {
			assertThat(lowered(source)).as(source)
				.doesNotContain("%CLOJURE-AS-FN")
				.doesNotContain("%CLOJURE-CALL")
				.doesNotContain("%CLOJURE-RE-REPLACEMENT");
		}
		assertThat(lowered("(def s #{1}) (map s [1])"))
			.contains("(RONTOLISP::%CLOJURE-MAP (RONTOLISP::%CLOJURE-AS-FN |c%s|)");
		assertThat(lowered("(def m {1 2}) (filter m [1])"))
			.contains("(RONTOLISP::%CLOJURE-FILTER (RONTOLISP::%CLOJURE-AS-FN |c%m|)");
		assertThat(lowered("(defn f [g xs] (mapv g xs))")).contains("(RONTOLISP::%CLOJURE-AS-FN |c%g|)");
		assertThat(lowered("(def s #{1}) (juxt s inc)")).contains("(RONTOLISP::%CLOJURE-AS-FN |c%s|)");
		assertThat(lowered("(defn f [xf] (transduce xf + [1]))")).contains("(RONTOLISP::%CLOJURE-AS-FN |c%xf|)");
		assertThat(lowered("(defn f [xf] (into [] xf [1]))")).contains("(RONTOLISP::%CLOJURE-AS-FN |c%xf|)");
		assertThat(lowered("(defn f [r] (clojure.string/replace \"a\" #\"a\" r))"))
			.contains("(RONTOLISP::%CLOJURE-RE-REPLACEMENT ");
		// as values, the verbs wrap their function parameter at run time
		assertThat(lowered("(apply map [inc [1]])")).contains("%CLOJURE-AS-FN");
	}

	@Test
	void aProgramPassingRealFunctionsSplicesNoDispatcher() {
		// end to end: the pruned program carries the dispatcher only once a value that
		// may hold a collection is called
		String plain = "(println (map inc [1]) (filter odd? [1]) (mapv (comp inc dec) [1])"
				+ " (into [] (map inc) [1]) (update {:a 1} :a inc) (reduce-kv (fn [a k v] v) 0 {:a 1})"
				+ " (clojure.string/replace \"ab\" #\"b\" \"c\"))";
		assertThat(splicesDispatcher(plain)).isFalse();
		assertThat(splicesDispatcher(plain + " (def s #{1}) (println (map s [1]))")).isTrue();
	}

	@Test
	void inlineLoopsFuncallALocalBoundToARealFunction() {
		// the inline loops decide directness from the datum: a local bound to a real
		// function is funcalled, a local that may hold a collection keeps the dispatcher
		List<String> direct = List.of("(let [f odd?] (println (every? f [1])))",
				"(let [f odd?] (println (some f [1])))", "(let [f odd?] (println (take-while f [1])))",
				"(let [f odd?] (println (drop-while f [1])))", "(let [f inc] (println (group-by f [2 1])))",
				"(let [f inc] (println (sort-by f [2 1])))", "(let [f inc g >] (println (sort-by f g [2 1])))",
				"(let [f >] (println (sort f [2 1])))", "(let [f inc] (println ((comp f dec) 1)))",
				"(let [f inc] (println ((partial f) 1)))", "(let [f odd?] (println ((complement f) 1)))",
				"(let [f inc] (println ((memoize f) 1)))", "(let [f inc] (println (trampoline f 1)))",
				"(let [f +] (println (merge-with f {:a 1} {:a 2})))", "(let [f odd?] (println (map f [1])))");
		for (String source : direct) {
			assertThat(lowered(source)).as(source).doesNotContain("%CLOJURE-CALL");
			assertThat(splicesDispatcher(source)).as(source).isFalse();
		}
		List<String> dispatched = List.of("(def s #{1}) (println (every? s [1]))",
				"(let [s #{1}] (println (every? s [1])))", "(let [s #{1}] (println (sort-by s [2 1])))",
				"(let [s #{1}] (println ((comp s dec) 2)))", "(defn f [g] (println (some g [1])))",
				// a value lambda's parameter can spell a user local's name
				"(let [every-pred inc] (println (apply every? [every-pred [1]])))");
		for (String source : dispatched) {
			assertThat(lowered(source)).as(source).contains("%CLOJURE-CALL");
		}
	}

	private static boolean splicesDispatcher(String source) {
		return am.ik.rontolisp.cli.CompileFrontendAccess.clojure(source, true, false)
			.forms()
			.stream()
			.map(LispVal::print)
			.anyMatch(text -> text.contains("(DEFUN RONTOLISP::%CLOJURE-CALL "));
	}

	@Test
	void seqVerbsLowerOverTheSeqView() {
		// The dropping verbs are one call to a lazy-or-strict runtime worker, a
		// function passed as itself and any other value wrapped in the dispatcher at the
		// call site (so a program passing a function never carries it)
		assertThat(lowered("(keep inc [1])")).contains("(RONTOLISP::%CLOJURE-KEEP ").doesNotContain("%CLOJURE-CALL");
		assertThat(lowered("(def m {1 2}) (keep m [1])")).contains("(RONTOLISP::%CLOJURE-KEEP ")
			.contains("(RONTOLISP::%CLOJURE-AS-FN |c%m|)");
		assertThat(lowered("(def s #{1}) (remove s [1])")).contains("(RONTOLISP::%CLOJURE-REMOVE ")
			.contains("(RONTOLISP::%CLOJURE-AS-FN |c%s|)");
		assertThat(lowered("(keep-indexed odd? [1])")).contains("(RONTOLISP::%CLOJURE-INDEXED ").endsWith(" T)");
		assertThat(lowered("(map-indexed vector [1])")).contains("(RONTOLISP::%CLOJURE-INDEXED ").endsWith(" NIL)");
		assertThat(lowered("(every? odd? [1])")).contains("LABELS").contains("RONTOLISP::%CLOJURE-FALSE");
		assertThat(lowered("(some odd? [1])")).contains("LABELS");
		assertThat(lowered("(distinct [1])")).contains("(RONTOLISP::%CLOJURE-DISTINCT (VECTOR 1))");
		// a two-argument partition evaluates its size once, as size and step
		assertThat(lowered("(partition (inc 1) [1])")).containsOnlyOnce("(+ 1 1)")
			.contains("(RONTOLISP::%CLOJURE-PARTITION ");
		assertThat(lowered("(take-while odd? [1])")).contains("LABELS");
		assertThat(lowered("(interleave [1] [2])"))
			.contains("(RONTOLISP::%CLOJURE-INTERLEAVE (LIST (VECTOR 1) (VECTOR 2)))");
		assertThat(lowered("(interpose 0 [1])")).contains("(RONTOLISP::%CLOJURE-INTERPOSE 0 (VECTOR 1))");
		assertThat(lowered("(zipmap [1] [2])")).contains("GETHASH");
		assertThat(lowered("(sort [2 1])")).contains("SORT").contains("COPY-LIST");
		assertThat(lowered("(group-by odd? [1])")).contains("GETHASH");
		assertThatThrownBy(() -> Clojure.read("(partition 2 1 [1] [2])", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("partition takes a size");
	}

	@Test
	void wholeCollectionConsumersRealizeAndPrefixConsumersStep() {
		// a realized lazy seq's tail is another wrapper: a consumer walking the list with
		// a Common Lisp list operation takes the whole-collection view (a strict list is
		// never copied), one that stops early steps with %clojure-seq-rest
		String all = "(RONTOLISP::%CLOJURE-SEQ-ALL ";
		for (String form : List.of("(last [1])", "(butlast [1])", "(sort [1])", "(sort-by - [1])",
				"(group-by odd? [1])", "(frequencies [1])", "(apply + [1])", "(select-keys {} [1])",
				"(clojure.string/join [1])", "(reverse [1])", "(count x)", "(set x)")) {
			assertThat(lowered("(def x [1]) " + form)).as(form).contains(all);
		}
		String step = "(RONTOLISP::%CLOJURE-SEQ-REST ";
		for (String form : List.of("(some odd? [1])", "(every? odd? [1])", "(take-while odd? [1])",
				"(drop-while odd? [1])", "(zipmap [1] [2])")) {
			assertThat(lowered(form)).as(form).contains(step).doesNotContain(all);
		}
		assertThat(lowered("(second [1 2])"))
			.isEqualTo(FALSE_BINDING + "(RONTOLISP::%CLOJURE-SEQ-NTH (VECTOR 1 2) 1 NIL)");
		// a lazy seq is empty when it realizes to nothing
		assertThat(lowered("(def x [1]) (empty? x)")).contains("(NULL (RONTOLISP::%CLOJURE-SEQ ");
	}

	@Test
	void countAndEmptyPartAListFromEveryWrapperByOneTestOfItsHead() {
		// a cons headed by a CL keyword is a tagged wrapper (no user list holds one): a
		// plain list reaches its arm past one test, the per-kind tests run only behind
		// it,
		// and a wrapper no arm takes is refused, so false and the scalars are not empty
		for (String form : List.of("(count x)", "(empty? x)")) {
			String lowered = lowered("(def x [1]) " + form).substring(FALSE_BINDING.length());
			assertThat(lowered).as(form)
				.contains("(IF (KEYWORDP (CAR ")
				.doesNotContain("%CLOJURE-RE-PATTERN-P")
				.doesNotContainPattern("\\(EQ \\S+ RONTOLISP::%CLOJURE-FALSE\\)");
		}
		assertThat(lowered("(def x [1]) (count x)")).contains("count needs a collection");
	}

	@Test
	void convenienceFnsLowerOverCoreAndHelpers() {
		assertThat(lowered("(mapv inc [1 2 3])")).contains("RONTOLISP::%CLOJURE-MAPV");
		assertThat(lowered("(filterv odd? [1])")).contains("RONTOLISP::%CLOJURE-FILTERV");
		assertThat(lowered("(mapcat reverse [[1]])")).contains("RONTOLISP::%CLOJURE-MAPCAT");
		assertThat(lowered("(ffirst [[1]])")).contains("CAR").contains("%CLOJURE-SEQ");
		assertThat(lowered("(nfirst [[1]])")).contains("CDR").contains("%CLOJURE-SEQ");
		assertThat(lowered("(boolean 1)")).contains("RONTOLISP::%CLOJURE-FALSE");
		assertThat(lowered("(char 97)")).contains("RONTOLISP::%CLOJURE-CHAR");
		assertThat(lowered("(name :a/b)")).contains("RONTOLISP::%CLOJURE-NAME");
		assertThat(lowered("(namespace :a/b)")).contains("RONTOLISP::%CLOJURE-NAMESPACE");
		assertThat(lowered("(keyword \"a\" \"b\")")).contains("RONTOLISP::%CLOJURE-KEYWORD-2");
		assertThat(lowered("(keyword \"a\")")).contains("RONTOLISP::%CLOJURE-KEYWORD-1");
		assertThat(lowered("(symbol \"a\")")).contains("RONTOLISP::%CLOJURE-SYMBOL-1");
		assertThat(lowered("(symbol \"a\" \"b\")")).contains("RONTOLISP::%CLOJURE-SYMBOL-2");
		assertThat(lowered("(assert true)")).contains("ERROR");
		assertThat(lowered("(rand 5)")).contains("RANDOM");
		assertThat(lowered("(rand-int 5)")).contains("TRUNCATE").contains("RANDOM");
		assertThat(lowered("(rand-nth [1])")).contains("RANDOM").contains("RONTOLISP::%CLOJURE-REALIZE-ALL");
		assertThat(lowered("(shuffle [1])")).contains("RONTOLISP::%CLOJURE-SHUFFLE");
		assertThat(lowered("(map mapv [inc] [[1]])")).contains("LAMBDA");
		assertThat(lowered("(map rand-nth [[1]])")).contains("LAMBDA");
		assertThat(lowered("(map vals [{:a 1}])")).contains("LAMBDA").contains("MAPHASH");
		assertThat(lowered("(mapcat reverse)")).contains(
				"(RONTOLISP::%CLOJURE-XF-MAPCAT (LAMBDA (|c%reverse-coll|) (REVERSE (RONTOLISP::%CLOJURE-SEQ-ALL |c%reverse-coll|))))");
		assertThatThrownBy(() -> Clojure.read("(mapv inc)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("mapv takes a function and collections");
	}

	@Test
	void collectionVerbsLowerToValuesAndVecCoerces() {
		assertThat(lowered("(vec [1 2])")).contains("RONTOLISP::%CLOJURE-REALIZE-ALL").contains("COERCE");
		assertThat(lowered("(map vec [[1]])")).contains("LAMBDA").contains("RONTOLISP::%CLOJURE-REALIZE-ALL");
		assertThat(lowered("(map assoc [{:a 1}] [:a] [2])")).contains("LAMBDA")
			.contains("RONTOLISP::%CLOJURE-PLIST-TABLE");
		assertThat(lowered("(map dissoc [{:a 1}] [:a])")).contains("LAMBDA").contains("REMHASH");
		assertThat(lowered("(map get [{:a 1}] [:a])")).contains("LAMBDA").contains("GETHASH");
		assertThat(lowered("(map contains? [{:a 1}] [:a])")).contains("LAMBDA").contains("GETHASH");
		assertThat(lowered("(map merge [{:a 1}] [{:b 2}])")).contains("LAMBDA").contains("MAPCAR");
		assertThat(lowered("(map conj [[1]] [2])")).contains("LAMBDA").contains("REDUCE");
		assertThat(lowered("(map disj [#{1}] [1])")).contains("LAMBDA").contains("REMHASH");
		assertThat(lowered("(map set [[1]])")).contains("LAMBDA").contains("GETHASH");
		assertThat(lowered("(map hash-map [:a] [1])")).contains("LAMBDA").contains("RONTOLISP::%CLOJURE-PLIST-TABLE");
		assertThat(lowered("(map array-map [:a] [1])")).contains("LAMBDA").contains("RONTOLISP::%CLOJURE-PLIST-TABLE");
		assertThat(lowered("(let [a (atom [1])] (swap! a conj 1))")).contains("APPLY").contains("REDUCE");
		assertThatThrownBy(() -> Clojure.read("(vec)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("vec takes one collection");
		assertThatThrownBy(() -> Clojure.read("(vec [1] [2])", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("vec takes one collection");
	}

	@Test
	void mapVerbsBuildFreshTables() {
		// a real function applies directly, any other value through the dispatcher
		assertThat(lowered("(update {:a 1} :a inc)")).contains("(APPLY ")
			.doesNotContain("RONTOLISP::%CLOJURE-CALL")
			.contains("HASH-TABLE");
		assertThat(lowered("(update-in {:a 1} [:a] inc)")).doesNotContain("RONTOLISP::%CLOJURE-CALL");
		assertThat(lowered("(def m {1 2}) (update {:a 1} :a m)")).contains("RONTOLISP::%CLOJURE-CALL");
		assertThat(lowered("(def m {1 2}) (update-in {:a 1} [:a] m)")).contains("RONTOLISP::%CLOJURE-CALL");
		assertThat(lowered("(assoc-in {} [:a] 1)")).contains("HASH-TABLE");
		assertThat(lowered("(get-in {:a 1} [:a])")).contains("GETHASH");
		assertThat(lowered("(select-keys {:a 1} [:a])")).contains("GETHASH");
		assertThat(lowered("(merge-with + {:a 1} {:a 2})")).contains("MAPHASH");
		assertThat(lowered("(into [] [1])")).contains("REDUCE");
		assertThat(lowered("(frequencies [1])")).contains("GETHASH");
		assertThatThrownBy(() -> Clojure.read("(update-in {:a 1} :a inc)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("update-in takes a vector of keys");
		assertThat(lowered("(into [] (map inc) [1])")).contains("(RONTOLISP::%CLOJURE-INTO-XF (VECTOR)");
		assertThatThrownBy(() -> Clojure.read("(into [])", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("into takes a target, an optional transducer and a source");
	}

	@Test
	void higherOrderFormsComposeAndCache() {
		assertThat(lowered("((comp inc inc) 5)")).contains("LAMBDA").contains("FUNCALL");
		assertThat(lowered("((partial + 1) 2)")).contains("LAMBDA").doesNotContain("RONTOLISP::%CLOJURE-CALL");
		assertThat(lowered("(def m {1 2}) ((partial m) 1)")).contains("RONTOLISP::%CLOJURE-CALL");
		assertThat(lowered("((complement odd?) 1)")).contains("LAMBDA");
		assertThat(lowered("((constantly 1) 2)")).contains("LAMBDA");
		assertThat(lowered("(memoize inc)")).contains("HASH-TABLE");
		assertThat(lowered("(trampoline inc 1)")).contains("LABELS").contains("FUNCTIONP");
	}

	@Test
	void predicatesAndCastsReadAndAnswer() {
		assertThat(lowered("(coll? [1])")).contains("RONTOLISP::%CLOJURE-IS-COLL")
			.contains("RONTOLISP::%CLOJURE-FALSE");
		assertThat(lowered("(symbol? 'a)")).contains("SYMBOLP");
		assertThat(lowered("(instance? String \"a\")")).contains("STRINGP");
		// an interface tests every kind implementing it, then the host object, the value
		// bound once
		assertThat(lowered("(instance? java.util.Map (identity {}))")).contains("(HASH-TABLE-P ")
			.contains("(RONTOLISP::%CLOJURE-SORTED-MAP-P ")
			.contains("(RONTOLISP::%CLOJURE-HOST-OBJECT-P ")
			.contains("\"java.util.Map\")")
			.contains("(LET* ((");
		assertThat(lowered("(def x 1) (instance? Number x)")).contains("(RONTOLISP::%CLOJURE-HOST-NUMBER-P |c%x|)");
		assertThat(lowered("(def x 1) (instance? clojure.lang.IFn x)")).doesNotContain("HOST-OBJECT-P")
			.contains("FUNCTIONP");
		assertThat(lowered("(instance? Integer 1)")).contains("(PROGN 1 RONTOLISP::%CLOJURE-FALSE)");
		assertThat(lowered("(class 1)")).contains(":C%KEYWORD");
		// int/long are the oracle's object casts; a literal folds through its own type's
		assertThat(lowered("(def x 1.5) (int x)")).contains("(RONTOLISP::%CLOJURE-INT-CAST |c%x|)");
		assertThat(lowered("(def x 1.5) (long x)")).contains("(RONTOLISP::%CLOJURE-LONG-CAST |c%x|)");
		assertThat(lowered("(def x [1]) (int (count x))")).doesNotContain("-CAST");
		assertThat(lowered("(defn f [count x] (int (count x)))")).contains("%CLOJURE-INT-CAST");
		assertThat(lowered("(int 1.5)")).endsWith("\n1");
		assertThat(lowered("(int 1e10)"))
			.contains("(RONTOLISP::%CLOJURE-ILLEGAL-ARGUMENT-EXCEPTION \"Value out of range for int: 1.0E10\")");
		assertThat(lowered("(int 3000000000)"))
			.contains("(RONTOLISP::%CLOJURE-ARITHMETIC-EXCEPTION \"integer overflow\")");
		assertThatThrownBy(() -> Clojure.read("(long 1 2)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (2) passed to: clojure.core/long");
		assertThatThrownBy(() -> Clojure.read("(instance? Point 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: Point");
	}

	@Test
	void ioEntryPointsAndFormat() {
		assertThat(lowered("(spit \"f\" \"x\")")).contains("WITH-OPEN-FILE")
			.contains("WRITE-STRING")
			.contains("%CLOJURE-STR-OF");
		assertThat(lowered("(slurp \"f\")")).contains("(RONTOLISP::%CLOJURE-SLURP \"f\")");
		assertThat(lowered("(line-seq \"f\")")).contains("READ-LINE").contains("STREAMP");
		assertThat(lowered("(ns t (:require [clojure.java.io :as jio])) (jio/reader \"f\")"))
			.contains("(|c%clojure.java.io/reader| \"f\")");
		assertThat(lowered("(ns t (:require [clojure.java.io :as jio])) (line-seq (jio/reader \"f\"))"))
			.contains("READ-LINE")
			.contains("STREAMP");
		assertThat(lowered("(format \"%s=%d\" :a 1)")).contains("FORMAT").contains("~A");
		// file-seq walks a java.io.File (the clojure.java.io runtime), slurp and spit
		// take an encoding through the namespace's reader and writer
		assertThat(lowered("(def x 1) (file-seq x)")).contains("(RONTOLISP::%CLOJURE-IO-FILE-SEQ |c%x|)");
		assertThat(lowered("(slurp \"f\" :encoding \"UTF-8\")"))
			.contains("(RONTOLISP::%CLOJURE-IO-SLURP \"f\" \"UTF-8\")");
		assertThat(lowered("(spit \"f\" 1 :encoding \"UTF-8\")"))
			.contains("(RONTOLISP::%CLOJURE-IO-SPIT \"f\" 1 NIL \"UTF-8\")");
		assertThatThrownBy(() -> Clojure.read("(reader \"f\")", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: reader");
		assertThatThrownBy(() -> Clojure.read("(format \"%e\" 1.5)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("format directive %e is not supported yet");
		assertThatThrownBy(() -> Clojure.read("(format x 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("format takes a literal format string");
	}

	@Test
	void javaIoIsABuiltInNamespaceLoadedAtItsFirstQualifiedName() {
		// clojure.java.io is Clojure source in the jar, loaded like clojure.walk on its
		// first qualified name too (the oracle has it loaded before the program): its
		// vars are the namespace's, called directly
		assertThat(lowered("(ns t (:require [clojure.java.io :as jio])) (jio/reader \"f\")"))
			.contains("(|c%clojure.java.io/reader| \"f\")");
		assertThat(lowered("(clojure.java.io/reader \"f\")")).contains("(|c%clojure.java.io/reader| \"f\")");
		assertThat(lowered("(ns t (:require [clojure.java.io :as jio :refer [reader]])) (reader \"f\")"))
			.contains("(|c%clojure.java.io/reader| \"f\")");
		assertThat(lowered("(ns t (:require [clojure.java.io :refer :all])) (file \"f\")"))
			.contains("(|c%clojure.java.io/file| \"f\")");
		assertThat(lowered("(ns t (:require [clojure.java.io :as jio])) jio/reader"))
			.contains("#'|c%clojure.java.io/reader|");
		// its kernels are the rontolisp.internal.io workers, which only it may require
		assertThat(lowered("(clojure.java.io/as-file \"f\")")).contains("(RONTOLISP::%CLOJURE-IO-FILE ");
		assertThatThrownBy(() -> Clojure.read("(ns t (:require [rontolisp.internal.io :as k]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("rontolisp.internal.io is internal to clojure.java.io");
	}

	@Test
	void clojureSetWiresLikeClojureString() {
		assertThat(lowered("(ns t (:require [clojure.set :as s])) (s/union #{1} #{2})"))
			.contains("RONTOLISP::%CLOJURE-SET-UNION (LIST");
		assertThat(lowered("(ns t (:require [clojure.set :refer [subset?]])) (subset? #{1} #{2})"))
			.contains("RONTOLISP::%CLOJURE-SET-SUBSET-P");
		assertThat(lowered("(ns t (:use clojure.set)) (join #{} #{} {:a :b})"))
			.contains("RONTOLISP::%CLOJURE-SET-JOIN-KM");
		assertThat(lowered("(ns t (:require [clojure.set])) (map clojure.set/select [odd?] [#{1}])"))
			.contains("RONTOLISP::%CLOJURE-SET-SELECT-V");
		assertThatThrownBy(() -> Clojure.read("(ns t (:require [clojure.set :as s])) (s/select odd?)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.set/select");
		assertThatThrownBy(() -> Clojure.read("(ns t (:require [clojure.set :as s])) (s/intersection)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.set/intersection");
		assertThatThrownBy(() -> Clojure.read("(ns t (:require [clojure.set :as s])) (s/join #{} #{} {} {})", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (4) passed to: clojure.set/join");
		assertThatThrownBy(() -> Clojure.read("(ns t (:require [clojure.set :as s])) (s/nope #{})", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: clojure.set/nope");
	}

	@Test
	void regexLiteralsLowerToCompiledPatterns() {
		assertThat(Clojure.read("#\"a+\"", null).stream().map(LispVal::print).toList().toString())
			.contains("RONTOLISP::%CLOJURE-RE-COMPILE");
	}

	@Test
	void reFormsLowerToTheRegexRuntime() {
		assertThat(lowered("(re-find #\"a+\" \"aaab\")")).contains("RONTOLISP::%CLOJURE-RE-FIND");
		assertThat(lowered("(re-find (re-matcher #\"a\" \"a\"))")).contains("RONTOLISP::%CLOJURE-RE-FIND-M");
		assertThat(lowered("(re-seq #\"a\" \"a\")")).contains("RONTOLISP::%CLOJURE-RE-SEQ");
		assertThat(lowered("(re-matches #\"a\" \"a\")")).contains("RONTOLISP::%CLOJURE-RE-MATCHES");
		assertThat(lowered("(re-groups (re-matcher #\"a\" \"a\"))")).contains("RONTOLISP::%CLOJURE-RE-GROUPS");
		assertThat(lowered("(re-pattern \"a\")")).contains("RONTOLISP::%CLOJURE-RE-PATTERN");
		assertThat(lowered("(map re-find [(re-matcher #\"a\" \"a\")])")).contains("RONTOLISP::%CLOJURE-RE-FIND-M");
		assertThat(lowered("(quote #\"a\")")).contains("RONTOLISP::%CLOJURE-RE-COMPILE");
		assertThatThrownBy(() -> Clojure.read("(re-find #\"a\" \"a\" \"a\")", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("re-find takes a matcher, or a pattern and a string");
		assertThatThrownBy(() -> Clojure.read("(re-groups #\"a\" \"a\")", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("re-groups takes a matcher");
		assertThatThrownBy(() -> Clojure.read("(re-matches #\"a\")", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("re-matches takes a pattern and a string");
	}

	@Test
	void foreignNamespacesStayRefused() {
		assertThatThrownBy(() -> Clojure.read("(ns t (:require [clojure.spec.alpha :as s]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown namespace: clojure.spec.alpha");
		assertThatThrownBy(() -> Clojure.read("(ns t (:require [clojure.xml :as x]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown namespace: clojure.xml");
	}

	@Test
	void metadataNamesDefinitionsWithoutAffectingThem() {
		assertThat(lowered("(defn- f [x] x)")).contains("(DEFUN |c%f| (|c%x|) |c%x|)");
		assertThat(lowered("(def ^:private x 1)")).contains("(SETQ |c%x| 1)");
		assertThat(lowered("(def ^:dynamic *d* 1)")).contains("(DEFPARAMETER |c%*d*| 1)")
			.contains("(DEFPARAMETER |c%*d*%bound-depth| 0)");
		assertThat(lowered("(defn ^:private f [x] x)")).contains("(DEFUN |c%f| (|c%x|) |c%x|)");
		assertThat(lowered("(defn f {:private true} [x] x)")).contains("(DEFUN |c%f| (|c%x|) |c%x|)");
		assertThat(lowered("(def x \"a docstring\" 1)")).contains("(SETQ |c%x| 1)");
		assertThat(lowered("(def x \"a docstring\")")).contains("(SETQ |c%x| \"a docstring\")");
		assertThat(lowered("(def x {:a 1})")).contains("HASH-TABLE");
		assertThat(lowered("(defn f [^String x] x)")).contains("(DEFUN |c%f| (|c%x|) |c%x|)");
		assertThat(lowered("(let [^String x 1] x)")).contains("(LET* ((|c%x| 1)) |c%x|)");
		assertThatThrownBy(() -> Clojure.read("(defmacro deref [x] x)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("cannot name a macro");
		// the reader spells ^m x with its own head, so a with-meta macro shadows only
		// the call, never reader metadata
		assertThat(lowered("(defmacro with-meta [x m] x) (def v 1) (println ^:k v)")).contains("|c%v|")
			.doesNotContain("%CLOJURE-WITH-META");
	}

	@Test
	void defonceDefstructAndStructLower() {
		assertThat(lowered("(defonce d 1)")).contains("BOUNDP").contains("(SETQ |c%d| 1)");
		assertThat(lowered("(defonce d)")).contains("(SETQ |c%d| NIL)");
		assertThat(lowered("(defstruct s :a :b)")).contains("(SETQ |c%s| (VECTOR");
		assertThat(lowered("(def s [:a]) (struct s 1)")).contains("GETHASH").contains("DOTIMES");
		assertThat(lowered("(def s [:a]) (struct-map s :a 1)")).contains("GETHASH").contains("DOTIMES");
		assertThatThrownBy(() -> Clojure.read("(defstruct s \"a\")", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("defstruct takes keyword keys");
	}

	@Test
	void stmVerbsLowerOverTheAtomCell() {
		assertThat(lowered("(ref 0)")).contains(":C%ATOM");
		assertThat(lowered("(ref 0 :validator odd?)")).contains("C%STM-PUT");
		assertThat(lowered("(dosync 1)")).contains("C%STM-DEPTH");
		assertThat(lowered("(def r (ref 0)) (alter r inc)")).contains("C%STM-CHECK").contains("No transaction");
		assertThat(lowered("(def r (ref 0)) (commute r inc)")).contains("C%STM-CHECK");
		assertThat(lowered("(def r (ref 0)) (ref-set r 1)")).contains("C%STM-CHECK");
		assertThat(lowered("(def r (ref 0)) (ensure r)")).contains("C%STM-DEPTH");
		assertThat(lowered("(agent 0)")).contains(":C%ATOM");
		assertThat(lowered("(def a (agent 0)) (send a inc)")).contains("C%AGENT").contains("C%STM-CHECK");
		assertThat(lowered("(def a (agent 0)) (send-off a inc)")).contains("C%AGENT");
		assertThat(lowered("(def a (agent 0)) (await a)")).contains("PROGN").contains("NIL");
		assertThat(lowered("(shutdown-agents)")).contains("NIL");
		assertThat(lowered("alter")).contains("LAMBDA");
		assertThatThrownBy(() -> Clojure.read("(ref 0 :history 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("ref option :history is not supported yet");
		assertThatThrownBy(() -> Clojure.read("(dosync (alter r inc))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: r");
	}

	@Test
	void bindingNeedsDynamicVars() {
		assertThat(lowered("(def ^:dynamic *d* 1) (binding [*d* 2] *d*)")).contains("LET*")
			.contains("|c%*d*|")
			.contains("(|c%*d*%bound-depth| (+ |c%*d*%bound-depth| 1))");
		assertThat(lowered("(binding [*out* 1] 1)")).contains("*STANDARD-OUTPUT*");
		// a syntax-quote qualifies the stream specials (`*out* reads
		// clojure.core/*out*), and the oracle binds the qualified spelling like
		// the bare one
		assertThat(lowered("(binding [clojure.core/*out* 1] 1)")).contains("*STANDARD-OUTPUT*");
		assertThat(lowered("(binding [clojure.core/*in* 1] 1)")).contains("*STANDARD-INPUT*");
		assertThatThrownBy(() -> Clojure.read("(binding [clojure.core/nope 1] 1)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("binding clojure.core/nope needs a ^:dynamic var");
		assertThatThrownBy(() -> Clojure.read("(def x 1) (binding [x 2] x)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("binding x needs a ^:dynamic var");
		assertThatThrownBy(() -> Clojure.read("(binding [x 2] x)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("binding x needs a ^:dynamic var");
	}

	@Test
	void dynamicDefnHoldsItsFunctionInTheValueCell() {
		// a ^:dynamic defn keeps its defun (recur and the arity helpers stay direct
		// calls) and installs the function in the value cell behind defparameter,
		// so calls route through it and binding rebinds it with dynamic extent
		assertThat(lowered("(defn ^:dynamic slow [n] (* n 2)) (slow 21)")).contains("(DEFUN |c%slow| (|c%n|)")
			.contains("(DEFPARAMETER |c%slow| #'|c%slow|)")
			.contains("(DEFPARAMETER |c%slow%bound-depth| 0)")
			.contains("(FUNCALL |c%slow| 21)");
		assertThat(lowered("(defn ^:dynamic madd ([x] 1) ([x y] 2)) (madd 1 2)")).contains("(DEFUN |c%madd%1|")
			.contains("(DEFUN |c%madd%2|")
			.contains("(DEFUN |c%madd|")
			.contains("(DEFPARAMETER |c%madd| #'|c%madd|)")
			.contains("(FUNCALL |c%madd| 1 2)");
		assertThat(lowered("(defn slow [n] (* n 2)) (slow 21)")).contains("(|c%slow| 21)")
			.doesNotContain("DEFPARAMETER");
		assertThatThrownBy(() -> Clojure.read("(defn slow [n] (* n 2)) (binding [slow 1] slow)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("binding slow needs a ^:dynamic var");
	}

	@Test
	void withOpenWithOutStrAndTimeLower() {
		assertThat(lowered("(def s \"x\") (with-open [a s] a)")).contains("UNWIND-PROTECT")
			.contains("JAVA:CALL")
			.contains("\"close\"")
			.contains("CLOSE");
		assertThat(lowered("(with-open [] 1)")).contains("1").doesNotContain("UNWIND-PROTECT");
		assertThat(lowered("(with-out-str 1)")).contains("MAKE-STRING-OUTPUT-STREAM")
			.contains("GET-OUTPUT-STREAM-STRING");
		assertThat(lowered("(time 1)")).contains("GET-INTERNAL-REAL-TIME").contains("Elapsed time: ");
		assertThat(lowered("(. *out* write \"x\")")).contains("STREAM");
	}

	@Test
	void futuresPromisesAndProxySuperStayRefused() {
		assertThatThrownBy(() -> Clojure.read("(future 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("future is not supported yet");
		assertThatThrownBy(() -> Clojure.read("(delay 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("delay is not supported yet");
		assertThatThrownBy(() -> Clojure.read("(force x)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("force is not supported yet");
		assertThatThrownBy(() -> Clojure.read("(promise)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("promise is not supported yet");
		assertThatThrownBy(() -> Clojure.read("(deliver p 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("deliver is not supported yet");
		// proxy-super lowers inside a proxy method body only.
		assertThatThrownBy(() -> Clojure.read("(proxy-super x)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("proxy-super outside a proxy method");
		assertThat(lowered("(proxy [java.io.File] [\"f\"] (toString [] (proxy-super toString)))"))
			.contains("JAVA:SUBCLASS")
			.contains("super$toString$0");
	}

	@Test
	void coreBacklogVerbsCallTheirSplicedWorkers() {
		assertThat(lowered("(drop-last [1 2])")).contains("(RONTOLISP::%CLOJURE-DROP-LAST 1 (VECTOR 1 2))");
		assertThat(lowered("(nthrest [1 2] 1)")).contains("(RONTOLISP::%CLOJURE-NTHREST (VECTOR 1 2) 1)");
		assertThat(lowered("(split-with odd? [1])")).contains("RONTOLISP::%CLOJURE-SPLIT-WITH");
		assertThat(lowered("(max-key :k {} {})")).contains("(RONTOLISP::%CLOJURE-EXTREME-KEY ").endsWith(" T)");
		assertThat(lowered("(min-key :k {} {})")).endsWith(" NIL)");
		assertThat(lowered("(juxt inc :a)")).contains("(RONTOLISP::%CLOJURE-JUXT (LIST ");
		// a two-argument partition-all evaluates its size once, as size and step
		assertThat(lowered("(partition-all (inc 1) [1 2 3])")).containsOnlyOnce("(+ 1 1)")
			.contains("RONTOLISP::%CLOJURE-PARTITION-ALL");
		// pmap is map: single-threaded, the same printed seq
		assertThat(lowered("(pmap inc [1])")).contains("RONTOLISP::%CLOJURE-MAP").doesNotContain("PMAP");
		// as values: the -v entries, which check the count with the oracle's wording
		assertThat(lowered("(map peek [[1]])")).contains("#'RONTOLISP::%CLOJURE-PEEK-V");
	}

	@Test
	void readingVerbsCallTheRunTimeReaderAndARecordClassRegistersFirst() {
		// read-string/read call the run-time reader with the calling namespace and its
		// aliases (what ::kw resolves against); a program that reads registers its
		// record classes behind the false binding, ahead of everything else
		String program = lowered("(ns rd (:require [clojure.string :as s])) (defrecord R [a]) (read-string \"1\")");
		assertThat(program)
			.startsWith(FALSE_BINDING + "(RONTOLISP::%CLOJURE-READ-REGISTER '((\"rd.R\" \"R\" (\"a\") T)))")
			.contains("(RONTOLISP::%CLOJURE-READ-STRING \"1\" '(\"rd\" ")
			.contains("(\"s\" \"clojure.string\")");
		assertThat(lowered("(read-string {:eof 1} \"\")")).contains("(RONTOLISP::%CLOJURE-READ-STRING-OPTS ");
		assertThat(lowered("(read)")).contains("(RONTOLISP::%CLOJURE-READ *STANDARD-INPUT* T NIL '(\"user\"))");
		assertThat(lowered("(read *in* false :e)")).contains("(RONTOLISP::%CLOJURE-READ (RONTOLISP::%CLOJURE-IN) ");
		assertThat(lowered("(read {} *in*)")).contains("(RONTOLISP::%CLOJURE-READ-OPTS ");
		assertThat(lowered("(map read-string [\"1\"])")).contains("RONTOLISP::%CLOJURE-READ-STRING-V");
		assertThat(lowered("(map read [])")).contains("RONTOLISP::%CLOJURE-READ-V");
		// a program that never reads registers nothing
		assertThat(lowered("(defrecord R [a])")).doesNotContain("READ-REGISTER");
		assertThatThrownBy(() -> Clojure.read("(read-string)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/read-string");
		assertThatThrownBy(() -> Clojure.read("(read 1 2 3 4 5)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (5) passed to: clojure.core/read");
	}

	@Test
	void aReaderWrapperOverAStreamIsTheStream() {
		// a PushbackReader/BufferedReader over a StringReader is a string input
		// stream and over a clojure.java.io/reader the reader itself, so read runs on
		// every backend; over anything else the host class stays a run-time choice
		assertThat(lowered("(java.io.PushbackReader. (java.io.StringReader. \"x\"))"))
			.endsWith("(RONTOLISP::%CLOJURE-STRING-READER \"x\")");
		assertThat(lowered(
				"(ns rdr (:import (java.io BufferedReader StringReader))) (BufferedReader. (new StringReader \"x\"))"))
			.endsWith("(RONTOLISP::%CLOJURE-STRING-READER \"x\")");
		assertThat(lowered(
				"(ns rdj (:require [clojure.java.io :refer [reader]])) (java.io.PushbackReader. (reader \"f\"))"))
			.endsWith("(|c%clojure.java.io/reader| \"f\")");
		assertThat(lowered("(java.io.PushbackReader. *in*)")).endsWith("(RONTOLISP::%CLOJURE-IN)");
		assertThat(lowered("(defn f [r] (java.io.PushbackReader. r))")).contains("(STREAMP ")
			.contains("(JAVA:NEW \"java.io.PushbackReader\" ");
		// a StringReader by itself stays the host class: a Java API takes it
		assertThat(lowered("(java.io.StringReader. \"x\")")).contains("(JAVA:NEW \"java.io.StringReader\" \"x\")");
	}

	@Test
	void coreBacklogArityRefusalsUseTheOracleWording() {
		assertThatThrownBy(() -> Clojure.read("(drop-last 1 2 3)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (3) passed to: clojure.core/drop-last");
		assertThatThrownBy(() -> Clojure.read("(split-at 2)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/split-at");
		assertThatThrownBy(() -> Clojure.read("(min-key count)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/min-key");
		assertThatThrownBy(() -> Clojure.read("(fnil inc 1 2 3 4)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (5) passed to: clojure.core/fnil");
		assertThatThrownBy(() -> Clojure.read("(juxt)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/juxt");
		assertThatThrownBy(() -> Clojure.read("(iteration)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/iteration");
		assertThatThrownBy(() -> Clojure.read("(reduce-kv + 0)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (2) passed to: clojure.core/reduce-kv");
		assertThatThrownBy(() -> Clojure.read("(pmap inc)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/pmap");
		assertThatThrownBy(() -> Clojure.read("(transduce (map inc) +)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (2) passed to: clojure.core/transduce");
		assertThatThrownBy(() -> Clojure.read("(take-nth 1 2 3)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (3) passed to: clojure.core/take-nth");
		assertThatThrownBy(() -> Clojure.read("(completing)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/completing");
		assertThatThrownBy(() -> Clojure.read("(replace {} [] [])", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (3) passed to: clojure.core/replace");
		assertThatThrownBy(() -> Clojure.read("(subvec [1])", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/subvec");
		assertThatThrownBy(() -> Clojure.read("(find {})", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/find");
		assertThatThrownBy(() -> Clojure.read("(key)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/key");
		assertThatThrownBy(() -> Clojure.read("(val 1 2)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (2) passed to: clojure.core/val");
		assertThatThrownBy(() -> Clojure.read("(map-entry?)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/map-entry?");
		assertThatThrownBy(() -> Clojure.read("(rseq [] [])", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (2) passed to: clojure.core/rseq");
		assertThatThrownBy(() -> Clojure.read("(find-keyword)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/find-keyword");
		assertThatThrownBy(() -> Clojure.read("(find-keyword \"a\" \"b\" \"c\")", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (3) passed to: clojure.core/find-keyword");
	}

	@Test
	void typePredicatesLowerToOneTestAnsweringTrueOrFalse() {
		// a collection predicate asks the interface's family too: the plain helper's
		// alias, which a program storing no row of that family calls in its place
		assertThat(lowered("(fn [x] (seq? x))")).contains("(RONTOLISP::%CLOJURE-IS-SEQ-TYPE ")
			.contains("RONTOLISP::%CLOJURE-FALSE");
		assertThat(lowered("(fn [x] (number? x))")).contains("(NUMBERP ");
		assertThat(lowered("(fn [x] (qualified-keyword? x))")).contains("(RONTOLISP::%CLOJURE-IS-QUALIFIED ");
		// a UUID or a host one: the UUID family's alias of the host test, which a
		// program making no UUID calls in its place
		assertThat(lowered("(fn [x] (uuid? x))")).contains("(RONTOLISP::%CLOJURE-IS-UUID ")
			.contains("\"java.util.UUID\"");
		assertThat(lowered("(map map? [1])")).contains("LAMBDA").contains("(RONTOLISP::%CLOJURE-IS-MAP-TYPE ");
		// a kind no value here has: false, the argument still evaluated
		assertThat(lowered("(delay? [1])")).contains("PROGN").contains("RONTOLISP::%CLOJURE-FALSE");
		// the sorted-aware tests: sorted? its own, set? and reversible? the helpers a
		// program storing no row of the interface's family and building no sorted
		// collection calls as the plain ones (ClojureLibrary's strip, in family order)
		assertThat(lowered("(fn [x] (sorted? x))")).contains("(RONTOLISP::%CLOJURE-IS-SORTED ");
		assertThat(lowered("(fn [x] (set? x))")).contains("(RONTOLISP::%CLOJURE-IS-SET-TYPE ");
		assertThat(lowered("(fn [x] (reversible? x))")).contains("(RONTOLISP::%CLOJURE-IS-REVERSIBLE-TYPE ");
		assertThat(lowered("(volatile! 1)")).contains(":C%VOLATILE");
		assertThat(lowered("(atom 1)")).doesNotContain(":C%VOLATILE");
	}

	@Test
	void sortedVerbsAreOneWorkerCallEach() {
		assertThat(lowered("(sorted-map :b 1 :a 2)"))
			.contains("(RONTOLISP::%CLOJURE-SORTED-MAKE NIL NIL (LIST (LIST :C%KEYWORD \"b\") 1");
		assertThat(lowered("(sorted-set-by > 3 1)")).contains("(RONTOLISP::%CLOJURE-SORTED-MAKE T (LAMBDA");
		// a literal core test is its keyword, which picks the oracle's path the way its
		// identity check does; anything else is a real function
		assertThat(lowered("(def s (sorted-set 1)) (subseq s > 1)"))
			.contains("(RONTOLISP::%CLOJURE-SUBSEQ |c%s| :> 1 T)");
		assertThat(lowered("(def s (sorted-set 1)) (rsubseq s >= 1 clojure.core/< 3)"))
			.contains("(RONTOLISP::%CLOJURE-SUBSEQ-5 |c%s| :>= 1 :< 3 NIL)");
		assertThat(lowered("(def s (sorted-set 1)) (defn t [a b] true) (subseq s t 1)"))
			.contains("(RONTOLISP::%CLOJURE-SUBSEQ |c%s| #'|c%t| 1 T)");
		assertThat(lowered("(compare 1 2)")).contains("(RONTOLISP::%CLOJURE-COMPARE 1 2)");
		assertThat(lowered("(vector-of :int 1 2)"))
			.contains("(RONTOLISP::%CLOJURE-VECTOR-OF (LIST :C%KEYWORD \"int\") (LIST 1 2))");
		assertThat(lowered("(map compare [1] [2])")).contains("#'RONTOLISP::%CLOJURE-COMPARE-V");
		assertThat(lowered("(apply sorted-map-by [> 1 2])")).contains("#'RONTOLISP::%CLOJURE-SORTED-MAP-BY-V");
		for (String[] call : new String[][] { { "(subseq (sorted-set) > 1 <)", "4", "subseq" },
				{ "(rsubseq (sorted-set) >)", "2", "rsubseq" }, { "(compare 1)", "1", "compare" },
				{ "(sorted-map-by)", "0", "sorted-map-by" }, { "(sorted-set-by)", "0", "sorted-set-by" },
				{ "(vector-of)", "0", "vector-of" } }) {
			assertThatThrownBy(() -> Clojure.read(call[0], null)).isInstanceOf(LispReadException.class)
				.hasMessageContaining("Wrong number of args (" + call[1] + ") passed to: clojure.core/" + call[2]);
		}
	}

	@Test
	void aProgramBuildingNoSortedCollectionCarriesNoneOfItsArms() {
		// the map and set verbs carry an arm for a sorted collection; a program that
		// builds none has them stripped before the splice, so neither its own forms nor
		// the library it splices name the sorted runtime
		String plain = "(def m {:a 1}) (def s #{1}) (println (get m :a) (m :a) (:a m) (contains? m :a) (count m)"
				+ " (empty? s) (assoc m :b 2) (dissoc m :a) (conj s 2) (disj s 1) (keys m) (vals m) (merge m {:c 3})"
				+ " (merge-with + m m) (select-keys m [:a]) (into {} m) (= m {:a 1}) (rseq [1 2]) (set? s)"
				+ " (reversible? []) (class m) (seq s) (first m) (rand-nth [1]) (shuffle [1]) (find m :a)"
				+ " (reduce-kv (fn [a k v] v) 0 m) (clojure.set/union s #{2}) (sort > [1 2]))"
				+ " (println (apply dissoc m [:a]) (apply disj s [1]) (apply merge [m]) (apply merge-with + [m m]))";
		String pruned = prunedForms(plain);
		assertThat(pruned).doesNotContain("%CLOJURE-SORTED")
			.doesNotContain("%CLOJURE-IS-SET")
			.doesNotContain("%CLOJURE-IS-REVERSIBLE")
			.contains("(RONTOLISP::%CLOJURE-SET-P ")
			.contains("(RONTOLISP::%CLOJURE-IS-VECTOR ");
		// one sorted collection and every arm stays, the runtime with them
		assertThat(prunedForms(plain + " (println (sorted-set 1))")).contains("(DEFUN RONTOLISP::%CLOJURE-SORTED-MAKE ")
			.contains("(RONTOLISP::%CLOJURE-SORTED-P ")
			.contains("(RONTOLISP::%CLOJURE-IS-SET ");
		// a value form of a constructor counts too
		assertThat(prunedForms("(println (apply sorted-set [1]))")).contains("(DEFUN RONTOLISP::%CLOJURE-SORTED-SET-V ")
			.contains("(DEFUN RONTOLISP::%CLOJURE-WRITE-SORTED ");
	}

	private static String prunedForms(String source) {
		return am.ik.rontolisp.cli.CompileFrontendAccess.clojure(source, true, false)
			.forms()
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"));
	}

	@Test
	void typePredicatesRefuseAWrongArgumentCountInTheOraclesWords() {
		for (String[] call : new String[][] { { "(seq?)", "0", "seq?" }, { "(map? 1 2)", "2", "map?" },
				{ "(any? 1 2)", "2", "any?" }, { "(sorted?)", "0", "sorted?" }, { "(identical? 1)", "1", "identical?" },
				{ "(distinct?)", "0", "distinct?" }, { "(not-any? odd?)", "1", "not-any?" },
				{ "(not-every? odd? [] [])", "3", "not-every?" }, { "(extends? P)", "1", "extends?" },
				{ "(NaN? 1 2)", "2", "NaN?" }, { "(qualified-symbol?)", "0", "qualified-symbol?" } }) {
			assertThatThrownBy(() -> Clojure.read(call[0], null)).isInstanceOf(LispReadException.class)
				.hasMessageContaining("Wrong number of args (" + call[1] + ") passed to: clojure.core/" + call[2]);
		}
		assertThatThrownBy(() -> Clojure.read("(extends? Nope String)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such protocol: Nope");
		assertThatThrownBy(() -> Clojure.read("(defprotocol P (m [x])) (extends? P java.time.Instantt)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: java.time.Instantt");
		for (String name : new String[] { "future?", "future-done?", "future-cancelled?", "future-cancel" }) {
			assertThatThrownBy(() -> Clojure.read("(" + name + ")", null)).isInstanceOf(LispReadException.class)
				.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/" + name);
			assertThatThrownBy(() -> Clojure.read("(" + name + " 1 2)", null)).isInstanceOf(LispReadException.class)
				.hasMessageContaining("Wrong number of args (2) passed to: clojure.core/" + name);
		}
		assertThatThrownBy(() -> Clojure.read("(map extends? [])", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: extends?");
	}

	@Test
	void transducerAritiesBuildTheSplicedTransducers() {
		// The one-argument (zero for dedupe/distinct) arity of a seq verb is its
		// transducer, a function over a reducing function built by a spliced worker
		assertThat(lowered("(map inc)")).contains("(RONTOLISP::%CLOJURE-XF-MAP");
		assertThat(lowered("(filter odd?)")).contains("(RONTOLISP::%CLOJURE-XF-FILTER").endsWith(" T)");
		assertThat(lowered("(remove odd?)")).contains("(RONTOLISP::%CLOJURE-XF-FILTER").endsWith(" NIL)");
		assertThat(lowered("(take 2)")).contains("(RONTOLISP::%CLOJURE-XF-TAKE 2)");
		assertThat(lowered("(take-nth 2)")).contains("(RONTOLISP::%CLOJURE-XF-TAKE-NTH 2)");
		assertThat(lowered("(dedupe)")).contains("(RONTOLISP::%CLOJURE-XF-DEDUPE)");
		assertThat(lowered("(distinct)")).contains("(RONTOLISP::%CLOJURE-XF-DISTINCT)");
		assertThat(lowered("(partition-all 2)")).contains("(RONTOLISP::%CLOJURE-XF-PARTITION-ALL 2)");
		assertThat(lowered("(partition-by odd?)")).contains("(RONTOLISP::%CLOJURE-XF-PARTITION-BY");
		assertThat(lowered("(keep-indexed vector)")).contains("(RONTOLISP::%CLOJURE-XF-INDEXED").endsWith(" T)");
		assertThat(lowered("(map-indexed vector)")).contains("(RONTOLISP::%CLOJURE-XF-INDEXED").endsWith(" NIL)");
		// the consumers and companions
		assertThat(lowered("(transduce (map inc) + [1])")).contains("(RONTOLISP::%CLOJURE-TRANSDUCE-3");
		assertThat(lowered("(transduce (map inc) + 0 [1])")).contains("(RONTOLISP::%CLOJURE-TRANSDUCE ");
		assertThat(lowered("(eduction (map inc) (filter odd?) [1])")).contains("(RONTOLISP::%CLOJURE-SEQUENCE-XF")
			.contains("(RONTOLISP::%CLOJURE-XF-COMP (LIST");
		assertThat(lowered("(sequence [1])")).contains("(RONTOLISP::%CLOJURE-SEQUENCE (VECTOR 1))");
		assertThat(lowered("(completing +)")).contains("(RONTOLISP::%CLOJURE-COMPLETING #'+ #'IDENTITY)");
		assertThat(lowered("(reduced? (reduced 1))"))
			.contains("(RONTOLISP::%CLOJURE-REDUCED-PRED (RONTOLISP::%CLOJURE-REDUCED 1))");
		assertThat(lowered("(into [] cat [[1]])")).contains("#'RONTOLISP::%CLOJURE-XF-CAT");
		// reduce goes through the lazy-aware, reduced-aware runtime; a value that may
		// hold a collection is wrapped over the dispatcher at the call site
		assertThat(lowered("(reduce + [1])")).contains("(RONTOLISP::%CLOJURE-REDUCE #'+ (VECTOR 1))");
		assertThat(lowered("(reduce + 0 [1])")).contains("(RONTOLISP::%CLOJURE-REDUCE-INIT #'+ 0 (VECTOR 1))");
		assertThat(lowered("(defn f [g] (reduce g [1]))"))
			.contains("(RONTOLISP::%CLOJURE-REDUCE (RONTOLISP::%CLOJURE-AS-FN |c%g|) (VECTOR 1))");
		// as values: the fixed-arity seq verbs widen to the transducer arity
		assertThat(lowered("(map filter [odd?])")).contains("&OPTIONAL").contains("%CLOJURE-XF-FILTER");
		assertThat(lowered("(map take-nth [2])")).contains("#'RONTOLISP::%CLOJURE-TAKE-NTH-V");
		assertThat(lowered("(map reduced [1])")).contains("#'RONTOLISP::%CLOJURE-REDUCED");
	}

	@Test
	void aProgramDefinitionOrLocalShadowsACoreNameInCallPosition() {
		// like the oracle (and like the value position): the program's own peek,
		// second or local pop is what a call reaches, not the core verb
		assertThat(lowered("(defn peek [x] x) (peek [1])")).contains("(|c%peek| (VECTOR 1))")
			.doesNotContain("%CLOJURE-PEEK");
		assertThat(lowered("(defn second [x] x) (second [1])")).contains("(|c%second| (VECTOR 1))")
			.doesNotContain("CADR");
		assertThat(lowered("(let [pop (fn [x] x)] (pop [1]))")).contains("(FUNCALL |c%pop| (VECTOR 1))");
		assertThat(lowered("(defn f [re-find] (re-find 1))")).doesNotContain("%CLOJURE-RE-FIND");
	}

	private static String loweredFrom(String source, String file) {
		List<LispVal> forms = Clojure.read(source, file);
		return forms.stream().map(LispVal::print).collect(Collectors.joining("\n"));
	}

	@Test
	void clojureTestReportsNameTheFormsFileAndLine() {
		// the oracle's (file:line): the last path segment of the file and the
		// is form's line, the deftest's line for an uncaught error; no file is
		// NO_SOURCE_FILE, like the oracle's eval
		String source = "(ns t (:require [clojure.test :refer :all]))\n(deftest a\n  (is (= 1 2))\n  (are [x] (pos? x) 1))";
		String out = loweredFrom(source, "dir/sub/x.clj");
		assertThat(out).contains("\"(x.clj:3)\"").contains("\"(x.clj:4)\"").contains("\"(x.clj:2)\"");
		assertThat(lowered(source)).contains("\"(NO_SOURCE_FILE:3)\"");
	}

	@Test
	void clojureTestLowersToTheRuntimeShapes() {
		String out = lowered(
				"(ns t (:require [clojure.test :as t])) (t/deftest a (t/is (= 1 2)) (t/is (and true false)) (t/is (thrown? Exception (/ 1 0))) (t/testing \"c\" (t/is true)))");
		// the test function, its body as a function of its own, the registration
		// under the namespace, and the runtime start ahead of everything
		assertThat(out).contains("RONTOLISP::%CLOJURE-TEST-INIT")
			.contains("(DEFUN |c%t/a%body| NIL")
			.contains("(DEFUN |c%t/a| NIL (RONTOLISP::%CLOJURE-TEST-VAR \"a\" #'|c%t/a%body|")
			.contains("(RONTOLISP::%CLOJURE-TEST-REGISTER \"t\" \"a\" #'|c%t/a|)");
		// = is a predicate call, and is an any-form assertion (a macro)
		assertThat(out).contains("RONTOLISP::%CLOJURE-TEST-PRED")
			.contains("RONTOLISP::%CLOJURE-TEST-ANY")
			.contains("RONTOLISP::%CLOJURE-TEST-THROWN")
			.contains("RONTOLISP::%CLOJURE-TEST-TESTING");
		// (:use clojure.test) refers every var, like the oracle's bare library symbol
		assertThat(lowered("(ns t (:use clojure.test)) (deftest a (is true)) (run-tests)"))
			.contains("RONTOLISP::%CLOJURE-TEST-RUN-TESTS (LIST \"t\") '(\"user\" \"t\")");
		// a test is a zero-argument function a later form may call
		assertThat(lowered("(ns t (:use clojure.test)) (b) (deftest b (is true))")).contains("(|c%t/b|)");
	}

	@Test
	void clojureTestRefusesWhatTheOracleRefuses() {
		assertThatThrownBy(() -> Clojure.read("(ns t (:use clojure.test)) (deftest a (are [x y] (= x y) 1 2 3))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("The number of args doesn't match are's argv.");
		assertThatThrownBy(() -> Clojure.read("(ns t (:use clojure.test)) (map is [1])", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Can't take value of a macro: #'clojure.test/is");
		assertThatThrownBy(() -> Clojure.read("(ns t (:use clojure.test)) (use-fixtures :each identity)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("use-fixtures is not supported yet");
		assertThatThrownBy(() -> Clojure.read("(ns t (:require [clojure.test :as t])) (t/no-such 1)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: clojure.test/no-such");
		assertThatThrownBy(() -> Clojure.read("(deftest a (is true))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: deftest");
		assertThatThrownBy(() -> Clojure.read("(ns t (:use no.such.lib))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Could not locate no/such/lib.clj");
	}

	private static String loweredWithFiles(String source, Map<String, String> files) {
		List<LispVal> forms = Clojure.read(source, null, ClojureMacroTime.create(), new MemoryClojureFiles(files));
		return forms.stream().map(LispVal::print).collect(Collectors.joining("\n"));
	}

	@Test
	void aNamespaceLoadsItsCljFromAnyRootAheadOfItsCljc() {
		// the oracle's RT.load (clj 1.12.6, 2026-10-08): the .clj under ANY root, then
		// the .cljc; a .cljc alone loads with its reader conditionals
		Map<String, String> files = Map.of("deps.edn", "{:paths [\"a\" \"b\"]}", "a/p/x.cljc",
				"(ns p.x) (defn from-cljc [] 1)", "b/p/x.clj", "(ns p.x) (defn from-clj [] 2)", "a/p/z.cljc",
				"(ns p.z) (defn f [] #?(:cljs 1 :clj 2))");
		assertThat(loweredWithFiles("(require 'p.x) (p.x/from-clj)", files)).contains("(|c%p.x/from-clj|)");
		assertThatThrownBy(() -> loweredWithFiles("(require 'p.x) (p.x/from-cljc)", files))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such var: p.x/from-cljc");
		assertThat(loweredWithFiles("(require 'p.z) (p.z/f)", files)).contains("(DEFUN |c%p.z/f| NIL 2)");
		assertThatThrownBy(() -> loweredWithFiles("(require 'p.q)", files)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Could not locate p/q.clj or p/q.cljc on the source path: ., a, b");
	}

	@Test
	void aRequiredNamespaceLowersAheadOfTheFormThatLoadsItOnce() {
		// the file from src (the default root without a deps.edn), its vars
		// qualified by its namespace, emitted once ahead of the requiring form
		String out = loweredWithFiles(
				"(ns m (:require [app.lib :as l])) (require 'app.lib) (println (l/f 1) (app.lib/f 2))",
				Map.of("src/app/lib.clj", "(ns app.lib) (defn f [x] (inc x))"));
		assertThat(out).containsOnlyOnce("(DEFUN |c%app.lib/f| (|c%x|) (+ |c%x| 1))")
			.contains("(|c%app.lib/f| 1)")
			.contains("(|c%app.lib/f| 2)");
		assertThat(out.indexOf("DEFUN |c%app.lib/f|")).isLessThan(out.indexOf("(|c%app.lib/f| 1)"));
		// a deps.edn's :paths name the roots, beside the entry's own
		assertThat(loweredWithFiles("(require 'app.lib) (app.lib/f 1)",
				Map.of("deps.edn", "{:paths [\"lib\"]}", "lib/app/lib.clj", "(ns app.lib) (defn f [x] x)")))
			.contains("(|c%app.lib/f| 1)");
	}

	@Test
	void eachNamespaceHasItsOwnVars() {
		// two namespaces define one name; user's vars keep the bare mangled name
		String out = lowered(
				"(defn f [] 0) (ns a) (defn f [] 1) (ns b (:require [a])) (defn f [] 2) (a/f) (f) (user/f)");
		assertThat(out).contains("(DEFUN |c%f| NIL 0)")
			.contains("(DEFUN |c%a/f| NIL 1)")
			.contains("(DEFUN |c%b/f| NIL 2)")
			.contains("(|c%a/f|)\n(|c%b/f|)\n(|c%f|)");
		// a name another namespace defines is no name here
		assertThatThrownBy(() -> Clojure.read("(ns a) (defn g [] 1) (ns b) (g)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: g");
	}

	@Test
	void requireRefersOnlyThroughReferOrUse() {
		// the oracle's load-lib: a bare :only under require refers nothing
		assertThatThrownBy(() -> Clojure.read("(require '[clojure.string :only [join]]) (join \",\" [\"a\"])", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: join");
		assertThat(lowered("(ns a) (defn f [] 1) (defn- p [] 2) (ns b (:use a)) (f)")).contains("(|c%a/f|)");
		assertThatThrownBy(() -> Clojure.read("(ns a) (defn- p [] 2) (ns b (:use a)) (p)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: p");
		assertThatThrownBy(() -> Clojure.read("(ns a) (defn- p [] 2) (ns b (:require [a :as x])) (x/p)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("var: #'a/p is not public");
		assertThatThrownBy(() -> Clojure.read("(ns a) (ns b (:require [a :as x])) (x/nope)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such var: x/nope");
	}

	@Test
	void syntaxQuoteQualifiesTheVarsItsNamespaceSees() {
		// like the oracle's read-time resolution: an own or referred var carries its
		// namespace (user's included); anything else qualifies too: a core
		// name the namespace sees as clojure.core/name, any other unresolved
		// spelling with the defining namespace, an alias head with its namespace,
		// a class head with its fully qualified name
		assertThat(loweredWithMacros("(ns s.a) (defn h [] 1) (defmacro m [] `(h ~'x nope let))")).contains("'|c%s.a/h|")
			.contains("'|c%s.a/nope|")
			.contains("'|c%clojure.core/let|");
		assertThat(loweredWithMacros("(defn h [] 1) (defmacro m [] `(h))")).contains("'|c%user/h|");
		assertThat(loweredWithMacros(
				"(ns s.b (:require [clojure.string :as s])) (defmacro m [] `(s/join s/nope System/nanoTime foo/bar import*))"))
			.contains("'|c%clojure.string/join|")
			.contains("'|c%clojure.string/nope|")
			.contains("'|c%java.lang.System/nanoTime|")
			.contains("'|c%foo/bar|")
			.contains("'|c%s.b/import*|");
		assertThat(loweredWithMacros("(ns s.c (:refer-clojure :exclude [map])) (defmacro m [] `(map filter))"))
			.contains("'|c%s.c/map|")
			.contains("'|c%clojure.core/filter|");
		// a class spelling is already fully qualified (measured on the
		// oracle: `java.io.StringWriter reads as written, `String as
		// java.lang.String) -- never with the defining namespace
		assertThat(loweredWithMacros("(ns s.d) (defmacro m [] `(java.io.StringWriter String))"))
			.contains("'|c%java.io.StringWriter|")
			.contains("'|c%java.lang.String|");
	}

	@Test
	void macroexpandCarriesTheCallSitesMacroScope() {
		// a bare head of a namespace other than user, and an alias-qualified one,
		// reach the table through the call site's scope; nothing else needs one
		assertThat(loweredWithMacros("(ns s.a) (defmacro m [] 1) (macroexpand-1 '(m))"))
			.contains("(C%MACROEXPAND-1 '(|c%m|) '((|c%m| . |c%s.a/m%macro|)))");
		assertThat(loweredWithMacros("(ns s.a) (defmacro m [] 1) (ns s.b (:require [s.a :as x])) (macroexpand '(x/m))"))
			.contains("(C%MACROEXPAND '(|c%x/m|) '((|c%x/m| . |c%s.a/m%macro|)))");
		assertThat(loweredWithMacros("(defmacro m [] 1) (macroexpand-1 '(m))"))
			.contains("(C%MACROEXPAND-1 '(|c%m|) NIL)");
	}

	@Test
	void aRequiredNamespaceRunsItsStatementsFromAnInitBehindAFlag() {
		// the defn stays a top-level defun; the print and the def run from the
		// namespace's init, which the require site calls behind the loaded flag
		String out = loweredWithFiles("(ns m (:require [app.lib :as l])) (println (l/f 1))",
				Map.of("src/app/lib.clj", "(ns app.lib) (println \"hi\") (def v 1) (defn f [x] (inc x))"));
		assertThat(out).contains("(DEFUN |c%app.lib/f| (|c%x|) (+ |c%x| 1))")
			.contains("(DEFVAR |c%app.lib%loaded| NIL)")
			.contains("(SETQ |c%app.lib%init-1| (LAMBDA NIL")
			.contains("(SETQ |c%app.lib/v| 1)")
			.contains("(SETQ |c%app.lib%init| (LAMBDA NIL (FUNCALL |c%app.lib%init-1|)))")
			.contains("(UNLESS |c%app.lib%loaded| " + loading("app.lib") + " (SETQ |c%app.lib%loaded| T))");
		// definitions ahead of the requiring form, statements inside the init
		assertThat(out.indexOf("(DEFUN |c%app.lib/f|")).isLessThan(out.indexOf("|c%app.lib%init-1| (LAMBDA"));
		assertThat(out.indexOf("\"hi\"")).isGreaterThan(out.indexOf("|c%app.lib%init-1| (LAMBDA"));
		assertThat(out.indexOf("(UNLESS |c%app.lib%loaded|")).isGreaterThan(out.indexOf("|c%app.lib%init| (LAMBDA"));
	}

	@Test
	void aReloadCallRunsTheInitUnconditionally() {
		String files = "(ns app.lib) (println \"hi\") (def v 1)";
		Map<String, String> fs = Map.of("src/app/lib.clj", files);
		// a second require is another guarded call, but the flag is still one defvar
		String both = loweredWithFiles("(require 'app.lib) (require 'app.lib)", fs);
		String guarded = "(UNLESS |c%app.lib%loaded| " + loading("app.lib") + " (SETQ |c%app.lib%loaded| T))";
		assertThat(both).containsOnlyOnce("(DEFVAR |c%app.lib%loaded| NIL)");
		assertThat(both.indexOf(guarded)).isLessThan(both.lastIndexOf(guarded));
		// :reload calls the init outright, and still marks it loaded
		assertThat(loweredWithFiles("(require '[app.lib] :reload)", fs))
			.contains("(PROGN " + loading("app.lib") + " (SETQ |c%app.lib%loaded| T))");
		// a library namespace has no init, so even :reload is nothing at run time
		assertThat(loweredWithFiles("(require '[clojure.string :as s] :reload) (s/join \",\" [\"a\"])", Map.of()))
			.doesNotContain("FUNCALL");
	}

	@Test
	void aReloadAllCallRunsDependenciesFirst() {
		Map<String, String> fs = Map.of("src/app/b.clj", "(ns app.b) (println \"b\") (def bv 1)", "src/app/a.clj",
				"(ns app.a (:require [app.b :as b])) (println \"a\") (def av b/bv)");
		String out = loweredWithFiles("(require '[app.a] :reload-all)", fs);
		assertThat(out).contains(loading("app.b") + " (SETQ |c%app.b%loaded| T) " + loading("app.a"));
		assertThat(out.indexOf("(FUNCALL |c%app.b%init|)")).isLessThan(out.indexOf("(FUNCALL |c%app.a%init|)"));
	}

	/**
	 * The run of a namespace's init where its load binds *ns*, *file* and *source-path*:
	 * {@code app.lib} reads {@code app/lib.clj}.
	 */
	private static String loading(String ns) {
		String file = ns.replace('.', '/') + ".clj";
		return "(LET ((RONTOLISP::%CLOJURE-NS RONTOLISP::%CLOJURE-NS) (RONTOLISP::%CLOJURE-FILE \"" + file
				+ "\") (RONTOLISP::%CLOJURE-SOURCE-PATH \"" + file.substring(file.lastIndexOf('/') + 1)
				+ "\")) (FUNCALL |c%" + ns + "%init|))";
	}

	@Test
	void aDynamicDefInANamespaceHoistsItsDeclaim() {
		// the setq runs in the init; the declaim and the binding-depth counter
		// stay top-level at the head, where both collectors read them
		String out = loweredWithFiles("(require 'app.dyn)",
				Map.of("src/app/dyn.clj", "(ns app.dyn) (def ^:dynamic x 1) (defonce ^:dynamic y 2)"));
		assertThat(out).contains("(DECLAIM (SPECIAL |c%app.dyn/x| |c%app.dyn/x%bound-depth|))")
			.contains("(DEFPARAMETER |c%app.dyn/x%bound-depth| 0)")
			.contains("(DECLAIM (SPECIAL |c%app.dyn/y| |c%app.dyn/y%bound-depth|))")
			.contains("(DEFPARAMETER |c%app.dyn/y%bound-depth| 0)")
			.contains("(SETQ |c%app.dyn/x| 1)")
			// A reload keeps the defonce root through a runtime boundp probe
			// of the var itself -- every store feeds the eval mirror the probe
			// reads, so no set flag beside the var is needed any more
			.contains("(UNLESS (BOUNDP '|c%app.dyn/y|) (SETQ |c%app.dyn/y| 2))")
			.doesNotContain("%set");
		assertThat(out).doesNotContain("(DEFPARAMETER |c%app.dyn/x| 1)");
	}

	@Test
	void aDefonceInANamespaceKeepsItsRootThroughBoundp() {
		// the plain (non-dynamic) init shape rides the same probe: the init's own
		// assignment poisons the name, so the compile-time boundp fold leaves the
		// probe to the run time, where the mirror answers it
		String out = loweredWithFiles("(require 'app.once)", Map.of("src/app/once.clj", "(ns app.once) (defonce v 1)"));
		assertThat(out).contains("(UNLESS (BOUNDP '|c%app.once/v|) (SETQ |c%app.once/v| 1))").doesNotContain("%set");
	}

}
