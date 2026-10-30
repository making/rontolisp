package am.ik.rontolisp.cli;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import am.ik.rontolisp.cli.SwankWire.Keyword;
import am.ik.rontolisp.cli.SwankWire.Symbol;
import am.ik.rontolisp.eval.CommonLispSwank;
import am.ik.rontolisp.eval.LispEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SWANK server against a client replaying what SLIME 2.32 ({@code slime-fancy}) sent
 * it, each reply checked against the one SWANK 2.32 on SBCL 2.2.9 answers the same
 * request with (recorded through a logging proxy, 2026-10-30), the request id and the
 * writing thread's id left out. Where the two differ the reply is rontolisp's: no
 * presentation markers around a REPL result, a package printed as its keyword, the
 * implementation's own names ({@code CL-USER}, {@code CL:MAPC}), no SBCL compiler output
 * or unused-variable note, a macro expansion on one line, a condition printed with its
 * report, and a debugger's frames the interpreter's own: each function waiting, the
 * request's top-level form and the request.
 */
class SwankServerTest {

	@TempDir
	Path tempDir;

	@Test
	void aSlimeSessionEvaluatesCompilesAndDocumentsAnsweringWhatSwankAnswers() throws Exception {
		try (SwankServer server = start(); Client client = new Client(server.port())) {
			List<String> info = client.rex("(swank:connection-info)", "COMMON-LISP-USER", "t");
			assertThat(info.getLast()).contains(":style :spawn")
				.contains(":lisp-implementation (:type \"rontolisp\" :name \"rontolisp\"")
				.contains(":package (:name \"CL-USER\" :prompt \"CL-USER\") :version \"2.32\")) id)");
			assertThat(client.rex("(swank:swank-require '(swank-presentations swank-repl))", "COMMON-LISP-USER", "t")
				.getLast()).contains("\"SWANK-REPL\"").contains("\"SWANK-PRESENTATIONS\"");
			assertThat(client.rex("(swank-repl:create-repl nil)", "COMMON-LISP-USER", "t"))
				.containsExactly("(:return (:ok (\"CL-USER\" \"CL-USER\")) id)");
			// each value a REPL result, output before them and ended by a fresh line
			assertThat(client.repl("(+ 1 2)")).containsExactly("(:write-string \"3\n\" :repl-result)",
					"(:return (:ok nil) id)");
			assertThat(client.repl("(progn (format t \"hello~%\") (values 1 \"two\"))")).containsExactly(
					"(:write-string \"hello\n\" nil tid)", "(:write-string \"1\n\" :repl-result)",
					"(:write-string \"\\\"two\\\"\n\" :repl-result)", "(:return (:ok nil) id)");
			assertThat(client.repl("(values)")).containsExactly("(:write-string \"; No value\" :repl-result)",
					"(:return (:ok nil) id)");
			assertThat(client.repl("(princ \"no newline\")")).containsExactly(
					"(:write-string \"no newline\n\" nil tid)", "(:write-string \"\\\"no newline\\\"\n\" :repl-result)",
					"(:return (:ok nil) id)");
			client.repl("(defvar *drv* 10)");
			assertThat(client.repl("(list *drv* * ** ***)")).first()
				.isEqualTo("(:write-string \"(10 *DRV* \\\"no newline\\\" NIL)\n\" :repl-result)");
			// standard input reads what the REPL types
			client.input.add("typed\n");
			assertThat(client.repl("(read-line)")).containsExactly("(:write-string \"\\\"typed\\\"\n\" :repl-result)",
					"(:write-string \"NIL\n\" :repl-result)", "(:return (:ok nil) id)");
			// a package switch is announced, and the next request names the new package
			client.repl("(defpackage :drv-pkg (:use :cl) (:export #:hello))");
			assertThat(client.repl("(in-package :drv-pkg)")).containsExactly(
					"(:write-string \":DRV-PKG\n\" :repl-result)", "(:new-package \"DRV-PKG\" \"DRV-PKG\")",
					"(:return (:ok nil) id)");
			client.rex("(swank-repl:listener-eval \"(defun hello () 'hi)\n\")", "DRV-PKG", ":repl-thread");
			assertThat(client.rex("(swank-repl:listener-eval \"(in-package :cl-user)\n\")", "DRV-PKG", ":repl-thread"))
				.contains("(:new-package \"CL-USER\" \"CL-USER\")");
			assertThat(client.repl("(drv-pkg:hello)")).first()
				.isEqualTo("(:write-string \"DRV-PKG::HI\n\" :repl-result)");
			assertThat(client.rex("(swank:set-package \"DRV-PKG\")", "CL-USER", ":repl-thread"))
				.containsExactly("(:return (:ok (\"DRV-PKG\" \"DRV-PKG\")) id)");
			// the editor's evaluations, a buffer's in-package naming the package
			assertThat(client.rex("(swank:interactive-eval \"(+ 2 3)\" 6 80)", "CL-USER", "t"))
				.containsExactly("(:return (:ok \"=> 5 (3 bits, #x5, #o5, #b101)\") id)");
			assertThat(client.rex("(swank:interactive-eval \"(values 1 2)\" 6 80)", "CL-USER", "t"))
				.containsExactly("(:return (:ok \"=> 1, 2\") id)");
			assertThat(client.rex("(swank:interactive-eval \"(values)\" 6 80)", "CL-USER", "t"))
				.containsExactly("(:return (:ok \"\") id)");
			assertThat(client.rex("(swank:interactive-eval \"(hello)\n\" 6 80)", ":drv-pkg", "t"))
				.containsExactly("(:return (:ok \"=> HI\") id)");
			assertThat(client.rex("(swank:eval-and-grab-output \"(progn (princ \\\"x\\\") (values 42 43))\")",
					"CL-USER", "t"))
				.containsExactly("(:return (:ok (\"x\" \"42\n43\")) id)");
			// C-c C-c: a call nothing defines is a style warning at the call
			assertThat(client
				.rex("(swank:compile-string-for-emacs \"(defun drv-add (a b)\n  (+ a b))\n\""
						+ " \"drv-compile.lisp\" '((:position 1) (:line 1 1)) nil 'nil)", "CL-USER", "t")
				.getLast()).startsWith("(:return (:ok (:compilation-result nil t ");
			assertThat(client
				.rex("(swank:compile-string-for-emacs \"(defun drv-bad ()\n  (drv-undefined-fn 1))\n\""
						+ " \"drv-compile.lisp\" '((:position 34) (:line 4 1)) nil 'nil)", "CL-USER", "t")
				.getLast())
				.startsWith("(:return (:ok (:compilation-result ((:message"
						+ " \"undefined function: CL-USER::DRV-UNDEFINED-FN\" :severity :style-warning :location"
						+ " (:location (:buffer \"drv-compile.lisp\") (:offset 34 20) nil) :references nil)) t ");
			// C-c C-k: loading the file is compiling it, the notes at file positions
			Path file = this.tempDir.resolve("drv-file.lisp");
			Files.writeString(file, "(defun drv-file-fn (x)\n  (* x 2))\n\n(defun drv-file-warn ()\n"
					+ "  (let ((unused 1))\n    (drv-other-undefined)))\n");
			assertThat(client.rex("(swank:compile-file-for-emacs " + string(file.toString()) + " t)", "CL-USER", "t")
				.getLast())
				.startsWith("(:return (:ok (:compilation-result ((:message"
						+ " \"undefined function: CL-USER::DRV-OTHER-UNDEFINED\" :severity :style-warning :location"
						+ " (:location (:file " + string(file.toString()) + ") (:position 84) nil) :references nil))"
						+ " t ")
				.endsWith(" nil nil)) id)");
			assertThat(client.repl("(drv-file-fn 21)")).first().isEqualTo("(:write-string \"42\n\" :repl-result)");
			assertThat(client.rex("(swank:load-file " + string(file.toString()) + ")", "CL-USER", "t"))
				.containsExactly("(:return (:ok \"T\") id)");
			// completion, argument lists, descriptions, expansions
			assertThat(client.rex("(swank:simple-completions \"mapc\" \"COMMON-LISP-USER\")", "CL-USER", "t"))
				.containsExactly("(:return (:ok ((\"mapc\" \"-f-------\" \"CL:MAPC\") (\"mapcan\" \"-f-------\""
						+ " \"CL:MAPCAN\") (\"mapcar\" \"-f-------\" \"CL:MAPCAR\") (\"mapcon\" \"-f-------\""
						+ " \"CL:MAPCON\"))) id)");
			assertThat(client.rex("(swank:simple-completions \"drv-pkg:he\" \"COMMON-LISP-USER\")", "CL-USER", "t"))
				.containsExactly("(:return (:ok ((\"drv-pkg:hello\" \"-f-------\" \"DRV-PKG:HELLO\"))) id)");
			assertThat(client.rex("(swank:completions \"m-v-b\" \"COMMON-LISP-USER\")", "CL-USER", "t"))
				.containsExactly(
						"(:return (:ok ((\"multiple-value-bind\" \"-f---m---\" \"CL:MULTIPLE-VALUE-BIND\"))) id)");
			assertThat(client.rex(
					"(swank:fuzzy-completions \"mvbind\" \"COMMON-LISP-USER\" :limit 5" + " :time-limit-in-msec 1500)",
					"CL-USER", "t"))
				.containsExactly("(:return (:ok (((\"multiple-value-bind\" \"-f---m---\" \"CL:MULTIPLE-VALUE-BIND\""
						+ " ((0 \"m\") (9 \"v\") (15 \"bind\")))) nil)) id)");
			assertThat(client.rex("(swank:autodoc '(\"mapcar\" \"\" swank::%cursor-marker%) :print-right-margin 80)",
					"CL-USER", "t"))
				.containsExactly("(:return (:ok (\"(mapcar ===> function <=== list &rest more-lists)\" t)) id)");
			assertThat(client.rex("(swank:autodoc '(\"drv-add\" \"1\" swank::%cursor-marker%) :print-right-margin 80)",
					"CL-USER", "t"))
				.containsExactly("(:return (:ok (\"(drv-add ===> a <=== b)\" t)) id)");
			assertThat(client.rex("(swank:operator-arglist \"mapcar\" \"COMMON-LISP-USER\")", "CL-USER", "t"))
				.containsExactly("(:return (:ok \"(mapcar FUNCTION LIST &REST MORE-LISTS)\") id)");
			assertThat(client.rex("(swank:describe-symbol \"drv-add\")", "CL-USER", "t")).containsExactly(
					"(:return (:ok \"CL-USER::DRV-ADD\n  [symbol]\n\nDRV-ADD names a function:\n  Lambda-list: (A B)\n\") id)");
			assertThat(client.rex("(swank:swank-macroexpand-1 \"(when a b)\")", "CL-USER", "t"))
				.containsExactly("(:return (:ok \"(IF A B NIL)\") id)");
			assertThat(client.rex("(swank:swank-macroexpand-all \"(when a (unless b c))\")", "CL-USER", "t"))
				.containsExactly("(:return (:ok \"(IF A (IF B NIL C) NIL)\") id)");
			// a function SWANK does not define here aborts
			assertThat(client.rex("(swank:init-inspector \"1\")", "CL-USER", "t")).containsExactly(
					"(:return (:abort \"#<SIMPLE-ERROR \\\"The function SWANK:INIT-INSPECTOR is undefined.\\\">\") id)");
		}
	}

