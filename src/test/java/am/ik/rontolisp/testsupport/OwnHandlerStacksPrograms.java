package am.ik.rontolisp.testsupport;

/**
 * The programs pinning that no thread reaches another's handler and restart stacks, which
 * the interpreter's and the JVM's tests share. Each is deterministic: the other thread
 * pushes between a thread's push and its restore, which through one global loses what it
 * pushed.
 */
public final class OwnHandlerStacksPrograms {

	/** What {@link #thread} prints. */
	public static final String THREAD_OUTPUT = ":HANDLED";

	/** A thread {@code make-thread} starts. */
	public static final String[] MAKE_THREAD = { "(rontolisp:make-thread #'worker)", "(rontolisp:join-thread th)" };

	/**
	 * A thread Java starts, on which the program's code runs without any entry of ours.
	 */
	public static final String[] JAVA_THREAD = {
			"(let ((j (java:new \"java.lang.Thread\" (lambda (m) (declare (ignore m)) (worker))))) (java:call j \"start\") j)",
			"(java:call th \"join\")" };

	/**
	 * An async body, past its first suspension: an await of a timer that has already
	 * fired, which still ends the eager part. A loaded box let the timer fire before the
	 * await, and the body ran on as its caller's call, waiting for that caller forever.
	 */
	public static final String[] ASYNC_BODY = { """
			(let ((tick (rontolisp:wait-for 1)))
			  (loop until (rontolisp::%future-settled-p tick) do (sleep 0.001))
			  (funcall (rontolisp:async-lambda () (rontolisp:await tick) (worker))))""", "(rontolisp:await th)" };

	/**
	 * What {@link #SERVED} answers on {@code /second}, whichever request's stack is left.
	 */
	public static final String SERVED_OUTPUT = "HANDLED";

	/**
	 * Two requests: {@code /first} holds a {@code handler-bind} until {@code /second} has
	 * pushed its own, then leaves it, and {@code /second} signals into what it pushed.
	 * The caller appends its {@code http-handler} directive.
	 */
	public static final String SERVED = """
			(defvar *phase* 0)
			(define-condition own-stack (error) ())
			(defun second-request ()
			  (loop until (= *phase* 1) do (sleep 0.01))
			  (handler-bind ((own-stack (lambda (e) (declare (ignore e)) (invoke-restart 'go-on))))
			    (setq *phase* 2)
			    (loop until (= *phase* 3) do (sleep 0.01))
			    (with-simple-restart (go-on "Go on.") (error 'own-stack))
			    "HANDLED"))
			(defun handle (env)
			  (if (string= (getf env :path-info) "/first")
			      (progn
			        (handler-bind ((own-stack #'identity))
			          (setq *phase* 1)
			          (loop until (= *phase* 2) do (sleep 0.01)))
			        (setq *phase* 3)
			        (list 200 nil (list "first")))
			      (list 200 nil (list (second-request)))))
			""";

