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

	/**
	 * A host {@code ArrayList} (holding a value, and empty) and a host
	 * {@code LinkedHashMap} beside a Lisp vector and hash table, through every type
	 * predicate, {@code typecase}, a method dispatch, {@code type-of} and every printer
	 * entry: a host collection is no Lisp array or table and prints {@code #<java C>}.
	 * Prints {@link #HOST_COLLECTION_OUTPUT}.
	 */
	public static final String HOST_COLLECTION_PROGRAM = """
			(defgeneric kind (x))
			(defmethod kind ((x hash-table)) 'table)
			(defmethod kind ((x vector)) 'vector)
			(defmethod kind (x) 'other)
			(let ((l (java:new "java.util.ArrayList"))
			      (e (java:new "java.util.ArrayList"))
			      (m (java:new "java.util.LinkedHashMap"))
			      (v (vector 1 2))
			      (h (make-hash-table)))
			  (java:call l "add" 1)
			  (java:call m "put" "k" 1)
			  (setf (gethash 'k h) m)
			  (dolist (x (list l e m v h))
			    (print (list (hash-table-p x) (vectorp x) (arrayp x) (stringp x) (typep x 'simple-vector)
			                 (typep x 'sequence) (typep x 'hash-table) (typep x '(vector t))
			                 (typecase x (hash-table 'table) (vector 'vector) (t 'other))
			                 (kind x) (type-of x))))
			  (print l) (print e) (print m)
			  (terpri) (prin1 l) (prin1 m) (princ l) (princ m)
			  (print (format nil "~a ~s ~a ~s" l l m m))
			  (print (list (prin1-to-string l) (princ-to-string m) (write-to-string e)))
			  (print (list l m v h (gethash 'k h)))
			  (print (vector l m v h)))
			""";

	/** What {@link #HOST_COLLECTION_PROGRAM} prints. */
	public static final String HOST_COLLECTION_OUTPUT = """
			(NIL NIL NIL NIL NIL NIL NIL NIL OTHER OTHER T)
			(NIL NIL NIL NIL NIL NIL NIL NIL OTHER OTHER T)
			(NIL NIL NIL NIL NIL NIL NIL NIL OTHER OTHER T)
			(NIL T T NIL T T NIL T VECTOR VECTOR (SIMPLE-VECTOR 2))
			(T NIL NIL NIL NIL NIL T NIL TABLE TABLE HASH-TABLE)
			#<java java.util.ArrayList>
			#<java java.util.ArrayList>
			#<java java.util.LinkedHashMap>

			#<java java.util.ArrayList>#<java java.util.LinkedHashMap>#<java java.util.ArrayList>#<java java.util.LinkedHashMap>\
			"#<java java.util.ArrayList> #<java java.util.ArrayList> #<java java.util.LinkedHashMap> #<java java.util.LinkedHashMap>"
			("#<java java.util.ArrayList>" "#<java java.util.LinkedHashMap>" "#<java java.util.ArrayList>")
			(#<java java.util.ArrayList> #<java java.util.LinkedHashMap> #(1 2) #<HASH-TABLE :TEST EQUAL :COUNT 1> #<java java.util.LinkedHashMap>)
			#(#<java java.util.ArrayList> #<java java.util.LinkedHashMap> #(1 2) #<HASH-TABLE :TEST EQUAL :COUNT 1>)""";

	/** What {@link #HOST_OBJECT_PROGRAM} prints. */
	public static final String HOST_OBJECT_OUTPUT = """
			java:call expects a java object as the first argument, got (1 2)
			"[1, 2]"
			java:call expects a java object as the first argument, got 1.0e10
			"1.0E10"
			java:call expects a java object as the first argument, got 1267650600228229401496703205376
			"1267650600228229401496703205376"
			java:call expects a java object as the first argument, got 1/3
			No matching method java.util.Objects.toString with 1 argument(s)
			java:call expects a java object as the first argument, got #C(1 2)
			No matching method java.util.Objects.toString with 1 argument(s)
			java:call expects a java object as the first argument, got #(0 0)
			"[0, 0]"
			java:call expects a java object as the first argument, got #()
			"[]"
			java:call expects a java object as the first argument, got #d(1.0 1.0)
			"[1.0, 1.0]"
			java:call expects a java object as the first argument, got #<HASH-TABLE :TEST EQUAL :COUNT 0>
			No matching method java.util.Objects.toString with 1 argument(s)
			java:call expects a java object as the first argument, got #(1 2)
			java:call expects a java object as the first argument, got #<HASH-TABLE :TEST EQUAL :COUNT 0>
			(1 1 1 1 "[1]" "{k=1}")
			java:static: argument 1 is not a java.util.LinkedHashMap, got #<HASH-TABLE :TEST EQUAL :COUNT 0>
			(123456789012345678901234567890 123456789012345678901234567891 5 T T 1 2)
			java:call expects a java object as the first argument, got 5""";

	/**
	 * Specialized vectors and bignums as arguments, at a dispatched site ({@code ts},
	 * {@code val}), at a site left to run time (the class name in a variable: the
	 * compiled program's bridge) and at resolved ones: every rank-1 packed float and
	 * integer vector converts element-wise like a general vector, a rank-2 one does not;
	 * a bignum is a {@code BigInteger} (or a supertype of one), a fixnum reaches a
	 * {@code BigInteger} parameter, a ratio and a bignum where a {@code double} is
	 * expected match nothing. Prints {@link #SPECIALIZED_AND_BIGNUM_OUTPUT}.
	 */
	public static final String SPECIALIZED_AND_BIGNUM_PROGRAM = """
			(defvar *arrays* "java.util.Arrays")
			(defvar *string* "java.lang.String")
			(defun ts (x) (java:static "java.util.Arrays" "toString" x))
			(defun ts* (x) (java:static *arrays* "toString" x))
			(defun val (x) (java:static "java.lang.String" "valueOf" x))
			(defun val* (x) (java:static *string* "valueOf" x))
			(defun row (thunk)
			  (handler-case (prin1 (funcall thunk)) (error (e) (princ e)))
			  (terpri))
			(dolist (x (list (make-array 3 :element-type 'double-float :initial-contents '(1d0 2d0 3.5d0))
			                 (make-array 2 :element-type 'single-float :initial-contents '(1.5 -2.0))
			                 #f(6.0)
			                 (make-array 2 :element-type 'bfloat16 :initial-contents '(1.5 -2.0))
			                 (make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(1 200 255))
			                 (make-array 2 :element-type '(unsigned-byte 16) :initial-contents '(7 65535))
			                 (make-array 2 :element-type '(unsigned-byte 32) :initial-contents '(9 65536))))
			  (row (lambda () (list (ts x) (ts* x) (val x) (val* x)))))
			(dolist (x (list (make-array '(2 2) :element-type 'double-float :initial-element 0d0)
			                 (expt 2 100) (- (expt 2 64)) 1/3))
			  (row (lambda () (ts x)))
			  (row (lambda () (ts* x)))
			  (row (lambda () (list (val x) (val* x)))))
			(row (lambda () (java:call (java:new "java.math.BigDecimal" 5 2) "toString")))
			(row (lambda () (java:call (java:new "java.math.BigDecimal" (expt 10 20) 3) "toString")))
			(row (lambda () (java:static "java.lang.String" "valueOf" 1267650600228229401496703205376)))
			(row (lambda () (java:static "java.util.Objects" "equals" (expt 2 100) (expt 2 100))))
			(row (lambda () (+ 1 (java:call (java:new "java.math.BigDecimal" (expt 2 100) 0) "toBigInteger"))))
			(row (lambda () (java:static "java.lang.Math" "sqrt" (expt 2 100))))
			(row (lambda () (java:call (java:static "java.util.List" "of" (expt 2 100) 1) "toString")))
			(row (lambda () (list (ts (list 1 (expt 2 100))) (ts* (vector (expt 2 100))))))
			""";

	/** What {@link #SPECIALIZED_AND_BIGNUM_PROGRAM} prints. */
	public static final String SPECIALIZED_AND_BIGNUM_OUTPUT = """
			("[1.0, 2.0, 3.5]" "[1.0, 2.0, 3.5]" "[1.0, 2.0, 3.5]" "[1.0, 2.0, 3.5]")
			("[1.5, -2.0]" "[1.5, -2.0]" "[1.5, -2.0]" "[1.5, -2.0]")
			("[6.0]" "[6.0]" "[6.0]" "[6.0]")
			("[1.5, -2.0]" "[1.5, -2.0]" "[1.5, -2.0]" "[1.5, -2.0]")
			("[1, 200, 255]" "[1, 200, 255]" "[1, 200, 255]" "[1, 200, 255]")
			("[7, 65535]" "[7, 65535]" "[7, 65535]" "[7, 65535]")
			("[9, 65536]" "[9, 65536]" "[9, 65536]" "[9, 65536]")
			No matching method java.util.Arrays.toString with 1 argument(s)
			No matching method java.util.Arrays.toString with 1 argument(s)
			No matching method java.lang.String.valueOf with 1 argument(s)
			No matching method java.util.Arrays.toString with 1 argument(s)
			No matching method java.util.Arrays.toString with 1 argument(s)
			("1267650600228229401496703205376" "1267650600228229401496703205376")
			No matching method java.util.Arrays.toString with 1 argument(s)
			No matching method java.util.Arrays.toString with 1 argument(s)
			("-18446744073709551616" "-18446744073709551616")
			No matching method java.util.Arrays.toString with 1 argument(s)
			No matching method java.util.Arrays.toString with 1 argument(s)
			No matching method java.lang.String.valueOf with 1 argument(s)
			"0.05"
			"100000000000000000.000"
			"1267650600228229401496703205376"
			T
			1267650600228229401496703205377
			No matching method java.lang.Math.sqrt with 1 argument(s)
			"[1267650600228229401496703205376, 1]"
			("[1, 1267650600228229401496703205376]" "[1267650600228229401496703205376]")""";

	/**
	 * {@code Object}'s methods called on a receiver whose static class is an interface --
	 * one declared a {@code List}, which redeclares only {@code equals} and
	 * {@code hashCode}, and a {@code java:reify} of {@code Runnable}, which redeclares
	 * none: each site resolves before it runs (JLS 9.2). Prints
	 * {@link #OBJECT_METHODS_ON_AN_INTERFACE_OUTPUT}.
	 */
	public static final String OBJECT_METHODS_ON_AN_INTERFACE = """
			(defun shown (l)
			  (declare (type (java:object "java.util.List") l))
			  (list (java:call l "toString") (java:call l "hashCode") (java:call l "equals" l)
			        (java:call (java:call l "getClass") "getName")))
			(print (shown (java:static "java.util.List" "of" 1 2)))
			(let ((r (java:reify "java.lang.Runnable" "run" (lambda () nil))))
			  (print (list (java:call r "toString") (java:call r "equals" r)
			               (eql (java:call r "hashCode") (java:call r "hashCode")))))
			""";

	/** What {@link #OBJECT_METHODS_ON_AN_INTERFACE} prints. */
	public static final String OBJECT_METHODS_ON_AN_INTERFACE_OUTPUT = """
			("[1, 2]" 994 T "java.util.ImmutableCollections$List12")
			("#<java-reify java.lang.Runnable>" T T)""";

	private JavaInteropPrograms() {
	}

}
