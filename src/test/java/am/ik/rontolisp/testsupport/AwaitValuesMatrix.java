package am.ik.rontolisp.testsupport;

/**
 * One program, pinned identically on the interpreter, the JVM, Preview 1 and the WASI
 * component: an async body's values reach its awaiter the way a function call's do -- the
 * future settles with the full value list and {@code rontolisp:await} answers them as
 * multiple values ({@code .kb/async-await.md}, "Multiple values").
 *
 * <p>
 * The backends share the text because each settles and resolves a future in its own
 * runtime, and one backend dropping the extra values is exactly the bug this pins. The
 * {@code await-answers-every-value-of-the-async-body} {@code ci-spec.yaml} case runs
 * {@link #PROGRAM} through the CLI on every backend.
 */
public final class AwaitValuesMatrix {

	private AwaitValuesMatrix() {
	}

	/**
	 * The program: literal, zero, syntactic-producer, called, nested, tail-awaited and
	 * stale-publish bodies; a consumer inside an async body; an async-lambda; a
	 * non-future operand; the call's own single value; values surviving an unrelated
	 * publish between the call and the await, and a second await of one future.
	 */
	public static final String PROGRAM = """
			(defun awv-plain () (values 4 5 6))
			(rontolisp:async-defun awv-three () (values 1 2 3))
			(rontolisp:async-defun awv-zero () (values))
			(rontolisp:async-defun awv-floor (n) (floor n 2))
			(rontolisp:async-defun awv-call () (awv-plain))
			(rontolisp:async-defun awv-nested () (awv-three))
			(rontolisp:async-defun awv-tail-await () (rontolisp:await (awv-three)))
			(rontolisp:async-defun awv-single () (awv-plain) 9)
			(rontolisp:async-defun awv-inner ()
			  (multiple-value-bind (a b c) (rontolisp:await (awv-call)) (list c b a)))
			(print (multiple-value-list (rontolisp:await (awv-three))))
			(print (multiple-value-list (rontolisp:await (awv-zero))))
			(print (multiple-value-list (rontolisp:await (awv-floor 7))))
			(print (multiple-value-list (rontolisp:await (awv-call))))
			(print (multiple-value-list (rontolisp:await (awv-nested))))
			(print (multiple-value-list (rontolisp:await (awv-tail-await))))
			(print (multiple-value-list (rontolisp:await (awv-single))))
			(print (rontolisp:await (awv-inner)))
			(print (multiple-value-list
			        (rontolisp:await (funcall (rontolisp:async-lambda (x) (values x (* x x))) 5))))
			(print (multiple-value-list (rontolisp:await 5)))
			(print (length (multiple-value-list (awv-three))))
			(let ((f (awv-three)))
			  (awv-plain)
			  (print (multiple-value-list (rontolisp:await f)))
			  (print (multiple-value-list (rontolisp:await f))))
			(multiple-value-bind (q r) (rontolisp:await (awv-floor 9)) (print (list q r)))
			(print (nth-value 2 (rontolisp:await (awv-three))))
			""";

	/** The printed lines of {@link #PROGRAM}. */
	public static final String EXPECTED = """
			(1 2 3)
			NIL
			(3 1)
			(4 5 6)
			(1 2 3)
			(1 2 3)
			(9)
			(6 5 4)
			(5 25)
			(5)
			1
			(1 2 3)
			(1 2 3)
			(4 1)
			3""";

	/**
	 * The suspending half (Preview 1 cannot suspend and has no {@code wait-for}): the
	 * values are captured where each body completes -- after its suspension, with the
	 * other body pending -- not read back from the channel by the awaiter.
	 */
	public static final String SUSPENDING_PROGRAM = """
			(rontolisp:async-defun awv-late (n)
			  (rontolisp:await (rontolisp:wait-for 20))
			  (values n (1+ n)))
			(let ((a (awv-late 1)) (b (awv-late 10)))
			  (print (multiple-value-list (rontolisp:await b)))
			  (print (multiple-value-list (rontolisp:await a))))
			""";

	/** The printed lines of {@link #SUSPENDING_PROGRAM}. */
	public static final String SUSPENDING_EXPECTED = """
			(10 11)
			(1 2)""";

	/**
	 * Sixty bodies in flight at once, each consuming a function's values two hundred
	 * times and answering three values of its own. On the interpreter and the JVM they
	 * run in parallel, and the multiple-value channel is per thread: through one shared
	 * register a sibling's step cleared the values between a callee's publish and its
	 * consumer's read ({@code Expected integer, got: NIL} on both, every run), and a
	 * body's own values between its tail and the capture.
	 */
	public static final String CONCURRENT_PROGRAM = """
			(defun awv-helper (n) (values n (* n n) (- n)))
			(rontolisp:async-defun awv-busy (n)
			  (rontolisp:await (rontolisp:wait-for (mod (* n 7) 13)))
			  (let ((acc 0))
			    (dotimes (i 200)
			      (multiple-value-bind (a b c) (awv-helper (+ n i))
			        (setq acc (+ acc (- (+ a b c) (* (+ n i) (+ n i)))))))
			    (values n (* n n) acc)))
			(let ((futures nil) (bad 0))
			  (dotimes (n 60) (push (awv-busy n) futures))
			  (setq futures (reverse futures))
			  (dotimes (n 60)
			    (unless (equal (multiple-value-list (rontolisp:await (nth n futures))) (list n (* n n) 0))
			      (setq bad (1+ bad))))
			  (print (list :bad bad)))
			""";

	/** The printed line of {@link #CONCURRENT_PROGRAM}. */
	public static final String CONCURRENT_EXPECTED = "(:BAD 0)";

}
