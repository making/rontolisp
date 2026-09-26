package am.ik.rontolisp.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import am.ik.rontolisp.compiler.UncaughtReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --report-locations}: a wasm-GC module prints the interpreter's location lines
 * under its uncaught report, on Preview 1 and {@code --component} alike, and a module
 * without the report is byte-identical with and without the option
 * ({@code .kb/error-handling.md}, "Location lines on wasm-GC").
 */
class WasmReportLocationsTest {

	@TempDir
	Path tempDir;

	@Test
	@EnabledIf("am.ik.rontolisp.testsupport.HostWasmtime#isAvailable")
	void lineGranularityPrintsTheInterpretersLinesOnBothBackends() throws Exception {
		// The interpreter's own case (RontoLispCliStreamsTest): through a package, a
		// dolist/when expansion, and a tail call into a library function whose forms
		// carry no position -- the call site in PARSE is what is reported.
		Path program = write("app.lisp", """
				(defpackage :app (:use :cl))
				(in-package :app)

				(defun parse (s)
				  (parse-integer s))

				(defun run ()
				  (dolist (s '("1" "x"))
				    (when s
				      (print (parse s)))))

				(print (ignore-errors (parse "y")))
				(run)
				""");
		List<String> expected = List.of("Unhandled condition: parse-integer: junk in string \"x\"",
				"  at " + program + ":5 in APP::PARSE");
		assertThat(interpreterReport(program)).isEqualTo(expected);
		assertThat(wasmReport(program, "--report-locations=line")).isEqualTo(expected);
		assertThat(wasmReport(program, "--report-locations=line", "--component")).isEqualTo(expected);
	}

	@Test
	@EnabledIf("am.ik.rontolisp.testsupport.HostWasmtime#isAvailable")
	void functionGranularityNamesTheFunctionAndTheLineItsDefinitionStartsOn() throws Exception {
		Path program = write("app.lisp", """
				(defun parse (s)
				  (let ((n (length s)))
				    (parse-integer s :end n)))

				(print (ignore-errors (parse "y")))
				(parse "x")
				""");
		List<String> expected = List.of("Unhandled condition: parse-integer: junk in string \"x\"",
				"  at " + program + ":1 in PARSE");
		assertThat(wasmReport(program, "--report-locations=function")).isEqualTo(expected);
		assertThat(wasmReport(program, "--report-locations=function", "--component")).isEqualTo(expected);
	}

	@Test
	@EnabledIf("am.ik.rontolisp.testsupport.HostWasmtime#isAvailable")
	void anAsyncBodysConditionNamesTheAsyncFunctionAndEveryAwaitSite() throws Exception {
		// Preview 1 runs the body at the call, --component on a state machine whose
		// rejected future the await re-signals: both print the interpreter's hops.
		Path program = write("prices.lisp", """
				(print (ignore-errors (error "caught")))
				(rontolisp:async-defun current-price (symbol)
				  (let ((feed nil))
				    (error "no price for ~a" symbol)))

				(rontolisp:async-defun portfolio ()
				  (list
				    (rontolisp:await (current-price "ABC"))))

				(print (rontolisp:await (portfolio)))
				""");
		List<String> expected = List.of("Unhandled condition: no price for ABC", "  at " + program + ":4",
				"  in CURRENT-PRICE (async), awaited at " + program + ":8",
				"  in PORTFOLIO (async), awaited at " + program + ":10");
		assertThat(interpreterReport(program)).isEqualTo(expected);
		assertThat(wasmReport(program, "--report-locations=line")).isEqualTo(expected);
		assertThat(wasmReport(program, "--report-locations=line", "--component")).isEqualTo(expected);
	}

	@Test
	@EnabledIf("am.ik.rontolisp.testsupport.HostWasmtime#isAvailable")
	void aSchemeProgramNamesTheProcedureHoldingTheForm() throws Exception {
		Path program = write("app.scm", """
				(import (scheme base) (scheme write))

				(define-syntax must
				  (syntax-rules ()
				    ((_ x) (or x (error "not a number")))))

				(define (parse s)
				  (must (string->number s)))

				(define (run)
				  (for-each (lambda (s)
				              (when s
				                (display (parse s))))
				            '("1" "x")))

				(guard (e (#t (display "caught")))
				  (raise 'oops))
				(run)
				""");
		List<String> expected = List.of("Unhandled condition: not a number", "  at " + program + ":8 in parse");
		assertThat(interpreterReport(program)).isEqualTo(expected);
		assertThat(wasmReport(program, "--report-locations=line")).isEqualTo(expected);
	}

