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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
		List<ClojureTopLevel> defined = session.read("(defn twice [x] (* 2 x))");
		assertThat(defined.get(0).forms().stream().map(LispVal::print).toList()).containsExactly(
				"(SETQ RONTOLISP::%CLOJURE-FALSE '|false|)",
				"(DEFVAR RONTOLISP::%CLOJURE-UNCHECKED-MATH RONTOLISP::%CLOJURE-FALSE)");
		assertThat(defined.get(1).forms().stream().map(LispVal::print).toList()).hasSize(1)
			.first()
			.asString()
			.startsWith(INPUT + "(DEFUN |c%twice| (|c%x|) " + ClojureLoweringTest.arity("user/twice")
					+ " (RONTOLISP::%CLOJURE-* 2 |c%x|)) " + ClojureLoweringTest.noteFnClass("twice", "user$twice")
					+ " (RONTOLISP::%CLOJURE-VAR \"user/twice\"");
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
		// stores the unbound root only into an unbound cell, and a call and a value
		// read take the function cell once a defn filled it -- until then the call
		// reaches the unbound root, which signals like the oracle's
		ClojureSession session = new ClojureSession();
		assertThat(forms(session.read("(declare later)"))).anyMatch(form -> form
			.contains("(UNLESS (BOUNDP '|c%later|) (SETQ |c%later| (RONTOLISP::%CLOJURE-UNBOUND \"user/later\")))"));
		assertThat(forms(session.read("(later 1)"))).anyMatch(
				form -> form.contains("(RONTOLISP::%CLOJURE-CALL (IF (FBOUNDP '|c%later|) #'|c%later| |c%later|)"));
		assertThat(forms(session.read("(map later [1])")))
			.anyMatch(form -> form.contains("(IF (FBOUNDP '|c%later|) #'|c%later| |c%later|)"));
		assertThat(runSession("(declare sx-later)", "(println (try (sx-later 1) (catch Exception e (ex-message e))))",
				"(defn sx-later [x] (inc x))", "(println (sx-later 1))"))
			.isEqualTo("Attempting to call unbound fn: #'user/sx-later\n2\n");
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
		session.attach(evaluator);
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
	void aTypeDefinedInOneBufferShowsJavaItsFaceInTheNext() {
		// a session registers a type's face as it defines the type: a later buffer may
		// hand the value to Java, where a file registers it only when it names the host
		assertThat(runSession("(deftype Task [] Runnable (run [_] (println \"ran\")))",
				"(doto (Thread. (Task.)) .start .join)"))
			.isEqualTo("ran\n");
		assertThat(forms(new ClojureSession().read("(deftype Task [] Runnable (run [_] nil))")))
			.anyMatch(form -> form.contains("(RONTOLISP::%CLOJURE-JAVA-FACE-TAG (LIST :C%KEYWORD \"Task\")"));
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
	void userRefersWhatTheOraclesReplRequiresLoadingANamespaceWhereANameFirstReachesIt() {
		// clojure.main/repl-requires: doc and pst from clojure.repl, pp and pprint from
		// clojure.pprint, referred into user before the first input
		assertThat(runSession("(defn f \"Adds one.\" [x] (inc x))", "(doc f)"))
			.isEqualTo("-------------------------\nuser/f\n([x])\n  Adds one.\n");
		assertThat(runSession("(pprint {:a 1})", "[1 2]", "(pp)")).isEqualTo("{:a 1}\n[1 2]\n");
		assertThat(runSession("(binding [*err* *out*] (pst (ex-info \"boom\" {:a 1})))"))
			.startsWith("ExceptionInfo boom");
		// the oracle's REPL loaded the namespaces: a qualified name needs no require
		assertThat(runSession("(clojure.pprint/pprint [1])", "(clojure.repl/doc clojure.repl/pst)"))
			.startsWith("[1]\n-------------------------\nclojure.repl/pst\n");
		// a buffer naming none loads neither
		assertThat(forms(new ClojureSession().read("(inc 1)"))).noneMatch(form -> form.contains("print-doc"));
	}

	@Test
	void aReplReferALocalOrADefinitionShadowsLoadsNothing() {
		assertThat(runSession("(prn (let [doc 1 source 2] [doc source]))")).isEqualTo("[1 2]\n");
		assertThat(runSession("(defn pprint [x] (prn :mine x))", "(pprint 1)")).isEqualTo(":mine 1\n");
		// another namespace refers none of them, like the oracle's
		assertThatThrownBy(() -> runSession("(ns sess.other)", "(doc map)")).hasMessageContaining("unknown name: doc");
	}

	@Test
	void aReplReferLeftOutNamesItsRefusal() {
		assertThatThrownBy(() -> runSession("(source map)"))
			.hasMessageContaining("clojure.repl/source is not built in: a definition's text is not kept at run time");
		assertThatThrownBy(() -> runSession("(dir clojure.string)"))
			.hasMessageContaining("clojure.repl/dir is not built in:");
		assertThatThrownBy(() -> runSession("(javadoc String)")).hasMessageContaining(
				"clojure.java.javadoc/javadoc is not built in: it opens a web browser on a class's Javadoc");
		assertThatThrownBy(() -> runSession("(add-libs '{hiccup/hiccup {:mvn/version \"2.0.0\"}})"))
			.hasMessageContaining("clojure.repl.deps/add-libs is not built in:");
		assertThatThrownBy(() -> runSession("(require 'clojure.repl.deps)"))
			.hasMessageContaining("clojure.repl.deps is not built in:");
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
		// a later input may expand at run time: the table holds the expander itself
		assertThat(defined).anyMatch(form -> form.contains("(SETQ |c%sx-unless%macro| (LAMBDA "))
			.noneMatch(form -> form.contains("C%MACRO-EXPANDER"));
		List<String> call = session.read("(sx-unless (odd? 2) 42)")
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
				&& form.contains("(\"string\" \"java.lang.String\"")
				&& form.contains("(\"java.lang.Long\" \"java.lang.Number\"")
				&& form.contains("\"java.lang.ArithmeticException\""));
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
	void aLaterBufferAltersTheRootOfAVarAnEarlierOneDefined() {
		// a def's value cell takes the new root; a defn an earlier input lowered as a
		// direct call cannot, so its alteration is refused naming ^:redef, which the
		// oracle's REPL would have altered
		assertThat(runSession("(def sx-root 1)", "(println (alter-var-root #'sx-root inc) sx-root)",
				"(defn sx-direct [] 1)",
				"(println (try (alter-var-root #'sx-direct (constantly 2))"
						+ " (catch UnsupportedOperationException e (ex-message e))))",
				"(defn ^:redef sx-redef [] 1)", "(alter-var-root #'sx-redef (constantly (fn [] 2)))",
				"(println (sx-redef))"))
			.isEqualTo("2 2\nalter-var-root of #'user/sx-direct is not supported: its calls do not go through"
					+ " the var (a defn an earlier input defined without ^:redef, a multimethod, protocol method,"
					+ " record constructor or test)\n2\n");
	}

	@Test
	void aLaterBufferRedefinitionNotifiesTheWatchesAnEarlierOneAdded() {
		// a session keeps the watch arms: a later buffer's def, defn or with-redefs
		// validates and notifies like the oracle's bindRoot, where a file program
		// naming no watch verb folds them back
		assertThat(runSession("(def sx 1)", "(add-watch #'sx :k (fn [k v o n] (println :watch o n)))", "(def sx 10)",
				"(defn sf [] :a)", "(add-watch #'sf :k (fn [k v o n] (println :fn (fn? o) (fn? n))))",
				"(defn sf [] :b)", "(println (sf))", "(with-redefs [sx 20] (println :body sx))", "(println :after sx)"))
			.isEqualTo(":watch 1 10\n:fn true true\n:b\n:watch 10 20\n:body 20\n:watch 20 10\n:after 10\n");
	}

	@Test
	void aLaterBufferMacroRedefinitionNotifiesTheWatchesAnEarlierOneAdded() {
		// a session keeps the macro watch arms too: a later buffer's defmacro
		// validates and notifies like the oracle's bindRoot, with the previous
		// expander as the replaced root; set-validator! of a literal macro var
		// checks the present expander, since the var's site reads no value
		assertThat(
				runSession("(defmacro sm [] :a)", "(add-watch #'sm :k (fn [k v o n] (println :macro (fn? o) (fn? n))))",
						"(defmacro sm [] :b)", "(println (sm))", "(def sm-ok (atom true))",
						"(set-validator! #'sm (fn [v] @sm-ok))", "(reset! sm-ok false)",
						"(println (try (defmacro sm [] :c) (catch IllegalStateException e (ex-message e))))",
						"(reset! sm-ok true)", "(remove-watch #'sm :k)", "(defmacro sm [] :d)", "(println (sm))"))
			.isEqualTo(":macro true true\n:b\nInvalid reference state\n:d\n");
	}

	@Test
	void aLaterBufferSetValidatorOfAComputedMacroVarAnswersNil() {
		// a computed set-validator! of a macro var checks the present expander the
		// earlier buffer's defmacro noted, since the var's site reads no value; a
		// validator it fails throws, nil removes it
		assertThat(runSession("(defmacro smc [] :a)",
				"(println (set-validator! (if true #'smc #'smc) (fn [v] (fn? v))))",
				"(println ((fn [v] (set-validator! v (fn [v] (fn? v)))) #'smc))",
				"(println (apply set-validator! [#'smc (fn [v] (fn? v))]))", "(println (fn? (get-validator #'smc)))",
				"(println (set-validator! (if true #'smc #'smc) nil))", "(println (get-validator #'smc))",
				"(println (try (set-validator! (if true #'smc #'smc) (fn [v] false)) (catch IllegalStateException e (ex-message e))))"))
			.isEqualTo("nil\nnil\nnil\ntrue\nnil\nnil\nInvalid reference state\n");
	}

	@Test
	void aLaterBufferTestRedefinitionNotifiesTheWatchesAnEarlierOneAdded() {
		// and a later buffer's deftest: the calls below it run the newest
		// definition while the watches see the replaced test function
		assertThat(runSession("(ns stest (:require [clojure.test :refer :all]))",
				"(deftest st (do (println :old-body) (is (= 1 1))))",
				"(add-watch #'st :k (fn [k v o n] (println :test (fn? o) (fn? n))))",
				"(deftest st (do (println :new-body) (is (= 2 2))))", "(st)"))
			.isEqualTo(":test true true\n:new-body\n");
	}

	@Test
	void evalLowersThroughTheSessionsOneLoweringInAMacroBodyAndAsAnInputRuns() {
		// eval in a macro body lowers through the session's one lowering, and so does an
		// input's eval as it runs: what it defines a later buffer names, and a computed
		// resolve answers it
		assertThat(runSession("(defn sx-four [] 4)", "(defmacro sx-if [t a b] (if (eval t) a b))",
				"(println (sx-if (= (sx-four) 4) :yes :no) (sx-if (resolve 'sx-nowhere) :yes :no))",
				"(println (eval '(defn sx-evald [] (inc (sx-four)))))",
				"(println (sx-evald) ((resolve (symbol \"sx-evald\"))) (load-string \"(sx-if true 1 2)\"))"))
			.isEqualTo(":yes :no\n#'user/sx-evald\n5 5 1\n");
	}

	@Test
	void aRunTimeExpansionResolvesInTheNamespaceALaterBufferSwitchedTo() {
		// the scopes a buffer changes after the macro runtime came define themselves
		// again; a bare head resolves in *ns*, like the oracle's REPL
		assertThat(runSession("(defmacro sx-um [x] `(inc ~x))", "(prn (macroexpand '(sx-um 1)))", "(ns sxns)",
				"(defmacro sx-m [x] `(dec ~x))", "(prn (macroexpand '(sx-m 1)) (macroexpand '(sx-um 1)))"))
			.isEqualTo("(clojure.core/inc 1)\n(clojure.core/dec 1) (sx-um 1)\n");
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
	void aLaterBufferExtendingAProtocolToAWalkedClassReachesItsValues() {
		// a session's dispatchers all walk; a buffer extending a protocol of an earlier
		// one to a throwable or an interface defines the walk again with that class
		assertThat(runSession("(defprotocol SP (sp [x])) (extend-protocol SP Object (sp [_] :object))",
				"(println (sp (ex-info \"a\" {})) (sp (atom 1)))", "(extend-protocol SP Exception (sp [_] :exception))",
				"(extend clojure.lang.IRef SP {:sp (fn [_] :iref)})",
				"(println (sp (ex-info \"a\" {})) (sp (atom 1)) (sp 1) (satisfies? SP (Error.)))"))
			.isEqualTo(":object :object\n:exception :iref :object true\n");
	}

	@Test
	void theExtendFormsEchoNil() {
		// the oracle answers nil from extend-protocol, extend-type and extend, not the
		// lambda the last row stored
		assertThat(runSession("(defprotocol EP (ep [x]))",
				"(prn (extend-protocol EP Long (ep [n] n)) (extend-type String EP (ep [s] s))"
						+ " (extend Boolean EP {:ep (fn [b] b)}))"))
			.isEqualTo("nil nil nil\n");
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
		assertThat(required).anyMatch(form -> form.contains("(DEFUN |c%app.lib/f| (|c%x|) "
				+ ClojureLoweringTest.arity("app.lib/f") + " (RONTOLISP::%CLOJURE-+ |c%x| 1))"));
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
	void anInputKeepsTheResourcesItsLiteralNamesFoundBeforeItRuns() {
		// kept ahead of the input's forms, never where the call runs: a Ring handler the
		// input starts runs one thread per request
		ClojureFiles files = new ClojureFiles() {
			@Override
			public @Nullable String read(String path) {
				return path.endsWith("src/greeting.txt") ? "hi" : null;
			}

			@Override
			public @Nullable String parent(String path) {
				return null;
			}

			@Override
			public String resolve(@Nullable String dir, String relative) {
				return dir == null ? relative : dir + "/" + relative;
			}
		};
		ClojureSession session = new ClojureSession(files);
		session.read("(require '[clojure.java.io :as io])");
		List<ClojureTopLevel> tops = session.read("(defn g [] (io/resource \"greeting.txt\")) (slurp (g))");
		assertThat(tops).hasSize(3);
		assertThat(tops.get(0).forms().stream().map(LispVal::print).toList()).singleElement()
			.asString()
			.matches("\\(RONTOLISP::%CLOJURE-IO-KEEP-RESOURCE \"file:.*src/greeting.txt\" \"hi\" :UTF-8\\)");
		assertThat(forms(tops.subList(1, 3))).noneMatch(form -> form.contains("KEEP-RESOURCE"))
			.anyMatch(form -> form.matches(".*\\(RONTOLISP::%CLOJURE-IO-URL-FOUND \"file:[^\"]*\"\\).*"));
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
