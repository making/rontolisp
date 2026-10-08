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
	 * shows it -- a float, bignum or fixnum receiver is called as its {@code Double},
	 * {@code BigInteger} or {@code Integer}, which has no {@code size}, and a vector or
	 * hash table argument converts to a fresh {@code List} or {@code Map} --, a host list
	 * and map are called, and a {@code BigInteger} is a Lisp integer. Prints
	 * {@link #HOST_OBJECT_OUTPUT}.
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

	/**
	 * A host {@code ArrayList} holding {@code 1} and a host {@code LinkedHashMap} holding
	 * {@code "k"} through every array and hash-table accessor, directly and as a function
	 * value: each is refused with the interpreter's type-error ({@code ARRAY} or
	 * {@code HASH-TABLE}, named after the accessor when that is a named operator, and
	 * {@code LENGTH}'s {@code SEQUENCE} one for {@code length}), an argument after the
	 * table is evaluated first, and the host map is left untouched. A Lisp table and
	 * vector beside them still answer. Prints {@link #HOST_ACCESSOR_OUTPUT}.
	 */
	public static final String HOST_ACCESSOR_PROGRAM = """
			(defun row (thunk)
			  (handler-case (prin1 (funcall thunk))
			    (type-error (e)
			      (princ (list 'type-error (type-error-datum e) (type-error-expected-type e)))
			      (princ " ")
			      (princ e))
			    (error (e) (princ e)))
			  (terpri))
			(let ((l (java:new "java.util.ArrayList"))
			      (m (java:new "java.util.LinkedHashMap"))
			      (v (make-array 2 :fill-pointer 1 :adjustable t :initial-element 7))
			      (h (make-hash-table)))
			  (java:call l "add" 1)
			  (java:call m "put" "k" 1)
			  (setf (gethash "k" h) 1)
			  (row (lambda () (gethash "k" m)))
			  (row (lambda () (gethash "k" m (progn (princ "default ") 0))))
			  (row (lambda () (setf (gethash "z" m) (progn (princ "value ") 2))))
			  (row (lambda () (remhash "k" m)))
			  (row (lambda () (clrhash m)))
			  (row (lambda () (hash-table-count m)))
			  (row (lambda () (hash-table-size m)))
			  (row (lambda () (hash-table-test m)))
			  (row (lambda () (hash-table-rehash-size m)))
			  (row (lambda () (hash-table-rehash-threshold m)))
			  (row (lambda () (maphash (lambda (k x) (print (list k x))) m)))
			  (row (lambda () (loop for k being the hash-keys of m collect k)))
			  (row (lambda () (funcall #'gethash "k" m)))
			  (row (lambda () (java:call m "toString")))
			  (row (lambda () (length l)))
			  (row (lambda () (funcall #'length l)))
			  (row (lambda () (coerce l 'list)))
			  (row (lambda () (elt l 0)))
			  (row (lambda () (aref l 0)))
			  (row (lambda () (svref l 0)))
			  (row (lambda () (setf (aref l 0) 2)))
			  (row (lambda () (row-major-aref l 0)))
			  (row (lambda () (array-dimensions l)))
			  (row (lambda () (array-rank l)))
			  (row (lambda () (array-element-type l)))
			  (row (lambda () (adjustable-array-p l)))
			  (row (lambda () (array-has-fill-pointer-p l)))
			  (row (lambda () (fill-pointer l)))
			  (row (lambda () (vector-push 2 l)))
			  (row (lambda () (vector-push-extend 2 l)))
			  (row (lambda () (vector-pop l)))
			  (row (lambda () (java:call l "toString")))
			  (row (lambda () (list (gethash "k" h) (hash-table-count h) (hash-table-test h) (length v) (aref v 0)
			                        (vector-push-extend 8 v) (fill-pointer v) (array-element-type v)))))
			""";

	/** What {@link #HOST_ACCESSOR_PROGRAM} prints. */
	public static final String HOST_ACCESSOR_OUTPUT = """
			(TYPE-ERROR #<java java.util.LinkedHashMap> HASH-TABLE) GETHASH: The value #<java java.util.LinkedHashMap> is not of type HASH-TABLE
			default (TYPE-ERROR #<java java.util.LinkedHashMap> HASH-TABLE) GETHASH: The value #<java java.util.LinkedHashMap> is not of type HASH-TABLE
			value (TYPE-ERROR #<java java.util.LinkedHashMap> HASH-TABLE) (SETF GETHASH): The value #<java java.util.LinkedHashMap> is not of type HASH-TABLE
			(TYPE-ERROR #<java java.util.LinkedHashMap> HASH-TABLE) REMHASH: The value #<java java.util.LinkedHashMap> is not of type HASH-TABLE
			(TYPE-ERROR #<java java.util.LinkedHashMap> HASH-TABLE) CLRHASH: The value #<java java.util.LinkedHashMap> is not of type HASH-TABLE
			(TYPE-ERROR #<java java.util.LinkedHashMap> HASH-TABLE) HASH-TABLE-COUNT: The value #<java java.util.LinkedHashMap> is not of type HASH-TABLE
			(TYPE-ERROR #<java java.util.LinkedHashMap> HASH-TABLE) HASH-TABLE-SIZE: The value #<java java.util.LinkedHashMap> is not of type HASH-TABLE
			(TYPE-ERROR #<java java.util.LinkedHashMap> HASH-TABLE) HASH-TABLE-TEST: The value #<java java.util.LinkedHashMap> is not of type HASH-TABLE
			(TYPE-ERROR #<java java.util.LinkedHashMap> HASH-TABLE) HASH-TABLE-REHASH-SIZE: The value #<java java.util.LinkedHashMap> is not of type HASH-TABLE
			(TYPE-ERROR #<java java.util.LinkedHashMap> HASH-TABLE) HASH-TABLE-REHASH-THRESHOLD: The value #<java java.util.LinkedHashMap> is not of type HASH-TABLE
			(TYPE-ERROR #<java java.util.LinkedHashMap> HASH-TABLE) MAPHASH: The value #<java java.util.LinkedHashMap> is not of type HASH-TABLE
			(TYPE-ERROR #<java java.util.LinkedHashMap> HASH-TABLE) MAPHASH: The value #<java java.util.LinkedHashMap> is not of type HASH-TABLE
			(TYPE-ERROR #<java java.util.LinkedHashMap> HASH-TABLE) GETHASH: The value #<java java.util.LinkedHashMap> is not of type HASH-TABLE
			"{k=1}"
			(TYPE-ERROR #<java java.util.ArrayList> SEQUENCE) LENGTH: The value #<java java.util.ArrayList> is not of type SEQUENCE
			(TYPE-ERROR #<java java.util.ArrayList> SEQUENCE) LENGTH: The value #<java java.util.ArrayList> is not of type SEQUENCE
			(TYPE-ERROR #<java java.util.ArrayList> SEQUENCE) COERCE: The value #<java java.util.ArrayList> is not of type SEQUENCE
			(TYPE-ERROR #<java java.util.ArrayList> ARRAY) AREF: The value #<java java.util.ArrayList> is not of type ARRAY
			(TYPE-ERROR #<java java.util.ArrayList> ARRAY) AREF: The value #<java java.util.ArrayList> is not of type ARRAY
			(TYPE-ERROR #<java java.util.ArrayList> ARRAY) AREF: The value #<java java.util.ArrayList> is not of type ARRAY
			(TYPE-ERROR #<java java.util.ArrayList> ARRAY) (SETF AREF): The value #<java java.util.ArrayList> is not of type ARRAY
			(TYPE-ERROR #<java java.util.ArrayList> ARRAY) ROW-MAJOR-AREF: The value #<java java.util.ArrayList> is not of type ARRAY
			(TYPE-ERROR #<java java.util.ArrayList> ARRAY) ARRAY-DIMENSIONS: The value #<java java.util.ArrayList> is not of type ARRAY
			(TYPE-ERROR #<java java.util.ArrayList> ARRAY) ARRAY-DIMENSIONS: The value #<java java.util.ArrayList> is not of type ARRAY
			(TYPE-ERROR #<java java.util.ArrayList> ARRAY) ARRAY-ELEMENT-TYPE: The value #<java java.util.ArrayList> is not of type ARRAY
			(TYPE-ERROR #<java java.util.ArrayList> ARRAY) ADJUSTABLE-ARRAY-P: The value #<java java.util.ArrayList> is not of type ARRAY
			(TYPE-ERROR #<java java.util.ArrayList> ARRAY) ARRAY-HAS-FILL-POINTER-P: The value #<java java.util.ArrayList> is not of type ARRAY
			(TYPE-ERROR #<java java.util.ArrayList> ARRAY) FILL-POINTER: The value #<java java.util.ArrayList> is not of type ARRAY
			(TYPE-ERROR #<java java.util.ArrayList> ARRAY) VECTOR-PUSH: The value #<java java.util.ArrayList> is not of type ARRAY
			(TYPE-ERROR #<java java.util.ArrayList> ARRAY) VECTOR-PUSH-EXTEND: The value #<java java.util.ArrayList> is not of type ARRAY
			(TYPE-ERROR #<java java.util.ArrayList> ARRAY) VECTOR-POP: The value #<java java.util.ArrayList> is not of type ARRAY
			"[1]"
			(1 1 EQUAL 1 7 1 2 T)""";

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
			No matching method java.lang.Double.size with 0 argument(s)
			"1.0E10"
			No matching method java.math.BigInteger.size with 0 argument(s)
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
			"{}"
			java:call expects a java object as the first argument, got #(1 2)
			java:call expects a java object as the first argument, got #<HASH-TABLE :TEST EQUAL :COUNT 0>
			(1 1 1 1 "[1]" "{k=1}")
			java:static: argument 1 is not a java.util.LinkedHashMap, got #<HASH-TABLE :TEST EQUAL :COUNT 0>
			(123456789012345678901234567890 123456789012345678901234567891 5 T T 1 2)
			No matching method java.lang.Integer.size with 0 argument(s)""";

	/**
	 * A Lisp value as a {@code java:call} receiver: called as the object it converts to
	 * for an {@code Object} parameter -- a string a {@code String} (a mutable one too), a
	 * fixnum its narrowest box, a float a {@code Double}, a bignum a {@code BigInteger},
	 * a character a {@code Character}, {@code t} {@code Boolean.TRUE} -- at a site left
	 * to run time ({@code call0}), a resolved one (a literal or declared {@code String}
	 * receiver) and a receiver declared another class; {@code nil}, a symbol, a list and
	 * a function are no receiver. Prints {@link #LISP_RECEIVER_OUTPUT}.
	 */
	public static final String LISP_RECEIVER_PROGRAM = """
			(defun row (thunk)
			  (handler-case (prin1 (funcall thunk)) (error (e) (princ e)))
			  (terpri))
			(defun call0 (x m) (java:call x m))
			(defun len (x)
			  (declare (type (java:object "java.lang.String") x))
			  (java:call x "length"))
			(defun sized (x)
			  (declare (type (java:object "java.util.Collection") x))
			  (java:call x "size"))
			(row (lambda () (list (java:call "abc" "codePointAt" 0) (java:call "a" "compareTo" "b")
			                      (call0 "abc" "length") (java:call "abc" "matches" "a.c"))))
			(row (lambda () (list (call0 42 "toString") (call0 (expt 2 40) "toString") (call0 1.5 "isNaN")
			                      (call0 (expt 2 100) "bitLength") (call0 #\\a "charValue") (call0 t "booleanValue"))))
			(row (lambda () (list (java:call (call0 42 "getClass") "getSimpleName")
			                      (java:call (call0 (expt 2 40) "getClass") "getSimpleName")
			                      (java:call (call0 "s" "getClass") "getSimpleName"))))
			(let ((s (concatenate 'string "a" "bc")))
			  (row (lambda () (list (len s) (call0 s "length")
			                        (java:call (the (java:object "java.lang.String") s) "codePointAt" 1)))))
			(row (lambda () (len 5)))
			(row (lambda () (len nil)))
			(row (lambda () (sized "abc")))
			(row (lambda () (java:call "abc" "noSuch")))
			(dolist (x (list nil 'foo (list 1) #'car))
			  (row (lambda () (call0 x "toString"))))
			""";

	/** What {@link #LISP_RECEIVER_PROGRAM} prints. */
	public static final String LISP_RECEIVER_OUTPUT = """
			(97 -1 3 T)
			("42" "1099511627776" NIL 101 #\\a T)
			("Integer" "Long" "String")
			(3 3 98)
			java:call: the receiver is not a java.lang.String, got 5
			java:call expects a java object as the first argument, got NIL
			java:call: the receiver is not a java.util.Collection, got "abc"
			No matching method java.lang.String.noSuch with 0 argument(s)
			java:call expects a java object as the first argument, got NIL
			java:call expects a java object as the first argument, got FOO
			java:call expects a java object as the first argument, got (1)
			java:call expects a java object as the first argument, got #<function CAR>""";

	/**
	 * The symbol {@code |false|} and hash tables as arguments, at a resolved site (a
	 * quoted {@code |false|}), a dispatched one ({@code ts}, {@code bool}, {@code copy})
	 * and one left to run time (the class name in a variable: the compiled program's
	 * bridge): {@code |false|} is Java's false for a {@code boolean} and
	 * {@code Boolean.FALSE} for a reference, where {@code nil} is {@code null}, a
	 * receiver called as {@code Boolean.FALSE}, and what a callback answers for a
	 * {@code boolean} or a {@code Boolean}; a hash table is a fresh {@code LinkedHashMap}
	 * of its live entries in insertion order (an {@code equalp} table's keys as first
	 * stored), element-wise, for a parameter one is assignable to, and nothing else.
	 * Prints {@link #FALSE_AND_TABLE_OUTPUT}.
	 */
	public static final String FALSE_AND_TABLE_PROGRAM = """
			(defvar *objects* "java.util.Objects")
			(defvar *hash-map* "java.util.HashMap")
			(defun row (thunk)
			  (handler-case (prin1 (funcall thunk)) (error (e) (princ e)))
			  (terpri))
			(defun ts (x) (java:static "java.util.Objects" "toString" x))
			(defun ts* (x) (java:static *objects* "toString" x))
			(defun bool (x) (java:static "java.lang.Boolean" "toString" x))
			(defun copy (x) (java:call (java:new "java.util.TreeMap" x) "toString"))
			(defun copy* (x) (java:call (java:new *hash-map* x) "toString"))
			(defun table (&rest kvs)
			  (let ((h (make-hash-table :test 'equal)))
			    (loop for (k v) on kvs by #'cddr do (setf (gethash k h) v))
			    h))
			(row (lambda () (list (java:static "java.lang.Boolean" "toString" '|false|) (bool '|false|) (bool t)
			                      (bool nil))))
			(row (lambda () (list (ts '|false|) (ts* '|false|) (ts nil) (ts* t))))
			(row (lambda () (let ((l (java:new "java.util.ArrayList")))
			                  (java:call l "add" '|false|)
			                  (java:call l "add" nil)
			                  (java:call l "add" (list t '|false|))
			                  (java:call l "toString"))))
			(row (lambda () (list (java:call '|false| "booleanValue") (java:call '|false| "equals" '|false|)
			                      (java:call (java:call '|false| "getClass") "getSimpleName"))))
			(row (lambda () (list (java:call (java:reify "java.util.function.Predicate" "test" (lambda (x) '|false|))
			                                 "test" 1)
			                      (java:call (java:reify "java.util.function.Function" "apply" (lambda (x) '|false|))
			                                 "apply" 1)
			                      (java:call (java:proxy "java.util.function.BooleanSupplier" (lambda (m) '|false|))
			                                 "getAsBoolean"))))
			(let ((h (table "b" 2 "a" '(1 "x") "gone" 0 "c" '|false|)))
			  (remhash "gone" h)
			  (row (lambda () (list (copy h) (copy* h) (ts h) (ts* h)))))
			(let ((p (make-hash-table :test 'equalp)))
			  (setf (gethash "Key" p) 1)
			  (setf (gethash "KEY" p) 2)
			  (row (lambda () (list (ts p) (ts* p)))))
			(row (lambda () (ts (make-hash-table))))
			(row (lambda () (copy (table 'sym 1))))
			(row (lambda () (copy* (table "k" 'sym))))
			(row (lambda () (java:static "java.lang.Math" "abs" (table "k" 1))))
			(row (lambda () (ts 'other)))
			""";

	/** What {@link #FALSE_AND_TABLE_PROGRAM} prints. */
	public static final String FALSE_AND_TABLE_OUTPUT = """
			("false" "false" "true" "false")
			("false" "false" "null" "true")
			"[false, null, [true, false]]"
			(NIL T "Boolean")
			(NIL NIL NIL)
			("{a=[1, x], b=2, c=false}" "{a=[1, x], b=2, c=false}" "{b=2, a=[1, x], c=false}" "{b=2, a=[1, x], c=false}")
			("{Key=2}" "{Key=2}")
			"{}"
			No matching constructor for java.util.TreeMap with 1 argument(s)
			No matching constructor for java.util.HashMap with 1 argument(s)
			No matching method java.lang.Math.abs with 1 argument(s)
			No matching method java.util.Objects.toString with 1 argument(s)""";

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

	/**
	 * {@code eq} and {@code eql} on host objects are identity -- two equal {@code File}s
	 * are distinct, one object answered twice by a Java call is itself, and a
	 * {@code java:reify} whose {@code equals} answers true is still neither {@code T} nor
	 * {@code 1} and does not print as {@code T} -- through every surface that compares
	 * with them: the sequence functions, {@code case}, {@code getf}/{@code remf},
	 * {@code catch} tags and {@code eq}/{@code eql} hash tables, where a key mutated
	 * after insertion keeps its bucket. {@code equal} asks {@code equals}, a host
	 * collection included, and so does an {@code equal} table. Prints
	 * {@link #HOST_IDENTITY_OUTPUT}.
	 */
	public static final String HOST_IDENTITY_PROGRAM = """
			(let* ((f1 (java:new "java.io.File" "x"))
			       (f2 (java:new "java.io.File" "x"))
			       (holder (java:new "java.util.ArrayList"))
			       (e1 (java:new "java.util.ArrayList"))
			       (e2 (java:new "java.util.ArrayList"))
			       (r (java:reify "java.lang.Runnable" "run" (lambda () nil) "equals" (lambda (o) t)))
			       (s (java:new "java.util.HashSet"))
			       (eqt (make-hash-table :test 'eq))
			       (eqlt (make-hash-table :test 'eql))
			       (equalt (make-hash-table :test 'equal))
			       (plist (list f1 1 'k 2))
			       (tail (list 'k 2 f1 1)))
			  (java:call holder "add" f1)
			  (print (list (eq f1 f2) (eql f1 f2) (eq f1 f1) (eq f1 (java:call holder "get" 0))
			               (equal f1 f2) (equalp f1 f2)))
			  (print (list (eq e1 e2) (eql e1 e2) (equal e1 e2)))
			  (print (list (java:call r "equals" 1) (eq r t) (eq t r) (eql r 1) (equal r r)
			               (string= (prin1-to-string r) "T") (string= (princ-to-string r) "T")))
			  (print (list (member f2 (list f1)) (position f2 (list f1)) (assoc f2 (list (cons f1 1)))
			               (find f2 (list f1)) (count f2 (list f1)) (length (remove-duplicates (list f1 f2)))
			               (position f2 (list f1) :test #'equal) (case f1 (1 'one) (t 'other))))
			  (print (list (getf plist f2) (getf plist f1) (progn (remf plist f2) (length plist))
			               (progn (remf tail f2) (length tail))))
			  (print (catch f1 (catch f2 (throw f1 :to-f1)) :caught-by-f2))
			  (setf (gethash f1 eqt) 1 (gethash f1 eqlt) 2 (gethash f1 equalt) 3 (gethash e1 equalt) 4
			        (gethash s eqt) 5)
			  (java:call s "add" 1)
			  (print (list (gethash f2 eqt) (gethash f1 eqt) (gethash f2 eqlt) (gethash f1 eqlt)
			               (gethash f2 equalt) (gethash e2 equalt) (gethash s eqt))))
			""";

	/** What {@link #HOST_IDENTITY_PROGRAM} prints. */
	public static final String HOST_IDENTITY_OUTPUT = """
			(NIL NIL T T T T)
			(NIL NIL T)
			(T NIL NIL NIL T NIL NIL)
			(NIL NIL NIL NIL 0 2 0 OTHER)
			(NIL 1 4 4)
			:TO-F1
			(NIL 1 NIL 2 3 4 5)""";

	/**
	 * {@code equal} of a host object and a Lisp value asks the host object's
	 * {@code equals} with the value as a Java method's {@code Object} parameter receives
	 * it -- so the method sees the value itself, a string unframed, a mutable string
	 * rendered, a character a {@code Character}, {@code nil} {@code null} -- and only
	 * when the host object is on the left, as Clojure's {@code =} asks its left operand.
	 * A value no {@code Object} parameter takes as one object (a symbol, a list, a
	 * vector, a ratio, a function) is equal to no host object, and {@code equals} is not
	 * asked. Prints {@link #HOST_EQUAL_LISP_VALUE_OUTPUT}.
	 */
	public static final String HOST_EQUAL_LISP_VALUE_PROGRAM = """
			(let* ((seen nil)
			       (r (java:reify "java.lang.Runnable" "run" (lambda () nil)
			                      "equals" (lambda (o) (setq seen (cons o seen)) t)))
			       (f (java:new "java.io.File" "x"))
			       (s (make-array 1 :element-type 'character :initial-element #\\s :adjustable t
			                        :fill-pointer 1)))
			  (print (list (equal r 1) (equal r "s") (equal r nil) (equal r t) (equal r #\\a) (equal r 1.5)
			               (equal r (expt 10 20)) (equal r s) (equalp r 2)))
			  (print (reverse seen))
			  (setq seen nil)
			  (print (list (equal r 'sym) (equal r '(1)) (equal r (vector 1)) (equal r 1/2) (equal r #'car)
			               (equal 1 r) (equal "s" r) (equal nil r) (equalp 2 r) seen))
			  (print (list (equal f "x") (equal "x" f) (equal f (java:new "java.io.File" "x")))))
			""";

	/** What {@link #HOST_EQUAL_LISP_VALUE_PROGRAM} prints. */
	public static final String HOST_EQUAL_LISP_VALUE_OUTPUT = """
			(T T T T T T T T T)
			(1 "s" NIL T #\\a 1.5 100000000000000000000 "s" 2)
			(NIL NIL NIL NIL NIL NIL NIL NIL NIL NIL)
			(NIL NIL T)""";

	/**
	 * A member that throws signals a {@code java:java-exception}, a {@code simple-error}
	 * reporting the member and the throwable whose {@code java:java-exception-cause} is
	 * what the member threw; a caught one passed to a member is that throwable again, at
	 * a site resolved before it runs and at one left to run time alike, a method called
	 * on one is the throwable's, and a condition of no Java exception is still no
	 * argument. Prints {@link #HOST_EXCEPTION_OUTPUT}.
	 */
	public static final String HOST_EXCEPTION_PROGRAM = """
			(defun parse (s) (java:static "java.lang.Integer" "parseInt" s))
			(print (handler-case (parse "x")
			         (java:java-exception (e)
			           (list (typep e 'simple-error)
			                 (java:call (java:java-exception-cause e) "getMessage")
			                 (format nil "~a" e)))))
			(print (handler-case (java:new "java.lang.StringBuilder" -1)
			         (error (e) (java:call (java:call (java:java-exception-cause e) "getClass") "getName"))))
			(print (handler-case (parse "x")
			         (java:java-exception (e)
			           (let ((wrapped (java:new "java.lang.RuntimeException" "wrapped" e)))
			             (list (java:call (java:call wrapped "getCause") "getMessage")
			                   (eq (java:call wrapped "getCause") (java:java-exception-cause e)))))))
			(defvar *init-cause* "initCause")
			(print (handler-case (parse "y")
			         (java:java-exception (e)
			           (let ((r (java:new "java.lang.RuntimeException" "r")))
			             (java:call r *init-cause* e)
			             (java:call (java:call r "getCause") "getMessage")))))
			(print (handler-case (parse "z") (java:java-exception (e) (java:call e "getMessage"))))
			(print (handler-case (java:new "java.lang.RuntimeException" "x"
			                               (make-condition 'simple-error :format-control "s"))
			         (error (e) (format nil "~a" e))))
			(print (handler-case (java:java-exception-cause 5) (type-error () :type-error)))
			""";

	/** What {@link #HOST_EXCEPTION_PROGRAM} prints. */
	public static final String HOST_EXCEPTION_OUTPUT = """
			(T "For input string: \\"x\\"" "error calling java.lang.Integer.parseInt: java.lang.NumberFormatException: For input string: \\"x\\"")
			"java.lang.NegativeArraySizeException"
			("For input string: \\"x\\"" T)
			"For input string: \\"y\\""
			"For input string: \\"z\\""
			"No matching constructor for java.lang.RuntimeException with 2 argument(s)"
			:TYPE-ERROR""";

	private JavaInteropPrograms() {
	}

}