	@Test
	@EnabledIf("am.ik.rontolisp.testsupport.HostWasmtime#isAvailable")
	void aMethodAndALocalFunctionInItReportAsTheInterpreterDoes() throws Exception {
		// The method body is a function the lowering names %AREA--m0, reported as its
		// generic; the labels function inside it reports its own line, not the labels
		// form's, on both.
		Path program = write("shapes.lisp", """
				(defgeneric area (s))
				(defmethod area ((s list))
				  (labels ((walk (xs acc)
				             (if (null xs)
				                 (error "no area for ~a" acc)
				                 (walk (cdr xs) (+ acc (car xs))))))
				    (walk s 0)))

				(print (ignore-errors (area '(1))))
				(area '(1 2))
				""");
		List<String> expected = List.of("Unhandled condition: no area for 3", "  at " + program + ":5 in AREA");
		assertThat(interpreterReport(program)).isEqualTo(expected);
		assertThat(wasmReport(program, "--report-locations=line")).isEqualTo(expected);
	}

	@Test
	@EnabledIf("am.ik.rontolisp.testsupport.HostWasmtime#isAvailable")
	void aConditionAHandlerCaughtLeavesNothingALaterOneCouldMisreport() throws Exception {
		// The frames note the first condition on its way to the handler-case; the second
		// escapes from the top level, in no function, and must say only that.
		Path program = write("twice.lisp", """
				(defun deep (n)
				  (if (> n 3)
				      (error "deep ~a" n)
				      (deep (+ n 1))))

				(print (handler-case (deep 0) (error () :caught)))
				(error "later")
				""");
		List<String> expected = List.of("Unhandled condition: later", "  at " + program + ":7");
		assertThat(interpreterReport(program)).isEqualTo(expected);
		assertThat(wasmReport(program, "--report-locations=line")).isEqualTo(expected);
		assertThat(wasmReport(program, "--report-locations=function")).isEqualTo(expected);
	}

	@Test
	@EnabledIf("am.ik.rontolisp.testsupport.HostWasmtime#isAvailable")
	void aTailCallStillRunsInConstantStackAndASignalInTailPositionKeepsItsFrame() throws Exception {
		// A tail call into another frame or through a function value stays a
		// return_call, so the depths .kb/wasm-tail-calls.md pins still hold; a tail call
		// into a signal becomes a plain call, or FAILS would never be named.
		Path program = write("tail.lisp", """
				(print (ignore-errors (error "caught")))
				(defun count-down (n acc)
				  (if (= n 0)
				      acc
				      (count-down (- n 1) (+ acc 1))))
				(print (count-down 1000000 0))
				(print (labels ((walk (n) (if (= n 0) :walked (walk (- n 1))))) (walk 1000000)))
				(defun fails (x)
				  (error "fails with ~a" x))
				(fails 42)
				""");
		for (String granularity : List.of("line", "function")) {
			Result run = compileAndRun(program, "--report-locations=" + granularity);
			assertThat(run.stdout()).as(granularity).isEqualTo("NIL\n1000000\n:WALKED\n");
			assertThat(report(run.stderr())).as(granularity)
				.containsExactly("Unhandled condition: fails with 42",
						"  at " + program + ":" + (granularity.equals("line") ? 9 : 8) + " in FAILS");
		}
	}

	@Test
	@EnabledIf("am.ik.rontolisp.testsupport.HostWasmtime#isAvailable")
	void aTailCallIntoAFunctionWithNoPositionKeepsTheCallersFrame() throws Exception {
		// CHECK's body is a macro's output, so it is not a frame; a return_call into it
		// would leave USE's frame too, and the report would fall back to the top level.
		// Called from two functions, so the optimizer's inliner keeps it a call.
		Path program = write("checker.lisp", """
				(defmacro define-checker (name)
				  `(defun ,name (x) (if (numberp x) x (error "not a number: ~a" x))))
				(define-checker check)
				(defun use (x)
				  (check x))
				(defun also (x)
				  (check x))
				(print (ignore-errors (also "b")))
				(use "a")
				""");
		List<String> expected = List.of("Unhandled condition: not a number: a", "  at " + program + ":5 in USE");
		assertThat(interpreterReport(program)).isEqualTo(expected);
		assertThat(wasmReport(program, "--report-locations=line")).isEqualTo(expected);
	}