	@Test
	void anInterruptEntersTheDebuggerWhoseContinueGoesOnWhileAnotherRequestRunsInItsOwnPackage() throws Exception {
		try (SwankServer server = start(); Client client = new Client(server.port())) {
			client.repl("(defpackage :drv-loop (:use :cl))");
			// the REPL thread loops in its own package...
			long looping = client.send(
					"(swank-repl:listener-eval \"(in-package :drv-loop)\n"
							+ "(progn (princ (package-name *package*)) (finish-output) (loop))\n\")",
					"CL-USER", ":repl-thread");
			assertThat(client.output()).isEqualTo("DRV-LOOP");
			// ...while a worker reads and runs in the package its request names
			assertThat(client.rex("(swank:interactive-eval \"(package-name *package*)\" 6 80)", "CL-USER", "t"))
				.containsExactly("(:return (:ok \"=> \\\"CL-USER\\\"\") id)");
			// the interrupt stops the loop in SWANK's simple-break, whose continue goes
			// on
			String interrupted = "(:debug tid 1 (\"Interrupt from Emacs\" \"   [Condition of type SIMPLE-ERROR]\" nil)"
					+ " ((\"CONTINUE\" \"Continue from break.\") (\"RETRY\" \"Retry SLIME REPL evaluation request.\")"
					+ " (\"*ABORT\" \"Return to SLIME's top level.\")"
					+ " (\"ABORT\" \"abort thread (#<THREAD \\\"repl-thread\\\" RUNNING {tid}>)\"))"
					+ " ((0 \"(EVAL (PROGN (PRINC (PACKAGE-NAME *PACKAGE*)) (FINISH-OUTPUT) (LOOP)))\")"
					+ " (1 \"(SWANK-REPL:LISTENER-EVAL \\\"(in-package :drv-loop) ..)\")) (id))";
			client.write("(:emacs-interrupt t)");
			assertThat(client.until("debug-activate")).containsExactly(interrupted, "(:debug-activate tid 1 nil)");
			assertThat(client.sldb("(swank:sldb-continue)", "debug-return"))
				.containsExactly("(:return (:abort \"NIL\") id)", "(:debug-return tid 1 nil)");
			client.write("(:emacs-interrupt t)");
			assertThat(client.until("debug-activate")).containsExactly(interrupted, "(:debug-activate tid 1 nil)");
			// q leaves the loop for SWANK's top level: the request aborts on no condition
			assertThat(client.sldb("(swank:throw-to-toplevel)", looping)).containsExactly(
					"(:return (:abort \"NIL\") id)", "(:debug-return tid 1 nil)", "(:return (:abort \"NIL\") id)");
			// the REPL thread goes on, its package switch never announced
			assertThat(client.repl("(list (package-name *package*) (+ 40 2))")).first()
				.isEqualTo("(:write-string \"(\\\"CL-USER\\\" 42)\n\" :repl-result)");
			// a sleep the interrupt woke sleeps on after the continue
			long sleeping = client.send(
					"(swank-repl:listener-eval \"(progn (princ 'zz) (finish-output) (sleep 1) :slept)\n\")", "CL-USER",
					":repl-thread");
			assertThat(client.output()).isEqualTo("ZZ");
			client.write("(:emacs-interrupt t)");
			assertThat(client.until("debug-activate").getFirst()).contains(" (\"Interrupt from Emacs\" ")
				.contains(" ((0 \"(EVAL (PROGN (PRINC 'ZZ) (FINISH-OUTPUT) (SLEEP 1) :SLEPT))\") (1 ");
			assertThat(client.sldb("(swank:sldb-continue)", sleeping)).containsExactly("(:return (:abort \"NIL\") id)",
					"(:debug-return tid 1 nil)", "(:write-string \"\n\" nil tid)",
					"(:write-string \":SLEPT\n\" :repl-result)", "(:return (:ok nil) id)");
		}
	}

