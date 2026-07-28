package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;

import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code rontolisp:make-mutex} / {@code mutex-acquire} / {@code mutex-release}
 * primitives and the {@code with-mutex} lowering on the interpreter, including the
 * property the whole feature exists for: two concurrent virtual threads incrementing a
 * shared counter under the lock agree with the sequential result.
 */
class MutexTest {

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
	void makeMutexHandsOutDistinctHandles() {
		assertThat(evalAll(evaluator(), """
				(let ((a (rontolisp:make-mutex)) (b (rontolisp:make-mutex)))
				  (if (eql a b) 'same 'distinct))
				""").print()).isEqualTo("DISTINCT");
	}

	@Test
	void withMutexYieldsTheBodyValue() {
		assertThat(evalAll(evaluator(), """
				(let ((m (rontolisp:make-mutex)))
				  (rontolisp:with-mutex (m) 1 2 42))
				""")).isEqualTo(new LispInteger(42));
	}

	@Test
	void withMutexReleasesOnANonLocalExitSoTheLockCanBeTakenAgain() {
		// The unwind-protect leg: without it the second acquisition would block forever.
		assertThat(evalAll(evaluator(), """
				(let ((m (rontolisp:make-mutex)))
				  (ignore-errors (rontolisp:with-mutex (m) (error "boom")))
				  (rontolisp:with-mutex (m) 'reacquired))
				""").print()).isEqualTo("REACQUIRED");
	}

	@Test
	void theLockIsReentrant() {
		assertThat(evalAll(evaluator(), """
				(let ((m (rontolisp:make-mutex)))
				  (rontolisp:with-mutex (m) (rontolisp:with-mutex (m) 'nested)))
				""").print()).isEqualTo("NESTED");
	}

	@Test
	void releasingAMutexThisThreadDoesNotHoldIsAnError() {
		assertThatThrownBy(() -> evalAll(evaluator(), """
				(rontolisp:mutex-release (rontolisp:make-mutex))
				""")).isInstanceOf(LispEvalException.class).hasMessageContaining("not held by this thread");
	}

	@Test
	void aValueThatIsNotAMutexHandleIsAnError() {
		assertThatThrownBy(() -> evalAll(evaluator(), "(rontolisp:mutex-acquire 0)"))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a mutex handle");
	}

	@Test
	void bordeauxThreadsLocksRideTheSamePrimitives() {
		LispEvaluator evaluator = evaluator();
		evaluator.eval(LispReader.readFromString("(asdf:load-system \"bordeaux-threads\")"));
		assertThat(evalAll(evaluator, """
				(let ((l (bt:make-lock "counter")))
				  (list (bt:with-lock-held (l) 'held)
				        (bt:acquire-lock l)
				        (bt:release-lock l)
				        bt:*supports-threads-p*))
				""").print()).isEqualTo("(HELD T T T)");
	}

	@Test
	void concurrentIncrementsUnderTheLockAgreeWithTheSequentialResult() throws Exception {
		int threads = 4;
		int perThread = 2000;
		LispEvaluator evaluator = evaluator();
		evalAll(evaluator, """
				(defvar *mt-lock* (rontolisp:make-mutex))
				(defvar *mt-counter* 0)
				(defun mt-bump (n)
				  (dotimes (i n)
				    (rontolisp:with-mutex (*mt-lock*)
				      (setq *mt-counter* (+ *mt-counter* 1)))))
				""");
		LispVal call = LispReader.readFromString("(mt-bump " + perThread + ")");
		CountDownLatch start = new CountDownLatch(1);
		List<Throwable> failures = new CopyOnWriteArrayList<>();
		List<Thread> workers = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			workers.add(Thread.ofVirtual().unstarted(() -> {
				try {
					start.await();
					evaluator.eval(call);
				}
				catch (Throwable ex) {
					failures.add(ex);
				}
			}));
		}
		workers.forEach(Thread::start);
		start.countDown();
		for (Thread worker : workers) {
			worker.join(TimeUnit.MINUTES.toMillis(1));
			assertThat(worker.isAlive()).as("worker finished (a lost release would hang here)").isFalse();
		}
		assertThat(failures).isEmpty();
		assertThat(evaluator.eval(LispReader.readFromString("*mt-counter*")))
			.isEqualTo(new LispInteger((long) threads * perThread));
	}

}
