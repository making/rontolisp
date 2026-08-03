package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The thread primitives on the JVM backend ({@code JvmThreadRuntimeBuilder}): spawn /
 * join / threadp / thread-alive-p / destroy-thread, the runtime name-to-ThreadLocal
 * dynamic binding of {@code make-thread}'s bindings alist (the clack.handler shape:
 * {@code *standard-output*} rebound in the spawned thread through the todo-189
 * thread-scoped store), the cross-thread error payload rethrown at join, and the
 * {@code bordeaux-threads}/{@code bt2} shim compiled through the {@code LoadInliner}
 * splice. Each case mirrors its interpreter twin in {@code ThreadTest}.
 */
class JvmThreadTest {

	@TempDir
	Path tempDir;

	private String compileAndRun(String lispCode, String className) throws Exception {
		List<LispVal> program = am.ik.rontolisp.cli.LoadInliner.inline(LispReader.readAllFromString(lispCode), path -> {
			throw new FileNotFoundException(path);
		});
		JvmLispCompiler compiler = new JvmLispCompiler(className);
		byte[] classBytes = compiler.compile(am.ik.rontolisp.eval.LispPreludeLibrary.process(program));
		Files.write(this.tempDir.resolve(className + ".class"), classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { this.tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass(className);
			Method main = clazz.getMethod("main", String[].class);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(baos));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			finally {
				System.setOut(oldOut);
			}
			return baos.toString().trim();
		}
	}

	@Test
	void joinThreadYieldsTheFunctionsValueAndTheThreadIsDeadAfterwards() throws Exception {
		assertThat(compileAndRun("""
				(let ((th (rontolisp:make-thread (lambda () (+ 40 2)))))
				  (print (list (rontolisp:threadp th)
				               (rontolisp:join-thread th)
				               (rontolisp:thread-alive-p th)
				               (rontolisp:threadp 42))))
				""", "ThreadJoinProg")).isEqualTo("(T 42 NIL NIL)");
	}

	@Test
	void currentThreadIsAnEqStableLiveHandle() throws Exception {
		// EQ-stability (the _curThreadTl cache) is the property dbi's per-thread
		// connection cache keys on: the handle must be reusable as an eq
		// hash-table key across calls, and a spawned body's self-handle is its own.
		assertThat(compileAndRun("""
				(let ((h (rontolisp:current-thread)))
				  (print (list (rontolisp:threadp h)
				               (eq h (rontolisp:current-thread))
				               (rontolisp:thread-alive-p h)
				               (let ((table (make-hash-table :test 'eq)))
				                 (setf (gethash (rontolisp:current-thread) table) :hit)
				                 (gethash (rontolisp:current-thread) table))
				               (eq h (rontolisp:join-thread
				                      (rontolisp:make-thread (lambda () (rontolisp:current-thread))))))))
				""", "ThreadCurrentProg")).isEqualTo("(T T T :HIT NIL)");
	}

	@Test
	void joinThreadResignalsTheThreadsErrorSoHandlerCaseDispatches() throws Exception {
		// The EMARKER payload: call() completes normally with {EMARKER, throwable,
		// condition}, _thread_join re-sets the condition on the joining thread and
		// rethrows, so handler-case dispatches by type (the _await precedent).
		assertThat(compileAndRun("""
				(print (handler-case
				           (rontolisp:join-thread (rontolisp:make-thread (lambda () (error "thread boom"))))
				         (error (e) (format nil "caught: ~A" e))))
				""", "ThreadErrProg")).isEqualTo("\"caught: thread boom\"");
	}

	@Test
	void makeThreadBindingsAreDynamicBindingsInTheSpawnedThreadOnly() throws Exception {
		// The clack.handler shape: *standard-output* rebound BY NAME at runtime (the
		// _dtl dispatch) in the spawned thread; the spawner's stream stays untouched.
		assertThat(compileAndRun("""
				(defvar *cap* (make-string-output-stream))
				(rontolisp:join-thread
				 (rontolisp:make-thread (lambda () (princ "from-thread"))
				                        (list (cons '*standard-output* *cap*))))
				(princ "spawner-out ")
				(print (get-output-stream-string *cap*))
				""", "ThreadBindProg")).isEqualTo("spawner-out \"from-thread\"");
	}

	@Test
	void spawnedThreadDoesNotInheritTheSpawnersDynamicBindings() throws Exception {
		// The plain-ThreadLocal rule of .kb/dynamic-special-variables.md, now with user
		// code as the spawner: the new thread reads the global default.
		assertThat(compileAndRun("""
				(defvar *who* 'global)
				(defun who-reader () *who*)
				(let ((*who* 'spawner))
				  (print (list (who-reader)
				               (rontolisp:join-thread (rontolisp:make-thread #'who-reader)))))
				""", "ThreadScopeProg")).isEqualTo("(SPAWNER GLOBAL)");
	}

	@Test
	void bt2ShimSpawnsThroughThePrimitivesWithInitialBindings() throws Exception {
		// clack's handler.lisp shape verbatim: a quote form and an already-evaluated
		// stream value in :initial-bindings, plus the v1 spellings clack imports.
		assertThat(compileAndRun("""
				(asdf:load-system "bordeaux-threads")
				(defvar *bt2-cap* (make-string-output-stream))
				(defvar *bt2-cfg* 'unset)
				(let ((th (bt2:make-thread (lambda () (princ "bt2-thread") *bt2-cfg*)
				                           :name "worker"
				                           :initial-bindings
				                           (list (cons '*standard-output* *bt2-cap*)
				                                 (cons '*bt2-cfg* '(quote configured))))))
				  (print (list (bordeaux-threads:threadp th)
				               (bt2:join-thread th)
				               (bordeaux-threads:thread-alive-p th)
				               (get-output-stream-string *bt2-cap*)
				               *bt2-cfg*
				               bt2:*default-special-bindings*)))
				""", "ThreadBt2Prog")).isEqualTo("(T CONFIGURED NIL \"bt2-thread\" UNSET NIL)");
	}

}
