package am.ik.rontolisp.e2e;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import static am.ik.rontolisp.e2e.LackE2eSupport.BACKTRACE_CONDITION;
import static am.ik.rontolisp.e2e.LackE2eSupport.BACKTRACE_MIDDLEWARE_EXPECTED;
import static am.ik.rontolisp.e2e.LackE2eSupport.BUILDER_OVER_CLACKUP_EXPECTED;
import static am.ik.rontolisp.e2e.LackE2eSupport.CLASSPATH;
import static am.ik.rontolisp.e2e.LackE2eSupport.JAVA;
import static am.ik.rontolisp.e2e.LackE2eSupport.LACK_EXERCISE;
import static am.ik.rontolisp.e2e.LackE2eSupport.LACK_EXPECTED;
import static am.ik.rontolisp.e2e.LackE2eSupport.SERVED_BODY_EXERCISE;
import static am.ik.rontolisp.e2e.LackE2eSupport.SERVED_BODY_EXPECTED;
import static am.ik.rontolisp.e2e.LackE2eSupport.SUBSTRATE_EXERCISE;
import static am.ik.rontolisp.e2e.LackE2eSupport.SUBSTRATE_EXPECTED_SPILLING;
import static am.ik.rontolisp.e2e.LackE2eSupport.backtraceMiddlewareExercise;
import static am.ik.rontolisp.e2e.LackE2eSupport.builderOverClackupExercise;
import static am.ik.rontolisp.e2e.LackE2eSupport.freePort;
import static am.ik.rontolisp.e2e.LackE2eSupport.run;
import static am.ik.rontolisp.e2e.LackE2eSupport.runCli;
import static am.ik.rontolisp.e2e.LackE2eSupport.runCliResult;
import static am.ik.rontolisp.e2e.LackE2eSupport.runSuccessfully;
import static am.ik.rontolisp.e2e.LackE2eSupport.writeProgram;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The REAL lack-request / lack-response / lack middleware ecosystem (quickloaded verbatim
 * from the live Quicklisp dist) parses request bodies and runs a session round trip on
 * rontolisp -- the interpreter and JVM legs, which need NO container. The WASM legs of
 * the same exercises live in {@link LackEcosystemWasmE2eTest}; the programs and the
 * subprocess plumbing are shared through {@link LackE2eSupport}.
 *
 * <p>
 * The split is deliberate. While every leg sat in one {@code @Testcontainers} class, a
 * machine without Docker skipped the interpreter and JVM legs too -- and those are
 * exactly the ones that caught the Gray-splice ordering regression (the served-body chain
 * did not compile at all). {@code Tests run: 2, Skipped: 2, BUILD SUCCESS} is not a green
 * suite.
 *
 * <p>
 * Three exercises:
 *
 * <ol>
 * <li><b>the lack chain</b> ({@code lackRequest*}) -- body parsing and the session round
 * trip, identical output on every backend. It used to be interpreter-only behind two
 * compile-backend ceilings (fast-http's {@code parse-header-field-and-value} past the
 * JVM's signed 16-bit branch offset; a {@code concatenate 'simple-byte-vector} deftype
 * alias outside the WASM result-type family); both since lifted --
 * {@code am.ik.jvm.BranchRelaxer} and the registry-aware
 * {@code ConcatenateForms.resultFamily} -- along with the two latent bugs the enablement
 * exposed (the babel package redirect, the redefined-defun duplicate emission). See
 * {@code .kb/lack.md}.</li>
 * <li><b>the substrate the chain rides on</b> ({@code smart-buffer} +
 * {@code flexi-streams}): an in-memory octet stream read back through
 * {@code read-byte}/{@code read-sequence}/{@code file-position}, and the disk-spill path
 * (a payload past the memory limit lands in a {@code uiop:with-temporary-file} temporary
 * and every further chunk APPENDS to it).</li>
 * <li><b>the chain over a SERVED body</b> ({@code lackBuilder*}) -- the buffered
 * {@code :raw-body} the Clack server hands a handler, parsed by {@code lack-request}
 * through circular-streams. Two spellings: over a real socket via {@code clack:clackup}
 * plus {@code fetch} (here only), and transport-free over
 * {@code rontolisp::%http-serve-request} (every backend, see
 * {@link LackE2eSupport#SERVED_BODY_EXERCISE}).</li>
 * <li><b>the DEFAULT middleware's error report</b> ({@code backtraceMiddleware*}) -- a
 * handler that signals, wrapped by the {@code :backtrace} middleware every
 * {@code clack:clackup} adds. What it reports has to be the application's condition, not
 * a diagnostic about the middleware itself; see
 * {@link LackE2eSupport#backtraceMiddlewareExercise}.</li>
 * </ol>
 *
 * Opt-in ({@code RONTOLISP_LACK_E2E=1}): on the first run it needs network access
 * ({@code ql:quickload} downloads lack and its dependencies into
 * {@code ~/.rontolisp/quicklisp}).
 *
 * <pre>{@code
 * RONTOLISP_LACK_E2E=1 ./mvnw -Dtest=LackEcosystemE2eTest -DfailIfNoTests=false test
 * }</pre>
 */
@EnabledIfEnvironmentVariable(named = "RONTOLISP_LACK_E2E", matches = "1")
class LackEcosystemE2eTest {

	@Test
	void lackBuilderParsesARealServedBodyOnTheInterpreter(@TempDir Path workDir) throws Exception {
		Path program = writeProgram(workDir, "builder-clackup.lisp", builderOverClackupExercise(freePort()));
		assertThat(runCli(workDir, program.getFileName().toString()))
			.isEqualToNormalizingWhitespace(BUILDER_OVER_CLACKUP_EXPECTED);
	}

	@Test
	void lackBuilderParsesARealServedBodyOnJvm(@TempDir Path workDir) throws Exception {
		Path program = writeProgram(workDir, "builder-clackup.lisp", builderOverClackupExercise(freePort()));
		runCli(workDir, program.getFileName().toString(), "-o", "BuilderClackup.class");
		assertThat(runSuccessfully(workDir, JAVA, "-cp", CLASSPATH + java.io.File.pathSeparator + workDir,
				"BuilderClackup"))
			.isEqualToNormalizingWhitespace(BUILDER_OVER_CLACKUP_EXPECTED);
	}

	@Test
	void backtraceMiddlewareReportsTheApplicationsRealErrorOnTheInterpreter(@TempDir Path workDir) throws Exception {
		Path program = writeProgram(workDir, "backtrace-mw.lisp", backtraceMiddlewareExercise(freePort()));
		assertBacktraceMiddlewareReport(runCliResult(workDir, program.getFileName().toString()));
	}

	@Test
	void backtraceMiddlewareReportsTheApplicationsRealErrorOnJvm(@TempDir Path workDir) throws Exception {
		Path program = writeProgram(workDir, "backtrace-mw.lisp", backtraceMiddlewareExercise(freePort()));
		runCli(workDir, program.getFileName().toString(), "-o", "BacktraceMw.class");
		assertBacktraceMiddlewareReport(
				run(workDir, JAVA, "-cp", CLASSPATH + java.io.File.pathSeparator + workDir, "BacktraceMw"));
	}

	// The 500 comes back either way; what regressed was WHICH condition the report names.
	private static void assertBacktraceMiddlewareReport(LackE2eSupport.Result result) {
		assertThat(result.exitCode()).as("%s", result).isZero();
		assertThat(result.out()).isEqualToNormalizingWhitespace(BACKTRACE_MIDDLEWARE_EXPECTED);
		assertThat(result.err()).as("%s", result).contains(BACKTRACE_CONDITION).doesNotContain("is unbound");
	}

	@Test
	void lackBuilderParsesAServedBodyOnTheInterpreter(@TempDir Path workDir) throws Exception {
		Path program = writeProgram(workDir, "served-body.lisp", SERVED_BODY_EXERCISE);
		assertThat(runCli(workDir, program.getFileName().toString()))
			.isEqualToNormalizingWhitespace(SERVED_BODY_EXPECTED);
	}

	@Test
	void lackBuilderParsesAServedBodyOnJvm(@TempDir Path workDir) throws Exception {
		Path program = writeProgram(workDir, "served-body.lisp", SERVED_BODY_EXERCISE);
		runCli(workDir, program.getFileName().toString(), "-o", "ServedBody.class");
		assertThat(
				runSuccessfully(workDir, JAVA, "-cp", CLASSPATH + java.io.File.pathSeparator + workDir, "ServedBody"))
			.isEqualToNormalizingWhitespace(SERVED_BODY_EXPECTED);
	}

	@Test
	void lackRequestParsesBodiesAndRunsASessionOnTheInterpreter(@TempDir Path workDir) throws Exception {
		Path program = writeProgram(workDir, "lack-exercise.lisp", LACK_EXERCISE);
		assertThat(runCli(workDir, program.getFileName().toString())).isEqualToNormalizingWhitespace(LACK_EXPECTED);
	}

	@Test
	void lackRequestParsesBodiesAndRunsASessionOnJvm(@TempDir Path workDir) throws Exception {
		Path program = writeProgram(workDir, "lack-exercise.lisp", LACK_EXERCISE);
		runCli(workDir, program.getFileName().toString(), "-o", "LackChain.class");
		assertThat(runSuccessfully(workDir, JAVA, "-cp", CLASSPATH + java.io.File.pathSeparator + workDir, "LackChain"))
			.isEqualToNormalizingWhitespace(LACK_EXPECTED);
	}

	@Test
	void smartBufferSubstrateOnTheInterpreter(@TempDir Path workDir) throws Exception {
		Path program = writeProgram(workDir, "substrate.lisp", SUBSTRATE_EXERCISE);
		assertThat(runCli(workDir, program.getFileName().toString()))
			.isEqualToNormalizingWhitespace(SUBSTRATE_EXPECTED_SPILLING);
	}

	@Test
	void smartBufferSubstrateOnJvm(@TempDir Path workDir) throws Exception {
		Path program = writeProgram(workDir, "substrate.lisp", SUBSTRATE_EXERCISE);
		runCli(workDir, program.getFileName().toString(), "-o", "Substrate.class");
		assertThat(runSuccessfully(workDir, JAVA, "-cp", CLASSPATH + java.io.File.pathSeparator + workDir, "Substrate"))
			.isEqualToNormalizingWhitespace(SUBSTRATE_EXPECTED_SPILLING);
	}

}
