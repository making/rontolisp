package am.ik.rontolisp.clojure;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.testsupport.CliStack;
import am.ik.rontolisp.testsupport.ThreadStdio;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The interop lowering ({@code .}, {@code ..}, {@code Class/member}, {@code Class.},
 * {@code new}) over the {@code java:} surface, on the interpreter and the JVM. The wasm
 * backends reject {@code java:} outright, so these cases cannot live in
 * {@code clojure-spec.yaml}.
 */
class ClojureInteropTest {

	@Test
	void instanceCallsOnStringsAnswerCoreValues() throws Exception {
		assertBothEqual("(println (.toUpperCase \"hi\"))", "HI\n");
		assertBothEqual("(println (. \"hi\" toUpperCase))", "HI\n");
		assertBothEqual("(println (. \"hi\" (toUpperCase)))", "HI\n");
		assertBothEqual("(println (. \"hi\" (substring 1)))", "i\n");
		assertBothEqual("(println (.. \"hi\" (toUpperCase) (substring 1)))", "I\n");
		assertBothEqual("(println (.length \"hi\"))", "2\n");
		assertBothEqual("(println (. \"hihi\" (indexOf \"i\")))", "1\n");
		assertBothEqual("(println (. \"hihi\" (indexOf \"z\")))", "-1\n");
	}

	@Test
	void unmappedMethodsCallAStringNumberOrCharacterAsItsHostObject() throws Exception {
		// answers measured against clj 1.12.6
		assertBothEqual("(println (.codePointAt \"abc\" 0))", "97\n");
		assertBothEqual("(println (.compareTo \"a\" \"b\"))", "-1\n");
		assertBothEqual("(println (String/.compareToIgnoreCase \"a\" \"B\"))", "-1\n");
		assertBothEqual("(println (.matches \"abc\" \"a.c\") (.matches \"abc\" \"x\"))", "true false\n");
		assertBothEqual("(println (.regionMatches \"abc\" 1 \"bc\" 0 2) (.hashCode \"ab\") (.repeat \"ab\" 2))",
				"true 3105 abab\n");
		assertBothEqual("(println (.codePointAt (str \"a\" \"b\") 1) (let [s \"hello\"] (.codePointAt s 1))"
				+ " (map #(.codePointAt % 0) [\"a\" \"b\"]))", "98 101 (97 98)\n");
		assertBothEqual("(println (.compareTo 1 2) (.doubleValue 3) (.isNaN 1.5) (.compareTo \\a \\b))",
				"-1 3.0 false -1\n");
		assertBothEqual("(println (.equals 1 1) (.equals 1 2) (.intValue 2.7) (.toString \\a))", "true false 2 a\n");
	}

	@Test
	void staticsConstructorsAndFields() throws Exception {
		assertBothEqual("(println (Integer/parseInt \"42\"))", "42\n");
		assertBothEqual("(println (Math/max 3 7))", "7\n");
		assertBothEqual("(println (. Math max 3 7))", "7\n");
		assertBothEqual("(println (String. \"hi\"))", "hi\n");
		assertBothEqual("(println (new String \"hi\"))", "hi\n");
		assertBothEqual("(println (Integer/MAX_VALUE))", "2147483647\n");
		assertBothEqual("(println (.-x (java.awt.Point. 1 2)))", "1\n");
		assertBothEqual("(ns pointns (:import (java.awt Point))) (println (Point. 3 4))", "#<java java.awt.Point>\n");
	}

	@Test
	void everyDefaultImportedJavaLangClassResolvesWithoutAnImport() throws Exception {
		// the oracle's default imports (clj 1.12.6): the common throwables construct and
		// catch by name
		assertBothEqual("(println (.getMessage (IllegalStateException. \"boo\")))", "boo\n");
		assertBothEqual("(println (.getMessage (new IllegalArgumentException \"boo\")))", "boo\n");
		assertBothEqual("(println (.getMessage (ArithmeticException. \"boo\")))", "boo\n");
		assertBothEqual("(println (.getMessage (UnsupportedOperationException. \"boo\")))", "boo\n");
		assertBothEqual("(println (.getMessage (IndexOutOfBoundsException. \"boo\")))", "boo\n");
		assertBothEqual("(println (.getMessage (NullPointerException. \"boo\")))", "boo\n");
		assertBothEqual("(println (.getMessage (ClassCastException. \"boo\")))", "boo\n");
		assertBothEqual("(println (.getMessage (Throwable. \"boo\")))", "boo\n");
		assertBothEqual(
				"(println (try (throw (IllegalStateException. \"boo\")) (catch IllegalStateException e :caught)))",
				":caught\n");
		assertBothEqual("(println (.getMessage (NumberFormatException. \"boo\")) (.length (StringBuffer. \"ab\")))",
				"boo 2\n");
	}

	@Test
	void aThrownHostThrowableKeepsItsClassAndMessage() throws Exception {
		// answers measured against clj 1.12.6: a throwable with members of its own
		// stays a host object, and throw hands catch an exception carrying its
		// class, message and cause
		assertBothEqual(
				"(prn (try (throw (java.net.URISyntaxException. \"in\" \"bad\"))"
						+ " (catch Exception e [(.getMessage e) (ex-message e) (str e) (ex-data e)])))",
				"[\"bad: in\" \"bad: in\" \"java.net.URISyntaxException: bad: in\" nil]\n");
		assertBothEqual(
				"(prn (ex-message (java.net.URISyntaxException. \"in\" \"bad\"))"
						+ " (ex-message (ex-cause (Exception. \"o\" (java.net.URISyntaxException. \"in\" \"bad\")))))",
				"\"bad: in\" \"bad: in\"\n");
		assertBothEqual("(require '[clojure.test :refer [is]])"
				+ " (prn (ex-message (is (thrown-with-msg? Exception #\"bad\" (throw (java.net.URISyntaxException. \"in\" \"bad\"))))))",
				"\"bad: in\"\n");
		// a host object of another kind keeps its own getMessage
		assertBothEqual("(prn (try (.getMessage (java.io.File. \"x\")) (catch Exception e :refused)))", ":refused\n");
	}

