package am.ik.rontolisp.testsupport;

/**
 * The {@code java:reify} / {@code java:proxy} programs the interpreter's
 * {@code JavaInteropTest} and the JVM backend's {@code JvmJavaInteropCompilerTest} both
 * run, and what each prints: one text, so the two backends are held to the same output.
 */
public final class JavaImplementationPrograms {

	private JavaImplementationPrograms() {
	}

	/**
	 * java:reify over JDK interfaces: conversions both ways, a default method, nesting.
	 */
	public static final String REIFY = """
			(let ((lst (java:new "java.util.ArrayList")))
			  (dolist (x (list 3 1 2)) (java:call lst "add" x))
			  (java:static "java.util.Collections" "sort" lst
			    (java:reify "java.util.Comparator" "compare" (lambda (a b) (- b a))))
			  (print (java:call lst "toString")))
			(let ((f (java:reify "java.util.function.Function" "apply" (lambda (x) (* x 10)))))
			  (print (java:call f "apply" 4))
			  (print (java:call (java:call f "andThen" (java:reify "java.util.function.Function" "apply" #'1+))
			                    "apply" 4))
			  (print (java:call f "toString"))
			  (print (java:call f "equals" f)))
			(let ((cs (java:reify "java.lang.CharSequence"
			            "length" (lambda () 3)
			            "charAt" (lambda (i) (char "xyz" i))
			            "subSequence" (lambda (a b) (subseq "xyz" a b))
			            "toString" (lambda () "xyz"))))
			  (print (list (java:call cs "length") (java:call cs "charAt" 1) (java:call cs "subSequence" 0 2)))
			  (print (java:call (java:new "java.lang.StringBuilder" "<") "append(CharSequence)" cs)))
			(print (java:call (java:reify "java.util.function.IntSupplier" "getAsInt" (lambda () #\\A)) "getAsInt"))
			(print (java:call (java:reify "java.util.function.DoubleSupplier" "getAsDouble" (lambda () 3)) "getAsDouble"))
			(print (java:call (java:reify "java.lang.Runnable" "run" (lambda () nil) "hashCode" (lambda () 42))
			                  "hashCode"))
			(let ((c (java:reify "java.util.Collection" "toArray()" (lambda () (list 1 "two" 3.0)))))
			  (print (java:call c "toArray()"))
			  (print (java:static "java.util.Arrays" "toString(Object[])" (java:call c "toArray()"))))
			(print (java:call (java:call (java:reify "java.util.function.Supplier" "get" (lambda () (vector 1 "x")))
			                             "get")
			                  "toString"))
			(let ((it (java:reify "java.lang.Iterable" "iterator"
			            (lambda ()
			              (let ((items (list :a :b)))
			                (java:reify "java.util.Iterator"
			                  "hasNext" (lambda () (not (null items)))
			                  "next" (lambda () (symbol-name (pop items)))))))))
			  (java:call it "forEach" (lambda (m x) (print (list m x)))))
			""";

	/** What {@link #REIFY} prints. */
	public static final String REIFY_OUTPUT = """
			"[3, 2, 1]"
			40
			41
			"#<java-reify java.util.function.Function>"
			T
			(3 #\\y "xy")
			#<java java.lang.StringBuilder>
			65
			3.0
			42
			(1 "two" 3.0)
			"[1, two, 3.0]"
			"[1, x]"
			("accept" "A")
			("accept" "B")""";

	/** java:proxy: every method, a default one too, calls the callable. */
	public static final String PROXY = """
			(let ((c (java:proxy "java.util.Comparator" (lambda (m &rest args) (if (equal m "compare") 5 nil)))))
			  (print (java:call c "compare" 1 2))
			  (print (java:call c "reversed"))
			  (print (java:call c "toString"))
			  (print (java:call c "equals" c)))
			""";

	/** What {@link #PROXY} prints. */
	public static final String PROXY_OUTPUT = """
			5
			NIL
			"#<java-proxy java.util.Comparator>"
			T""";

	/**
	 * java:proxy of several interfaces: one object Java calls through each -- a name two
	 * declare ({@code accept(Object)}, {@code accept(int)}) reaches the one callable --
	 * and a method of a class-typed receiver resolved when it runs; interface names
	 * computed at run time make the same object.
	 */
	public static final String PROXY_SEVERAL = """
			(defvar *runnable* "java.lang.Runnable")
			(let ((p (java:proxy "java.util.function.Consumer" "java.util.function.IntConsumer" "java.lang.Runnable"
			           (lambda (m &rest args) (print (cons m args))))))
			  (java:call (java:static "java.util.List" "of" "a" "b") "forEach" p)
			  (java:call (java:static "java.util.stream.IntStream" "range" 0 2) "forEach" p)
			  (java:call (java:new "java.lang.Thread" p) "run")
			  (java:call p "run")
			  (print (java:call p "toString"))
			  (print (java:call p "equals" p)))
			(let ((q (java:proxy "java.util.function.Supplier" *runnable*
			           (lambda (m &rest args) (if (equal m "get") 42 (print m))))))
			  (print (java:call q "get"))
			  (java:call q "run")
			  (print (java:call q "toString")))
			""";

