package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The new {@code objc} base's blocks -- {@code make-objc-block}, {@code call-objc-block}
 * and the rest ({@code objc-block.lisp}) -- and {@code fli:define-foreign-function} on
 * the interpreter. The corpus ({@code objc-block-corpus.lisp}) is what a JVM class prints
 * byte for byte too; a {@code --native} executable prints its own expected file, which
 * differs exactly where a block arrives on another thread (.kb/objc.md, "Blocks and C
 * functions").
 */
class ObjcBlockTest {

	private static String resource(String name) {
		try (InputStream in = ObjcBlockTest.class.getResourceAsStream("/" + name)) {
			return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String eval(String source) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		LispVal result = LispNil.INSTANCE;
		for (LispVal form : LispReader.readAllFromString(source)) {
			result = evaluator.eval(form);
		}
		return result.print();
	}

	@Test
	void theDeclaringFormsNeedNoRuntime() {
		// A block type and a foreign function are declarations: the runtime is opened
		// when a block is made or the function is called, on a Mac.
		assertThat(eval("""
				(objc:define-objc-block-type comparator :long-long
				  (objc:objc-object-pointer objc:objc-object-pointer))
				(fli:define-foreign-function (dispatch-async "dispatch_async")
				    ((queue objc:objc-object-pointer) (work objc:objc-at-question-mark))
				  :result-type :void)
				(list (gethash 'comparator objc::*block-types*) (fboundp 'dispatch-async)
				      (objc::%block-encodings :void '(objc:objc-object-pointer (:unsigned :long-long))))
				""")).isEqualTo("((:LONG-LONG (OBJC:OBJC-OBJECT-POINTER OBJC:OBJC-OBJECT-POINTER)) T \"v^v@Q\")");
		assertThat(eval("""
				(handler-case (objc:define-objc-block-type bad :void (:no-such-type))
				  (error (e) (princ-to-string e)))
				""")).isEqualTo("\"objc: :NO-SUCH-TYPE is not an FLI type this interface can call\"");
		// A foreign name left out is the Lisp name in lower case, hyphens underscores.
		assertThat(eval("""
				(fli:define-foreign-function (getpid "getpid") () :result-type :int)
				(macroexpand-1 '(fli:define-foreign-function pthread-main-np () :result-type :int))
				""")).contains("\"pthread_main_np\"");
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void theCorpusPrintsWhatIsCommitted() {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		PrintStream oldErr = System.err;
		System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
		String out;
		try {
			out = ObjcBaseTest.interpret(resource("objc-block-corpus.lisp"));
		}
		finally {
			System.setErr(oldErr);
		}
		assertThat(out).isEqualTo(resource("objc-block-corpus.expected"));
		// A block whose function signals is contained: printed, answered as zero.
		assertThat(err.toString(StandardCharsets.UTF_8)).isEqualTo("objc: error in a callback: no 9\n");
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void aBlockAnotherThreadCallsRunsThereWithTheGlobalBindings() {
		// A libdispatch worker enters the interpreter: the function runs on that thread,
		// concurrently with the program, and sees the global values -- a binding the
		// program's thread made is not the worker's (a closure's own captures aside, as
		// for rontolisp:make-thread).
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		assertThat(eval("""
				(fli:define-foreign-function (pthread-main-np "pthread_main_np") () :result-type :int)
				(fli:define-foreign-function (dispatch-queue-create "dispatch_queue_create")
				    ((label objc:objc-c-string) (attributes :pointer))
				  :result-type objc:objc-object-pointer)
				(fli:define-foreign-function (dispatch-async "dispatch_async")
				    ((queue objc:objc-object-pointer) (work objc:objc-at-question-mark))
				  :result-type :void)
				(defvar *where* :global)
				(defvar *seen* nil)
				(defun where () *where*)
				(let ((*where* :bound))
				  (objc:with-objc-block (b '(:void ()) (lambda () (setq *seen* (list (where) (pthread-main-np)))))
				    (dispatch-async (dispatch-queue-create "rontolisp.test" nil) b))
				  (do ((i 0 (+ i 1))) ((or *seen* (> i 500))) (sleep 0.01))
				  (list (where) *seen*))
				""")).isEqualTo("(:BOUND (:GLOBAL 0))");
	}

}
