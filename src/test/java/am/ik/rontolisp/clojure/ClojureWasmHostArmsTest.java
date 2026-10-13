package am.ik.rontolisp.clojure;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.testsupport.ThreadStdio;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Clojure program the host has no part in compiles to either WASM target without a
 * {@code java:} warning: the host arms of its own lowering and of the library -- the
 * byte-array, charset and {@code Object}-method dispatch, the host faces of a deftype,
 * the hierarchy's host class walk, clojure.xml's SAX parser -- are dropped where no host
 * object can exist, while a call only the host answers still warns.
 */
class ClojureWasmHostArmsTest {

	private static final List<String> PORTABLE = List.of("(println (count (.getBytes \"abc\" \"UTF-8\")))",
			"(println (String. (.getBytes \"abc\" \"UTF-8\") \"UTF-8\"))",
			"(println (count (.getBytes \"é\" java.nio.charset.StandardCharsets/UTF_8)))",
			"(println (let [sb (StringBuilder.)] (.append sb \"a\") (.append sb :k) (str sb) (.length sb)))",
			"(println (str (StringBuilder. \"ab\") (StringBuffer. 16) (new StringBuilder \"c\")))",
			"(println (str (java.io.StringWriter. 16) (new java.io.StringWriter 8)))",
			"(println (.toUpperCase \"straße\" java.util.Locale/US) (.intern (str \"a\")))",
			"(deftype K [s] Object (equals [this o] (and (instance? K o) (= s (.-s ^K o))))"
					+ " (hashCode [this] (hash s)))" + " (println (= (K. 1) (K. 1)) (= (K. 1) (K. 2)))");

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void aPortableProgramCompilesWithoutAJavaWarning(boolean component) {
		for (String program : PORTABLE) {
			assertThat(javaWarnings(program, component)).as(program).isEmpty();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "collections.clj", "bundled-namespaces.clj" })
	void anExampleRunningOnEveryBackendCompilesWithoutAJavaWarning(String example) throws Exception {
		String program = Files.readString(Path.of("examples/clojure", example));
		for (boolean component : new boolean[] { false, true }) {
			assertThat(javaWarnings(program, component)).as(component ? "component" : "preview 1").isEmpty();
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void aCallOnlyTheHostAnswersStillWarns(boolean component) {
		// Long has byteValue: the program's call needs the host
		assertThat(javaWarnings("(defn f [x] (.byteValue x)) (println (f 1))", component))
			.containsExactly("warning: the function JAVA:CALL is undefined; compiled as a call-time error");
	}

	private static List<String> javaWarnings(String program, boolean component) {
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.clojure(program, true, component);
		ByteArrayOutputStream warnings = new ByteArrayOutputStream();
		try (var _ = ThreadStdio.err(warnings)) {
			WasmLispCompiler.builder()
				.component(component)
				.runtimeFeatures(frontend.features().names())
				.build()
				.compile(frontend.forms());
		}
		return warnings.toString(StandardCharsets.UTF_8).lines().filter(line -> line.contains("JAVA:")).toList();
	}

}
