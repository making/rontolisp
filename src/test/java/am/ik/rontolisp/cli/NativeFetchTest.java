package am.ik.rontolisp.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.compiler.FetchResponseShape;
import am.ik.rontolisp.eval.HostFetchLibrary;
import am.ik.wasm.WasmImports;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code rontolisp:fetch} in a {@code --native} output, from the compile side: the module
 * a native output carries fetches over the runner's {@code rlhttp} imports
 * ({@code HostFetchLibrary}'s runner shape), a program that never fetches carries the
 * module it always did, and the output starts with the runner its imports need. What the
 * fetch then DOES is the cross-backend corpus's ({@code FetchSpecE2eTest}).
 */
class NativeFetchTest {

	@TempDir
	Path tempDir;

	/** The module the command line builds for a {@code .wasm} or a native output. */
	private static byte[] module(String source, @Nullable String nativePlatform) {
		CompileFrontend.Result frontend = CompileFrontend.run(CompileFrontend.Request.builder()
			.source(source)
			.options(CompileFrontend.Options.builder().wasm(true).nativePlatform(nativePlatform).build())
			.build());
		return WasmLispCompiler.builder()
			.runnerFetch(nativePlatform != null)
			.runtimeFeatures(frontend.features().names())
			.build()
			.compile(frontend.program());
	}

	@Test
	void aProgramThatNeverFetchesCarriesTheModuleItAlwaysDid() {
		// Futures included: the deferred arm of the await runtime exists only in a
		// module that can meet a deferred future.
		String source = """
				(rontolisp:async-defun twice (x) (* 2 x))
				(print (rontolisp:await (twice 21)))
				(print (rontolisp:futurep (twice 1)))
				""";
		byte[] wasm = module(source, null);
		for (String platform : NativeTarget.PLATFORMS) {
			assertThat(module(source, platform)).as("the %s module", platform).isEqualTo(wasm);
		}
	}

	@Test
	void aFetchingProgramImportsTheRunnersHostAndNothingAHostWouldSupply() {
		byte[] module = module("(print (getf (rontolisp:await (rontolisp:fetch \"http://127.0.0.1:1/\")) :status))",
				"linux-x86_64");
		assertThat(WasmImports.functionFields(module, FetchResponseShape.RUNNER_IMPORT_MODULE)).containsExactly(
				FetchResponseShape.RUNNER_START_FIELD, FetchResponseShape.RUNNER_HEAD_FIELD,
				HostFetchLibrary.BODY_IMPORT_FIELD);
		assertThat(WasmLispCompiler.importModules(module)).containsExactlyInAnyOrder("wasi_snapshot_preview1",
				FetchResponseShape.RUNNER_IMPORT_MODULE);
	}

	@Test
	void aPreview1WasmStillHasNoTransportAndSaysWhichOutputsDo() {
		assertThatThrownBy(() -> module("(rontolisp:fetch \"http://127.0.0.1:1/\")", null))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--component")
			.hasMessageContaining("--native");
	}

	/**
	 * The runner is picked by what the finished module imports: {@code rlrun-net} for a
	 * fetch's {@code rlhttp}, the plain {@code rlrun} otherwise. On Linux the output is
	 * the stub with the module appended, so it starts with the stub's bytes.
	 */
	@Test
	void theOutputStartsWithTheRunnerItsModuleNeeds() throws Exception {
		assumeTrue(NativeToolchain.availableOnHost(), "no --native shim for this host on the classpath");
		assumeTrue(System.getProperty("os.name", "").startsWith("Linux"),
				"a macOS output embeds the module in the stub's image instead of appending it");
		String host = Objects.requireNonNull(NativeTarget.hostPlatform());
		NativeToolchain toolchain = NativeToolchain.load();
		byte[] fetching = nativeOutput("fetching", "(print (rontolisp:futurep (rontolisp:fetch \"http://h/\")))");
		byte[] plain = nativeOutput("plain", "(print 1)");
		byte[] network = toolchain.stub(host, true);
		assertThat(Arrays.copyOf(fetching, network.length)).isEqualTo(network);
		byte[] stub = toolchain.stub(host);
		assertThat(Arrays.copyOf(plain, stub.length)).isEqualTo(stub);
		assertThat(plain.length).isLessThan(network.length);
	}

	private byte[] nativeOutput(String name, String source) throws Exception {
		Path program = this.tempDir.resolve(name + ".lisp");
		Files.writeString(program, source);
		Path output = this.tempDir.resolve(name);
		new RontoLispCli(new ByteArrayInputStream(new byte[0]),
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
			.run(new String[] { program.toString(), "--native", "-o", output.toString() });
		return Files.readAllBytes(output);
	}

}