	@Test
	void anErrorEntersTheDebuggerWhoseRestartsFramesAndLevelsAnswerAsSwanksDo() throws Exception {
		try (SwankServer server = start(); Client client = new Client(server.port())) {
			// SLDB opens where the error is signalled, with SWANK's restarts and the
			// frames
			// down to the request; c with no continue restart answers nil, q leaves
			long boom = client.send("(swank-repl:listener-eval \"(error \\\"boom ~a\\\" 1)\n\")", "CL-USER",
					":repl-thread");
			assertThat(client.until("debug-activate")).containsExactly(
					"(:debug tid 1 (\"boom 1\" \"   [Condition of type SIMPLE-ERROR]\" nil) ((\"RETRY\""
							+ " \"Retry SLIME REPL evaluation request.\") (\"*ABORT\" \"Return to SLIME's top level.\")"
							+ " (\"ABORT\" \"abort thread (#<THREAD \\\"repl-thread\\\" RUNNING {tid}>)\"))"
							+ " ((0 \"(EVAL (ERROR \\\"boom ~a\\\" 1))\")"
							+ " (1 \"(SWANK-REPL:LISTENER-EVAL \\\"(error \\\\\\\"boom ~a\\\\\\\" 1) ..)\")) (id))",
					"(:debug-activate tid 1 nil)");
			assertThat(client.sldb("(swank:sldb-continue)", "return")).containsExactly("(:return (:ok nil) id)");
			assertThat(client.sldb("(swank:throw-to-toplevel)", boom)).containsExactly("(:return (:abort \"NIL\") id)",
					"(:debug-return tid 1 nil)", "(:return (:abort \"#<SIMPLE-ERROR \\\"boom 1\\\">\") id)");
			// a numbered restart: the program's own, the request answering its value
			long pick = client.send(
					"(swank-repl:listener-eval \"(restart-case (error \\\"pick ~a\\\" 2)"
							+ " (use-five () :report \\\"Return five.\\\" 5) (use-ten () 10))\n\")",
					"CL-USER", ":repl-thread");
			assertThat(client.until("debug-activate").getFirst()).contains(" (\"pick 2\" ")
				.contains(" ((\"USE-FIVE\" \"Return five.\") (\"USE-TEN\" \"USE-TEN\") (\"RETRY\" ");
			assertThat(client.sldb("(swank:invoke-nth-restart-for-emacs 1 0)", pick)).containsExactly(
					"(:return (:abort \"NIL\") id)", "(:debug-return tid 1 nil)",
					"(:write-string \"5\n\" :repl-result)", "(:return (:ok nil) id)");
			// c: cerror's continue
			long cerror = client.send(
					"(swank-repl:listener-eval"
							+ " \"(progn (cerror \\\"Keep going.\\\" \\\"Cerror ~a\\\" 3) :went-on)\n\")",
					"CL-USER", ":repl-thread");
			assertThat(client.until("debug-activate").getFirst())
				.contains(" ((\"CONTINUE\" \"Keep going.\") (\"RETRY\" ");
			assertThat(client.sldb("(swank:sldb-continue)", cerror)).containsExactly("(:return (:abort \"NIL\") id)",
					"(:debug-return tid 1 nil)", "(:write-string \":WENT-ON\n\" :repl-result)",
					"(:return (:ok nil) id)");
			// a: a worker's first abort restart, SWANK's top level
			long car = client.send("(swank:interactive-eval \"(car 1)\" 6 80)", "CL-USER", "t");
			assertThat(client.until("debug-activate")).containsExactly(
					"(:debug tid 1 (\"CAR: The value 1 is not of type LIST\" \"   [Condition of type TYPE-ERROR]\" nil)"
							+ " ((\"RETRY\" \"Retry SLIME interactive evaluation request.\")"
							+ " (\"*ABORT\" \"Return to SLIME's top level.\")"
							+ " (\"ABORT\" \"abort thread (#<THREAD \\\"worker\\\" RUNNING {tid}>)\"))"
							+ " ((0 \"(CAR 1)\") (1 \"(EVAL (CAR 1))\") (2 \"(SWANK:INTERACTIVE-EVAL \\\"(car 1)\\\" 6 80)\"))"
							+ " (id))",
					"(:debug-activate tid 1 nil)");
			assertThat(client.sldb("(swank:sldb-abort)", car)).containsExactly("(:return (:abort \"NIL\") id)",
					"(:debug-return tid 1 nil)",
					"(:return (:abort \"#<TYPE-ERROR \\\"CAR: The value 1 is not of type LIST\\\">\") id)");
			// the functions waiting are frames, each with its locals; an expression
			// evaluates in a frame
			client.repl("(defun drv-g (z) (list (error \"deep ~a\" z)))");
			client.repl("(defun drv-f (x) (let ((y (* x 2))) (list (drv-g y))))");
			long deep = client.send("(swank-repl:listener-eval \"(drv-f 21)\n\")", "CL-USER", ":repl-thread");
			assertThat(client.until("debug-activate").getFirst()).endsWith(" ((0 \"(DRV-G 42)\") (1 \"(DRV-F 21)\")"
					+ " (2 \"(EVAL (DRV-F 21))\") (3 \"(SWANK-REPL:LISTENER-EVAL \\\"(drv-f 21) ..)\")) (id))");
			assertThat(client.sldb("(swank:frame-locals-and-catch-tags 0)", "return"))
				.containsExactly("(:return (:ok (((:name \"Z\" :id 0 :value \"42\")) nil)) id)");
			assertThat(client.sldb("(swank:frame-locals-and-catch-tags 1)", "return")).containsExactly(
					"(:return (:ok (((:name \"X\" :id 0 :value \"21\") (:name \"Y\" :id 0 :value \"42\")) nil)) id)");
			assertThat(client.sldb("(swank:frame-package-name 1)", "return"))
				.containsExactly("(:return (:ok \"CL-USER\") id)");
			assertThat(client.sldb("(swank:eval-string-in-frame \"(* x 2)\" 1 \"COMMON-LISP-USER\" 6 80)", "return"))
				.containsExactly("(:return (:ok \"=> 42 (6 bits, #x2A, #o52, #b101010)\") id)");
			assertThat(client.sldb("(swank:backtrace 1 3)", "return"))
				.containsExactly("(:return (:ok ((1 \"(DRV-F 21)\") (2 \"(EVAL (DRV-F 21))\"))) id)");
			assertThat(client.sldb("(swank:throw-to-toplevel)", deep)).containsExactly("(:return (:abort \"NIL\") id)",
					"(:debug-return tid 1 nil)", "(:return (:abort \"#<SIMPLE-ERROR \\\"deep 42\\\">\") id)");
			// an error in a level opens the next, whose restarts return to the one below:
			// that level shows itself again, twice as SWANK does
			long outer = client.send("(swank-repl:listener-eval \"(error \\\"outer\\\")\n\")", "CL-USER",
					":repl-thread");
			String level1 = client.until("debug-activate").getFirst();
			long inner = client.send("(swank-repl:listener-eval \"(error \\\"inner\\\")\n\")", "CL-USER",
					":repl-thread");
			assertThat(client.until("debug-activate"))
				.containsExactly("(:debug tid 2 (\"inner\" \"   [Condition of type SIMPLE-ERROR]\" nil) ((\"RETRY\""
						+ " \"Retry SLIME REPL evaluation request.\") (\"ABORT\" \"Return to sldb level 1.\") (\"RETRY\""
						+ " \"Retry SLIME REPL evaluation request.\") (\"*ABORT\" \"Return to SLIME's top level.\")"
						+ " (\"ABORT\" \"abort thread (#<THREAD \\\"repl-thread\\\" RUNNING {tid}>)\"))"
						+ " ((0 \"(EVAL (ERROR \\\"inner\\\"))\")"
						+ " (1 \"(SWANK-REPL:LISTENER-EVAL \\\"(error \\\\\\\"inner\\\\\\\") ..)\")"
						+ " (2 \"(SWANK::SLDB-LOOP 1)\") (3 \"(EVAL (ERROR \\\"outer\\\"))\")"
						+ " (4 \"(SWANK-REPL:LISTENER-EVAL \\\"(error \\\\\\\"outer\\\\\\\") ..)\")) (id id))",
						"(:debug-activate tid 2 nil)");
			assertThat(client.sldb("(swank:invoke-nth-restart-for-emacs 2 1)", inner)).containsExactly(
					"(:return (:abort \"NIL\") id)", "(:debug-return tid 2 nil)",
					"(:return (:abort \"#<SIMPLE-ERROR \\\"inner\\\">\") id)");
			assertThat(client.until("debug-activate")).containsExactly(level1, "(:debug-activate tid 1 nil)");
			assertThat(client.until("debug-activate")).containsExactly(level1, "(:debug-activate tid 1 nil)");
			// the request aborts on the last condition it entered a debugger for
			assertThat(client.sldb("(swank:throw-to-toplevel)", outer)).containsExactly("(:return (:abort \"NIL\") id)",
					"(:debug-return tid 1 nil)", "(:return (:abort \"#<SIMPLE-ERROR \\\"inner\\\">\") id)");
			assertThat(client.repl("(+ 40 2)")).containsExactly("(:write-string \"42\n\" :repl-result)",
					"(:return (:ok nil) id)");
		}
	}