	@Test
	void hostObjectsChainThroughCalls() throws Exception {
		assertBothEqual("(println (.toString (. (StringBuilder. \"a\") (append \"b\"))))", "ab\n");
		assertBothEqual("(println (try (Integer/parseInt \"xx\") (catch Exception e \"bad\")))", "bad\n");
	}

	@Test
	void stringWriterBindsToOutAndStrAnswersWithoutClearing() throws Exception {
		// A zero-argument (new java.io.StringWriter) lowers to a string
		// output stream (never a host Writer), so binding *out* to it captures
		// printing, and str/.toString answer the text so far WITHOUT clearing it
		// (the oracle prints 12 12 12). The all-four-backend pin is the
		// clojure-spec.yaml case; the (Class.) spelling and the excerpt macro live
		// here with the other interop pins.
		assertBothEqual("(let [s (new java.io.StringWriter)] (binding [*out* s] (print 1) (print 2))"
				+ " (println (str s) (str s) (.toString s)))", "12 12 12\n");
		assertBothEqual(
				"(let [w (java.io.StringWriter.)] (. w write \"ab\") (. w flush)" + " (println (str w)) (. w close))",
				"ab\n");
		assertBothEqual(
				"(defmacro with-out-str [& body] `(let [s# (new java.io.StringWriter)]"
						+ " (binding [*out* s#] ~@body (str s#)))) (println (with-out-str (print 1) (print 2)))",
				"12\n");
	}

	@Test
	void classNamesAnswerAsValuesAndStringsAnswerGetClass() throws Exception {
		assertBothEqual("(println (= String (Class/forName \"java.lang.String\")))", "true\n");
		assertBothEqual("(println (.getName String))", "java.lang.String\n");
		assertBothEqual("(println (= String (.getClass \"s\")))", "true\n");
		assertBothEqual("(ns importsns (:import (java.util ArrayList))) (println (.getName ArrayList))",
				"java.util.ArrayList\n");
		assertBothEqual("(println (.getName (.getClass \"s\")))", "java.lang.String\n");
		assertBothEqual("(println (= java.util.ArrayList (.getClass (java.util.ArrayList.))))", "true\n");
		assertBothEqual("(defmulti m class) (defmethod m String [_] :s) (println (m \"a\"))", ":s\n");
		assertBothEqual("(println (instance? String \"a\"))", "true\n");
		assertThatThrownBy(() -> interpret("(println NoSuchClass)")).hasMessageContaining("unknown name");
	}

	@Test
	void aClassObjectPrintsItsNameAndStrSpellsItsToString() throws Exception {
		// the oracle's print-method for a Class writes getName; str is toString
		assertBothEqual("(println String)", "java.lang.String\n");
		assertBothEqual("(print String) (prn String)", "java.lang.Stringjava.lang.String\n");
		assertBothEqual("(println (pr-str String) (str String))", "java.lang.String class java.lang.String\n");
		assertBothEqual("(pr [String \"a\"]) (println {String 1} (str [String]))",
				"[java.lang.String \"a\"]{java.lang.String 1} [java.lang.String]\n");
		assertBothEqual("(println Long/TYPE (str Long/TYPE))", "long long\n");
		assertBothEqual("(println (class (java.io.File. \"foo\")))", "java.io.File\n");
	}

	@Test
	void strOfAHostObjectIsItsToStringWhilePrintingStaysUnreadable() throws Exception {
		assertBothEqual("(println (str (StringBuilder. \"ab\") \"|\" (java.io.File. \"foo\")))", "ab|foo\n");
		assertBothEqual("(println (str (java.util.ArrayList. [1 2])))", "[1, 2]\n");
		assertBothEqual("(println (str 1 :k [1 \"a\"] nil 's))", "1:k[1 \"a\"]s\n");
		assertBothEqual("(println (java.io.File. \"foo\"))", "#<java java.io.File>\n");
	}

	@Test
	void classOfAHostObjectIsItsHostClassSoDispatchReachesTheDefault() throws Exception {
		assertBothEqual("(println (= java.io.File (class (java.io.File. \"foo\"))))", "true\n");
		assertBothEqual("(println (.getName (class (java.io.File. \"foo\"))))", "java.io.File\n");
		assertBothEqual("(println (.getName ((comp class identity) (java.io.File. \"foo\"))))", "java.io.File\n");
		// the book's my-print: a host object misses every class row and lands on
		// :default, whose .toString reaches the host method (oracle #<foo>)
		assertBothEqual("(defmulti mp class) (defmethod mp String [s] s)"
				+ " (defmethod mp Number [n] (str \"n\" (.toString n)))"
				+ " (defmethod mp :default [x] (str \"#<\" (.toString x) \">\"))"
				+ " (println (mp 42) (mp (java.io.File. \"foo\")))", "n42 #<foo>\n");
		assertBothEqual(
				"(defmulti mo class) (defmethod mo Object [x] :object)" + " (println (mo (java.io.File. \"foo\")))",
				":object\n");
	}