	/**
	 * A java:proxy of several interfaces passed where one of them is expected: the calls
	 * resolve before they run (the object's kind is the interfaces' implementation).
	 */
	public static final String PROXY_SEVERAL_PASSED = """
			(let ((p (java:proxy "java.util.function.Consumer" "java.util.function.IntConsumer"
			           (lambda (m x) (print x)))))
			  (java:call (java:static "java.util.List" "of" "a") "forEach" p)
			  (java:call (java:static "java.util.stream.IntStream" "range" 0 1) "forEach" p))
			""";

	/** What {@link #PROXY_SEVERAL_PASSED} prints. */
	public static final String PROXY_SEVERAL_PASSED_OUTPUT = """
			"a"
			0""";

	/**
	 * A declaration that a value is what a {@code java:proxy} of one interface makes,
	 * holding a {@code java:proxy} of that interface and another: the kind is the
	 * interface list, so the declaration lies.
	 */
	public static final String FALSE_SINGLE_IMPLEMENTATION = """
			(java:new "java.lang.Thread"
			  (the (java:object "java.lang.Runnable" :exact)
			       (java:proxy "java.lang.Runnable" "java.util.function.Supplier" (lambda (m) nil))))
			""";

	/** The start of the error {@link #FALSE_SINGLE_IMPLEMENTATION} raises. */
	public static final String FALSE_SINGLE_IMPLEMENTATION_ERROR = "java:new: argument 1 is not an implementation of"
			+ " java.lang.Runnable, got #<java ";

	/** What {@link #PROXY_SEVERAL} prints. */
	public static final String PROXY_SEVERAL_OUTPUT = """
			("accept" "a")
			("accept" "b")
			("accept" 0)
			("accept" 1)
			("run")
			("run")
			"#<java-proxy java.util.function.Consumer java.util.function.IntConsumer java.lang.Runnable>"
			T
			42
			"run"
			"#<java-proxy java.util.function.Supplier java.lang.Runnable>\"""";

	/**
	 * A listener held in a {@code let} keeps its kind, so the calls passing it resolve:
	 * added, fired, removed, fired again.
	 */
	public static final String LISTENER = """
			(let ((support (java:new "java.beans.PropertyChangeSupport" "bean"))
			      (seen nil))
			  (let ((listener (java:reify "java.beans.PropertyChangeListener" "propertyChange"
			                    (lambda (e)
			                      (declare (type (java:object "java.beans.PropertyChangeEvent") e))
			                      (push (java:call e "getNewValue") seen)))))
			    (java:call support "addPropertyChangeListener" listener)
			    (java:call support "firePropertyChange" "x" 1 2)
			    (java:call support "removePropertyChangeListener" listener)
			    (java:call support "firePropertyChange" "x" 2 3)
			    (print seen)))
			""";

	/** What {@link #LISTENER} prints. */
	public static final String LISTENER_OUTPUT = "(2)";

	/**
	 * A declaration that a value is what a {@code java:reify} / {@code java:proxy} of an
	 * interface makes, which lies: the error where the value meets the call.
	 */
	public static final String FALSE_IMPLEMENTATION = """
			(java:new "java.lang.Thread" (the (java:object "java.lang.Runnable" :exact) (java:new "java.lang.Thread")))
			""";

	/** The error {@link #FALSE_IMPLEMENTATION} raises. */
	public static final String FALSE_IMPLEMENTATION_ERROR = "java:new: argument 1 is not an implementation of"
			+ " java.lang.Runnable, got #<java java.lang.Thread>";

