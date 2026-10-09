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
	void aHostIteratorStepsThroughIteratorSeqAndAnIterableTypesVerbs() throws Exception {
		// answers measured against clj 1.12.6: iterator-seq steps a host Iterator, and an
		// Iterable type's iterator may hand one on to seq, reduce and into
		assertBothEqual("(println (iterator-seq (.iterator (java.util.ArrayList. [1 2 3]))))", "(1 2 3)\n");
		assertBothEqual("(deftype H [l] java.lang.Iterable (iterator [_] (.iterator l)))"
				+ " (println (seq (H. (java.util.ArrayList. [4 5]))) (reduce + (H. (java.util.ArrayList. [4 5])))"
				+ " (into [] (H. (java.util.ArrayList. [6]))))", "(4 5) 9 [6]\n");
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
	void collectionKeywordSymbolAndRatioReceiversAnswerTheirCommonMethods() throws Exception {
		// answers measured against clj 1.12.6; a host object of the same method name
		// still reaches java:call
		assertBothEqual("(defn sz [x] (.size x)) (println (sz [1 2 3]) (sz (java.util.ArrayList. [1 2])) (sz {:a 1}))",
				"3 2 1\n");
		assertBothEqual("(defn g [x k] (.get x k)) (println (g {\"a\" 1} \"a\")"
				+ " (g (doto (java.util.HashMap.) (.put \"a\" 2)) \"a\") (g [5 6] 1) (g (java.util.ArrayList. [7 8]) 0))",
				"1 2 6 7\n");
		assertBothEqual(
				"(defn nm [x] (.getName x))"
						+ " (println (nm :k) (nm 'sym) (nm (java.io.File. \"/tmp/x.txt\")) (nm String))",
				"k sym x.txt java.lang.String\n");
		assertBothEqual("(println (.count [1 2 3]) (.get {:a 1} :a) (.contains #{1} 1) (.getName :abc)"
				+ " (.getNamespace :a/b) (.numerator 1/3))", "3 1 true abc a 1\n");
		// a method left unmapped is refused by name (the oracle answers 994)
		assertBothEqual("(println (try (.hashCode [1 2]) (catch Exception e (.getMessage e))))",
				"Method hashCode taking 0 args is not supported for class clojure.lang.PersistentVector\n");
		assertBothEqual("(println (try (.size {:a 1} 2) (catch Exception e (.getMessage e))))",
				"No matching method size found taking 1 args for class clojure.lang.PersistentArrayMap\n");
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
	void aHostThrowableAnswersInstanceAndItsStackTraceFromTheHost() throws Exception {
		// measured against clj 1.12.6: a host throwable is no condition, so instance?
		// asks the host and getStackTrace answers the host's frames
		assertBothEqual(
				"(println (instance? Exception (java.net.URISyntaxException. \"in\" \"bad\"))"
						+ " (instance? RuntimeException (java.net.URISyntaxException. \"in\" \"bad\"))"
						+ " (pos? (count (.getStackTrace (java.net.URISyntaxException. \"in\" \"bad\")))))",
				"true false true\n");
	}

	@Test
	void aFailedHostCallIsCaughtAsTheExceptionTheHostThrew() throws Exception {
		// measured against clj 1.12.6: the class of what the member threw decides the
		// catch, and the catch binds that exception -- its message, class, cause and
		// members are the host's
		assertBothEqual(
				"(println (try (Integer/parseInt \"x\") (catch NumberFormatException e"
						+ " [(.getMessage e) (ex-message e) (.getLocalizedMessage e)])))",
				"[For input string: \"x\" For input string: \"x\" For input string: \"x\"]\n");
		assertBothEqual("(println (try (Integer/parseInt \"x\") (catch Exception e [(class e)"
				+ " (instance? IllegalArgumentException e) (str e) (.toString e) (ex-data e) (ex-cause e) (.getCause e)])))",
				"[java.lang.NumberFormatException true java.lang.NumberFormatException: For input string: \"x\""
						+ " java.lang.NumberFormatException: For input string: \"x\" nil nil nil]\n");
		assertBothEqual("(println (try (Class/forName \"no.Such\") (catch RuntimeException e :rt)"
				+ " (catch ClassNotFoundException e [:cnf (.getMessage e)])))", "[:cnf no.Such]\n");
		assertBothEqual("(println (try (try (Integer/parseInt \"x\") (catch ArithmeticException e :arith))"
				+ " (catch IllegalArgumentException e :iae)))", ":iae\n");
		assertBothEqual("(println (try (Thread/sleep -1) (catch IllegalArgumentException e (.getMessage e))))",
				"timeout value is negative\n");
		assertBothEqual("(println (try (.get (java.util.ArrayList.) 0) (catch IndexOutOfBoundsException e (class e))))",
				"java.lang.IndexOutOfBoundsException\n");
		assertBothEqual(
				"(println (try (java.net.URI. \"a b\") (catch java.net.URISyntaxException e [(.getIndex e) (.getReason e)])))",
				"[1 Illegal character in path]\n");
		assertBothEqual("(println (try (Integer/parseInt \"x\") (catch Throwable e (class e))))",
				"java.lang.NumberFormatException\n");
		assertBothEqual("(println (try (Integer/parseInt \"x\") (catch clojure.lang.ExceptionInfo e :info)"
				+ " (catch Exception e :exc)))", ":exc\n");
	}

	@Test
	void aCaughtHostExceptionRethrowsAndWrapsAsItself() throws Exception {
		// measured against clj 1.12.6: rethrown, it is the same object; as a cause, the
		// host's own
		assertBothEqual(
				"(println (try (try (Integer/parseInt \"x\") (catch Exception e (throw e)))"
						+ " (catch NumberFormatException e [:outer (.getMessage e)])))",
				"[:outer For input string: \"x\"]\n");
		assertBothEqual(
				"(println (let [c (atom nil)] (try (try (Integer/parseInt \"x\")"
						+ " (catch Exception e (reset! c e) (throw e))) (catch Exception e (identical? e @c)))))",
				"true\n");
		assertBothEqual(
				"(println (try (try (Integer/parseInt \"x\") (catch Exception e (throw (RuntimeException."
						+ " \"wrapped\" e)))) (catch RuntimeException e [(.getMessage e) (class (.getCause e))"
						+ " (ex-message (ex-cause e))])))",
				"[wrapped java.lang.NumberFormatException For input string: \"x\"]\n");
		assertBothEqual(
				"(println (try (Integer/parseInt \"x\") (catch Exception e (let [i (ex-info \"wrap\" {:a 1} e)]"
						+ " [(ex-message i) (ex-data i) (identical? e (ex-cause i)) (ex-message (ex-cause i))]))))",
				"[wrap {:a 1} true For input string: \"x\"]\n");
		assertBothEqual(
				"(println (try (try (Integer/parseInt \"x\") (catch Exception e (throw (ex-info \"wrap\""
						+ " {:a 1} e)))) (catch clojure.lang.ExceptionInfo e [(ex-message e) (class (ex-cause e))])))",
				"[wrap java.lang.NumberFormatException]\n");
		assertBothEqual("(def u (java.net.URISyntaxException. \"a\" \"b\"))"
				+ " (println (try (throw u) (catch Exception e [(identical? e u) (class e)])))"
				+ " (println (identical? u (ex-cause (ex-info \"w\" {} u))) (class (ex-cause (Exception. \"m\" u))))",
				"[true java.net.URISyntaxException]\ntrue java.net.URISyntaxException\n");
		assertBothEqual(
				"(require '[clojure.test :refer [is]])"
						+ " (println (class (is (thrown? NumberFormatException (Integer/parseInt \"x\")))))"
						+ " (println (.getMessage (is (thrown-with-msg? NumberFormatException #\"input string\""
						+ " (Integer/parseInt \"x\")))))",
				"java.lang.NumberFormatException\nFor input string: \"x\"\n");
	}

	@Test
	void anExceptionPassedToAHostMemberIsAHostThrowableOfItsClass() throws Exception {
		// measured against clj 1.12.6: an exception the program built crosses into Java
		// as a host throwable of its class, with its message and cause
		assertBothEqual("(let [u (java.io.UncheckedIOException. \"u\" (java.io.IOException. \"io\"))]"
				+ " (println [(.getMessage u) (.getMessage (.getCause u)) (class (.getCause u)) (ex-message (ex-cause u))]))",
				"[u io java.io.IOException io]\n");
		assertBothEqual(
				"(let [c (java.io.IOException. \"io\") u (java.io.UncheckedIOException. \"u\" c)]"
						+ " (println [(.getMessage (.getCause u)) (class (.getCause u))]))",
				"[io java.io.IOException]\n");
		assertBothEqual(
				"(println (.getMessage (java.util.concurrent.ExecutionException. (IllegalStateException. \"s\"))))",
				"java.lang.IllegalStateException: s\n");
		assertBothEqual("(println (let [f (java.util.concurrent.CompletableFuture.)]"
				+ " (.completeExceptionally f (ArithmeticException. \"boom\")) (try (.get f)"
				+ " (catch java.util.concurrent.ExecutionException e [(class (.getCause e)) (.getMessage (.getCause e))]))))",
				"[java.lang.ArithmeticException boom]\n");
		assertBothEqual("(println (try (Integer/parseInt \"x\") (catch Exception e (.getMessage (.getCause"
				+ " (java.io.UncheckedIOException. \"u\" (java.io.IOException. \"io\" e)))))))", "io\n");
		assertBothEqual("(println (try (throw (java.io.UncheckedIOException. \"u\" (java.io.IOException. \"io\")))"
				+ " (catch java.io.UncheckedIOException e [(ex-message e) (ex-message (ex-cause e)) (class (ex-cause e))])))",
				"[u io java.io.IOException]\n");
		assertBothEqual("(println (.getMessage (.getCause (java.util.concurrent.ExecutionException. \"x\""
				+ " (ex-info \"info\" {:a 1})))))", "info\n");
		assertBothEqual(
				"(println (let [e (Exception.)] (.getMessage (.getCause (java.util.concurrent.ExecutionException."
						+ " \"x\" e)))))",
				"nil\n");
		assertBothEqual("(println (let [e (Exception. \"outer\" (IllegalStateException. \"inner\"))] (.getMessage"
				+ " (.getCause (.getCause (java.util.concurrent.ExecutionException. \"x\" e))))))", "inner\n");
		// a host method of the exception reaches that host throwable, the same one each
		// time
		assertBothEqual("(println (let [e (Exception. \"x\")] (.addSuppressed e (IllegalStateException. \"s\"))"
				+ " [(count (.getSuppressed e)) (.getMessage (first (.getSuppressed e)))]))", "[1 s]\n");
		assertBothEqual("(println (let [e (ex-info \"i\" {:a 1})] (.addSuppressed e (Exception. \"s\"))"
				+ " [(count (.getSuppressed e)) (ex-data e)]))", "[1 {:a 1}]\n");
	}

	@Test
	void aThrownHostThrowableIsCaughtByItsOwnClassChain() throws Exception {
		// measured against clj 1.12.6: the host class's superclasses, read at run time,
		// decide the catch -- a checked exception passes a RuntimeException catch
		assertBothEqual("(println (try (throw (java.net.URISyntaxException. \"in\" \"bad\"))"
				+ " (catch java.net.URISyntaxException e (.getMessage e))))", "bad: in\n");
		assertBothEqual("(println (try (try (throw (java.net.URISyntaxException. \"in\" \"bad\"))"
				+ " (catch RuntimeException e :rte)) (catch Exception e :ex)))", ":ex\n");
	}

	@Test
	void theClassOfAHostThrowableNoConstructionNamesTakesItsSupersFromTheHost() throws Exception {
		// measured against clj 1.12.6: the program names no ZipException, so no row of
		// the lowering holds it; the host answers its supers
		assertBothEqual("(def z (try (throw (.newInstance (Class/forName \"java.util.zip.ZipException\")))"
				+ " (catch Exception e e)))"
				+ " (println (isa? (class z) java.io.IOException) (isa? (class z) Exception) (isa? (class z) Object))"
				+ " (println (sort (map #(apply str (remove #{\\:} (pr-str %))) (ancestors (class z)))))",
				"true true true\n"
						+ "(java.io.IOException java.io.Serializable java.lang.Exception java.lang.Object java.lang.Throwable)\n");
	}

	@Test
	void aHostClassObjectWalksJavaInheritanceInAHierarchy() throws Exception {
		// measured against clj 1.12.6: class of a host object is its class object, which
		// isa? the classes it extends and implements, Object among them; java.util.List
		// lowers to :list in a hierarchy position, which the host class still reaches (a
		// java.net.URI: a java.io.File is clojure.java.io's own value on every backend)
		assertBothEqual("(println (isa? (class (java.net.URI. \"x\")) Object)"
				+ " (isa? (class (java.util.ArrayList.)) java.util.List)"
				+ " (isa? (class (java.util.ArrayList.)) java.util.Map)"
				+ " (isa? (class (java.util.ArrayList.)) Exception)"
				+ " (isa? (class (java.net.URI. \"x\")) java.io.Serializable)"
				+ " (isa? (class (StringBuilder.)) CharSequence))", "true true false false true true\n");
		// a class object parent: the class's own spelling, or one of a value's class
		assertBothEqual("(let [p java.util.List] (println (isa? (class (java.util.ArrayList.)) p)"
				+ " (isa? (class (java.util.ArrayList.)) java.util.AbstractList)"
				+ " (isa? (class \"x\") (class (StringBuilder.)))"
				+ " (isa? [(class (java.util.ArrayList.))] [java.util.List])))", "true true false true\n");
		// parents and ancestors answer class objects, a program spelling no class too
		assertBothEqual(
				"(println (sort (map str (parents (class (java.util.ArrayList.))))))"
						+ " (println (sort (map str (ancestors (class (java.net.URI. \"x\"))))))"
						+ " (println (parents (class (Object.))) (ancestors (class (Object.))))",
				"(class java.util.AbstractList interface java.io.Serializable interface java.lang.Cloneable"
						+ " interface java.util.List interface java.util.RandomAccess)\n"
						+ "(class java.lang.Object interface java.io.Serializable interface java.lang.Comparable)\n"
						+ "nil nil\n");
		// what a super derives from, under any spelling of it, and descendants' refusal
		assertBothEqual(
				"(derive java.util.List ::seqy) (derive java.util.AbstractList ::abs)"
						+ " (def c (class (java.util.ArrayList.)))"
						+ " (println (isa? c ::seqy) (isa? c ::abs) (contains? (ancestors c) ::seqy)"
						+ " (contains? (ancestors c) ::abs))"
						+ " (println (try (descendants c) (catch UnsupportedOperationException e (ex-message e))))",
				"true true true true\nCan't get descendants of classes\n");
		// a class multimethod dispatches a host object through its interfaces
		assertBothEqual("(defmulti f class) (defmethod f java.util.List [x] :list) (defmethod f java.util.Map [x] :map)"
				+ " (defmethod f :default [x] :default)"
				+ " (println (f (java.util.ArrayList.)) (f (java.util.HashMap.)) (f (java.io.File. \"x\")) (f '(1)))",
				":list :map :default :list\n");
	}

	@Test
	void aMultimethodDispatchesOnAHostClassOfNoKind() throws Exception {
		// measured against clj 1.12.6: a class no kind or chain names is a dispatch value
		// of its own, found exactly and through Java inheritance, the most specific first
		assertBothEqual("(defmulti f class) (defmethod f java.io.File [x] :file)"
				+ " (defmethod f java.util.AbstractList [x] :alist)"
				+ " (defmethod f java.util.AbstractCollection [x] :acoll) (defmethod f java.util.List [x] :list)"
				+ " (defmethod f :default [x] :default)"
				+ " (println (f (java.io.File. \"x\")) (f (java.util.ArrayList.)) (f (java.util.ArrayDeque.))"
				+ " (f (java.util.LinkedList.)) (f 1))"
				+ " (println (some? (get-method f java.io.File)) (some? (get-method f java.util.HashMap)))"
				+ " (remove-method f java.io.File) (println (f (java.io.File. \"x\")))",
				":file :alist :acoll :alist :default\ntrue false\n:default\n");
		// a class value dispatched on itself, a dispatch vector, and prefer-method
		assertBothEqual("(defmulti g identity) (defmethod g java.util.AbstractMap [x] :amap)"
				+ " (defmethod g :default [x] :default)"
				+ " (println (g java.util.AbstractMap) (g java.util.HashMap) (g java.util.Map))"
				+ " (defmulti h (fn [a b] [(class a) (class b)])) (defmethod h [java.io.File String] [a b] :fs)"
				+ " (defmethod h :default [a b] :default) (println (h (java.io.File. \"x\") \"s\") (h \"s\" \"s\"))"
				+ " (defmulti k class) (defmethod k java.util.RandomAccess [x] :ra)"
				+ " (defmethod k java.util.AbstractList [x] :al) (prefer-method k java.util.RandomAccess java.util.AbstractList)"
				+ " (println (k (java.util.ArrayList.)))", ":amap :amap :default\n:fs :default\n:ra\n");
	}

	@Test
	void instanceOfAHostClassTestsTheValuesKindAndTheHostObjectsClass() throws Exception {
		// measured against clj 1.12.6: a class no kind or chain names tests a host object
		// by its host class, and an interface a Clojure value implements tests the kind
		assertBothEqual("(println (instance? java.io.File (java.io.File. \"x\")) (instance? java.util.List [1])"
				+ " (instance? java.util.AbstractList (java.util.ArrayList.)))", "true true true\n");
		assertBothEqual(
				"(println (instance? java.util.Map (java.util.HashMap.)) (instance? java.util.Map {:a 1})"
						+ " (instance? java.util.Map [1]) (instance? java.io.File \"x\")"
						+ " (instance? java.util.RandomAccess (java.util.ArrayList.)))",
				"true true false false true\n");
		// a core class a host object may also be: Number, CharSequence
		assertBothEqual(
				"(println (instance? Number (java.math.BigDecimal. \"1.5\")) (instance? Number 1)"
						+ " (instance? Number \"1\") (instance? CharSequence (StringBuilder. \"a\"))"
						+ " (instance? CharSequence \"a\") (instance? CharSequence 1))",
				"true true false true true false\n");
		assertBothEqual(
				"(println (instance? Comparable (java.io.File. \"x\")) (instance? Comparable :k)"
						+ " (instance? Iterable (java.util.ArrayDeque.)) (instance? Iterable #{1}))",
				"true true true true\n");
		// the value is evaluated once
		assertBothEqual("(let [f (fn [] (println :made) (java.util.LinkedList.))]"
				+ " (println (instance? java.util.Deque (f)) (instance? java.util.AbstractList (f))"
				+ " (instance? Boolean (f))))", ":made\n:made\n:made\ntrue true false\n");
		assertBothEqual("(println (instance? java.io.Serializable (Exception. \"x\"))"
				+ " (instance? java.io.Serializable (java.io.File. \"x\")) (instance? Object (java.io.File. \"x\")))",
				"true true true\n");
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
		assertBothEqual("(println (class (java.net.URI. \"foo\")))", "java.net.URI\n");
	}

	@Test
	void strOfAHostObjectIsItsToStringWhilePrintingStaysUnreadable() throws Exception {
		assertBothEqual("(println (str (StringBuilder. \"ab\") \"|\" (java.net.URI. \"foo\")))", "ab|foo\n");
		assertBothEqual("(println (str (java.util.ArrayList. [1 2])))", "[1, 2]\n");
		assertBothEqual("(println (str 1 :k [1 \"a\"] nil 's))", "1:k[1 \"a\"]s\n");
		assertBothEqual("(println (java.net.URI. \"foo\"))", "#<java java.net.URI>\n");
	}

	@Test
	void classOfAHostObjectIsItsHostClassSoDispatchReachesTheDefault() throws Exception {
		assertBothEqual("(println (= java.net.URI (class (java.net.URI. \"foo\"))))", "true\n");
		assertBothEqual("(println (.getName (class (java.net.URI. \"foo\"))))", "java.net.URI\n");
		assertBothEqual("(println (.getName ((comp class identity) (java.net.URI. \"foo\"))))", "java.net.URI\n");
		// the book's my-print: a host object misses every class row and lands on
		// :default, whose .toString reaches the host method (oracle #<foo>)
		assertBothEqual("(defmulti mp class) (defmethod mp String [s] s)"
				+ " (defmethod mp Number [n] (str \"n\" (.toString n)))"
				+ " (defmethod mp :default [x] (str \"#<\" (.toString x) \">\"))"
				+ " (println (mp 42) (mp (java.net.URI. \"foo\")))", "n42 #<foo>\n");
		assertBothEqual(
				"(defmulti mo class) (defmethod mo Object [x] :object)" + " (println (mo (java.net.URI. \"foo\")))",
				":object\n");
	}

	@Test
	void aJavaIoFileCrossesTheJavaBoundaryAsTheHostFile() throws Exception {
		// oracle-identical (clj 1.12.6, 2026-10-08): a File clojure.java.io makes is a
		// java.io.File to a Java member -- an argument (Objects/toString, compareTo), the
		// receiver of a member the namespace leaves to the host (toPath) -- and a host
		// File a member answers is a File to slurp, spit, the namespace's functions and
		// a protocol extended to java.io.File
		assertBothEqual("(def f (clojure.java.io/file \"a/b.txt\"))"
				+ " (prn (str (.toPath f)) (str (.getFileName (.toPath f))) (java.util.Objects/toString f))"
				+ " (prn (.compareTo (.getParentFile (java.io.File/createTempFile \"jio\" \".tmp\"))"
				+ " (clojure.java.io/file (System/getProperty \"java.io.tmpdir\"))))"
				+ " (def t (java.io.File/createTempFile \"jio\" \".txt\")) (spit t \"one\\ntwo\")"
				+ " (prn (slurp t) (with-open [r (clojure.java.io/reader t)] (doall (line-seq r))))"
				+ " (prn (= (clojure.java.io/file (str t)) (clojure.java.io/as-file t)) (instance? java.io.File t))"
				+ " (defprotocol Jp (jp [x])) (extend-protocol Jp java.io.File (jp [x] :file))"
				+ " (prn (jp t) (jp f) (clojure.java.io/delete-file t) (.exists (clojure.java.io/file (str t))))",
				"\"a/b.txt\" \"b.txt\" \"a/b.txt\"\n0\n\"one\\ntwo\" (\"one\" \"two\")\ntrue true\n:file :file true false\n");
	}

	@Test
	void printTraceElementSpellsAHostStackTraceElementLikeTheOracle() throws Exception {
		// oracle-identical (clj 1.12.6, 2026-10-08); a throwable built here has no frames
		// (clojure-spec clojure-stacktrace-prints-no-frames), so only a host element
		// reaches clojure.stacktrace/print-trace-element
		assertBothEqual("(require '[clojure.stacktrace :as st])"
				+ " (st/print-trace-element (StackTraceElement. \"my.ns$f__123\" \"invoke\" \"f.clj\" 10)) (newline)"
				+ " (st/print-trace-element (StackTraceElement. \"a.b$c\" \"doInvoke\" nil -1)) (newline)",
				"my.ns/f (f.clj:10)\na.b$c.doInvoke (:-1)\n");
	}

	@Test
	void hostKindPredicatesTestTheHostClass() throws Exception {
		// oracle-identical (clj 1.12.6); a Lisp value is no host object, so a string
		// is no uri? or inst? (the all-four-backend false answers are clojure-spec's)
		assertBothEqual("(def hu (java.util.UUID/nameUUIDFromBytes (.getBytes \"x\")))"
				+ " (prn (class? String) (class? \"a\") (class? 1) (inst? (java.util.Date/from (java.time.Instant/now)))"
				+ " (inst? (java.time.Instant/now)) (inst? \"2020\") (uuid? hu) (uuid? \"x\")"
				+ " (uri? (java.net.URI. \"http://a\")) (uri? \"http://a\") (map uuid? [hu 1]))",
				"true false false true true false true false true false (true false)\n");
	}

	@Test
	void instMsReadsAHostDateOrInstantBesideTheProgramsOwnInstants() throws Exception {
		// oracle-identical (clj 1.12.6): the Inst protocol's host rows beside a Date the
		// program read, whose class instance? names as the host's
		assertBothEqual("(def hd (java.util.Date/from (java.time.Instant/ofEpochMilli 5)))"
				+ " (prn (inst? hd) (inst-ms hd) (inst-ms (java.time.Instant/ofEpochMilli 7)) (inst-ms* hd)"
				+ " (map inst-ms [hd #inst \"1970-01-01T00:00:00.009Z\"]) (instance? java.util.Date hd)"
				+ " (instance? java.util.Date #inst \"2020\") (uuid? (java.util.UUID/randomUUID)) (uuid? #uuid \"1-1-1-1-1\"))",
				"true 5 7 5 (5 9) true true true true\n");
	}

	@Test
	void aDateOrUuidMadeHereCrossesTheJavaBoundaryAsTheHostObject() throws Exception {
		// oracle-identical (clj 1.12.6): a construction makes the value #inst or #uuid
		// reads, which a java: member is handed as the host Date, Timestamp or UUID,
		// and whose class's other methods the host object answers -- a Date it answers
		// coming back as one made here
		assertBothEqual(
				"(defn utc [p] (doto (java.text.SimpleDateFormat. p)"
						+ " (.setTimeZone (java.util.TimeZone/getTimeZone \"UTC\"))))"
						+ " (prn (.format (utc \"yyyy-MM-dd HH:mm:ss.SSS\") (java.util.Date. 1577934245678))"
						+ " (.format (utc \"yyyy\") #inst \"2020\") (map #(.format (utc \"yyyy-MM-dd\") %)"
						+ " [(java.sql.Timestamp. 0) #inst \"1999-12-31\"]))",
				"\"2020-01-02 03:04:05.678\" \"2020\" (\"1970-01-01\" \"1999-12-31\")\n");
		assertBothEqual(
				"(prn (str (.toInstant (java.util.Date. 1500))) (str (.toInstant (java.sql.Timestamp. 1500)))"
						+ " (.hashCode (java.util.Date. 1577836800000)) (.hashCode (java.util.UUID. -1 -2))"
						+ " (.getYear (java.util.Date. 1592222400000)) (.clone (java.util.Date. 5))"
						+ " (= (.clone (java.util.Date. 5)) (java.util.Date. 5)))",
				"\"1970-01-01T00:00:01.500Z\" \"1970-01-01T00:00:01.500Z\" 1583802735 1 120"
						+ " #inst \"1970-01-01T00:00:00.005-00:00\" true\n");
		assertBothEqual("(let [s (java.util.HashSet.) l (java.util.ArrayList.) m (java.util.HashMap.)"
				+ " c (java.util.Calendar/getInstance (java.util.TimeZone/getTimeZone \"UTC\"))]"
				+ " (.add s #uuid \"1-1-1-1-1\") (.add l (java.util.Date. 3)) (.put m (java.util.UUID. 1 2) \"v\")"
				+ " (.setTime c (java.util.Date. 86400000))"
				+ " (prn (.contains s (java.util.UUID/fromString \"1-1-1-1-1\")) (map inst-ms l) (= (.get l 0) (java.util.Date. 3))"
				+ " (.get m #uuid \"00000000-0000-0001-0000-000000000002\") (.get c java.util.Calendar/DAY_OF_MONTH)))",
				"true (3) true \"v\" 2\n");
		// a mutator but setTime would change a copy: refused by name (the oracle mutates)
		assertBothEqual("(prn (try (.setYear (java.util.Date. 0) 100) (catch Exception e (.getMessage e))))",
				"\"Method setYear taking 1 args is not supported for class java.util.Date\"\n");
	}

	@Test
	void aHostDateOrUuidIsEqualToAndComparesBesideOneMadeHere() throws Exception {
		// oracle-identical (clj 1.12.6) but the printed form: a Date or UUID a member
		// answers stays the host object, = to the value made here by the first one's
		// equals and ordered beside it by compareTo -- a host Comparable's compareTo
		// orders it -- and printed as the host object, where the oracle's print-method
		// writes #inst and #uuid
		String host = "(def hd (java.util.Date/from (java.time.Instant/ofEpochMilli 5)))"
				+ " (def hu (java.util.UUID/nameUUIDFromBytes (.getBytes \"abc\")))";
		assertBothEqual(
				host + " (prn (inst-ms hd) (str hu) (= hd (java.util.Date. 5)) (= (java.util.Date. 5) hd)"
						+ " (= #uuid \"90015098-3cd2-3fb0-9696-3f7d28e17f72\" hu) (= hd (java.sql.Timestamp. 5))"
						+ " (= (java.sql.Timestamp. 5) hd) (not= hd (java.util.Date. 6)))",
				"5 \"90015098-3cd2-3fb0-9696-3f7d28e17f72\" true true true true false true\n");
		assertBothEqual(
				host + " (prn (compare hd (java.util.Date. 6)) (compare (java.util.Date. 6) hd) (compare hd hd)"
						+ " (compare hu (java.util.UUID. 0 0)) (compare (java.util.UUID. 0 0) hu)"
						+ " (map inst-ms (sort [(java.util.Date. 9) hd (java.util.Date. 1)]))"
						+ " (map inst-ms (sorted-set (java.util.Date. 9) hd))"
						+ " (compare (java.time.Instant/ofEpochMilli 1) (java.time.Instant/ofEpochMilli 2))"
						+ " (map str (sort [(java.time.Instant/ofEpochMilli 3) (java.time.Instant/ofEpochMilli 1)])))",
				"-1 1 0 -1 1 (1 5 9) (5 9) -1000000 (\"1970-01-01T00:00:00.001Z\" \"1970-01-01T00:00:00.003Z\")\n");
		assertBothEqual(host + " (prn hd (pr-str [hu]))", "#<java java.util.Date> \"[#<java java.util.UUID>]\"\n");
	}

	@Test
	void classOfAnExceptionIsItsClassKeywordWithOrWithoutInterop() throws Exception {
		// an ex-info condition is no host object: the same keyword whether the program
		// uses interop (the host arm behind the exception arm) or not (no java: at all)
		String plain = "(println (try (class (ex-info \"a\" {})) (catch Exception e (ex-message e))))";
		assertBothEqual(plain, ":clojure.lang.ExceptionInfo\n");
		assertBothEqual("(println (.getName String)) " + plain, "java.lang.String\n:clojure.lang.ExceptionInfo\n");
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
	void aSubstringOfABuiltStringRefusesARangeOutsideIt() throws Exception {
		// .substring lowers to subseq; a string built at run time is the mutable
		// representation, whose lane used to crash on the JVM instead of refusing.
		assertBothEqual("""
				(defn f [s i j] (try (.substring s i j) (catch Exception e :refused)))
				(println (f (str "ab" "c") 1 2))
				(println (f (str "ab" "c") 2 1))
				(println (f (str "ab" "c") -1 2))
				(println (f (str "ab" "c") 1 5))
				""", "b\n:refused\n:refused\n:refused\n");
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
		// a host-object boolean answers T-or-false whatever the receiver (every host
		// call ends in :java-false), like the oracle
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

	// Oracle (clj 1.12.6): identical? on host objects is ==, while = asks equals -- a
	// host collection included, and so do a set's and a map's lookups. A proxy whose
	// equals answers true is not identical? to true or 1 and str shows its toString.
	// Before, measured 2026-10-04: the JVM answered equals for identical? (the proxy was
	// identical? to true and printed "true"), the interpreter for two Files.
	// Oracle (clj 1.12.6, measured 2026-10-08): a fn passed where a functional interface
	// is expected implements its method by the method's arguments -- a literal fn (a
	// resolved site), a local of a known receiver (a dispatched site), a var on a
	// receiver of unknown class (run time) and a static call alike -- and the interface's
	// default methods keep their bodies (Predicate/not calls negate on it). Before,
	// measured 2026-10-08 on the interpreter and the JVM, every call passed the method's
	// name first: "Function expects 0 arguments, got 1".
	@Test
	void aFnPassedWhereAnInterfaceIsExpectedImplementsItsMethodByItsArguments() throws Exception {
		assertBothEqual("(let [t (Thread. (fn [] (println \"ran\")))] (.start t) (.join t))", "ran\n");
		assertBothEqual("(.forEach (java.util.ArrayList. [1 2]) (fn [x] (println x)))", "1\n2\n");
		assertBothEqual("(println (.compute (doto (java.util.HashMap.) (.put \"a\" 1)) \"a\" (fn [k v] (inc v))))",
				"2\n");
		assertBothEqual("(defn each [coll f] (.forEach coll f)) (each (java.util.ArrayList. [3]) println)", "3\n");
		assertBothEqual("(defn each2 [f] (.forEach (java.util.ArrayList. [5]) f)) (each2 println)", "5\n");
		assertBothEqual("(println (.test (java.util.function.Predicate/not (fn [x] (when (odd? x) true))) 2))",
				"true\n");
		assertBothEqual("(println (.test (java.util.function.Predicate/not odd?) 2))", "true\n");
		assertBothEqual(
				"(let [l (java.util.ArrayList. [3 1 2])]"
						+ " (java.util.Collections/sort l (fn [a b] (compare a b))) (println (vec l))"
						+ " (.sort l (java.util.Comparator/comparing (fn [x] (- x)))) (println (vec l)))",
				"[1 2 3]\n[3 2 1]\n");
		assertBothEqual("(println (.get (.map (java.util.Optional/of 4) (fn [x] (* 2 x)))))", "8\n");
		// a fn of the wrong arity is called with the method's arguments, and refuses them
		assertThatThrownBy(() -> interpret("(.forEach (java.util.ArrayList. [2]) (fn [] 1))"))
			.hasStackTraceContaining("expects 0 arguments, got 1");
		assertThatThrownBy(() -> runOnJvm("(.forEach (java.util.ArrayList. [2]) (fn [] 1))"))
			.hasStackTraceContaining("expects 0 arguments, got 1");
		// Deviation: the oracle converts a fn only to an interface annotated
		// @FunctionalInterface (a PropertyChangeListener or a DocumentListener is a
		// ClassCastException there); here every abstract method of any interface calls
		// it.
		assertBothEqual("""
				(let [s (java.beans.PropertyChangeSupport. "b")]
				  (.addPropertyChangeListener s (fn [e] (println "pc" (.getNewValue e))))
				  (.firePropertyChange s "size" 1 2))
				(let [d (javax.swing.text.PlainDocument.)]
				  (.addDocumentListener d (fn [e] (println "doc" (str (.getType e)))))
				  (.insertString d 0 "x" nil))
				""", "pc 2\ndoc INSERT\n");
	}

	// Oracle (clj 1.12.6, measured 2026-10-08): false crosses to Java as Java's false --
	// an argument (a Boolean where an Object is expected) and a fn's or a proxy body's
	// answer for a boolean -- a map as a java.util.Map, and a fn is a Callable, a
	// Runnable
	// and a Comparator itself (clojure-spec pins the receiver half on every backend); a
	// proxy's constructor arguments convert a fn as every call does. Before, measured
	// 2026-10-08 on the interpreter and the JVM: "No matching method
	// java.util.ArrayList.add with 1 argument(s)", "java:reify: cannot return |false| as
	// boolean ...", "No matching constructor for java.util.HashMap", "java:call expects a
	// java object as the first argument, got #<lambda>". Deviation: a map argument is a
	// fresh LinkedHashMap whose vector and map values are converted too (the oracle's
	// toString spells them as Clojure's).
	@Test
	void falseAMapAndAFnCrossTheJavaBoundaryAsTheOraclesDo() throws Exception {
		assertBothEqual("(let [l (java.util.ArrayList.)] (.add l false) (.add l true) (.add l nil) (println (str l)))",
				"[false, true, null]\n");
		assertBothEqual("(println (Boolean/toString false) (java.util.Objects/toString false))", "false false\n");
		assertBothEqual("(let [l (java.util.ArrayList. [1 2 3])] (.removeIf l odd?) (println (vec l)))", "[2]\n");
		assertBothEqual("(println (.test (proxy [java.util.function.Predicate] [] (test [x] false)) 1))", "false\n");
		assertBothEqual("(let [p (proxy [java.util.function.Predicate] [] (test [x] (odd? x)))]"
				+ " (println (.test p 1) (.test p 2)))", "true false\n");
		assertBothEqual("(println (.booleanValue false) (.booleanValue true) (.equals false false))",
				"false true true\n");
		assertBothEqual("(println (str (java.util.HashMap. {\"a\" 1})) (str (java.util.TreeMap. {\"b\" 2 \"a\" 1})))",
				"{a=1} {a=1, b=2}\n");
		assertBothEqual("(let [l (java.util.ArrayList.)] (.add l {\"k\" false}) (println (str l)))", "[{k=false}]\n");
		assertBothEqual("(println (.call (fn [] 5)) (.run (fn [] 5)) (.compare (fn [a b] (< a b)) 2 1))", "5 nil 1\n");
		assertBothEqual("(let [t (proxy [Thread] [(fn [] (println \"ran\"))])] (.start t) (.join t))", "ran\n");
	}

	// Oracle (clj 1.12.6, measured 2026-10-08): Java's false is Clojure's false wherever
	// it comes back -- a host collection's element read by vec/seq/first/into/nth, a
	// Map's value by .get and get, an Object or boolean answer of a receiver of no known
	// class, a static field and a static method's Boolean, an array's elements, an
	// argument a fn or a proxy body receives from Java. Before, measured 2026-10-08 on
	// the interpreter and the JVM, every one was nil ((vec l) of a list holding false
	// [nil]).
	@Test
	void aHostFalseComesBackAsFalse() throws Exception {
		assertBothEqual(
				"(let [l (java.util.ArrayList.)] (.add l false) (println (vec l) (seq l) (first l) (into [] l)))",
				"[false] (false) false [false]\n");
		assertBothEqual("(println (.get (doto (java.util.HashMap.) (.put \"x\" false)) \"x\"))", "false\n");
		assertBothEqual(
				"(defn e? [l] (.isEmpty l)) (println (e? (java.util.ArrayList. [1])) (e? (java.util.ArrayList.)))",
				"false true\n");
		assertBothEqual(
				"(println (Boolean/FALSE) Boolean/FALSE (Boolean/valueOf \"false\") (Boolean/parseBoolean \"x\"))",
				"false false false false\n");
		assertBothEqual(
				"(let [l (java.util.ArrayList. [true false nil])] (println (seq l) (into [] l) (second l) (nth l 2)))",
				"(true false nil) [true false nil] false nil\n");
		assertBothEqual("(println (seq (.toArray (java.util.ArrayList. [false true]))))", "(false true)\n");
		assertBothEqual("(.forEach (java.util.ArrayList. [false]) (fn [x] (println :each x)))", ":each false\n");
		assertBothEqual("(let [m (java.util.HashMap. {\"a\" false})]"
				+ " (println (get m \"a\") (if (get m \"a\") :yes :no) (= false (get m \"a\")) (find m \"a\")))",
				"false :no true [a false]\n");
		assertBothEqual(
				"(println (.test (proxy [java.util.function.Predicate] [] (test [x] (println :proxy-arg x) true)) false))",
				":proxy-arg false\ntrue\n");
		assertBothEqual("(println (.getOrDefault (java.util.HashMap.) \"q\" false)"
				+ " (str (.get (doto (java.util.HashMap.) (.put \"x\" false)) \"x\")))", "false false\n");
		assertBothEqual(
				"(println (= [false] (java.util.List/of false)) (= {\"a\" false} (java.util.Map/of \"a\" false)))",
				"true true\n");
		assertBothEqual("(prn (java.util.ArrayList. [false]) (java.util.HashMap. {\"a\" false}))",
				"[false] {\"a\" false}\n");
	}

	// Oracle (clj 1.12.6, measured 2026-10-08): a fn is a Comparator whose compare is
	// AFunction.compare -- a true answer -1, a false one 1 when the fn answers true for
	// the arguments swapped and else 0, a number its intValue (a double or ratio
	// truncated, a long's or bigint's low 32 bits) -- whether Java calls it through
	// List.sort, Collections.sort or a TreeMap. Before, measured 2026-10-08 on the
	// interpreter and the JVM: "java:reify: cannot return T as int from
	// java.util.Comparator.compare" (a double or a ratio was refused too). Deviation: a
	// fn answering nil or a non-number is the java: refusal of its value, where the
	// oracle throws a NullPointerException or a ClassCastException.
	@Test
	void aFnPassedAsAComparatorComparesLikeTheOraclesAFunction() throws Exception {
		assertBothEqual("(let [l (java.util.ArrayList. [3 1 2])] (.sort l <) (println (vec l)))", "[1 2 3]\n");
		assertBothEqual("(let [l (java.util.ArrayList. [3 1 2])] (java.util.Collections/sort l >) (println (vec l)))",
				"[3 2 1]\n");
		assertBothEqual("(defn by-desc [a b] (> a b)) (let [l (java.util.ArrayList. [3 1 2])] (.sort l by-desc)"
				+ " (println (vec l)))", "[3 2 1]\n");
		assertBothEqual("(defn sorted [cmp] (let [l (java.util.ArrayList. [3 1 2])] (java.util.Collections/sort l cmp)"
				+ " (vec l))) (println (sorted compare) (sorted (fn [a b] (* 0.5 (- a b)))) (sorted (fn [a b] (/ (- b a) 2)))"
				+ " (sorted (fn [a b] (* (- a b) 4294967296))))", "[1 2 3] [1 3 2] [3 1 2] [3 1 2]\n");
		assertBothEqual("(let [t (java.util.TreeMap. >)] (.put t 1 \"a\") (.put t 2 \"b\") (println (keys t)))",
				"(2 1)\n");
		// a constructor taking a Comparator or a Collection takes the fn as the
		// Comparator, a functional interface (before, measured 2026-10-08: as the
		// Collection, "Function expects 2 arguments, got 0")
		assertBothEqual("(let [s (java.util.TreeSet. (fn [a b] (< a b)))] (.add s 2) (.add s 1) (.add s 3)"
				+ " (println (seq s)))", "(1 2 3)\n");
		assertBothEqual("(let [q (java.util.PriorityQueue. compare)] (.add q 2) (.add q 1) (println (.peek q)))",
				"1\n");
		assertBothEqual("(let [s (java.util.concurrent.ConcurrentSkipListSet. >)] (.add s 2) (.add s 3)"
				+ " (println (seq s)))", "(3 2)\n");
		assertBothEqual("(let [l (java.util.ArrayList. [\"b\" \"a\" \"c\"])] (.sort l (fn [a b] (neg? (compare a b))))"
				+ " (println (vec l)))", "[a b c]\n");
		assertBothEqual("(let [l (java.util.ArrayList. [3 1 2])] (println (try (.sort l (fn [a b] nil)) :sorted"
				+ " (catch Exception e :refused))))", ":refused\n");
	}

	// Oracle (clj 1.12.6, measured 2026-10-08): Java is handed the Clojure value itself
	// -- a set is a java.util.Set; a map, a record or a sorted map a java.util.Map; a
	// sorted set or a lazy seq a collection; a keyword or a symbol an object Java keys,
	// finds and orders by its spelling -- and a keyword or symbol Java hands back is
	// itself. Before, measured 2026-10-08 on the interpreter and the JVM: "No matching
	// constructor for java.util.HashSet with 1 argument(s)" (a record, a sorted set
	// alike), "No matching method java.util.HashMap.put with 2 argument(s)" (a keyword).
	// Deviation: what Java is handed is a copy, so its toString and a collection inside
	// it spell as Java's ([1, 2], not #{1 2}); a ratio or an atom is still refused.
	@Test
	void aSetAKeywordAndARecordCrossTheJavaBoundaryAsTheOraclesDo() throws Exception {
		assertBothEqual("(println (str (java.util.HashSet. #{1 2})) (str (java.util.HashMap. {:a 1})))",
				"[1, 2] {:a=1}\n");
		assertBothEqual(
				"(let [m (java.util.HashMap.)] (.put m :k 1) (prn (.get m :k) (get m :k) (keys m)"
						+ " (= :k (first (keys m))) (contains? m :k) (find m :k) (:k m)))",
				"1 1 (:k) true true [:k 1] 1\n");
		assertBothEqual("(let [s (java.util.HashSet.)] (.add s :a) (prn (str s) (contains? s :a) (.contains s :a)"
				+ " (seq s)))", "\"[:a]\" true true (:a)\n");
		assertBothEqual("(let [l (java.util.ArrayList.)] (.add l 'sym) (.add l 'ns/q) (prn (str l) (first l)"
				+ " (symbol? (first l)) (= 'ns/q (second l))))", "\"[sym, ns/q]\" sym true true\n");
		assertBothEqual("(defrecord R [a]) (println (str (java.util.HashMap. (->R 1))))", "{:a=1}\n");
		assertBothEqual("(println (str (java.util.ArrayList. (sorted-set 3 1 2)))"
				+ " (str (java.util.TreeMap. (sorted-map :b 1 :a 2))) (str (java.util.ArrayList. (map inc [1 2]))))",
				"[1, 2, 3] {:a=2, :b=1} [2, 3]\n");
		assertBothEqual("(let [l (java.util.ArrayList. [:b :a :c/d])] (java.util.Collections/sort l) (prn (vec l)))",
				"[:a :b :c/d]\n");
		assertBothEqual("(println (.equals (java.util.HashSet. [1 2]) #{1 2}) (java.util.Objects/toString :a)"
				+ " (java.util.Objects/toString 'x))", "true :a x\n");
		assertBothEqual("(let [s (java.util.HashSet. #{:x})] (prn (first s) (keyword? (first s)) (= #{:x} (set s))))",
				":x true true\n");
		assertBothEqual("(let [m (java.util.HashMap.)] (.put m :k false) (prn (get m :k) (.get m :k)"
				+ " (.containsKey m :k) (.containsValue m false)))", "false false true true\n");
	}

	// Oracle (clj 1.12.6, measured 2026-10-09): a keyword hashes as Keyword.hashCode and
	// a
	// symbol as Symbol.hashCode (Util.hashCombine of the name's and the namespace's
	// String hashCode, a keyword 0x9e3779b9 more), so a host HashMap or HashSet of them
	// iterates in the oracle's order, and they sort as their compareTo: no namespace
	// first, then by namespace, then by name. Before, measured 2026-10-09 on the
	// interpreter and the JVM (a handle hashing and ordering by its spelling): "{:j=10,
	// :a=1, :b=2, ...}", "[:beta, :alpha, ...]", [:a/b :m :z].
	@Test
	void aKeywordOrSymbolHashesAndSortsInJavaAsTheOraclesDo() throws Exception {
		assertBothEqual("(prn (str (java.util.HashMap. {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8 :i 9 :j 10})))",
				"\"{:d=4, :g=7, :f=6, :b=2, :c=3, :a=1, :h=8, :i=9, :j=10, :e=5}\"\n");
		assertBothEqual(
				"(prn (str (java.util.HashSet. [:alpha :beta :gamma :delta :epsilon :zeta]))"
						+ " (str (java.util.HashSet. ['alpha 'beta 'gamma 'delta 'epsilon 'zeta])))",
				"\"[:alpha, :delta, :beta, :gamma, :zeta, :epsilon]\" \"[alpha, beta, gamma, epsilon, zeta, delta]\"\n");
		assertBothEqual("(prn (str (java.util.HashSet. [:a/x :b/y 'c/z 'd :e])))", "\"[d, :a/x, :b/y, c/z, :e]\"\n");
		assertBothEqual("(prn (vec (java.util.TreeSet. [:z :a/b :m :a/a])) (vec (java.util.TreeSet. ['b/x 'y 'a/z])))",
				"[:m :z :a/a :a/b] [y a/z b/x]\n");
	}

	@Test
	void lockingExcludesTheOtherThreadsOnTheInterpreterAndTheJvm() throws Exception {
		// four threads each run 2000 read-yield-write steps on one atom: without the
		// lock the steps interleave and updates are lost (2389 of 8000, measured
		// 2026-10-08); under it every step lands, like the oracle's monitor
		assertBothEqual("""
				(def lock (atom 0))
				(def plain (atom 0))
				(defn work []
				  (dotimes [_ 2000]
				    (locking lock (let [v @plain] (Thread/yield) (reset! plain (inc v))))))
				(def ts (doall (repeatedly 4 #(doto (Thread. work) (.start)))))
				(doseq [t ts] (.join t))
				(println @plain)
				""", "8000\n");
	}

	@Test
	void identicalOnHostObjectsIsIdentity() throws Exception {
		assertBothEqual("""
				(def f1 (java.io.File. "x"))
				(def f2 (java.io.File. "x"))
				(def p (proxy [Object] [] (equals [o] true) (toString [] "P")))
				(def l1 (java.util.ArrayList.))
				(def l2 (java.util.ArrayList.))
				(println (identical? f1 f2) (= f1 f2) (identical? f1 f1))
				(println (identical? p true) (identical? p 1) (identical? true p))
				(println (str p))
				(println (identical? l1 l2) (= l1 l2))
				(println (contains? #{f1} f2) (get {f1 1} f2) (count (distinct [f1 f2])))
				""", "false true true\nfalse false false\nP\nfalse true\ntrue 1 1\n");
	}

	// Oracle (clj 1.12.6): = asks the left operand, so a proxy whose equals answers
	// true is = to a number, a string, nil, true and a character, while none of them
	// is = to it; a vector is compared as a collection, never handed to equals. Before,
	// measured 2026-10-04: the interpreter answered false for the first row, the JVM
	// true for (= p [1]).
	@Test
	void equalsOfAHostObjectAndAValueAsksTheLeftOperand() throws Exception {
		assertBothEqual("""
				(def p (proxy [Object] [] (equals [o] true) (toString [] "P")))
				(println (= p 1) (= p "s") (= p nil) (= p true) (= p \\a) (= p 1.5))
				(println (= 1 p) (= "s" p) (= nil p) (= p [1]))
				""", "true true true true true true\nfalse false false false\n");
	}

	// Oracle (clj 1.12.6, 2026-10-04): Util.equiv sends a pair holding a Clojure
	// collection to its equiv, which compares a java.util.List element by element with a
	// sequential, a Map entry by entry with a map and a Set member by member with a set,
	// either operand first; a host collection inside a set is still found by its hash
	// (the last answer of the sixth row). Before, every row answered false.
	@Test
	void aHostCollectionIsEqualToAClojureCollectionOfItsKind() throws Exception {
		assertBothEqual("""
				(def al (java.util.ArrayList. [1 2]))
				(def hm (doto (java.util.HashMap.) (.put "a" 1) (.put "b" (java.util.ArrayList. [1 2]))))
				(def hs (doto (java.util.HashSet.) (.add 1) (.add "x")))
				(defrecord R [a])
				(println (= al [1 2]) (= [1 2] al) (= al '(1 2)) (= '(1 2) al) (= al (map inc [0 1]))
				         (= (map inc [0 1]) al) (= al (range 1 3)))
				(println (= al [1 3]) (= al [1 2 3]) (= al []) (= (java.util.ArrayList.) [])
				         (= (java.util.ArrayList.) ()) (= al (iterate inc 1)) (= (iterate inc 1) al))
				(println (= hm {"a" 1 "b" [1 2]}) (= {"a" 1 "b" [1 2]} hm) (= hm {"a" 1 "b" '(1 2)})
				         (= hm {"a" 1}) (= hm {"a" 2 "b" [1 2]}) (= hm {"a" 1 "c" [1 2]}))
				(println (= hs #{1 "x"}) (= #{1 "x"} hs) (= hs #{1}) (= hs #{1 "y"}) (= hs [1 "x"])
				         (= al #{1 2}) (= hm [1 2]) (= al {1 2}))
				(println (= hm (sorted-map "a" 1 "b" [1 2])) (= (sorted-map "a" 1 "b" [1 2]) hm)
				         (= (doto (java.util.HashSet.) (.add 1) (.add 2)) (sorted-set 1 2))
				         (= (sorted-set 2 1) (doto (java.util.HashSet.) (.add 1) (.add 2))))
				(println (= (java.util.ArrayList. [al [3]]) [[1 2] [3]]) (= [[1 2] [3]] (java.util.ArrayList. [al [3]]))
				         (= [al] [[1 2]]) (= [[1 2]] [al]) (= {:k al} {:k [1 2]}) (= #{al} #{[1 2]}))
				(println (not= al [1 2]) (= al [1 2] '(1 2)) (= al (java.util.ArrayList. [1 2])) (apply = [al [1 2]])
				         (= hm (->R 1)) (= (->R 1) hm))
				(println (= (java.util.LinkedList. [1 2]) [1 2]) (= (java.util.ArrayList. [1.0 2]) [1 2])
				         (= (doto (java.util.TreeMap.) (.put "a" 1)) {"a" 1}) (= al (vector-of :long 1 2))
				         (= al 1) (= 1 al) (= al "x"))
				""", """
				true true true true true true true
				false false false true true false false
				true true true false false false
				true true false false false false false false
				true true true true
				true true true true true false
				false true true true false false
				true false true true false false false
				""");
	}

	// Oracle (clj 1.12.6, 2026-10-04): RT.seq takes any Iterable (a Map through its
	// entries, a CharSequence through its characters), RT.count a Collection, a Map or a
	// CharSequence, RT.get a Map, RT.contains a Map or a Set, each by the host's own
	// lookup (a key a Java method cannot take is in no host map); contains? of a List
	// and count of an Iterable that is no Collection are refused. Before, every seq verb
	// signalled "seq needs a collection" and count a LENGTH type error.
	@Test
	void aHostCollectionSeqsCountsAndLooksUpLikeAClojureOne() throws Exception {
		assertBothEqual(
				"""
						(def al (java.util.ArrayList. [1 2]))
						(def hm (doto (java.util.HashMap.) (.put "a" 1)))
						(def tm (doto (java.util.TreeMap.) (.put "a" 1) (.put "b" (java.util.ArrayList. [3]))))
						(def ts (doto (java.util.TreeSet.) (.add 3) (.add 1)))
						(def ea (java.util.ArrayList.))
						(def path (.toPath (java.io.File. "a/b")))
						(println (seq al) (vec al) (first al) (rest al) (next al) (last al) (nth al 1) (seq ea))
						(println (map inc al) (filter odd? al) (reduce + al) (reduce + 10 al) (apply + al) (mapv inc al))
						(println (into {} hm) (into [] al) (into #{} ts) (seq ts) (sort ts) (set al) (frequencies al))
						(println (count al) (count hm) (count ts) (count ea) (count (StringBuilder. "abc")))
						(println (empty? al) (empty? ea) (empty? hm) (empty? (java.util.HashMap.)) (seq (java.util.HashMap.)))
						(println (get hm "a") (get hm "z") (get hm "z" :none) (vec (get tm "b")) (get al 0) (get al 0 :d))
						(println (contains? hm "a") (contains? hm "z") (contains? ts 3) (contains? ts 2))
						(println (keys tm) (first (vals tm)) (map key tm) (for [[k v] hm] (str k v)) (= (vec (second (vals tm))) [3]))
						(println (seq (StringBuilder. "ab")) (map str path) (map count [al hm]))
						(println (let [[a b] al] (+ a b)) (zipmap al [:x :y]) (clojure.string/join "," al) (concat al [3]))
						(println (try (contains? al 0) (catch Exception e (.getMessage e))))
						(println (try (count path) (catch Exception e (.getMessage e))))
						(def vk (doto (java.util.HashMap.) (.put [1 2] "v") (.put nil 0) (.put \\c 1.5)))
						(def hs (doto (java.util.HashSet.) (.add [1]) (.add "x")))
						(println (get vk [1 2]) (get vk '(1 2)) (get vk nil) (get vk \\c) (get vk :a :none) (get vk {:a 1})
						         (contains? vk {:a 1}) (contains? vk nil))
						(println (contains? hs [1]) (contains? hs "x") (contains? hs :x) (contains? hs #{1}) (get hs "x"))
						""",
				"""
						(1 2) [1 2] 1 (2) (2) 2 2 nil
						(2 3) (1) 3 13 3 [2 3]
						{a 1} [1 2] #{1 3} (1 3) (1 3) #{1 2} {1 1, 2 1}
						2 1 2 0 3
						false true false true nil
						1 nil :none [3] nil :d
						true false true false
						(a b) 1 (a b) (a1) true
						(a b) (a b) (2 1)
						3 {1 :x, 2 :y} 1,2 (1 2 3)
						contains? not supported on type: java.util.ArrayList
						count not supported on this type: UnixPath
						v v 0 1.5 :none nil false true
						true true false false nil
						""");
	}

	// Oracle (clj 1.12.6): deref of a host Future is its get (a failure surfaces as the
	// ExecutionException, a cancelled one as CancellationException), and the
	// three-argument deref is get with a timeout in milliseconds answering the third
	// argument on TimeoutException; anything that is no Future refuses the timed form
	// with a ClassCastException (nil a NullPointerException), after both extra
	// arguments ran.
	@Test
	void derefOfAHostFutureIsItsGetWithAnOptionalTimeout() throws Exception {
		assertBothEqual(
				"""
						(import '(java.util.concurrent CompletableFuture ExecutionException CancellationException FutureTask Callable))
						(def done (CompletableFuture/completedFuture 1))
						(def failed (CompletableFuture/failedFuture (Exception. "x")))
						(def pending (CompletableFuture.))
						(def cancelled (doto (CompletableFuture.) (.cancel true)))
						(defn kind [f] (try (f) (catch ClassCastException e :cce) (catch NullPointerException e :npe)))
						(println @done (deref done) (deref done 10 :timeout) (deref pending 10 :timeout) (deref (CompletableFuture/completedFuture nil) 10 :t))
						(println (try @failed (catch ExecutionException e (.getMessage (.getCause e)))) (try (deref failed 10 :t) (catch ExecutionException e (.getMessage (.getCause e)))))
						(println (try @cancelled (catch CancellationException e :cancelled)) (try (deref cancelled 10 :t) (catch CancellationException e :cancelled)))
						(println (map deref [done (CompletableFuture/completedFuture 2)]) (let [ft (FutureTask. (proxy [Callable] [] (call [] 7)))] (.run ft) [@ft (deref ft 1 :t)]))
						(println [(kind #(deref (atom 1) 10 :t)) (kind #(deref (volatile! 1) 10 :t)) (kind #(deref (java.util.ArrayList.) 10 :t)) (kind #(deref (java.util.ArrayList.))) (kind #(deref nil 10 :t)) (kind #(deref 1 10 :t)) (kind #(deref done :a :t))])
						(println (deref done 10.7 :t) (deref pending 0 :t) (deref pending -5 :t))
						(def n (atom 0))
						(println (deref done (do (swap! n inc) 10) (do (swap! n inc) :t)) @n (kind #(deref (atom 5) (do (swap! n inc) 10) (do (swap! n inc) :t))) @n)
						""",
				"""
						1 1 1 :timeout nil
						x x
						:cancelled :cancelled
						(1 2) [7 7]
						[:cce :cce :cce :cce :npe :cce :cce]
						1 :t :t
						1 2 :cce 4
						""");
	}

	// Oracle (clj 1.12.6): a host Future is a future for future?, future-done?,
	// future-cancelled? and future-cancel (isDone, isCancelled, cancel(true)); a value
	// that
	// is no Future is a ClassCastException (nil a NullPointerException) for the three
	// verbs, and realized? of a host Future a ClassCastException.
	@Test
	void theFuturePredicatesReadAHostFuture() throws Exception {
		assertBothEqual(
				"""
						(import '(java.util.concurrent CompletableFuture FutureTask Callable))
						(def done (CompletableFuture/completedFuture 1))
						(def pending (CompletableFuture.))
						(def cancelled (doto (CompletableFuture.) (.cancel true)))
						(defn kind [f] (try (f) (catch ClassCastException e :cce) (catch NullPointerException e :npe)))
						(println [(future? done) (future? pending) (future? (FutureTask. (proxy [Callable] [] (call [] 1)))) (future? 1) (future? nil) (future? (atom 1)) (future? [done]) (future? "s")])
						(println [(future-done? done) (future-done? pending) (future-done? cancelled) (future-cancelled? done) (future-cancelled? pending) (future-cancelled? cancelled)])
						(println [(kind #(future-done? 1)) (kind #(future-done? nil)) (kind #(future-done? (atom 1))) (kind #(future-cancelled? "s")) (kind #(future-cancelled? nil)) (kind #(future-cancel 1)) (kind #(future-cancel nil)) (kind #(future-cancel [done])) (kind #(realized? done))])
						(println (future-cancel done) (future-done? done) (future-cancelled? done))
						(println (future-cancel pending) (future-done? pending) (future-cancelled? pending) (future-cancel pending) (future-cancel cancelled))
						(println (map future? [done 1]) (map future-done? [done pending]) (map future-cancelled? [done]) (apply future-done? [done]) (map future-cancel [(CompletableFuture.)]))
						(def n (atom 0))
						(println (future? (do (swap! n inc) done)) (future-done? (do (swap! n inc) done)) (future-cancel (CompletableFuture.)) @n)
						(println (if (future? done) @done :no) (if (future? 5) @5 :no))
						""",
				"""
						[true true true false false false false false]
						[true false true false false true]
						[:cce :npe :cce :cce :npe :cce :npe :cce :cce]
						false true false
						true true true true true
						(true false) (true true) (false) true (true)
						true true true 2
						1 :no
						""");
	}

	// Oracle (clj 1.12.6, 2026-10-04): print-method under *print-readably* writes a
	// RandomAccess List as a vector, any other List as a list, a Map as a map and a Set
	// as a set, each member readably and under *print-length* / *print-level*; another
	// Collection (ArrayDeque, a Map's values) and any host object not readably is
	// print-object (here the #<java C> deviation, without hash and toString). The
	// members follow the host's own order. Before, every host collection printed as
	// #<java C>.
	@Test
	void aHostCollectionPrintsReadablyLikeItsClojureKind() throws Exception {
		assertBothEqual(
				"""
						(def al (java.util.ArrayList. [1 2]))
						(def ll (java.util.LinkedList. [1 "s"]))
						(def hm (doto (java.util.HashMap.) (.put "a" 1)))
						(def hs (doto (java.util.HashSet.) (.add "x")))
						(def tm (doto (java.util.TreeMap.) (.put "b" al) (.put "a" nil)))
						(def ts (java.util.TreeSet. [3 1 2]))
						(def nested (java.util.ArrayList. [al ll hm hs "s" \\c nil true 1.5]))
						(prn al ll hm hs tm ts)
						(prn nested (java.util.Vector. [1 "q"]) (java.util.Collections/singletonList 7) (java.util.Collections/unmodifiableList ll) (.keySet tm))
						(prn (java.util.ArrayList.) (java.util.LinkedList.) (java.util.HashMap.) (java.util.HashSet.))
						(println (pr-str al) (pr-str [al hm]) (str [ll]) (str {:k hs}) (str al) (str hm))
						(println (prn-str tm) (with-out-str (pr ts)) (pr-str (list al)))
						(binding [*print-length* 1] (prn al ll tm ts [al]))
						(binding [*print-level* 1] (prn nested [al] tm))
						(println (pr-str (java.util.ArrayDeque. [1 2])) (str [(.values tm)]))
						(println al [hm])
						(binding [*print-readably* false] (prn ll))
						(def big (java.util.TreeSet. (map #(* 7 %) (range 30))))
						(prn big (java.util.ArrayList. [(doto (java.util.HashMap.) (.put nil 1)) (doto (java.util.HashSet.) (.add nil))]))
						(binding [*print-level* 0] (prn (java.util.LinkedList.) (java.util.ArrayList.)))
						(binding [*print-length* 0] (prn (java.util.LinkedList.) (java.util.LinkedList. [1])))
						""",
				"""
						[1 2] (1 "s") {"a" 1} #{"x"} {"a" nil, "b" [1 2]} #{1 2 3}
						[[1 2] (1 "s") {"a" 1} #{"x"} "s" \\c nil true 1.5] [1 "q"] [7] (1 "s") #{"a" "b"}
						[] () {} #{}
						[1 2] [[1 2] {"a" 1}] [(1 "s")] {:k #{"x"}} [1, 2] {a=1}
						{"a" nil, "b" [1 2]}
						 #{1 2 3} ([1 2])
						[1 ...] (1 ...) {"a" nil, ...} #{1 ...} [[1 ...]]
						[# # # # "s" \\c nil true 1.5] [#] {"a" nil, "b" #}
						#<java java.util.ArrayDeque> [#<java java.util.TreeMap$Values>]
						#<java java.util.ArrayList> [#<java java.util.HashMap>]
						#<java java.util.LinkedList>
						#{0 7 14 21 28 35 42 49 56 63 70 77 84 91 98 105 112 119 126 133 140 147 154 161 168 175 182 189 196 203} [{nil 1} #{nil}]
						# #
						() (...)
						""");
	}

	// Oracle (clj 1.12.6, 2026-10-04): RT.find (and select-keys over it) takes a Map by
	// its own containsKey and refuses any other host object, kv-reduce reads a Map's
	// entries (reduce-kv, update-vals, update-keys, map-invert), and a map's cons takes
	// any seq of Map.Entry (conj, merge, merge-with, into). Before, each signalled.
	@Test
	void aHostMapIsAMapToTheMapVerbs() throws Exception {
		assertBothEqual(
				"""
						(require 'clojure.set)
						(def hm (doto (java.util.TreeMap.) (.put "a" 1) (.put "b" 2)))
						(def al (java.util.ArrayList. [1 2]))
						(def hh (java.util.HashMap. hm))
						(println (find hm "a") (find hm "z") (apply find [hm "b"]) (select-keys hm ["a" "z"]) (select-keys hm []) (apply select-keys [hm ["b"]]))
						(println (reduce-kv (fn [a k v] (+ a v)) 0 hm) (reduce-kv (fn [a k v] (conj a k)) [] hm) (apply reduce-kv [(fn [a k v] (+ a v)) 0 hm]) (update-vals hm inc) (update-keys hm keyword))
						(println (merge {} hm) (merge {"c" 3} hm nil) (conj {} hm) (conj {"a" 0} hm) (apply merge [{} hm]) (apply conj [{} hm]) (into {} hm))
						(println (merge-with + {"a" 10} hm) (apply merge-with + [{"a" 10} hm]) (clojure.set/map-invert hm) (clojure.set/rename-keys {"a" 5} hm))
						(println (merge (sorted-map "z" 0) hm) (conj (sorted-map "z" 0) hm) (merge-with + (sorted-map "a" 10) hm))
						(println (conj {} (.entrySet hm)) (reduce-kv (fn [a k v] (+ a v)) 0 (.entrySet hm)) (conj {} (java.util.ArrayList.)) (find hh :a) (select-keys hh [:a {:b 1} "a"]))
						(println (try (find al 0) (catch Exception e (.getMessage e))))
						(println (try (select-keys al [0]) (catch Exception e (.getMessage e))))
						""",
				"""
						[a 1] nil [b 2] {a 1} {} {b 2}
						3 [a b] 3 {a 2, b 3} {:a 1, :b 2}
						{a 1, b 2} {c 3, a 1, b 2} {a 1, b 2} {a 1, b 2} {a 1, b 2} {a 1, b 2} {a 1, b 2}
						{a 11, b 2} {a 11, b 2} {1 a, 2 b} {1 5}
						{a 1, b 2, z 0} {a 1, b 2, z 0} {a 11, b 2}
						{a 1, b 2} 3 {} nil {a 1}
						find not supported on type: java.util.ArrayList
						find not supported on type: java.util.ArrayList
						""");
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
				           (catch UnsupportedOperationException e [(class e) (ex-message e)])))
				""", "0\n[java.lang.UnsupportedOperationException get]\n");
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
		// slurp closes the file reader it read, like the oracle: a later read is its
		// IOException, and the with-open's own close does nothing
		assertBothEqual(
				prelude + "(with-open [r (jio/reader " + path + ")] (println (count (slurp r)))"
						+ " (println (try (.read r) (catch java.io.IOException e (ex-message e)))))",
				"35\nStream closed\n");
		// class of a file reader names its host class like the printer does
		assertBothEqual(prelude + "(with-open [r (jio/reader " + path + ")] (println (class r)))",
				":java.io.BufferedReader\n");
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