	@Test
	void hostKindPredicatesTestTheHostClass() throws Exception {
		// oracle-identical (clj 1.12.6); a Lisp value is no host object, so a string
		// is no uri? or inst? (the all-four-backend false answers are clojure-spec's)
		assertBothEqual("(prn (class? String) (class? \"a\") (class? 1) (inst? (java.util.Date.))"
				+ " (inst? (java.time.Instant/now)) (inst? \"2020\") (uuid? (java.util.UUID/randomUUID)) (uuid? \"x\")"
				+ " (uri? (java.net.URI. \"http://a\")) (uri? \"http://a\") (map uuid? [(java.util.UUID/randomUUID) 1]))",
				"true false false true true false true false true false (true false)\n");
	}

	@Test
	void classOfAValueOfNoKnownKindStaysARefusalWithOrWithoutInterop() throws Exception {
		// an ex-info condition is no host object: the same refusal whether the
		// program uses interop (the host arm) or not (no java: at all)
		String plain = "(println (try (class (ex-info \"a\" {})) (catch Exception e (ex-message e))))";
		assertBothEqual(plain, "class needs a value of a known kind\n");
		assertBothEqual("(println (.getName String)) " + plain,
				"java.lang.String\nclass needs a value of a known kind\n");
	}

	@Test
	void theHostArmsAddNoJavaReferenceToAProgramWithoutInterop() {
		// a java: reference changes the JVM output (the bridge, the host guards on
		// every accessor), so class's, the printer's, str's and the host-kind
		// predicates' host arms exist only where the program has one; the
		// java:-free stand-ins take their names
		for (boolean wasm : new boolean[] { false, true }) {
			var forms = am.ik.rontolisp.cli.CompileFrontendAccess
				.clojure(
						"(defmulti k class) (defmethod k :default [x] x)"
								+ " (println (k 1) (class [1]) (str [1] 2) (pr-str 3) (uuid? 4) (inst? 5))",
						wasm, false)
				.forms()
				.stream()
				.map(LispVal::print)
				.toList();
			assertThat(forms).noneMatch(text -> text.contains("JAVA:"));
			assertThat(forms).anyMatch(text -> text.contains("%CLOJURE-HOST-CLASS-NAME"));
			assertThat(forms).anyMatch(text -> text.contains("%CLOJURE-HOST-STRING"));
			assertThat(forms).anyMatch(text -> text.contains("%CLOJURE-HOST-INSTANCE-P"));
		}
	}

	@Test
	void staticFieldsAnswerAsValues() throws Exception {
		// the book's snake.clj/atom_snake.clj dirs shape: a static field as a map
		// value, through an import
		assertBothEqual("(ns awtns (:import (java.awt.event KeyEvent))) (println KeyEvent/VK_LEFT)", "37\n");
		assertBothEqual("(println Math/PI)", "3.141592653589793\n");
		assertBothEqual("(println java.lang.Math/PI)", "3.141592653589793\n");
		assertBothEqual("(ns mathns (:import java.lang.Math)) (println Math/PI)", "3.141592653589793\n");
		assertBothEqual("(println (Math/PI))", "3.141592653589793\n");
		assertBothEqual("(println (. Math PI))", "3.141592653589793\n");
	}

	@Test
	void zeroArgStaticCallsReachTheMethod() throws Exception {
		// the book's sequences.clj/pi.clj shape and the bench-macro timer: a
		// zero-argument (Class/member) is the static method, not the field read
		assertBothEqual("(println (> (System/currentTimeMillis) 0))", "true\n");
		assertBothEqual("(println (> (System/nanoTime) 0))", "true\n");
		assertBothEqual("(println (> (. System currentTimeMillis) 0))", "true\n");
		// a field in call position keeps answering the field
		assertBothEqual("(println (Integer/MAX_VALUE))", "2147483647\n");
	}

	@Test
	void staticMembersAnswerAsValues() throws Exception {
		// the book's introduction.clj blank? shape: the member over every?
		assertBothEqual("(defn all-ws? [s] (every? Character/isWhitespace s)) (println (all-ws? \"   \"))", "true\n");
		assertBothEqual("(println (every? Character/isWhitespace \"   \"))", "true\n");
		assertBothEqual("(println (every? Character/isWhitespace \" a \"))", "false\n");
		assertBothEqual("(println (map Integer/toString [1 2]))", "(1 2)\n");
		assertBothEqual("(println (map Character/isWhitespace \" a\"))", "(true false)\n");
		assertBothEqual("(let [isws Character/isWhitespace] (println (isws \\space)))", "true\n");
	}

	@Test
	void memberValuesRefuseAWrongArgumentCount() throws Exception {
		// like a multi-arity defn dispatch: any other count signals, on both backends
		assertThatThrownBy(() -> interpret("(println ((fn [f] (f 1 2)) Character/isWhitespace))"))
			.isInstanceOf(Exception.class);
		assertThatThrownBy(() -> runOnJvm("(println ((fn [f] (f 1 2)) Character/isWhitespace))"))
			.isInstanceOf(Exception.class);
	}

	@Test
	void qualifiedInstanceMethodsTakeTheTargetFirst() throws Exception {
		// Clojure 1.12 Class/.method, answers measured against clj 1.12.6
		assertBothEqual("(println (String/.toUpperCase \"abc\"))", "ABC\n");
		assertBothEqual("(println (String/.substring \"hello\" 1 3))", "el\n");
		assertBothEqual("(println (map String/.length [\"ab\" \"abcd\"]))", "(2 4)\n");
		assertBothEqual("(prn ((fn [f] (f \"x\" 0)) String/.charAt))", "\\x\n");
		assertBothEqual("(prn (map String/.indexOf [\"abc\"] [\"c\"] [1]))", "(2)\n");
		assertBothEqual("(prn (apply String/.substring [\"hello\" 1]))", "\"ello\"\n");
		assertBothEqual("(prn ((comp String/.length String/.trim) \" ab \"))", "2\n");
		assertBothEqual("(prn (Object/.toString 5))", "\"5\"\n");
		assertBothEqual("(ns qi (:import (java.util ArrayList)))"
				+ " (let [a (ArrayList/new)] (.add a 1) (println (ArrayList/.size a) (ArrayList/.isEmpty a)"
				+ " (map ArrayList/.isEmpty [(ArrayList/new)])))", "1 false (true)\n");
		assertBothEqual("(println (str (StringBuilder/.append (StringBuilder/new \"a\") \"b\")))", "ab\n");
	}

