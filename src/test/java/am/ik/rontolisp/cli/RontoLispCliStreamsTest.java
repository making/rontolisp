package am.ik.rontolisp.cli;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CLI end to end, in process, for the methods that take over a process-wide stream:
 * {@code System.err} to read what the CLI reported there ({@link #runReporting}), or
 * {@code System.out}. Split out of {@link RontoLispCliTest} so that class can run its
 * methods concurrently: a top-level class runs alone in its fork, so a capture here never
 * sees another method's output.
 */
class RontoLispCliStreamsTest {

	@TempDir
	Path tempDir;

	private String runCli(String input, String... args) {
		ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		RontoLispCli cli = new RontoLispCli(in, new PrintStream(out));
		cli.run(args);
		return out.toString(StandardCharsets.UTF_8);
	}

	/**
	 * Drives the reporting wrapper {@code main} runs the CLI through, answering
	 * {@code {exitCode, stdout, stderr}}. {@code main} itself ends in
	 * {@code System.exit}, so it cannot be called from a test JVM.
	 */
	private String[] runReporting(String... args) {
		return runReporting(new RontoLispCli(new ByteArrayInputStream(new byte[0]), new PrintStream(this.stdout)),
				args);
	}

	private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();

	/**
	 * A REPL session through the reporting wrapper, fed the input as a PIPE feeds it (no
	 * prompt, failures on standard error) or as a TERMINAL does: {@code {exitCode,
	 * stdout, stderr}}.
	 */
	private String[] runSession(boolean terminal, String input, String... args) {
		RontoLispCli cli = new RontoLispCli(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
				new PrintStream(this.stdout, true, StandardCharsets.UTF_8));
		cli.assumeTerminal(terminal);
		return runReporting(cli, args);
	}

	private String[] runReporting(RontoLispCli cli, String... args) {
		ByteArrayOutputStream out = this.stdout;
		out.reset();
		ByteArrayOutputStream err = new ByteArrayOutputStream();
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

	// A program deeper than the 1 MiB linux-x64 gives a process's first thread, and well
	// inside the stack the CLI hands the interpreter. cl-mustache's spec suite is the
	// real-world specimen (~800 KiB down); this is the same shape in two lines.
	//
	// The depth is MEASURED, and measured beside the run it justifies. What 1 MiB holds
	// is the JIT's answer, not the program's: 337 calls with C1 frames, 511 cold, ~1130
	// once Graal has compiled the evaluator, and more than 1500 on CI run 34637528638.
	// A depth found once and reused later (a constant, or a search cached for the JVM)
	// can therefore fit by the time it is asserted on.
	private static final int DEEP_PROGRAM_FIRST_DEPTH = 1500;

	private static final int DEEP_PROGRAM_MAX_DEPTH = 1 << 16;

	// What linux-x64 gives thread 0, and so what the CLI used to interpret on there.
	private static final long LAUNCHER_STACK_BYTES = 1L << 20;

	private static String deepProgram(int depth) {
		return """
				(defun depth (n) (if (= n 0) 0 (+ 1 (depth (- n 1)))))
				(print (depth %d))
				""".formatted(depth);
	}

	// Runs the body on a thread with the given stack and answers what it threw, so a
	// StackOverflowError is this test's evidence rather than its failure.
	private static @Nullable Throwable onAStackOf(long stackBytes, Runnable body) throws InterruptedException {
		Throwable[] thrown = new Throwable[1];
		Thread thread = new Thread(null, () -> {
			try {
				body.run();
			}
			catch (Throwable ex) {
				thrown[0] = ex;
			}
		}, "launcher", stackBytes);
		thread.start();
		thread.join();
		return thrown[0];
	}

	@Test
	void aWrittenHttpHandlerPortWarnsOnceOnAWarBecauseTheContainerOwnsThePort() throws Exception {
		Path program = this.tempDir.resolve("app.lisp");
		Files.writeString(program, """
				(defun handle (env)
				  (list 200 '(:content-type "text/plain") (list "ok")))
				(rontolisp:http-handler 'handle 8080)
				""");
		java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
		PrintStream oldErr = System.err;
		System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
		try {
			runCli("", program.toString(), "-o", this.tempDir.resolve("app.war").toString());
		}
		finally {
			System.setErr(oldErr);
		}
		assertThat(err.toString(StandardCharsets.UTF_8)).contains("the servlet container owns the port");
	}

	@Test
	void anUncaughtConditionReportsOneLineAndExitsOne() throws Exception {
		// The interpreter's half of the cross-backend contract: the condition's report,
		// once, on standard error -- not the 16 (212 for a cl-postgres connect) frames
		// of LispEvaluator the default handler used to print -- and under it where it
		// happened. The program's own output still comes out.
		Path program = this.tempDir.resolve("boom.lisp");
		Files.writeString(program, "(print \"before\")\n(error \"boom: ~a\" 42)\n");
		String[] result = runReporting(program.toString());
		assertThat(result[0]).isEqualTo("1");
		assertThat(result[1]).isEqualTo("\"before\"\n");
		assertThat(result[2].lines()).containsExactly("Unhandled condition: boom: 42", "  at " + program + ":2");
	}

	@Test
	void anUncaughtConditionNamesTheInnermostFormAndTheFunctionHoldingIt() throws Exception {
		// The innermost form read from the file that the condition passed through, and
		// the named function whose body holds it -- through a package (the resolver's
		// rewrite keeps the position), a when/dolist expansion, and a tail call into a
		// built-in library function whose own forms carry no position: the call site in
		// PARSE is what is reported, not the library's body and not RUN.
		Path program = this.tempDir.resolve("app.lisp");
		Files.writeString(program, """
				(defpackage :app (:use :cl))
				(in-package :app)

				(defun parse (s)
				  (parse-integer s))

				(defun run ()
				  (dolist (s '("1" "x"))
				    (when s
				      (print (parse s)))))

				(run)
				""");
		String[] result = runReporting(program.toString());
		assertThat(result[0]).isEqualTo("1");
		assertThat(result[2].lines()).containsExactly("Unhandled condition: parse-integer: junk in string \"x\"",
				"  at " + program + ":5 in APP::PARSE");
	}

	@Test
	void anUncaughtConditionInASchemeFileNamesTheInnermostFormAndTheProcedureHoldingIt() throws Exception {
		// The .scm twin: the lowering rebuilds every datum into core forms, and each
		// rewrite keeps the position of the datum it stands for -- through a `when`
		// desugaring, an anonymous lambda and a syntax-rules expansion.
		Path program = this.tempDir.resolve("app.scm");
		Files.writeString(program, """
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

				(run)
				""");
		String[] result = runReporting(program.toString());
		assertThat(result[0]).isEqualTo("1");
		assertThat(result[1]).isEqualTo("1");
		assertThat(result[2].lines()).containsExactly("Unhandled condition: not a number",
				"  at " + program + ":8 in parse");
	}

	@Test
	void anUncaughtConditionInALoadedFileNamesThatFile() throws Exception {
		// A macro that signals while expanding at evaluation time: the form in the
		// LOADED file, inside the macro -- not F, whose body holds the call site. The
		// message itself stays bare
		// (RontoLispCliTest#theInterpreterKeepsItsBareErrorText).
		Files.writeString(this.tempDir.resolve("lib.lisp"), """
				(defmacro twice (x)
				  (error "twice: bad argument ~a" x))

				(defun f (n)
				  (twice n))
				""");
		Path main = this.tempDir.resolve("main.lisp");
		Files.writeString(main, "(load \"lib.lisp\")\n(print (f 1))\n");
		String[] result = runReporting(main.toString());
		List<String> lines = result[2].lines().toList();
		assertThat(lines).hasSize(2);
		assertThat(lines.get(0)).isEqualTo("Unhandled condition: twice: bad argument N");
		assertThat(lines.get(1)).startsWith("  at ").endsWith("lib.lisp:2 in TWICE");
	}

	@Test
	void anAsyncBodysConditionNamesTheAsyncFunctionAndEveryAwaitSite() throws Exception {
		// The body runs on its own virtual thread and await rethrows on another, so the
		// awaiter's frames are not the async function's: each boundary is its own line,
		// naming the async function and the await that rethrew.
		Path program = this.tempDir.resolve("prices.lisp");
		Files.writeString(program, """
				(rontolisp:async-defun current-price (symbol)
				  (let ((feed nil))
				    (error "no price for ~a" symbol)))

				(rontolisp:async-defun portfolio ()
				  (list
				    (rontolisp:await (current-price "ABC"))))

				(print (rontolisp:await (portfolio)))
				""");
		String[] result = runReporting(program.toString());
		assertThat(result[0]).isEqualTo("1");
		assertThat(result[2].lines()).containsExactly("Unhandled condition: no price for ABC", "  at " + program + ":3",
				"  in CURRENT-PRICE (async), awaited at " + program + ":7",
				"  in PORTFOLIO (async), awaited at " + program + ":9");
	}

	@Test
	void aProgramWithNoFileReportsTheLineAlone() {
		// -e names no file, so there is nothing to locate against: the report is the one
		// line it always was.
		String[] result = runReporting("-e", "(defun f (x) (car x)) (f 1)");
		assertThat(result[0]).isEqualTo("1");
		assertThat(result[2].lines()).containsExactly("Unhandled condition: CAR: The value 1 is not of type LIST");
	}

	@Test
	void aSignaledReportlessInstanceNamesItsClass() {
		// (error c) over an instance whose class reports nothing: the text names the
		// class as written, not the escaped printed form of its internal tag.
		String[] result = runReporting("-e",
				"(define-condition uc-plain (error) ()) (error (identity (make-condition 'uc-plain)))");
		assertThat(result[2].lines()).containsExactly("Unhandled condition: Condition of type UC-PLAIN was signalled.");
	}

	@Test
	void aRontolispDiagnosticIsNotDressedUpAsACondition() throws Exception {
		// Only a signaled condition takes the cross-backend wording; a read error, a
		// compile failure or a bad command line is the COMPILER talking and says
		// "error:", keeping the file:line:column: prefix the frontend put on it.
		Path program = this.tempDir.resolve("unbalanced.lisp");
		Files.writeString(program, "(print (+ 1 2)\n");
		String[] result = runReporting(program.toString());
		assertThat(result[0]).isEqualTo("1");
		assertThat(result[2].trim()).startsWith("error: ").doesNotContain("Unhandled condition");
	}

	@Test
	void aSchemeTailCallThroughAProcedureValueRunsInConstantStackOnTheInterpreter() {
		// 300,000 deep on the test JVM's own thread, where the interpreter used to hold
		// a few thousand: a session's definitions are variables called through funcall,
		// and a file's tail call through an argument is the same funcall. The wasm twin
		// is WasmLispCompilerIntegrationTest's constant-stack case; the JVM output stays
		// bounded (.kb/interpreter-tail-calls.md).
		String program = "(define (g self n) (if (= n 0) 'done (self self (- n 1))))";
		assertThat(runCli(program + "\n(g g 300000)\n", "--source-language", "scheme")).isEqualTo("done\n");
		String[] file = runReporting("-e", program + " (display (g g 300000)) (newline)", "--source-language",
				"scheme");
		assertThat(file[2]).isEmpty();
		assertThat(file[1]).isEqualTo("done\n");
	}

	@Test
	void aPipedSchemeReplReadSeesWhatWasTypedNext() {
		// (read) consumes the session's own stdin: the next datum typed is the answer
		// (a (driver-loop) typed at the prompt takes over). Lines and run-time reads
		// share one BufferedReader, so no second look-ahead hides what was typed.
		String[] session = runSession(false, "(display (read)) (newline)\n42\n", "--source-language", "scheme");
		assertThat(session[0]).isEqualTo("0");
		assertThat(session[1]).isEqualTo("42\n\n");
		assertThat(session[2]).isEmpty();
	}

	@Test
	void aTerminalReplPromptsOncePerFreshForm() {
		// The prompt is the current package, as in any CL REPL: an (in-package ...)
		// typed at one prompt shows at the next, so which package a bare symbol
		// interns into is never invisible.
		// defpackage answers the package (its keyword, what find-package answers).
		assertThat(runSession(true, "(defpackage :app (:use :cl))\n(in-package :app)\n(+ 1 2)\n")[1])
			.isEqualTo("CL-USER> :APP\nCL-USER> :APP\nAPP> 3\nAPP> ");
		// A form typed over two lines is answered at one prompt.
		assertThat(runSession(true, "(+ 1\n 2)\n(define x 1)\n", "--source-language", "scheme")[1])
			.isEqualTo("scheme> 3\nscheme> scheme> ");
	}

	@Test
	void aPipedReplReportsFailuresOnStandardErrorAndEndsNonZero() {
		for (String[] session : List.of(runSession(false, "(car 1)\n(+ 1 2)\n"),
				runSession(false, "(car 1)\n(+ 1 2)\n", "--source-language", "scheme"))) {
			assertThat(session[0]).isEqualTo("1");
			assertThat(session[1]).isEqualTo("3\n");
			assertThat(session[2]).startsWith("Error: CAR: ").endsWith("\n").hasLineCount(1);
		}
		assertThat(runSession(false, "(+ 1 2)\n")[0]).isEqualTo("0");
		// On a terminal the report stays between the prompts, and the status is 0: a
		// person saw it.
		String[] terminal = runSession(true, "(car 1)\n(+ 1 2)\n", "--source-language", "scheme");
		assertThat(terminal[0]).isEqualTo("0");
		assertThat(terminal[1]).startsWith("scheme> Error: CAR: ").endsWith("\nscheme> 3\nscheme> ");
		assertThat(terminal[2]).isEmpty();
	}

	@Test
	void exitEndsTheSessionWithItsStatusInEitherLanguage() {
		String[] scheme = runSession(false, "(display \"a\")\n(exit 3)\n(display \"never\")\n", "--source-language",
				"scheme");
		assertThat(scheme[0]).isEqualTo("3");
		assertThat(scheme[1]).isEqualTo("a\n");
		assertThat(runSession(false, "(exit)\n", "--source-language", "scheme")[0]).isEqualTo("0");
		assertThat(runSession(false, "(exit #t)\n", "--source-language", "scheme")[0]).isEqualTo("0");
		assertThat(runSession(false, "(exit #f)\n", "--source-language", "scheme")[0]).isEqualTo("1");
		assertThat(runSession(false, "(emergency-exit 300)\n", "--source-language", "scheme")[0]).isEqualTo("44");
		// The status an exit asks for wins over a failure reported before it.
		assertThat(runSession(false, "(car 1)\n(exit 0)\n", "--source-language", "scheme")[0]).isEqualTo("0");
		// It used to be reported as "Error: null" and the session went on.
		String[] lisp = runSession(false, "(+ 1 2)\n(uiop:quit 4)\n5\n");
		assertThat(lisp[0]).isEqualTo("4");
		assertThat(lisp[1]).isEqualTo("3\n");
		assertThat(lisp[2]).isEmpty();
	}

	@Test
	void anExitInASchemeFileEndsTheProcessWithItsStatus() throws Exception {
		Path program = this.tempDir.resolve("exits.scm");
		Files.writeString(program, """
				(import (scheme base) (scheme write) (scheme process-context))
				(display "before") (newline)
				(dynamic-wind (lambda () #t) (lambda () (exit 7)) (lambda () (display "after")))
				(display "never")
				""");
		String[] result = runReporting(program.toString());
		assertThat(result[0]).isEqualTo("7");
		assertThat(result[1]).isEqualTo("before\nafter");
		assertThat(result[2]).isEmpty();
	}

	@Test
	void theSchemeReplReportsANonProcedureAsAFileDoes() throws IOException {
		// One text for one condition: the REPL, file mode and every compiled backend
		// report what the condition says (.kb/error-handling.md), so a Scheme session
		// does not reword it.
		String[] session = runSession(false, """
				(define (get key) #f)
				((get 'op) 2 3)
				(define h 3)
				(h 1)
				""", "--source-language", "scheme");
		assertThat(session[2]).isEqualTo("""
				Error: The object is not applicable: #f
				Error: The object is not applicable: 3
				""");
		Path program = this.tempDir.resolve("apply.scm");
		Files.writeString(program, "(define h 3)\n(display \"before\")\n(h 1)\n");
		String[] file = runReporting(program.toString());
		assertThat(file[0]).isEqualTo("1");
		assertThat(file[1]).isEqualTo("before");
		assertThat(file[2].lines()).containsExactly("Unhandled condition: The object is not applicable: 3",
				"  at " + program + ":3");
	}

	@Test
	void theSchemeReplCatchesAndReportsRaisedObjects() throws IOException {
		// An error object caught at one prompt is a value at the next; an uncaught raise
		// reports the object as write spells it, an uncaught error its message and
		// irritants -- the file-mode texts after "Unhandled condition: ".
		String[] session = runSession(false, """
				(define e1 (guard (e (#t e)) (error "bad" 1 "two")))
				(error-object-message e1)
				(error-object-irritants e1)
				(with-exception-handler (lambda (e) 10) (lambda () (+ 1 (raise-continuable 'c))))
				(raise (list 1 "a"))
				(error "msg" 'x)
				""", "--source-language", "scheme");
		assertThat(session[1]).isEqualTo("\"bad\"\n(1 \"two\")\n11\n");
		assertThat(session[2]).isEqualTo("""
				Error: (1 "a")
				Error: msg x
				""");
	}

	@Test
	void aMalformedLineIsReportedWholeWithoutEvaluatingItsCompletePrefix() {
		// Pinned, not designed: the buffer is read before anything in it runs.
		for (String[] session : List.of(runSession(false, "(print 5) garbage)\n(+ 1 2)\n"),
				runSession(false, "(display 5) garbage)\n(+ 1 2)\n", "--source-language", "scheme"))) {
			assertThat(session[1]).isEqualTo("3\n");
			assertThat(session[2]).startsWith("Error: ");
		}
	}

	@Test
	void theSchemeReplReportsAnErrorWithoutAFilePrefixAndGoesOn() {
		String[] session = runSession(false, "(car)\n(if)\n)\n(+ 1 2)\n", "--source-language", "scheme");
		assertThat(session[1]).isEqualTo("3\n");
		assertThat(session[2]).contains("Error: ").doesNotContain("null:");
	}

	@Test
	void aStackOverflowAtTheReplIsReportedAndTheSessionKeepsItsDefinitions() {
		// Each level binds a special, so the deepest frames' restores run with no stack
		// left: whatever they could not undo must not survive into the next prompt.
		String[] lisp = runSession(true, """
				(defvar *level* 0)
				(defun sink (n) (let ((*level* n)) (+ 1 (sink (+ n 1)))))
				(sink 1)
				*level*
				(+ 1 2)
				""");
		assertThat(lisp[1]).contains("Error: stack overflow").endsWith("CL-USER> 0\nCL-USER> 3\nCL-USER> ");
		String[] scheme = runSession(false, """
				(define (sink n) (+ 1 (sink n)))
				(define kept 42)
				(sink 0)
				kept
				""", "--source-language", "scheme");
		assertThat(scheme[1]).isEqualTo("42\n");
		assertThat(scheme[2]).startsWith("Error: stack overflow");
	}

	@Test
	void aCyclicValueIsEchoedWithoutKillingTheSession() {
		assertThat(runCli("(define l (list 1 2))\n(set-cdr! (cdr l) l)\nl\n(+ 1 2)\n", "--source-language", "scheme"))
			.isEqualTo("#0=(1 2 . #0#)\n3\n");
		// A print-object method routes the Common Lisp echo through prin1-to-string.
		String[] lisp = runSession(false, """
				(defclass pt () ())
				(defmethod print-object ((p pt) s) (format s "PT"))
				(defparameter *l* (list 1 2))
				(progn (setf (cdr (cdr *l*)) *l*) nil)
				*l*
				(+ 1 2)
				""");
		assertThat(lisp[2]).isEmpty();
		assertThat(lisp[1]).endsWith("\n3\n");
	}

	@Test
	void anUncaughtStackOverflowInAFileIsOneLineAndExitOne() {
		String[] result = runReporting("-e", "(defun sink (n) (+ 1 (sink n)))\n(print (sink 0))\n");
		assertThat(result[0]).isEqualTo("1");
		assertThat(result[2].trim()).isEqualTo("error: stack overflow (--stack <MiB> raises the limit)");
	}

	@Test
	void anUnknownSourceLanguageFailsFast() throws Exception {
		Path program = this.tempDir.resolve("hello.lisp");
		Files.writeString(program, "(print (+ 1 2))\n");
		String[] result = runReporting(program.toString(), "--source-language=elvish");
		assertThat(result[0]).isEqualTo("1");
		assertThat(result[2]).contains("--source-language");
	}

	@Test
	void schemeStandardR7rsIsStrictOnAFileOnEveryPath() throws Exception {
		Path program = this.tempDir.resolve("sicp.scm");
		Files.writeString(program, "; no import\n(display (1+ 1))\n");
		assertThat(runReporting(program.toString())[1]).isEqualTo("2");
		String expected = "error: " + program + ":2:1: an R7RS program begins with an import declaration";
		assertThat(runReporting(program.toString(), "--scheme-standard", "r7rs")[2].trim()).isEqualTo(expected);
		assertThat(runReporting(program.toString(), "--scheme-standard=r7rs", "-o",
				this.tempDir.resolve("Sicp.class").toString())[2]
			.trim()).isEqualTo(expected);
		Path strict = this.tempDir.resolve("strict.scm");
		Files.writeString(strict, "(import (scheme base) (scheme write))\n(display (square 3))\n");
		assertThat(runCli("", strict.toString(), "--scheme-standard", "r7rs")).isEqualTo("9");
	}

	@Test
	void schemeStandardR7rsReachesAFileLoadedAtRunTimeAndInlined() throws Exception {
		Files.writeString(this.tempDir.resolve("lib.scm"), "(define (twice x) (* 2 x))\n");
		Path program = this.tempDir.resolve("main.lisp");
		Files.writeString(program, "(load \"lib.scm\")\n(print (|twice| 21))\n");
		String expected = "error: " + this.tempDir.resolve("lib.scm")
				+ ":1:1: an R7RS program begins with an import declaration";
		assertThat(runReporting(program.toString(), "--scheme-standard", "r7rs")[2].trim()).isEqualTo(expected);
		assertThat(runReporting(program.toString(), "--scheme-standard", "r7rs", "-o",
				this.tempDir.resolve("Mixed.class").toString())[2]
			.trim()).isEqualTo(expected);
	}

	@Test
	void schemeStandardR7rsStartsTheReplWithTheR7rsLibrariesOnly() {
		String[] session = runSession(false, "(square 3)\n(1+ 1)\n(exact->inexact 1)\n", "--source-language", "scheme",
				"--scheme-standard", "r7rs");
		assertThat(session[1]).isEqualTo("9\n");
		assertThat(session[2]).contains("s%1+").contains("exact->inexact");
		assertThat(runCli("(1+ 1)\n", "--source-language", "scheme")).isEqualTo("2\n");
	}

	@Test
	void anUnknownSchemeStandardFailsFastByName() throws Exception {
		Path program = this.tempDir.resolve("hello.scm");
		Files.writeString(program, "(display 1)\n");
		String[] result = runReporting(program.toString(), "--scheme-standard=r6rs");
		assertThat(result[0]).isEqualTo("1");
		assertThat(result[1]).isEmpty();
		assertThat(result[2]).contains("--scheme-standard 'r6rs' names no standard");
	}

	@Test
	void aSchemeSyntaxErrorNamesItsPositionOnEveryPath() throws Exception {
		Path program = this.tempDir.resolve("broken.scm");
		Files.writeString(program, "(define (f x)\n  (if))\n");
		String expected = "error: " + program + ":2:3: malformed if";
		assertThat(runReporting(program.toString())[2].trim()).isEqualTo(expected);
		assertThat(runReporting(program.toString(), "-o", this.tempDir.resolve("Broken.class").toString())[2].trim())
			.isEqualTo(expected);
	}

	@Test
	void aSchemeProgramIsRefusedByTheScalarBackend() throws Exception {
		Path program = this.tempDir.resolve("scalar.scm");
		Files.writeString(program, "(define (f x) (* x 2))\n");
		String[] result = runReporting(program.toString(), "-o", this.tempDir.resolve("scalar.wasm").toString(),
				"--no-gc");
		assertThat(result[0]).isEqualTo("1");
		assertThat(result[2].trim()).isEqualTo("error: Cannot compile: a Scheme program needs the GC backend --"
				+ " --no-gc has no cons cell, no symbol and no closure (drop --no-gc)");
	}

	@Test
	void anUncaughtSchemeErrorReportsItsMessageAndIrritants() throws Exception {
		// The message is built under a rebound *standard-output*, inside a helper the
		// interpreter loads lazily: loaded without registering its special bindings,
		// the text went to stdout and the report named the exception class.
		Path program = this.tempDir.resolve("fails.scm");
		Files.writeString(program, "(define (f) (error \"bad thing:\" 'sym \"str\" 42 '(1 #f)))\n(f)\n");
		String[] result = runReporting(program.toString());
		assertThat(result[0]).isEqualTo("1");
		assertThat(result[1]).isEmpty();
		assertThat(result[2].lines()).containsExactly("Unhandled condition: bad thing: sym \"str\" 42 (1 #f)",
				"  at " + program + ":1 in f");
	}

	@Test
	void aSchemeTranscendentalWithAComplexAnswerIsRefusedByName() throws Exception {
		// Common Lisp answers #C(0.0 2.0); this front end has no complex numbers.
		for (String call : List.of("(sqrt -4)", "(log -1.5)", "(asin 2)", "(acos -1.5)", "(log 8 -2)")) {
			Path program = this.tempDir.resolve("complex.scm");
			Files.writeString(program, "(display " + call + ")\n");
			String[] result = runReporting(program.toString());
			assertThat(result[0]).as(call).isEqualTo("1");
			assertThat(result[1]).as(call).isEmpty();
			assertThat(result[2].trim()).as(call)
				.startsWith("Unhandled condition: " + call.substring(1, call.indexOf(' ')) + ": ")
				.contains("complex numbers are not supported: ");
		}
	}

	@Test
	void anErrorInsideSchemeEvalIsReportedInSchemeTerms() throws Exception {
		// The evaluator behind eval spells its own messages through the Scheme printer,
		// on the interpreter and the compiled backends alike (scheme-spec.yaml pins the
		// values; the messages end the program, so they are pinned here).
		for (String[] program : List.of(
				new String[] { "(eval 'nothing (interaction-environment))", "Unbound variable: nothing" },
				new String[] { "(eval '(if) (interaction-environment))", "Ill-formed special form: (if)" },
				new String[] { "(eval '(define-record-type p (mk) p?) (interaction-environment))",
						"Not supported inside eval: (define-record-type p (mk) p?)" },
				new String[] { "(eval 1 2)", "eval: not an environment: 2" },
				new String[] { "(eval '((lambda (a b) a) 1) (interaction-environment))",
						"Wrong number of arguments: (a b) given (1)" },
				new String[] { "(eval '(3 4) (interaction-environment))", "The object is not applicable: 3" },
				new String[] { "(environment '(scheme time))", "environment: library is not available: (scheme time)" },
				new String[] { "(eval 'if (interaction-environment))",
						"Syntactic keyword may not be used as an expression: if" })) {
			Path file = this.tempDir.resolve("eval-error.scm");
			Files.writeString(file, program[0] + "\n");
			String[] result = runReporting(file.toString());
			assertThat(result[0]).as(program[0]).isEqualTo("1");
			assertThat(result[1]).as(program[0]).isEmpty();
			assertThat(result[2].lines().findFirst()).as(program[0]).contains("Unhandled condition: " + program[1]);
		}
	}

	@Test
	void aTestCommandLineThatCannotNameATargetExitsTwo() {
		// 2, not 1: "the command was wrong" has to stay distinct from "a test failed",
		// which is the whole point of the subcommand's exit code (as with `format`).
		for (String[] args : List.of(new String[] { "test" }, new String[] { "test", "nope.lisp" },
				new String[] { "test", "-r", "not a style", "x.lisp" })) {
			String[] result = runReporting(args);
			assertThat(result[0]).as("%s", String.join(" ", args)).isEqualTo("2");
			assertThat(result[2]).as("%s", String.join(" ", args)).startsWith("rontolisp: test: ");
		}
	}

	@Test
	void aCallToAnUndefinedFunctionWarnsAtItsCallSite() throws Exception {
		Files.writeString(this.tempDir.resolve("lib.lisp"), """
				(defun broken (y)
				  (no-such-function y))
				""");
		Path main = this.tempDir.resolve("main.lisp");
		Files.writeString(main, "(load \"lib.lisp\")\n(print (broken 1))\n");
		PrintStream savedErr = System.err;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		try {
			System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
			runCli("", main.toString(), "-o", this.tempDir.resolve("Main.class").toString());
		}
		finally {
			System.setErr(savedErr);
		}
		assertThat(captured.toString(StandardCharsets.UTF_8))
			.contains("lib.lisp:2:3: warning: the function NO-SUCH-FUNCTION is undefined");
	}

	@Test
	void anUndefinedFunctionWarnsExactlyOncePerCallSite() throws Exception {
		// The JVM backend re-runs the whole compile when a runtime-helper gate was
		// under-predicted, and the discarded attempt used to have printed its warnings
		// already -- two identical lines for one call site. (An undefined function pulls
		// in the eval group, so this program takes the retry.)
		Path file = this.tempDir.resolve("warn.lisp");
		Files.writeString(file, """
				(defun broken (y)
				  (no-such-function y))
				(print (broken 1))
				""");
		PrintStream savedErr = System.err;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		try {
			System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
			runCli("", file.toString(), "-o", this.tempDir.resolve("Warn.class").toString());
		}
		finally {
			System.setErr(savedErr);
		}
		assertThat(captured.toString(StandardCharsets.UTF_8)
			.lines()
			.filter(line -> line.contains("the function NO-SUCH-FUNCTION is undefined"))
			.count()).isEqualTo(1);
	}

	@Test
	void mainRunsTheProgramOnItsOwnStackNotTheLaunchersOne() throws Exception {
		// So the depth ceiling is one number on every platform and every launcher: main
		// is called here on a thread with exactly what linux-x64 gives thread 0, and runs
		// a program that the same thread cannot run through run.
		//
		// Each round pairs the two at one depth, main FIRST: the control then runs on a
		// JIT at least as warm as main's, so its overflow shows the depth needs more than
		// the launcher's stack even with the frames main may have had. A control that
		// fits does not fail the test -- it doubles the depth and pairs again.
		for (int depth = DEEP_PROGRAM_FIRST_DEPTH; depth <= DEEP_PROGRAM_MAX_DEPTH; depth *= 2) {
			final int d = depth;
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
			Throwable thrown;
			try {
				thrown = onAStackOf(LAUNCHER_STACK_BYTES,
						() -> RontoLispCli.main(new String[] { "-e", deepProgram(d) }));
			}
			finally {
				System.setOut(oldOut);
			}
			assertThat(thrown).as("main at depth %d", depth).isNull();
			assertThat(out.toString(StandardCharsets.UTF_8)).contains(String.valueOf(depth));
			Throwable control = onAStackOf(LAUNCHER_STACK_BYTES, () -> {
				RontoLispCli cli = new RontoLispCli(new ByteArrayInputStream(new byte[0]),
						new PrintStream(new ByteArrayOutputStream()));
				cli.run(new String[] { "-e", deepProgram(d) });
			});
			if (control instanceof StackOverflowError) {
				return;
			}
			assertThat(control).as("run on the launcher's stack at depth %d", depth).isNull();
		}
		throw new AssertionError(
				"no depth up to " + DEEP_PROGRAM_MAX_DEPTH + " overflowed a " + LAUNCHER_STACK_BYTES + "-byte stack");
	}

}
