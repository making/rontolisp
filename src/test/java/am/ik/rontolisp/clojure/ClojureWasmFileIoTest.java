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
 * (over a path and over an open reader), {@code clojure.java.io/reader} and {@code read}
 * through a {@code PushbackReader} over one read, like every other backend. (Without a
 * preopen the open signals the file-error instead -- pinned in
 * {@link ClojureWasmFileRefusalTest}. The shared spec yaml cannot pin file IO, so the
 * interpreter and JVM legs live in {@link ClojureInteropTest}.)
 */
class ClojureWasmFileIoTest {

	@TempDir
	static Path workDir;

	@Test
	void spitSlurpLineSeqAndReaderRunWithAPreopenOnPreview1() throws Exception {
		assertThat(runWithPreopen(false))
			.isEqualTo("a\nb\n(a b)\n(1 2)\n[1 2]\n{:a 1}\n42\n\"\"\ns#fileio.Rec{:a 1, :b 2}\n7\n"
					+ "(#fileio.Rec{:a 1, :b \"x\"}) :eof\n");
	}

	@Test
	void spitSlurpLineSeqAndReaderRunWithAPreopenOnTheComponent() throws Exception {
		assertThat(runWithPreopen(true))
			.isEqualTo("a\nb\n(a b)\n(1 2)\n[1 2]\n{:a 1}\n42\n\"\"\ns#fileio.Rec{:a 1, :b 2}\n7\n"
					+ "(#fileio.Rec{:a 1, :b \"x\"}) :eof\n");
	}

	private static String runWithPreopen(boolean component) throws Exception {
		requireWasmtime();
		Path fixture = workDir.resolve("words.txt");
		try (java.io.InputStream in = ClojureWasmFileIoTest.class.getResourceAsStream("/clojure-words.txt")) {
			assertThat(in).isNotNull();
			Files.copy(in, fixture, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		Path written = workDir.resolve("out.txt");
		Files.deleteIfExists(written);
		String out = "\"" + written.toString().replace("\\", "\\\\") + "\"";
		String words = "\"" + fixture.toString().replace("\\", "\\\\") + "\"";
		String program = "(ns fileio (:require [clojure.java.io :as jio]))" + "(defrecord Rec [a b])" + "(spit " + out
				+ " \"a\\nb\")" + "(println (slurp " + out + "))" + "(println (line-seq " + out + "))" + "(spit " + out
				+ " '(1 2))" + "(println (slurp " + out + "))" + "(spit " + out + " [1 2])" + "(println (slurp " + out
				+ "))" + "(spit " + out + " {:a 1})" + "(println (slurp " + out + "))" + "(spit " + out + " 42)"
				+ "(println (slurp " + out + "))" + "(spit " + out + " nil)" + "(println (pr-str (slurp " + out + ")))"
				+ "(spit " + out + " \"s\")" + "(spit " + out + " (->Rec 1 2) :append true)" + "(println (slurp " + out
				+ "))" + "(with-open [r (jio/reader " + words + ")] (println (count (line-seq r))))" + "(spit " + out
				+ " (list (->Rec 1 \"x\")))" + "(with-open [r (java.io.PushbackReader. (jio/reader " + out + "))]"
				+ " (prn (read r) (read r false :eof)))";
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.clojure(program, true, component);
		byte[] module = WasmLispCompiler.builder()
			.component(component)
			.runtimeFeatures(frontend.features().names())
			.build()
			.compile(frontend.forms());
		Path path = Files.createTempFile(workDir, "fileio", component ? "-c.wasm" : ".wasm");
		Files.write(path, module);
		Path outFile = Files.createTempFile(workDir, "fileio", ".out");
		Path errFile = Files.createTempFile(workDir, "fileio", ".err");
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