	@Test
	void qualifiedConstructorsConstruct() throws Exception {
		assertBothEqual("(prn (String/new \"q\"))", "\"q\"\n");
		assertBothEqual("(prn (map String/new [\"a\"]))", "(\"a\")\n");
		assertBothEqual("(prn (map (comp str StringBuilder/new) [\"a\" \"b\"]))", "(\"a\" \"b\")\n");
		assertBothEqual("(ns qc (:import (java.util ArrayList)))"
				+ " (let [a (ArrayList/new 4)] (ArrayList/.add a \"x\") (println (str a)))", "[x]\n");
		assertBothEqual("(defrecord R [a b]) (prn (R/new 1 2) (map R/new [3] [4]))",
				"#user.R{:a 1, :b 2} (#user.R{:a 3, :b 4})\n");
		assertBothEqual("(let [w (java.io.StringWriter/new)] (.write w \"hi\") (println (str w)))", "hi\n");
		assertBothEqual("(prn (read (java.io.PushbackReader/new (java.io.StringReader/new \"(1 2)\"))))", "(1 2)\n");
	}

	@Test
	void paramTagsSelectTheOverload() throws Exception {
		// ^[double] picks abs(double) over the cost rule's abs(long), like the oracle
		assertBothEqual("(prn (map ^[double] Math/abs [-1 2]) (^[double] Math/abs -1))", "(1.0 2.0) 1.0\n");
		assertBothEqual("(prn (^[long] Long/toString 5) (map ^[long] Long/toString [1 2]))", "\"5\" (\"1\" \"2\")\n");
		assertBothEqual("(prn (map ^[int] String/.charAt [\"ab\"] [1]))", "(\\b)\n");
		assertBothEqual("(prn (^[int int] String/.substring \"hello\" 1 3))", "\"el\"\n");
		assertBothEqual("(prn (map ^[_ _] String/.substring [\"hello\"] [1] [3]))", "(\"el\")\n");
		assertBothEqual("(prn (map ^[] String/.length [\"abc\"]) (^[] String/new))", "(3) \"\"\n");
		assertBothEqual("(println (.capacity (^[int] StringBuilder/new 64)) (str (^[String] StringBuilder/new \"z\")))",
				"64 z\n");
		assertBothEqual("(let [b (^[int] StringBuilder/new 64)] (println (StringBuilder/.capacity b)))", "64\n");
		assertBothEqual("(ns qt (:import (java.util ArrayList Collection)))"
				+ " (println (map ^[Collection] ArrayList/new [[1 2]]))", "(#<java java.util.ArrayList>)\n");
		assertBothEqual("(println (^[objects] java.util.Arrays/toString (make-array Object 2))"
				+ " (^[Object/1] java.util.Arrays/toString (make-array Object 1)))", "[null, null] [null]\n");
		// on a name a var claims, the tags stay plain reader metadata
		assertBothEqual("(println (^[long] clojure.string/upper-case \"a\"))", "A\n");
	}

	@Test
	void qualifiedMethodRefusals() {
		// the oracle refuses these at compile time
		assertThatThrownBy(() -> interpret("(println (map String/.foo [\"x\"]))"))
			.hasMessageContaining("no matches found for instance method foo in class java.lang.String");
		assertThatThrownBy(() -> runOnJvm("(println (map Math/.abs [1]))"))
			.hasMessageContaining("no matches found for instance method abs in class java.lang.Math");
		assertThatThrownBy(() -> interpret("(println (^[int] String/.substring \"hello\" 1 3))"))
			.hasMessageContaining("expected 1 arguments, but received 2");
		assertThatThrownBy(() -> runOnJvm("(println (^[int] String/.substring \"hello\" 1 3))"))
			.hasMessageContaining("expected 1 arguments, but received 2");
		assertThatThrownBy(() -> interpret("(println ((fn [f] (f \"x\" 0 1)) String/.charAt))"))
			.hasMessageContaining("wrong number of arguments passed to: String/.charAt");
	}

	@Test
	void arraysRoundTripThroughMakeArrayAgetAsetAlength() throws Exception {
		// the book's interop.clj painstakingly-create-array shape: the class spells
		// the element type and is ignored, every array here is general
		assertBothEqual("(let [a (make-array String 3)] (aset a 0 \"x\") (println (aget a 0)))", "x\n");
		assertBothEqual("(let [a (make-array String 3)] (aset a 0 \"x\") (println (alength a)))", "3\n");
		assertBothEqual(
				"(let [b (make-array String 2 2)] (aset b 0 1 \"y\") (println (aget b 0 1)) (println (alength b)))",
				"y\n2\n");
	}

	@Test
	void inIsBoundAndReadsThroughBinding() throws Exception {
		// the book's hangman take-guess input shape, mocked: *in* is bound (never
		// real stdin here) and rebinding it feeds (.readLine *in*)
		assertBothEqual("(println (nil? *in*))", "false\n");
		assertBothEqual(
				"(binding [*in* (java.io.BufferedReader. (java.io.StringReader. \"hello\"))] (println (.readLine *in*)))",
				"hello\n");
	}