	@Test
	void outsideExceptionHandlingModeTheOptionAddsNoByte() throws Exception {
		// No catching form: the module has no report, so nothing to put lines under.
		Path program = write("plain.lisp", """
				(defun parse (s)
				  (parse-integer s))
				(print (parse "12"))
				""");
		byte[] plain = Files.readAllBytes(compile(program, "plain.wasm"));
		for (String granularity : List.of("line", "function")) {
			assertThat(Files.readAllBytes(compile(program, granularity + ".wasm", "--report-locations=" + granularity)))
				.as(granularity)
				.isEqualTo(plain);
		}
	}

	@Test
	void aProgramReadFromNoFileHasNothingToLocate() throws Exception {
		// -e: in exception-handling mode, but no form was read from a file, so there
		// is no frame to note anything and the module is the one the option was not
		// asked for.
		String source = "(print (ignore-errors (error \"x\"))) (defun f (s) (parse-integer s)) (f \"y\")";
		Path plain = this.tempDir.resolve("plain.wasm");
		Path located = this.tempDir.resolve("located.wasm");
		assertThat(runReporting("-e", source, "-o", plain.toString())[0]).isEqualTo("0");
		assertThat(runReporting("-e", source, "-o", located.toString(), "--report-locations=line")[0]).isEqualTo("0");
		assertThat(Files.readAllBytes(located)).isEqualTo(Files.readAllBytes(plain));
	}

	@Test
	void theOptionIsRefusedWhereItCouldMeanNothing() throws Exception {
		Path program = write("app.lisp", "(print 1)\n");
		assertThat(runReporting(program.toString(), "-o", this.tempDir.resolve("App.class").toString(),
				"--report-locations=line")[2])
			.contains("--report-locations reaches a wasm-GC output only");
		assertThat(runReporting(program.toString(), "-o", this.tempDir.resolve("s.wasm").toString(), "--no-gc",
				"--report-locations=line")[2])
			.contains("--report-locations reaches a wasm-GC output only");
		assertThat(runReporting(program.toString(), "--report-locations=line")[2])
			.contains("--report-locations chooses what a compiled wasm-GC module");
		assertThat(runReporting(program.toString(), "-o", this.tempDir.resolve("a.wasm").toString(),
				"--report-locations=everything")[2])
			.contains("--report-locations takes 'function' or 'line' ('everything' given)");
	}

	private Path write(String name, String source) throws IOException {
		Path program = this.tempDir.resolve(name);
		Files.writeString(program, source);
		return program;
	}

	private Path compile(Path program, String output, String... flags) {
		Path wasm = this.tempDir.resolve(output);
		List<String> args = new ArrayList<>(List.of(program.toString(), "-o", wasm.toString()));
		args.addAll(List.of(flags));
		String[] result = runReporting(args.toArray(new String[0]));
		assertThat(result[0]).as("compile %s: %s", program, result[2]).isEqualTo("0");
		return wasm;
	}

	/** The interpreter's report and location lines for {@code program}. */
	private List<String> interpreterReport(Path program) {
		String[] result = runReporting(program.toString());
		assertThat(result[0]).isEqualTo("1");
		return report(result[2]);
	}

	/** The wasm module's report and location lines, wasmtime's own trap note dropped. */
	private List<String> wasmReport(Path program, String... flags) throws Exception {
		Result run = compileAndRun(program, flags);
		assertThat(run.exitCode()).isNotZero();
		return report(run.stderr());
	}

	private Result compileAndRun(Path program, String... flags) throws Exception {
		Path wasm = compile(program, "out.wasm", flags);
		Path out = this.tempDir.resolve("run.out");
		Path err = this.tempDir.resolve("run.err");
		Process process = new ProcessBuilder("wasmtime", "run", wasm.toString()).redirectOutput(out.toFile())
			.redirectError(err.toFile())
			.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
			.start();
		assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
		return new Result(process.exitValue(), Files.readString(out), Files.readString(err));
	}

	private static List<String> report(String stderr) {
		return stderr.lines()
			.filter(line -> line.startsWith(UncaughtReport.PREFIX) || UncaughtReport.isLocationLine(line))
			.toList();
	}

	/** {@code {exitCode, stdout, stderr}} of the CLI's reporting entry point. */
	private String[] runReporting(String... args) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		RontoLispCli cli = new RontoLispCli(new ByteArrayInputStream(new byte[0]), new PrintStream(out));
		PrintStream oldErr = System.err;
		System.setErr(new PrintStream(err));
		int code;
		try {
			code = RontoLispCli.runReporting(cli, args);
		}
		finally {
			System.setErr(oldErr);
		}
		return new String[] { String.valueOf(code), out.toString(StandardCharsets.UTF_8),
				err.toString(StandardCharsets.UTF_8) };
	}

	private record Result(int exitCode, String stdout, String stderr) {
	}

}
