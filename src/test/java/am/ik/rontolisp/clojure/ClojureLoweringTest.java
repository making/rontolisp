package am.ik.rontolisp.clojure;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.ClojureMacroTime;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;
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

	private static String wasmLowered(String source) {
		List<LispVal> forms = Clojure.read(source, null, null, ClojureFiles.NONE, false);
		return forms.stream().map(LispVal::print).collect(Collectors.joining("\n"));
	}

	private static final String FALSE_BINDING = "(SETQ RONTOLISP::%CLOJURE-FALSE '|false|)\n";

	/**
	 * The definition a program naming {@code +}, {@code -}, {@code *}, {@code inc} or
	 * {@code dec} carries for the flag their worker reads (g28): the plain false object,
	 * so the flag down they are the plain verbs.
	 */
	private static final String UNCHECKED_MATH_DEFVAR = "(DEFVAR RONTOLISP::%CLOJURE-UNCHECKED-MATH RONTOLISP::%CLOJURE-FALSE)\n";

	/**
	 * The declaration a single-arity function's body opens with: its wrong-count report
	 * in the oracle's words, naming it.
	 */
	static String arity(String name) {
		return "(DECLARE (%ARITY-REPORT \"Wrong number of args (\" \") passed to: " + name + "\"))";
	}

	@Test
	void everyIdentifierManglesBehindThePrefix() {
		assertThat(lowered("(def x 1) x")).isEqualTo(FALSE_BINDING
				+ "(RONTOLISP::%CLOJURE-VAR-STORE (LAMBDA NIL (IF (BOUNDP '|c%x|) |c%x| (RONTOLISP::%CLOJURE-UNBOUND \"user/x\"))) (LAMBDA (|__clojure_new_root|) (SETQ |c%x| |__clojure_new_root|)) 1 \"user/x\")\n|c%x|");
		assertThat(ClojureLowering.mangle("a:b")).isEqualTo("c%a%cb");
		assertThat(ClojureLowering.mangle("a%b")).isEqualTo("c%a%%b");
	}

	@Test
	void aLocalNamedLikeASpecialBindsASymbolOfItsOwn() {
		// a local spelled like a user ^:dynamic var -- even one defined below it --
		// or like a stream alias binds c%name%local, so it stays lexical; the var
		// keeps its symbol, and a local named like nothing special is untouched
		assertThat(lowered("(defn f [*x*] *x*) (def ^:dynamic *x* 1)"))
			.contains("(DEFUN |c%f| (|c%*x*%local|) " + arity("user/f") + " |c%*x*%local|)");
		assertThat(lowered("(def ^:dynamic *x* 1) (let [*x* 2] (binding [*x* 3] *x*))"))
			.contains("(LET* ((|c%*x*%local| 2)) (LET* ((|c%*x*| 3)");
		assertThat(lowered("(let [*out* 5] *out*)")).contains("(LET* ((|c%*out*%local| 5)) |c%*out*%local|)");
		assertThat(lowered("(defn f [*x*] *x*)")).contains("(DEFUN |c%f| (|c%*x*|) " + arity("user/f") + " |c%*x*|)");
		assertThat(lowered("(ns other) (def ^:dynamic *x* 1) (defn f [*x*] *x*)"))
			.contains("(|c%*x*|) " + arity("other/f") + " |c%*x*|)");
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
	void defReadsItsNameAndALoneStringAsTheOracleDoes() {
		assertThat(lowered("(def x \"hello\") x")).isEqualTo(FALSE_BINDING
				+ "(RONTOLISP::%CLOJURE-VAR-STORE (LAMBDA NIL (IF (BOUNDP '|c%x|) |c%x| (RONTOLISP::%CLOJURE-UNBOUND \"user/x\"))) (LAMBDA (|__clojure_new_root|) (SETQ |c%x| |__clojure_new_root|)) \"hello\" \"user/x\")\n|c%x|");
		assertThat(lowered("(def x \"doc\" 1) x")).isEqualTo(FALSE_BINDING
				+ "(RONTOLISP::%CLOJURE-VAR-STORE (LAMBDA NIL (IF (BOUNDP '|c%x|) |c%x| (RONTOLISP::%CLOJURE-UNBOUND \"user/x\"))) (LAMBDA (|__clojure_new_root|) (SETQ |c%x| |__clojure_new_root|)) 1 \"user/x\")\n|c%x|");
		// a name qualified with the current namespace is its bare var; another
		// namespace's is refused in the oracle's words (clj 1.12.6)
		assertThat(lowered("(def user/x \"doc\" 1) x")).isEqualTo(lowered("(def x \"doc\" 1) x"));
		assertThatThrownBy(() -> Clojure.read("(def clojure.core/inc 1)", null))
			.hasMessageContaining("Can't create defs outside of current ns");
		assertThatThrownBy(() -> Clojure.read("(defonce clojure.core/nope 1)", null))
			.hasMessageContaining("Can't refer to qualified var that doesn't exist");
	}

	@Test
	void defnIsADefunCalledDirectly() {
		assertThat(lowered("(defn f [x] x) (f 1)"))
			.isEqualTo(FALSE_BINDING + "(DEFUN |c%f| (|c%x|) " + arity("user/f") + " |c%x|)\n(|c%f| 1)");
		assertThat(lowered("(defn f [x] x) f"))
			.isEqualTo(FALSE_BINDING + "(DEFUN |c%f| (|c%x|) " + arity("user/f") + " |c%x|)\n#'|c%f|");
	}

	@Test
	void defAfterDefnCapturesTheFunctionCell() {
		// (def p (memoize p)) after a (defn p ...) captures the function cell:
		// the value lowers against the OLD FUNCTION binding (#'|c%p|), not the
		// still-unbound value cell, and only then does the name become a VARIABLE.
		assertThat(lowered("(defn p [x] x) (def p (memoize p))")).contains("#'|c%p|");
		assertThat(lowered("(defn p [x] x) (def p p)")).contains(
				"(RONTOLISP::%CLOJURE-VAR-STORE (LAMBDA NIL (IF (BOUNDP '|c%p|) |c%p| (RONTOLISP::%CLOJURE-UNBOUND \"user/p\"))) (LAMBDA (|__clojure_new_root|) (SETQ |c%p| |__clojure_new_root|)) #'|c%p| \"user/p\")");
		assertThat(lowered("(defn p [x] x) (defonce p (memoize p))")).contains("#'|c%p|");
	}

	@Test
	void aRedefinedDefnGetsAFreshNamePerDefinition() {
		// A later defn of the same name wins everywhere on the compiled
		// backends. Each definition lowers to its own defun (the first keeps the
		// bare name, later ones take a %defN suffix no identifier spells), the
		// call sites below each definition call the newest, and a value position
		// captures the definition current at that point.
		assertThat(lowered("(defn f [] 1) (def g f) (defn f [] 2) (g) (f)"))
			.contains("(DEFUN |c%f| NIL " + arity("user/f") + " 1)")
			.contains(
					"(RONTOLISP::%CLOJURE-VAR-STORE (LAMBDA NIL (IF (BOUNDP '|c%g|) |c%g| (RONTOLISP::%CLOJURE-UNBOUND \"user/g\"))) (LAMBDA (|__clojure_new_root|) (SETQ |c%g| |__clojure_new_root|)) #'|c%f| \"user/g\")")
			.contains("(RONTOLISP::%CLOJURE-VAR-DEFN-NOTIFY \"user/f\" #'|c%f%def2| #'|c%f|)")
			.contains("(DEFUN |c%f%def2| NIL " + arity("user/f") + " 2)")
			.contains("(FUNCALL |c%g|)")
			.contains("(|c%f%def2|)");
		// a top-level call between the definitions calls the older one
		assertThat(lowered("(defn f [] 1) (f) (defn f [] 2) (f)")).contains("(|c%f|)").contains("(|c%f%def2|)");
		// a redefined dynamic defn installs its fresh function cell, so the
		// value cell always holds the newest
		assertThat(lowered("(defn ^:dynamic d [] 1) (defn ^:dynamic d [] 2)"))
			.contains("(DEFPARAMETER |c%d| (RONTOLISP::%CLOJURE-VAR-VALIDATED #'|c%d| \"user/d\"))")
			.contains("(RONTOLISP::%CLOJURE-VAR-NOTIFY-STORED \"user/d\" |c%d|)")
			.contains("(DEFUN |c%d%def2| NIL " + arity("user/d") + " 2)")
			.contains("(DEFPARAMETER |c%d| (RONTOLISP::%CLOJURE-VAR-VALIDATED #'|c%d%def2| \"user/d\"))");
		// namespaces version their own names independently
		assertThat(lowered("(ns nsa) (defn f [] 1) (ns nsb) (defn f [] 2) (nsa/f)"))
			.contains("(DEFUN |c%nsa/f| NIL " + arity("nsa/f") + " 1)")
			.contains("(DEFUN |c%nsb/f| NIL " + arity("nsb/f") + " 2)")
			.contains("(|c%nsa/f|)");
	}

	@Test
	void headPositionCallsToVariablesReachTheValueCell() {
		// a parameter may hold a collection, so its call goes through the prelude
		// dispatcher (which funcalls real functions); a let/def binding of a real
		// function stays a direct funcall; a declared-never-defined name calls its
		// unbound root, which signals like the oracle's
		assertThat(lowered("(defn call-it [f x] (f x))")).contains("(DEFUN |c%call-it| (|c%f| |c%x|) "
				+ arity("user/call-it") + " (RONTOLISP::%CLOJURE-CALL |c%f| (LIST |c%x|)))");
		assertThat(lowered("(let [g inc] (g 1))")).contains("(FUNCALL |c%g| 1)");
		assertThat(lowered("(let [s #{:h}] (s :h))")).contains("RONTOLISP::%CLOJURE-CALL");
		assertThat(lowered("(def v (fn [x] x)) (v 1)")).contains("(FUNCALL |c%v| 1)");
		assertThat(lowered("(declare u) (u 1)")).contains("(SETQ |c%u| (RONTOLISP::%CLOJURE-UNBOUND \"user/u\"))",
				"(RONTOLISP::%CLOJURE-CALL |c%u| (LIST 1))");
		assertThat(lowered("(declare u) (defn u [x] x) (u 1)")).contains("(|c%u| 1)")
			.contains(
					"(RONTOLISP::%CLOJURE-VAR-DEFN-NOTIFY \"user/u\" #'|c%u| (RONTOLISP::%CLOJURE-UNBOUND \"user/u\"))");
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
			.isEqualTo(FALSE_BINDING + "(LABELS ((|c%f| (|c%x|) " + arity("user/f") + " |c%x|)) (|c%f| 1))");
		assertThat(lowered("(letfn [] 1)")).isEqualTo(FALSE_BINDING + "1");
		// mutual recursion: siblings call each other directly
		assertThat(lowered("(letfn [(e [n] (o n)) (o [n] n)] (e 1))")).contains("(|c%o| |c%n|)").contains("(|c%e| 1)");
		// an entry's name is a function value, like a named fn's
		assertThat(lowered("(letfn [(f [x] x)] f)")).contains("#'|c%f|");
		// an inner letfn shadows an outer variable: the call stays direct
		assertThat(lowered("(let [f 99] (letfn [(f [x] x)] (f 1)))"))
			.contains("(LABELS ((|c%f| (|c%x|) " + arity("user/f") + " |c%x|)) (|c%f| 1))");
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
		// a macro expansion in tail position keeps it: a recur the expansion holds
		// recurs (data.json's codepoint-case around a loop's recur), while one in an
		// argument slot of the expansion still refuses, like the oracle's
		assertThat(loweredWithMacros("(defmacro mwhen [c x] `(if ~c :d (recur ~x))) (loop [i 0] (mwhen (zero? i) i))"))
			.contains("LABELS");
		assertThatThrownBy(() -> Clojure.read("(defmacro mno [x] `(+ ~x 1)) (loop [i 0] (mno (recur i)))", null,
				ClojureMacroTime.create()))
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
			.contains("(DEFUN |c%vr| (|c%a| &REST |c%r|) " + arity("user/vr") + " (|c%vr%*| |c%a| |c%r|))");
		// an unused variadic keeps its single shape
		assertThat(lowered("(defn vu [a & r] a)"))
			.isEqualTo(FALSE_BINDING + "(DEFUN |c%vu| (|c%a| &REST |c%r|) " + arity("user/vu") + " |c%a|)");
		// a named fn splits into a worker plus its &rest head inside labels
		assertThat(lowered("(fn f [a & r] (recur a r))")).contains("(|c%f%*| (|c%a| |c%r|) (|c%f%*| |c%a| |c%r|))")
			.contains("(|c%f| (|c%a| &REST |c%r|) " + arity("user/f") + " (|c%f%*| |c%a| |c%r|))");
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
	void localsAndProgramDefinitionsShadowLoweringRowsButNotSpecials() {
		// a parameter named fn is called, never defined through (malli's
		// -proxy-schema destructures fn); the same for when and ->
		assertThat(lowered("((fn [fn] (fn 1 2)) (fn [a] a))")).contains("%CLOJURE-CALL");
		assertThat(lowered("(let [wh 1] (wh 2))")).contains("%CLOJURE-CALL");
		// the special forms never shadow: if stays the special under a local if
		assertThat(lowered("(let [if 1] (if 1 2 3))")).contains("(IF ").contains("|c%if|");
		assertThat(lowered("(let [do 5] (do do))")).contains("|c%do|");
		// a defn of a core name replaces the core verb from there on, like the
		// oracle's form-by-form compile (malli's defn deref)
		assertThat(lowered("(defn shd-deref ([x] [x])) (shd-deref 1)")).contains("(|c%shd-deref| 1)");
		assertThat(lowered("(defn deref ([x] [x])) (deref 1)")).contains("(|c%deref| 1)")
			.doesNotContain("%CLOJURE-DEREF");
		// above the definition the core meaning stays: the pre-scan leaves a
		// shadowing definition invisible until lowered
		assertThat(lowered("(deref 1) (defn deref [x] [x])")).contains("%CLOJURE-DEREF");
		// a non-core definition keeps the pre-scan's forward visibility
		assertThat(lowered("(defn shd-f [] (shd-g)) (defn shd-g [] 1)")).contains("(|c%shd-g|)");
	}

	@Test
	void recurThroughThreadingFormsKeepsTailPosition() {
		// the threading rewrites lower in the incoming tail position, so a recur
		// in a step's tail is its target's, like the oracle's macroexpansions
		assertThat(lowered("((fn [n] (cond-> n true (do (recur (inc n))))) 1)")).contains("LABELS");
		assertThat(lowered("((fn [n] (-> n (do (recur (inc n))))) 1)")).contains("LABELS");
		assertThat(lowered("((fn [n] (as-> n v (if (zero? v) :z (recur (dec v))))) 1)")).contains("LABELS");
		// outside tail position the refusal stays, like the oracle's
		assertThatThrownBy(() -> Clojure.read("(defn f [x] (list (-> x (recur x))))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Can only recur from tail position");
		assertThatThrownBy(() -> Clojure.read("(defn f [x] [(cond-> x true (do (recur x)))])", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Can only recur from tail position");
	}

	@Test
	void macroArgumentsKeepTheirReaderMetadata() {
		// argument datums travel to the expander with their reader metadata, so
		// a ^:volatile-mutable deftype field below a selecting macro keeps its
		// flag and set! compiles (dynaload's if-bb, which malli loads through)
		assertThat(loweredWithMacros(
				"(defmacro mm-pick [then else] (if (System/getProperty \"no.such.property\") then else))"
						+ " (mm-pick :bb (deftype MmT [f ^:volatile-mutable c]"
						+ " clojure.lang.IDeref (deref [_] (set! c 1))))"))
			.contains("SETF");
		// a quoted shape decodes back with it, so meta reads through the call
		assertThat(loweredWithMacros("(defmacro mm-id [x] x) (println (meta (mm-id ^:foo [1 2])))"))
			.contains("%CLOJURE-PUT-META");
	}

	@Test
	void readerMetadataAttachesToFnReifyAndLiterals() {
		// a fn or reify form takes the metadata on the value it builds (malli's
		// ^{:type ::schema} (reify ...)), like a vector, map or set literal
		assertThat(lowered("^:foo (fn [x] x)")).contains("%CLOJURE-PUT-META");
		assertThat(lowered("^{:type :rm-t} (reify Object (toString [_] \"r\"))")).contains("%CLOJURE-PUT-META");
		// anywhere else it parses and drops: a symbol, a call, a quoted shape
		assertThat(lowered("(let [x 1] ^:foo x)")).doesNotContain("PUT-META");
		assertThat(lowered("^:foo (inc 1)")).doesNotContain("PUT-META");
	}

	@Test
	void booleanPredicatesAnswerTheSameLambdasAsValues() {
		// boolean?, true? and false? lower inline in calls and answer the same
		// lambdas as values (what a function position takes), so a map holding
		// one calls it (malli's {:pred boolean?})
		assertThat(lowered("(:pred {:pred boolean?})")).contains("LAMBDA").contains("(EQ ");
		assertThat(lowered("(:pred {:pred true?})")).contains("LAMBDA").contains("(EQ ");
		assertThat(lowered("(:pred {:pred false?})")).contains("LAMBDA").contains("(EQ ");
	}

	@Test
	void printMethodOfATypeKeywordStoresUnderTheKeyword() {
		// a :type keyword dispatch stores under the keyword itself, where the
		// printer's :type-first lookup finds it (malli's ::into-schema); a host
		// class no core value belongs to but an instant stores under its binary
		// name, which the printer's host walk reads (tools.reader's Date method);
		// any other dispatch value stays refused by name
		assertThat(lowered("(defmethod print-method :kw [x w] x)"))
			.contains("(RONTOLISP::%CLOJURE-PRINT-METHOD-ROW (LIST :C%KEYWORD \"kw\")");
		assertThat(lowered("(defmethod print-method java.util.Date [x w] x)"))
			.contains("(RONTOLISP::%CLOJURE-PRINT-METHOD-ROW \"java.util.Date\"");
		for (String refused : List.of("Object", "String", "java.util.Map")) {
			assertThatThrownBy(() -> Clojure.read("(defmethod print-method " + refused + " [x w] x)", null))
				.isInstanceOf(LispReadException.class)
				.hasMessageContaining("print-method of |" + refused + "| is not supported yet");
		}
	}

	@Test
	void fnAndAnonFnAreLambdas() {
		assertThat(lowered("((fn [a b] (+ a b)) 1 2)")).isEqualTo(FALSE_BINDING + UNCHECKED_MATH_DEFVAR
				+ "(FUNCALL (LAMBDA (|c%a| |c%b|) " + arity("user/fn") + " (RONTOLISP::%CLOJURE-+ |c%a| |c%b|)) 1 2)");
		assertThat(lowered("(map #(* % %) '(1 2))")).contains("%CLOJURE-MAP")
			.contains("(LAMBDA (|c%p1__1#|) " + arity("user/fn") + " (RONTOLISP::%CLOJURE-* |c%p1__1#| |c%p1__1#|))");
		assertThatThrownBy(() -> Clojure.read("%", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("%");
	}

	@Test
	void coreCallsLowerToTheirCommonLispNames() {
		assertThat(lowered("(map + '(1 2))")).contains("%CLOJURE-MAP").contains("#'RONTOLISP::%CLOJURE-BD-+");
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
	void aLiteralOfConstantKeysEqualOnceEvaluatedIsRefusedWhenLowered() {
		// the oracle's compile-time refusal: every key a constant, two of them =
		assertThatThrownBy(() -> lowered("{[1] :a '(1) :b}")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Duplicate constant keys in map");
		assertThatThrownBy(() -> lowered("{1 :a '1 :b :c (str 1)}")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Duplicate constant keys in map");
		// constants stay unchecked at run time; a computed key checks there
		assertThat(lowered("{[1] :a '(2) :b :c 1}")).doesNotContain("%CLOJURE-MAP-LITERAL");
		assertThat(lowered("#{[1] '(1)}")).doesNotContain("%CLOJURE-SET-LITERAL");
		assertThat(lowered("(defn f [x] {x 1 :a 2})")).contains("(RONTOLISP::%CLOJURE-MAP-LITERAL");
		assertThat(lowered("(defn f [x] #{x 1})")).contains("(RONTOLISP::%CLOJURE-SET-LITERAL");
		// one entry cannot repeat; a call keeps the last value
		assertThat(lowered("(defn f [x] [{x 1} #{x} (hash-map x 1 x 2)])")).doesNotContain("%CLOJURE-MAP-LITERAL")
			.doesNotContain("%CLOJURE-SET-LITERAL");
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
		// an end-less range is the infinite lazy integers from zero, on every
		// backend, as a value too
		assertThat(lowered("(range)")).contains("(RONTOLISP::%CLOJURE-RANGE)");
		assertThat(lowered("((fn [r] (r)) range)")).contains("RONTOLISP::%CLOJURE-RANGE");
		assertThatThrownBy(() -> Clojure.read("(range 1 2 3 4)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("range takes an end, or a start, an end and an optional step");
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
				+ "(RONTOLISP::%CLOJURE-VAR-STORE (LAMBDA NIL (IF (BOUNDP '|c%x|) |c%x| (RONTOLISP::%CLOJURE-UNBOUND \"user/x\"))) (LAMBDA (|__clojure_new_root|) (SETQ |c%x| |__clojure_new_root|)) RONTOLISP::%CLOJURE-FALSE \"user/x\")\n"
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
	void transientVerbsLowerToOneWorkerCallAfterTheOraclesArityCheck() {
		assertThat(lowered("(transient [1])")).isEqualTo(FALSE_BINDING + "(RONTOLISP::%CLOJURE-TRANSIENT (VECTOR 1))");
		assertThat(lowered("(persistent! [1])"))
			.isEqualTo(FALSE_BINDING + "(RONTOLISP::%CLOJURE-PERSISTENT (VECTOR 1))");
		assertThat(lowered("(assoc! [1] 0 2 1 3)"))
			.isEqualTo(FALSE_BINDING + "(RONTOLISP::%CLOJURE-TRANSIENT-ASSOC (VECTOR 1) (LIST 0 2 1 3))");
		assertThat(lowered("(dissoc! [1] 0 1)"))
			.isEqualTo(FALSE_BINDING + "(RONTOLISP::%CLOJURE-TRANSIENT-DISSOC (VECTOR 1) (LIST 0 1))");
		assertThat(lowered("(pop! [1])")).isEqualTo(FALSE_BINDING + "(RONTOLISP::%CLOJURE-TRANSIENT-POP (VECTOR 1))");
		// the oracle's (conj!) is (transient []), (conj! coll) and (disj! set) the
		// argument itself
		assertThat(lowered("(conj!)")).isEqualTo(FALSE_BINDING + "(RONTOLISP::%CLOJURE-TRANSIENT (VECTOR))");
		assertThat(lowered("(conj! [1])")).isEqualTo(FALSE_BINDING + "(VECTOR 1)");
		assertThat(lowered("(disj! [1])")).isEqualTo(FALSE_BINDING + "(VECTOR 1)");
		assertThat(lowered("(conj! [1] 2)"))
			.isEqualTo(FALSE_BINDING + "(RONTOLISP::%CLOJURE-TRANSIENT-CONJ (VECTOR 1) 2)");
		assertThat(lowered("(def f conj!)")).contains("RONTOLISP::%CLOJURE-TRANSIENT-CONJ-V");
		List<List<String>> refused = List.of(List.of("(conj! [] 1 2)", "(3) passed to: clojure.core/conj!"),
				List.of("(assoc! [] 0)", "(2) passed to: clojure.core/assoc!"),
				List.of("(dissoc! [])", "(1) passed to: clojure.core/dissoc!"),
				List.of("(disj!)", "(0) passed to: clojure.core/disj!"),
				List.of("(pop! [] 1)", "(2) passed to: clojure.core/pop!"),
				List.of("(persistent!)", "(0) passed to: clojure.core/persistent!"),
				List.of("(transient [] [])", "(2) passed to: clojure.core/transient"));
		for (List<String> one : refused) {
			assertThatThrownBy(() -> Clojure.read(one.get(0), null)).as(one.get(0))
				.isInstanceOf(LispReadException.class)
				.hasMessageContaining("Wrong number of args " + one.get(1));
		}
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
		assertThat(lowered("(ns foo) (def x 1) x")).isEqualTo(FALSE_BINDING + nsSwitch
				+ "(RONTOLISP::%CLOJURE-VAR-STORE (LAMBDA NIL (IF (BOUNDP '|c%foo/x|) |c%foo/x| (RONTOLISP::%CLOJURE-UNBOUND \"foo/x\"))) (LAMBDA (|__clojure_new_root|) (SETQ |c%foo/x| |__clojure_new_root|)) 1 \"foo/x\")\n|c%foo/x|");
		assertThat(lowered("(ns foo (:require [clojure.string :as s])) (def x 1) x")).isEqualTo(FALSE_BINDING + nsSwitch
				+ "(RONTOLISP::%CLOJURE-VAR-STORE (LAMBDA NIL (IF (BOUNDP '|c%foo/x|) |c%foo/x| (RONTOLISP::%CLOJURE-UNBOUND \"foo/x\"))) (LAMBDA (|__clojure_new_root|) (SETQ |c%foo/x| |__clojure_new_root|)) 1 \"foo/x\")\n|c%foo/x|");
		assertThat(lowered("(def x 1) x")).isEqualTo(FALSE_BINDING
				+ "(RONTOLISP::%CLOJURE-VAR-STORE (LAMBDA NIL (IF (BOUNDP '|c%x|) |c%x| (RONTOLISP::%CLOJURE-UNBOUND \"user/x\"))) (LAMBDA (|__clojure_new_root|) (SETQ |c%x| |__clojure_new_root|)) 1 \"user/x\")\n|c%x|");
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
		assertThat(lowered("(quot 7 2)")).isEqualTo(FALSE_BINDING + "(RONTOLISP::%CLOJURE-BD-QUOT 7 2)");
		assertThat(lowered("quot")).contains("(LAMBDA").contains("RONTOLISP::%CLOJURE-BD-QUOT");
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
		assertThat(lowered("(map inc '(1 2))")).contains("%CLOJURE-MAP")
			.contains("(LAMBDA")
			.contains("(RONTOLISP::%CLOJURE-BD-+");
		assertThat(lowered("(map dec [1 2])")).contains("%CLOJURE-MAP").contains("(RONTOLISP::%CLOJURE-BD--");
		assertThat(lowered("(map str [1 2])")).contains("%CLOJURE-MAP").contains("CONCATENATE");
		assertThat(lowered("(map count [[1]])")).contains("%CLOJURE-MAP").contains("LENGTH");
		assertThat(lowered("(filter first [[1] []])")).contains("%CLOJURE-FILTER").contains("(CAR");
		assertThat(lowered("inc")).contains("(LAMBDA").contains("(RONTOLISP::%CLOJURE-BD-+");
		assertThat(lowered("(apply + 1 '(2 3))")).contains("APPLY").contains("#'RONTOLISP::%CLOJURE-BD-+");
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
		// the oracle's seq-binding-form: an :as name may follow the rest, and ends the
		// pattern
		assertThat(lowered("(let [[a & r :as all] [1 2]] all)")).contains("(RONTOLISP::%CLOJURE-DROP 1 ");
		assertThatThrownBy(() -> Clojure.read("(let [[a & r b] [1 2]] a)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("a vector pattern & takes a single rest pattern after it");
		assertThatThrownBy(() -> Clojure.read("(let [[a :as all b] [1 2]] a)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("a vector pattern :as and its name end the pattern");
		assertThatThrownBy(() -> Clojure.read("(let [[a & r :as] [1 2]] a)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("a vector pattern :as needs a plain name after it");
		assertThatThrownBy(() -> Clojure.read("(let [{:keys a} {:a 1}] a)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("a map pattern :keys takes a vector of plain names");
		assertThat(lowered("(let [{:a/keys [b]} {:a/b 1}] b)")).contains("GETHASH");
		assertThat(lowered("(loop [{::keys [b]} {}] b)")).contains("LABELS");
		// the oracle's spec refuses a qualified or keyword entry and :ns/strs
		assertThatThrownBy(() -> Clojure.read("(let [{:a/keys [b/c]} {}] c)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("a map pattern :a/keys takes a vector of plain names");
		assertThatThrownBy(() -> Clojure.read("(let [{:a/keys [:c]} {}] c)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("a map pattern :a/keys takes a vector of plain names");
		assertThatThrownBy(() -> Clojure.read("(let [{:syms [:c]} {}] c)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("a map pattern :syms takes a vector of plain names");
		assertThatThrownBy(() -> Clojure.read("(let [{:a/strs [c]} {}] c)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("a map pattern :a/strs");
	}

	@Test
	void threadingIsADatumRewriteAroundOneTemporary() {
		assertThat(lowered("(-> 5 inc)"))
			.isEqualTo(FALSE_BINDING + UNCHECKED_MATH_DEFVAR + "(RONTOLISP::%CLOJURE-+ 5 1)");
		assertThat(lowered("(->> 5 (conj [1]))")).contains("COERCE").contains("(VECTOR 1)");
		assertThat(lowered("(-> {:a 1} :a)")).contains("GETHASH");
		assertThat(lowered("(as-> 5 x (inc x))")).contains("LET*");
		assertThat(lowered("(doto 5 (inc))")).contains("(LET");
		assertThat(lowered("(cond-> 5 (odd? 5) inc)")).contains("(IF");
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
			.contains("(RONTOLISP::%CLOJURE-FN-ARITY-ERROR |__clojure_1| \"user/mar-f\")");
		assertThat(lowered("(defn mar-g ([x & xs] xs))")).contains("NTHCDR");
		assertThatThrownBy(() -> Clojure.read("(defn bad ([x] x) ([y] y))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("two clauses for arity 1");
		assertThatThrownBy(() -> Clojure.read("(defn bad ([x & a] x) ([y & b] y))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("at most one variadic clause");
		// in a body every arity's defun and the dispatch defun are nested ones
		assertThat(lowered("(let [x 1] (defn nested-mar ([y] (+ x y)) ([z w] z)))")).contains("(PROGN (DEFUN")
			.contains("|c%nested-mar%1|")
			.contains("|c%nested-mar%2|");
	}

	@Test
	void namedAndMultiArityFn() {
		assertThat(lowered("((fn myfn [x] x) 1)")).contains("LABELS").contains("#'|c%myfn|");
		assertThat(lowered("((fn ([x] x) ([x y] y)) 1)")).contains("LENGTH").contains("(NTH");
		assertThatThrownBy(() -> Clojure.read("(fn)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("fn needs a parameter vector and a body");
	}

	@Test
	void aReturnTypeHintOnAParameterVectorIsDropped() {
		assertThat(lowered("(defn f ^long [x] (inc x))")).isEqualTo(lowered("(defn f [x] (inc x))"));
		assertThat(lowered("(defn- f ^long [x] (inc x))")).isEqualTo(lowered("(defn- f [x] (inc x))"));
		assertThat(lowered("(fn ^long [x] x)")).isEqualTo(lowered("(fn [x] x)"));
		assertThat(lowered("(defn f (^long [x] x) (^String [x y] y))"))
			.isEqualTo(lowered("(defn f ([x] x) ([x y] y))"));
		assertThatThrownBy(() -> Clojure.read("(fn 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("a parameter vector takes its bindings in a vector: 1");
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
	void referClojureDefinlineAndWithPrecisionLower() {
		// (refer-clojure ...) is the ns (:refer-clojure ...) wiring as a standalone
		// form: the same :only/:exclude/:rename options, answering nil
		assertThat(lowered("(refer-clojure :exclude '[map])")).isEqualTo(FALSE_BINDING + "NIL");
		assertThat(lowered("(refer-clojure :only '[map])")).isEqualTo(FALSE_BINDING + "NIL");
		assertThat(lowered("(refer-clojure :rename '{map core-map})")).isEqualTo(FALSE_BINDING + "NIL");
		assertThatThrownBy(() -> Clojure.read("(refer-clojure :bogus '[map])", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining(":refer-clojure option :bogus is not supported yet");
		// a rename spells the core var under its new name at a call head, like the
		// ns clause's
		assertThat(lowered("(refer-clojure :rename '{map core-map}) (defn f [x] (core-map inc x))"))
			.contains("(RONTOLISP::%CLOJURE-MAP ");
		// definline is defn: the same defun, no compiler macro (nothing here inlines
		// call sites), spelled the oracle's qualified way too
		assertThat(lowered("(definline di [x] x)")).contains("(DEFUN |c%di|").doesNotContain("COMPILER-MACRO");
		assertThat(lowered("(definline di \"doc\" [x] x)")).contains("(DEFUN |c%di|");
		assertThat(lowered("(clojure.core/definline di [x] x)")).isEqualTo(lowered("(definline di [x] x)"));
		assertThatThrownBy(() -> Clojure.read("(definline di)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("defn needs a parameter vector and a body");
		// a definline below its use pre-scans like a defn, so a function defined
		// above calls the definition below
		assertThat(lowered("(defn fwd-user [] (di-fwd 1)) (definline di-fwd [x] (+ x 10))"))
			.contains("(DEFUN |c%di-fwd|");
		// with-precision binds *math-context* to a math context of the precision and
		// the rounding mode around its body, answering the last value
		assertThat(lowered("(with-precision 10 1)")).contains(
				"(LET* ((RONTOLISP::%CLOJURE-MATH-CONTEXT (RONTOLISP::%CLOJURE-BD-MATH-CONTEXT 10 \"HALF_UP\"))) 1)")
			.contains("DEFVAR RONTOLISP::%CLOJURE-MATH-CONTEXT");
		assertThat(lowered("(with-precision (+ 5 5) :rounding FLOOR)")).contains(
				"(LET* ((RONTOLISP::%CLOJURE-MATH-CONTEXT (RONTOLISP::%CLOJURE-BD-MATH-CONTEXT (RONTOLISP::%CLOJURE-+ 5 5) \"FLOOR\"))) NIL)");
		assertThatThrownBy(() -> Clojure.read("(with-precision)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("with-precision takes a precision and a body");
		assertThatThrownBy(() -> Clojure.read("(with-precision 2 :rounding SIDEWAYS 1)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("No matching field found: SIDEWAYS for class java.math.RoundingMode");
	}

	@Test
	void boundFnWithLocalVarsAndPvaluesLower() {
		// bound-fn is bound-fn* over fn: the fdecl travels untouched, so a docstring
		// the fn lowering refuses fails there, where the oracle's macroexpansion
		// fails; the wrapper pushes the creation-time bindings around the call
		assertThat(lowered("(bound-fn [x] x)")).contains("(RONTOLISP::%CLOJURE-BOUND-FN*");
		assertThat(lowered("(bound-fn name [x] x)")).contains("(RONTOLISP::%CLOJURE-BOUND-FN*");
		assertThat(lowered("(clojure.core/bound-fn [x] x)")).isEqualTo(lowered("(bound-fn [x] x)"));
		assertThatThrownBy(() -> Clojure.read("(bound-fn)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("fn needs a parameter vector and a body");
		// bound-fn* takes exactly the function; anything else is the oracle's
		// arity refusal
		assertThat(lowered("(bound-fn* (fn [x] x))")).contains("(RONTOLISP::%CLOJURE-BOUND-FN*");
		assertThatThrownBy(() -> Clojure.read("(bound-fn* 1 2)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (2) passed to: clojure.core/bound-fn*");
		// with-local-vars binds each name to a fresh cell: the constructor, the
		// box stores and the unwind-protect restoring them
		assertThat(lowered("(with-local-vars [a 1] a)")).contains("(RONTOLISP::%CLOJURE-LOCAL-VAR")
			.contains("UNWIND-PROTECT");
		assertThatThrownBy(() -> Clojure.read("(with-local-vars [a] a)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("with-local-vars requires an even number of forms in binding vector");
		assertThatThrownBy(() -> Clojure.read("(with-local-vars \"x\" a)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("with-local-vars takes its bindings in a vector");
		// pvalues is one future per expression, dereferenced in order: the
		// values answer in order like the oracle's, a failure surfacing at the
		// deref; on wasm each expression runs in order on the calling thread
		assertThat(lowered("(pvalues 1 2)")).contains("RONTOLISP::%CLOJURE-FUTURE-START")
			.contains("RONTOLISP::%CLOJURE-DEREF-OTHER");
		assertThat(wasmLowered("(pvalues 1 2)")).isEqualTo(FALSE_BINDING + "(LIST 1 2)");
		assertThat(lowered("(pvalues)")).contains("(LIST)").doesNotContain("FUTURE-START");
		assertThat(wasmLowered("(pvalues)")).isEqualTo(FALSE_BINDING + "(LIST)");
		// g16's values: the three macros answer the run-time once-expansion
		// delegate, while bound-fn* answers its function, capturing where the
		// value is called
		assertThat(lowered("(def x bound-fn)")).contains("C%MACROEXPAND-1");
		assertThat(lowered("(def x with-local-vars)")).contains("C%MACROEXPAND-1");
		assertThat(lowered("(def x pvalues)")).contains("C%MACROEXPAND-1");
		assertThat(lowered("(def x bound-fn*)")).contains("(RONTOLISP::%CLOJURE-BOUND-FN*")
			.doesNotContain("C%MACROEXPAND-1");
	}

	@Test
	void amapAreduceLowerToIndexedWalks() {
		// amap clones the array once and sets each element by index, answering
		// the clone; areduce walks the index with the accumulator, answering it.
		// Both lower the oracle's own macroexpansion, so a program's redefinition
		// of an expansion verb never leaks in.
		assertThat(lowered("(let [a [1] e 2] (amap a i r e))")).contains("LABELS")
			.contains("RONTOLISP::%CLOJURE-ALENGTH")
			.contains("ACLONE")
			.contains("(RONTOLISP::%CLOJURE-ASET ")
			.doesNotContain("DOLIST");
		assertThat(lowered("(let [a [1] e 2] (areduce a i r 0 e))")).contains("LABELS")
			.contains("RONTOLISP::%CLOJURE-ALENGTH")
			.doesNotContain("ACLONE")
			.doesNotContain("DOLIST");
		assertThat(lowered("(defn alength [x] 99) (let [a [1]] (amap a i r (alength a)))"))
			.contains("RONTOLISP::%CLOJURE-ALENGTH")
			.contains("|c%alength|");
		assertThat(lowered("(let [a [1] e 2] (clojure.core/amap a i r e))"))
			.isEqualTo(lowered("(let [a [1] e 2] (amap a i r e))"));
		assertThat(lowered("(let [a [1] e 2] (clojure.core/areduce a i r 0 e))"))
			.isEqualTo(lowered("(let [a [1] e 2] (areduce a i r 0 e))"));
		// both are macros in the oracle, so an is over one is the any-form
		// assertion, like over doseq
		assertThat(lowered(
				"(ns t (:use clojure.test)) (deftest a (let [x [1]] (is (amap x i r i)) (is (areduce x i r 0 i))))"))
			.contains("RONTOLISP::%CLOJURE-TEST-ANY")
			.doesNotContain("RONTOLISP::%CLOJURE-TEST-PRED");
		assertThatThrownBy(() -> Clojure.read("(amap)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/amap");
		assertThatThrownBy(() -> Clojure.read("(amap a i r)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (3) passed to: clojure.core/amap");
		assertThatThrownBy(() -> Clojure.read("(amap a i r e x)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (5) passed to: clojure.core/amap");
		assertThatThrownBy(() -> Clojure.read("(areduce)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/areduce");
		assertThatThrownBy(() -> Clojure.read("(areduce a i r init)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (4) passed to: clojure.core/areduce");
		assertThatThrownBy(() -> Clojure.read("(areduce a i r init e x)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (6) passed to: clojure.core/areduce");
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
		assertThat(lowered("(defmulti area class) (defmethod area Number [n] n)")).contains("\"java.lang.Number\"");
		assertThat(lowered("(defmulti area class) (defmethod area Long [n] n)")).contains("\"java.lang.Long\"")
			.doesNotContain("\"number\"");
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
		assertThat(lowered("(defmulti area :t) (get-method area Number)")).contains("\"java.lang.Number\"");
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
	void hierarchyVerbsNameValues() {
		// g39: the hierarchy family resolves as a value, over the same runtime as
		// the call -- including the class-chain flags the call sets, so a class
		// keyword still walks. Wrong counts signal in the call's words.
		assertThat(lowered("(def x isa?)")).contains("C%H-ISA?").contains("%CLOJURE-SHARED-VALUE");
		assertThat(lowered("(def x derive)")).contains("C%H-DERIVE");
		assertThat(lowered("(def x underive)")).contains("C%H-UNDERIVE");
		assertThat(lowered("(def x parents)")).contains("C%H-PARENTS").contains("C%H-GLOBAL");
		assertThat(lowered("(def x ancestors)")).contains("C%H-ANCESTORS");
		assertThat(lowered("(map descendants [1])")).contains("C%H-DESCENDANTS");
		assertThat(lowered("(def x make-hierarchy)")).contains("(C%H-EMPTY)");
		assertThatThrownBy(() -> Clojure.read("(def x (isa? :a))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("isa? takes a child and a parent, or a hierarchy and both");
	}

	@Test
	void coreFunctionValueArmsForArraysAndStructs() {
		// g39: aget/alength/aset/make-array, struct/struct-map and list* resolve as
		// values, over the same runtime as the call. Wrong counts signal in the
		// call's words.
		assertThat(lowered("(def x alength)")).contains("RONTOLISP::%CLOJURE-ALENGTH");
		assertThat(lowered("(def x aget)")).contains("RONTOLISP::%CLOJURE-AGET");
		assertThat(lowered("(def x aset)")).contains("RONTOLISP::%CLOJURE-ASET");
		assertThat(lowered("(def x make-array)")).contains("RONTOLISP::%CLOJURE-BYTE-ARRAY");
		assertThat(lowered("(def x struct)")).contains("Too many arguments to struct constructor");
		assertThat(lowered("(def x struct-map)")).contains("struct-map takes key/value pairs");
		assertThat(lowered("(def x list*)")).contains("RONTOLISP::%CLOJURE-CONS");
		assertThat(lowered("(def x newline)")).contains("(PRINC \"\n\")");
		assertThat(lowered("(def x shutdown-agents)")).contains("(LAMBDA NIL NIL)");
		assertThat(lowered("(def x ensure)")).contains("RONTOLISP::%CLOJURE-STM-REQUIRE");
		assertThat(lowered("(def x await)")).contains("RONTOLISP::%CLOJURE-AGENT-AWAIT");
		assertThat(lowered("(def x await-for)")).contains("RONTOLISP::%CLOJURE-AGENT-AWAIT-FOR");
		assertThat(lowered("(def x instance?)")).contains("RONTOLISP::%CLOJURE-CLASS-INSTANCE-P");
		assertThatThrownBy(() -> Clojure.read("(alength)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("alength takes an array");
		assertThatThrownBy(() -> Clojure.read("(make-array String)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("make-array takes a class and dimensions");
		assertThatThrownBy(() -> Clojure.read("(await-for)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/await-for");
	}

	@Test
	void lowerTimeOnlyVerbsRefuseAtRunTimeAsValues() {
		// g39: the verbs whose head meaning is lower-time-only (the multimethod
		// name, the literal protocol, the literal load path, the paired thread
		// bindings) still resolve as values -- like the g30 call-time refusals --
		// with the count checked first in the call's words, then the run-time
		// refusal.
		assertThat(lowered("(def x get-method)")).contains("of a computed multimethod");
		assertThat(lowered("(def x remove-method)")).contains("of a computed multimethod");
		assertThat(lowered("(def x methods)")).contains("of a computed multimethod");
		assertThat(lowered("(def x prefer-method)")).contains("of a computed multimethod");
		assertThat(lowered("(def x prefers)")).contains("of a computed multimethod");
		assertThat(lowered("(def x remove-all-methods)")).contains("of a computed multimethod");
		assertThat(lowered("(def x load)")).contains("RONTOLISP::%CLOJURE-LOAD-V");
		assertThat(lowered("(def x push-thread-bindings)")).contains("what binding expands to");
		assertThat(lowered("(def x pop-thread-bindings)")).contains("what binding expands to");
		// g40: the six multimethod arms keep the refusal only for a function the
		// registry holds no tables for -- anything but a defined multimethod's
		// dispatch function -- and otherwise read the registry's tables
		assertThat(lowered("(def x get-method)")).contains("RONTOLISP::%CLOJURE-MULTIMETHODS")
			.contains("RONTOLISP::%CLOJURE-MULTIFN-GET-METHOD");
		assertThat(lowered("(def x remove-method)")).contains("RONTOLISP::%CLOJURE-MULTIMETHODS")
			.contains("RONTOLISP::%CLOJURE-DISPATCH-KEY")
			.contains("RONTOLISP::%CLOJURE-DISPATCH-NAME");
		assertThat(lowered("(def x methods)")).contains("RONTOLISP::%CLOJURE-MULTIMETHODS")
			.contains("RONTOLISP::%CLOJURE-METHODS");
		assertThat(lowered("(def x prefer-method)")).contains("RONTOLISP::%CLOJURE-MULTIMETHODS")
			.contains("RONTOLISP::%CLOJURE-DISPATCH-VALUE");
		assertThat(lowered("(def x prefers)")).contains("RONTOLISP::%CLOJURE-MULTIMETHODS")
			.contains("RONTOLISP::%CLOJURE-PREFERS");
		assertThat(lowered("(def x remove-all-methods)")).contains("RONTOLISP::%CLOJURE-MULTIMETHODS")
			.contains("CLRHASH");
		// g40: a defmulti registers its tables beside the dispatch function, and
		// answers the defined name (what the bare defun answers)
		assertThat(lowered("(defmulti area :shape)")).contains("RONTOLISP::%CLOJURE-REGISTER-MULTIMETHOD")
			.contains("(DEFUN |c%area|");
		assertThatThrownBy(() -> Clojure.read("(get-method m)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("get-method takes a multimethod and a dispatch value");
		// g42: the three protocol arms run what the calls run over the registry,
		// with the oracle's arities -- a value the protocol registry holds no
		// tables for is the oracle's NullPointerException, a type that is no
		// class its ClassCastException, a pair whose protocol is none its
		// IllegalArgumentException
		assertThat(lowered("(def x satisfies?)")).contains("RONTOLISP::%CLOJURE-SATISFIES")
			.contains("clojure.core/satisfies?");
		assertThat(lowered("(def x extends?)")).contains("RONTOLISP::%CLOJURE-EXTENDS-P")
			.contains("clojure.core/extends?");
		assertThat(lowered("(def x extend)")).contains("RONTOLISP::%CLOJURE-EXTEND");
		// g42: a defprotocol registers its tables beside the methods table, and
		// answers the table (what the bare setq answers); a defrecord registers
		// its tag beside the constructors, answering the tag
		assertThat(lowered("(defprotocol P (foo [x]))")).contains("RONTOLISP::%CLOJURE-REGISTER-PROTOCOL")
			.contains("(SETQ |c%P| |c%P%methods|)");
		assertThat(lowered("(defrecord R [a])")).contains("RONTOLISP::%CLOJURE-REGISTER-TYPE-TAG")
			.contains("DEFUN |c%->R|");
		assertThatThrownBy(() -> Clojure.read("(extends? p)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/extends?");
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
		// a reify's rows are its site's: stored once under a tag no symbol spells, ahead
		// of the top-level form holding it, each a trampoline into the evaluation's own
		// methods
		String reify = lowered("(defprotocol P (foo [x])) (defn f [] (reify P (foo [_] 1)))");
		assertThat(reify).contains("(SETF (GETHASH (LIST :C%KEYWORD \"foo\") ")
			.contains("(FUNCALL (AREF (CADDR ")
			.contains("(LIST :C%REIFY (LIST :C%KEYWORD \"reify ")
			.contains("(VECTOR (LAMBDA (|c%_|) 1))")
			.doesNotContain("GENSYM");
		assertThat(reify.indexOf("(FUNCALL (AREF (CADDR ")).isLessThan(reify.indexOf("(DEFUN |c%f|"));
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
		assertThat(reify).contains("(RONTOLISP::%CLOJURE-COLL-REDUCER-ROW |c%clojure.core.protocols/CollReduce%methods|"
				+ " (LIST :C%KEYWORD \"reify ");
		String record = lowered("(require '[clojure.core.protocols :as p]) (defrecord R [a])"
				+ " (extend-protocol p/IKVReduce R (kv-reduce [r f init] init))");
		assertThat(record).contains(
				"(RONTOLISP::%CLOJURE-KV-REDUCER-ROW |c%clojure.core.protocols/IKVReduce%methods| (LIST :C%KEYWORD \"R\")");
		// the namespace's own nil and Object rows, a core kind and any other protocol
		// store without the writer (the defprotocol registration carries the
		// reducing method's keyword, never the row writer, so a program
		// extending one to no typed row keeps the reducible arms folded)
		String core = lowered("(require '[clojure.core.protocols :as p])"
				+ " (extend-protocol p/CollReduce String (coll-reduce ([s f] 1) ([s f i] 2)))"
				+ " (defprotocol Q (m [x])) (reify Q (m [_] 1))");
		assertThat(core).doesNotContain("(%CLOJURE-COLL-REDUCER-ROW").doesNotContain("(%CLOJURE-KV-REDUCER-ROW");
		assertThat(core).contains("(RONTOLISP::%CLOJURE-REGISTER-PROTOCOL")
			.doesNotContain("CLOJURE-COLL-REDUCER-ROW")
			.doesNotContain("CLOJURE-KV-REDUCER-ROW");
	}

	@Test
	void anExtendToATypeWhoseBodyImplementsTheProtocolIsRefusedLikeTheOracles() {
		// the oracle's class-already-implements refusal, at lowering for the head
		// calls (the value arm refuses when it runs, over the tag registry)
		for (String form : List.of("(defprotocol W (m [x])) (deftype T [] W (m [_] 1)) (extend T W {:m (fn [_] 2)})",
				"(defprotocol W (m [x])) (deftype T [] W (m [_] 1)) (extend-protocol W T (m [_] 2))",
				"(defprotocol W (m [x])) (deftype T [] W (m [_] 1)) (extend-type T W (m [_] 2))",
				"(defprotocol W (m [x])) (defrecord R [a] W (m [_] 1)) (extend R W {:m (fn [_] 2)})")) {
			assertThatThrownBy(() -> Clojure.read(form, null)).isInstanceOf(LispReadException.class)
				.hasMessageContaining("already directly implements interface user.W for protocol:#'user/W");
		}
		// a type with no body for the protocol, or another protocol's body,
		// extends and re-extends freely; a later extend replaces the row
		assertThat(lowered("(defprotocol W (m [x])) (deftype T []) (extend T W {:m (fn [_] 1)})"))
			.contains("|c%W%methods|");
		assertThat(lowered("(defprotocol W (m [x])) (defprotocol V (v [x])) (deftype T [] V (v [_] 1))"
				+ " (extend-protocol W T (m [_] 2))"))
			.contains("|c%W%methods|");
		assertThat(lowered("(defprotocol W (m [x])) (extend-protocol W String (m [_] 1))"))
			.contains("(SETF (GETHASH (LIST :C%KEYWORD \"string\") |c%W%methods|) (MAKE-HASH-TABLE");
		assertThat(lowered("(defprotocol W (m [x])) (extend-protocol W Object (m [_] 1))"))
			.contains("(SETQ |c%W%default| (MAKE-HASH-TABLE");
	}

	@Test
	void aBodyImplementingAnInterfaceStoresItsRowThroughTheFamilyOfEach() {
		// one store per family under the type's tag: the interfaces it implements
		// (supers included) and a lambda per method, the body's or the oracle's
		// AbstractMethodError for one it leaves out
		String reify = lowered("(def r (reify clojure.lang.Counted (count [_] 3)))");
		assertThat(reify).contains("(RONTOLISP::%CLOJURE-COUNTED-ROW (LIST :C%KEYWORD \"reify ")
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
		// a CharSequence stores its three abstract methods (the defaults its body leaves
		// out stay Java's) and its toString in the Object row
		String chars = lowered("(deftype S [s] CharSequence (length [_] 1) (charAt [_ i] \\a)"
				+ " (subSequence [_ a b] nil) (toString [_] \"a\"))");
		assertThat(chars)
			.contains("(RONTOLISP::%CLOJURE-CHAR-SEQUENCE-ROW (LIST :C%KEYWORD \"S\") '(\"java.lang.CharSequence\")"
					+ " (LIST \"length\" (LAMBDA (")
			.contains("\"charAt\" (LAMBDA (")
			.contains("\"subSequence\" (LAMBDA (")
			.contains("(RONTOLISP::%CLOJURE-OBJECT-ROW (LIST :C%KEYWORD \"S\") NIL (LIST \"toString\" (LAMBDA (")
			.doesNotContain("\"isEmpty\"");
		// a Comparator stores its compare through the Comparable row's family (the
		// defaults its body leaves out stay Java's)
		String comparator = lowered("(reify java.util.Comparator (compare [_ a b] (compare b a)))");
		assertThat(comparator).contains("(RONTOLISP::%CLOJURE-COMPARABLE-ROW (LIST :C%KEYWORD \"reify ")
			.contains("'(\"java.util.Comparator\") (LIST \"compare\" (LAMBDA (")
			.doesNotContain("\"reversed\"");
		// an Object override needs no group of its own, and a reify's names its class
		String object = lowered("(defprotocol P (m [x])) (reify P (m [_] 1) (toString [_] \"r\"))");
		assertThat(object).contains("(RONTOLISP::%CLOJURE-OBJECT-ROW (LIST :C%KEYWORD \"reify ")
			.contains(" NIL (LIST \"toString\" (LAMBDA (")
			.contains("\"class\" \"user$reify\")");
		// a protocol-only body stores no interface row
		assertThat(lowered("(defprotocol P (m [x])) (reify P (m [_] 1)) (deftype U [] P (m [_] 2))"))
			.doesNotContain("-ROW (");
		// a group symbol resolves like the oracle's class names
		assertThat(lowered("(ns u (:import (clojure.lang IFn))) (reify IFn (invoke [_] 1))"))
			.contains("%CLOJURE-INVOKABLE-ROW");
		// a host interface no core verb reads stores through the host-interface family
		// under its binary name, its reflected supers with it (Comparable, which a verb
		// reads, through its own family)
		assertThat(lowered("(def r (reify java.util.concurrent.ThreadFactory (newThread [_ x] x)))"))
			.contains("(RONTOLISP::%CLOJURE-HOST-INTERFACE-ROW (LIST :C%KEYWORD \"reify ")
			.contains("'(\"java.util.concurrent.ThreadFactory\") (LIST \"newThread\" (LAMBDA (");
		assertThat(lowered("(deftype D [] java.util.concurrent.ScheduledFuture (isDone [_] true))"))
			.contains("'(\"java.util.concurrent.ScheduledFuture\" \"java.util.concurrent.Delayed\""
					+ " \"java.util.concurrent.Future\")")
			.contains("(RONTOLISP::%CLOJURE-COMPARABLE-ROW (LIST :C%KEYWORD \"D\") '(\"java.lang.Comparable\")");
		for (String[] refused : new String[][] {
				{ "(reify IFn (invoke [_] 1))", "Unable to resolve symbol: IFn in this context" },
				{ "(reify clojure.lang.IChunkedSeq (first [_] 1))",
						"clojure.lang.IChunkedSeq is not supported yet as an interface of reify" },
				{ "(reify clojure.lang.Foo)", "Unable to resolve classname: clojure.lang.Foo" },
				{ "(reify java.lang.String)", "only interfaces are supported, had: java.lang.String" },
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
	void aDefinterfaceDefinesAnInterfaceADefTypeImplementsThroughItsOwnRow() {
		// definterface binds the binary name and stores no row of its own; a deftype
		// or reify body naming it stores its methods in the defined-interface row
		// under that name, which instance? tests and an instance call dispatches on
		String defined = lowered("(definterface PBR (^int readChar []) (^long readChars [b s l]))");
		assertThat(defined).contains("\"user.PBR\"").doesNotContain("-ROW (");
		String type = lowered("(definterface PBR (^int readChar [])) (deftype R [n] PBR (readChar [_] n))");
		assertThat(type).contains("(RONTOLISP::%CLOJURE-DEFINED-INTERFACE-ROW (LIST :C%KEYWORD \"R\")"
				+ " '(\"user.PBR\") (LIST \"readChar\" (LAMBDA (");
		String reified = lowered("(definterface PBR (^int readChar [])) (def r (reify PBR (readChar [_] 1)))");
		assertThat(reified).contains("(RONTOLISP::%CLOJURE-DEFINED-INTERFACE-ROW (LIST :C%KEYWORD \"reify ")
			.contains("'(\"user.PBR\") (LIST \"readChar\"");
		// instance? of the interface reads the row under its binary name, and a call
		// of its method the row's entry
		assertThat(lowered("(definterface PBR (m [x])) (defn f [x] (instance? PBR x))"))
			.contains("(RONTOLISP::%CLOJURE-INTERFACE-ENTRY");
		assertThat(lowered("(definterface PBR (m [x])) (defn f [x] (.m x 1))"))
			.contains("(RONTOLISP::%CLOJURE-INTERFACE-ENTRY");
		// a program defining none lowers a .m call and an instance? without the rows
		assertThat(lowered("(defn f [x] (.m x 1))")).doesNotContain("DEFINED-INTERFACE-ROW");
		for (String[] refused : new String[][] { { "(definterface)", "definterface takes a name and methods" },
				{ "(definterface PBR (1 2))", "definterface takes method signatures, not (1 2)" },
				{ "(definterface PBR foo)", "definterface takes method signatures, not |foo|" },
				{ "(definterface PBR (m [x] [y]))",
						"definterface takes method signatures, not (|m| (|%vector| |x|) (|%vector| |y|))" },
				{ "(definterface PBR (m [1 y]))", "definterface method m takes parameter names, not 1" },
				{ "(definterface PBR (^int q []) (^int q []))",
						"Duplicate method name \"q\" with signature \"()I\" in class file user.PBR" },
				{ "(definterface PBR (q [x]) (q [x]))",
						"Duplicate method name \"q\" with signature \"(Ljava.lang.Object;)Ljava.lang.Object;\""
								+ " in class file user.PBR" },
				{ "(gen-class)", "protocols are not supported yet: gen-class" },
				{ "(gen-interface)", "protocols are not supported yet: gen-interface" },
				{ "(definterface PBR (m [x])) (extend-protocol PBR nil (m [_] 1))",
						"interface user.PBR is not a protocol" },
				{ "(definterface PBR (m [x])) (extend-type String PBR (m [_] 1))",
						"interface user.PBR is not a protocol" },
				{ "(deftype R [] PBR (m [_] 1))", "Unable to resolve symbol: PBR in this context" } }) {
			assertThatThrownBy(() -> Clojure.read(refused[0], null)).as(refused[0])
				.isInstanceOf(LispReadException.class)
				.hasMessageContaining(refused[1]);
		}
	}

	@Test
	void aTypeShowsJavaItsFaceOnlyWhereTheProgramNamesTheHost() {
		// a deftype implementing a Java interface: its maker -- a java:reify over the
		// literal interface names, Comparable among them, standing for the value -- is
		// registered around the tag of its first row store; each method calls the
		// type's row method, an abstract one it leaves out its row's refusal, and
		// compareTo of a type implementing no Comparable the oracle's cast failure
		String task = "(deftype Task [] Runnable (run [_] (println \"ran\")))";
		String faced = lowered(task + " (Thread. (Task.))");
		assertThat(faced)
			.contains("(RONTOLISP::%CLOJURE-INVOKABLE-ROW (RONTOLISP::%CLOJURE-JAVA-FACE-TAG (LIST :C%KEYWORD \"Task\")"
					+ " (LAMBDA (")
			.contains("(JAVA:REIFY '(\"java.lang.Runnable\" \"java.lang.Comparable\") :VALUE ")
			.contains(" :CLASS \"user.Task\" \"compareTo(java.lang.Object)\" (LAMBDA (")
			.contains("(RONTOLISP::%CLOJURE-CLASS-CAST-EXCEPTION"
					+ " \"class user.Task cannot be cast to class java.lang.Comparable\")) \"run()\" (LAMBDA NIL"
					+ " (FUNCALL (RONTOLISP::%CLOJURE-INTERFACE-ENTRY ")
			.contains(" \"run\") ")
			.contains(":JAVA-FALSE :OCTETS))) '(\"java.lang.Runnable\")");
		// only a program naming a java: operator hands a value to Java; only the host has
		// a Java; a type with no Java interface and no Object override crosses as an
		// object equal only to itself
		assertThat(lowered(task + " (def t (Task.))")).doesNotContain("JAVA-FACE");
		assertThat(Clojure.read(task + " (Thread. (Task.))", null, null, ClojureFiles.NONE, false)
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"))).doesNotContain("JAVA-FACE");
		// a record's face is a Map too, its Map methods, equals, hashCode and toString
		// its map view's, made once per face; a record with no Java interface crosses as
		// its map view
		String record = lowered("(defrecord R [] Runnable (run [_] nil)) (Thread. (->R))");
		assertThat(record).contains("(JAVA:REIFY '(\"java.util.Map\" \"java.lang.Runnable\" \"java.lang.Comparable\")")
			.contains("(LET ((")
			.contains(" (RONTOLISP::%CLOJURE-HOST-RECORD-VIEW ")
			.contains("\"get(java.lang.Object)\" (LAMBDA (")
			.contains("(JAVA:CALL (THE (JAVA:OBJECT \"java.util.Map\") ")
			.contains(" \"get\" (RONTOLISP::%CLOJURE-HOST-MEMBER (RONTOLISP::%CLOJURE-HOST-ANSWER ")
			.contains("\"equals(java.lang.Object)\" (LAMBDA (")
			.contains("\"toString()\" (LAMBDA NIL (RONTOLISP::%CLOJURE-HOST-MEMBER (JAVA:CALL ")
			.doesNotContain("\"getOrDefault(");
		assertThat(lowered("(defrecord R [] Object (toString [_] \"r\")) (Thread. (->R))")).doesNotContain("JAVA-FACE");
		assertThat(lowered("(defrecord R [] clojure.lang.IDeref (deref [_] 1)) (Thread. (->R))"))
			.doesNotContain("JAVA-FACE");
		assertThat(lowered("(deftype U [] clojure.lang.Counted (count [_] 1)) (Thread. (U.))"))
			.doesNotContain("JAVA-FACE");
		// an Object override alone shows a face; a reify's registers once per site,
		// under its class; a boolean answer by its truth, an Iterator as Java's
		String reify = lowered("(str (java.util.ArrayList. [(reify Object (toString [_] \"r\") (equals [_ o] 1))]))");
		assertThat(reify)
			.contains("(RONTOLISP::%CLOJURE-OBJECT-ROW (RONTOLISP::%CLOJURE-JAVA-FACE-TAG (LIST :C%KEYWORD \"reify ")
			.contains("(JAVA:REIFY '(\"java.lang.Comparable\") :VALUE ")
			.contains(" :CLASS \"user$reify\" ")
			.contains("\"equals(java.lang.Object)\" (LAMBDA (")
			.contains("(RONTOLISP::%CLOJURE-TRUTHY (FUNCALL (RONTOLISP::%CLOJURE-INTERFACE-ENTRY ")
			.contains("\"toString()\" (LAMBDA NIL (RONTOLISP::%CLOJURE-HOST-MEMBER (FUNCALL ");
		assertThat(lowered(
				"(deftype Bag [items] Iterable (iterator [_] (.iterator items))) (String/join \",\" (Bag. []))"))
			.contains("\"iterator()\" (LAMBDA NIL (RONTOLISP::%CLOJURE-HOST-ITERATOR (FUNCALL ");
		// a Comparator's face implements it beside Comparable, its compare calling
		// the type's row method
		assertThat(lowered(
				"(deftype D [] java.util.Comparator (compare [_ a b] (compare b a)))" + " (java.util.TreeMap. (D.))"))
			.contains("(JAVA:REIFY '(\"java.util.Comparator\" \"java.lang.Comparable\") :VALUE ")
			.contains(" \"compare(java.lang.Object,java.lang.Object)\" (LAMBDA (");
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
	void instanceOfAClassValueTestsTheValueAgainstItAtRunTime() {
		// a class held in a var, a local or computed: the value's class, read once
		assertThat(lowered("(def c (class 1)) (instance? c 2)")).contains("(RONTOLISP::%CLOJURE-CLASS-INSTANCE-P ");
		assertThat(lowered("(instance? (class 1) 2)")).contains("(RONTOLISP::%CLOJURE-CLASS-INSTANCE-P ");
		assertThat(lowered("(fn [String x] (instance? String x))")).contains("(RONTOLISP::%CLOJURE-CLASS-INSTANCE-P ");
		assertThat(lowered("(ns a) (def t (class 1)) (ns b) (instance? a/t 2)"))
			.contains("(RONTOLISP::%CLOJURE-CLASS-INSTANCE-P ");
		// a class name keeps its own test; a keyword is no class
		assertThat(lowered("(def x 1) (instance? String x)")).doesNotContain("CLASS-INSTANCE-P");
		assertThatThrownBy(() -> Clojure.read("(instance? :a 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("instance? takes a class name, not :a");
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
		// the aware helpers, which a program giving no reference a validator or a watch
		// has renamed to the plain ones (ClojureArms.Family.WATCH)
		assertThat(lowered("(atom 1)")).contains(":C%ATOM").contains("(RONTOLISP::%CLOJURE-REF-CELL 1)");
		assertThat(lowered("(def a (atom 1)) @a")).contains("RONTOLISP::%CLOJURE-STM-DEREF");
		assertThat(lowered("(def a (atom 1)) (swap! a inc)")).contains("RONTOLISP::%CLOJURE-REF-SWAP");
		assertThat(lowered("(def a (volatile! 1)) (vswap! a inc)")).contains("APPLY")
			.doesNotContain("%CLOJURE-ATOM-")
			.doesNotContain("%CLOJURE-REF-");
		assertThat(lowered("(def a (atom 1)) (compare-and-set! a 1 2)")).contains("RONTOLISP::%CLOJURE-REF-CAS");
		assertThat(lowered("(def a (atom 1)) (reset! a 2)")).contains("RONTOLISP::%CLOJURE-REF-RESET");
		assertThat(lowered("(def a (atom 1)) (swap-vals! a + 2)")).contains("RONTOLISP::%CLOJURE-REF-SWAP-VALS");
		assertThat(lowered("(def a (atom 1)) (reset-vals! a 2)")).contains("RONTOLISP::%CLOJURE-REF-RESET-VALS");
		assertThat(lowered("(atom 1 :validator pos?)")).contains("RONTOLISP::%CLOJURE-ATOM-OPTIONS");
		assertThat(lowered("(add-watch (atom 1) :k identity)")).contains("RONTOLISP::%CLOJURE-ADD-WATCH");
		assertThat(lowered("(map set-validator! [] [])")).contains("RONTOLISP::%CLOJURE-SET-VALIDATOR-V");
		assertThatThrownBy(() -> Clojure.read("(atom)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/atom");
		assertThatThrownBy(() -> Clojure.read("(add-watch (atom 1) :k)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (2) passed to: clojure.core/add-watch");
		assertThatThrownBy(() -> Clojure.read("(reset-vals! (atom 1))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/reset-vals!");
		assertThatThrownBy(() -> Clojure.read("(await-for)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/await-for");
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
	void aRequireTheCodeAroundItReachesFailsWhereItRunsWhenNoRootHoldsTheNamespace() {
		// the oracle's load fails where it runs: inside a function, a try or a when the
		// failure is the FileNotFoundException that code throws, in the oracle's words;
		// a top-level require still fails while the program lowers
		String expected = "(RONTOLISP::%CLOJURE-FILE-NOT-FOUND-EXCEPTION "
				+ "\"Could not locate no/such/lib__init.class, no/such/lib.clj or no/such/lib.cljc on classpath.\")";
		assertThat(lowered("(defn f [] (require 'no.such.lib))")).contains(expected);
		assertThat(lowered("(try (require '[no.such.lib :as n]) (catch Exception e nil))")).contains(expected);
		assertThat(lowered("(when false (use 'no.such.lib))")).contains(expected);
		assertThatThrownBy(() -> Clojure.read("(require 'no.such.lib)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Could not locate no/such/lib.clj");
	}

	@Test
	void nsResolveResolvesAQuotedSymbolInANamespaceTheLoweringKnows() {
		// a quoted symbol in a namespace the lowering knows resolves like resolve there;
		// one that does not exist is the oracle's failure (find-ns's nil reaching the-ns,
		// the-ns's own refusal); anything computed goes to the run-time library
		String source = "(ns nr) (defn f [] 1) ";
		assertThat(lowered(source + "(ns-resolve 'nr 'f)")).contains("\"nr/f\"");
		assertThat(lowered(source + "(ns-resolve (find-ns 'nr) 'f)")).contains("\"nr/f\"");
		assertThat(lowered(source + "(ns-resolve *ns* 'f)")).contains("\"nr/f\"");
		assertThat(lowered(source + "(ns-resolve 'clojure.core 'map)")).contains("\"clojure.core/map\"");
		assertThat(lowered(source + "(ns-resolve 'nr 'nowhere)")).doesNotContain("nowhere");
		assertThat(lowered(source + "(ns-resolve (find-ns 'no.such) 'f)"))
			.contains("(RONTOLISP::%CLOJURE-NULL-POINTER-EXCEPTION ");
		assertThat(lowered(source + "(ns-resolve 'no.such 'f)"))
			.contains("(RONTOLISP::%CLOJURE-EXCEPTION \"No namespace: no.such found\")");
		assertThat(lowered(source + "(let [s 'f] (ns-resolve *ns* s))")).contains("(RONTOLISP::%CLOJURE-NS-RESOLVE ");
		assertThatThrownBy(() -> Clojure.read("(ns-resolve 'user)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/ns-resolve");
	}

	@Test
	void aMacroBodyReadsItsCallAndTheCallsLocalsThroughFormAndEnv() {
		// &env is nil without a local, else a map of the locals' symbols; &form the call
		String source = "(defmacro m [] `(quote ~[(when &env (sort (map name (keys &env)))) &form]))";
		assertThat(macroLowered(source + " (m)")).contains("(VECTOR 'NIL '(|c%m|))");
		assertThat(macroLowered(source + " (let [a 1 b 2] (m))")).contains("\"a\"").contains("\"b\"");
		// a run-time expansion binds &form where a macro reads it, and only there
		assertThat(macroLowered(source + " (macroexpand '(m))")).contains("RONTOLISP::%CLOJURE-MACRO-FORM");
		assertThat(macroLowered("(defmacro n [] 1) (macroexpand '(n))")).doesNotContain("%CLOJURE-MACRO-FORM");
		assertThatThrownBy(() -> Clojure.read("(defn g [] &env)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: &env");
	}

	private static String macroLowered(String source) {
		return Clojure.read(source, null, ClojureMacroTime.create())
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"));
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
		// a String construction is the byte-array family's alias of java:new, which
		// decodes a byte array; a param-tagged one is java:new itself
		assertThat(lowered("(String. \"hi\")")).contains("(RONTOLISP::%CLOJURE-STRING-NEW \"java.lang.String\" \"hi\"");
		assertThat(lowered("(Integer/MAX_VALUE)")).contains("JAVA:FIELD");
		assertThat(lowered("(new String \"hi\")")).contains("(RONTOLISP::%CLOJURE-STRING-NEW \"java.lang.String\"");
		assertThat(lowered("(^[String] String/new \"hi\")")).contains("JAVA:NEW").doesNotContain("STRING-NEW");
		assertThat(lowered("(java.util.ArrayList. 3)")).contains("JAVA:NEW");
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
		// an initial capacity is an empty string output stream, never a java:new
		// (which wasm refuses); anything else at run time is the oracle's
		// ClassCastException to Number, nil its NullPointerException
		assertThat(lowered("(new java.io.StringWriter 16)")).contains("(RONTOLISP::%CLOJURE-STRING-WRITER-1 16)")
			.doesNotContain("JAVA:NEW");
		assertThat(lowered("(let [s (new java.io.StringWriter)] (.toString s))")).contains(
				"((RONTOLISP::%CLOJURE-STREAM-P |__clojure_0|) (RONTOLISP::%CLOJURE-STREAM-STRING |__clojure_0|))");
	}

	@Test
	void aZeroArgumentStringBuilderLowersToAStringOutputStream() {
		// A zero-argument (StringBuilder.), (new StringBuilder) or (StringBuffer.) is
		// the same string output stream as (new java.io.StringWriter) on every
		// backend, never a java:new (which wasm refuses); .length/.charAt/.setLength
		// read a string output stream's text, refusing any other stream in the
		// oracle's words; .intern of a string is the string; .toUpperCase of one
		// argument maps, its locale evaluated and ignored.
		assertThat(lowered("(StringBuilder.)")).contains("(RONTOLISP::%CLOJURE-STRING-WRITER)")
			.doesNotContain("JAVA:NEW");
		assertThat(lowered("(new StringBuilder)")).contains("(RONTOLISP::%CLOJURE-STRING-WRITER)")
			.doesNotContain("JAVA:NEW");
		assertThat(lowered("(StringBuffer.)")).contains("(RONTOLISP::%CLOJURE-STRING-WRITER)")
			.doesNotContain("JAVA:NEW");
		assertThat(lowered("(let [s (StringBuilder.)] (.length s))"))
			.contains("(RONTOLISP::%CLOJURE-STRING-OUTPUT-P |__clojure_0|)")
			.contains("RONTOLISP::%CLOJURE-STREAM-STRING");
		assertThat(lowered("(let [s (StringBuilder.)] (.charAt s 1))")).contains("RONTOLISP::%CLOJURE-CHAR-AT");
		assertThat(lowered("(let [s (StringBuilder.)] (.setLength s 2))"))
			.contains("RONTOLISP::%CLOJURE-STRING-BUILDER-SET-LENGTH");
		assertThat(lowered("(.intern \"hi\")")).contains("JAVA:CALL").contains("intern");
		assertThat(lowered("(.toUpperCase \"hi\" java.util.Locale/US)")).contains("PROGN")
			.contains("en_US")
			.contains("JAVA:CALL");
		assertThat(lowered("(println java.util.Locale/US java.util.Locale/ROOT java.util.Locale/ENGLISH)"))
			.contains("\"en_US\"");
		// where the host is not, no java: operator is left for any of them
		String portable = "(let [sb (StringBuilder.)] (.append sb \"a\") (println (str sb) (.length sb)))"
				+ " (println (.toUpperCase \"hi\" java.util.Locale/US) (.intern \"x\"))";
		assertThat(Clojure.read(portable, null, null, ClojureFiles.NONE, false)
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"))).doesNotContain("JAVA:");
	}

	@Test
	void aOneArgumentStringBuilderLowersToAStreamTheArgumentFills() {
		// A one-argument (StringBuilder.), (new StringBuilder), (StringBuilder/new) or
		// (StringBuffer.) is a fresh string output stream the argument fills at run
		// time, on every backend, never a java:new (which wasm refuses): the class
		// travels for the refusal, a string is written into the stream, an integer is
		// a capacity (ignored), anything else the oracle's no-such-constructor
		// refusal. Two arguments, and a tagged construction naming the host overload,
		// keep the host construction, like any other class.
		assertThat(lowered("(StringBuilder. \"ab\")"))
			.contains("(RONTOLISP::%CLOJURE-STRING-BUILDER-1 \"java.lang.StringBuilder\" \"ab\")")
			.doesNotContain("JAVA:NEW");
		assertThat(lowered("(new StringBuilder 16)"))
			.contains("(RONTOLISP::%CLOJURE-STRING-BUILDER-1 \"java.lang.StringBuilder\" 16)")
			.doesNotContain("JAVA:NEW");
		assertThat(lowered("(StringBuilder/new \"ab\")"))
			.contains("(RONTOLISP::%CLOJURE-STRING-BUILDER-1 \"java.lang.StringBuilder\" \"ab\")")
			.doesNotContain("JAVA:NEW");
		assertThat(lowered("(StringBuffer. \"ab\")"))
			.contains("(RONTOLISP::%CLOJURE-STRING-BUILDER-1 \"java.lang.StringBuffer\" \"ab\")")
			.doesNotContain("JAVA:NEW");
		assertThat(lowered("(new StringBuffer 16)"))
			.contains("(RONTOLISP::%CLOJURE-STRING-BUILDER-1 \"java.lang.StringBuffer\" 16)")
			.doesNotContain("JAVA:NEW");
		assertThat(lowered("(StringBuilder. \"a\" \"b\")")).contains("JAVA:NEW").contains("java.lang.StringBuilder");
		// a tagged construction names the host overload and keeps java:new, on the
		// interpreter and the JVM only
		assertThat(lowered("(^[String] StringBuilder/new \"z\")")).contains("JAVA:NEW")
			.contains("java.lang.StringBuilder");
		assertThat(lowered("(^[int] StringBuilder/new 64)")).contains("JAVA:NEW").contains("java.lang.StringBuilder");
		// where the host is not, no java: operator is left for any of them
		String portable = "(println (str (StringBuilder. \"ab\")))" + " (println (str (new StringBuffer 16)))"
				+ " (println (let [sb (StringBuilder/new \"x\")] (.append sb \"y\") (str sb)))";
		assertThat(Clojure.read(portable, null, null, ClojureFiles.NONE, false)
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"))).doesNotContain("JAVA:");
	}

	@Test
	void aOneArgumentStringWriterLowersToAnEmptyStreamWithTheOracleRefusals() {
		// A one-argument (java.io.StringWriter.), (new java.io.StringWriter) or
		// (java.io.StringWriter/new) is an empty string output stream on every
		// backend, never a java:new (which wasm refuses): a number is a capacity
		// (ignored), anything else the oracle's ClassCastException to Number at
		// run time (nil its NullPointerException) -- a StringWriter has no String
		// constructor, so no shared no-such-constructor helper. Two arguments, and
		// a tagged construction naming the host overload, keep the host
		// construction, like any other class.
		assertThat(lowered("(java.io.StringWriter. 16)")).contains("(RONTOLISP::%CLOJURE-STRING-WRITER-1 16)")
			.doesNotContain("JAVA:NEW");
		assertThat(lowered("(new java.io.StringWriter \"ab\")"))
			.contains("(RONTOLISP::%CLOJURE-STRING-WRITER-1 \"ab\")")
			.doesNotContain("JAVA:NEW");
		assertThat(lowered("(java.io.StringWriter/new 8)")).contains("(RONTOLISP::%CLOJURE-STRING-WRITER-1 8)")
			.doesNotContain("JAVA:NEW");
		assertThat(lowered("(java.io.StringWriter. \"a\" \"b\")")).contains("JAVA:NEW")
			.contains("java.io.StringWriter");
		// a tagged construction names the host overload and keeps java:new, on the
		// interpreter and the JVM only
		assertThat(lowered("(^[int] java.io.StringWriter/new 64)")).contains("JAVA:NEW")
			.contains("java.io.StringWriter");
		// where the host is not, no java: operator is left for any of them
		String portable = "(println (str (java.io.StringWriter. 16)))"
				+ " (println (str (new java.io.StringWriter (long 8))))"
				+ " (println (let [w (java.io.StringWriter/new 4)] (.append w \"y\") (str w)))";
		assertThat(Clojure.read(portable, null, null, ClojureFiles.NONE, false)
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"))).doesNotContain("JAVA:");
	}

	@Test
	void aHashMapConstructionLowersToTheMapItselfWhereTheHostIsNot() {
		// A zero-argument (java.util.HashMap.), (new java.util.HashMap) or
		// (java.util.HashMap/new) is the empty map, and a one-argument construction
		// the map itself (nil the oracle's NullPointerException), where the host is
		// not (wasm), never a java:new (which wasm refuses). A two-argument
		// construction of two number datums is the empty map too
		// (aTwoArgumentHashMapConstructionOfTwoNumbersIsTheEmptyMapWhereTheHostIsNot);
		// a tagged construction naming the host overload keeps the host
		// construction, like any other class; where the host is every spelling keeps
		// java:new, whose mutable object the host tests pin.
		assertThat(wasmLowered("(java.util.HashMap.)")).contains("PLIST-HASH-TABLE").doesNotContain("JAVA:");
		assertThat(wasmLowered("(new java.util.HashMap)")).contains("PLIST-HASH-TABLE").doesNotContain("JAVA:");
		assertThat(wasmLowered("(java.util.HashMap/new)")).contains("PLIST-HASH-TABLE").doesNotContain("JAVA:");
		assertThat(wasmLowered("(let [m {:a 1}] (java.util.HashMap. m))")).doesNotContain("JAVA:")
			.contains("RONTOLISP::%CLOJURE-NULL-POINTER-EXCEPTION");
		assertThat(wasmLowered("(java.util.HashMap. nil)")).contains("RONTOLISP::%CLOJURE-NULL-POINTER-EXCEPTION")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(^[int] java.util.HashMap/new 64)")).contains("JAVA:NEW").contains("java.util.HashMap");
		assertThat(lowered("(java.util.HashMap.)")).contains("JAVA:NEW").contains("java.util.HashMap");
		assertThat(lowered("(java.util.HashMap. {:a 1})")).contains("JAVA:NEW").contains("java.util.HashMap");
		// where the host is not, no java: operator is left for any of them
		String portable = "(println (.get (java.util.HashMap. {:a 1}) :a))"
				+ " (println (.get (new java.util.HashMap {:a 1}) :a))"
				+ " (println (let [m (java.util.HashMap.)] (.size m)))";
		assertThat(wasmLowered(portable)).doesNotContain("JAVA:");
	}

	@Test
	void aTwoArgumentHashMapConstructionOfTwoNumbersIsTheEmptyMapWhereTheHostIsNot() {
		// (java.util.HashMap. capacity loadFactor) of two number datums is the empty
		// map where the host is not (wasm), never a java:new (which wasm refuses):
		// malli's fast-registry builds (HashMap. 1024 0.25) at load. Any other pair,
		// and a tagged construction naming the host overload, keep the host
		// construction, like any other class; where the host is every spelling keeps
		// java:new, whose mutable object the host tests pin.
		assertThat(wasmLowered("(java.util.HashMap. 1024 0.25)")).contains("PLIST-HASH-TABLE").doesNotContain("JAVA:");
		assertThat(wasmLowered("(new java.util.HashMap 16 0.5)")).contains("PLIST-HASH-TABLE").doesNotContain("JAVA:");
		assertThat(wasmLowered("(java.util.HashMap. \"a\" \"b\")")).contains("JAVA:NEW").contains("java.util.HashMap");
		assertThat(wasmLowered("(let [c 16] (java.util.HashMap. c 0.5))")).contains("JAVA:NEW")
			.contains("java.util.HashMap");
		assertThat(wasmLowered("(^[int float] java.util.HashMap/new 64 0.5)")).contains("JAVA:NEW")
			.contains("java.util.HashMap");
		assertThat(lowered("(java.util.HashMap. 1024 0.25)")).contains("JAVA:NEW").contains("java.util.HashMap");
		// where the host is not, no java: operator is left for the portable shape
		assertThat(wasmLowered("(println (.size (java.util.HashMap. 16 0.5)))")).doesNotContain("JAVA:");
	}

	@Test
	void aRandomConstructionLowersToThePortableGeneratorWhereTheHostIsNot() {
		// (java.util.Random. seed), (new java.util.Random seed) and
		// (java.util.Random/new seed) are the portable 48-bit LCG generator where
		// the host is not (wasm) -- data.generators' *rnd* root loads there --
		// never a java:new (which wasm refuses); a zero-argument construction
		// seeds from host entropy, a tagged one keeps the host overload, as does
		// any other count. Where the host is every spelling keeps java:new, whose
		// host object the host tests pin.
		assertThat(wasmLowered("(java.util.Random. 42)")).contains("RONTOLISP::%CLOJURE-RANDOM-NEW")
			.contains("42")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(new java.util.Random 42)")).contains("RONTOLISP::%CLOJURE-RANDOM-NEW")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(java.util.Random/new 42)")).contains("RONTOLISP::%CLOJURE-RANDOM-NEW")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(java.util.Random.)")).contains("RONTOLISP::%CLOJURE-RANDOM-NEW")
			.contains("RONTOLISP::%CLOJURE-RANDOM-ENTROPY")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(java.util.Random. 1 2)")).contains("JAVA:NEW").contains("java.util.Random");
		assertThat(wasmLowered("(^[long] java.util.Random/new 42)")).contains("JAVA:NEW").contains("java.util.Random");
		assertThat(lowered("(java.util.Random. 42)")).contains("JAVA:NEW").contains("java.util.Random");
		assertThat(lowered("(java.util.Random.)")).contains("JAVA:NEW").contains("java.util.Random");
	}

	@Test
	void aRandomDrawLowersToThePortableWorkerWhereTheHostIsNot() {
		// (.nextInt r), (.nextLong r), (.nextFloat r), (.nextDouble r),
		// (.nextBoolean r), (.nextGaussian r) and (.setSeed r s) on a receiver of
		// unknown class answer the portable draw where the host is not (wasm),
		// gated on the receiver being a portable generator; anything else keeps
		// what the call lowered to (the oracle's refusal -- no hostless class has
		// the method). As a value the lambda dispatches the same way. Any other
		// count keeps the host call; where the host is every spelling keeps
		// java:call.
		assertThat(wasmLowered("(defn f [r] (.nextInt r))")).contains("RONTOLISP::%CLOJURE-RANDOM-P")
			.contains("RONTOLISP::%CLOJURE-RANDOM-INT")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(defn f [r] (.nextLong r))")).contains("RONTOLISP::%CLOJURE-RANDOM-LONG")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(defn f [r] (.nextFloat r))")).contains("RONTOLISP::%CLOJURE-RANDOM-FLOAT")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(defn f [r] (.nextDouble r))")).contains("RONTOLISP::%CLOJURE-RANDOM-DOUBLE")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(defn f [r] (.nextBoolean r))")).contains("RONTOLISP::%CLOJURE-RANDOM-BOOLEAN")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(defn f [r] (.nextGaussian r))")).contains("RONTOLISP::%CLOJURE-RANDOM-GAUSSIAN")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(defn f [r s] (.setSeed r s))")).contains("RONTOLISP::%CLOJURE-RANDOM-SET-SEED")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(defn f [r b] (.nextInt r b))")).contains("RONTOLISP::%CLOJURE-RANDOM-INT-BOUND")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(map #(.nextInt %) [(java.util.Random. 42)])"))
			.contains("RONTOLISP::%CLOJURE-RANDOM-INT");
		assertThat(wasmLowered("(defn f [r] (.nextInt r 1 2))")).doesNotContain("RONTOLISP::%CLOJURE-RANDOM-")
			.contains("No matching method nextInt");
		assertThat(lowered("(defn f [r] (.nextInt r))")).contains("JAVA:CALL")
			.doesNotContain("RONTOLISP::%CLOJURE-RANDOM-");
	}

	@Test
	void mathFloorCeilAndLogLowerToTheirWorkersWhereTheHostIsNot() {
		// (Math/floor x), (Math/ceil x) and (Math/log x) of one argument are
		// their portable workers where the host is not (wasm) -- data.generators'
		// uniform reads floor, its geometric reads ceil and log -- never a
		// java:static (which wasm refuses); as a value the arity lambda wraps the
		// same worker. Any other arity stays the host call; where the host is
		// every spelling keeps java:static.
		assertThat(wasmLowered("(Math/floor 2.5)")).contains("RONTOLISP::%CLOJURE-MATH-FLOOR").doesNotContain("JAVA:");
		assertThat(wasmLowered("(Math/ceil 2.5)")).contains("RONTOLISP::%CLOJURE-MATH-CEIL").doesNotContain("JAVA:");
		assertThat(wasmLowered("(Math/log 2)")).contains("RONTOLISP::%CLOJURE-MATH-LOG").doesNotContain("JAVA:");
		assertThat(wasmLowered("(map Math/floor [2.5])")).contains("RONTOLISP::%CLOJURE-MATH-FLOOR");
		assertThat(wasmLowered("(Math/floor 1 2)")).contains("JAVA:STATIC")
			.doesNotContain("RONTOLISP::%CLOJURE-MATH-FLOOR");
		assertThat(lowered("(Math/floor 2.5)")).contains("JAVA:STATIC")
			.doesNotContain("RONTOLISP::%CLOJURE-MATH-FLOOR");
	}

	@Test
	void mathAbsBitCountAndLeadingZerosLowerToTheirWorkersWhereTheHostIsNot() {
		// (Math/abs x), (Long/bitCount x) and (Long/numberOfLeadingZeros x) of
		// one argument are their portable workers where the host is not (wasm)
		// -- test.check's shrink-long reads abs and numberOfLeadingZeros, its
		// mix-gamma bitCount -- never a java:static (which wasm refuses); as a
		// value the arity lambda wraps the same worker. Any other arity stays
		// the host call; where the host is every spelling keeps java:static.
		assertThat(wasmLowered("(Math/abs -5)")).contains("RONTOLISP::%CLOJURE-MATH-ABS").doesNotContain("JAVA:");
		assertThat(wasmLowered("(Long/bitCount 7)")).contains("RONTOLISP::%CLOJURE-LONG-BIT-COUNT")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(Long/numberOfLeadingZeros 7)"))
			.contains("RONTOLISP::%CLOJURE-LONG-NUMBER-OF-LEADING-ZEROS")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(map Math/abs [-5])")).contains("RONTOLISP::%CLOJURE-MATH-ABS");
		assertThat(wasmLowered("(map Long/bitCount [7])")).contains("RONTOLISP::%CLOJURE-LONG-BIT-COUNT");
		assertThat(wasmLowered("(Math/abs 1 2)")).contains("JAVA:STATIC")
			.doesNotContain("RONTOLISP::%CLOJURE-MATH-ABS");
		assertThat(wasmLowered("(Long/bitCount 1 2)")).contains("JAVA:STATIC")
			.doesNotContain("RONTOLISP::%CLOJURE-LONG-BIT-COUNT");
		assertThat(lowered("(Math/abs -5)")).contains("JAVA:STATIC").doesNotContain("RONTOLISP::%CLOJURE-MATH-ABS");
		assertThat(lowered("(Long/bitCount 7)")).contains("JAVA:STATIC")
			.doesNotContain("RONTOLISP::%CLOJURE-LONG-BIT-COUNT");
		assertThat(lowered("(Long/numberOfLeadingZeros 7)")).contains("JAVA:STATIC")
			.doesNotContain("RONTOLISP::%CLOJURE-LONG-NUMBER-OF-LEADING-ZEROS");
	}

	@Test
	void doubleLimitsAnswerThemselvesWhereTheHostIsNot() {
		// Double/MAX_VALUE (and MIN_VALUE, the infinities and NaN) is itself
		// where the host is not (wasm), so test.check's double-generator bound
		// defs load -- never a java:field (which wasm refuses). Where the host
		// is the field read stays untouched; any other member keeps it
		// everywhere.
		assertThat(wasmLowered("(println Double/MAX_VALUE Double/MIN_VALUE)")).contains("1.7976931348623157e308")
			.contains("4.9e-324")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(println Double/POSITIVE_INFINITY Double/NEGATIVE_INFINITY Double/NaN)"))
			.contains("Infinity")
			.contains("-Infinity")
			.contains("NaN")
			.doesNotContain("JAVA:");
		assertThat(lowered("(println Double/MAX_VALUE)")).contains("JAVA:FIELD");
		assertThat(wasmLowered("(println Double/SIZE)")).contains("JAVA:FIELD");
	}

	@Test
	void aThreadLocalProxyLowersToThePortableCellWhereTheHostIsNot() {
		// (proxy [ThreadLocal] [] (initialValue [] ...)) is the portable
		// single-threaded cell where the host is not (wasm), so test.check's
		// seedless next-rng loads -- never a java:subclass (which wasm refuses
		// at call time). .get/.set/.remove on a receiver of unknown class
		// answer the cell's worker there, gated on the receiver being a cell;
		// anything else keeps what the call lowered to. Constructor arguments
		// stay the host's java:subclass; any other method is refused by name.
		// Where the host is the proxy keeps java:subclass and the calls
		// java:call.
		assertThat(wasmLowered("(proxy [ThreadLocal] [] (initialValue [] 42))"))
			.contains("(RONTOLISP::%CLOJURE-THREAD-LOCAL (LAMBDA")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(proxy [ThreadLocal] [] (initialValue [] 42))")).contains("42");
		assertThat(wasmLowered("(defn f [c] (.get c))")).contains("RONTOLISP::%CLOJURE-THREAD-LOCAL-GET")
			.contains("RONTOLISP::%CLOJURE-THREAD-LOCAL-P")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(defn f [c v] (.set c v))")).contains("RONTOLISP::%CLOJURE-THREAD-LOCAL-SET")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(defn f [c] (.remove c))")).contains("RONTOLISP::%CLOJURE-THREAD-LOCAL-REMOVE")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(defn f [c] (.get c 1))")).doesNotContain("RONTOLISP::%CLOJURE-THREAD-LOCAL-")
			.contains("No matching method get");
		assertThat(wasmLowered("(proxy [java.io.File] [\"f\"] (lastModified [] 1))")).contains("JAVA:SUBCLASS")
			.doesNotContain("THREAD-LOCAL");
		assertThatThrownBy(
				() -> Clojure.read("(proxy [ThreadLocal] [] (get [] 1))", null, null, ClojureFiles.NONE, false))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("proxy of ThreadLocal supports initialValue, not get");
		assertThat(lowered("(proxy [ThreadLocal] [] (initialValue [] 42))")).contains("JAVA:SUBCLASS")
			.doesNotContain("THREAD-LOCAL");
		assertThat(lowered("(defn f [c] (.get c))")).contains("JAVA:CALL").doesNotContain("THREAD-LOCAL");
	}

	@Test
	void systemGetPropertyOfALiteralKeyIsNilOrItsDefaultWhereTheHostIsNot() {
		// (System/getProperty k) of a literal key is nil and (System/getProperty k
		// dflt) its default where the host is not (wasm) -- no properties exist
		// there -- never a java:static (which wasm refuses): malli.registry reads
		// its mode and type this way at load. Nil is the oracle's
		// NullPointerException; a computed key stays the host call. Where the host
		// is every spelling keeps java:static.
		assertThat(wasmLowered("(System/getProperty \"a\")")).doesNotContain("JAVA:");
		assertThat(wasmLowered("(println (System/getProperty \"a\" \"d\"))")).contains("\"d\"")
			.doesNotContain("JAVA:STATIC");
		assertThat(wasmLowered("(System/getProperty nil)")).contains("RONTOLISP::%CLOJURE-NULL-POINTER-EXCEPTION")
			.contains("key can't be null")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(let [k \"a\"] (System/getProperty k))")).contains("JAVA:STATIC");
		assertThat(lowered("(System/getProperty \"a\")")).contains("JAVA:STATIC");
		assertThat(lowered("(System/getProperty \"a\" \"d\")")).contains("JAVA:STATIC");
	}

	@Test
	void putAllOnAMapStoresIntoItsTableWhereTheHostIsNot() {
		// (.putAll m s) on a value of unknown class stores s's entries into m's table
		// where the host is not (wasm) -- malli's fast-registry fills its HashMap this
		// way at load -- answering nil; a record or a sorted map takes the oracle's
		// message-less UnsupportedOperationException, anything else its
		// no-such-method refusal. Where the host is the call keeps java:call.
		assertThat(wasmLowered("(defn f [m s] (.putAll m s))")).contains("RONTOLISP::%CLOJURE-HASH-MAP-PUT-ALL")
			.contains("RONTOLISP::%CLOJURE-THROW")
			.contains("java.lang.UnsupportedOperationException")
			.contains("No matching method putAll");
		assertThat(lowered("(defn f [m s] (.putAll m s))")).contains("JAVA:CALL")
			.doesNotContain("RONTOLISP::%CLOJURE-HASH-MAP-PUT-ALL");
	}

	@Test
	void longParseLongOfOneStringIsThePortableReaderWhereTheHostIsNot() {
		// (Long/parseLong s) of one argument is the portable long reader where the
		// host is not (wasm) -- malli's -string->long reads strings through it, so
		// the string-transformer decode runs there -- never a java:static (which
		// wasm refuses); as a value the arity lambda wraps the same worker for its
		// one-argument arm (the four-argument arm stays the host call). Any other
		// arity stays the host call, like every other member; where the host
		// is every spelling keeps java:static, whose object the host tests pin.
		assertThat(wasmLowered("(Long/parseLong \"12\")")).contains("RONTOLISP::%CLOJURE-PARSE-LONG-STATIC")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(map Long/parseLong [\"12\"])")).contains("RONTOLISP::%CLOJURE-PARSE-LONG-STATIC")
			.contains("JAVA:STATIC");
		assertThat(wasmLowered("(Long/parseLong \"1\" 0 1 10)")).contains("JAVA:STATIC")
			.doesNotContain("RONTOLISP::%CLOJURE-PARSE-LONG-STATIC");
		assertThat(lowered("(Long/parseLong \"12\")")).contains("JAVA:STATIC")
			.doesNotContain("RONTOLISP::%CLOJURE-PARSE-LONG-STATIC");
	}

	@Test
	void putOnAMapThrowsTheOraclesUnsupportedOperationWhereTheHostIsNot() {
		// .put of a key and a value on a value of unknown class throws the
		// message-less UnsupportedOperationException construction where the host is
		// not (wasm) -- the map is an unmodifiable java.util.Map there, like the
		// read-only view Java is handed it as where the host is. Any other receiver
		// keeps the oracle's no-such-method refusal, on every backend.
		assertThat(wasmLowered("(defn f [m k v] (.put m k v))")).contains("RONTOLISP::%CLOJURE-THROW")
			.contains("java.lang.UnsupportedOperationException")
			.contains("No matching method put");
		assertThat(lowered("(defn f [m k v] (.put m k v))")).contains("JAVA:CALL")
			.doesNotContain("RONTOLISP::%CLOJURE-THROW");
	}

	@Test
	void predicateValuesLiveInOneProgramWideCell() {
		// a predicate as a value goes through the shared-value worker, which
		// stores the value on first use and answers the stored object after, so
		// two references are = and identical? like the oracle's var roots (and
		// malli's registry, keyed by predicate values, hits): a fresh lambda per
		// site used to make them distinct objects. Calls stay inline, on every
		// backend.
		assertThat(lowered("(pos-int? 1)")).contains("IS-POS-INT").doesNotContain("SHARED-VALUE");
		String shared = lowered("(println (= pos-int? pos-int?))");
		assertThat(shared).contains("RONTOLISP::%CLOJURE-SHARED-VALUE")
			.contains("\"pos-int?\"")
			.contains("RONTOLISP::%CLOJURE-IS-POS-INT");
		assertThat(wasmLowered("(println (= string? string?) (identical? odd? odd?))")).contains("\"string?\"")
			.contains("\"odd?\"")
			.doesNotContain("JAVA:");
	}

	@Test
	void coreFunctionValuesLiveInOneProgramWideCell() {
		// every other site-independent core value goes through the same
		// shared-value worker as the predicates, so two references are = and
		// identical? like the oracle's var roots: a fresh lambda per site used
		// to make (= map map) false. Calls stay inline, on every backend; a
		// verb's transducer arity is shared with the verb. Values snapshotting
		// lower-time state share one cell per snapshot: two references under one
		// snapshot are one object, while references under different snapshots
		// keep distinct cells, so a call through a value still reads its own
		// site's snapshot. default-data-readers is no snapshot -- always the
		// same two shipped reader vars -- so it shares one cell program-wide.
		// The namespace-mapping values (ns-publics, ns-interns, ns-map,
		// ns-refers, ns-aliases, ns-imports) close over nothing but their
		// argument, so they share one cell program-wide with no snapshot key.
		assertThat(lowered("(map inc [1])")).contains("%CLOJURE-MAP").contains("\"inc\"");
		assertThat(lowered("(count [1 2])")).doesNotContain("SHARED-VALUE");
		String shared = lowered("(println (= map map))");
		assertThat(shared).contains("RONTOLISP::%CLOJURE-SHARED-VALUE").contains("\"map\"");
		assertThat(lowered("(println (= filter filter))")).contains("\"filter\"")
			.contains("RONTOLISP::%CLOJURE-XF-FILTER");
		assertThat(lowered("(println (= not= not=))")).contains("\"not=\"");
		assertThat(lowered("(println (= < <))")).contains("\"<\"");
		assertThat(lowered("(println (= inc inc))")).contains("\"inc\"");
		assertThat(lowered("(println (= str str))")).contains("\"str\"");
		assertThat(lowered("(println (= count count))")).contains("\"count\"");
		assertThat(lowered("(println (= comp comp))")).contains("\"comp\"");
		assertThat(lowered("(println (= atom atom))")).contains("\"atom\"");
		assertThat(lowered("(println (= keyword keyword))")).contains("\"keyword\"");
		assertThat(lowered("(println (= clojure.core/map clojure.core/map))")).contains("\"map\"");
		assertThat(lowered("(println (= ns-publics ns-publics))")).contains("RONTOLISP::%CLOJURE-SHARED-VALUE")
			.contains("\"ns-publics\"")
			.doesNotContain("\"ns-publics#");
		assertThat(lowered("(println (= ns-interns ns-interns))")).contains("\"ns-interns\"")
			.doesNotContain("\"ns-interns#");
		assertThat(lowered("(println (= ns-map ns-map))")).contains("\"ns-map\"").doesNotContain("\"ns-map#");
		assertThat(lowered("(println (= ns-refers ns-refers))")).contains("\"ns-refers\"")
			.doesNotContain("\"ns-refers#");
		assertThat(lowered("(println (= ns-aliases ns-aliases))")).contains("\"ns-aliases\"")
			.doesNotContain("\"ns-aliases#");
		assertThat(lowered("(println (= ns-imports ns-imports))")).contains("\"ns-imports\"")
			.doesNotContain("\"ns-imports#");
		assertThat(lowered("(println (= clojure.core/ns-map clojure.core/ns-map))")).contains("\"ns-map\"");
		assertThat(lowered("(println (= = =))")).doesNotContain("SHARED-VALUE");
		assertThat(lowered("(println (= + +))")).doesNotContain("SHARED-VALUE");
		assertThat(lowered("(println (= apply apply))")).doesNotContain("SHARED-VALUE");
		String snapshotting = lowered("(println all-ns all-ns read read find-ns find-ns loaded-libs loaded-libs)");
		assertThat(snapshotting).contains("RONTOLISP::%CLOJURE-SHARED-VALUE")
			.contains("\"all-ns#")
			.contains("\"read#")
			.contains("\"find-ns#")
			.contains("\"loaded-libs#");
		assertThat(snapshotting).doesNotContain("\"all-ns\"")
			.doesNotContain("\"read\"")
			.doesNotContain("\"find-ns\"")
			.doesNotContain("\"loaded-libs\"");
		assertThat(lowered("(println (= default-data-readers default-data-readers))"))
			.contains("RONTOLISP::%CLOJURE-SHARED-VALUE")
			.contains("\"default-data-readers\"")
			.doesNotContain("\"default-data-readers#");
		assertThat(snapshotCells(lowered("(println find-ns find-ns)"), "find-ns")).hasSize(1);
		assertThat(snapshotCells(lowered("(println the-ns) (ns f97-lt) (println the-ns)"), "the-ns")).hasSize(2);
		assertThat(wasmLowered("(println (= map map) (= count count))")).contains("\"map\"")
			.contains("\"count\"")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(println (= ns-publics ns-publics) (= ns-map ns-map) (= ns-imports ns-imports))"))
			.contains("RONTOLISP::%CLOJURE-SHARED-VALUE")
			.contains("\"ns-publics\"")
			.contains("\"ns-map\"")
			.contains("\"ns-imports\"")
			.doesNotContain("JAVA:");
	}

	/**
	 * The distinct snapshot digests a snapshotting core value's cells carry in the
	 * lowered program: one per snapshot the references stood under.
	 * @param lowered the lowered program
	 * @param name the Clojure name
	 * @return the distinct digests
	 */
	private static List<String> snapshotCells(String lowered, String name) {
		return Pattern.compile("\"" + name + "#([0-9a-f]+)\"")
			.matcher(lowered)
			.results()
			.map(match -> match.group(1))
			.distinct()
			.toList();
	}

	@Test
	void typeAnswersTheDesignatorWhereARegistryCouldCollide() {
		// type reads the designator of the host class where the oracle answers
		// one no schema registry may collide with, else class's kind keyword: a
		// registry lookup falls through to (type x) on a miss, so a kind keyword
		// hit the :function/:keyword/... entry -- an unregistered function looped
		// until the stack overflowed instead of refusing with
		// :malli.core/invalid-schema (f96). class itself is untouched, and a
		// dispatch value reads back as its kind.
		String designator = "RONTOLISP::%CLOJURE-TYPE-DESIGNATOR";
		assertThat(lowered("(println (type (fn [x] x)))")).contains(designator);
		assertThat(lowered("(println (type nil))")).contains(designator);
		assertThat(wasmLowered("(println (type \"s\"))")).contains(designator).doesNotContain("JAVA:");
		assertThat(lowered("(println (class (fn [x] x)))")).contains(":C%KEYWORD").doesNotContain(designator);
		assertThat(lowered("(println (type 1))")).contains(designator);
		String multi = lowered("(defmulti m type) (defmethod m String [s] s)");
		assertThat(multi).contains("RONTOLISP::%CLOJURE-DESIGNATOR-KIND");
	}

	@Test
	void typeOfAPatternIsThePatternClass() {
		// type of a pattern is what the oracle answers: the host Pattern class
		// where a host is, its (:C%CLASS name) designator where none is -- the
		// same split a bare class literal lowers to. Only the oracle's answer
		// hits the registry's class-schemas entry, so (m/schema #"a") builds
		// the regex schema (f98); class keeps the :pattern keyword.
		assertThat(lowered("(println (type #\"a\"))")).contains("RONTOLISP::%CLOJURE-RE-PATTERN-P")
			.contains("JAVA:STATIC")
			.contains("forName")
			.contains("java.util.regex.Pattern");
		assertThat(wasmLowered("(println (type #\"a\"))")).contains("RONTOLISP::%CLOJURE-RE-PATTERN-P")
			.contains(":C%CLASS")
			.contains("java.util.regex.Pattern")
			.doesNotContain("JAVA:");
		assertThat(lowered("(println (class #\"a\"))")).contains(":C%KEYWORD")
			.doesNotContain("RONTOLISP::%CLOJURE-TYPE-DESIGNATOR");
	}

	@Test
	void aZeroArgumentObjectConstructionIsAFreshTokenWhereTheHostIsNot() {
		// (Object.) is a fresh uninterned symbol where the host is not (wasm), so
		// dynaload's dynalock defonce loads -- never a java:new (which wasm
		// refuses). Two constructions are never = or identical?, like two host
		// objects. Where the host is every spelling keeps java:new; any other
		// count keeps it everywhere.
		assertThat(wasmLowered("(Object.)")).contains("(GENSYM)").doesNotContain("JAVA:");
		assertThat(wasmLowered("(new Object)")).contains("(GENSYM)").doesNotContain("JAVA:");
		assertThat(wasmLowered("(Object/new)")).contains("(GENSYM)").doesNotContain("JAVA:");
		assertThat(lowered("(Object.)")).contains("JAVA:NEW").contains("java.lang.Object");
	}

	@Test
	void boxedIntegerLimitsAnswerThemselvesWhereTheHostIsNot() {
		// Long/MAX_VALUE (and MIN_VALUE, the Integer/Short/Byte pairs) is itself
		// where the host is not (wasm), so malli's +max-size+ def loads -- never
		// a java:field (which wasm refuses). Where the host is the field read
		// stays untouched; any other member keeps it everywhere.
		assertThat(wasmLowered("(println Long/MAX_VALUE Long/MIN_VALUE)")).contains("9223372036854775807")
			.contains("-9223372036854775808")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(println Integer/MAX_VALUE Integer/MIN_VALUE)")).contains("2147483647")
			.contains("-2147483648")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(println Short/MAX_VALUE Byte/MIN_VALUE)")).contains("32767")
			.contains("-128")
			.doesNotContain("JAVA:");
		assertThat(lowered("(println Long/MAX_VALUE)")).contains("JAVA:FIELD");
		assertThat(wasmLowered("(println Long/SIZE)")).contains("JAVA:FIELD");
	}

	@Test
	void aClassLiteralIsItsDesignatorWhereTheHostIsNot() {
		// a bare class name is its (:C%CLASS name) designator where the host is
		// not (wasm), so a class-keyed map (malli's class-schemas, read at load
		// into the default registry) builds -- never a java:static (which wasm
		// refuses). Where the host is it keeps the forName object.
		assertThat(wasmLowered("(println String)")).contains(":C%CLASS")
			.contains("java.lang.String")
			.doesNotContain("JAVA:");
		assertThat(lowered("(println String)")).contains("JAVA:STATIC").contains("forName");
	}

	@Test
	void dateBuilderChainsThreadTheirMarkerWhereTheHostIsNot() {
		// a DateTimeFormatterBuilder chain evaluates to its marker where the host
		// is not (wasm), so malli's formatter defs load: the construction is the
		// builder marker, each builder call it again, toFormatter the formatter,
		// ofPattern and ZoneId/of their own -- never a java: operator. Where the
		// host is every spelling keeps its host call.
		String chain = "(-> (java.time.format.DateTimeFormatterBuilder.)"
				+ " (.appendPattern \"yyyy\") (.toFormatter))";
		assertThat(wasmLowered(chain)).contains(":C%DATE-FORMATTER-BUILDER")
			.contains(":C%DATE-FORMATTER")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(java.time.format.DateTimeFormatter/ofPattern \"yyyy\")")).contains(":C%DATE-FORMATTER")
			.doesNotContain("JAVA:");
		assertThat(wasmLowered("(java.time.ZoneId/of \"UTC\")")).contains(":C%ZONE-ID").doesNotContain("JAVA:");
		assertThat(wasmLowered("java.time.temporal.ChronoField/MICRO_OF_SECOND")).contains(":C%CHRONO-FIELD")
			.doesNotContain("JAVA:");
		assertThat(lowered(chain)).contains("JAVA:NEW");
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
		assertThat(lowered("(System/nanoTime)")).contains("JAVA:STATIC").doesNotContain("JAVA:FIELD");
		assertThat(lowered("(. System nanoTime)")).contains("JAVA:STATIC").doesNotContain("JAVA:FIELD");
		assertThat(lowered("(Integer/MAX_VALUE)")).contains("JAVA:FIELD").doesNotContain("JAVA:STATIC");
		assertThat(lowered("(. Math PI)")).contains("JAVA:FIELD").doesNotContain("JAVA:STATIC");
		assertThat(lowered("(Math/PI)")).contains("JAVA:FIELD").doesNotContain("JAVA:STATIC");
		// calls with arguments keep the static call, a host false the false object
		// (:java-false), with no wrap of its own
		assertThat(lowered("(Integer/parseInt \"42\")")).contains("JAVA:STATIC");
		assertThat(lowered("(Character/isWhitespace \\a)"))
			.endsWith("(JAVA:STATIC \"java.lang.Character\" \"isWhitespace\" #\\a :JAVA-FALSE)");
		assertThat(lowered("(Boolean/FALSE)")).endsWith("(JAVA:FIELD \"java.lang.Boolean\" \"FALSE\" :JAVA-FALSE)");
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
	void hostCallsEndInJavaFalseWithNoBooleanWrap() {
		// every host call ends in :java-false, so a host false is the false object the
		// call answers itself, whatever the receiver: no T-or-false IF wraps it
		assertThat(lowered("(println (.isEmpty (java.util.ArrayList.)))"))
			.contains("(JAVA:CALL (RONTOLISP::%CLOJURE-IO-HOST |__clojure_0|) \"isEmpty\" :JAVA-FALSE)")
			.doesNotContain("(IF (JAVA:CALL");
		assertThat(lowered("(let [al (java.util.ArrayList.)] (println (.isEmpty al)))"))
			.contains("\"isEmpty\" :JAVA-FALSE)")
			.doesNotContain("(IF (JAVA:CALL");
		assertThat(lowered("(defn check-empty [x] (.isEmpty x))")).contains("\"isEmpty\" :JAVA-FALSE)")
			.doesNotContain("(IF (JAVA:CALL");
		assertThat(lowered("(println (.matches \"abc\" \"x\"))")).contains("\"matches\" \"x\" :JAVA-FALSE)")
			.doesNotContain("(IF (JAVA:CALL");
		// :functional before it when an argument may be a fn, which Java calls through
		// the
		// host call; a literal false argument is the quoted false object, which a site
		// resolves on
		assertThat(lowered("(defn each [l f] (.forEach l f))"))
			.contains("\"forEach\" (RONTOLISP::%CLOJURE-HOST-VALUE (RONTOLISP::%CLOJURE-IO-HOST |c%f|))"
					+ " :FUNCTIONAL :JAVA-FALSE :OCTETS :THROUGH #'RONTOLISP::%CLOJURE-HOST-CALL)");
		assertThat(lowered("(.add (java.util.ArrayList.) false)")).contains("\"add\" '|false| :JAVA-FALSE)");
		assertThat(lowered("(java.util.ArrayList. 3)")).endsWith("(JAVA:NEW \"java.util.ArrayList\" 3 :JAVA-FALSE)");
		// a proxy's body is handed Java's false as the false object, and Java calls it
		// through the proxy call -- a proxy over a class through the subclass call
		assertThat(lowered("(proxy [Runnable] [] (run [] 1))"))
			.endsWith(":JAVA-FALSE :OCTETS :THROUGH #'RONTOLISP::%CLOJURE-PROXY-CALL)");
		assertThat(lowered("(proxy [Thread] [] (run [] 1))"))
			.endsWith(":JAVA-FALSE :OCTETS :THROUGH #'RONTOLISP::%CLOJURE-SUBCLASS-CALL)");
	}

	@Test
	void aFnOrProxyJavaCallsIsCalledThroughTheHostCall() {
		// a fn form, a defn or a local bound to a fn is handed to a member as it is --
		// still a fn the site resolves on -- and the call ends in :octets and :through
		// the
		// host call, which reads each argument Java hands it as a Clojure value (a byte[]
		// a byte array) and answers the value's own object where the method answers one
		assertThat(lowered("(.forEach (java.util.ArrayList.) (fn [x] x))"))
			.contains("\"forEach\" (LAMBDA (|c%x|) " + arity("user/fn") + " |c%x|) :FUNCTIONAL :JAVA-FALSE :OCTETS"
					+ " :THROUGH #'RONTOLISP::%CLOJURE-HOST-CALL)");
		assertThat(lowered("(defn show [b] b) (.forEach (java.util.ArrayList.) show)")).contains(
				"\"forEach\" #'|c%show| :FUNCTIONAL :JAVA-FALSE :OCTETS :THROUGH #'RONTOLISP::%CLOJURE-HOST-CALL)");
		assertThat(lowered("(let [g (fn [b] b)] (.forEach (java.util.ArrayList.) g))"))
			.contains("\"forEach\" |c%g| :FUNCTIONAL :JAVA-FALSE :OCTETS :THROUGH #'RONTOLISP::%CLOJURE-HOST-CALL)");
		// a proxy's body answers as it is and ends in the same markers: Java calls it
		// through the proxy call -- a proxy over a class through the subclass call --
		// which unboxes the answer for the kind Java takes, as the oracle's proxy class
		assertThat(lowered("(proxy [java.util.function.Supplier java.lang.Runnable] [] (get [] [1]) (run [] 2))"))
			.contains("(APPLY (LAMBDA NIL (VECTOR 1)) ")
			.contains("(APPLY (LAMBDA NIL 2) ")
			.doesNotContain("%CLOJURE-HOST-MEMBER")
			.doesNotContain("%CLOJURE-HOST-ANSWER")
			.endsWith(":JAVA-FALSE :OCTETS :THROUGH #'RONTOLISP::%CLOJURE-PROXY-CALL)");
		assertThat(lowered("(proxy [java.io.InputStream] [] (read [b o l] 1) (toString [] \"s\"))"))
			.contains("(APPLY (LAMBDA (|c%this|) \"s\") ")
			.doesNotContain("%CLOJURE-HOST-MEMBER")
			.doesNotContain("%CLOJURE-HOST-ANSWER")
			.endsWith(":JAVA-FALSE :OCTETS :THROUGH #'RONTOLISP::%CLOJURE-SUBCLASS-CALL)");
		// where the host is not (wasm) none of it: a java: form is a call-time error
		String wasm = Clojure
			.read("(.forEach (java.util.ArrayList.) (fn [x] x)) (proxy [java.lang.Runnable] [] (run [] 1))", null, null,
					ClojureFiles.NONE, false)
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"));
		assertThat(wasm).doesNotContain("%CLOJURE-HOST-CALL")
			.doesNotContain("%CLOJURE-PROXY-CALL")
			.doesNotContain(":THROUGH")
			.doesNotContain(":OCTETS");
	}

	@Test
	void aHostArgumentThatMayHoldAValueJavaLacksGoesThroughTheHostValue() {
		// a parameter, a keyword or a call may hold a keyword, a symbol, a set or a
		// record: the library function hands Java a value of it, behind the io family's
		// view of a clojure.java.io value
		assertThat(lowered("(defn put [m k] (.put m k 1))"))
			.contains("\"put\" (RONTOLISP::%CLOJURE-HOST-VALUE (RONTOLISP::%CLOJURE-IO-HOST |c%k|)) 1"
					+ " :FUNCTIONAL :JAVA-FALSE :OCTETS :THROUGH #'RONTOLISP::%CLOJURE-HOST-CALL)");
		assertThat(lowered("(java.util.HashSet. #{1})"))
			.contains("(JAVA:NEW \"java.util.HashSet\" (RONTOLISP::%CLOJURE-HOST-VALUE ");
		assertThat(lowered("(.add (java.util.ArrayList.) :a)")).contains("\"add\" (RONTOLISP::%CLOJURE-HOST-VALUE ");
		// a literal, a fn, a construction and a local bound to one keep the site
		// resolving on their kind, none of them an io value either
		assertThat(lowered("(.add (java.util.ArrayList.) \"a\")")).contains("\"add\" \"a\" :JAVA-FALSE)");
		assertThat(lowered("(.forEach (java.util.ArrayList.) (fn [x] x))")).contains("\"forEach\" (LAMBDA")
			.doesNotContain("%CLOJURE-HOST-VALUE");
		assertThat(lowered("(.add (java.util.ArrayList.) (java.util.ArrayList.))"))
			.contains("\"add\" (JAVA:NEW \"java.util.ArrayList\" :JAVA-FALSE)")
			.doesNotContain("%CLOJURE-HOST-VALUE");
		assertThat(lowered("(let [l (java.util.ArrayList.)] (.add (java.util.ArrayList.) l))"))
			.contains("\"add\" |c%l| :FUNCTIONAL :JAVA-FALSE :OCTETS :THROUGH #'RONTOLISP::%CLOJURE-HOST-CALL)")
			.doesNotContain("%CLOJURE-HOST-VALUE");
		// a local the binding shadows is no longer the construction
		assertThat(lowered("(let [l (java.util.ArrayList.)] (fn [l] (.add (java.util.ArrayList.) l)))"))
			.contains("(RONTOLISP::%CLOJURE-HOST-VALUE (RONTOLISP::%CLOJURE-IO-HOST |c%l|))");
		// where the host is not (wasm) a java: call is a call-time error: no wrap of
		// its own, nor the io family's view of an argument
		assertThat(Clojure.read("(defn cmp [m k] (.compareTo m k))", null, null, ClojureFiles.NONE, false)
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"))).contains("\"compareTo\" |c%k| :FUNCTIONAL :JAVA-FALSE)")
			.doesNotContain("%CLOJURE-HOST-VALUE")
			.doesNotContain("%CLOJURE-IO-HOST");
	}

	@Test
	void aStandardCharsetsFieldAndCharsetForNameOfALiteralLowerToTheCharsetValue() {
		// the value of this front end's own, never a java:field wasm would refuse
		assertThat(lowered("(.getBytes \"a\" java.nio.charset.StandardCharsets/UTF_8)"))
			.contains("(RONTOLISP::%CLOJURE-IO-CHARSET-VALUE \"UTF-8\" \"sun.nio.cs.UTF_8\")")
			.doesNotContain("JAVA:FIELD");
		assertThat(lowered("(java.nio.charset.Charset/forName \"latin1\")"))
			.contains("(RONTOLISP::%CLOJURE-IO-CHARSET-VALUE \"ISO-8859-1\" \"sun.nio.cs.ISO_8859_1\")");
		assertThat(lowered("(java.nio.charset.Charset/forName \"bogus\")"))
			.contains("(RONTOLISP::%CLOJURE-UNSUPPORTED-CHARSET-EXCEPTION \"bogus\")");
		// a String construction reads the value as it is, whatever it was given as
		assertThat(lowered("(String. (.getBytes \"a\") java.nio.charset.StandardCharsets/UTF_8)"))
			.contains("(RONTOLISP::%CLOJURE-STRING-NEW \"java.lang.String\"")
			.contains("(RONTOLISP::%CLOJURE-IO-CHARSET-VALUE \"UTF-8\" \"sun.nio.cs.UTF_8\")")
			.doesNotContain("JAVA:STATIC");
		assertThat(lowered("(defn dec-bytes [b cs] (String. b cs))")).contains("(RONTOLISP::%CLOJURE-STRING-NEW")
			.doesNotContain("%CLOJURE-IO-HOST");
		// anything but a literal is the host's own static, and a java: member named a
		// charset alone is handed the host Charset at once
		assertThat(lowered("(defn fn-name [n] (java.nio.charset.Charset/forName n))")).contains("JAVA:STATIC")
			.doesNotContain("CHARSET-VALUE");
		assertThat(lowered("(java.nio.file.Files/readString (java.nio.file.Path/of \"/x\" (make-array String 0))"
				+ " java.nio.charset.StandardCharsets/UTF_8)"))
			.contains("(JAVA:STATIC \"java.nio.charset.Charset\" \"forName\" \"UTF-8\")")
			.doesNotContain("CHARSET-VALUE");
		// a program naming none keeps the lowering it has
		assertThat(lowered("(.getBytes \"a\" \"UTF-8\")")).doesNotContain("CHARSET");
	}

	@Test
	void arraysLowerToTheCoreArrayForms() {
		assertThat(lowered("(make-array String 3)")).contains("MAKE-ARRAY");
		assertThat(lowered("(make-array String 2 2)")).contains("MAKE-ARRAY").contains("LIST");
		assertThatThrownBy(() -> Clojure.read("(make-array 1 2)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("make-array takes a class name");
		// one index: the byte-array family's aget, aset and alength, which a program
		// making no byte array folds to aref, (setf aref) and array-dimension
		// (ClojureArmsTest); more indexes: the core forms
		assertThat(lowered("(let [a20-v 1] (aget a20-v 0))")).contains("(RONTOLISP::%CLOJURE-AGET |c%a20-v| 0)");
		assertThat(lowered("(let [a20-v 1] (aset a20-v 0 1))")).contains("(RONTOLISP::%CLOJURE-ASET |c%a20-v| 0 1)");
		assertThat(lowered("(let [a20-v 1] (alength a20-v))")).contains("(RONTOLISP::%CLOJURE-ALENGTH |c%a20-v| 0)");
		assertThat(lowered("(let [a20-v 1] (aget a20-v 0 1))")).contains("AREF").doesNotContain("%CLOJURE-AGET");
		assertThat(lowered("(let [a20-v 1] (aset a20-v 0 1 2))")).contains("SETF")
			.contains("AREF")
			.doesNotContain("%CLOJURE-ASET");
		// an array of Byte/TYPE is a byte array
		assertThat(lowered("(make-array Byte/TYPE 3)")).contains("(RONTOLISP::%CLOJURE-BYTE-ARRAY 3)");
	}

	@Test
	void parsersAndTypedArraysLowerToTheirWorkers() {
		// the portable parsers are one worker call each, on every backend
		assertThat(lowered("(parse-long \"12\")")).contains("(RONTOLISP::%CLOJURE-PARSE-LONG");
		assertThat(lowered("(parse-double \"1.5\")")).contains("(RONTOLISP::%CLOJURE-PARSE-DOUBLE");
		assertThat(lowered("(parse-boolean \"true\")")).contains("(RONTOLISP::%CLOJURE-PARSE-BOOLEAN");
		assertThat(lowered("(map parse-long [\"1\"])")).contains("#'RONTOLISP::%CLOJURE-PARSE-LONG-V");
		assertThatThrownBy(() -> Clojure.read("(parse-long \"1\" \"2\")", null))
			.hasMessageContaining("Wrong number of args (2) passed to: clojure.core/parse-long");
		// the object constructors are one worker call each
		assertThat(lowered("(object-array [1])")).contains("(RONTOLISP::%CLOJURE-OBJECT-ARRAY");
		assertThat(lowered("(to-array [1])")).contains("(RONTOLISP::%CLOJURE-TO-ARRAY");
		assertThat(lowered("(into-array [1])")).contains("(RONTOLISP::%CLOJURE-INTO-ARRAY");
		// a class spelling resolves at lowering time to a (:C%CLASS name)
		// designator, so no backend needs the host; a computed type travels
		assertThat(lowered("(into-array String [\"a\"])"))
			.contains("(RONTOLISP::%CLOJURE-INTO-ARRAY-2 (LIST :C%CLASS \"java.lang.String\")");
		assertThat(lowered("(into-array Integer/TYPE [1])"))
			.contains("(RONTOLISP::%CLOJURE-INTO-ARRAY-2 (LIST :C%CLASS \"int\")");
		assertThat(lowered("(let [t2-v String] (into-array t2-v [\"a\"]))"))
			.contains("(RONTOLISP::%CLOJURE-INTO-ARRAY-2 |c%t2-v|");
		assertThatThrownBy(() -> Clojure.read("(into-array)", null))
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/into-array");
		// each primitive constructor is its worker, of two arguments the -2 one
		assertThat(lowered("(int-array [1])")).contains("(RONTOLISP::%CLOJURE-INT-ARRAY");
		assertThat(lowered("(int-array 2 7)")).contains("(RONTOLISP::%CLOJURE-INT-ARRAY-2");
		assertThat(lowered("(long-array [1])")).contains("(RONTOLISP::%CLOJURE-LONG-ARRAY");
		assertThat(lowered("(double-array [1])")).contains("(RONTOLISP::%CLOJURE-DOUBLE-ARRAY");
		assertThat(lowered("(float-array [1])")).contains("(RONTOLISP::%CLOJURE-FLOAT-ARRAY");
		assertThat(lowered("(short-array [1])")).contains("(RONTOLISP::%CLOJURE-SHORT-ARRAY");
		assertThat(lowered("(char-array [1])")).contains("(RONTOLISP::%CLOJURE-CHAR-ARRAY");
		assertThat(lowered("(boolean-array [1])")).contains("(RONTOLISP::%CLOJURE-BOOLEAN-ARRAY");
		assertThat(lowered("(map int-array [[1]])")).contains("#'RONTOLISP::%CLOJURE-INT-ARRAY-V");
		assertThatThrownBy(() -> Clojure.read("(int-array 1 2 3)", null))
			.hasMessageContaining("Wrong number of args (3) passed to: clojure.core/int-array");
		// to-array-2d is one worker call
		assertThat(lowered("(to-array-2d [[1]])")).contains("(RONTOLISP::%CLOJURE-TO-ARRAY-2D");
		assertThat(lowered("(map to-array-2d [[[1]]])")).contains("#'RONTOLISP::%CLOJURE-TO-ARRAY-2D-V");
		assertThatThrownBy(() -> Clojure.read("(to-array-2d)", null))
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/to-array-2d");
		// each checked store is its worker; of more than one index the
		// intermediate levels read through the byte-array family's aget
		assertThat(lowered("(let [a76-v 1] (aset-int a76-v 0 1))"))
			.contains("(RONTOLISP::%CLOJURE-ASET-INT |c%a76-v| 0 1)");
		assertThat(lowered("(let [a76-v 1] (aset-int a76-v 0 1 2))")).contains("(RONTOLISP::%CLOJURE-ASET-INT")
			.contains("(RONTOLISP::%CLOJURE-AGET |c%a76-v| 0)");
		assertThat(lowered("(map aset-char [[\\a]] [0] [\\z])")).contains("#'RONTOLISP::%CLOJURE-ASET-CHAR-V");
		assertThatThrownBy(() -> Clojure.read("(aset-int a76-v 0)", null))
			.hasMessageContaining("Wrong number of args (2) passed to: clojure.core/aset-int");
		// each plural cast is its worker
		assertThat(lowered("(ints [1])")).contains("(RONTOLISP::%CLOJURE-INTS");
		assertThat(lowered("(longs [1])")).contains("(RONTOLISP::%CLOJURE-LONGS");
		assertThat(lowered("(doubles [1])")).contains("(RONTOLISP::%CLOJURE-DOUBLES");
		assertThat(lowered("(floats [1])")).contains("(RONTOLISP::%CLOJURE-FLOATS");
		assertThat(lowered("(shorts [1])")).contains("(RONTOLISP::%CLOJURE-SHORTS");
		assertThat(lowered("(chars [1])")).contains("(RONTOLISP::%CLOJURE-CHARS");
		assertThat(lowered("(booleans [1])")).contains("(RONTOLISP::%CLOJURE-BOOLEANS");
		assertThat(lowered("(map booleans [[true]])")).contains("#'RONTOLISP::%CLOJURE-BOOLEANS-V");
		assertThatThrownBy(() -> Clojure.read("(ints)", null))
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/ints");
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
	void charactersRadixAndDecimalsLowerAsTheirValues() {
		assertThat(lowered("\\a")).contains("#\\a");
		assertThat(lowered("0xFF")).isEqualTo(FALSE_BINDING + "255");
		// a decimal literal is its quoted wrapper, unscaled value and scale
		assertThat(lowered("1M")).isEqualTo(FALSE_BINDING + "'(:C%DECIMAL 1 0)");
		assertThat(lowered("0.10M")).isEqualTo(FALSE_BINDING + "'(:C%DECIMAL 10 2)");
	}

	private static String loweredWithMacros(String source) {
		List<LispVal> forms = Clojure.read(source, null, ClojureMacroTime.create());
		return forms.stream().map(LispVal::print).collect(Collectors.joining("\n"));
	}

	/**
	 * A program lowered with its expanders, printed: a run-time expansion keeps them.
	 */
	private static String loweredWithExpanders(String source) {
		return loweredWithMacros(source + "\n(macroexpand nil)");
	}

	/**
	 * The expander a whole program's {@code C%MACRO-EXPANDER} answers for the index of a
	 * {@code defmacro}, printed.
	 */
	private static String expanderOf(String source, int index) {
		// a run-time expansion carries the expanders
		LispVal table = Clojure.read(source + "\n(macroexpand nil)", null, ClojureMacroTime.create())
			.stream()
			.filter(form -> form.print().startsWith("(DEFUN C%MACRO-EXPANDER "))
			.findFirst()
			.orElseThrow();
		// (defun C%MACRO-EXPANDER (id) (case id (0 expander) ... (t nil)))
		List<LispVal> arms = ((LispCons) ((LispCons) table).toList().get(3)).toList();
		List<LispVal> arm = ((LispCons) arms.get(2 + index)).toList();
		assertThat(arm.get(0).print()).isEqualTo(String.valueOf(index));
		return arm.get(1).print();
	}

	@Test
	void aWholeProgramsTableHoldsAnIndexIntoTheOneFunctionHoldingTheExpanders() {
		// only the run-time expansion calls C%MACRO-EXPANDER, so the compile path's
		// pruner drops every expander of a program whose run-time expansion is dead,
		// and a program expanding nothing at run time carries none
		String macros = """
				(defmacro mu-a [x] `(inc ~x))
				(defmacro mu-b [x] `(dec ~x))""";
		assertThat(loweredWithMacros(macros)).contains("(PROGN (SETQ |c%mu-a%macro| 0) ")
			.doesNotContain("C%MACRO-EXPANDER")
			.doesNotContain("C%MACRO-FN");
		String out = loweredWithMacros(macros + "\n(macroexpand '(mu-a 1))");
		assertThat(out).contains("(PROGN (SETQ |c%mu-a%macro| 0) ")
			.contains("(PROGN (SETQ |c%mu-b%macro| 1) ")
			.contains("(C%MACRO-EXPANDER |cell|)")
			.doesNotContain("FUNCTIONP");
		assertThat(expanderOf("(defmacro mu-a [x] `(inc ~x)) (defmacro mu-b [x] `(dec ~x))", 1))
			.contains("(%UNSPELLED-QUOTE |c%clojure.core/dec|)")
			.doesNotContain("inc");
	}

	@Test
	void defmacroEmitsATableEntryAndRegistersTheExpander() {
		String out = loweredWithExpanders("(defmacro mu-unless [c t] (list 'if c nil t))");
		assertThat(out).contains("PROGN")
			.contains("|c%mu-unless%macro|")
			.contains("LAMBDA")
			.contains("wrong number of arguments passed to macro: mu-unless");
	}

	@Test
	void aRedefinedDefmacroNotifiesBehindItsRegistration() {
		// a redefined defmacro validates and notifies like a cell-less defn: the
		// replaced root is the previous expander, in a statement the strip drops
		// where the program names no watch verb; a first definition emits none
		assertThat(loweredWithExpanders("(defmacro mu-rd [] 1)")).doesNotContain("%CLOJURE-VAR-DEFN-NOTIFY");
		assertThat(loweredWithExpanders("(defmacro mu-rd [] 1) (defmacro mu-rd [] 2)"))
			.contains("(RONTOLISP::%CLOJURE-VAR-DEFN-NOTIFY \"user/mu-rd\"");
		// over a valued or declared root the replaced root is the cell's, as for defn
		assertThat(loweredWithExpanders("(def mu-rd 1) (defmacro mu-rd [] 2)"))
			.contains("(RONTOLISP::%CLOJURE-VAR-DEFN-NOTIFY \"user/mu-rd\"");
		assertThat(loweredWithExpanders("(declare mu-rd) (defmacro mu-rd [] 2)"))
			.contains("(RONTOLISP::%CLOJURE-VAR-DEFN-NOTIFY \"user/mu-rd\"");
		// set-validator! of a literal macro checks the present expander, since the
		// var's site reads no value; above the definition it reads the plain worker
		assertThat(loweredWithMacros("(defmacro mu-sv [] 1) (set-validator! #'mu-sv pos?)"))
			.contains("RONTOLISP::%CLOJURE-SET-VALIDATOR-MACRO");
		assertThat(loweredWithMacros("(set-validator! #'mu-sv pos?) (defmacro mu-sv [] 1)"))
			.doesNotContain("SET-VALIDATOR-MACRO");
		// every defmacro site notes its present expander under its var key, so a
		// computed set-validator! of its var checks the expander without touching
		// the signaling site; a computed target reads the plain worker
		assertThat(loweredWithMacros("(defmacro mu-nt [] 1)"))
			.contains("(RONTOLISP::%CLOJURE-NOTE-MACRO-EXPANDER \"user/mu-nt\"");
		assertThat(loweredWithMacros("(defmacro mu-cv [] 1) ((fn [v] (set-validator! v pos?)) #'mu-cv)"))
			.contains("(RONTOLISP::%CLOJURE-SET-VALIDATOR ")
			.doesNotContain("SET-VALIDATOR-MACRO");
	}

	@Test
	void macroCallsExpandAtLowerTime() {
		String out = loweredWithMacros(
				"(defmacro mu-unless [c t] (list 'if c nil t)) (defn f [c] (mu-unless c 42)) (mu-unless false 7)");
		assertThat(out).contains("|c%mu-unless%macro|");
		// the call lowered to the if over its test, answering 42 for a false one
		assertThat(out).contains("(LET ((|__clojure_").contains("RONTOLISP::%CLOJURE-FALSE").contains(" 42 NIL))");
		// and over the literal false, to the branch it takes
		assertThat(out).endsWith("7");
	}

	@Test
	void aMacroBodyCallsAHelperDefinedAboveIt() {
		// the oracle evaluates each top-level form before the next compiles
		assertThat(loweredWithMacros("(defn mu-helper [x] (list 'inc x)) (defmacro mu-m [x] (mu-helper x)) (mu-m 1)"))
			.endsWith("(RONTOLISP::%CLOJURE-+ 1 1)");
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
	void aMacroBodysEvalChoosesTheCodeThisFrontEndLowers() {
		// data.priority-map's compile-if: a core var with no value here resolves to nil,
		// so the expansion takes the fallback (the oracle, which has it, takes the
		// other);
		// one it has, hash-unordered-coll included, takes the oracle's branch. A refused
		// verb's call-time value resolves too (g30): seque is :has for the same reason.
		String compileIf = "(defmacro mu-ci [test then else] (if (eval test) then else)) ";
		assertThat(loweredWithMacros(compileIf + "(mu-ci (resolve 'clojure.core/add-classpath) :has :lacks)"))
			.endsWith("\"lacks\")");
		assertThat(loweredWithMacros(compileIf + "(mu-ci (resolve 'clojure.core/hash-unordered-coll) :has :lacks)"))
			.endsWith("\"has\")");
		assertThat(loweredWithMacros(compileIf + "(mu-ci (resolve 'clojure.core/inc) :has :lacks)"))
			.endsWith("\"has\")");
		// a class lookup runs on the macro-time JVM, the same on every backend
		assertThat(loweredWithMacros(
				compileIf + "(mu-ci (try (Class/forName \"java.util.ArrayList\") (catch Exception _ nil)) :cls :none)"))
			.endsWith("\"cls\")");
		assertThat(loweredWithMacros(compileIf + "(mu-ci (resolve 'java.util.List) :cls :none)")).endsWith("\"cls\")");
		assertThat(loweredWithMacros(compileIf + "(mu-ci (resolve 'no.such.Klass) :cls :none)")).endsWith("\"none\")");
	}

	@Test
	void aMacroBodysEvalSeesNoLocalOfTheCallSite() {
		// the oracle's eval compiles in the namespace, never in the caller's scope
		assertThatThrownBy(() -> loweredWithMacros(
				"(defmacro mu-ev [form] (eval form)) (let [mu-local 1] (mu-ev (inc mu-local)))"))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("in macro `mu-ev`")
			.hasMessageContaining("unknown name: mu-local");
	}

	@Test
	void evalAndResolveRefuseWhatTheyCannotTake() {
		// (resolve env sym) is (ns-resolve *ns* env sym): a symbol the environment holds
		// resolves to nil
		assertThat(loweredWithMacros("(resolve {} 'inc)")).contains("\"clojure.core/inc\"");
		assertThatThrownBy(() -> loweredWithMacros("(resolve 1 2 3)")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (3) passed to: clojure.core/resolve");
		assertThatThrownBy(() -> loweredWithMacros("(eval 1 2)")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (2) passed to: clojure.core/eval");
		assertThatThrownBy(() -> loweredWithMacros("(defmacro mu-rs [x] (resolve x)) (mu-rs :k)"))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("resolve takes a symbol");
		// a program naming a lowering verb over computed arguments keeps its lowering
		// for the run where its target lowers then, and a computed require spec is one
		// (a local's name was read as a library's until 2026-10-29)
		assertThat(runTimeOf("(eval (list '+ 1 2))", true)).isNotNull();
		assertThat(runTimeOf("(let [n 'clojure.set] (require n))", true)).isNotNull();
		// so is a call spelled through clojure.core's alias or full name (spec.alpha's
		// gen: (require (c/symbol ns)) under (alias 'c 'clojure.core))
		assertThat(runTimeOf("(alias 'c 'clojure.core) (defn dl [s] (require (c/symbol s)))", true)).isNotNull();
		assertThat(runTimeOf("(defn dl [s] (require (clojure.core/symbol s)))", true)).isNotNull();
		assertThat(runTimeOf("(eval (list '+ 1 2))", false)).isNull();
		assertThat(runTimeOf("(require 'clojure.set) (resolve 'inc)", true)).isNull();
	}

	private static @Nullable ClojureRunTime runTimeOf(String source, boolean runTimeTarget) {
		return Clojure
			.program(source, null, ClojureMacroTime.create(), ClojureFiles.NONE, true, ClojureBoundary.NONE,
					runTimeTarget)
			.runTime();
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
		assertThatThrownBy(() -> Clojure.read("(defmacro deref [x] x)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("deref cannot name a macro: the reader spells its own forms with it");
		assertThatThrownBy(() -> Clojure.read("(defmacro s/m [x] x)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("cannot name a macro");
		assertThatThrownBy(() -> Clojure.read("(defmacro mu-env [&form x] x)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("&form cannot name a macro parameter");
		assertThatThrownBy(() -> Clojure.read("(defmacro mu-env2 [x &env] x)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("&env cannot name a macro parameter");
		assertThatThrownBy(() -> Clojure.read("(defmacro)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("defmacro needs a name");
		assertThatThrownBy(() -> Clojure.read("(defmacro mu-short)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("defmacro needs a parameter vector and a body");
		assertThatThrownBy(() -> Clojure.read("(defmacro String. [x] x)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("String. cannot name a macro: it names an interop or qualified form");
		assertThatThrownBy(() -> Clojure.read("(defmacro .foo [x] x)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining(".foo cannot name a macro: it names an interop or qualified form");
	}

	@Test
	void defmacroAcceptsATrailingDotNameWithNoClassBase() {
		// shcloj4's ?. macro (examples/utils.clj): ? is no class spelling, so the
		// name is no construction head and the definition lowers; a call below it
		// expands through the macro table to the instance call
		String out = loweredWithMacros("(defmacro ?. [x form] `(. ~x ~form)) (defn qm-dot [s] (?.\"hi\" toString))");
		assertThat(out).contains("|c%?.%macro|").contains("toString");
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
		String source = """
				(defmacro wrap [& body] `(with-out-str ~@body))
				(defmacro with-out-str [& body] `(do ~@body))
				(defmacro wrap2 [& body] `(with-out-str ~@body))""";
		String wrap = expanderOf(source, 0);
		String wrap2 = expanderOf(source, 2);
		assertThat(wrap).contains("(%UNSPELLED-QUOTE |c%clojure.core/with-out-str|)");
		assertThat(wrap2).contains("(%UNSPELLED-QUOTE |c%user/with-out-str|)").doesNotContain("clojure.core");
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
		assertThat(loweredWithMacros("(defn inc [x] x) (clojure.core/inc 1)")).contains("(RONTOLISP::%CLOJURE-+ 1 1)");
		assertThat(loweredWithMacros("(defn inc [x] x) (map clojure.core/inc [1])")).doesNotContain("#'|c%inc|");
		assertThat(loweredWithMacros("(list clojure.core/*out*)")).contains("(RONTOLISP::%CLOJURE-OUT)");
		assertThatThrownBy(() -> Clojure.read("(clojure.core/nope 1)", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such var: clojure.core/nope");
		assertThatThrownBy(() -> Clojure.read("clojure.core/nope", null, ClojureMacroTime.create()))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such var: clojure.core/nope");
		// bit-shift-left lowers since f67 (was unknown)
		assertThat(lowered("(clojure.core/bit-shift-left 1 2)")).contains("RONTOLISP::%CLOJURE-BIT-SHIFT-LEFT");
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
	void coreMacroNamesAnswerTheirExpanderAsAValue() {
		// g16: a core-macro name with no value arm answers the macro's expander as a
		// value -- the run-time once-expansion over the quoted form, shared per name
		// like every other site-independent core value. Program-defined macros keep
		// their refusal (macrosHaveNoValue).
		assertThat(lowered("(def x when)")).contains("C%MACROEXPAND-1").contains("%CLOJURE-SHARED-VALUE");
		assertThat(lowered("(def x (deref (resolve 'when)))")).contains("C%MACROEXPAND-1");
		assertThat(lowered("(def x @(resolve 'when))")).contains("C%MACROEXPAND-1");
		assertThat(lowered("(def x (deref (resolve 'cond->)))")).contains("C%MACROEXPAND-1");
		assertThat(lowered("(def x clojure.core/when)")).contains("C%MACROEXPAND-1");
		assertThat(lowered("(list when when)")).contains("%CLOJURE-SHARED-VALUE");
		// the g22/g23/g25 slices left value-pinning to g16: their macros lower as
		// values now, with no per-verb behavior change
		assertThat(lowered("(def x amap)")).contains("C%MACROEXPAND-1");
		assertThat(lowered("(def x sync)")).contains("C%MACROEXPAND-1");
		assertThat(lowered("(def x definline)")).contains("C%MACROEXPAND-1");
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
	void bitOperationsLowerToWorkersWithOracleArities() {
		// f67: the bit operations are backlog workers (ClojureCoreLowering), each
		// with its -v value
		assertThat(lowered("(bit-and 1 2 3)")).contains("(RONTOLISP::%CLOJURE-BIT-AND ");
		assertThat(lowered("(bit-and-not 1 2)")).contains("(RONTOLISP::%CLOJURE-BIT-AND-NOT ");
		assertThat(lowered("(bit-not 1)")).contains("(RONTOLISP::%CLOJURE-BIT-NOT ");
		assertThat(lowered("(unsigned-bit-shift-right 1 2)"))
			.contains("(RONTOLISP::%CLOJURE-UNSIGNED-BIT-SHIFT-RIGHT ");
		assertThat(lowered("(map bit-test [1])")).contains("#'RONTOLISP::%CLOJURE-BIT-TEST-V");
		assertThatThrownBy(() -> Clojure.read("(bit-and 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/bit-and");
		assertThatThrownBy(() -> Clojure.read("(bit-not 1 2)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (2) passed to: clojure.core/bit-not");
		assertThatThrownBy(() -> Clojure.read("(bit-test 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/bit-test");
		assertThatThrownBy(() -> Clojure.read("(bit-shift-left 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/bit-shift-left");
	}

	@Test
	void seqAndPrintingBacklogLowersToWorkersWithOracleArities() {
		// f67: reductions, random-sample, bounded-count, partitionv,
		// partitionv-all, splitv-at, replicate, xml-seq and the printing verbs
		assertThat(lowered("(reductions + [1])")).contains("(RONTOLISP::%CLOJURE-REDUCTIONS-2 ");
		assertThat(lowered("(reductions + 0 [1])")).contains("(RONTOLISP::%CLOJURE-REDUCTIONS ");
		assertThat(lowered("(random-sample 0.5 [1])")).contains("(RONTOLISP::%CLOJURE-RANDOM-SAMPLE ");
		assertThat(lowered("(bounded-count 2 [1])")).contains("(RONTOLISP::%CLOJURE-BOUNDED-COUNT-WALK ");
		assertThat(lowered("(partitionv 2 [1])")).contains("(RONTOLISP::%CLOJURE-PARTITIONV ");
		assertThat(lowered("(partitionv-all 2 [1])")).contains("(RONTOLISP::%CLOJURE-PARTITIONV-ALL ");
		assertThat(lowered("(splitv-at 1 [1])")).contains("(RONTOLISP::%CLOJURE-SPLITV-AT ");
		assertThat(lowered("(replicate 2 :a)")).contains("(RONTOLISP::%CLOJURE-REPEAT-N ");
		assertThat(lowered("(xml-seq {:a 1})")).contains("(RONTOLISP::%CLOJURE-XML-SEQ ");
		assertThat(lowered("(printf \"%d\" 1)")).contains("PRINC");
		assertThat(lowered("(char-escape-string \\a)")).contains("(RONTOLISP::%CLOJURE-CHAR-ESCAPE-STRING ");
		assertThat(lowered("(char-name-string \\a)")).contains("(RONTOLISP::%CLOJURE-CHAR-NAME-STRING ");
		assertThat(lowered("(munge \"a\")")).contains("(RONTOLISP::%CLOJURE-MUNGE ");
		assertThat(lowered("(namespace-munge \"a\")")).contains("(RONTOLISP::%CLOJURE-NAMESPACE-MUNGE ");
		assertThat(lowered("(print-simple :a *out*)")).contains("(RONTOLISP::%CLOJURE-PRINT-SIMPLE ");
		assertThat(lowered("(print-dup :a *out*)")).contains("(RONTOLISP::%CLOJURE-PRINT-DUP ");
		assertThat(lowered("(print-ctor :a (fn [o w] o) *out*)")).contains("(RONTOLISP::%CLOJURE-PRINT-CTOR ");
		assertThatThrownBy(() -> Clojure.read("(reductions +)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/reductions");
		assertThatThrownBy(() -> Clojure.read("(partitionv 2)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/partitionv");
		assertThatThrownBy(() -> Clojure.read("(print-simple :a)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/print-simple");
		assertThatThrownBy(() -> Clojure.read("(printf)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/printf");
	}

	@Test
	void namespaceMappingsAnswerWhatTheLoweringKnows() {
		// f67: all-ns, create-ns, remove-ns, loaded-libs, intern, var-set,
		// with-redefs-fn and the mapping verbs lower; anything computed refuses
		// at run time
		assertThat(lowered("(all-ns)")).contains("(RONTOLISP::%CLOJURE-ALL-NS '(");
		assertThat(lowered("(create-ns 'foo)")).contains("(RONTOLISP::%CLOJURE-CREATE-NS ");
		assertThat(lowered("(remove-ns 'foo)")).contains("(RONTOLISP::%CLOJURE-REMOVE-NS ");
		assertThat(lowered("(loaded-libs)")).contains("(RONTOLISP::%CLOJURE-LOADED-LIBS ");
		assertThat(lowered("(intern 'f67-other 'x 1)")).contains("(RONTOLISP::%CLOJURE-INTERN ");
		// of a quoted name in the current namespace, the var the forms below name
		assertThat(lowered("(intern *ns* 'f67-x 1) (inc f67-x)")).doesNotContain("%CLOJURE-INTERN ")
			.contains("RONTOLISP::%CLOJURE-VAR \"user/f67-x\"");
		assertThat(lowered("(def f67-vs 1) (var-set (var f67-vs) 2)")).contains("(RONTOLISP::%CLOJURE-VAR-SET ");
		assertThat(lowered("(with-redefs-fn {} (fn [] 1))")).contains("(RONTOLISP::%CLOJURE-WITH-REDEFS-FN ");
		assertThat(lowered("(def f67-ns-x 1) (get (ns-publics 'user) 'f67-ns-x)"))
			.contains("RONTOLISP::%CLOJURE-VAR \"user/f67-ns-x\"");
		assertThat(lowered("(get (ns-map 'user) 'map)")).contains("RONTOLISP::%CLOJURE-VAR \"clojure.core/map\"");
		assertThat(lowered("(ns-publics (the-ns 'user))")).contains("(RONTOLISP::%CLOJURE-NS-COMPUTED-HERE ");
		assertThat(lowered("(fn [s] (load-string s))")).contains("(RONTOLISP::%CLOJURE-LOAD-STRING-HERE ");
		assertThat(lowered("(load-string \"(prn 1)\")")).contains("RONTOLISP::%CLOJURE-WRITE-DATUM");
		assertThat(lowered("(requiring-resolve 'clojure.string/join)"))
			.contains("RONTOLISP::%CLOJURE-VAR \"clojure.string/join\"");
		assertThatThrownBy(() -> Clojure.read("(intern 'user)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/intern");
		assertThatThrownBy(() -> Clojure.read("(create-ns)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/create-ns");
	}

	@Test
	void checkedArithmeticLowersLikeThePlainVerbs() {
		// f75: inc' dec' +' -' *' are the plain verbs (integers are bignums,
		// so nothing overflows) through their decimal-aware helpers, which a program
		// making no decimal folds back, with the oracle's arity wording
		assertThat(lowered("(inc' 1)")).contains("(RONTOLISP::%CLOJURE-BD-+ 1 1)");
		assertThat(lowered("(dec' 1)")).contains("(RONTOLISP::%CLOJURE-BD-- 1 1)");
		assertThat(lowered("(+' 1 2)")).contains("(RONTOLISP::%CLOJURE-BD-+ 1 2)");
		assertThat(lowered("(-' 5 2)")).contains("(RONTOLISP::%CLOJURE-BD-- 5 2)");
		assertThat(lowered("(*' 2 3)")).contains("(RONTOLISP::%CLOJURE-BD-* 2 3)");
		assertThat(lowered("(map +' [1])")).contains("#'RONTOLISP::%CLOJURE-BD-+");
		assertThat(lowered("(map -' [1])")).contains("#'RONTOLISP::%CLOJURE-BD--");
		assertThat(lowered("(map *' [1])")).contains("#'RONTOLISP::%CLOJURE-BD-*");
		assertThat(lowered("(map inc' [1])")).contains("(LAMBDA");
		assertThat(lowered("(map dec' [1])")).contains("(LAMBDA");
		assertThatThrownBy(() -> Clojure.read("(inc')", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/inc'");
		assertThatThrownBy(() -> Clojure.read("(dec' 1 2)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (2) passed to: clojure.core/dec'");
		assertThatThrownBy(() -> Clojure.read("(-')", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/-'");
	}

	@Test
	void castLowersToAnInstanceTestOrTheComputedWorker() {
		// f75: cast of a named class is the instance test inline, of anything
		// else the computed worker, with the oracle's refusal
		assertThat(lowered("(fn [x] (cast String x))")).contains("STRINGP").contains("Cannot cast ");
		assertThat(lowered("(fn [c x] (cast c x))")).contains("(RONTOLISP::%CLOJURE-CAST ");
		assertThat(lowered("(map cast [String] [\"a\"])")).contains("#'RONTOLISP::%CLOJURE-CAST-V");
		assertThatThrownBy(() -> Clojure.read("(cast String)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/cast");
		assertThatThrownBy(() -> Clojure.read("(cast String \"a\" \"b\")", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (3) passed to: clojure.core/cast");
	}

	@Test
	void refHistoryVerbsLowerToWorkersAndTrackRefs() {
		// f75: the history readers and the min/max setters are workers with -v
		// values; a ref registers where the program names a history verb, and
		// stays the atom's cell anywhere else
		assertThat(lowered("(fn [r] (ref-history-count r))")).contains("(RONTOLISP::%CLOJURE-REF-HISTORY-COUNT ");
		assertThat(lowered("(fn [r] (ref-min-history r 1))")).contains("(RONTOLISP::%CLOJURE-REF-MIN-HISTORY-SET ");
		assertThat(lowered("(fn [r] (ref-max-history r))")).contains("(RONTOLISP::%CLOJURE-REF-MAX-HISTORY ");
		assertThat(lowered("(fn [r] (map ref-max-history [r]))")).contains("#'RONTOLISP::%CLOJURE-REF-MAX-HISTORY-V");
		assertThat(lowered("(def f75-r (ref 1))")).doesNotContain("REF-HISTORY");
		assertThat(lowered("(def f75-r (ref 1)) (ref-history-count f75-r)")).contains("REF-HISTORY-TRACK");
		assertThat(lowered("(def f75-r (ref 1 :min-history 2))")).contains("REF-HISTORY-TRACK");
		assertThatThrownBy(() -> Clojure.read("(fn [r] (ref-history-count r 1))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (2) passed to: clojure.core/ref-history-count");
		assertThatThrownBy(() -> Clojure.read("(fn [r] (ref-min-history r 1 2))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (3) passed to: clojure.core/ref-min-history");
	}

	@Test
	void structLeftoversLowerToWorkersWithOracleArities() {
		// f75: create-struct is the key vector struct reads, accessor its
		// reader, each with a -v value
		assertThat(lowered("(create-struct :a :b)")).contains("(RONTOLISP::%CLOJURE-CREATE-STRUCT ");
		assertThat(lowered("(map create-struct [[:a]])")).contains("#'RONTOLISP::%CLOJURE-CREATE-STRUCT-V");
		assertThat(lowered("(defstruct f75-s :a) (accessor f75-s :a)")).contains("(RONTOLISP::%CLOJURE-ACCESSOR ");
		assertThat(lowered("(defstruct f75-s :a) (map accessor [f75-s f75-s])"))
			.contains("#'RONTOLISP::%CLOJURE-ACCESSOR-V");
		assertThatThrownBy(() -> Clojure.read("(defstruct f75-s :a) (accessor f75-s)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/accessor");
	}

	@Test
	void multimethodIntrospectionTakesTheMultimethodName() {
		// f75: prefers reads the prefers table, remove-all-methods clears both
		// tables, answering like the oracle's; a name no multimethod has is refused
		assertThat(lowered("(defmulti f75-m identity) (prefers f75-m)")).contains("(RONTOLISP::%CLOJURE-PREFERS ");
		assertThat(lowered("(defmulti f75-m identity) (remove-all-methods f75-m)")).contains("CLRHASH");
		assertThatThrownBy(() -> Clojure.read("(defmulti f75-m identity) (prefers f75-n)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such multimethod: f75-n");
		assertThatThrownBy(() -> Clojure.read("(defmulti f75-m identity) (remove-all-methods f75-n)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such multimethod: f75-n");
	}

	@Test
	void protocolIntrospectionReadsTheMethodTables() {
		// f75: extenders answers the tags, find-protocol-impl their rows and
		// find-protocol-method the functions, each with a -v value
		assertThat(lowered("(defprotocol f75-P (f75-m [x])) (extenders f75-P)"))
			.contains("(RONTOLISP::%CLOJURE-EXTENDERS ");
		assertThat(lowered("(defprotocol f75-P (f75-m [x])) (map extenders [f75-P])"))
			.contains("#'RONTOLISP::%CLOJURE-EXTENDERS-V");
		assertThat(lowered("(defprotocol f75-P (f75-m [x])) (find-protocol-impl f75-P nil)"))
			.contains("(RONTOLISP::%CLOJURE-FIND-PROTOCOL-IMPL ");
		assertThat(lowered("(defprotocol f75-P (f75-m [x])) (find-protocol-method f75-P :f75-m nil)"))
			.contains("(RONTOLISP::%CLOJURE-FIND-PROTOCOL-METHOD ");
		assertThat(lowered("(defprotocol f75-P (f75-m [x])) (map find-protocol-method [f75-P])"))
			.contains("#'RONTOLISP::%CLOJURE-FIND-PROTOCOL-METHOD-V");
		assertThatThrownBy(() -> Clojure.read("(defprotocol f75-P (f75-m [x])) (find-protocol-impl f75-P)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/find-protocol-impl");
		assertThatThrownBy(
				() -> Clojure.read("(defprotocol f75-P (f75-m [x])) (find-protocol-method f75-P :f75-m)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (2) passed to: clojure.core/find-protocol-method");
	}

	@Test
	void hostOnlyVarsAreRefusedWhenCalled() {
		// g30: naming a refused verb compiles -- a library merely defining such a
		// function loads -- and only the call signals, in the same words. What no
		// backend answers is still refused naming the reason, never an unknown
		// name; the run-time signal is pinned by clojure-spec
		// refused-verbs-signal-at-call-time.
		assertThat(lowered("(let [x 1] (bean x))"))
			.contains("(ERROR \"bean is not supported yet: host classes are not answered on every backend\")");
		assertThat(lowered("(let [x 1] (supers x))"))
			.contains("(ERROR \"supers is not supported yet: host classes are not answered on every backend\")");
		assertThat(lowered("(clojure-version)"))
			.contains("(ERROR \"clojure-version is not supported yet: no release of Clojure runs here\")");
		assertThat(lowered("(let [x 1] (destructure x))"))
			.contains("(ERROR \"destructure is not supported yet: the destructuring map has no lowering\")");
		assertThat(lowered("(let [f inc] (future-call f))")).contains("(RONTOLISP::%CLOJURE-FUTURE-START")
			.contains("java.util.concurrent.FutureTask");
		assertThat(wasmLowered("(let [f inc] (future-call f))"))
			.contains("(ERROR \"future-call is not supported yet on this backend");
		assertThat(lowered("(let [x 1] (seque x))"))
			.contains("(ERROR \"seque is not supported yet: there is no thread pool on any backend\")");
		assertThat(lowered("(let [e 1 a 2 f inc] (send-via e a f))"))
			.contains("(ERROR \"send-via is not supported yet: agents take no custom executor on any backend\")");
		assertThat(lowered("(let [x 1] (enumeration-seq x))"))
			.contains("(ERROR \"enumeration-seq is not supported yet: host enumerations stop at the host boundary\")");
		assertThat(lowered("(let [x 1] (resultset-seq x))"))
			.contains("(ERROR \"resultset-seq is not supported yet: JDBC stops at the host boundary\")");
		assertThat(lowered("(compile 'foo)"))
			.contains("(ERROR \"compile is not supported yet: namespaces compile ahead of time, never at run time\")");
		assertThat(lowered("(bound-fn* (fn [x] x))")).contains("(RONTOLISP::%CLOJURE-BOUND-FN*");
		assertThat(lowered("(get-thread-bindings)")).contains(
				"(ERROR \"get-thread-bindings is not supported yet: thread bindings are not reified at run time\")");
		assertThat(lowered("(let [s \"x\"] (read+string s))")).contains(
				"(ERROR \"read+string is not supported yet: reading the consumed text needs a position-tracking reader\")");
		assertThat(lowered("(let [x 1] (chunk x))"))
			.contains("(ERROR \"chunk is not supported yet: sequences chunk nowhere here\")");
		assertThat(lowered("(let [x 1] (->Vec x))")).contains(
				"(ERROR \"->Vec is not supported yet: construction of clojure.lang internals is not supported\")");
		assertThat(lowered("(defn g30-never-called [e] (enumeration-seq e))"))
			.contains("(ERROR \"enumeration-seq is not supported yet: host enumerations stop at the host boundary\")");
		assertThat(lowered("enumeration-seq"))
			.contains("(ERROR \"enumeration-seq is not supported yet: host enumerations stop at the host boundary\")");
		assertThat(lowered("bean"))
			.contains("(ERROR \"bean is not supported yet: host classes are not answered on every backend\")");
		assertThat(lowered("(let [x 1] (proxy-mappings x))"))
			.contains("(ERROR \"proxy-mappings is not supported yet: proxy construction is not reified here\")");
	}

	@Test
	void syntaxQuoteQualifiesSplicesAndGensyms() {
		String out = loweredWithExpanders("(defmacro mu-sq [x] `(a ~x ~@'(1 2) s#))");
		assertThat(out).contains("(GENSYM \"s\")").contains("APPEND").contains("(%UNSPELLED-QUOTE |c%user/a|)");
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
		assertThat(lowered("macroexpand-1")).endsWith("#'C%MACROEXPAND-1");
		assertThat(lowered("macroexpand")).endsWith("#'C%MACROEXPAND");
		assertThatThrownBy(() -> Clojure.read("(macroexpand-1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("macroexpand-1 takes one form");
		assertThatThrownBy(() -> Clojure.read("(macroexpand 1 2)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("macroexpand takes one form");
	}

	@Test
	void multiArityMacrosDispatchByCount() {
		String out = loweredWithExpanders(
				"(defmacro mu-ch ([x f] (list '. x f)) ([x f & m] (concat (list 'mu-ch (list '. x f)) m))) (mu-ch \"hi\" toUpperCase length)");
		assertThat(out).contains("wrong number of arguments passed to macro: mu-ch");
		assertThat(out).contains("%CLOJURE-UPPER-CASE");
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
		// itself; on a collection literal it attaches, like the oracle's reader, and a
		// quoted symbol is one carrying it, a quoted list a copy carrying it
		assertThat(lowered("(def v 1) ^:k v")).contains("|c%v|").doesNotContain("META");
		assertThat(lowered("^:k [1]")).contains("%CLOJURE-PUT-META");
		assertThat(lowered("'^:k x")).contains("(RONTOLISP::%CLOJURE-SYMBOL-WITH-META '|c%x|");
		assertThat(lowered("'^:k (1)")).contains("(RONTOLISP::%CLOJURE-PUT-META (COPY-LIST '(1))");
		// a with-meta call attaches at run time
		assertThat(lowered("(with-meta [1] {:a 1})")).contains("%CLOJURE-WITH-META");
		assertThat(lowered("(meta [1])")).contains("%CLOJURE-META");
		assertThatThrownBy(() -> Clojure.read("(with-meta 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/with-meta");
		assertThatThrownBy(() -> Clojure.read("^1 [1]", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Metadata must be Symbol,Keyword,String,Vector or Map");
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
		// and its binder only in a program binding vars through var objects, a site
		// lowered before the first such binding included (the program lowers again)
		assertThat(lowered("(def ^:dynamic *d* 1) #'*d*")).doesNotContain("binder");
		assertThat(lowered("(def ^:dynamic *d* 1) (def v #'*d*) (with-bindings {v 2} *d*)"))
			.contains("(LAMBDA (|c%binder-value| |c%binder-thunk|) (LET* ((|c%*d*| |c%binder-value|)")
			.contains("(RONTOLISP::%CLOJURE-WITH-BINDINGS ");
		assertThat(lowered("(with-bindings* {#'*out* *out*} println 1)"))
			.contains("(LET* ((*STANDARD-OUTPUT* |c%binder-value|)");
		// a push-thread-bindings a try popping in its finally follows is with-bindings
		assertThat(lowered(
				"(def ^:dynamic *d* 1) (defn b [] (push-thread-bindings {#'*d* 2}) (try *d* (finally (pop-thread-bindings))))"))
			.contains("(RONTOLISP::%CLOJURE-WITH-BINDINGS ")
			.doesNotContain("push-thread-bindings");
		assertThatThrownBy(() -> Clojure.read("(defn b [] (push-thread-bindings {}) 1)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("push-thread-bindings is supported only as push-thread-bindings followed by a try");
		assertThatThrownBy(() -> Clojure.read("(pop-thread-bindings)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("pop-thread-bindings is supported only as push-thread-bindings followed by a try");
		// find-var of a literal resolves while the program lowers, of a computed symbol
		// through the run-time library
		assertThat(lowered("(def fv 1) (find-var 'user/fv)")).contains("(RONTOLISP::%CLOJURE-VAR \"user/fv\"");
		assertThat(lowered("(find-var 'fv)")).contains("Symbol must be namespace-qualified");
		assertThat(lowered("(find-var (symbol \"a\" \"b\"))")).contains("(RONTOLISP::%CLOJURE-FIND-VAR ");
		// a var of a library the lowering implements: its root the function value
		assertThat(lowered("#'clojure.string/join")).contains("(RONTOLISP::%CLOJURE-VAR \"clojure.string/join\"");
		assertThat(lowered("(resolve 'clojure.string/nope)")).isEqualTo(FALSE_BINDING + "NIL");
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
		assertThat(lowered("#'when")).contains("(RONTOLISP::%CLOJURE-VAR \"clojure.core/when\" (LAMBDA NIL")
			.contains("C%MACROEXPAND-1")
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
		// all-ns names a zero-argument function value since f67 (was refused)
		assertThat(lowered("#'all-ns")).contains("RONTOLISP::%CLOJURE-ALL-NS '(");
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
		assertThat(lowered("(partition (inc 1) [1])")).containsOnlyOnce("(RONTOLISP::%CLOJURE-+ 1 1)")
			.contains("(RONTOLISP::%CLOJURE-PARTITION ");
		assertThat(lowered("(take-while odd? [1])")).contains("LABELS");
		assertThat(lowered("(interleave [1] [2])"))
			.contains("(RONTOLISP::%CLOJURE-INTERLEAVE (LIST (VECTOR 1) (VECTOR 2)))");
		assertThat(lowered("(interpose 0 [1])")).contains("(RONTOLISP::%CLOJURE-INTERPOSE 0 (VECTOR 1))");
		assertThat(lowered("(zipmap [1] [2])")).contains("GETHASH");
		assertThat(lowered("(sort [2 1])")).contains("SORT").contains("COPY-LIST");
		assertThat(lowered("(group-by odd? [1])")).contains("GETHASH");
		assertThat(lowered("(partition 2 1 [0] [1 2])"))
			.contains("(RONTOLISP::%CLOJURE-PARTITION-PAD 2 1 (VECTOR 0) (VECTOR 1 2))");
		assertThatThrownBy(() -> Clojure.read("(partition 2 1 [0] [1] [2])", null))
			.isInstanceOf(LispReadException.class)
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
				"(RONTOLISP::%CLOJURE-XF-MAPCAT (RONTOLISP::%CLOJURE-SHARED-VALUE \"reverse\" (LAMBDA NIL (LAMBDA (|c%reverse-coll|) (REVERSE (RONTOLISP::%CLOJURE-SEQ-ALL |c%reverse-coll|))))))");
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
		assertThat(lowered("(let [a (atom [1])] (swap! a conj 1))")).contains("RONTOLISP::%CLOJURE-REF-SWAP")
			.contains("REDUCE");
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
		// a key path that is no literal vector is walked at run time, a literal one
		// unrolled
		assertThat(lowered("(defn f [ks] (update-in {:a 1} ks inc))")).contains("RONTOLISP::%CLOJURE-SEQ-ALL");
		assertThat(lowered("(defn f [ks] (assoc-in {} ks 1))")).contains("RONTOLISP::%CLOJURE-SEQ-ALL");
		assertThat(lowered("(defn f [ks] (get-in {} ks :d))")).contains("RONTOLISP::%CLOJURE-SEQ-ALL");
		assertThat(lowered("(get-in {:a {:b 1}} [:a :b] :d)")).doesNotContain("RONTOLISP::%CLOJURE-SEQ-ALL");
		assertThat(lowered("(update-in {:a 1} [] assoc :b 2)")).doesNotContain("RONTOLISP::%CLOJURE-SEQ-ALL");
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
		assertThat(lowered("(def x 1) (instance? Number x)"))
			.contains("(RONTOLISP::%CLOJURE-BD-NUMBER-INSTANCE-P |c%x|)");
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
		// a directive rendered nowhere here refuses when the call runs, after the
		// arguments, so a namespace defining such a call loads (tools.reader)
		assertThat(lowered("(format \"%h\" 1.5)")).contains("(ERROR \"format directive %h is not supported yet\")");
		assertThat(lowered("(format \"%tY\" (inc 1))")).contains("(RONTOLISP::%CLOJURE-+ 1 1)")
			.contains("(ERROR \"format directive %t is not supported yet\")");
		// a flag renders through the conversion's helper; a refusal of Formatter's parse
		// is the run-time refusal of its class, after the arguments
		assertThat(lowered("(format \"%%x%04x\" 10)")).contains("(RONTOLISP::%CLOJURE-FORMAT-INTEGER 10 #\\x \"0\" 4)");
		assertThat(lowered("(format \"%-d\" (inc 1))")).contains("(RONTOLISP::%CLOJURE-+ 1 1)")
			.contains("(RONTOLISP::%CLOJURE-MISSING-FORMAT-WIDTH-EXCEPTION \"%-d\")");
		// a computed format string, and format as a value, parse when the call runs
		assertThat(lowered("(format (str \"%\" \"d\") 1)")).contains("(RONTOLISP::%CLOJURE-FORMAT-V ");
		assertThat(lowered("(map format [\"%d\"] [1])")).contains("#'RONTOLISP::%CLOJURE-FORMAT-V");
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
	void reMatcherAndReFindReadACharSequenceTypeThroughTheLazyMatcherOnlyWhereOneIsStored() {
		// re-matcher and re-find hand their input to the runtime as it is, which reads a
		// CharSequence type only as far as a match needs (the lazy matcher); the text
		// view
		// of the other regex verbs reads it whole. A program storing no CharSequence row
		// folds the lazy matcher's arms and carries none of it.
		String source = "(def m (re-matcher #\"a\" (str \"ab\"))) (println (.lookingAt m) (re-find #\"b\" (str \"ab\"))"
				+ " (re-matches #\"ab\" (str \"ab\")))";
		assertThat(lowered(source)).contains("(RONTOLISP::%CLOJURE-RE-MATCHER (RONTOLISP::%CLOJURE-RE-COMPILE \"a\") ")
			.contains(
					"(RONTOLISP::%CLOJURE-RE-MATCHES (RONTOLISP::%CLOJURE-RE-COMPILE \"ab\") (RONTOLISP::%CLOJURE-CHAR-SEQUENCE-TEXT ");
		assertThat(prunedForms(source)).doesNotContain("%CLOJURE-RE-LAZY").doesNotContain("%CLOJURE-CHAR-SEQUENCE");
		String typed = prunedForms("(deftype S [] CharSequence (length [_] 2) (charAt [_ i] \\a)"
				+ " (subSequence [_ a b] nil)) " + source);
		assertThat(typed).contains("(DEFUN RONTOLISP::%CLOJURE-RE-LAZY-SEARCH ")
			.contains("(DEFUN RONTOLISP::%CLOJURE-RE-LAZY-MATCHER ");
	}

	@Test
	void reFormsLowerToTheRegexRuntime() {
		assertThat(lowered("(re-find #\"a+\" \"aaab\")")).contains("RONTOLISP::%CLOJURE-RE-FIND");
		assertThat(lowered("(re-find (re-matcher #\"a\" \"a\"))")).contains("RONTOLISP::%CLOJURE-RE-FIND-M");
		assertThat(lowered("(re-seq #\"a\" \"a\")")).contains("RONTOLISP::%CLOJURE-RE-SEQ");
		assertThat(lowered("(re-matches #\"a\" \"a\")")).contains("RONTOLISP::%CLOJURE-RE-MATCHES");
		assertThat(lowered("(re-groups (re-matcher #\"a\" \"a\"))")).contains("RONTOLISP::%CLOJURE-RE-GROUPS");
		// a literal source parses as a regex literal's; a computed one at run time
		assertThat(lowered("(re-pattern \"a\")")).contains("(RONTOLISP::%CLOJURE-RE-COMPILE \"a\")");
		assertThat(lowered("(re-pattern \"\\\\pL\")")).contains("(RONTOLISP::%CLOJURE-RE-COMPILE-UNICODE \"\\\\pL\")");
		assertThat(lowered("(defn f [s] (re-pattern s))")).contains("(RONTOLISP::%CLOJURE-RE-PATTERN ");
		assertThat(lowered("(re-find #\"\\ba\" \"a\")")).contains("(RONTOLISP::%CLOJURE-RE-COMPILE-BOUND ");
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
		assertThatThrownBy(() -> Clojure.read("(ns t (:require [clojure.inspector :as i]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown namespace: clojure.inspector");
	}

	@Test
	void aClojureCoreAliasSpellsCoreNames() {
		// clojure.core loads nothing: its alias spells a core name wherever
		// clojure.core/name does, a var the namespace lacks is the oracle's refusal
		assertThat(lowered("(ns t (:require [clojure.core :as c])) (c/inc 1)")).contains("(RONTOLISP::%CLOJURE-+ 1 1)");
		assertThat(lowered("(ns t (:require [clojure.core :as c])) (defn inc [x] x) (map c/inc [1])"))
			.contains("%CLOJURE-MAP")
			.contains("(RONTOLISP::%CLOJURE-BD-+ ");
		assertThatThrownBy(() -> Clojure.read("(ns t (:require [clojure.core :as c])) (c/nope 1)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such var: c/nope");
		assertThatThrownBy(() -> Clojure.read("(ns t (:require [clojure.core :refer [nope]]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("nope does not exist");
	}

	@Test
	void metadataNamesDefinitionsWithoutAffectingThem() {
		assertThat(lowered("(defn- f [x] x)")).contains("(DEFUN |c%f| (|c%x|) " + arity("user/f") + " |c%x|)");
		assertThat(lowered("(def ^:private x 1)")).contains(
				"(RONTOLISP::%CLOJURE-VAR-STORE (LAMBDA NIL (IF (BOUNDP '|c%x|) |c%x| (RONTOLISP::%CLOJURE-UNBOUND \"user/x\"))) (LAMBDA (|__clojure_new_root|) (SETQ |c%x| |__clojure_new_root|)) 1 \"user/x\")");
		assertThat(lowered("(def ^:dynamic *d* 1)"))
			.contains("(DEFPARAMETER |c%*d*| (RONTOLISP::%CLOJURE-VAR-VALIDATED 1 \"user/*d*\"))")
			.contains("(RONTOLISP::%CLOJURE-VAR-NOTIFY-STORED \"user/*d*\" |c%*d*|)")
			.contains("(DEFPARAMETER |c%*d*%bound-depth| 0)");
		assertThat(lowered("(defn ^:private f [x] x)")).contains("(DEFUN |c%f| (|c%x|) " + arity("user/f") + " |c%x|)");
		assertThat(lowered("(defn f {:private true} [x] x)"))
			.contains("(DEFUN |c%f| (|c%x|) " + arity("user/f") + " |c%x|)");
		assertThat(lowered("(def x \"a docstring\" 1)")).contains(
				"(RONTOLISP::%CLOJURE-VAR-STORE (LAMBDA NIL (IF (BOUNDP '|c%x|) |c%x| (RONTOLISP::%CLOJURE-UNBOUND \"user/x\"))) (LAMBDA (|__clojure_new_root|) (SETQ |c%x| |__clojure_new_root|)) 1 \"user/x\")");
		assertThat(lowered("(def x \"a docstring\")")).contains(
				"(RONTOLISP::%CLOJURE-VAR-STORE (LAMBDA NIL (IF (BOUNDP '|c%x|) |c%x| (RONTOLISP::%CLOJURE-UNBOUND \"user/x\"))) (LAMBDA (|__clojure_new_root|) (SETQ |c%x| |__clojure_new_root|)) \"a docstring\" \"user/x\")");
		assertThat(lowered("(def x {:a 1})")).contains("HASH-TABLE");
		assertThat(lowered("(defn f [^String x] x)")).contains("(DEFUN |c%f| (|c%x|) " + arity("user/f") + " |c%x|)");
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
		assertThat(lowered("(defonce d 1)")).contains("BOUNDP")
			.contains(
					"(RONTOLISP::%CLOJURE-VAR-STORE (LAMBDA NIL (IF (BOUNDP '|c%d|) |c%d| (RONTOLISP::%CLOJURE-UNBOUND \"user/d\"))) (LAMBDA (|__clojure_new_root|) (SETQ |c%d| |__clojure_new_root|)) 1 \"user/d\")");
		assertThat(lowered("(defonce d)")).contains(
				"(RONTOLISP::%CLOJURE-VAR-STORE (LAMBDA NIL (IF (BOUNDP '|c%d|) |c%d| (RONTOLISP::%CLOJURE-UNBOUND \"user/d\"))) (LAMBDA (|__clojure_new_root|) (SETQ |c%d| |__clojure_new_root|)) NIL \"user/d\")");
		assertThat(lowered("(defstruct s :a :b)")).contains("(SETQ |c%s| (VECTOR");
		assertThat(lowered("(def s [:a]) (struct s 1)")).contains("GETHASH").contains("DOTIMES");
		assertThat(lowered("(def s [:a]) (struct-map s :a 1)")).contains("GETHASH").contains("DOTIMES");
		assertThatThrownBy(() -> Clojure.read("(defstruct s \"a\")", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("defstruct takes keyword keys");
	}

	@Test
	void stmVerbsLowerOverTheAtomCell() {
		assertThat(lowered("(ref 0)")).contains(":C%ATOM");
		assertThat(lowered("(ref 0 :validator odd?)")).contains("(RONTOLISP::%CLOJURE-REF-MAKE 0");
		// a transaction is its body's thunk run by the STM runtime, the thunk its
		// own zero-arity recur target like the oracle's (fn [] body)
		assertThat(lowered("(dosync 1)")).contains("(RONTOLISP::%CLOJURE-STM-RUN (LAMBDA NIL 1))");
		// sync is the same transaction with its flags dropped unevaluated, a
		// nested sync joining the running one like a nested dosync
		assertThat(lowered("(sync nil 1)")).contains("(RONTOLISP::%CLOJURE-STM-RUN (LAMBDA NIL 1))");
		assertThatThrownBy(() -> Clojure.read("(sync)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/sync");
		// io! is its body behind the in-transaction check, in the oracle's wording
		assertThat(lowered("(io! 1)")).contains("(IF RONTOLISP::%CLOJURE-STM-LOG").contains("I/O in transaction");
		// with-loading-context is its body as-is: loading has no separate context
		assertThat(lowered("(with-loading-context 1)")).isEqualTo(FALSE_BINDING + "1");
		assertThatThrownBy(() -> Clojure.read("(loop [i 0] (dosync (if (< i 3) (recur (inc i)) i)))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("wrong number of arguments passed to recur: expected 0, got 1");
		assertThat(lowered("(def r (ref 0)) (alter r inc)")).contains("(RONTOLISP::%CLOJURE-STM-REQUIRE)")
			.contains("RONTOLISP::%CLOJURE-STM-WRITE");
		// commute defers to commit (g37), so it names the commute worker, not the write
		assertThat(lowered("(def r (ref 0)) (commute r inc)")).contains("RONTOLISP::%CLOJURE-STM-COMMUTE")
			.doesNotContain("RONTOLISP::%CLOJURE-STM-WRITE");
		assertThat(lowered("(def r (ref 0)) (ref-set r 1)")).contains("RONTOLISP::%CLOJURE-STM-WRITE");
		assertThat(lowered("(def r (ref 0)) (ensure r)")).contains("(RONTOLISP::%CLOJURE-STM-REQUIRE)");
		// reads go through the tracking worker and commute defers to commit (g37)
		assertThat(lowered("(dosync 1) (def r (ref 0)) (dosync @r)")).contains("RONTOLISP::%CLOJURE-STM-DEREF");
		assertThat(lowered("(dosync 1) (def r (ref 0)) (dosync (alter r inc))"))
			.contains("RONTOLISP::%CLOJURE-STM-DEREF")
			.contains("RONTOLISP::%CLOJURE-STM-WRITE");
		assertThat(lowered("(dosync 1) (def r (ref 0)) (dosync (ensure r))"))
			.contains("RONTOLISP::%CLOJURE-STM-ENSURE");
		assertThat(lowered("(dosync 1) (def r (ref 0)) (dosync (commute r inc))"))
			.contains("RONTOLISP::%CLOJURE-STM-COMMUTE")
			.doesNotContain("RONTOLISP::%CLOJURE-STM-WRITE");
		assertThat(lowered("(agent 0)")).contains("(RONTOLISP::%CLOJURE-AGENT-MAKE 0 NIL NIL NIL)");
		// options are read at run time, like the oracle's (apply hash-map options)
		assertThat(lowered("(agent 0 :error-mode :continue)")).contains("(RONTOLISP::%CLOJURE-AGENT-V 0");
		assertThat(lowered("(def a (agent 0)) (restart-agent a 1 :clear-actions true)"))
			.contains("(RONTOLISP::%CLOJURE-AGENT-RESTART |c%a| 1 (LIST ");
		assertThat(lowered("(def a (agent 0)) (agent-error a)")).contains("(RONTOLISP::%CLOJURE-AGENT-ERROR |c%a|)");
		assertThat(lowered("(map error-mode [])")).contains("RONTOLISP::%CLOJURE-AGENT-ERROR-MODE");
		assertThatThrownBy(() -> Clojure.read("(def a (agent 0)) (restart-agent a)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/restart-agent");
		// a failed agent's refusal carries its error as the cause: the exception runtime
		assertThat(lowered("(def a (agent 0)) (send a inc)")).contains("C%E-NEW");
		assertThat(lowered("(def a (agent 0)) (send a inc)")).contains("C%AGENT")
			.contains("RONTOLISP::%CLOJURE-AGENT-SEND");
		assertThat(lowered("(def a (agent 0)) (send-off a inc)")).contains("C%AGENT");
		assertThat(lowered("(def a (agent 0)) (await a)")).contains("(RONTOLISP::%CLOJURE-AGENT-AWAIT ")
			.contains("Can't await in agent action");
		assertThat(lowered("(shutdown-agents)")).contains("NIL");
		assertThat(lowered("alter")).contains("LAMBDA");
		assertThatThrownBy(() -> Clojure.read("(ref 0 :history 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("ref option :history is not supported yet");
		assertThatThrownBy(() -> Clojure.read("(dosync (alter r inc))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: r");
	}

	@Test
	void emptyNodeIsTheEmptyVectorAndStackTraceElementVecReadsAnElement() {
		// g26: EMPTY-NODE, the oracle's persistent-vector node, is the empty vector
		// here -- the value is one vector shared program-wide, a call reads like one
		// of [], and a bare call is refused at lower time like ([])
		assertThat(lowered("EMPTY-NODE")).contains("(RONTOLISP::%CLOJURE-SHARED-VALUE \"EMPTY-NODE\"")
			.contains("(VECTOR)");
		assertThat(lowered("clojure.core/EMPTY-NODE")).contains("(RONTOLISP::%CLOJURE-SHARED-VALUE \"EMPTY-NODE\"");
		assertThat(lowered("(EMPTY-NODE :k)")).contains("(RONTOLISP::%CLOJURE-NTH (VECTOR)");
		assertThat(lowered("(EMPTY-NODE :k :d)")).contains("(RONTOLISP::%CLOJURE-NTH (VECTOR)");
		assertThatThrownBy(() -> Clojure.read("(EMPTY-NODE)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("a collection as a function takes a key and an optional default");
		assertThatThrownBy(() -> Clojure.read("(EMPTY-NODE 1 2 3)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("a collection as a function takes a key and an optional default");
		// StackTraceElement->vec is the oracle's [class method file line] vector of
		// a host element: the nil and the instance tests first, then the four host
		// reads; the arity is checked at lower time in the oracle's wording, and the
		// value is a one-argument lambda over the same read
		assertThat(lowered("(defn f [e] (StackTraceElement->vec e))"))
			.contains("(RONTOLISP::%CLOJURE-HOST-METHOD |__clojure_")
			.contains("getClassName")
			.contains("getMethodName")
			.contains("getFileName")
			.contains("getLineNumber")
			.contains("RONTOLISP::%CLOJURE-SYMBOL-1")
			.contains("cannot be cast to class java.lang.StackTraceElement");
		assertThat(lowered("(map StackTraceElement->vec [])"))
			.contains("(RONTOLISP::%CLOJURE-SHARED-VALUE \"StackTraceElement->vec\"")
			.contains("(LAMBDA (|c%stack-trace-element|)");
		assertThatThrownBy(() -> Clojure.read("(StackTraceElement->vec)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/StackTraceElement->vec");
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
			.contains("(DEFPARAMETER |c%slow| (RONTOLISP::%CLOJURE-VAR-VALIDATED #'|c%slow| \"user/slow\"))")
			.contains("(RONTOLISP::%CLOJURE-VAR-NOTIFY-STORED \"user/slow\" |c%slow|)")
			.contains("(DEFPARAMETER |c%slow%bound-depth| 0)")
			.contains("(FUNCALL |c%slow| 21)");
		assertThat(lowered("(defn ^:dynamic madd ([x] 1) ([x y] 2)) (madd 1 2)")).contains("(DEFUN |c%madd%1|")
			.contains("(DEFUN |c%madd%2|")
			.contains("(DEFUN |c%madd|")
			.contains("(DEFPARAMETER |c%madd| (RONTOLISP::%CLOJURE-VAR-VALIDATED #'|c%madd| \"user/madd\"))")
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
	void aDelayIsAMemoCellOverItsBodysThunkAndForceTheFamilysHelper() {
		// the body is a zero-argument lambda (its own recur target), handed to the
		// constructor that makes the delay family's value; force is one helper call
		assertThat(lowered("(delay (println 1) 2)")).contains("(RONTOLISP::%CLOJURE-DELAY (LAMBDA NIL ");
		assertThat(lowered("(force 1)")).contains("(RONTOLISP::%CLOJURE-FORCE 1)");
		assertThat(lowered("(map force [1])")).contains("#'RONTOLISP::%CLOJURE-FORCE-V");
		assertThatThrownBy(() -> Clojure.read("(force)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/force");
	}

	@Test
	void futuresAndPromisesLowerToHostThreadsAndRefuseOnlyWithoutThem() {
		// g35: future/future-call/promise/deliver lower to the host-thread
		// machinery (a host FutureTask around the conveyed function, a promise
		// cell) where threads exist; on wasm, single-threaded by construction,
		// only the call signals, in the same words (g30's naming shape)
		assertThat(lowered("(future 1)")).contains("(RONTOLISP::%CLOJURE-FUTURE-START")
			.contains("java.util.concurrent.FutureTask")
			.contains("RONTOLISP::%CLOJURE-BOUND-FN*");
		assertThat(lowered("(future-call inc)")).contains("(RONTOLISP::%CLOJURE-FUTURE-START")
			.contains("java.util.concurrent.FutureTask");
		assertThat(lowered("(promise)")).contains("(RONTOLISP::%CLOJURE-PROMISE)");
		assertThat(lowered("(let [p 1] (deliver p 1))")).contains("(RONTOLISP::%CLOJURE-DELIVER")
			.contains("deliver needs a promise");
		// as values: the same constructors and stores over a lambda
		assertThat(lowered("(map future-call [inc])")).contains("RONTOLISP::%CLOJURE-FUTURE-START");
		assertThat(lowered("(map promise [1])")).contains("(RONTOLISP::%CLOJURE-PROMISE)");
		assertThat(lowered("deliver")).contains("(RONTOLISP::%CLOJURE-DELIVER");
		assertThat(wasmLowered("(future 1)")).contains("(ERROR \"future is not supported yet on this backend");
		assertThat(wasmLowered("(future-call inc)"))
			.contains("(ERROR \"future-call is not supported yet on this backend");
		assertThat(wasmLowered("(promise)")).contains("(ERROR \"promise is not supported yet on this backend");
		assertThat(wasmLowered("(let [p 1] (deliver p 1))"))
			.contains("(ERROR \"deliver is not supported yet on this backend");
		assertThat(wasmLowered("deliver")).contains("(ERROR \"deliver is not supported yet on this backend");
		// arities still refuse at lower time, in the oracle's words
		assertThatThrownBy(() -> Clojure.read("(future-call)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/future-call");
		assertThatThrownBy(() -> Clojure.read("(promise 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/promise");
		assertThatThrownBy(() -> Clojure.read("(deliver (promise))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/deliver");
		// pcalls/seque/await1 stay refused everywhere: no thread pool for them
		assertThat(lowered("(defn g35-never-called-pcalls [f] (pcalls f))"))
			.contains("(ERROR \"pcalls is not supported yet: there is no thread pool on any backend\")");
		assertThat(wasmLowered("(seque 1)"))
			.contains("(ERROR \"seque is not supported yet: there is no thread pool on any backend\")");
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
		assertThat(lowered("(partition-all (inc 1) [1 2 3])")).containsOnlyOnce("(RONTOLISP::%CLOJURE-+ 1 1)")
			.contains("RONTOLISP::%CLOJURE-PARTITION-ALL");
		// pmap runs one future per element, dereferenced in order with the
		// oracle's lookahead, so the printed seq is the sequential one's; on
		// wasm it stays the sequential map
		assertThat(lowered("(pmap inc [1])")).contains("RONTOLISP::%CLOJURE-FUTURE-START")
			.contains("RONTOLISP::%CLOJURE-PMAP-WINDOW")
			.contains("RONTOLISP::%CLOJURE-DOALL");
		assertThat(wasmLowered("(pmap inc [1])")).contains("RONTOLISP::%CLOJURE-MAP").doesNotContain("PMAP");
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
		assertThat(lowered("(java.io.StringReader. \"x\")"))
			.contains("(JAVA:NEW \"java.io.StringReader\" \"x\" :JAVA-FALSE)");
	}

	@Test
	void theHashVerbsCallTheirWorkersAndATypesHasheqStoresInItsOwnFamily() {
		assertThat(lowered("(hash [1]) (hash-combine 1 :a) (mix-collection-hash 1 2) (hash-ordered-coll [])"))
			.contains("(RONTOLISP::%CLOJURE-HASHEQ (VECTOR 1))")
			.contains("(RONTOLISP::%CLOJURE-HASH-COMBINE 1 (LIST :C%KEYWORD \"a\"))")
			.contains("(RONTOLISP::%CLOJURE-MIX-COLLECTION-HASH 1 2)")
			.contains("(RONTOLISP::%CLOJURE-HASH-ORDERED-COLL (VECTOR))");
		assertThat(lowered("(map hash [1]) (map hash-unordered-coll [#{}])")).contains("#'RONTOLISP::%CLOJURE-HASHEQ-V")
			.contains("#'RONTOLISP::%CLOJURE-HASH-UNORDERED-COLL-V");
		assertThatThrownBy(() -> Clojure.read("(hash)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (0) passed to: clojure.core/hash");
		assertThatThrownBy(() -> Clojure.read("(mix-collection-hash 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: clojure.core/mix-collection-hash");
		assertThatThrownBy(() -> Clojure.read("(hash-ordered-coll [] [])", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (2) passed to: clojure.core/hash-ordered-coll");
		// hash reads an IHashEq row, so it is a family of its own, not a marker
		assertThat(lowered("(deftype H [] clojure.lang.IHashEq (hasheq [_] 1))"))
			.contains("(RONTOLISP::%CLOJURE-HASHEQ-ROW (LIST :C%KEYWORD \"H\") '(\"clojure.lang.IHashEq\")")
			.doesNotContain("%CLOJURE-MARKER-ROW");
		// .hashCode of a receiver of no known class is the hashCode of any value, on
		// every backend; a known host class keeps the host's
		assertThat(lowered("(fn [x] (.hashCode x))")).contains("(RONTOLISP::%CLOJURE-JAVA-HASH ")
			.doesNotContain("JAVA:CALL");
		assertThat(lowered("(.hashCode (java.util.ArrayList.))")).contains("\"hashCode\"")
			.doesNotContain("%CLOJURE-JAVA-HASH");
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
		assertThatThrownBy(() -> Clojure.read("(halt-when odd? + -)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (3) passed to: clojure.core/halt-when");
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
		assertThat(lowered("(fn [x] (number? x))")).contains("(RONTOLISP::%CLOJURE-BD-NUMBERP ");
		assertThat(lowered("(fn [x] (qualified-keyword? x))")).contains("(RONTOLISP::%CLOJURE-IS-QUALIFIED ");
		// a UUID or a host one: the UUID family's alias of the host test, which a
		// program making no UUID calls in its place
		assertThat(lowered("(fn [x] (uuid? x))")).contains("(RONTOLISP::%CLOJURE-IS-UUID ")
			.contains("\"java.util.UUID\"");
		assertThat(lowered("(map map? [1])")).contains("LAMBDA").contains("(RONTOLISP::%CLOJURE-IS-MAP-TYPE ");
		// a kind no value here has: false, the argument still evaluated
		assertThat(lowered("(chunked-seq? [1])")).contains("PROGN").contains("RONTOLISP::%CLOJURE-FALSE");
		// a delay: the delay family's test, (progn x false) again where no delay is made
		assertThat(lowered("(delay? [1])")).contains("(RONTOLISP::%CLOJURE-IS-DELAY (VECTOR 1) ")
			.contains("RONTOLISP::%CLOJURE-FALSE");
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
		assertThat(lowered("(sorted-set-by > 3 1)"))
			.contains("(RONTOLISP::%CLOJURE-SORTED-MAKE T (RONTOLISP::%CLOJURE-SHARED-VALUE \">\"");
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

	@Test
	void aProgramMakingNoDecimalCarriesNoneOfItsArms() {
		// the numeric verbs lower to the decimal-aware helpers; a program making no
		// decimal has them renamed back to the plain operators and every library arm
		// folded, so neither its own forms nor the library it splices name a decimal
		String plain = "(defn f [x y] [(/ x y) (quot x y) (rem x y) (mod x y) (max x y) (min x y) (abs x) (< x y)"
				+ " (>= x y) (== x y) (zero? x) (pos? x) (neg? x) (number? x) (rational? x) (decimal? x)"
				+ " (+' x y) (inc' x) (instance? Number x) (class x) (str x) (= x y) (hash x) (compare x y)"
				+ " (format \"%.2f\" x) (double x) (long x) (range x y)])"
				+ " (println (f 7 2) (map inc [1]) (reduce + [1 2]) (apply max [1 2]) (map zero? [0]) (map < [1] [2]))";
		String pruned = prunedForms(plain);
		assertThat(pruned).doesNotContain("%CLOJURE-BD-")
			.doesNotContain(":C%DECIMAL")
			.contains("(/ |c%x| |c%y|)")
			.contains("(TRUNCATE |c%x| |c%y|)")
			.contains("(ZEROP |c%x|)")
			.contains("(NUMBERP |c%x|)")
			.contains("#'+");
		// one decimal literal and every arm stays, the decimal runtime with them
		assertThat(prunedForms(plain + " (println 1.5M)")).contains("(DEFUN RONTOLISP::%CLOJURE-BD-ADD ")
			.contains("(RONTOLISP::%CLOJURE-BD-/ |c%x| |c%y|)")
			.contains("(RONTOLISP::%CLOJURE-BD-P X)");
		// and so does bigdec as a value
		assertThat(prunedForms("(println (map bigdec [1]))")).contains("(DEFUN RONTOLISP::%CLOJURE-BD-STRING ");
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
		assertThat(lowered("(map extends? [])")).contains("RONTOLISP::%CLOJURE-EXTENDS-P");
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
		assertThat(lowered("(completing +)"))
			.contains("(RONTOLISP::%CLOJURE-COMPLETING #'RONTOLISP::%CLOJURE-BD-+ #'IDENTITY)");
		assertThat(lowered("(reduced? (reduced 1))"))
			.contains("(RONTOLISP::%CLOJURE-REDUCED-PRED (RONTOLISP::%CLOJURE-REDUCED 1))");
		assertThat(lowered("(into [] cat [[1]])")).contains("#'RONTOLISP::%CLOJURE-XF-CAT");
		assertThat(lowered("(halt-when odd?)")).contains("(RONTOLISP::%CLOJURE-XF-HALT-WHEN");
		assertThat(lowered("(into [] (halt-when odd?) [1])")).contains("%CLOJURE-XF-HALT-WHEN");
		assertThat(lowered("(map halt-when [odd?])")).contains("#'RONTOLISP::%CLOJURE-HALT-WHEN-V");
		// reduce goes through the lazy-aware, reduced-aware runtime; a value that may
		// hold a collection is wrapped over the dispatcher at the call site
		assertThat(lowered("(reduce + [1])"))
			.contains("(RONTOLISP::%CLOJURE-REDUCE #'RONTOLISP::%CLOJURE-BD-+ (VECTOR 1))");
		assertThat(lowered("(reduce + 0 [1])"))
			.contains("(RONTOLISP::%CLOJURE-REDUCE-INIT #'RONTOLISP::%CLOJURE-BD-+ 0 (VECTOR 1))");
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
	void aRedefinedDeftestVersionsItsFunctionsAndNotifies() {
		// a redefined deftest validates and notifies like a redefined defn: each
		// definition keeps its own function, the calls below it run the newest, and
		// the replaced root travels in the notify statement the strip drops where
		// the program names no watch verb; a single definition lowers as before
		String out = lowered("(ns t (:use clojure.test)) (deftest a (is true)) (deftest a (is false)) (a)");
		assertThat(out).contains("(DEFUN |c%t/a| NIL")
			.contains("(DEFUN |c%t/a%body| NIL")
			.contains("(DEFUN |c%t/a%def2| NIL")
			.contains("(DEFUN |c%t/a%def2%body| NIL")
			.contains("(RONTOLISP::%CLOJURE-TEST-REGISTER \"t\" \"a\" #'|c%t/a%def2|)")
			.contains("(RONTOLISP::%CLOJURE-VAR-DEFN-NOTIFY \"t/a\" #'|c%t/a%def2| #'|c%t/a|)")
			.contains("(|c%t/a%def2|)");
		assertThat(lowered("(ns t (:use clojure.test)) (deftest a (is true)) (a)")).contains("(DEFUN |c%t/a| NIL")
			.contains("(DEFUN |c%t/a%body| NIL")
			.doesNotContain("%CLOJURE-VAR-DEFN-NOTIFY")
			.doesNotContain("%def2");
	}

	@Test
	void clojureTestRefusesWhatTheOracleRefuses() {
		assertThatThrownBy(() -> Clojure.read("(ns t (:use clojure.test)) (deftest a (are [x y] (= x y) 1 2 3))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("The number of args doesn't match are's argv.");
		assertThatThrownBy(() -> Clojure.read("(ns t (:use clojure.test)) (map is [1])", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Can't take value of a macro: #'clojure.test/is");
		assertThat(lowered("(ns t (:use clojure.test)) (use-fixtures :each identity)"))
			.contains("(ERROR \"use-fixtures is not supported yet: fixtures need a design\")");
		assertThatThrownBy(() -> Clojure.read("(ns t (:require [clojure.test :as t])) (t/no-such 1)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: clojure.test/no-such");
		assertThatThrownBy(() -> Clojure.read("(deftest a (is true))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: deftest");
		assertThatThrownBy(() -> Clojure.read("(ns t (:use no.such.lib))", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Could not locate no/such/lib.clj");
	}

	@Test
	void clojureTestReportsThroughDoReportAndReport() {
		// report and do-report resolve through an alias and a refer; the
		// multimethods the runtime defines answer their calls and values
		assertThat(lowered("(ns t (:require [clojure.test :as tt])) (tt/do-report {:type :pass})"))
			.contains("RONTOLISP::%CLOJURE-TEST-DO-REPORT");
		assertThat(lowered("(ns t (:use clojure.test)) (do-report {:type :pass})"))
			.contains("RONTOLISP::%CLOJURE-TEST-DO-REPORT");
		assertThat(lowered("(ns t (:require [clojure.test :as tt])) (tt/report {:type :pass})"))
			.contains("(|c%clojure.test/report|");
		assertThat(lowered("(ns t (:require [clojure.test :as tt])) (map tt/do-report [{}])"))
			.contains("RONTOLISP::%CLOJURE-TEST-DO-REPORT-V");
		assertThat(lowered("(ns t (:require [clojure.test :as tt])) (map tt/report [{}])"))
			.contains("#'|c%clojure.test/report|");
		// the runtime defines the report multimethod's tables ahead of everything
		assertThat(lowered("(ns t (:use clojure.test)) (is true)")).contains("|c%clojure.test/report%methods|")
			.contains("|c%clojure.test/assert-expr%methods|");
		// *testing-vars* reads the dynamic var, testing-vars-str calls its helper
		assertThat(lowered("(ns t (:use clojure.test)) *testing-vars*")).contains("|c%clojure.test/*testing-vars*|");
		assertThat(lowered("(ns t (:require [clojure.test :as tt])) (tt/testing-vars-str {})"))
			.contains("RONTOLISP::%CLOJURE-TEST-TESTING-VARS-STR");
		assertThat(lowered("(ns t (:require [clojure.test :as tt])) (map tt/testing-vars-str [{}])"))
			.contains("RONTOLISP::%CLOJURE-TEST-TESTING-VARS-STR-V");
		// with-test-out binds the report stream, and is a macro (no value)
		assertThat(lowered("(ns t (:use clojure.test)) (with-test-out (println 1))")).contains("*STANDARD-OUTPUT*")
			.contains("RONTOLISP::%CLOJURE-TEST-OUT");
		assertThatThrownBy(() -> Clojure.read("(ns t (:use clojure.test)) (map with-test-out [1])", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("Can't take value of a macro: #'clojure.test/with-test-out");
		// a defn carrying a :test function registers it, by name metadata or attr map
		assertThat(lowered("(ns t (:use clojure.test)) (defn ^{:test (fn [] 1)} foo [x] x)"))
			.contains("RONTOLISP::%CLOJURE-TEST-REGISTER \"t\" \"foo\"");
		assertThat(lowered("(ns t (:use clojure.test)) (defn foo {:test (fn [] 1)} [x] x)"))
			.contains("RONTOLISP::%CLOJURE-TEST-REGISTER \"t\" \"foo\"");
		assertThat(lowered("(ns t (:use clojure.test)) (defn foo ^:test [x] x)"))
			.doesNotContain("RONTOLISP::%CLOJURE-TEST-REGISTER");
	}

	@Test
	void clojureTestAssertExprMethodsExtendIsAtLowerTime() {
		// a defmethod on clojure.test/assert-expr reaches through an alias, and an
		// is over its head spelling expands through the method at lower time (no
		// predicate wrapper around it, so its do-report calls count alone)
		String out = loweredWithMacros("(ns t (:require [clojure.test :as tt :refer [is]]))"
				+ " (defmethod tt/assert-expr 't/my-check [_ form] `(let [m# ~(nth form 1)] (tt/do-report m#)))"
				+ " (is (t/my-check 1))");
		assertThat(out).contains("RONTOLISP::%CLOJURE-TEST-DO-REPORT").doesNotContain("RONTOLISP::%CLOJURE-TEST-PRED");
		// an is over any other head keeps the predicate call
		assertThat(loweredWithMacros(
				"(ns t (:require [clojure.test :as tt :refer [is]])) (defn tpred [x] x) (is (tpred 1))"))
			.contains("RONTOLISP::%CLOJURE-TEST-PRED");
	}

	@Test
	void instanceOfMultiFnAnswersFalse() {
		// clojure.lang.MultiFn has no instances here (a multimethod is a function),
		// so the test answers false instead of refusing the name, which is what
		// clojure.test.check asks to detect a rebound report
		assertThat(lowered("(instance? clojure.lang.MultiFn (fn [] 1))")).contains("PROGN")
			.doesNotContain("CLOJURE-HOST-OBJECT-P");
		assertThatThrownBy(() -> Clojure.read("(instance? clojure.lang.Nope 1)", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: clojure.lang.Nope");
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
		assertThat(loweredWithFiles("(require 'p.z) (p.z/f)", files))
			.contains("(DEFUN |c%p.z/f| NIL " + arity("p.z/f") + " 2)");
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
		assertThat(out)
			.containsOnlyOnce(
					"(DEFUN |c%app.lib/f| (|c%x|) " + arity("app.lib/f") + " (RONTOLISP::%CLOJURE-+ |c%x| 1))")
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
		assertThat(out).contains("(DEFUN |c%f| NIL " + arity("user/f") + " 0)")
			.contains("(DEFUN |c%a/f| NIL " + arity("a/f") + " 1)")
			.contains("(DEFUN |c%b/f| NIL " + arity("b/f") + " 2)")
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
		assertThat(loweredWithExpanders("(ns s.a) (defn h [] 1) (defmacro m [] `(h ~'x nope let))"))
			.contains("(%UNSPELLED-QUOTE |c%s.a/h|)")
			.contains("(%UNSPELLED-QUOTE |c%s.a/nope|)")
			.contains("(%UNSPELLED-QUOTE |c%clojure.core/let|)");
		assertThat(loweredWithExpanders("(defn h [] 1) (defmacro m [] `(h))"))
			.contains("(%UNSPELLED-QUOTE |c%user/h|)");
		assertThat(loweredWithExpanders(
				"(ns s.b (:require [clojure.string :as s])) (defmacro m [] `(s/join s/nope System/nanoTime foo/bar import*))"))
			.contains("(%UNSPELLED-QUOTE |c%clojure.string/join|)")
			.contains("(%UNSPELLED-QUOTE |c%clojure.string/nope|)")
			.contains("(%UNSPELLED-QUOTE |c%java.lang.System/nanoTime|)")
			.contains("(%UNSPELLED-QUOTE |c%foo/bar|)")
			.contains("(%UNSPELLED-QUOTE |c%s.b/import*|)");
		assertThat(loweredWithExpanders("(ns s.c (:refer-clojure :exclude [map])) (defmacro m [] `(map filter))"))
			.contains("(%UNSPELLED-QUOTE |c%s.c/map|)")
			.contains("(%UNSPELLED-QUOTE |c%clojure.core/filter|)");
		// a class spelling is already fully qualified (measured on the
		// oracle: `java.io.StringWriter reads as written, `String as
		// java.lang.String) -- never with the defining namespace
		assertThat(loweredWithExpanders("(ns s.d) (defmacro m [] `(java.io.StringWriter String))"))
			.contains("(%UNSPELLED-QUOTE |c%java.io.StringWriter|)")
			.contains("(%UNSPELLED-QUOTE |c%java.lang.String|)");
	}

	@Test
	void aRunTimeExpansionResolvesItsHeadThroughTheScopeOfTheNamespaceItRunsIn() {
		// the call carries no scope: C%MACRO-FN asks C%MACRO-SCOPE for *ns*'s, which
		// spells a namespace's bare and alias-qualified macro names the table cannot
		String out = loweredWithMacros(
				"(ns s.a) (defmacro m [] 1) (ns s.b (:require [s.a :as x :refer [m]])) (macroexpand '(x/m))");
		assertThat(out).contains("(C%MACROEXPAND '(|c%x/m|))")
			.contains("((STRING= |ns| \"s.a\") '((|c%m| . |c%s.a/m%macro|)))")
			.contains("((STRING= |ns| \"s.b\") '((|c%m| . |c%s.a/m%macro|) (|c%x/m| . |c%s.a/m%macro|)))");
		// user's table spells a user macro's bare name
		assertThat(loweredWithMacros("(defmacro m [] 1) (macroexpand-1 '(m))")).contains("(C%MACROEXPAND-1 '(|c%m|))")
			.contains("(DEFUN C%MACRO-SCOPE (|ns|) (COND (T NIL)))");
	}

	@Test
	void aRequiredNamespaceRunsItsStatementsFromAnInitBehindAFlag() {
		// the defn stays a top-level defun; the print and the def run from the
		// namespace's init, which the require site calls behind the loaded flag
		String out = loweredWithFiles("(ns m (:require [app.lib :as l])) (println (l/f 1))",
				Map.of("src/app/lib.clj", "(ns app.lib) (println \"hi\") (def v 1) (defn f [x] (inc x))"));
		assertThat(out)
			.contains("(DEFUN |c%app.lib/f| (|c%x|) " + arity("app.lib/f") + " (RONTOLISP::%CLOJURE-+ |c%x| 1))")
			.contains("(DEFVAR |c%app.lib%loaded| NIL)")
			.contains("(SETQ |c%app.lib%init-1| (LAMBDA NIL")
			.contains(
					"(RONTOLISP::%CLOJURE-VAR-STORE (LAMBDA NIL (IF (BOUNDP '|c%app.lib/v|) |c%app.lib/v| (RONTOLISP::%CLOJURE-UNBOUND \"app.lib/v\"))) (LAMBDA (|__clojure_new_root|) (SETQ |c%app.lib/v| |__clojure_new_root|)) 1 \"app.lib/v\")")
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
			.contains(
					"(RONTOLISP::%CLOJURE-VAR-STORE (LAMBDA NIL (IF (BOUNDP '|c%app.dyn/x|) |c%app.dyn/x| (RONTOLISP::%CLOJURE-UNBOUND \"app.dyn/x\"))) (LAMBDA (|__clojure_new_root|) (SETQ |c%app.dyn/x| |__clojure_new_root|)) 1 \"app.dyn/x\")")
			// A reload keeps the defonce root through a runtime boundp probe
			// of the var itself -- every store feeds the eval mirror the probe
			// reads, so no set flag beside the var is needed any more
			.contains(
					"(UNLESS (BOUNDP '|c%app.dyn/y|) (RONTOLISP::%CLOJURE-VAR-STORE (LAMBDA NIL (IF (BOUNDP '|c%app.dyn/y|) |c%app.dyn/y| (RONTOLISP::%CLOJURE-UNBOUND \"app.dyn/y\"))) (LAMBDA (|__clojure_new_root|) (SETQ |c%app.dyn/y| |__clojure_new_root|)) 2 \"app.dyn/y\"))")
			.doesNotContain("%set");
		assertThat(out).doesNotContain("(DEFPARAMETER |c%app.dyn/x| 1)");
	}

	@Test
	void aDefonceInANamespaceKeepsItsRootThroughBoundp() {
		// the plain (non-dynamic) init shape rides the same probe: the init's own
		// assignment poisons the name, so the compile-time boundp fold leaves the
		// probe to the run time, where the mirror answers it
		String out = loweredWithFiles("(require 'app.once)", Map.of("src/app/once.clj", "(ns app.once) (defonce v 1)"));
		assertThat(out).contains(
				"(UNLESS (BOUNDP '|c%app.once/v|) (RONTOLISP::%CLOJURE-VAR-STORE (LAMBDA NIL (IF (BOUNDP '|c%app.once/v|) |c%app.once/v| (RONTOLISP::%CLOJURE-UNBOUND \"app.once/v\"))) (LAMBDA (|__clojure_new_root|) (SETQ |c%app.once/v| |__clojure_new_root|)) 1 \"app.once/v\"))")
			.doesNotContain("%set");
	}

}