	@Test
	void hostBooleansPrintFalse() throws Exception {
		// a host-object boolean answers T-or-false when the receiver's class is
		// known (a construction literal, a let/if-let/when-let local bound to
		// one, or a .. step's declared return), like the oracle; any other
		// receiver keeps the shared java: unmarshal
		assertBothEqual("(println (.contains (java.util.ArrayList. [1]) 2))", "false\n");
		assertBothEqual("(println (.isEmpty (java.util.ArrayList. [1])))", "false\n");
		assertBothEqual("(println (.isEmpty (java.util.ArrayList.)))", "true\n");
		assertBothEqual("(let [al (java.util.ArrayList. [1])] (println (.contains al 2)))", "false\n");
		assertBothEqual("(let [al (java.util.ArrayList.)] (println (.isEmpty al)))", "true\n");
		assertBothEqual("(println (if-let [al (java.util.ArrayList. [1])] (.isEmpty al) :e))", "false\n");
		assertBothEqual("(println (when-let [al (java.util.ArrayList. [1])] (.contains al 2)))", "false\n");
		assertBothEqual("(println (.. (java.util.ArrayList. [1]) (subList 0 1) (isEmpty)))", "false\n");
		assertBothEqual("(println (.. (java.util.ArrayList.) (subList 0 0) (isEmpty)))", "true\n");
		// if/eq/str see the false object, like the oracle
		assertBothEqual("(println (if (.isEmpty (java.util.ArrayList. [1])) :empty :full))", ":full\n");
		assertBothEqual("(println (= (.contains (java.util.ArrayList. [1]) 2) false))", "true\n");
		assertBothEqual("(println (str (.contains (java.util.ArrayList. [1]) 2)))", "false\n");
		// non-boolean answers are untouched
		assertBothEqual("(println (.size (java.util.ArrayList. [1 2])))", "2\n");
		assertBothEqual("(println (.toString (java.util.ArrayList. [1])))", "[1]\n");
		assertBothEqual("(println (.. (java.util.ArrayList. [1]) (subList 0 1) (size)))", "1\n");
	}

	@Test
	void memfnCallsHostMethods() throws Exception {
		assertBothEqual("(println (.toString ((memfn append x) (StringBuilder. \"a\") \"b\")))", "ab\n");
		assertBothEqual("(println (map (memfn toString) [(StringBuilder. \"a\")]))", "(a)\n");
	}

	@Test
	void proxyImplementsASingleInterface() throws Exception {
		assertBothEqual("(println (.get (proxy [java.util.function.Supplier] [] (get [] 42))))", "42\n");
		assertBothEqual("(println (.get (proxy [java.util.function.Supplier] [] (get [] (+ 40 2)))))", "42\n");
		assertBothEqual("(println (let [p (proxy [java.util.function.Supplier] [] (get [] \"hi\"))] (.get p)))",
				"hi\n");
	}

	// Oracle (clj 1.12.6.1673): one proxy implements every interface of its vector; a
	// name two interfaces declare (Consumer.accept(Object), IntConsumer.accept(int))
	// runs the one body; Java takes the object as either listener (the book's snake.clj
	// shape, less its JPanel superclass); a method left out is refused when it is called.
	@Test
	void proxyImplementsSeveralInterfaces() throws Exception {
		assertBothEqual("""
				(def p (proxy [java.util.function.Consumer java.util.function.IntConsumer] []
				         (accept [x] (println :got x))))
				(.forEach (java.util.List/of "s") p)
				(.forEach (java.util.stream.IntStream/range 3 4) p)
				""", ":got s\n:got 3\n");
		assertBothEqual("""
				(ns listenersns (:import (java.awt.event ActionListener KeyListener)))
				(def q (proxy [ActionListener KeyListener] []
				         (actionPerformed [e] (println :clicked e))
				         (keyTyped [e])))
				(.actionPerformed q nil)
				(.keyTyped q nil)
				(println (count (.getActionListeners (javax.swing.Timer. 10 q))))
				(println (try (.keyPressed q nil) (catch Exception e (ex-message e))))
				""", ":clicked nil\n1\nno proxy method: keyPressed\n");
	}

	// Oracle: a proxy over a class extends it -- the sequences test's File shape:
	// the constructor arguments choose the superclass constructor, a named method
	// runs its body, an unnamed one is inherited.
	@Test
	void proxyOverAClassExtendsIt() throws Exception {
		assertBothEqual("""
				(def p (proxy [java.io.File] ["recent"] (lastModified [] 42)))
				(println (.lastModified p))
				(println (.getName p))
				(println (.toString p))
				""", "42\nrecent\nrecent\n");
	}

	// Oracle: toString/equals/hashCode run their bodies; this is the proxy object;
	// proxy-super calls the superclass implementation.
	@Test
	void proxyOverAClassSeesThisAndSuper() throws Exception {
		assertBothEqual("""
				(ns thisns (:import (java.io File)))
				(def p (proxy [File] ["x"]
				         (toString [] (str "super-was:" (proxy-super toString)))
				         (equals [o] true)
				         (hashCode [] 7)))
				(println (.toString p))
				(println (.equals p p))
				(println (.hashCode p))
				(println (.getName p))
				""", "super-was:x\ntrue\n7\nx\n");
	}

	// Oracle: a protected method overrides -- paintComponent records -- while an
	// unnamed one is inherited.
	@Test
	void proxyOverAClassOverridesProtectedMethods() throws Exception {
		assertBothEqual("""
				(ns paintns (:import (javax.swing JPanel)))
				(def seen (atom []))
				(def p (proxy [JPanel] [] (paintComponent [g] (swap! seen conj :painted))))
				(.paintComponent p nil)
				(println @seen)
				(println (.isOpaque p))
				""", "[:painted]\ntrue\n");
	}

