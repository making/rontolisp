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
 * The filesystem refusal of the {@code clojure.java.io/reader} program on both WASM
 * backends: without a preopened directory covering the path, opening the fixture signals
 * the file-error instead of answering a reader. (With a preopen the same program reads,
 * like every backend -- but the spec suite's shared yaml cannot pin file IO, so the
 * behavior lives in {@link ClojureInteropTest} on the interpreter and the JVM.)
 */
class ClojureWasmFileRefusalTest {

	@TempDir
	static Path workDir;

	@Test
	void readerWithoutAPreopenRefusesOnPreview1() throws Exception {
		assertRefusal(false);
	}

	@Test
	void readerWithoutAPreopenRefusesOnTheComponent() throws Exception {
		assertRefusal(true);
	}

	private static void assertRefusal(boolean component) throws Exception {
		requireWasmtime();
		Path fixture = workDir.resolve("b22-words.txt");
		try (java.io.InputStream in = ClojureWasmFileRefusalTest.class.getResourceAsStream("/clojure-b22-words.txt")) {
			assertThat(in).isNotNull();
			Files.copy(in, fixture, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		String quoted = "\"" + fixture.toString().replace("\\", "\\\\") + "\"";
		String program = "(ns b22wref (:require [clojure.java.io :as jio]))" + "(with-open [r (jio/reader " + quoted
				+ ")] (println (count (line-seq r))))";
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.clojure(program, false, component);
		byte[] module = WasmLispCompiler.builder()
			.component(component)
			.runtimeFeatures(frontend.features().names())
			.build()
			.compile(frontend.forms());
		Path path = Files.createTempFile(workDir, "b22wref", component ? "-c.wasm" : ".wasm");
		Files.write(path, module);
		Path outFile = Files.createTempFile(workDir, "b22wref", ".out");
		Path errFile = Files.createTempFile(workDir, "b22wref", ".err");
		Process process = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", path.toString())
			.redirectOutput(outFile.toFile())
			.redirectError(errFile.toFile())
			.start();
		try {
			assertThat(process.waitFor(300, TimeUnit.SECONDS)).isTrue();
			assertThat(process.exitValue()).as("wasmtime exit code without a preopen").isNotZero();
			String transcript = Files.readString(outFile, StandardCharsets.UTF_8)
					+ Files.readString(errFile, StandardCharsets.UTF_8);
			assertThat(transcript).as("the filesystem refusal names the failed open").contains("cannot open file");
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
