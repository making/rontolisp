package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import am.ik.rontolisp.LispFuture;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The interpreter arm of the cross-backend async/await contract:
 * {@code rontolisp:async-defun}/{@code async-lambda} eager-start semantics,
 * {@code rontolisp:await} (special form) resolution/flattening/error re-signaling, the
 * await placement rules, EH across suspension, and the first-class stream operations.
 */
class AsyncEvalTest {

	private LispVal eval(String input) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		return evaluator.eval(LispReader.readFromString(input));
	}

	private record Run(LispVal result, String output) {
	}

	private Run evalMulti(String input) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(baos, true, StandardCharsets.UTF_8));
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return new Run(result, baos.toString(StandardCharsets.UTF_8));
	}

	@Test
	void asyncDefunReturnsFutureAndAwaitResolvesIt() {
		Run run = evalMulti("""
				(rontolisp:async-defun add (a b) (+ a b))
				(let ((f (add 1 2)))
				  (list (rontolisp:futurep f) (rontolisp:await f) (rontolisp:await f)))
				""");
		assertThat(run.result().print()).isEqualTo("(t 3 3)");
	}

	@Test
	void eagerStartRunsBodyToFirstSuspension() {
		// the body's output up to the first unsettled await is ordered BEFORE the
		// caller resumes; the remainder is ordered before the await of its future
		Run run = evalMulti("""
				(defvar *s* (rontolisp:make-stream))
				(rontolisp:async-defun f ()
				  (print "in")
				  (rontolisp:await (rontolisp:stream-read *s*))
				  (print "late")
				  "done")
				(print "before")
				(defvar *f* (f))
				(print "after")
				(rontolisp:stream-write *s* "x")
				(print (rontolisp:await *f*))
				""");
		int before = run.output().indexOf("\"before\"");
		int in = run.output().indexOf("\"in\"");
		int after = run.output().indexOf("\"after\"");
		int late = run.output().indexOf("\"late\"");
		int done = run.output().indexOf("\"done\"");
		assertThat(before).isLessThan(in);
		assertThat(in).isLessThan(after);
		assertThat(after).isLessThan(late);
		assertThat(late).isLessThan(done);
	}

	@Test
	void asyncBodyCompletingWithoutSuspensionYieldsSettledFuture() {
		assertThat(eval("(rontolisp:await (funcall (rontolisp:async-lambda (x) (* x 2)) 21))"))
			.isEqualTo(new LispInteger(42));
	}

	@Test
	void awaitFlattensNestedAsyncCalls() {
		Run run = evalMulti("""
				(rontolisp:async-defun inner () 10)
				(rontolisp:async-defun outer () (+ (rontolisp:await (inner)) 1))
				(rontolisp:await (outer))
				""");
		assertThat(run.result()).isEqualTo(new LispInteger(11));
	}

	@Test
	void awaitPassesNonFutureThrough() {
		assertThat(eval("(rontolisp:await 42)")).isEqualTo(new LispInteger(42));
		assertThat(eval("(rontolisp:await nil)").print()).isEqualTo("nil");
		assertThatThrownBy(() -> eval("(rontolisp:await)")).hasMessageContaining("await");
	}

	@Test
	void errorInAsyncBodySignalsAtAwaitAndIsCatchable() {
		Run run = evalMulti("""
				(rontolisp:async-defun failing () (error "boom"))
				(handler-case (rontolisp:await (failing))
				  (error (e) "caught"))
				""");
		assertThat(run.result()).isEqualTo(new LispString("caught"));
		// awaiting the failed future twice re-signals identically
		Run twice = evalMulti("""
				(rontolisp:async-defun failing () (error "boom"))
				(defvar *f* (failing))
				(handler-case (rontolisp:await *f*) (error (e) nil))
				(handler-case (rontolisp:await *f*) (error (e) "again"))
				""");
		assertThat(twice.result()).isEqualTo(new LispString("again"));
	}

	@Test
	void unAwaitedFutureIsDroppedSilently() {
		Run run = evalMulti("""
				(rontolisp:async-defun failing () (error "boom"))
				(failing)
				"survived"
				""");
		assertThat(run.result()).isEqualTo(new LispString("survived"));
	}

	@Test
	void asyncWrapperOnDefunEqualsAsyncDefun() {
		Run run = evalMulti("""
				(rontolisp:async (defun add (a b) (+ a b)))
				(let ((f (add 1 2)))
				  (list (rontolisp:futurep f) (rontolisp:await f)))
				""");
		assertThat(run.result().print()).isEqualTo("(t 3)");
	}

	@Test
	void asyncWrapperOnLambdaMayAwaitInsideItsBody() {
		Run run = evalMulti("""
				(rontolisp:async-defun inner () 10)
				(rontolisp:await (funcall (rontolisp:async (lambda (x) (+ (rontolisp:await (inner)) x))) 1))
				""");
		assertThat(run.result().print()).isEqualTo("11");
	}

	@Test
	void asyncWrapperLambdaInsidePlainDefunBodyIsLegal() {
		// the wrapper's body is an async body even when the wrapper sits nested in a
		// plain defun -- the placement check expands the sugar before walking
		Run run = evalMulti("""
				(defun make () (rontolisp:async (lambda () (rontolisp:await 7))))
				(rontolisp:await (funcall (make)))
				""");
		assertThat(run.result().print()).isEqualTo("7");
	}

	@Test
	void plainLambdaNestedInAsyncWrapperBodyIsStillRejected() {
		assertThatThrownBy(() -> evalMulti("""
				(rontolisp:async (defun outer ()
				  (mapcar (lambda (x) (rontolisp:await x)) (list 1 2))))
				(outer)
				""")).hasMessageContaining("only allowed inside");
	}

	@Test
	void asyncWrapperRejectsNonDefiningForms() {
		assertThatThrownBy(() -> eval("(rontolisp:async (+ 1 2))"))
			.hasMessageContaining("expects a single (defun ...) or (lambda ...) form");
		assertThatThrownBy(() -> eval("(rontolisp:async)"))
			.hasMessageContaining("expects a single (defun ...) or (lambda ...) form");
	}

	@Test
	void awaitInPlainDefunIsRejected() {
		assertThatThrownBy(() -> eval("(defun bad () (rontolisp:await 1))"))
			.hasMessageContaining("only allowed inside");
	}

	@Test
	void awaitInPlainLambdaIsRejected() {
		assertThatThrownBy(() -> eval("(lambda () (rontolisp:await 1))")).hasMessageContaining("only allowed inside");
	}

	@Test
	void awaitInPlainLambdaNestedInAsyncBodyIsRejected() {
		// the JavaScript rule: an inner plain function does not inherit awaitability
		assertThatThrownBy(() -> evalMulti("""
				(rontolisp:async-defun outer ()
				  (mapcar (lambda (x) (rontolisp:await x)) (list 1 2)))
				(outer)
				""")).hasMessageContaining("only allowed inside");
	}

	@Test
	void nestedAsyncLambdaInsideAsyncBodyIsLegal() {
		Run run = evalMulti("""
				(rontolisp:async-defun outer ()
				  (rontolisp:await (funcall (rontolisp:async-lambda () (rontolisp:await 5)))))
				(rontolisp:await (outer))
				""");
		assertThat(run.result()).isEqualTo(new LispInteger(5));
	}

	@Test
	void unwindProtectCleanupRunsAcrossSuspension() {
		Run run = evalMulti("""
				(defvar *s* (rontolisp:make-stream))
				(defvar *log* "")
				(rontolisp:async-defun guarded ()
				  (unwind-protect
				      (progn
				        (setq *log* (concatenate 'string *log* "body"))
				        (rontolisp:await (rontolisp:stream-read *s*))
				        (setq *log* (concatenate 'string *log* "+resumed")))
				    (setq *log* (concatenate 'string *log* "+cleanup"))))
				(defvar *f* (guarded))
				(rontolisp:stream-write *s* "x")
				(rontolisp:await *f*)
				*log*
				""");
		assertThat(run.result()).isEqualTo(new LispString("body+resumed+cleanup"));
	}

	@Test
	void handlerCaseCatchesAcrossSuspension() {
		Run run = evalMulti("""
				(defvar *s* (rontolisp:make-stream))
				(rontolisp:async-defun guarded ()
				  (handler-case
				      (progn
				        (rontolisp:await (rontolisp:stream-read *s*))
				        (error "after-resume"))
				    (error (e) "caught-after-resume")))
				(defvar *f* (guarded))
				(rontolisp:stream-write *s* "x")
				(rontolisp:await *f*)
				""");
		assertThat(run.result()).isEqualTo(new LispString("caught-after-resume"));
	}

	@Test
	void streamsCarryChunksAndSignalEndOfStream() {
		Run run = evalMulti("""
				(defvar *s* (rontolisp:make-stream))
				(rontolisp:stream-write *s* "a")
				(rontolisp:stream-write *s* "b")
				(rontolisp:stream-close *s*)
				(list (rontolisp:await (rontolisp:stream-read *s*))
				      (rontolisp:await (rontolisp:stream-read *s*))
				      (rontolisp:await (rontolisp:stream-read *s*)))
				""");
		assertThat(run.result().print()).isEqualTo("(\"a\" \"b\" nil)");
	}

	@Test
	void readAllDrainsAStream() {
		Run run = evalMulti("""
				(defvar *s* (rontolisp:make-stream))
				(rontolisp:stream-write *s* "hello ")
				(rontolisp:stream-write *s* "world")
				(rontolisp:stream-close *s*)
				(rontolisp:await (rontolisp:read-all *s*))
				""");
		assertThat(run.result()).isEqualTo(new LispString("hello world"));
	}

	@Test
	void writeToClosedStreamSignals() {
		assertThatThrownBy(() -> evalMulti("""
				(defvar *s* (rontolisp:make-stream))
				(rontolisp:stream-close *s*)
				(rontolisp:stream-write *s* "late")
				""")).hasMessageContaining("closed");
		assertThatThrownBy(() -> evalMulti("""
				(defvar *s* (rontolisp:make-stream))
				(rontolisp:stream-write *s* nil)
				""")).hasMessageContaining("nil");
	}

	@Test
	void streampDistinguishesAsyncStreams() {
		Run run = evalMulti("""
				(list (rontolisp:streamp (rontolisp:make-stream))
				      (rontolisp:streamp 42)
				      (rontolisp:futurep (rontolisp:make-stream))
				      (rontolisp:futurep (rontolisp:stream-read (rontolisp:make-stream))))
				""");
		assertThat(run.result().print()).isEqualTo("(t nil nil t)");
	}

	@Test
	void twoAsyncBodiesProgressConcurrently() {
		// ping suspends on s2 after writing to s1; pong drains s1 and feeds s2 --
		// without real concurrency after the first suspension this rendezvous deadlocks
		Run run = evalMulti("""
				(defvar *s1* (rontolisp:make-stream))
				(defvar *s2* (rontolisp:make-stream))
				(rontolisp:async-defun ping ()
				  (rontolisp:stream-write *s1* "ping")
				  (rontolisp:await (rontolisp:stream-read *s2*)))
				(rontolisp:async-defun pong ()
				  (let ((got (rontolisp:await (rontolisp:stream-read *s1*))))
				    (rontolisp:stream-write *s2* (concatenate 'string got "-pong"))
				    got))
				(let ((f1 (ping)) (f2 (pong)))
				  (list (rontolisp:await f1) (rontolisp:await f2)))
				""");
		assertThat(run.result().print()).isEqualTo("(\"ping-pong\" \"ping\")");
	}

	@Test
	void asyncLambdaClosesOverItsEnvironment() {
		Run run = evalMulti("""
				(defun make-adder (n) (rontolisp:async-lambda (x) (+ x n)))
				(rontolisp:await (funcall (make-adder 10) 32))
				""");
		assertThat(run.result()).isEqualTo(new LispInteger(42));
	}

	@Test
	void fetchReturnsALispFuture() {
		// shape only (no network): a refused connection still returns a future
		assertThat(eval("(rontolisp:fetch \"http://127.0.0.1:1/x\")")).isInstanceOf(LispFuture.class);
	}

	@Test
	void waitForReturnsAFutureSettlingToNil() {
		Run run = evalMulti("""
				(let ((f (rontolisp:wait-for 10)))
				  (list (rontolisp:futurep f) (rontolisp:await f)))
				""");
		assertThat(run.result().print()).isEqualTo("(t nil)");
	}

	@Test
	void waitForTimersCompleteInDelayOrderNotStartOrder() {
		// slow starts first but fast's shorter timer fires first -- the timers run
		// concurrently, so total wall time is ~max, not sum (not asserted; ordering is)
		Run run = evalMulti("""
				(defvar *log* nil)
				(rontolisp:async-defun slow () (rontolisp:await (rontolisp:wait-for 300)) (push "slow" *log*))
				(rontolisp:async-defun fast () (rontolisp:await (rontolisp:wait-for 30)) (push "fast" *log*))
				(let ((s (slow)) (f (fast)))
				  (rontolisp:await s)
				  (rontolisp:await f))
				(reverse *log*)
				""");
		assertThat(run.result().print()).isEqualTo("(\"fast\" \"slow\")");
	}

	@Test
	void waitForRejectsNegativeAndNonIntegerArguments() {
		assertThatThrownBy(() -> eval("(rontolisp:wait-for -1)"))
			.hasMessageContaining("wait-for expects a non-negative integer of milliseconds");
		assertThatThrownBy(() -> eval("(rontolisp:wait-for \"x\")"))
			.hasMessageContaining("wait-for expects a non-negative integer of milliseconds");
	}

}