	// The book's snake.clj shape: a class with interfaces over a constructor without
	// arguments, this with a type hint, and an inherited method beside the bodies.
	@Test
	void proxyOverAClassWithInterfacesRunsSnakeShapes() throws Exception {
		assertBothEqual("""
				(ns snakens (:import (javax.swing JPanel) (java.awt.event ActionListener KeyListener)))
				(def p (proxy [JPanel ActionListener KeyListener] []
				         (actionPerformed [e] (.repaint ^JPanel this))
				         (keyPressed [e] nil)
				         (keyReleased [e] nil)
				         (keyTyped [e] nil)
				         (toString [] "panel!")))
				(println (.toString p))
				(.actionPerformed p nil)
				(println :repaint-ok)
				(println (.isOpaque p))
				""", "panel!\n:repaint-ok\ntrue\n");
	}

	// The book's interop.clj shape: a SAX handler proxy receives parser callbacks.
	@Test
	void proxyOverAClassHandlesSaxCallbacks() throws Exception {
		java.nio.file.Path fixture = workDir.resolve("sax.xml");
		java.nio.file.Files.writeString(fixture, "<a><b/></a>");
		String path = "\"" + fixture.toString().replace("\\", "\\\\") + "\"";
		assertBothEqual(
				"(ns saxns (:import (org.xml.sax.helpers DefaultHandler)))" + "(def seen (atom []))"
						+ "(def h (proxy [DefaultHandler] []"
						+ " (startElement [uri local qname attrs] (swap! seen conj qname))"
						+ " (endElement [uri local qname] (swap! seen conj (str \"/\" qname)))))"
						+ "(let [f (javax.xml.parsers.SAXParserFactory/newInstance)]"
						+ " (.parse (.newSAXParser f) (java.io.File. " + path + ") h))" + "(println @seen)",
				"[a b /b /a]\n");
	}

	// An unnamed abstract method throws with the method's name when it is called; a
	// final superclass, an unknown method, a second class, a duplicate method, an
	// argument vector that is no vector and a proxy-super outside a method are
	// refused by name.
	@Test
	void proxyOverAClassRefusals() throws Exception {
		assertBothEqual("""
				(ns uoens (:import (java.util AbstractList)) (:require [clojure.string :as s]))
				(def a (proxy [AbstractList] [] (size [] 0)))
				(println (.size a))
				(println (try (.get a 0)
				           (catch Exception e (s/includes? (ex-message e) "UnsupportedOperationException: get"))))
				""", "0\ntrue\n");
		assertThatThrownBy(
				() -> interpret("(ns proxyns (:import (java.lang String))) (proxy [String] [] (toString [] \"x\"))"))
			.isInstanceOf(Exception.class)
			.hasMessageContaining("proxy cannot extend final class java.lang.String");
		assertThatThrownBy(
				() -> runOnJvm("(ns proxyns (:import (java.lang String))) (proxy [String] [] (toString [] \"x\"))"))
			.isInstanceOf(Exception.class)
			.hasMessageContaining("proxy cannot extend final class java.lang.String");
		assertThatThrownBy(() -> interpret("(proxy [java.io.File] [\"x\"] (nope [] 1))")).isInstanceOf(Exception.class)
			.hasMessageContaining("has no method nope");
		assertThatThrownBy(() -> runOnJvm("(proxy [java.io.File] [\"x\"] (nope [] 1))")).isInstanceOf(Exception.class)
			.hasStackTraceContaining("java:subclass java.io.File is left to run time");
		assertThatThrownBy(() -> interpret("(proxy [java.io.File java.lang.String] [] (toString [] \"x\"))"))
			.isInstanceOf(Exception.class)
			.hasMessageContaining("is a class, not an interface");
		assertThatThrownBy(() -> interpret("(proxy [java.io.File] [\"x\"] (getName [] 1) (getName [] 2))"))
			.isInstanceOf(Exception.class)
			.hasMessageContaining("proxy defines method getName twice");
		assertThatThrownBy(() -> interpret("(proxy [java.io.File] \"x\" (getName [] 1))")).isInstanceOf(Exception.class)
			.hasMessageContaining("proxy takes an argument vector, not");
		assertThatThrownBy(() -> interpret("(proxy-super toString)")).isInstanceOf(Exception.class)
			.hasMessageContaining("proxy-super outside a proxy method");
	}

	@Test
	void stringSplitStaysLiteral() throws Exception {
		assertBothEqual("(println (.split \"aaa\" \".\"))", "(aaa)\n");
		assertBothEqual("(println (.split \"a,b\" \",\"))", "(a b)\n");
	}

	@Test
	void filesRoundTripThroughSpitSlurpAndLineSeq() throws Exception {
		// File IO lives here with the interop cases for the interpreter and the
		// JVM; the wasm legs (a --dir preopen) live in ClojureWasmFileIoTest and
		// the un-preopened refusal in ClojureWasmFileRefusalTest.
		String path = "\"" + workDir.resolve("io.txt").toString().replace("\\", "\\\\") + "\"";
		assertBothEqual("(spit " + path + " \"a\\nb\") (println (slurp " + path + "))", "a\nb\n");
		assertBothEqual("(spit " + path + " \"a\\nb\") (println (line-seq " + path + "))", "(a b)\n");
		assertBothEqual(
				"(spit " + path + " \"a\\nb\") (spit " + path + " \"c\" :append true) (println (slurp " + path + "))",
				"a\nbc\n");
		assertBothEqual("(spit " + path + " \"a\\nb\") (println (map slurp [" + path + "]))", "(a\nb)\n");
	}

