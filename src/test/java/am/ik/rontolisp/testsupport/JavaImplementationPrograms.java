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

}
