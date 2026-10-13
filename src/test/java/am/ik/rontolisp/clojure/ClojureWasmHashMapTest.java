package am.ik.rontolisp.clojure;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * The {@code java.util.HashMap} construction on both WASM backends: where the host is not
 * the construction is the map itself ({@code .kb/clojure-frontend.md}, "HashMap
 * construction"), so {@code .get} reads it and {@code .put} on it throws the oracle's
 * {@code UnsupportedOperationException} with no message -- where the host is the same
 * construction stays the mutable object (the shared spec yaml pins only the lines both
 * agree on, and {@link ClojureInteropTest} the mutation).
 */
class ClojureWasmHashMapTest {

	@TempDir
	static Path workDir;

	@Test
	void aHashMapConstructionReadsAndRefusesWritesOnPreview1() throws Exception {
		assertThat(run(false)).isEqualTo("1\n2\n:uoe\n:rte\nnil\n0 true\n:npe\n");
	}

	@Test
	void aHashMapConstructionReadsAndRefusesWritesOnTheComponent() throws Exception {
		assertThat(run(true)).isEqualTo("1\n2\n:uoe\n:rte\nnil\n0 true\n:npe\n");
	}

	private static String run(boolean component) throws Exception {
		requireWasmtime();
		String program = "(let [m (java.util.HashMap. {:a 1})]" + " (println (.get m :a))"
				+ " (println (.get (assoc m :b 2) :b))"
				+ " (println (try (.put m :b 2) (catch UnsupportedOperationException e :uoe)))"
				+ " (println (try (.put m :b 2) (catch RuntimeException e :rte)))"
				+ " (println (ex-message (try (.put m :b 2) (catch UnsupportedOperationException e e)))))"
				+ "(let [m (java.util.HashMap.)]" + " (println (.size m) (.isEmpty m)))"
				+ "(println (try (java.util.HashMap. nil) (catch NullPointerException e :npe)))";
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.clojure(program, true, component);
		byte[] module = WasmLispCompiler.builder()
			.component(component)
			.runtimeFeatures(frontend.features().names())
			.build()
			.compile(frontend.forms());
		Path path = Files.createTempFile(workDir, "hashmap", component ? "-c.wasm" : ".wasm");
		Files.write(path, module);
		Path outFile = Files.createTempFile(workDir, "hashmap", ".out");
		Path errFile = Files.createTempFile(workDir, "hashmap", ".err");
		Process process = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "--dir",
				workDir.toString(), path.toString())
			.redirectOutput(outFile.toFile())
			.redirectError(errFile.toFile())
			.start();
		try {
			assertThat(process.waitFor(300, TimeUnit.SECONDS)).isTrue();
			String err = Files.readString(errFile, StandardCharsets.UTF_8);
			assertThat(process.exitValue()).as("wasmtime exit code; stderr: %s", err).isZero();
			return Files.readString(outFile, StandardCharsets.UTF_8);
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
