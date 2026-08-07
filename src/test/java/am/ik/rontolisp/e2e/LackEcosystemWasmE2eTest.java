package am.ik.rontolisp.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.testsupport.WasmtimeSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Testcontainers;

import static am.ik.rontolisp.e2e.LackE2eSupport.LACK_EXERCISE;
import static am.ik.rontolisp.e2e.LackE2eSupport.LACK_EXPECTED;
import static am.ik.rontolisp.e2e.LackE2eSupport.SERVED_BODY_EXERCISE;
import static am.ik.rontolisp.e2e.LackE2eSupport.SERVED_BODY_EXPECTED;
import static am.ik.rontolisp.e2e.LackE2eSupport.SUBSTRATE_EXERCISE;
import static am.ik.rontolisp.e2e.LackE2eSupport.SUBSTRATE_EXPECTED_NO_FILESYSTEM;
import static am.ik.rontolisp.e2e.LackE2eSupport.runCli;
import static am.ik.rontolisp.e2e.LackE2eSupport.writeProgram;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The WASM legs of the lack-ecosystem exercises {@link LackEcosystemE2eTest} runs on the
 * interpreter and the JVM -- Preview 1 and the {@code --component} backend, both inside
 * the pinned wasmtime container. Everything Docker-gated lives HERE and nowhere else, so
 * a machine without Docker skips only these.
 *
 * <p>
 * The smart-buffer spill is interpreter/JVM only: both WASM backends signal the standard
 * {@code ensure-directories-exist} message at CALL time, the documented divergence
 * ({@code .kb/directory-listing.md}), which the program catches and prints -- hence the
 * separate expectation here.
 *
 * <p>
 * Opt-in ({@code RONTOLISP_LACK_E2E=1}): it needs Docker (the pinned wasmtime image) and,
 * on the first run, network access.
 *
 * <pre>{@code
 * RONTOLISP_LACK_E2E=1 ./mvnw -Dtest=LackEcosystemWasmE2eTest -DfailIfNoTests=false test
 * }</pre>
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "RONTOLISP_LACK_E2E", matches = "1")
class LackEcosystemWasmE2eTest {

	@Test
	void lackRequestParsesBodiesAndRunsASessionOnWasmPreview1(@TempDir Path workDir) throws Exception {
		assertThat(runWasm(workDir, LACK_EXERCISE, "lack-p1.wasm", List.of()))
			.isEqualToNormalizingWhitespace(LACK_EXPECTED);
	}

	@Test
	void lackRequestParsesBodiesAndRunsASessionOnWasmComponent(@TempDir Path workDir) throws Exception {
		assertThat(runWasm(workDir, LACK_EXERCISE, "lack-comp.wasm", List.of("--component")))
			.isEqualToNormalizingWhitespace(LACK_EXPECTED);
	}

	@Test
	void lackBuilderParsesAServedBodyOnWasmPreview1(@TempDir Path workDir) throws Exception {
		assertThat(runWasm(workDir, SERVED_BODY_EXERCISE, "served-p1.wasm", List.of()))
			.isEqualToNormalizingWhitespace(SERVED_BODY_EXPECTED);
	}

	@Test
	void lackBuilderParsesAServedBodyOnWasmComponent(@TempDir Path workDir) throws Exception {
		assertThat(runWasm(workDir, SERVED_BODY_EXERCISE, "served-comp.wasm", List.of("--component")))
			.isEqualToNormalizingWhitespace(SERVED_BODY_EXPECTED);
	}

	@Test
	void smartBufferSubstrateOnWasmPreview1(@TempDir Path workDir) throws Exception {
		assertThat(runWasm(workDir, SUBSTRATE_EXERCISE, "substrate-p1.wasm", List.of()))
			.isEqualToNormalizingWhitespace(SUBSTRATE_EXPECTED_NO_FILESYSTEM);
	}

	@Test
	void smartBufferSubstrateOnWasmComponent(@TempDir Path workDir) throws Exception {
		assertThat(runWasm(workDir, SUBSTRATE_EXERCISE, "substrate-comp.wasm", List.of("--component")))
			.isEqualToNormalizingWhitespace(SUBSTRATE_EXPECTED_NO_FILESYSTEM);
	}

	// Compiles the given exercise to WASM and runs it in the pinned wasmtime
	// container. -W exceptions=y: every exercise compiles in EH mode (the substrate's
	// handler-case around the spill; the lack chain's handler-case/unwind-protect
	// sites inside the loaded libraries; the served-body chain is async).
	private String runWasm(Path workDir, String source, String output, List<String> extraFlags) throws Exception {
		Path program = writeProgram(workDir, "exercise.lisp", source);
		List<String> args = new ArrayList<>(List.of(program.getFileName().toString(), "-o", output));
		args.addAll(extraFlags);
		runCli(workDir, args.toArray(String[]::new));
		String path = "/tmp/" + workDir.getFileName() + "-" + output;
		WasmtimeSupport.container()
			.copyFileToContainer(Transferable.of(Files.readAllBytes(workDir.resolve(output))), path);
		ExecResult result = WasmtimeSupport.container()
			.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "--dir", "/tmp", path);
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		return result.getStdout();
	}

}
