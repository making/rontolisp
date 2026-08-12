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
		assertThat(run.result().print()).isEqualTo("(T 3 3)");
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
		assertThat(eval("(rontolisp:await nil)").print()).isEqualTo("NIL");
		assertThatThrownBy(() -> eval("(rontolisp:await)")).hasMessageContaining("AWAIT");
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
		assertThat(run.result().print()).isEqualTo("(T 3)");
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
		assertThat(run.result().print()).isEqualTo("(\"a\" \"b\" NIL)");
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
	void readAllPassesAStringThrough() {
		// A body that has fully arrived (a --host-fetch reactor's :body, or the
		// declared absent-body default "") is its own drained value, on every backend.
		Run run = evalMulti("(rontolisp:await (rontolisp:read-all \"already here\"))");
		assertThat(run.result()).isEqualTo(new LispString("already here"));
	}

	@Test
	void futureForceResolvesAFutureFromSynchronousCode() {
		// The FUNCTION spelling of await's resolve: legal in a plain defun, which is
		// where the http-reactor transport resolves a future-valued application answer.
		Run run = evalMulti("""
				(rontolisp:async-defun answer () 41)
				(defun sync-caller () (+ 1 (rontolisp::%future-force (answer))))
				(sync-caller)
				""");
		assertThat(run.result().print()).isEqualTo("42");
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
		assertThat(run.result().print()).isEqualTo("(T NIL NIL T)");
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
		assertThat(run.result().print()).isEqualTo("(T NIL)");
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
			.hasMessageContaining("WAIT-FOR expects a non-negative integer of milliseconds");
		assertThatThrownBy(() -> eval("(rontolisp:wait-for \"x\")"))
			.hasMessageContaining("WAIT-FOR expects a non-negative integer of milliseconds");
	}

	// ---------- Future-as-value combinators: then / then* / catch / finally ----------

	@Test
	void thenChainsOnFutureSettledValue() {
		Run run = evalMulti("""
				(rontolisp:async-defun produce () 21)
				(let ((chained (rontolisp:then (produce) (lambda (v) (* v 2)))))
				  (list (rontolisp:futurep chained) (rontolisp:await chained)))
				""");
		assertThat(run.result().print()).isEqualTo("(T 42)");
	}

	@Test
	void thenPropagatesUpstreamConditionUnchanged() {
		// on error the callback is skipped and the condition rides through the fresh
		// future; handler-case around the outer await catches it by type
		Run run = evalMulti("""
				(rontolisp:async-defun failing () (error "boom"))
				(handler-case
				    (rontolisp:await (rontolisp:then (failing) (lambda (v) (list :should-not-see v))))
				  (error (c) :caught))
				""");
		assertThat(run.result().print()).isEqualTo(":CAUGHT");
	}

	@Test
	void thenStarVariadicChainsAcrossStages() {
		Run run = evalMulti("""
				(rontolisp:async-defun produce () 40)
				(rontolisp:await
				  (rontolisp:then* (produce) (lambda (v) (+ v 1)) #'1+))
				""");
		assertThat(run.result()).isEqualTo(new LispInteger(42));
	}

	@Test
	void thenStarNoCallbacksReturnsInputFutureUnchanged() {
		// documented degenerate identity: zero callbacks = the input future carried
		// through; award-once semantics of the original future are preserved
		Run run = evalMulti("""
				(rontolisp:async-defun produce () 7)
				(let* ((f (produce))
				       (g (rontolisp:then* f)))
				  (list (eq f g) (rontolisp:await g)))
				""");
		assertThat(run.result().print()).isEqualTo("(T 7)");
	}

	@Test
	void thenStarStageMayReturnAFutureWhichIsFlattened() {
		// a stage returning a future is auto-flattened by await on the next stage; the
		// caller never observes future<future<T>>
		Run run = evalMulti("""
				(rontolisp:async-defun add-one (n) (+ n 1))
				(rontolisp:async-defun produce () 10)
				(rontolisp:await
				  (rontolisp:then* (produce) #'add-one #'add-one))
				""");
		assertThat(run.result()).isEqualTo(new LispInteger(12));
	}

	@Test
	void catchRunsOnlyWhenUpstreamSignals() {
		Run run = evalMulti("""
				(rontolisp:async-defun boom () (error "nope"))
				(rontolisp:await (rontolisp:catch (boom) (lambda (c) (declare (ignore c)) :fallback)))
				""");
		assertThat(run.result().print()).isEqualTo(":FALLBACK");
	}

	@Test
	void catchPassesUpstreamValueThroughOnSuccess() {
		Run run = evalMulti("""
				(rontolisp:async-defun ok () 99)
				(rontolisp:await
				  (rontolisp:catch (ok) (lambda (c) (declare (ignore c)) :should-not-see)))
				""");
		assertThat(run.result()).isEqualTo(new LispInteger(99));
	}

	@Test
	void catchHandlerReceivesTheCondition() {
		Run run = evalMulti("""
				(define-condition my-err (error) ((v :initarg :v :reader my-err-v)))
				(rontolisp:async-defun failing () (error 'my-err :v 7))
				(rontolisp:await
				  (rontolisp:catch (failing) (lambda (c) (list :caught (my-err-v c)))))
				""");
		assertThat(run.result().print()).isEqualTo("(:CAUGHT 7)");
	}

	@Test
	void catchHandlerErrorReplacesOutcome() {
		Run run = evalMulti("""
				(rontolisp:async-defun failing () (error "first"))
				(handler-case
				    (rontolisp:await
				      (rontolisp:catch (failing) (lambda (c) (declare (ignore c)) (error "second"))))
				  (error (c) (simple-condition-format-control c)))
				""");
		assertThat(run.result().print()).isEqualTo("\"second\"");
	}

	@Test
	void finallyRunsOnSuccessPathAndPreservesValue() {
		Run run = evalMulti("""
				(defvar *fin-success-log* nil)
				(rontolisp:async-defun ok () 5)
				(let ((v (rontolisp:await
				           (rontolisp:finally (ok)
				                              (lambda () (push :done *fin-success-log*))))))
				  (list v (reverse *fin-success-log*)))
				""");
		assertThat(run.result().print()).isEqualTo("(5 (:DONE))");
	}

	@Test
	void finallyRunsOnFailurePathAndPreservesCondition() {
		// the thunk runs on the error channel too, and the original condition is what
		// the surrounding handler-case observes -- not the thunk's return value
		Run run = evalMulti("""
				(defvar *fin-failure-log* nil)
				(rontolisp:async-defun boom () (error "detonate"))
				(let ((c (handler-case
				             (rontolisp:await
				               (rontolisp:finally (boom)
				                                  (lambda () (push :cleanup *fin-failure-log*))))
				           (error (e) (simple-condition-format-control e)))))
				  (list c (reverse *fin-failure-log*)))
				""");
		assertThat(run.result().print()).isEqualTo("(\"detonate\" (:CLEANUP))");
	}

	@Test
	void finallyThunkErrorReplacesPendingOutcome() {
		// unwind-protect semantics: an exception from the cleanup wins over the
		// primary outcome; here the primary was a success, but the thunk's error rides
		Run run = evalMulti("""
				(rontolisp:async-defun ok () 5)
				(handler-case
				    (rontolisp:await
				      (rontolisp:finally (ok) (lambda () (error "cleanup-boom"))))
				  (error (c) (simple-condition-format-control c)))
				""");
		assertThat(run.result().print()).isEqualTo("\"cleanup-boom\"");
	}

	@Test
	void combinatorsReturnAFreshFutureNotTheInputExceptThenStarZeroArg() {
		// eq-identity of the returned future distinguishes the fresh futures from
		// then*'s documented degenerate identity path
		Run run = evalMulti("""
				(rontolisp:async-defun ok () 1)
				(let* ((base (ok))
				       (t1 (rontolisp:then base #'identity))
				       (t2 (rontolisp:catch base (lambda (c) c)))
				       (t3 (rontolisp:finally base (lambda () nil))))
				  (list (rontolisp:futurep t1) (eq t1 base)
				        (rontolisp:futurep t2) (eq t2 base)
				        (rontolisp:futurep t3) (eq t3 base)))
				""");
		assertThat(run.result().print()).isEqualTo("(T NIL T NIL T NIL)");
	}

	@Test
	void combinatorsRejectNonFutureFirstArgument() {
		// the design decision: no JS-style auto-coercion of arbitrary values to a
		// resolved promise -- each operator signals a clear error
		assertThatThrownBy(() -> eval("(rontolisp:then 42 #'identity)"))
			.hasMessageContaining("rontolisp:THEN expects a future");
		assertThatThrownBy(() -> eval("(rontolisp:then* 42 #'identity)"))
			.hasMessageContaining("rontolisp:THEN* expects a future");
		assertThatThrownBy(() -> eval("(rontolisp:catch 42 (lambda (c) c))"))
			.hasMessageContaining("rontolisp:CATCH expects a future");
		assertThatThrownBy(() -> eval("(rontolisp:finally 42 (lambda () nil))"))
			.hasMessageContaining("rontolisp:FINALLY expects a future");
	}

	@Test
	void combinatorsAreFirstClassAndFunctionalUnderRlNickname() {
		// rl: is a built-in nickname of rontolisp; both spellings must reach the same
		// defun (the splice / lazy-load derivation is member-name-based)
		Run run = evalMulti("""
				(rontolisp:async-defun produce () 10)
				(rontolisp:await (rl:then (produce) (lambda (v) (+ v 5))))
				""");
		assertThat(run.result()).isEqualTo(new LispInteger(15));
	}

	@Test
	void combinatorsComposeAcrossBoundaryReadable() {
		// the motivating shape from the todo: a plain defun exposes a future that a
		// caller decorates with a value combinator, without pulling the callee inside
		// its own async body
		Run run = evalMulti("""
				(rontolisp:async-defun some-future-producer () 21)
				(defun caller ()
				  (rontolisp:then (some-future-producer) (lambda (v) (* 2 v))))
				(rontolisp:await (caller))
				""");
		assertThat(run.result()).isEqualTo(new LispInteger(42));
	}

}