	@Test
	void spitWritesStrSpellingOfNonStrings() throws Exception {
		// spit writes (str content): a list, vector, map, number and record
		// go through the str spelling, nil writes nothing, a string is written
		// as before; :append included. The wasm legs (a --dir preopen) live in
		// ClojureWasmFileIoTest.
		String path = "\"" + workDir.resolve("spit.txt").toString().replace("\\", "\\\\") + "\"";
		assertBothEqual("(spit " + path + " '(1 2)) (println (slurp " + path + "))", "(1 2)\n");
		assertBothEqual("(spit " + path + " [1 2]) (println (slurp " + path + "))", "[1 2]\n");
		assertBothEqual("(spit " + path + " {:a 1}) (println (slurp " + path + "))", "{:a 1}\n");
		assertBothEqual("(spit " + path + " 42) (println (slurp " + path + "))", "42\n");
		assertBothEqual("(spit " + path + " nil) (println (pr-str (slurp " + path + ")))", "\"\"\n");
		assertBothEqual(
				"(spit " + path + " \"s\") (spit " + path + " '(9) :append true) (println (slurp " + path + "))",
				"s(9)\n");
		assertBothEqual("(defrecord SpitR [a b]) (spit " + path + " (->SpitR 1 2)) (println (slurp " + path + "))",
				"#user.SpitR{:a 1, :b 2}\n");
		assertBothEqual("(defrecord SpitS [a b]) (spit " + path + " \"s\") (spit " + path
				+ " (->SpitS 1 2) :append true)" + " (println (slurp " + path + "))", "s#user.SpitS{:a 1, :b 2}\n");
	}

	@Test
	void readTakesBackWhatSpitWrote() throws Exception {
		// the book's concurrency.clj backup shape -- spit a list of records, read
		// it back through a PushbackReader over clojure.java.io/reader -- answers = to
		// what was written, like the oracle; read leaves the reader right after the
		// datum. The wasm legs (a --dir preopen) live in ClojureWasmFileIoTest.
		String path = "\"" + workDir.resolve("backup.clj").toString().replace("\\", "\\\\") + "\"";
		String prelude = "(ns backupio (:require [clojure.java.io :refer [reader]]))"
				+ " (defrecord Message [sender text]) (def msg (->Message \"unit test\" \"test message\"))";
		assertBothEqual(
				prelude + " (spit " + path + " (list msg)) (println (slurp " + path + "))"
						+ " (println (= (read (java.io.PushbackReader. (reader " + path + "))) (list msg)))",
				"(#backupio.Message{:sender \"unit test\", :text \"test message\"})\ntrue\n");
		assertBothEqual(prelude + " (spit " + path + " \"[1 2] :k\\nnext line\")"
				+ " (with-open [r (java.io.PushbackReader. (reader " + path + "))]"
				+ " (prn (read r) (read r) (.readLine r) (read r false :eof)))", "[1 2] :k \"\" next\n");
		// a host reader is no stream: read says what it reads from
		assertThatThrownBy(
				() -> interpret("(let [sr (java.io.StringReader. \"1\")] (read (java.io.PushbackReader. sr)))"))
			.hasMessageContaining("read needs a reader");
	}

	@Test
	void filesRoundTripThroughReaderAndLineSeq() throws Exception {
		// clojure.java.io/reader opens a buffered file-stream reader: line-seq reads
		// it without closing (with-open owns closing, like the oracle), over the
		// vendored words fixture rather than the book's 32k corpus. Interpreter and
		// JVM here; the wasm preopen leg is in ClojureWasmFileIoTest.
		java.nio.file.Path fixture = workDir.resolve("reader-words.txt");
		try (java.io.InputStream in = ClojureInteropTest.class.getResourceAsStream("/clojure-words.txt")) {
			assertThat(in).isNotNull();
			java.nio.file.Files.copy(in, fixture);
		}
		String path = "\"" + fixture.toString().replace("\\", "\\\\") + "\"";
		String prelude = "(ns wordsio (:require [clojure.java.io :as jio] [clojure.string :as s])) ";
		// the hangman available-words shape: lowercase-only words survive, like the
		// oracle
		assertBothEqual(
				prelude + "(with-open [r (jio/reader " + path + ")]"
						+ " (println (apply vector (filter (fn [w] (= w (s/lower-case w))) (line-seq r)))))",
				"[apple fig cherry kiwi ]\n");
		// the eager.clj non-blank-lines count over the same fixture
		assertBothEqual(prelude + "(println (count (remove s/blank? (line-seq (jio/reader " + path + ")))))", "6\n");
		// the eager.clj transducer shapes: non-blank-lines pours the lines
		// through (filter non-blank?) into a vector, line-count reduces an eduction
		String eager = prelude + "(defn non-blank? [s] (not (s/blank? s)))";
		assertBothEqual(eager + "(with-open [r (jio/reader " + path + ")]"
				+ " (println (count (into [] (filter non-blank?) (line-seq r)))))", "6\n");
		assertBothEqual(
				eager + "(with-open [r (jio/reader " + path + ")]"
						+ " (println (reduce (fn [cnt el] (inc cnt)) 0 (eduction (filter non-blank?) (line-seq r)))))",
				"6\n");
		// the sequences.clj with-open shape over a referred reader, answering the line
		// count
		assertBothEqual("(ns refns (:require [clojure.java.io :refer [reader]]))" + "(with-open [r (reader " + path
				+ ")] (println (count (line-seq r))))", "7\n");
		// with-open closes: reading after the close signals, like the oracle
		assertBothEqual(prelude + "(def closed (jio/reader " + path + "))" + "(with-open [r closed] (line-seq r))"
				+ "(println (try (line-seq closed) (catch Exception e :closed)))", ":closed\n");
		// the line-seq path form keeps answering strictly
		assertBothEqual(prelude + "(println (line-seq " + path + "))", "(apple Banana fig cherry DATE kiwi )\n");
	}