	private static SwankServer start() throws IOException {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(OutputStream.nullOutputStream()),
				InputStream.nullInputStream());
		SwankServer server = new SwankServer(new CommonLispSwank(evaluator), evaluator,
				InetAddress.getLoopbackAddress(), 0, SizedThread.WORKER_STACK_BYTES);
		Thread.ofVirtual().start(() -> {
			try {
				server.serve();
			}
			catch (IOException ex) {
				// the test closed it
			}
		});
		return server;
	}

	private static String string(String text) {
		return SwankWire.print(text);
	}

	/**
	 * A client sending one request at a time, as SLIME does: it answers each output with
	 * {@code :write-done} and each read with the next queued input line.
	 */
	private static final class Client implements AutoCloseable {

		final Deque<String> input = new ArrayDeque<>();

		private final Socket socket;

		private final InputStream in;

		private final OutputStream out;

		private long ids;

		/** The thread the last {@code :debug} named. */
		private long debugged;

		Client(int port) throws IOException {
			this.socket = new Socket(InetAddress.getLoopbackAddress(), port);
			// a server that answers nothing fails the test rather than hanging it
			this.socket.setSoTimeout(120_000);
			this.in = new BufferedInputStream(this.socket.getInputStream());
			this.out = new BufferedOutputStream(this.socket.getOutputStream());
		}

		/** Reads up to the next output, answering it, and answers its text. */
		String output() throws IOException {
			while (true) {
				List<?> message = (List<?>) Objects.requireNonNull(SwankWire.read(this.in));
				if (((Keyword) message.getFirst()).name().equals("write-string") && message.size() > 3) {
					write("(:write-done " + message.get(3) + ")");
					return (String) message.get(1);
				}
			}
		}

		void write(String message) throws IOException {
			byte[] payload = message.getBytes(StandardCharsets.UTF_8);
			this.out.write(String.format("%06x", payload.length).getBytes(StandardCharsets.US_ASCII));
			this.out.write(payload);
			this.out.flush();
		}

		/** Sends an {@code :emacs-rex}, answering its id. */
		long send(String form, String pkg, String thread) throws IOException {
			long id = ++this.ids;
			write("(:emacs-rex " + form + " " + string(pkg) + " " + thread + " " + id + ")");
			return id;
		}

		/** Sends a request and answers its messages up to its {@code :return}. */
		List<String> rex(String form, String pkg, String thread) throws IOException {
			return replies(send(form, pkg, thread));
		}

		/** A REPL input, as {@code slime-repl-return} sends it. */
		List<String> repl(String input) throws IOException {
			return rex("(swank-repl:listener-eval " + string(input + "\n") + ")", "CL-USER", ":repl-thread");
		}

		/**
		 * The messages up to the {@code :return} of a request, printed as {@link #show}
		 * prints them. Output that arrived in several messages in a row is shown as one:
		 * where a flush falls depends on time.
		 */
		List<String> replies(long id) throws IOException {
			return readUntil(
					message -> kind(message).equals("return") && ((Number) message.getLast()).longValue() == id);
		}

		/** The messages up to the first of a kind, as {@link #replies} reads them. */
		List<String> until(String kind) throws IOException {
			return readUntil(message -> kind(message).equals(kind));
		}

		/**
		 * Sends a request to the thread the last {@code :debug} named, as SLDB does, and
		 * answers the messages up to the first of a kind.
		 */
		List<String> sldb(String form, String kind) throws IOException {
			send(form, "CL-USER", Long.toString(this.debugged));
			return until(kind);
		}

		/**
		 * Sends a request to the thread the last {@code :debug} named and answers the
		 * messages up to the {@code :return} of another request.
		 */
		List<String> sldb(String form, long id) throws IOException {
			send(form, "CL-USER", Long.toString(this.debugged));
			return replies(id);
		}

		private List<String> readUntil(Predicate<List<?>> last) throws IOException {
			List<String> messages = new ArrayList<>();
			String output = null;
			while (true) {
				List<?> message = (List<?>) Objects.requireNonNull(SwankWire.read(this.in));
				String kind = kind(message);
				if (kind.equals("write-string") && message.size() > 3) {
					write("(:write-done " + message.get(3) + ")");
					output = (output == null ? "" : output) + message.get(1);
					continue;
				}
				if (output != null) {
					messages.add(SwankWire
						.print(List.of(new Keyword("write-string"), output, List.of(), new Symbol("tid"))));
					output = null;
				}
				if (kind.equals("read-string")) {
					write("(:emacs-return-string " + message.get(1) + " " + message.get(2) + " "
							+ string(this.input.removeFirst()) + ")");
					continue;
				}
				if (kind.equals("debug")) {
					this.debugged = ((Number) message.get(1)).longValue();
				}
				messages.add(show(message));
				if (last.test(message)) {
					return messages;
				}
			}
		}

		private static String kind(List<?> message) {
			return ((Keyword) message.getFirst()).name();
		}

		/**
		 * A message as the test reads it: a request's id printed {@code id}, a thread's
		 * {@code tid}, in a reply and a debugger level's messages, and in a thread's
		 * printed name.
		 */
		private static String show(List<?> message) {
			List<Object> shown = new ArrayList<>(message);
			switch (kind(message)) {
				case "return" -> shown.set(shown.size() - 1, new Symbol("id"));
				case "debug" -> {
					shown.set(1, new Symbol("tid"));
					shown.set(shown.size() - 1,
							((List<?>) message.getLast()).stream().map(each -> new Symbol("id")).toList());
				}
				case "debug-activate", "debug-return" -> shown.set(1, new Symbol("tid"));
				default -> {
					// as it came
				}
			}
			return SwankWire.print(shown).replaceAll("\\{[0-9A-F]+}", "{tid}");
		}

		@Override
		public void close() throws IOException {
			this.socket.close();
		}

	}

}