	/**
	 * What a function called back from Java raises -- a {@code return-from}, a
	 * {@code throw}, a {@code go}, a condition of any type, a restart's transfer, a raw
	 * failure -- reaches the Lisp code that made the Java call as itself, through
	 * generated and reflective implementations, direct and run-time sites, nested calls
	 * and Java code that relays the first of two failures ({@code Stream.close}); what
	 * Java makes of it instead ({@code FutureTask.get}) is the Java call's failure, a
	 * condition Java swallowed ({@code FutureTask.run}) is no later failure's, and a
	 * plain failure Java swallows in a cleanup leaves the condition on its way out alone.
	 */
	public static final String CALLBACK_SIGNALS = """
			(define-condition callback-failed (error) ((item :initarg :item :reader callback-failed-item)))
			(defun each (coll f) (java:call coll "forEach" f))
			(defvar *consumer* "java.util.function.Consumer")
			(print (block b
			         (java:call (java:static "java.util.List" "of" 1 2 3) "forEach"
			                    (lambda (m x) (when (= x 2) (return-from b x))))
			         :none))
			(print (catch 'done
			         (java:call (java:static "java.util.List" "of" 1 2 3) "forEach"
			                    (lambda (m x) (when (= x 3) (throw 'done (* x 10)))))
			         :none))
			(print (let ((seen nil))
			         (tagbody
			            (java:call (java:static "java.util.List" "of" 1 2 3) "forEach"
			                       (lambda (m x) (push x seen) (when (= x 2) (go out))))
			          out)
			         seen))
			(print (handler-case (java:call (java:static "java.util.List" "of" 1) "forEach"
			                                (lambda (m x) (error "boom ~a" x)))
			         (error (e) (format nil "~a" e))))
			(print (handler-case (java:call (java:static "java.util.List" "of" 7) "forEach"
			                                (lambda (m x) (error 'callback-failed :item x)))
			         (callback-failed (c) (list :failed (callback-failed-item c)))))
			(print (restart-case
			           (handler-bind ((callback-failed (lambda (c) (invoke-restart 'skip (callback-failed-item c)))))
			             (java:call (java:static "java.util.List" "of" 1 "two" 3) "forEach"
			                        (lambda (m x) (unless (numberp x) (error 'callback-failed :item x)))))
			         (skip (item) (list :skipped item))))
			(print (handler-case (java:call (java:static "java.util.List" "of" 1) "forEach" (lambda (m x) (car x)))
			         (type-error () :type-error)))
			(print (handler-case (java:call (java:static "java.util.List" "of" "x") "forEach"
			                                (lambda (m s) (java:static "java.lang.Integer" "parseInt" s)))
			         (error (e) (format nil "~a" e))))
			(print (block b
			         (java:call (java:static "java.util.List" "of" 1) "forEach"
			                    (lambda (m x) (unwind-protect (return-from b :left) (print :cleanup))))))
			(print (block outer
			         (java:call (java:static "java.util.List" "of" 1) "forEach"
			                    (lambda (m x)
			                      (java:call (java:static "java.util.List" "of" 2) "forEach"
			                                 (lambda (m y) (return-from outer (list x y))))))
			         :none))
			(print (block b
			         (each (java:static "java.util.List" "of" 1 2 3) (lambda (m x) (when (= x 2) (return-from b x))))
			         :none))
			(print (catch 'tag
			         (each (java:static "java.util.List" "of" 4)
			               (java:reify "java.util.function.Consumer" "accept" (lambda (x) (throw 'tag x))))))
			(print (handler-case (java:call (java:static "java.util.List" "of" 5) "forEach"
			                                (java:reify *consumer* "accept" (lambda (x) (error 'callback-failed :item x))))
			         (callback-failed (c) (callback-failed-item c))))
			(print (block b
			         (java:call (java:call (java:call (java:static "java.util.stream.Stream" "of" 1)
			                                          "onClose" (lambda (m) (return-from b :first)))
			                               "onClose" (lambda (m) (return-from b :second)))
			                    "close")
			         :none))
			(print (handler-case
			           (java:call (java:call (java:call (java:static "java.util.stream.Stream" "of" 1)
			                                            "onClose" (lambda (m) (error 'callback-failed :item :first)))
			                                 "onClose" (lambda (m) (error 'callback-failed :item :second)))
			                      "close")
			         (callback-failed (c) (callback-failed-item c))))
			(defun failed-task (item)
			  (let ((task (java:new "java.util.concurrent.FutureTask"
			                        (java:reify "java.util.concurrent.Callable" "call"
			                                    (lambda () (error 'callback-failed :item item))))))
			    (java:call task "run")
			    task))
			(let ((task (failed-task 1)))
			  (print (handler-case (java:call task "get")
			           (callback-failed () :swallowed-condition)
			           (error (e)
			             (search "error calling java.util.concurrent.FutureTask.get: java.util.concurrent.ExecutionException: "
			                     (format nil "~a" e))))))
			(let ((task (failed-task 2)))
			  (print (handler-case (car (java:call task "isDone"))
			           (callback-failed () :swallowed-condition)
			           (type-error () :type-error))))
			(print (handler-case
			           (unwind-protect (error 'callback-failed :item :outer)
			             (java:call (java:new "java.util.concurrent.FutureTask"
			                                  (java:reify "java.util.concurrent.Callable" "call"
			                                              (lambda () (error "plain"))))
			                        "run"))
			         (callback-failed (c) (callback-failed-item c))
			         (error () :lost-its-type)))
			""";

	/** What {@link #CALLBACK_SIGNALS} prints. */
	public static final String CALLBACK_SIGNALS_OUTPUT = """
			2
			30
			(2 1)
			"boom 1"
			(:FAILED 7)
			(:SKIPPED "two")
			:TYPE-ERROR
			"error calling java.lang.Integer.parseInt: java.lang.NumberFormatException: For input string: \\"x\\""
			:CLEANUP
			:LEFT
			(1 2)
			2
			4
			5
			:FIRST
			:FIRST
			0
			:TYPE-ERROR
			:OUTER""";

}
