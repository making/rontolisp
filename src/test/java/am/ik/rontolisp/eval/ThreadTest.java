package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code rontolisp:make-thread} / {@code join-thread} / {@code threadp} /
 * {@code thread-alive-p} / {@code destroy-thread} primitives and the
 * {@code bordeaux-threads}/{@code bt2} shim over them on the interpreter, including the
 * property the whole feature exists for (clack's handler.lisp shape): a spawned thread
 * runs its function under the given dynamic bindings, invisible to the spawner.
 */
class ThreadTest {

	private static LispVal evalAll(LispEvaluator evaluator, String input) {
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result;
	}

	private static LispEvaluator evaluator() {
		return new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
	}

	@Test
	void joinThreadYieldsTheFunctionsValueAndTheThreadIsDeadAfterwards() {
		assertThat(evalAll(evaluator(), """
				(let ((th (rontolisp:make-thread (lambda () (+ 40 2)))))
				  (list (rontolisp:threadp th)
				        (rontolisp:join-thread th)
				        (rontolisp:thread-alive-p th)))
				""").print()).isEqualTo("(T 42 NIL)");
	}

	@Test
	void threadpIsNilOnANonThread() {
		assertThat(evalAll(evaluator(), "(rontolisp:threadp 42)")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void threadAlivePOnANonThreadIsAnError() {
		assertThatThrownBy(() -> evalAll(evaluator(), "(rontolisp:thread-alive-p 42)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a thread handle");
	}

	@Test
	void joinThreadResignalsTheThreadsErrorSoHandlerCaseDispatches() {
		assertThat(evalAll(evaluator(), """
				(handler-case
				    (rontolisp:join-thread (rontolisp:make-thread (lambda () (error "thread boom"))))
				  (error (e) (format nil "caught: ~A" e)))
				""").print()).isEqualTo("\"caught: thread boom\"");
	}

	@Test
	void makeThreadBindingsAreDynamicBindingsInTheSpawnedThreadOnly() {
		// The clack.handler shape: the spawned server thread prints through a rebound
		// *standard-output*, while the spawner's own stream stays untouched.
		assertThat(evalAll(evaluator(), """
				(defvar *cap* (make-string-output-stream))
				(rontolisp:join-thread
				 (rontolisp:make-thread (lambda () (princ "from-thread"))
				                        (list (cons '*standard-output* *cap*))))
				(get-output-stream-string *cap*)
				""").print()).isEqualTo("\"from-thread\"");
	}

	@Test
	void spawnedThreadDoesNotInheritTheSpawnersDynamicBindings() {
		// Same rule as the JVM backend's plain ThreadLocal store: the new thread reads
		// the global default until it binds for itself. The reader is a defun, not a
		// lambda built inside the binding -- a closure would capture the entry value
		// and the capture wins (the documented dual-bind rule,
		// .kb/dynamic-special-variables.md).
		assertThat(evalAll(evaluator(), """
				(defvar *who* 'global)
				(defun who-reader () *who*)
				(let ((*who* 'spawner))
				  (list (who-reader)
				        (rontolisp:join-thread (rontolisp:make-thread #'who-reader))))
				""").print()).isEqualTo("(SPAWNER GLOBAL)");
	}

	@Test
	void destroyThreadOnAFinishedThreadAnswersTheHandle() {
		assertThat(evalAll(evaluator(), """
				(let ((th (rontolisp:make-thread (lambda () 1))))
				  (rontolisp:join-thread th)
				  (rontolisp:threadp (rontolisp:destroy-thread th)))
				""").print()).isEqualTo("T");
	}

	@Test
	void bt2ShimSpawnsThroughThePrimitivesWithInitialBindings() {
		LispEvaluator evaluator = evaluator();
		evaluator.eval(LispReader.readFromString("(asdf:load-system \"bordeaux-threads\")"));
		// clack's handler.lisp shape verbatim: a quote form and an already-evaluated
		// stream value in :initial-bindings, plus the v1 spellings clack imports.
		assertThat(evalAll(evaluator, """
				(defvar *bt2-cap* (make-string-output-stream))
				(defvar *bt2-cfg* 'unset)
				(let ((th (bt2:make-thread (lambda () (princ "bt2-thread") *bt2-cfg*)
				                           :name "worker"
				                           :initial-bindings
				                           (list (cons '*standard-output* *bt2-cap*)
				                                 (cons '*bt2-cfg* '(quote configured))))))
				  (list (bordeaux-threads:threadp th)
				        (bt2:join-thread th)
				        (bordeaux-threads:thread-alive-p th)
				        (get-output-stream-string *bt2-cap*)
				        *bt2-cfg*
				        bt2:*default-special-bindings*))
				""").print()).isEqualTo("(T CONFIGURED NIL \"bt2-thread\" UNSET NIL)");
	}

	@Test
	void bt2RejectsAVariableReferenceBindingForm() {
		LispEvaluator evaluator = evaluator();
		evaluator.eval(LispReader.readFromString("(asdf:load-system \"bordeaux-threads\")"));
		// A symbol form would need the new thread's dynamic environment; the shim
		// signals instead of binding the wrong value.
		assertThatThrownBy(() -> evalAll(evaluator, """
				(bt2:make-thread (lambda () 1)
				                 :initial-bindings (list (cons '*x* '*standard-output*)))
				""")).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("unsupported :initial-bindings value form");
	}

	@Test
	void bt2LockNamesResolveOntoTheV1LockSubset() {
		LispEvaluator evaluator = evaluator();
		evaluator.eval(LispReader.readFromString("(asdf:load-system \"bordeaux-threads\")"));
		assertThat(evalAll(evaluator, """
				(let ((l (bt2:make-lock)))
				  (bt2:with-lock-held (l) 'held))
				""").print()).isEqualTo("HELD");
	}

}
