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
 * instance call, and a proxy over a class all compile to the undefined-function call-time
 * error (pinned here by the compile warning naming it) and trap when run. The behavior
 * itself lives in {@link ClojureInteropTest} on the interpreter and the JVM.
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
		assertRefusal("(println (System/currentTimeMillis))", "JAVA:STATIC", false);
	}

	@Test
	void zeroArgStaticCallRefusesOnTheComponent() throws Exception {
		assertRefusal("(println (System/currentTimeMillis))", "JAVA:STATIC", true);
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

	private static void assertRefusal(String program, String surface, boolean component) throws Exception {
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.clojure(program, false, component);
		ByteArrayOutputStream warnings = new ByteArrayOutputStream();
		byte[] module;
		try (var _ = ThreadStdio.err(warnings)) {
			module = WasmLispCompiler.builder()
				.component(component)
				.runtimeFeatures(frontend.features().names())
				.build()
				.compile(frontend.forms());
		}
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