	/**
	 * What an async body keeps across a suspension, on the backends where it can suspend
	 * (the interpreter, the JVM, the component; Preview 1 has no {@code wait-for}): the
	 * stacks and {@code handler-case}s its call saw, what it pushes out of anyone else's
	 * reach, and a handler's exit reaching a frame its awaiter is in. A holder signals on
	 * the turn its caller hands out, not after a timer: on the interpreter and the JVM it
	 * resumes in parallel with its caller, so a timer raced the caller's read and the
	 * other holder's {@code push}. Last, an await of a timer an await has already
	 * resolved stays in the eager part, so the body's exit leaves the call.
	 */
	public static final String SUSPENDING = """
			(define-condition own-c (error) ())
			(rontolisp:async-defun own-late-signal ()
			  (rontolisp:await (rontolisp:wait-for 1))
			  (signal 'own-c)
			  (print :declined)
			  3)
			(print (let ((seen nil))
			         (handler-bind ((own-c (lambda (e) (declare (ignore e)) (setq seen t))))
			           (list (rontolisp:await (own-late-signal)) seen))))
			(print (handler-case (rontolisp:await (own-late-signal)) (own-c () :caught)))
			(print (let* ((seen nil)
			              (f (handler-bind ((own-c (lambda (e) (declare (ignore e)) (setq seen t))))
			                   (own-late-signal))))
			         (list (rontolisp:await f) seen)))
			(defvar *own-seen* nil)
			(defvar *own-turn* 0)
			(rontolisp:async-defun own-holder (n)
			  (handler-bind ((own-c (lambda (e) (declare (ignore e)) (push n *own-seen*))))
			    (loop until (= *own-turn* n) do (rontolisp:await (rontolisp:wait-for 1)))
			    (signal 'own-c)
			    (setq *own-turn* (1+ n))
			    (rontolisp:await (rontolisp:wait-for n))
			    n))
			(let ((a (own-holder 2)) (b (own-holder 3)))
			  (setq *own-turn* 2)
			  (print (list (rontolisp:await a) (rontolisp:await b) (sort (copy-list *own-seen*) #'<))))
			(setq *own-seen* nil *own-turn* 0)
			(let ((f (own-holder 2)))
			  (signal 'own-c)
			  (print *own-seen*)
			  (setq *own-turn* 2)
			  (rontolisp:await f)
			  (print *own-seen*))
			(setq *own-turn* 0)
			(let ((f (own-holder 2)))
			  (print (handler-bind ((own-c (lambda (e) (declare (ignore e)) (invoke-restart 'own-r))))
			           (setq *own-turn* 2)
			           (rontolisp:await f)
			           (with-simple-restart (own-r "R.") (error 'own-c))
			           :handled)))
			(rontolisp:async-defun own-return ()
			  (block out
			    (handler-bind ((own-c (lambda (e) (declare (ignore e)) (return-from out :left))))
			      (rontolisp:await (own-late-signal))
			      :stayed)))
			(print (rontolisp:await (own-return)))
			(rontolisp:async-defun own-throw ()
			  (catch 'own-tag
			    (handler-bind ((own-c (lambda (e) (declare (ignore e)) (throw 'own-tag :thrown))))
			      (rontolisp:await (own-late-signal))
			      :stayed)))
			(print (rontolisp:await (own-throw)))
			(rontolisp:async-defun own-invoke ()
			  (with-simple-restart (own-r "R.")
			    (handler-bind ((own-c (lambda (e) (declare (ignore e)) (invoke-restart 'own-r))))
			      (rontolisp:await (own-late-signal))
			      :stayed)))
			(print (multiple-value-list (rontolisp:await (own-invoke))))
			(print (let ((tick (rontolisp:wait-for 1)))
			         (rontolisp:await tick)
			         (block out
			           (funcall (rontolisp:async-lambda () (rontolisp:await tick) (return-from out :left)))
			           :stayed)))
			""";

	/** The printed lines of {@link #SUSPENDING}. */
	public static final String SUSPENDING_OUTPUT = """
			:DECLINED
			(3 T)
			:CAUGHT
			:DECLINED
			(3 T)
			(2 3 (2 3))
			NIL
			(2)
			:HANDLED
			:LEFT
			:THROWN
			(NIL T)
			:LEFT""";

	private OwnHandlerStacksPrograms() {
	}

	/**
	 * A worker on a thread of its own holds a {@code handler-bind} while the main thread
	 * pushes its own, then leaves it, and the main thread signals into what it pushed.
	 * @param shape how the worker's thread starts and ends: {@link #MAKE_THREAD},
	 * {@link #JAVA_THREAD}, {@link #ASYNC_BODY}
	 * @return the program, printing {@link #THREAD_OUTPUT}
	 */
	public static String thread(String[] shape) {
		return """
				(defvar *phase* 0)
				(define-condition own-stack (error) ())
				(defun worker ()
				  (handler-bind ((own-stack #'identity))
				    (setq *phase* 1)
				    (loop until (= *phase* 2) do (sleep 0.01))))
				(let ((th %s))
				  (loop until (= *phase* 1) do (sleep 0.01))
				  (print (handler-bind ((own-stack (lambda (e) (declare (ignore e)) (invoke-restart 'go-on))))
				           (setq *phase* 2)
				           %s
				           (with-simple-restart (go-on "Go on.") (error 'own-stack))
				           :handled)))
				""".formatted(shape[0], shape[1]);
	}

}
