package am.ik.rontolisp.cli;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import am.ik.rontolisp.cli.SwankWire.Keyword;
import am.ik.rontolisp.cli.SwankWire.Symbol;
import am.ik.rontolisp.eval.CommonLispSwank;
import am.ik.rontolisp.eval.LispEvaluator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
			// M-.: where the editor compiled a definition, else where a load read it
			Path nav = this.tempDir.resolve("drv-nav.lisp");
			Files.writeString(nav,
					"(defvar *drv-var* 1)\n\n(defmacro drv-mac (x)\n  `(list ,x *drv-var*))\n\n"
							+ "(defun drv-callee (a)\n  \"Callee doc.\"\n  (* a 2))\n\n(defun drv-caller (b)\n"
							+ "  (let ((*drv-var* 2))\n    (setq *drv-var* 3)\n    (drv-mac (drv-callee b))))\n\n"
							+ "(defgeneric drv-gen (o))\n\n"
							+ "(defclass drv-point () ((x :initarg :x :accessor drv-point-x) (y :initform 5)))\n\n"
							+ "(defmethod drv-gen ((o drv-point)) (drv-callee 1))\n\n(defstruct drv-st a b)\n");
			String navFile = string(nav.toString());
			client.rex("(swank:compile-file-for-emacs " + navFile + " t)", "CL-USER", "t");
			Path nav2 = this.tempDir.resolve("drv-nav2.lisp");
			client.rex("(swank:compile-string-for-emacs \"(defun drv-buf-fn ()\n  (drv-callee 7))\n\" \"drv-nav2.lisp\""
					+ " '((:position 2) (:line 2 1)) " + string(nav2.toString()) + " 'nil)", "CL-USER", "t");
			Path loaded = this.tempDir.resolve("drv-load.lisp");
			Files.writeString(loaded,
					"(defvar *unused*)\n\n#+(or) (defun drv-never () 0)\n  (defun drv-loaded () (drv-callee 1))\n");
			client.rex("(swank:load-file " + string(loaded.toString()) + ")", "CL-USER", "t");
			client.repl("(defun drv-repl-fn () (drv-callee 3))");
			assertThat(client.rex("(swank:find-definitions-for-emacs \"drv-callee\")", "CL-USER", "t"))
				.containsExactly("(:return (:ok ((\"(DEFUN DRV-CALLEE)\" (:location (:file " + navFile
						+ ") (:position 70) (:snippet \"(defun drv-callee (a)\n  \\\"Callee doc.\\\"\n  (* a 2))\n\n"
						+ "(defun drv-caller (b)\n  (let ((*drv-var* 2))\n    (setq *drv-var* 3)\n"
						+ "    (drv-mac (drv-callee b))))\n\n(defgeneric drv-gen (o))\n\n(defclass drv-point () ((x"
						+ " :initarg :x :accessor drv-point-x) (y :initform 5)))\n\"))))) id)");
			assertThat(definitions(client, "drv-gen")).containsExactly("(DEFGENERIC DRV-GEN (O)) (:position 220)",
					"(DEFMETHOD DRV-GEN DRV-POINT) (:position 327)");
			assertThat(definitions(client, "drv-point")).containsExactly("(DEFCLASS DRV-POINT) (:position 246)");
			assertThat(definitions(client, "(setf drv-point-x)"))
				.containsExactly("(DEFMETHOD (SETF DRV-POINT-X) T DRV-POINT) (:position 270)");
			assertThat(definitions(client, "make-drv-st")).containsExactly("(DEFUN MAKE-DRV-ST) (:position 379)");
			assertThat(definitions(client, "*drv-var*,")).containsExactly("(DEFVAR *DRV-VAR*) (:position 1)");
			assertThat(definitions(client, "drv-loaded")).containsExactly("(DEFUN DRV-LOADED) (:position 52)");
			assertThat(client.rex("(swank:find-definitions-for-emacs \"drv-buf-fn\")", "CL-USER", "t")).containsExactly(
					"(:return (:ok ((\"(DEFUN DRV-BUF-FN)\" (:location (:buffer-and-file \"drv-nav2.lisp\" "
							+ string(nav2.toString()) + ") (:offset 2 0) (:snippet \"(defun drv-buf-fn ()\n"
							+ "  (drv-callee 7))\"))))) id)");
			assertThat(client.rex("(swank:find-definitions-for-emacs \"drv-repl-fn\")", "CL-USER", "t"))
				.containsExactly("(:return (:ok ((\"(DEFUN DRV-REPL-FN)\" (:error \"Error: DEFINITION-SOURCE of"
						+ " function DRV-REPL-FN did not contain meaningful information.\")))) id)");
			assertThat(client.rex("(swank:find-definitions-for-emacs \"mapcar\")", "CL-USER", "t"))
				.containsExactly("(:return (:ok nil) id)");
			// cross references: each use, labelled with the definition it is in
			// a loaded file's uses too, and a REPL definition's, which has no place
			assertThat(xref(client, ":calls", "drv-callee")).containsExactly("DRV-CALLER (:position 201)",
					"(DEFMETHOD DRV-GEN (DRV-POINT)) (:position 362)", "DRV-BUF-FN (:offset 2 23)",
					"DRV-LOADED (:position 73)",
					"DRV-REPL-FN Error: DEFINITION-SOURCE of function DRV-REPL-FN did not contain meaningful information.");
			assertThat(xref(client, ":callers", "drv-callee")).containsExactly("DRV-CALLER (:position 120)",
					"(DEFMETHOD DRV-GEN (DRV-POINT)) (:position 327)", "DRV-BUF-FN (:offset 2 0)",
					"DRV-LOADED (:position 52)",
					"DRV-REPL-FN Error: DEFINITION-SOURCE of function DRV-REPL-FN did not contain meaningful information.");
			assertThat(xref(client, ":calls-who", "drv-caller")).containsExactly("DRV-CALLEE (:position 70)");
			assertThat(xref(client, ":references", "*drv-var*")).containsExactly("DRV-CALLER (:position 192)");
			assertThat(xref(client, ":binds", "*drv-var*")).containsExactly("DRV-CALLER (:position 144)");
			assertThat(xref(client, ":sets", "*drv-var*")).containsExactly("DRV-CALLER (:position 169)");
			assertThat(xref(client, ":macroexpands", "drv-mac")).containsExactly("DRV-CALLER (:position 192)");
			assertThat(xref(client, ":specializes", "drv-point")).containsExactly("(DEFMETHOD DRV-GEN) (:position 327)",
					"(DEFMETHOD DRV-POINT-X) (:position 270)", "(DEFMETHOD (SETF DRV-POINT-X)) (:position 270)");
			assertThat(client.rex("(swank:xref :who-knows \"drv-callee\")", "CL-USER", "t"))
				.containsExactly("(:return (:ok :not-implemented) id)");
			assertThat(client.rex("(swank:xrefs '(:calls :binds) \"drv-buf-fn\")", "CL-USER", "t"))
				.containsExactly("(:return (:ok nil) id)");
			// the inspector: a value's parts, each inspected in turn, and back
			assertThat(client.rex("(swank:init-inspector \"(list 1 \\\"two\\\" 'three)\")", "CL-USER", "t").getLast())
				.matches("\\(:return \\(:ok \\(:title \"#<CONS \\{[0-9A-F]+\\}>\" :id 0 :content \\(\\(\"A proper"
						+ " list:\" \"\n\" \"0\" \": \" \\(:value \"1\" 1\\) \"\n\" \"1\" \": \" \\(:value \"\\\\\"two"
						+ "\\\\\"\" 2\\) \"\n\" \"2\" \": \" \\(:value \"THREE\" 3\\) \"\n\"\\) 14 0 500\\)\\)\\) id\\)");
			assertThat(client.rex("(swank:inspect-nth-part 1)", "CL-USER", "t").getLast())
				.contains(":content ((\"Value: 1 = #x00000001 = #o1 = #b1 = 1.0e+0\" \"\n\"")
				.contains("\"Integer-length\" \": \" (:value \"@1=1\" 2)");
			assertThat(client.rex("(swank:inspector-pop)", "CL-USER", "t").getLast())
				.contains(":id 4 :content ((\"A proper list:\" \"\n\" \"0\" \": \" (:value \"@1=1\" 5)");
			assertThat(client.rex("(swank:inspector-range 0 2)", "CL-USER", "t"))
				.containsExactly("(:return (:ok ((\"A proper list:\" \"\n\") 1002 0 2)) id)");
			assertThat(client.rex("(swank:inspector-eval \"(length *)\")", "CL-USER", "t"))
				.containsExactly("(:return (:ok \"3\") id)");
			assertThat(client.rex("(swank:describe-inspectee)", "CL-USER", "t"))
				.containsExactly("(:return (:ok \"(1 \\\"two\\\" THREE)\n  [list]\n\") id)");
			// an action changes the value and shows its page again
			client.rex("(swank:init-inspector \"(let ((h (make-hash-table))) (setf (gethash :a h) 1) h)\")", "CL-USER",
					"t");
			assertThat(client.rex("(swank:inspector-call-nth-action 1)", "CL-USER", "t").getLast())
				.contains("(\"Count\" \": \" (:value \"0\" ")
				.doesNotContain("[remove entry]");
			assertThat(
					client.rex("(swank:init-inspector \"(make-instance 'drv-point :x 1)\")", "CL-USER", "t").getLast())
				.contains(
						" \" Group slots by inheritance \" (:action \"[ ]\" 0) \"\n\" \" Sort slots alphabetically  \""
								+ " (:action \"[X]\" 1) \"\n\" \"\n\" \"All Slots:\" \"\n\" (:action \"[ ]\" 2) \"  \" (:value"
								+ " \"X\" 2) \"\" \" = \" (:value \"1\" 3) \"\n\" (:action \"[ ]\" 3) \"  \" (:value \"Y\" 4)"
								+ " \"\" \" = \" (:value \"5\" 5) \"\n\" \"\n\" (:action \"[set value]\" 4) \"  \" (:action"
								+ " \"[make unbound]\" 5) \"\n\") 33 0 500)");
			assertThat(client.rex("(swank:init-inspector \"'drv-callee\")", "CL-USER", "t").getLast())
				.contains("\"It is a function: \" (:value \"#<function DRV-CALLEE>\" 2) \" \" (:action \"[unbind]\" 0)"
						+ " \"\n\" \"Function documentation\" \": \" \"Callee doc.\" \"\n\" \"It is \" \"internal\"");
			// a class, a slot definition, a generic function and its method, a package
			// and its symbols, a pathname: the fancy inspector's pages for them
			assertThat(client.rex("(swank:init-inspector \"(find-class 'drv-point)\")", "CL-USER", "t").getLast())
				.contains(":content ((\"Name: \" (:value \"DRV-POINT\" 1) \"\n\" \"Super classes: \" \"\n\""
						+ " \"Direct Slots: \" (:value \"X\" 2) \", \" (:value \"Y\" 3) \"\n\" \"Effective Slots: \""
						+ " (:value \"X\" 4) \", \" (:value \"Y\" 5) \"\n\" \"Sub classes: \" \"\n\" \"Precedence List: \""
						+ " (:value \"DRV-POINT\" 6) \"\n\" \"It is used as a direct specializer in the following"
						+ " methods:\" \"\n\" \"  \" (:value \"(DRV-GEN DRV-POINT)\" 7) \"\n\"")
				.contains("\"Prototype: \" (:value \"#<DRV-POINT ");
			assertThat(client.rex("(swank:inspect-nth-part 3)", "CL-USER", "t").getLast())
				.contains(":content ((\"Name: \" (:value \"Y\" 1) \"\n\" \"Init args: \" (:value \"(:Y)\" 2) \"\n\""
						+ " \"Init form: \" (:value \"5\" 3) \"\n\" \"Init function: \" (:value \"NIL\" 4) \"\n\""
						+ " \"--------------------\"");
			assertThat(client.rex("(swank:init-inspector \"#'drv-gen\")", "CL-USER", "t").getLast())
				.matches("\\(:return \\(:ok \\(:title \"#<STANDARD-GENERIC-FUNCTION \\{[0-9A-F]+\\}>\" :id 0 :content"
						+ " \\(\\(\"Name\" \": \" \\(:value \"DRV-GEN\" 1\\) \"\n\" \"Arguments\" \": \" \\(:value \"\\(O\\)\""
						+ " 2\\) \"\n\" \"Method combination\" \": \" \\(:value \"STANDARD\" 3\\) \"\n\" \"Methods: \" \"\n\""
						+ " \\(:value \"\\(DRV-POINT\\)\" 4\\) \" \" \\(:action \"\\[remove method\\]\" 0\\) \"\n\" \"\n\"\\)"
						+ " 19 0 500\\)\\)\\) id\\)");
			assertThat(client.rex("(swank:inspect-nth-part 4)", "CL-USER", "t").getLast())
				.matches("\\(:return \\(:ok \\(:title \"#<STANDARD-METHOD \\{[0-9A-F]+\\}>\" :id 0 :content"
						+ " \\(\\(\"Method defined on the generic function \" \\(:value \"DRV-GEN\" 1\\) \"\n\""
						+ " \"Lambda List: \" \\(:value \"\\(O\\)\" 2\\) \"\n\" \"Specializers: \" \\(:value"
						+ " \"\\(DRV-POINT\\)\" 3\\) \"\n\" \"Qualifiers: \" \\(:value \"NIL\" 4\\) \"\n\""
						+ " \"Method function: \" \\(:value \"#<function %DRV-GEN--m0>\" 5\\) \"\n\"\\) 15 0 500\\)\\)\\) id\\)");
			client.rex("(swank:inspector-pop)", "CL-USER", "t");
			assertThat(client.rex("(swank:inspector-call-nth-action 1)", "CL-USER", "t"))
				.containsExactly("(:return (:ok nil) id)");
			assertThat(client.repl("(drv-gen (make-instance 'drv-point))").getLast()).startsWith("(:return (:abort ");
			assertThat(client.rex("(swank:init-inspector \"(find-package :drv-pkg)\")", "CL-USER", "t").getLast())
				.contains(":content ((\"\" \"Name: \" (:value \"\\\"DRV-PKG\\\"\" 1) \"\n\" \"Nicknames: \" \"\n\""
						+ " \"Use list: \" (:value \"CL\" 2) \"\n\" \"Used by list: \" \"\n\" (:value \"1 present"
						+ " symbol.\" 3) \"\n\" (:value \"1 external symbol.\" 4) \"\n\" \"0 internal symbols.\" \"\n\"");
			assertThat(client.rex("(swank:inspect-nth-part 3)", "CL-USER", "t").getLast())
				.contains(":content ((\"All present symbols of package \\\"DRV-PKG\\\"\" \"\n\" \"\n\" \"A symbol is"
						+ " considered present in a package if it's\" \"\n\"")
				.contains("\"  \" (:action \"[Group by classification]\" 0) \"\n\" \"\n\" \"\" \"Symbols:\" \"       \""
						+ " \"Flags:\" \"\n\" \"-------------- ---------\" \"\n\" (:value \"HELLO          \" 1)"
						+ " \"-f-------\" \"\n\")");
			assertThat(client.rex("(swank:inspector-call-nth-action 0)", "CL-USER", "t").getLast())
				.contains("\"  \" (:action \"[Group by symbol]\" 1) \"\n\" \"\n\" \"FUNCTION\" \"\n\" \""
						+ "-".repeat(64) + "\" \"\n\" (:value \"HELLO\" 3) \"\n\" \"\n\")");
			assertThat(client.rex("(swank:init-inspector \"#p\\\"/no/such/drv.lisp\\\"\")", "CL-USER", "t").getLast())
				.contains(":content ((\"A pathname.\" \"\n\" \"Namestring\" \": \" (:value"
						+ " \"\\\"/no/such/drv.lisp\\\"\" 1) \"\n\" \"Host\" \": \" (:value \"NIL\" 2) \"\n\" \"Device\""
						+ " \": \" (:value \"NIL\" 3) \"\n\" \"Directory\" \": \" (:value \"(:ABSOLUTE \\\"no\\\""
						+ " \\\"such\\\")\" 4) \"\n\" \"Name\" \": \" (:value \"\\\"drv\\\"\" 5) \"\n\" \"Type\" \": \""
						+ " (:value \"\\\"lisp\\\"\" 6) \"\n\" \"Version\" \": \" (:value \"NIL\" 7) \"\n\") 30 0 500)");
			assertThat(client.rex("(swank:quit-inspector)", "CL-USER", "t")).containsExactly("(:return (:ok nil) id)");
			// macrostep: one step of a macro form, the macro forms it holds placed
			assertThat(client.rex("(swank-macrostep:macro-form-p \"(drv-mac 1)\" nil nil)", "CL-USER", "t"))
				.containsExactly("(:return (:ok (:ok :macro)) id)");
			assertThat(client.rex("(swank-macrostep:macro-form-p \"(+ 1 2)\" nil nil)", "CL-USER", "t"))
				.containsExactly("(:return (:ok (:ok nil)) id)");
			assertThat(
					client.rex("(swank-macrostep:macrostep-expand-1 \"(drv-mac (when x y))\" nil nil)", "CL-USER", "t"))
				.containsExactly("(:return (:ok (:ok \"(LIST (WHEN X Y) *DRV-VAR*)\" ((\"WHEN\" :macro 6)))) id)");
			assertThat(client.rex("(swank-macrostep:macrostep-expand-1 \"(+ 1 2)\" t nil)", "CL-USER", "t"))
				.containsExactly("(:return (:ok (:error \"Not a macro or compiler-macro form\")) id)");
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
			assertThat(client.rex("(swank:profile-report)", "CL-USER", "t")).containsExactly(
					"(:return (:abort \"#<SIMPLE-ERROR \\\"The function SWANK:PROFILE-REPORT is undefined.\\\">\") id)");
		}
	}

	/**
	 * SLY 1.0.43 against Slynk and Lem 2.3.0 against micros, recorded the same way
	 * (2026-10-30): one server answers both, each request in the dialect of its
	 * function's package, SLY's REPL a channel with a thread of its own.
	 */
	@Test
	void aSlyAndALemSessionAreAnsweredInTheirOwnDialects() throws Exception {
		Path file = this.tempDir.resolve("drv-lem.lisp");
		Files.writeString(file, "(defun drv-lem () 42)\n");
		try (SwankServer server = start();
				Client sly = new Client(server.port());
				Client lem = new Client(server.port())) {
			assertThat(sly.rex("(slynk:connection-info)", null, "t").getLast())
				.contains(":modules (\"SLYNK/COMPLETION\")")
				.contains(":version \"1.0.43\")) id)");
			assertThat(sly.rex("(slynk:slynk-require '(\"slynk/mrepl\"))", null, "t"))
				.containsExactly("(:return (:ok ((\"SLYNK/COMPLETION\" \"SLYNK/MREPL\") (\"slynk/mrepl\"))) id)");
			List<String> created = sly.rex("(slynk-mrepl:create-mrepl 1)", null, "t");
			assertThat(created).startsWith("(:channel-send 1 (:write-string \"; SLY 1.0.43 (#<MREPL mrepl-1-1>)\n\"))",
					"(:channel-send 1 (:prompt \"CL-USER\" \"CL-USER\" 0 0))");
			assertThat(created.getLast()).startsWith("(:return (:ok (1 ");
			String thread = created.getLast().replaceAll("^\\(:return \\(:ok \\(1 (\\d+)\\)\\) id\\)$", "$1");
			// the REPL answers on its channel: output, the values with their history
			// entry
			// and what reads back as each, then a prompt with the history's length
			assertThat(sly.mrepl("(progn (format t \"hello~%\") (values 1 \"two\"))")).containsExactly(
					"(:channel-send 1 (:write-string \"hello\n\"))",
					"(:channel-send 1 (:write-values ((\"1 (1 bit, #x1, #o1, #b1)\" 0 \"1\") (\"\\\"two\\\"\" 0 nil))))",
					"(:channel-send 1 (:prompt \"CL-USER\" \"CL-USER\" 0 1))");
			sly.mrepl("(defpackage :drv-sly (:use :cl))");
			assertThat(sly.mrepl("(in-package :drv-sly)")).containsExactly(
					"(:channel-send 1 (:write-values ((\":DRV-SLY\" 2 \":drv-sly\"))))",
					"(:channel-send 1 (:prompt \"DRV-SLY\" \"DRV-SLY\" 0 3))");
			// a read asks the editor's REPL for its next input
			sly.write("(:emacs-channel-send 1 (:process \"(list (read-line) 7)\"))");
			assertThat(sly.until("(:channel-send 1 (:set-read-mode :read))")).hasSize(1);
			assertThat(sly.mrepl("typed\n")).containsExactly("(:channel-send 1 (:set-read-mode :finished-reading))",
					"(:channel-send 1 (:write-values ((\"(\\\"typed\\\" 7)\" 3 nil))))",
					"(:channel-send 1 (:prompt \"DRV-SLY\" \"DRV-SLY\" 0 4))");
			assertThat(sly.mrepl("(error \"boom\")")).containsExactly(
					"(:channel-send 1 (:evaluation-aborted \"#<SIMPLE-ERROR \\\"boom\\\">\"))",
					"(:channel-send 1 (:prompt \"DRV-SLY\" \"DRV-SLY\" 0 4))");
			// a package set on the REPL's thread, Slynk's own echo area and completions
			assertThat(sly.rex("(slynk-mrepl:eval-for-mrepl 1 'slynk-mrepl:guess-and-set-package \"CL-USER\")", null,
					thread))
				.containsExactly("(:return (:ok ((\"CL-USER\" \"CL-USER\" 0 4) (t))) id)");
			assertThat(sly.rex("(slynk:interactive-eval \"(+ 2 3)\")", null, "t"))
				.containsExactly("(:return (:ok \"5 (3 bits, #x5, #o5, #b101)\") id)");
			assertThat(sly.rex("(slynk:interactive-eval \"(values)\")", null, "t"))
				.containsExactly("(:return (:ok \"; No value\") id)");
			assertThat(sly.rex("(slynk-completion:simple-completions \"mapc\" \"COMMON-LISP-USER\")", null, "t"))
				.containsExactly("(:return (:ok ((\"mapc\" \"mapcan\" \"mapcar\" \"mapcon\") \"mapc\")) id)");
			assertThat(sly.rex("(slynk-completion:flex-completions \"mvbind\" \"COMMON-LISP-USER\")", null, "t"))
				.containsExactly("(:return (:ok (((\"multiple-value-bind\" 0.009072388522326946"
						+ " ((0 \"M\") (9 \"V\") (15 \"BIND\")) \"macro\")) nil)) id)");
			// an interrupt names the REPL's thread
			sly.write("(:emacs-channel-send 1 (:process \"(loop)\"))");
			Thread.sleep(200);
			sly.write("(:emacs-interrupt " + thread + ")");
			assertThat(sly.until("(:channel-send 1 (:prompt")).containsExactly(
					"(:channel-send 1 (:evaluation-aborted \"#<SIMPLE-ERROR \\\"Interrupt from Emacs\\\">\"))",
					"(:channel-send 1 (:prompt \"CL-USER\" \"CL-USER\" 0 4))");

			assertThat(lem.rex("(micros:connection-info)", "COMMON-LISP-USER", "T").getLast())
				.contains(":version \"0.0.0\")) id)");
			assertThat(lem.rex("(micros/repl:create-repl nil :coding-system \"utf-8-unix\")", "CL-USER", "T"))
				.containsExactly("(:return (:ok (\"CL-USER\" \"CL-USER\")) id)");
			// each value a printed object, output asking for no :write-done
			assertThat(
					lem.rex("(micros/repl:listener-eval \"(progn (format t \\\"hello~%\\\") (values 1 \\\"two\\\"))\""
							+ " :window-width 148)", "CL-USER", ":repl-thread"))
				.containsExactly("(:write-string \"hello\n\")", "(:write-object \"1\" 1 :repl-result)",
						"(:write-string \"\n\" :repl-result)", "(:write-object \"\\\"two\\\"\" 2 :repl-result)",
						"(:write-string \"\n\" :repl-result)", "(:return (:ok nil) id)");
			assertThat(lem.rex("(micros/pretty-eval:pretty-eval \"(* 6 7)\")", "CL-USER", "T"))
				.containsExactly("(:return (:ok (:value \"=> 42 (6 bits, #x2A, #o52, #b101010)\" :id 3)) id)");
			assertThat(lem.rex("(micros:interactive-eval \"(values)\")", "CL-USER", "T"))
				.containsExactly("(:return (:ok \"; No value\") id)");
			// C-c C-l calls micros:load-file in a form of Lem's own
			assertThat(lem.rex(
					"(micros:interactive-eval " + string("(MICROS:LOAD-FILE " + string(file.toString()) + ")") + ")",
					"CL-USER", "T"))
				.containsExactly("(:return (:ok \"=> \\\"T\\\"\") id)");
			assertThat(lem.rex("(micros/lsp-api:completions \"drv-le\" \"CL-USER\")", "CL-USER", "T"))
				.containsExactly("(:return (:ok ((\"drv-lem\" ((0 \"drv-le\")) \"function\" \"(drv-lem)\""
						+ " \"## Function\n```lisp\n(drv-lem)\n```\" \"0000000000\"))) id)");
			assertThat(lem.rex("(micros/lsp-api:hover-symbol \"mapcar\")", "CL-USER", "T")).containsExactly(
					"(:return (:ok \"## Function\n```lisp\n(mapcar function list &rest more-lists)\n```\") id)");
			assertThat(lem.rex("(micros/macrostep:macrostep-expand-1 \"(when a b)\" t '(\"\" \"(when a b)\"))",
					"CL-USER", "T"))
				.containsExactly("(:return (:ok (:ok \"(IF A B NIL)\" nil)) id)");
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

	@Test
	void aProgramStartsTheServerOnItsOwnEvaluatorAndGoesOn() throws Exception {
		int waiting;
		try (ServerSocket probe = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
			waiting = probe.getLocalPort();
		}
		BlockingQueue<String> lines = new LinkedBlockingQueue<>();
		OutputStream sink = new OutputStream() {
			private final ByteArrayOutputStream line = new ByteArrayOutputStream();

			@Override
			public synchronized void write(int b) {
				if (b == '\n') {
					lines.add(this.line.toString(StandardCharsets.UTF_8));
					this.line.reset();
				}
				else {
					this.line.write(b);
				}
			}
		};
		RontoLispCli cli = new RontoLispCli(InputStream.nullInputStream(), new PrintStream(sink, true));
		// :dont-close t returns at once; SWANK's default waits for one connection, then
		// closes the port
		Thread program = Thread.ofPlatform().start(() -> cli.run(new String[] { "-e", """
				(asdf:load-system "swank")
				(defvar *state* 41)
				(format t "port ~a~%%" (swank:create-server :port 0 :dont-close t))
				(finish-output)
				(swank:create-server :port %d)
				(format t "went on~%%")
				""".formatted(waiting) }));
		String announced = Objects.requireNonNull(lines.poll(60, TimeUnit.SECONDS));
		assertThat(announced).startsWith("port ");
		int port = Integer.parseInt(announced.substring("port ".length()));
		try (Client client = new Client(port); Client first = connect(waiting)) {
			assertThat(lines.poll(60, TimeUnit.SECONDS)).isEqualTo("went on");
			program.join();
			// both serve the program's own evaluator
			assertThat(client.repl("(1+ *state*)")).first().isEqualTo("(:write-string \"42\n\" :repl-result)");
			assertThat(first.repl("*state*")).first().isEqualTo("(:write-string \"41\n\" :repl-result)");
			assertThatThrownBy(() -> new Socket(InetAddress.getLoopbackAddress(), waiting).close())
				.isInstanceOf(ConnectException.class);
			// stop-server closes the port, the connection going on
			client.repl("(swank:stop-server " + port + ")");
			assertThatThrownBy(() -> new Socket(InetAddress.getLoopbackAddress(), port).close())
				.isInstanceOf(ConnectException.class);
			assertThat(client.repl("(+ 1 2)")).first().isEqualTo("(:write-string \"3\n\" :repl-result)");
		}
	}

	/** Connects to a port a program is about to listen on. */
	private static Client connect(int port) throws IOException, InterruptedException {
		for (int attempt = 0;; attempt++) {
			try {
				return new Client(port);
			}
			catch (ConnectException ex) {
				if (attempt == 600) {
					throw ex;
				}
				Thread.sleep(100);
			}
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

	/** Each of a name's definitions as its label and its position, the file left out. */
	private static List<String> definitions(Client client, String name) throws IOException {
		return places(client.rex("(swank:find-definitions-for-emacs " + string(name) + ")", "CL-USER", "t"));
	}

	/** Each use an {@code xref} answers as its label and its position. */
	private static List<String> xref(Client client, String type, String name) throws IOException {
		return places(client.rex("(swank:xref " + type + " " + string(name) + ")", "CL-USER", "t"));
	}

	private static List<String> places(List<String> replies) throws IOException {
		List<?> reply = (List<?>) SwankWire.parse(replies.getLast());
		List<String> out = new ArrayList<>();
		for (Object entry : (List<?>) Objects.requireNonNull(((List<?>) Objects.requireNonNull(reply.get(1))).get(1))) {
			List<?> pair = (List<?>) Objects.requireNonNull(entry);
			List<?> where = (List<?>) Objects.requireNonNull(pair.get(1));
			// a place's position, or the error a definition with none answers
			out.add(pair.getFirst() + " " + (where.size() > 2 ? SwankWire.print(where.get(2)) : where.get(1)));
		}
		return out;
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
		long send(String form, @Nullable String pkg, String thread) throws IOException {
			long id = ++this.ids;
			write("(:emacs-rex " + form + " " + (pkg == null ? "nil" : string(pkg)) + " " + thread + " " + id + ")");
			return id;
		}

		/** Sends a request and answers its messages up to its {@code :return}. */
		List<String> rex(String form, @Nullable String pkg, String thread) throws IOException {
			return replies(send(form, pkg, thread));
		}

		/**
		 * An input to SLY's REPL on channel 1, and its messages up to the next prompt.
		 */
		List<String> mrepl(String input) throws IOException {
			write("(:emacs-channel-send 1 (:process " + string(input) + "))");
			return until("(:channel-send 1 (:prompt");
		}

		/** The messages up to the first that starts with a text. */
		List<String> until(String prefix) throws IOException {
			List<String> messages = new ArrayList<>();
			while (true) {
				String message = SwankWire.print(Objects.requireNonNull(SwankWire.read(this.in)));
				messages.add(message);
				if (message.startsWith(prefix)) {
					return messages;
				}
			}
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
