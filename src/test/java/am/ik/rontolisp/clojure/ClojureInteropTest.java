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
	void staticsConstructorsAndFields() throws Exception {
		assertBothEqual("(println (Integer/parseInt \"42\"))", "42\n");
		assertBothEqual("(println (Math/max 3 7))", "7\n");
		assertBothEqual("(println (. Math max 3 7))", "7\n");
		assertBothEqual("(println (String. \"hi\"))", "hi\n");
		assertBothEqual("(println (new String \"hi\"))", "hi\n");
		assertBothEqual("(println (Integer/MAX_VALUE))", "2147483647\n");
		assertBothEqual("(println (.-x (java.awt.Point. 1 2)))", "1\n");
		assertBothEqual("(ns b05-imp (:import (java.awt Point))) (println (Point. 3 4))", "#<java java.awt.Point>\n");
	}

	@Test
	void hostObjectsChainThroughCalls() throws Exception {
		assertBothEqual("(println (.toString (. (StringBuilder. \"a\") (append \"b\"))))", "ab\n");
		assertBothEqual("(println (try (Integer/parseInt \"xx\") (catch Exception e \"bad\")))", "bad\n");
	}

	@Test
	void staticFieldsAnswerAsValues() throws Exception {
		// the book's snake.clj/atom_snake.clj dirs shape: a static field as a map
		// value, through an import
		assertBothEqual("(ns b20snake (:import (java.awt.event KeyEvent))) (println KeyEvent/VK_LEFT)", "37\n");
		assertBothEqual("(println Math/PI)", "3.141592653589793\n");
		assertBothEqual("(println java.lang.Math/PI)", "3.141592653589793\n");
		assertBothEqual("(ns b20imp (:import java.lang.Math)) (println Math/PI)", "3.141592653589793\n");
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
		assertBothEqual("(defn b20-blank? [s] (every? Character/isWhitespace s)) (println (b20-blank? \"   \"))",
				"true\n");
		assertBothEqual("(println (every? Character/isWhitespace \"   \"))", "true\n");
		assertBothEqual("(println (every? Character/isWhitespace \" a \"))", "false\n");
		assertBothEqual("(println (map Integer/toString [1 2]))", "(1 2)\n");
		assertBothEqual("(println (map Character/isWhitespace \" a\"))", "(true false)\n");
		assertBothEqual("(let [b20-isws Character/isWhitespace] (println (b20-isws \\space)))", "true\n");
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
		assertBothEqual("(let [b29-list (java.util.ArrayList. [1])] (println (.contains b29-list 2)))", "false\n");
		assertBothEqual("(let [b29-list (java.util.ArrayList.)] (println (.isEmpty b29-list)))", "true\n");
		assertBothEqual("(println (if-let [b34-list (java.util.ArrayList. [1])] (.isEmpty b34-list) :e))", "false\n");
		assertBothEqual("(println (when-let [b34-list (java.util.ArrayList. [1])] (.contains b34-list 2)))", "false\n");
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
				(ns b59 (:import (java.awt.event ActionListener KeyListener)))
				(def q (proxy [ActionListener KeyListener] []
				         (actionPerformed [e] (println :clicked e))
				         (keyTyped [e])))
				(.actionPerformed q nil)
				(.keyTyped q nil)
				(println (count (.getActionListeners (javax.swing.Timer. 10 q))))
				(println (try (.keyPressed q nil) (catch Exception e (ex-message e))))
				""", ":clicked nil\n1\nno proxy method: keyPressed\n");
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
		String path = "\"" + workDir.resolve("b15-io.txt").toString().replace("\\", "\\\\") + "\"";
		assertBothEqual("(spit " + path + " \"a\\nb\") (println (slurp " + path + "))", "a\nb\n");
		assertBothEqual("(spit " + path + " \"a\\nb\") (println (line-seq " + path + "))", "(a b)\n");
		assertBothEqual(
				"(spit " + path + " \"a\\nb\") (spit " + path + " \"c\" :append true) (println (slurp " + path + "))",
				"a\nbc\n");
		assertBothEqual("(spit " + path + " \"a\\nb\") (println (map slurp [" + path + "]))", "(a\nb)\n");
	}

	@Test
	void spitWritesStrSpellingOfNonStrings() throws Exception {
		// b74: spit writes (str content): a list, vector, map, number and record
		// go through the str spelling, nil writes nothing, a string is written
		// as before; :append included. The wasm legs (a --dir preopen) live in
		// ClojureWasmFileIoTest.
		String path = "\"" + workDir.resolve("b74-spit.txt").toString().replace("\\", "\\\\") + "\"";
		assertBothEqual("(spit " + path + " '(1 2)) (println (slurp " + path + "))", "(1 2)\n");
		assertBothEqual("(spit " + path + " [1 2]) (println (slurp " + path + "))", "[1 2]\n");
		assertBothEqual("(spit " + path + " {:a 1}) (println (slurp " + path + "))", "{:a 1}\n");
		assertBothEqual("(spit " + path + " 42) (println (slurp " + path + "))", "42\n");
		assertBothEqual("(spit " + path + " nil) (println (pr-str (slurp " + path + ")))", "\"\"\n");
		assertBothEqual(
				"(spit " + path + " \"s\") (spit " + path + " '(9) :append true) (println (slurp " + path + "))",
				"s(9)\n");
		assertBothEqual("(defrecord B74R [a b]) (spit " + path + " (->B74R 1 2)) (println (slurp " + path + "))",
				"#user.B74R{:a 1, :b 2}\n");
		assertBothEqual("(defrecord B74S [a b]) (spit " + path + " \"s\") (spit " + path + " (->B74S 1 2) :append true)"
				+ " (println (slurp " + path + "))", "s#user.B74S{:a 1, :b 2}\n");
	}

	@Test
	void filesRoundTripThroughReaderAndLineSeq() throws Exception {
		// clojure.java.io/reader opens a buffered file-stream reader: line-seq reads
		// it without closing (with-open owns closing, like the oracle), over the
		// vendored words fixture rather than the book's 32k corpus. Interpreter and
		// JVM here; the wasm preopen leg is in ClojureWasmFileIoTest.
		java.nio.file.Path fixture = workDir.resolve("b22-words.txt");
		try (java.io.InputStream in = ClojureInteropTest.class.getResourceAsStream("/clojure-b22-words.txt")) {
			assertThat(in).isNotNull();
			java.nio.file.Files.copy(in, fixture);
		}
		String path = "\"" + fixture.toString().replace("\\", "\\\\") + "\"";
		String prelude = "(ns b22io (:require [clojure.java.io :as jio] [clojure.string :as s])) ";
		// the hangman available-words shape: lowercase-only words survive, like the
		// oracle
		assertBothEqual(
				prelude + "(with-open [r (jio/reader " + path + ")]"
						+ " (println (apply vector (filter (fn [w] (= w (s/lower-case w))) (line-seq r)))))",
				"[apple fig cherry kiwi ]\n");
		// the eager.clj non-blank-lines count over the same fixture
		assertBothEqual(prelude + "(println (count (remove s/blank? (line-seq (jio/reader " + path + ")))))", "6\n");
		// the eager.clj transducer shapes (b60): non-blank-lines pours the lines
		// through (filter non-blank?) into a vector, line-count reduces an eduction
		String eager = prelude + "(defn b60-non-blank? [s] (not (s/blank? s)))";
		assertBothEqual(eager + "(with-open [r (jio/reader " + path + ")]"
				+ " (println (count (into [] (filter b60-non-blank?) (line-seq r)))))", "6\n");
		assertBothEqual(eager + "(with-open [r (jio/reader " + path + ")]"
				+ " (println (reduce (fn [cnt el] (inc cnt)) 0 (eduction (filter b60-non-blank?) (line-seq r)))))",
				"6\n");
		// the sequences.clj with-open shape over a referred reader, answering the line
		// count
		assertBothEqual("(ns b22ref (:require [clojure.java.io :refer [reader]]))" + "(with-open [r (reader " + path
				+ ")] (println (count (line-seq r))))", "7\n");
		// with-open closes: reading after the close signals, like the oracle
		assertBothEqual(prelude + "(def b22closed (jio/reader " + path + "))" + "(with-open [r b22closed] (line-seq r))"
				+ "(println (try (line-seq b22closed) (catch Exception e :closed)))", ":closed\n");
		// the line-seq path form keeps answering strictly
		assertBothEqual(prelude + "(println (line-seq " + path + "))", "(apple Banana fig cherry DATE kiwi )\n");
	}

	@Test
	void quotedRequiresWireTheBookShapes() throws Exception {
		// life_without_multi.clj's my-print-vector and sequences.clj's non-blank?
		// spell their libspecs quoted (the oracle's bare-require spelling, b24): a
		// quoted :as over .write/str-join, and quoted :refer of reader/blank? over
		// with-open/line-seq and the vendored fixture. Interpreter and JVM only
		// (files), like the pins above; the wasm preopen leg is in
		// ClojureWasmFileIoTest.
		java.nio.file.Path fixture = workDir.resolve("b24-words.txt");
		try (java.io.InputStream in = ClojureInteropTest.class.getResourceAsStream("/clojure-b22-words.txt")) {
			assertThat(in).isNotNull();
			java.nio.file.Files.copy(in, fixture);
		}
		String path = "\"" + fixture.toString().replace("\\", "\\\\") + "\"";
		assertBothEqual("(require '[clojure.string :as b24str])" + "(defn b24-my-print-vector [ob] (.write *out* \"[\")"
				+ " (.write *out* (b24str/join \" \" ob)) (.write *out* \"]\"))"
				+ "(b24-my-print-vector [\"a\" \"b\"])", "[a b]");
		assertBothEqual("(require '[clojure.java.io :refer [reader]])" + "(require '[clojure.string :refer [blank?]])"
				+ "(defn b24-non-blank? [line] (not (blank? line)))" + "(with-open [r (reader " + path + ")]"
				+ " (println (count (filter b24-non-blank? (line-seq r)))))", "6\n");
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
