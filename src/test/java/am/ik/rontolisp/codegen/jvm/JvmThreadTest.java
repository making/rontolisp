package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import am.ik.rontolisp.testsupport.SocketTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The thread primitives on the JVM backend ({@code JvmThreadRuntimeBuilder}): spawn /
 * join / threadp / thread-alive-p / destroy-thread, the runtime name-to-ThreadLocal
 * dynamic binding of {@code make-thread}'s bindings alist (the clack.handler shape:
 * {@code *standard-output*} rebound in the spawned thread through the thread-scoped
 * store), the cross-thread error payload rethrown at join, and the
 * {@code bordeaux-threads}/{@code bt2} shim compiled through the command line's front
 * end. Each case mirrors its interpreter twin in {@code ThreadTest}.
 */
class JvmThreadTest {

	@TempDir
	Path tempDir;

	private String compileAndRun(String lispCode, String className) throws Exception {
		// The command line's whole front end, so a library's macros expand as they do
		// for a user.
		byte[] classBytes = new am.ik.rontolisp.cli.JvmSourceCompiler(className).compile(lispCode, null).classBytes();
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
	void eachThreadHasItsOwnMultipleValueChannel() throws Exception {
		// The channel is a per-thread store in a program that spawns threads
		// (JvmMvChannel): through the one static field a sibling's tail cleared the
		// values between th-helper's publish and the consumer's read, and a b c came back
		// nil ("Expected integer, got: NIL" on every run).
		assertThat(compileAndRun("""
				(defun th-helper (n) (values n (* n n) (- n)))
				(defun th-work (n)
				  (let ((acc 0))
				    (dotimes (i 20000)
				      (multiple-value-bind (a b c) (th-helper (+ n i))
				        (setq acc (+ acc (- (+ a b c) (* (+ n i) (+ n i)))))))
				    acc))
				(let ((threads nil))
				  (dotimes (n 8) (let ((k n)) (push (rontolisp:make-thread (lambda () (th-work k))) threads)))
				  (print (mapcar (lambda (th) (rontolisp:join-thread th)) threads)))
				""", "ThreadMvProg")).isEqualTo("(0 0 0 0 0 0 0 0)");
	}

	@Test
	void gensymsMadeByRacingThreadsAreAllDistinct() throws Exception {
		// The counter is one static int per compiled class; a plain read-increment-write
		// let two threads answer the same name (a served handler runs one thread per
		// request, .kb/concurrent-served-requests.md). Every name is kept, then counted.
		assertThat(compileAndRun("""
				(defun th-names (n)
				  (let ((acc nil))
				    (dotimes (i n) (push (gensym) acc))
				    acc))
				(let ((threads nil) (seen (make-hash-table :test 'equal)))
				  (dotimes (k 8) (push (rontolisp:make-thread (lambda () (th-names 20000))) threads))
				  (dolist (th threads)
				    (dolist (name (rontolisp:join-thread th)) (setf (gethash name seen) t)))
				  (print (hash-table-count seen)))
				""", "ThreadGensymProg")).isEqualTo("160000");
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
	void aBuiltInsTypeErrorStaysOneAcrossTheJoin() throws Exception {
		// The datum and type of a built-in's type-error are recorded on the thread that
		// raised it; the payload carries the record to the joiner, whose pad read the
		// throwable as a simple-error without it.
		assertThat(compileAndRun("""
				(defvar *tj* (read-from-string "5"))
				(print (handler-case (rontolisp:join-thread (rontolisp:make-thread (lambda () (car *tj*))))
				         (type-error (c) (list :type-error (type-error-datum c) (type-error-expected-type c)))))
				""", "ThreadTypeErrProg")).isEqualTo("(:TYPE-ERROR 5 LIST)");
	}

	@Test
	void aJoinHandledInACleanupLeavesTheConditionOnItsWayOut() throws Exception {
		// _thread_join records the thread's condition under the throwable it rethrows:
		// setting the one per-thread slot replaced the typed condition the cleanup's
		// unwind-protect was carrying, and the outer handler-case caught a
		// simple-error.
		assertThat(compileAndRun("""
				(define-condition jc-typed (error) ())
				(define-condition jc-other (error) ())
				(let ((th (rontolisp:make-thread (lambda () (error 'jc-other)))))
				  (print (handler-case
				             (unwind-protect (error 'jc-typed)
				               (print (handler-case (rontolisp:join-thread th)
				                        (jc-typed () :read-as-the-typed-one)
				                        (jc-other () :other))))
				           (jc-typed () :typed)
				           (error () :lost-its-type))))
				""", "ThreadCleanupProg")).isEqualTo(":OTHER\n:TYPED");
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
		// code as the spawner: the new thread reads the global default -- a closure built
		// inside the binding too, since no special binding has a lexical twin to capture.
		assertThat(compileAndRun("""
				(defvar *who* 'global)
				(defun who-reader () *who*)
				(let ((*who* 'spawner))
				  (print (list (who-reader)
				               (rontolisp:join-thread (rontolisp:make-thread #'who-reader))
				               (rontolisp:join-thread (rontolisp:make-thread (lambda () *who*))))))
				""", "ThreadScopeProg")).isEqualTo("(SPAWNER GLOBAL GLOBAL)");
	}

	@Test
	void aSpecialWithoutAValueIsUnboundInAThreadThatDoesNotBindIt() throws Exception {
		// The UNBOUND marker is the shared global default a thread without a binding
		// reads through _dget, so its read signals there -- caught in the thread, and
		// re-signalled by join-thread when not -- while the bindings alist hands it a
		// value. The read used to answer nil (interpreter parity: ThreadTest's twin).
		assertThat(compileAndRun("""
				(defvar *tv*)
				(defun tv-read () (handler-case *tv* (unbound-variable (c) (list :unbound (cell-error-name c)))))
				(let ((*tv* :spawner))
				  (print (list (tv-read)
				               (rontolisp:join-thread (rontolisp:make-thread #'tv-read))
				               (rontolisp:join-thread (rontolisp:make-thread #'tv-read (list (cons '*tv* :handed))))
				               (handler-case (rontolisp:join-thread (rontolisp:make-thread (lambda () *tv*)))
				                 (unbound-variable (c) (list :joined (cell-error-name c)))))))
				(print (tv-read))
				""", "ThreadUnboundProg")).isEqualTo("""
				(:SPAWNER (:UNBOUND *TV*) :HANDED (:JOINED *TV*))
				(:UNBOUND *TV*)""");
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

	@Test
	void btConditionVariablesAndSemaphoresHandOffBetweenNamedThreads() throws Exception {
		assertThat(compileAndRun("""
				(asdf:load-system "bordeaux-threads")
				(defmethod bt-kind ((x bt:lock)) :lock)
				(defmethod bt-kind ((x integer)) :integer)
				(defvar *cv-lock* (bt:make-lock))
				(defvar *cv* (bt:make-condition-variable))
				(defvar *cv-box* nil)
				(defvar *go* (bt:make-semaphore :name "go"))
				(let ((th (bt:make-thread (lambda ()
				                            (bt:wait-on-semaphore *go*)
				                            (bt:with-lock-held (*cv-lock*)
				                              (setq *cv-box* (bt:thread-name (bt:current-thread)))
				                              (bt:condition-broadcast *cv*))
				                            :finished)
				                          :name "worker"
				                          :initial-bindings bt:*standard-io-bindings*)))
				  (print (list (bt-kind (bt:make-recursive-lock)) (bt-kind 7)
				         (bt:thread-name th)
				         (if (member th (bt:all-threads)) t nil)
				         (bt:wait-on-semaphore (bt:make-semaphore) :timeout 1/100)
				         (bt:with-lock-held (*cv-lock*) (bt:condition-wait *cv* *cv-lock* :timeout 1/100))
				         (progn (bt:signal-semaphore *go*)
				                (bt:with-lock-held (*cv-lock*)
				                  (loop until (or *cv-box* (not (bt:thread-alive-p th)))
				                        do (bt:condition-wait *cv* *cv-lock* :timeout 1))
				                  *cv-box*))
				         (bt:join-thread th)
				         (bt:wait-on-semaphore (bt:make-semaphore :count 1))
				         (bt:thread-name (bt:current-thread)))))
				""", "ThreadBtCvProg")).isEqualTo("(:LOCK :INTEGER \"worker\" T NIL NIL \"worker\" :FINISHED T NIL)");
	}

	@SocketTest
	void threadAndSocketOperatorsSignalTheirConditionOverAValueTheyCannotTake() throws Exception {
		// The interpreter's conditions and words (ThreadTest), where a cast or a null
		// table used to leak a host exception.
		assertThat(compileAndRun(am.ik.rontolisp.HandleOperandFixture.PROGRAM, "HandleOperandProg"))
			.isEqualTo(am.ik.rontolisp.HandleOperandFixture.EXPECTED);
	}

	@SocketTest
	void socketHostFailuresReadAsTheInterpretersDo() throws Exception {
		assertThat(compileAndRun(am.ik.rontolisp.HandleOperandFixture.HOST_FAILURE_PROGRAM, "HostFailureProg"))
			.isEqualTo(am.ik.rontolisp.HandleOperandFixture.HOST_FAILURE_EXPECTED);
	}

}
