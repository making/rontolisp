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
 * The preopened case for Clojure file IO on both WASM backends: with a {@code --dir}
 * preopen covering the paths, {@code spit} writes and {@code slurp}, {@code line-seq}
 * (over a path and over an open reader) and {@code clojure.java.io/reader} read, like
 * every other backend. (Without a preopen the open signals the file-error instead --
 * pinned in {@link ClojureWasmFileRefusalTest}. The shared spec yaml cannot pin file IO,
 * so the interpreter and JVM legs live in {@link ClojureInteropTest}.)
 */
class ClojureWasmFileIoTest {

	@TempDir
	static Path workDir;

	@Test
	void spitSlurpLineSeqAndReaderRunWithAPreopenOnPreview1() throws Exception {
		assertThat(runWithPreopen(false)).isEqualTo("a\nb\n(a b)\n7\n");
	}

	@Test
	void spitSlurpLineSeqAndReaderRunWithAPreopenOnTheComponent() throws Exception {
		assertThat(runWithPreopen(true)).isEqualTo("a\nb\n(a b)\n7\n");
	}

	private static String runWithPreopen(boolean component) throws Exception {
		requireWasmtime();
		Path fixture = workDir.resolve("b51-words.txt");
		try (java.io.InputStream in = ClojureWasmFileIoTest.class.getResourceAsStream("/clojure-b22-words.txt")) {
			assertThat(in).isNotNull();
			Files.copy(in, fixture, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		Path written = workDir.resolve("b51-out.txt");
		Files.deleteIfExists(written);
		String out = "\"" + written.toString().replace("\\", "\\\\") + "\"";
		String words = "\"" + fixture.toString().replace("\\", "\\\\") + "\"";
		String program = "(ns b51wio (:require [clojure.java.io :as jio]))" + "(spit " + out + " \"a\\nb\")"
				+ "(println (slurp " + out + "))" + "(println (line-seq " + out + "))" + "(with-open [r (jio/reader "
				+ words + ")] (println (count (line-seq r))))";
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.clojure(program, false, component);
		byte[] module = WasmLispCompiler.builder()
			.component(component)
			.runtimeFeatures(frontend.features().names())
			.build()
			.compile(frontend.forms());
		Path path = Files.createTempFile(workDir, "b51wio", component ? "-c.wasm" : ".wasm");
		Files.write(path, module);
		Path outFile = Files.createTempFile(workDir, "b51wio", ".out");
		Path errFile = Files.createTempFile(workDir, "b51wio", ".err");
		Process process = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "--dir",
				workDir.toString(), path.toString())
			.redirectOutput(outFile.toFile())
			.redirectError(errFile.toFile())
			.start();
		try {
			assertThat(process.waitFor(300, TimeUnit.SECONDS)).isTrue();
			String err = Files.readString(errFile, StandardCharsets.UTF_8);
			assertThat(process.exitValue()).as("wasmtime exit code with a preopen; stderr: %s", err).isZero();
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
