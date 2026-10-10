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

	/**
	 * java:reify of several interfaces, and one given {@code :value}: one object Java
	 * holds as each interface -- a superinterface of another listed one implied -- whose
	 * unnamed abstract method names the interface declaring it; an object standing for a
	 * value is handed back as the value, its {@code equals}, {@code hashCode} and
	 * {@code toString} unless named those of a nil-hash handle (equal to an object
	 * standing for the very same value of its class, the value's identity hash,
	 * {@code Object}'s spelling of its {@code :class}), and a {@code :value} that is a
	 * marker's keyword is the value; an interface name computed at run time makes an
	 * equal object (a compiled program's bridge).
	 */
	public static final String REIFY_SEVERAL_STANDING = """
			(defun row (thunk)
			  (handler-case (prin1 (funcall thunk))
			    (java:java-exception (e) (princ (java:call (java:java-exception-cause e) "getMessage")))
			    (error (e) (princ e)))
			  (terpri))
			(let ((o (java:reify '("java.lang.Runnable" "java.util.function.Supplier")
			           "run" (lambda () (print :ran))
			           "get" (lambda () 42))))
			  (java:call (java:new "java.lang.Thread" o) "run")
			  (row (lambda () (java:call o "get")))
			  (row (lambda () (java:call o "toString"))))
			(row (lambda () (let ((l (java:reify '("java.util.Collection" "java.util.List" "java.lang.Iterable")
			                           "size" (lambda () 0))))
			                  (java:call l "size"))))
			(row (lambda () (java:call (java:reify '("java.lang.Runnable" "java.util.function.Supplier")
			                             "run" (lambda () nil))
			                           "get")))
			(defvar *cell* (list :cell 1))
			(let* ((o (java:reify '("java.lang.Comparable") :value *cell* :class "my.Cell"
			            "compareTo" (lambda (other) (if (eq other *cell*) 0 1))))
			       (twin (java:reify "java.lang.Runnable" :value *cell* :class "my.Cell" "run" (lambda () nil)))
			       (other (java:reify "java.lang.Runnable" :value *cell* :class "my.Other" "run" (lambda () nil)))
			       (l (java:new "java.util.ArrayList")))
			  (java:call l "add" o)
			  (row (lambda () (eq (java:call l "get" 0) *cell*)))
			  (row (lambda () (java:call o "compareTo" o)))
			  (row (lambda () (list (java:call l "contains" twin) (java:call l "contains" other))))
			  (row (lambda () (= (java:call o "hashCode") (java:call twin "hashCode"))))
			  (row (lambda () (string= (java:call o "toString")
			                           (format nil "my.Cell@~(~x~)" (java:call o "hashCode"))))))
			(defun point (x)
			  (java:reify '("java.lang.Runnable") :value (list :point x) :class "my.Point"
			    "run" (lambda () nil)
			    "equals" (lambda (other) (and (consp other) (eq (car other) :point) (= (cadr other) x)))
			    "hashCode" (lambda () x)
			    :java-false))
			(let ((s (java:new "java.util.HashSet")))
			  (java:call s "add" (point 1))
			  (java:call s "add" (point 1))
			  (java:call s "add" (point 2))
			  (row (lambda () (java:call s "size")))
			  (row (lambda () (java:call (point 255) "toString"))))
			(row (lambda () (let ((l (java:new "java.util.ArrayList")))
			                  (java:call l "add" (java:reify "java.lang.Runnable" :value :java-false "run" (lambda () nil)))
			                  (java:call l "get" 0))))
			(defvar *runnable* "java.lang.Runnable")
			(row (lambda () (let ((l (java:new "java.util.ArrayList"))
			                      (late (java:reify *runnable* :value *cell* :class "my.Cell" "run" (lambda () nil))))
			                  (java:call l "add" late)
			                  (list (eq (java:call l "get" 0) *cell*)
			                        (java:call l "contains"
			                                   (java:reify "java.lang.Runnable" :value *cell* :class "my.Cell"
			                                     "run" (lambda () nil)))))))
			(row (lambda () (java:reify '("java.lang.Runnable" "java.lang.Runnable") "run" (lambda () nil))))
			(row (lambda () (java:reify '("java.lang.Runnable" "java.util.function.Supplier") "foo" (lambda () nil))))
			(row (lambda () (java:reify "java.lang.Runnable" :class "x" "run" (lambda () nil))))
			""";

	/** What {@link #REIFY_SEVERAL_STANDING} prints, trimmed. */
	public static final String REIFY_SEVERAL_STANDING_OUTPUT = """
			:RAN
			42
			"#<java-reify java.lang.Runnable java.util.function.Supplier>"
			0
			java:reify: no implementation of java.util.function.Supplier.get()
			T
			0
			(T NIL)
			T
			T
			2
			"my.Point@ff"
			:JAVA-FALSE
			(T T)
			java:reify names interface java.lang.Runnable twice
			java:reify: interfaces java.lang.Runnable java.util.function.Supplier have no method foo
			java:reify expects (java:reify "interface"-or-list [:value v] [:class "class"] "method" function ...)""";

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
	 * Calls ending in {@code :functional}: a function passed where an interface is
	 * expected implements every abstract method by the method's arguments -- a literal
	 * lambda (a resolved site), a function in a variable (a dispatched site), a receiver
	 * of no known class (resolved when it runs), a constructor and a static call -- and a
	 * default method keeps its body ({@code Predicate.not} calls {@code negate} on it).
	 * The last two calls differ only in the marker: a compiled program gives them
	 * different site methods.
	 */
	public static final String FUNCTIONAL = """
			(let ((lst (java:new "java.util.ArrayList")))
			  (dolist (x (list 3 1 2)) (java:call lst "add" x))
			  (java:static "java.util.Collections" "sort" lst (lambda (a b) (- a b)) :functional)
			  (print (java:call lst "toString"))
			  (print (java:call (java:static "java.util.function.Predicate" "not" (lambda (x) (oddp x)) :functional)
			                    "test" 2))
			  (let ((f (lambda (x) (print (* x 10)))))
			    (java:call lst "forEach" f :functional))
			  (let ((each (lambda (coll f) (java:call coll "forEach" f :functional))))
			    (funcall each lst #'print))
			  (let ((th (java:new "java.lang.Thread" (lambda () (print :ran)) :functional)))
			    (java:call th "start")
			    (java:call th "join")))
			(let ((one (java:new "java.util.ArrayList")))
			  (java:call one "add" 7)
			  (java:call one "forEach" (lambda (m x) (print (list m x))))
			  (java:call one "forEach" (lambda (x) (print x)) :functional))
			""";

	/** What {@link #FUNCTIONAL} prints. */
	public static final String FUNCTIONAL_OUTPUT = """
			"[1, 2, 3]"
			T
			10
			20
			30
			1
			2
			3
			:RAN
			("accept" 7)
			7""";

	/**
	 * Implementations made at a call ending in {@code :java-false}, or by a form ending
	 * in it: Java's {@code false} reaches the function as {@code |false|} -- a function
	 * converted by its arguments, one converted as a proxy, a {@code java:reify}, a
	 * {@code java:proxy} and a {@code java:subclass} -- where the unmarked call hands it
	 * {@code nil}; and with {@code :functional} too, a function implementing
	 * {@code Comparator} may answer a boolean ({@code t} first, {@code |false|} by the
	 * arguments swapped) or any real, whose {@code intValue} it is -- at a resolved site
	 * (a literal lambda), a dispatched one (a function parameter) and one left to run
	 * time (the class in a variable); {@code nil} is the comparator's
	 * {@code NullPointerException} and a string its {@code ClassCastException}, as
	 * Clojure's {@code AFunction.compare} throws them, which the site wraps as a member's
	 * failure. A boolean without the markers is refused as before.
	 */
	public static final String JAVA_FALSE = """
			(defvar *collections* "java.util.Collections")
			(defun row (thunk)
			  (handler-case (prin1 (funcall thunk)) (error (e) (princ e)))
			  (terpri))
			(defun sorted (cmp)
			  (let ((l (java:new "java.util.ArrayList")))
			    (dolist (x (list 3 1 2)) (java:call l "add" x))
			    (java:static "java.util.Collections" "sort" l cmp :functional :java-false)
			    (java:call l "toString")))
			(defun sorted* (cmp)
			  (let ((l (java:new "java.util.ArrayList")))
			    (dolist (x (list 3 1 2)) (java:call l "add" x))
			    (java:static *collections* "sort" l cmp :functional :java-false)
			    (java:call l "toString")))
			(let ((l (java:new "java.util.ArrayList")) (seen nil))
			  (java:call l "add" '|false|)
			  (java:call l "add" t)
			  (java:call l "forEach" (lambda (x) (push x seen)) :functional :java-false)
			  (java:call l "forEach" (lambda (x) (push x seen)) :functional)
			  (java:call l "forEach" (lambda (m x) (push (list m x) seen)) :java-false)
			  (row (lambda () (reverse seen))))
			(row (lambda () (java:call (java:reify "java.util.function.Predicate" "test" (lambda (x) (eq x '|false|))
			                             :java-false)
			                           "test" '|false|)))
			(row (lambda () (java:call (java:reify "java.util.function.Predicate" "test" (lambda (x) (eq x '|false|)))
			                           "test" '|false|)))
			(row (lambda () (let ((got nil))
			                  (java:call (java:proxy "java.util.function.Consumer" (lambda (m x) (setq got (list m x)))
			                               :java-false)
			                             "accept" '|false|)
			                  got)))
			(row (lambda () (let* ((got nil)
			                       (l (java:subclass "java.util.ArrayList" '() '("add")
			                            (lambda (this m &rest args) (setq got args) t) :java-false)))
			                  (java:call l "add" '|false|)
			                  got)))
			(row (lambda () (list (java:static "java.util.Collections" "sort" (java:new "java.util.ArrayList") (lambda (a b) t)
			                        :functional :java-false)
			                      (sorted (lambda (a b) (if (< a b) t '|false|)))
			                      (sorted* (lambda (a b) (if (> b a) t '|false|))))))
			(row (lambda () (let ((l (java:new "java.util.ArrayList")))
			                  (dolist (x (list 3 1 2)) (java:call l "add" x))
			                  (java:static "java.util.Collections" "sort" l (lambda (a b) (if (> a b) t '|false|))
			                    :functional :java-false)
			                  (java:call l "toString"))))
			(row (lambda () (list (sorted (lambda (a b) (* 0.5 (- a b)))) (sorted* (lambda (a b) (/ (- b a) 2)))
			                      (sorted (lambda (a b) (* (- a b) 4294967296))) (sorted* (lambda (a b) (- (* (- a b) (expt 2 70)) 1))))))
			(row (lambda () (sorted (lambda (a b) nil))))
			(row (lambda () (sorted* (lambda (a b) "x"))))
			(row (lambda () (let ((l (java:new "java.util.ArrayList")))
			                  (dolist (x (list 3 1 2)) (java:call l "add" x))
			                  (java:static "java.util.Collections" "sort" l (lambda (a b) (< a b)) :functional)
			                  (java:call l "toString"))))
			""";

	/** What {@link #JAVA_FALSE} prints. */
	public static final String JAVA_FALSE_OUTPUT = """
			(|false| T NIL T ("accept" |false|) ("accept" T))
			T
			NIL
			("accept" |false|)
			(|false|)
			(NIL "[1, 2, 3]" "[1, 2, 3]")
			"[3, 2, 1]"
			("[1, 3, 2]" "[3, 1, 2]" "[3, 1, 2]" "[2, 1, 3]")
			error calling java.util.Collections.sort: java.lang.NullPointerException: Cannot invoke "java.lang.Number.intValue()" because "n" is null
			error calling java.util.Collections.sort: java.lang.ClassCastException: class java.lang.String cannot be cast to class java.lang.Number
			java:reify: cannot return T as int from java.util.Comparator.compare""";

	/**
	 * Implementations made by a form ending in {@code :octets}, or at a call ending in
	 * it: a {@code byte[]} Java hands the function is an {@code (unsigned-byte 8)} vector
	 * of its octets -- a {@code java:reify} and a {@code java:proxy} (generated, and the
	 * bridge's with the interface in a variable), a {@code java:subclass}, a function
	 * converted at a resolved, a dispatched and a run-time site -- where the unmarked
	 * form hands a list of signed bytes. The vector is Java's own array: what the
	 * function stores Java reads, when it returns or throws ({@code readNBytes} reads the
	 * array it handed), what Java stores while it runs the function reads, and one array
	 * Java hands twice is one vector.
	 */
	public static final String OCTETS = """
			(defvar *consumer* "java.util.function.Consumer")
			(defun row (thunk)
			  (handler-case (prin1 (funcall thunk)) (error (e) (princ e)))
			  (terpri))
			(defun octets (&rest xs)
			  (let ((v (make-array (length xs) :element-type '(unsigned-byte 8))))
			    (dotimes (i (length xs) v) (setf (aref v i) (nth i xs)))))
			(defun holding (&rest vectors)
			  (let ((l (java:new "java.util.ArrayList")))
			    (dolist (v vectors l) (java:call l "add" (java:view v v :bytes)))))
			(defun each (l f) (java:call l "forEach" f :functional :octets))
			(defun each-listed (l f)
			  (declare (type (java:object "java.util.List") l))
			  (java:call l "forEach" f :functional :octets))
			(row (lambda ()
			       (let ((seen nil) (l (holding (octets 1 255))))
			         (java:call l "forEach" (java:reify "java.util.function.Consumer" "accept" (lambda (x) (push x seen))
			                                  :octets))
			         (java:call l "forEach" (java:reify *consumer* "accept" (lambda (x) (push x seen)) :octets))
			         (java:call l "forEach" (java:reify "java.util.function.Consumer" "accept" (lambda (x) (push x seen))))
			         (reverse seen))))
			(row (lambda ()
			       (let ((seen nil) (l (holding (octets 2))))
			         (java:call l "forEach" (java:proxy "java.util.function.Consumer" (lambda (m x) (push (list m x) seen))
			                                  :octets :java-false))
			         (java:call l "forEach" (java:proxy *consumer* (lambda (m x) (push (list m x) seen)) :java-false :octets))
			         seen)))
			(row (lambda ()
			       (let* ((n 0)
			              (in (java:subclass "java.io.InputStream" '() '("read")
			                    (lambda (this name &rest args)
			                      (if (or (null args) (> n 0))
			                          -1
			                          (let ((buf (first args)) (off (second args)))
			                            (setq n 1)
			                            (setf (aref buf off) 7)
			                            (setf (aref buf (+ off 1)) 8)
			                            2)))
			                    :octets)))
			         (java:call in "readNBytes" 4 :octets))))
			(row (lambda ()
			       (let ((seen nil) (l (holding (octets 3) (octets 4))))
			         (java:call (the (java:object "java.util.List") l) "forEach" (lambda (x) (push x seen))
			                    :functional :octets)
			         (each-listed l (lambda (x) (push x seen)))
			         (each l (lambda (x) (push x seen)))
			         (java:call (the (java:object "java.util.List") l) "forEach" (lambda (x) (push x seen)) :functional)
			         (reverse seen))))
			(row (lambda ()
			       (let ((l (holding (octets 0 0 0))))
			         (each l (lambda (x) (setf (aref x 0) 9)))
			         (each-listed l (lambda (x) (setf (aref x 1) 8)))
			         (handler-case (java:call (the (java:object "java.util.List") l) "forEach"
			                                  (lambda (x) (setf (aref x 2) 7) (error "boom")) :functional :octets)
			           (error (e) (princ e) (terpri)))
			         (java:call l "get" 0 :octets))))
			(row (lambda ()
			       (let* ((m (java:new "java.util.HashMap")) (v (octets 1)) (k (java:view v v :bytes)) (got nil))
			         (java:call m "put" k k)
			         (java:call m "forEach" (java:reify "java.util.function.BiConsumer" "accept"
			                                  (lambda (a b) (setf (aref a 0) 5) (setq got (aref b 0))) :octets))
			         (list got (java:call (java:call m "keySet") "toArray" :octets)))))
			(row (lambda ()
			       (let* ((bb (java:static "java.nio.ByteBuffer" "allocate" 2))
			              (a (java:call bb "array" :octets))
			              (seen nil))
			         (java:call (holding a) "forEach"
			                    (java:reify "java.util.function.Consumer" "accept"
			                      (lambda (x) (java:call bb "put" 7) (setf (aref x 1) 5) (setq seen (list (aref x 0) (aref x 1))))
			                      :octets))
			         (list seen a))))
			""";

	/** What {@link #OCTETS} prints. */
	public static final String OCTETS_OUTPUT = """
			(#(1 255) #(1 255) (1 -1))
			(("accept" #(2)) ("accept" #(2)))
			#(7 8)
			(#(3) #(4) #(3) #(4) #(3) #(4) (3) (4))
			boom
			#(9 8 7)
			(5 (#(5)))
			((7 5) #(7 5))""";

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
	 * java:subclass over a JDK class: the constructor arguments choose the superclass
	 * constructor, a named method runs its body (which sees the object as {@code this}),
	 * an unnamed one is inherited, and a {@code proxy-super} reaches the superclass
	 * implementation through the generated accessor.
	 */
	public static final String SUBCLASS = """
			(let ((f (java:subclass "java.io.File" '() '("lastModified") "recent"
			            (lambda (this name &rest args)
			              (if (equal name "lastModified")
			                  (if (equal (java:call this "getName") "recent") 42 -1)
			                  nil)))))
			  (print (java:call f "lastModified"))
			  (print (java:call f "getName"))
			  (print (java:call f "toString"))
			  (print (java:call f "equals" f)))
			(let ((g (java:subclass "java.io.File" '() '("toString" "equals" "hashCode") "x"
			            (lambda (this name &rest args)
			              (if (equal name "toString") "over!"
			                  (if (equal name "equals") T 7))))))
			  (print (java:call g "toString"))
			  (print (java:call g "equals" g))
			  (print (java:call g "hashCode"))
			  (print (java:call g "super$toString$0")))
			""";

	/** What {@link #SUBCLASS} prints. */
	public static final String SUBCLASS_OUTPUT = """
			42
			"recent"
			"recent"
			T
			"over!"
			T
			7
			"x\"""";

	/**
	 * A java:subclass of the classes a Lisp array and hash table are built on is a host
	 * object: its own overrides are never asked whether it is one -- an {@code isEmpty}
	 * that says "not empty" on an empty list, a {@code get} that answers a list for any
	 * key -- whether it is the receiver of a resolved call or of one left to run time, an
	 * argument, or tested by a predicate.
	 */
	public static final String HOST_COLLECTION_SUBCLASS = """
			(defun empty-p (x) (java:call x "isEmpty"))
			(defun add-all (x y) (java:call x "addAll" y))
			(let ((l (java:subclass "java.util.ArrayList" '() '("isEmpty") (lambda (this name &rest args) nil)))
			      (s (java:subclass "java.util.ArrayList" '() '("size") (lambda (this name &rest args) 1)))
			      (m (java:subclass "java.util.LinkedHashMap" '() '("get")
			            (lambda (this name &rest args) (java:new "java.util.ArrayList")))))
			  (print (java:call l "isEmpty"))
			  (print (empty-p l))
			  (print (java:call l "size"))
			  (print (arrayp l))
			  (print (vectorp l))
			  (print (java:call (java:new "java.util.ArrayList") "addAll" l))
			  (print (java:call (java:new "java.util.ArrayList") "addAll" s))
			  (print (add-all (java:new "java.util.ArrayList") s))
			  (print (stringp s))
			  (print (typep s 'simple-array))
			  (print (java:call m "size"))
			  (print (empty-p m))
			  (print (hash-table-p m))
			  (print (java:call (java:new "java.util.HashMap") "equals" m)))
			""";

	/** What {@link #HOST_COLLECTION_SUBCLASS} prints. */
	public static final String HOST_COLLECTION_SUBCLASS_OUTPUT = """
			NIL
			NIL
			0
			NIL
			NIL
			NIL
			NIL
			NIL
			NIL
			NIL
			0
			T
			NIL
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
