package am.ik.rontolisp.testsupport;

/**
 * The {@code java:} programs the interpreter's {@code JavaInteropTest} and the JVM
 * backend's {@code JvmJavaInteropCompilerTest} both run, with the output both must print:
 * one text, so the two cannot drift apart.
 */
public final class JavaInteropPrograms {

	/**
	 * Dispatched sites meeting every kind of argument; prints {@link #DISPATCH_OUTPUT}.
	 */
	public static final String DISPATCH_PROGRAM = """
			(defun mx (x y) (java:static "java.lang.Math" "max" x y))
			(defun val (x) (java:static "java.lang.String" "valueOf" x))
			(defun two (a b) (java:static "java.lang.String" "format" "%s/%s" a b))
			(defun mk (x) (java:call (java:new "java.lang.StringBuilder" x) "toString"))
			(defun app (sb s)
			  (declare (type (java:object "java.lang.StringBuilder") sb)
			           (type (java:object "java.lang.String") s))
			  (java:call sb "append" s))
			(print (list (mx 1 2) (mx 1 2.5) (mx #\\a 3)))
			(print (mapcar #'val (list 5 2.5 t nil #\\a "s" "str" (list #\\a #\\b) (vector #\\c #\\d))))
			(print (list (two 1 "x") (two (list 1 2) 2.5)))
			(print (list (mk "ab") (mk 16) (val (make-string 2 :initial-element #\\x))))
			(let ((sb (java:new "java.lang.StringBuilder")))
			  (app sb "ab") (app sb "c") (app sb nil)
			  (print (java:call sb "toString")))
			""";

	/**
	 * Dispatched sites meeting sequences and functions: nested lists and vectors to an
	 * {@code Object[]}, a fixnum vector to an {@code int[]}, a function value to an
	 * interface (a proxy); prints {@link #SEQUENCE_DISPATCH_OUTPUT}.
	 */
	public static final String SEQUENCE_DISPATCH_PROGRAM = """
			(defun deep (x) (java:static "java.util.Arrays" "deepToString" x))
			(defun ts (x) (java:static "java.util.Arrays" "toString" x))
			(defun each (l f)
			  (declare (type (java:object "java.util.List") l))
			  (java:call l "forEach" f))
			(print (deep (list (list 1 2) (vector 3 "a") nil (make-string 1 :initial-element #\\y))))
			(print (ts (make-array 3 :element-type 'fixnum :initial-contents '(1 2 3))))
			(print (ts (vector 1.5 2)))
			(each (java:static "java.util.List" "of" 1 2) (lambda (m x) (print (list m x))))
			""";

	/** What {@link #SEQUENCE_DISPATCH_PROGRAM} prints. */
	public static final String SEQUENCE_DISPATCH_OUTPUT = """
			"[[1, 2], [3, a], null, y]"
			"[1, 2, 3]"
			"[1.5, 2.0]"
			("accept" 1)
			("accept" 2)""";

	/** What {@link #DISPATCH_PROGRAM} prints. */
	public static final String DISPATCH_OUTPUT = """
			(2 2.5 97)
			("5" "2.5" "true" "false" "a" "s" "str" "ab" "cd")
			("1/x" "[1, 2]/2.5")
			("ab" "" "xx")
			"abcfalse\"""";

	/**
	 * A dispatched call on a receiver declared a {@code Collection}: prints
	 * {@code (T "[10, 20]")}.
	 */
	public static final String UPPER_BOUND_DISPATCH = """
			(defun drop (c x)
			  (declare (type (java:object "java.util.Collection") c))
			  (java:call c "remove" x))
			(let ((a (java:new "java.util.ArrayList")))
			  (dolist (x (list 10 20 1)) (java:call a "add" x))
			  (print (list (drop a 1) (java:call a "toString"))))
			""";

	private JavaInteropPrograms() {
	}

}