	@Test
	void quotedRequiresWireTheBookShapes() throws Exception {
		// life_without_multi.clj's my-print-vector and sequences.clj's non-blank?
		// spell their libspecs quoted (the oracle's bare-require spelling): a
		// quoted :as over .write/str-join, and quoted :refer of reader/blank? over
		// with-open/line-seq and the vendored fixture. Interpreter and JVM only
		// (files), like the pins above; the wasm preopen leg is in
		// ClojureWasmFileIoTest.
		java.nio.file.Path fixture = workDir.resolve("libspec-words.txt");
		try (java.io.InputStream in = ClojureInteropTest.class.getResourceAsStream("/clojure-words.txt")) {
			assertThat(in).isNotNull();
			java.nio.file.Files.copy(in, fixture);
		}
		String path = "\"" + fixture.toString().replace("\\", "\\\\") + "\"";
		assertBothEqual(
				"(require '[clojure.string :as s])" + "(defn my-print-vector [ob] (.write *out* \"[\")"
						+ " (.write *out* (s/join \" \" ob)) (.write *out* \"]\"))" + "(my-print-vector [\"a\" \"b\"])",
				"[a b]");
		assertBothEqual("(require '[clojure.java.io :refer [reader]])" + "(require '[clojure.string :refer [blank?]])"
				+ "(defn non-blank? [line] (not (blank? line)))" + "(with-open [r (reader " + path + ")]"
				+ " (println (count (filter non-blank? (line-seq r)))))", "6\n");
	}

	@Test
	void regexRuntimeSignalsLikeTheOracle() throws Exception {
		// re-groups past no match, a bad group reference, a dangling $, an
		// unsupported construct and non-pattern arguments all signal, on both
		// backends. Value legs run too (re-find/re-seq/re-matches as values).
		assertBothEqual("(println (map re-pattern [\"a+\" \"b+\"]))", "(#\"a+\" #\"b+\")\n");
		assertBothEqual("(println (map #(re-find #\"a\" %) [\"xa\" \"y\"]))", "(a nil)\n");
		assertThatThrownBy(() -> interpret("(println (re-groups (re-matcher #\"a\" \"a\")))"))
			.isInstanceOf(Exception.class)
			.hasMessageContaining("No match found");
		assertThatThrownBy(() -> runOnJvm("(println (re-groups (re-matcher #\"a\" \"a\")))"))
			.isInstanceOf(Exception.class);
		assertThatThrownBy(() -> interpret("(println (clojure.string/replace \"a\" #\"a\" \"$\"))"))
			.isInstanceOf(Exception.class);
		assertThatThrownBy(() -> runOnJvm("(println (clojure.string/replace \"a\" #\"a\" \"$\"))"))
			.isInstanceOf(Exception.class);
		assertThatThrownBy(() -> interpret("(println (re-find \"a+\" \"aaab\"))")).isInstanceOf(Exception.class);
		assertThatThrownBy(() -> runOnJvm("(println (re-find \"a+\" \"aaab\"))")).isInstanceOf(Exception.class);
		assertThatThrownBy(() -> interpret("(println (re-find #\"a(?=b)\" \"ab\"))")).isInstanceOf(Exception.class)
			.hasMessageContaining("unsupported regex");
		assertThatThrownBy(() -> runOnJvm("(println (re-find #\"a(?=b)\" \"ab\"))")).isInstanceOf(Exception.class);
	}

	@Test
	void keepAndUpdateSignalInsteadOfSkipping() {
		// (keep inc [1 nil 2]) throws on the oracle (nil is not a number): the
		// signal is pinned here, not a silent skip. Same for a missing update key.
		assertThatThrownBy(() -> interpret("(println (keep inc [1 nil 2]))")).isInstanceOf(Exception.class);
		assertThatThrownBy(() -> runOnJvm("(println (keep inc [1 nil 2]))")).isInstanceOf(Exception.class);
		assertThatThrownBy(() -> interpret("(println (update {:a 1} :missing inc))")).isInstanceOf(Exception.class);
		assertThatThrownBy(() -> runOnJvm("(println (update {:a 1} :missing inc))")).isInstanceOf(Exception.class);
	}

	private static void assertBothEqual(String source, String expected) throws Exception {
		assertThat(interpret(source)).isEqualTo(expected);
		assertThat(runOnJvm(source)).isEqualTo(expected);
	}

	private static String interpret(String program) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("clojure-interop", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : SourceLanguage.CLOJURE.read(program, Features.INTERPRETER, "test.clj")) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	@org.junit.jupiter.api.io.TempDir
	static java.nio.file.Path workDir;

	private static String runOnJvm(String program) throws Exception {
		String name = "ClojureInterop";
		JvmSourceCompiler.Result result = new JvmSourceCompiler(name).sourceLanguage("clojure").compile(program, null);
		java.nio.file.Files.write(workDir.resolve(name + ".class"), result.classBytes());
		for (var file : result.runtimeClasses().entrySet()) {
			java.nio.file.Path target = workDir.resolve(file.getKey());
			java.nio.file.Files.createDirectories(target.getParent());
			java.nio.file.Files.write(target, file.getValue());
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (var loader = new java.net.URLClassLoader(new java.net.URL[] { workDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader()); var _ = ThreadStdio.out(out)) {
			CliStack.call("clojure-interop", () -> {
				try {
					Method main = loader.loadClass(name).getMethod("main", String[].class);
					main.invoke(null, (Object) new String[0]);
				}
				catch (ReflectiveOperationException ex) {
					throw new IllegalStateException(ex);
				}
				return null;
			});
		}
		return out.toString(StandardCharsets.UTF_8);
	}

}
