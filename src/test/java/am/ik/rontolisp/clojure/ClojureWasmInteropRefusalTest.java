package am.ik.rontolisp.clojure;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.testsupport.ThreadStdio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * The {@code java:} refusal of the new interop value shapes on both WASM backends: a
 * static field as a value, a member as a value, a zero-arg static call and a host-boolean
 * instance call, a proxy over a class, and the qualified {@code Class/new},
 * {@code Class/.method} and param-tagged forms all compile to the undefined-function
 * call-time error (pinned here by the compile warning naming it) and trap when run. The
 * behavior itself lives in {@link ClojureInteropTest} on the interpreter and the JVM. A
 * Date or UUID construction is no refusal: it makes the value {@code #inst} or
 * {@code #uuid} reads, whose host-only members alone are refused, by name.
 */
class ClojureWasmInteropRefusalTest {

	@TempDir
	static Path workDir;

	@Test
	void staticFieldValueRefusesOnPreview1() throws Exception {
		assertRefusal("(ns awtimp (:import (java.awt.event KeyEvent))) (println KeyEvent/VK_LEFT)", "JAVA:FIELD",
				false);
	}

	@Test
	void staticFieldValueRefusesOnTheComponent() throws Exception {
		assertRefusal("(ns awtimp (:import (java.awt.event KeyEvent))) (println KeyEvent/VK_LEFT)", "JAVA:FIELD", true);
	}

	@Test
	void memberValueRefusesOnPreview1() throws Exception {
		assertRefusal("(println (every? Character/isWhitespace \"   \"))", "JAVA:STATIC", false);
	}

	@Test
	void memberValueRefusesOnTheComponent() throws Exception {
		assertRefusal("(println (every? Character/isWhitespace \"   \"))", "JAVA:STATIC", true);
	}

	@Test
	void zeroArgStaticCallRefusesOnPreview1() throws Exception {
		assertRefusal("(println (System/nanoTime))", "JAVA:STATIC", false);
	}

	@Test
	void zeroArgStaticCallRefusesOnTheComponent() throws Exception {
		assertRefusal("(println (System/nanoTime))", "JAVA:STATIC", true);
	}

	@Test
	void classValueRefusesOnPreview1() throws Exception {
		assertRefusal("(println String)", "JAVA:STATIC", false);
	}

	@Test
	void classValueRefusesOnTheComponent() throws Exception {
		assertRefusal("(println String)", "JAVA:STATIC", true);
	}

	@Test
	void hostBooleanCallRefusesOnPreview1() throws Exception {
		assertRefusal("(println (.isEmpty (java.util.ArrayList.)))", "JAVA:CALL", false);
	}

	@Test
	void hostBooleanCallRefusesOnTheComponent() throws Exception {
		assertRefusal("(println (.isEmpty (java.util.ArrayList.)))", "JAVA:CALL", true);
	}

	@Test
	void proxyOverAClassRefusesOnPreview1() throws Exception {
		assertRefusal("(println (.lastModified (proxy [java.io.File] [\"x\"] (lastModified [] 42))))", "JAVA:SUBCLASS",
				false);
	}

	@Test
	void proxyOverAClassRefusesOnTheComponent() throws Exception {
		assertRefusal("(println (.lastModified (proxy [java.io.File] [\"x\"] (lastModified [] 42))))", "JAVA:SUBCLASS",
				true);
	}

	@Test
	void qualifiedConstructorRefusesOnPreview1() throws Exception {
		assertRefusal("(println (String/new \"q\"))", "JAVA:NEW", false);
	}

	@Test
	void qualifiedConstructorRefusesOnTheComponent() throws Exception {
		assertRefusal("(println (String/new \"q\"))", "JAVA:NEW", true);
	}

	@Test
	void qualifiedInstanceMethodValueRefusesOnPreview1() throws Exception {
		assertRefusal("(println (map Integer/.byteValue [1]))", "JAVA:CALL", false);
	}

	@Test
	void qualifiedInstanceMethodValueRefusesOnTheComponent() throws Exception {
		assertRefusal("(println (map Integer/.byteValue [1]))", "JAVA:CALL", true);
	}

	@Test
	void taggedStaticCallRefusesOnPreview1() throws Exception {
		assertRefusal("(println (^[double] Math/abs -1))", "JAVA:STATIC", false);
	}

	@Test
	void taggedStaticCallRefusesOnTheComponent() throws Exception {
		assertRefusal("(println (^[double] Math/abs -1))", "JAVA:STATIC", true);
	}

	@Test
	void aDateMadeHereRefusesOnlyAHostOnlyMemberOnBothBackends() throws Exception {
		// a construction is the value #inst reads, so it runs here: a member only the
		// host
		// answers is refused by name, where the interpreter and the JVM call it on the
		// host Date it stands for (ClojureInteropTest)
		String program = "(println (try (.toInstant (java.util.Date. 5)) (catch Exception e (ex-message e)))"
				+ " (inst-ms (java.util.Date. 5)) (uuid? (java.util.UUID/randomUUID)))";
		for (boolean component : new boolean[] { false, true }) {
			assertThat(run(program, component)).as(component ? "component" : "preview 1")
				.isEqualTo("Method toInstant taking 0 args is not supported for class java.util.Date 5 true\n");
		}
	}

	@Test
	void aMethodOfAValuesJavaObjectIsUnsupportedOnBothBackends() throws Exception {
		// the interpreter and the JVM call it on the value's Java object
		// (ClojureInteropTest); a method no value class has keeps the oracle's words
		String program = "(defn msg [f] (try (f) (catch Exception e (ex-message e))))"
				+ " (println (msg #(.toArray [1 2])) \"|\" (msg #(.toArray :k)) \"|\""
				+ " (msg #(let [it (.iterator [1])] (.remove it))) \"|\" (msg #(.reversed (fn [a b] 0))) \"|\""
				+ " (msg #(.thenComparing {:a 1} compare)))";
		for (boolean component : new boolean[] { false, true }) {
			assertThat(run(program, component)).as(component ? "component" : "preview 1")
				.isEqualTo("Method toArray taking 0 args is not supported for class clojure.lang.PersistentVector"
						+ " | No matching field found: toArray for class clojure.lang.Keyword | nil"
						+ " | Method reversed taking 0 args is not supported for class clojure.lang.AFunction"
						+ " | No matching method thenComparing found taking 1 args for class clojure.lang.PersistentArrayMap\n");
		}
	}

	@Test
	void isoInstantFormatsInstantsOnBothBackends() throws Exception {
		// DateTimeFormatter/ISO_INSTANT is the portable value where no host is, and
		// .format of it answers a Date or a Timestamp in ISO-8601 (data.json's
		// write-instant); anything else is refused in the oracle's words. Answers
		// measured against clj 1.12.6; the interpreter and the JVM keep the host's
		// object and call (ClojureInteropTest has no such case: the host call of an
		// instant of this front end's own is the oracle's refusal).
		String program = "(require '[clojure.instant :as iso-inst])"
				+ " (println (.format java.time.format.DateTimeFormatter/ISO_INSTANT (java.util.Date. 0)))"
				+ " (println (.format java.time.format.DateTimeFormatter/ISO_INSTANT (java.util.Date. 1234567890123)))"
				+ " (println (.format java.time.format.DateTimeFormatter/ISO_INSTANT (java.util.Date. 1)))"
				+ " (println (.format java.time.format.DateTimeFormatter/ISO_INSTANT (java.util.Date. -394)))"
				+ " (println (.format java.time.format.DateTimeFormatter/ISO_INSTANT (java.util.Date. -62135596800000)))"
				+ " (println (.format java.time.format.DateTimeFormatter/ISO_INSTANT (java.sql.Timestamp. 1000)))"
				+ " (println (.format java.time.format.DateTimeFormatter/ISO_INSTANT (java.sql.Timestamp. 1500)))"
				+ " (println (.format java.time.format.DateTimeFormatter/ISO_INSTANT (java.sql.Timestamp. 1123)))"
				+ " (println (.format java.time.format.DateTimeFormatter/ISO_INSTANT"
				+ " (iso-inst/read-instant-timestamp \"1970-01-01T00:00:01.123456789Z\")))"
				+ " (println (try (.format java.time.format.DateTimeFormatter/ISO_INSTANT \"x\")"
				+ " (catch ClassCastException e (ex-message e))))";
		String expected = "1970-01-01T00:00:00Z\n2009-02-13T23:31:30.123Z\n1970-01-01T00:00:00.001Z\n"
				+ "1969-12-31T23:59:59.606Z\n0001-01-01T00:00:00Z\n1970-01-01T00:00:01Z\n"
				+ "1970-01-01T00:00:01.500Z\n1970-01-01T00:00:01.123Z\n" + "1970-01-01T00:00:01.123456789Z\n"
				+ "class java.lang.String cannot be cast to class java.time.temporal.TemporalAccessor\n";
		for (boolean component : new boolean[] { false, true }) {
			assertThat(run(program, component)).as(component ? "component" : "preview 1").isEqualTo(expected);
		}
	}

	@Test
	void portableNumberStaticsRefuseLikeTheHostStaticsOnBothBackends() throws Exception {
		// Long/valueOf, Double/valueOf, Integer/parseInt of radix 16,
		// Integer/toHexString and Math/min lower to the portable readers where no
		// host is (data.json's read path): a malformed string is the oracle's
		// NumberFormatException, anything else its coercion or refusal, each
		// measured against clj 1.12.6. The interpreter and the JVM keep the host's
		// call, whose coercions are its own (so only the shared lines are in
		// clojure-spec).
		String program = "(println (try (Long/valueOf 1.5) (catch IllegalArgumentException e (ex-message e))))"
				+ " (println (try (Long/valueOf :k) (catch IllegalArgumentException e (ex-message e))))"
				+ " (println (try (Double/valueOf 5) (catch IllegalArgumentException e (ex-message e))))"
				+ " (println (try (Double/valueOf 1/2) (catch IllegalArgumentException e (ex-message e))))"
				+ " (println (try (Integer/parseInt 5 16) (catch ClassCastException e (ex-message e))))"
				+ " (println (try (Integer/parseInt 1.5 16) (catch ClassCastException e (ex-message e))))"
				+ " (println (Integer/toHexString 1.5) (Integer/toHexString 1/2) (Integer/toHexString -1.5)"
				+ " (Integer/toHexString (/ 0.0 0.0)))"
				+ " (println (try (Integer/toHexString 4294967295) (catch ArithmeticException e (ex-message e))))"
				+ " (println (try (Integer/toHexString (/ 1.0 0.0))"
				+ " (catch IllegalArgumentException e (ex-message e))))"
				+ " (println (try (Integer/toHexString nil) (catch NullPointerException e (ex-message e))))"
				+ " (println (try (Integer/toHexString \"ff\") (catch ClassCastException e (ex-message e))))"
				+ " (println (try (Math/min 1 1.0) (catch IllegalArgumentException e (ex-message e))))"
				+ " (println (try (Math/min 1/2 2) (catch IllegalArgumentException e (ex-message e))))"
				+ " (println (try (Math/min \"a\" 1) (catch IllegalArgumentException e (ex-message e))))"
				+ " (println (try (Math/min nil 1) (catch IllegalArgumentException e (ex-message e))))"
				+ " (println (try (Math/min true 1) (catch IllegalArgumentException e (ex-message e))))";
		String expected = "No matching method valueOf found taking 1 args\n"
				+ "No matching method valueOf found taking 1 args\n"
				+ "No matching method valueOf found taking 1 args\n"
				+ "No matching method valueOf found taking 1 args\n"
				+ "class java.lang.Long cannot be cast to class java.lang.String\n"
				+ "class java.lang.Double cannot be cast to class java.lang.String\n" + "1 0 ffffffff 0\n"
				+ "integer overflow\n" + "Value out of range for long: Infinity\n"
				+ "Cannot invoke \"java.lang.Character.charValue()\" because \"x\" is null\n"
				+ "class java.lang.String cannot be cast to class java.lang.Number\n"
				+ "No matching method min found taking 2 args\n" + "No matching method min found taking 2 args\n"
				+ "No matching method min found taking 2 args\n" + "No matching method min found taking 2 args\n"
				+ "No matching method min found taking 2 args\n";
		for (boolean component : new boolean[] { false, true }) {
			assertThat(run(program, component)).as(component ? "component" : "preview 1").isEqualTo(expected);
		}
	}

	@Test
	void aMemberNoValueHereHasIsTheOraclesRefusalOnBothBackends() throws Exception {
		// no value a wasm program makes has getBytes but a string, or a field s: the
		// oracle's own refusal, where the host arm was a call-time error; a String
		// construction of anything but a byte array is the host's, refused by name
		String program = "(defn msg [f] (try (f) (catch Exception e (ex-message e))))"
				+ " (defn g [x] (.getBytes x \"UTF-8\")) (defn h [x] (.-s x)) (defn s [x] (String. x))"
				+ " (println (count (g \"ab\")) (msg #(g 5)) \"|\" (msg #(h 5)) \"|\" (count (s (byte-array 2)))"
				+ " (msg #(s \"abc\")))";
		for (boolean component : new boolean[] { false, true }) {
			assertThat(run(program, component)).as(component ? "component" : "preview 1")
				.isEqualTo("2 No matching method getBytes found taking 1 args for class java.lang.Long"
						+ " | No matching field found: s for class java.lang.Long"
						+ " | 2 Constructor java.lang.String taking 1 args is not supported for class java.lang.String\n");
		}
	}

	private static byte[] compile(String program, boolean component, ByteArrayOutputStream warnings) {
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.clojure(program, true, component);
		try (var _ = ThreadStdio.err(warnings)) {
			return WasmLispCompiler.builder()
				.component(component)
				.runtimeFeatures(frontend.features().names())
				.build()
				.compile(frontend.forms());
		}
	}

	// Compiles and runs the program, requiring it to exit normally, and answers its
	// output.
	private static String run(String program, boolean component) throws Exception {
		byte[] module = compile(program, component, new ByteArrayOutputStream());
		requireWasmtime();
		Path path = Files.createTempFile(workDir, "interoprun", component ? "-c.wasm" : ".wasm");
		Files.write(path, module);
		Path outFile = Files.createTempFile(workDir, "interoprun", ".out");
		Path errFile = Files.createTempFile(workDir, "interoprun", ".err");
		Process process = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", path.toString())
			.redirectOutput(outFile.toFile())
			.redirectError(errFile.toFile())
			.start();
		try {
			assertThat(process.waitFor(300, TimeUnit.SECONDS)).isTrue();
			assertThat(process.exitValue()).as("wasmtime exit code: %s", Files.readString(errFile)).isZero();
			return Files.readString(outFile);
		}
		finally {
			Files.deleteIfExists(outFile);
			Files.deleteIfExists(errFile);
		}
	}

	private static void assertRefusal(String program, String surface, boolean component) throws Exception {
		ByteArrayOutputStream warnings = new ByteArrayOutputStream();
		byte[] module = compile(program, component, warnings);
		assertThat(warnings.toString(StandardCharsets.UTF_8)).as("the compile warning names the refused surface")
			.contains("the function " + surface + " is undefined");
		requireWasmtime();
		Path path = Files.createTempFile(workDir, "interopref", component ? "-c.wasm" : ".wasm");
		Files.write(path, module);
		Path outFile = Files.createTempFile(workDir, "interopref", ".out");
		Path errFile = Files.createTempFile(workDir, "interopref", ".err");
		Process process = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", path.toString())
			.redirectOutput(outFile.toFile())
			.redirectError(errFile.toFile())
			.start();
		try {
			assertThat(process.waitFor(300, TimeUnit.SECONDS)).isTrue();
			assertThat(process.exitValue()).as("wasmtime exit code for a java: program").isNotZero();
		}
		finally {
			Files.deleteIfExists(outFile);
			Files.deleteIfExists(errFile);
		}
	}

	private static void requireWasmtime() {
		try {
			Process probe = new ProcessBuilder("wasmtime", "--version").start();
			if (!probe.waitFor(60, TimeUnit.SECONDS) || probe.exitValue() != 0) {
				abort("no usable wasmtime on PATH");
			}
		}
		catch (Exception ex) {
			abort("no usable wasmtime on PATH");
		}
	}

}
