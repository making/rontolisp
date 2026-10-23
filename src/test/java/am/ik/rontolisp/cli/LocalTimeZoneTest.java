package am.ik.rontolisp.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import am.ik.rontolisp.testsupport.HostWasmtime;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Without a time-zone, {@code decode-universal-time}, {@code encode-universal-time} and
 * {@code get-decoded-time} take the zone the process's {@code TZ} names, daylight saving
 * time included, on the interpreter, a compiled class, a Preview 1 module and a component
 * -- each run as its own process with {@code TZ} set, the WASM ones under
 * {@code wasmtime run --env TZ=... --dir /usr/share/zoneinfo}. The expected lines are
 * SBCL's for the same program and zone: New York across both 2026 transitions (a
 * spring-forward gap and a fall-back overlap encoded where SBCL puts them), and Kolkata's
 * half-hour zone as a rational. An explicit zone is untouched. The {@code --native} leg
 * is {@code NativeOutputE2eTest}'s.
 */
class LocalTimeZoneTest {

	private static final String PROGRAM = """
			(dolist (ut '(3981941999 3981942000 4002499800 4002503400))
			  (print (multiple-value-list (decode-universal-time ut))))
			(print (list (encode-universal-time 0 30 2 8 3 2026) (encode-universal-time 0 30 1 1 11 2026)))
			(print (multiple-value-list (decode-universal-time 3900000000 -9)))
			(print (= (length (multiple-value-list (get-decoded-time))) 9))
			""";

	private static final Map<String, String> EXPECTED = Map.of("America/New_York", """
			(59 59 1 8 3 2026 6 NIL 5)
			(0 0 3 8 3 2026 6 T 5)
			(0 30 1 1 11 2026 6 T 5)
			(0 30 1 1 11 2026 6 NIL 5)
			(3981940200 4002499800)
			(0 20 6 3 8 2023 3 NIL -9)
			T
			""", "Asia/Kolkata", """
			(59 29 12 8 3 2026 6 NIL -11/2)
			(0 30 12 8 3 2026 6 NIL -11/2)
			(0 0 11 1 11 2026 6 NIL -11/2)
			(0 0 12 1 11 2026 6 NIL -11/2)
			(3981906000 4002465600)
			(0 20 6 3 8 2023 3 NIL -9)
			T
			""");

	private static final Path ZONEINFO = Path.of("/usr/share/zoneinfo");

	@TempDir
	Path dir;

	@ParameterizedTest
	@ValueSource(strings = { "interpreter", "jvm", "wasm", "component" })
	void theLocalZoneIsTheOneTzNames(String backend) throws Exception {
		Path src = this.dir.resolve("probe.lisp");
		Files.writeString(src, PROGRAM);
		List<String> command = switch (backend) {
			case "interpreter" -> List.of(java(), "-cp", System.getProperty("java.class.path"),
					"am.ik.rontolisp.cli.RontoLispCli", src.toString());
			case "jvm" -> {
				compile(src, this.dir.resolve("LocalTimeProbe.class"));
				yield List.of(java(), "-cp", this.dir.toString(), "LocalTimeProbe");
			}
			default -> {
				assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
				assumeTrue(Files.isRegularFile(ZONEINFO.resolve("America/New_York")), "no zone files in " + ZONEINFO);
				Path module = this.dir.resolve(backend + ".wasm");
				compile(src, module, "component".equals(backend) ? new String[] { "--component" } : new String[0]);
				yield List.of("wasmtime", "run", "--env", "TZ", "--dir", ZONEINFO.toString(), module.toString());
			}
		};
		for (Map.Entry<String, String> zone : EXPECTED.entrySet()) {
			assertThat(run(command, zone.getKey())).as("%s under TZ=%s", backend, zone.getKey())
				.isEqualTo(zone.getValue());
		}
	}

	private static void compile(Path src, Path output, String... flags) throws Exception {
		List<String> args = new ArrayList<>(List.of(src.toString(), "-o", output.toString()));
		args.addAll(List.of(flags));
		new RontoLispCli(new ByteArrayInputStream(new byte[0]),
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
			.run(args.toArray(String[]::new));
	}

	// `wasmtime run --env TZ` passes the variable on from this process's environment.
	private String run(List<String> command, String tz) throws Exception {
		Path errors = this.dir.resolve("stderr.log");
		ProcessBuilder builder = new ProcessBuilder(command).directory(this.dir.toFile())
			.redirectError(errors.toFile())
			.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()));
		builder.environment().put("TZ", tz);
		Process process = builder.start();
		try {
			String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("%s ended", command).isTrue();
			assertThat(process.exitValue()).as(Files.readString(errors)).isZero();
			return stdout;
		}
		finally {
			process.destroyForcibly();
		}
	}

	private static String java() {
		return ProcessHandle.current().info().command().orElse("java");
	}

}
