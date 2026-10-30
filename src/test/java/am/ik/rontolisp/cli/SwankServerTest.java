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
 * or unused-variable note, a macro expansion on one line, and an error or an interrupt
 * aborting the request instead of entering the debugger.
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
			// no debugger: an error, or a function SWANK does not define here, aborts
			assertThat(client.repl("(error \"boom ~a\" 1)"))
				.containsExactly("(:return (:abort \"#<SIMPLE-ERROR \\\"boom 1\\\">\") id)");
			assertThat(client.rex("(swank:init-inspector \"1\")", "CL-USER", "t")).containsExactly(
					"(:return (:abort \"#<SIMPLE-ERROR \\\"The function SWANK:INIT-INSPECTOR is undefined.\\\">\") id)");
		}
	}

	@Test
	void anInterruptAbortsTheRunningRequestWhileAnotherRunsInItsOwnPackage() throws Exception {
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
			client.write("(:emacs-interrupt t)");
			assertThat(client.replies(looping))
				.containsExactly("(:return (:abort \"#<SIMPLE-ERROR \\\"Interrupt from Emacs\\\">\") id)");
			// the REPL thread goes on, its package switch never announced
			assertThat(client.repl("(list (package-name *package*) (+ 40 2))")).first()
				.isEqualTo("(:write-string \"(\\\"CL-USER\\\" 42)\n\" :repl-result)");
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
		 * The messages up to the {@code :return} of a request, printed with the request's
		 * id as {@code id} and a writer's thread id as {@code tid}. Output that arrived
		 * in several messages in a row is shown as one: where a flush falls depends on
		 * time.
		 */
		List<String> replies(long id) throws IOException {
			List<String> messages = new ArrayList<>();
			String output = null;
			while (true) {
				List<?> message = (List<?>) Objects.requireNonNull(SwankWire.read(this.in));
				String kind = ((Keyword) message.getFirst()).name();
				List<Object> shown = new ArrayList<>(message);
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
				boolean returned = kind.equals("return") && ((Number) message.getLast()).longValue() == id;
				if (returned) {
					shown.set(shown.size() - 1, new Symbol("id"));
				}
				messages.add(SwankWire.print(shown));
				if (returned) {
					return messages;
				}
			}
		}

		@Override
		public void close() throws IOException {
			this.socket.close();
		}

	}

}
