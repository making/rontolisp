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

	/**
	 * Lisp values where a host object is expected -- at a site left to run time
	 * ({@code size}), a dispatched one ({@code shown}), receivers declared a
	 * {@code Collection} / {@code Map}, a falsely declared argument -- and
	 * {@code BigInteger} results: every Lisp value is refused and shown as {@code prin1}
	 * shows it, a host list and map are called, and a {@code BigInteger} is a Lisp
	 * integer. Prints {@link #HOST_OBJECT_OUTPUT}.
	 */
	public static final String HOST_OBJECT_PROGRAM = """
			(defun row (thunk)
			  (handler-case (prin1 (funcall thunk)) (error (e) (princ e)))
			  (terpri))
			(defun size (x) (java:call x "size"))
			(defun sized (x)
			  (declare (type (java:object "java.util.Collection") x))
			  (java:call x "size"))
			(defun entries (m)
			  (declare (type (java:object "java.util.Map") m))
			  (java:call m "size"))
			(defun shown (x) (java:static "java.util.Objects" "toString" x))
			(defun whole (x) (java:call x "toBigInteger"))
			(dolist (x (list (list 1 2) 1.0e10 (expt 2 100) 1/3 (complex 1 2)
			                 (make-array 2 :initial-element 0) (make-array 1 :fill-pointer 0)
			                 (make-array 2 :element-type 'double-float :initial-element 1d0)
			                 (make-hash-table)))
			  (row (lambda () (size x)))
			  (row (lambda () (shown x))))
			(row (lambda () (sized (vector 1 2))))
			(row (lambda () (entries (make-hash-table))))
			(let ((l (java:new "java.util.ArrayList"))
			      (m (java:new "java.util.LinkedHashMap")))
			  (java:call l "add" 1)
			  (java:call m "put" "k" 1)
			  (row (lambda () (list (size l) (sized l) (size m) (entries m) (shown l) (shown m)))))
			(row (lambda () (java:static "java.lang.String" "valueOf"
			                                 (the (java:object "java.util.LinkedHashMap" :exact) (make-hash-table)))))
			(let ((b (java:new "java.math.BigInteger" "123456789012345678901234567890"))
			      (s (java:static "java.math.BigInteger" "valueOf" 5)))
			  (row (lambda () (list b (+ b 1) s (eql s 5) (typep s 'fixnum)
			                        (java:call (java:new "java.math.BigDecimal" "1.5") "toBigInteger")
			                        (whole (java:new "java.math.BigDecimal" "2.5")))))
			  (row (lambda () (size s))))
			""";

	/** What {@link #HOST_OBJECT_PROGRAM} prints. */
	public static final String HOST_OBJECT_OUTPUT = """
			java:call expects a java object as the first argument, got (1 2)
			"[1, 2]"
			java:call expects a java object as the first argument, got 1.0e10
			"1.0E10"
			java:call expects a java object as the first argument, got 1267650600228229401496703205376
			No matching method java.util.Objects.toString with 1 argument(s)
			java:call expects a java object as the first argument, got 1/3
			No matching method java.util.Objects.toString with 1 argument(s)
			java:call expects a java object as the first argument, got #C(1 2)
			No matching method java.util.Objects.toString with 1 argument(s)
			java:call expects a java object as the first argument, got #(0 0)
			"[0, 0]"
			java:call expects a java object as the first argument, got #()
			"[]"
			java:call expects a java object as the first argument, got #d(1.0 1.0)
			No matching method java.util.Objects.toString with 1 argument(s)
			java:call expects a java object as the first argument, got #<HASH-TABLE :TEST EQUAL :COUNT 0>
			No matching method java.util.Objects.toString with 1 argument(s)
			java:call expects a java object as the first argument, got #(1 2)
			java:call expects a java object as the first argument, got #<HASH-TABLE :TEST EQUAL :COUNT 0>
			(1 1 1 1 "[1]" "{k=1}")
			java:static: argument 1 is not a java.util.LinkedHashMap, got #<HASH-TABLE :TEST EQUAL :COUNT 0>
			(123456789012345678901234567890 123456789012345678901234567891 5 T T 1 2)
			java:call expects a java object as the first argument, got 5""";

	private JavaInteropPrograms() {
	}

}
