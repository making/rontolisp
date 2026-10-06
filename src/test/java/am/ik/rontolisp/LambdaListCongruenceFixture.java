package am.ik.rontolisp;

/**
 * A method whose lambda list is not congruent with its generic function's (CLHS 7.6.4) is
 * refused where it is added, shared by the backend suites. Which definitions are refused
 * and which are added is SBCL 2.2.9's answer; the texts are rontolisp's.
 */
public final class LambdaListCongruenceFixture {

	private LambdaListCongruenceFixture() {
	}

	/**
	 * Each rule refused and accepted from inside a handler (a {@code defmethod} under a
	 * top-level {@code let} reaches the compile paths in place), then the calls that show
	 * a refused method was never added.
	 */
	public static final String PROGRAM = """
			(defun llc-try (thunk)
			  (handler-case (progn (funcall thunk) :added)
			    (program-error (c) (princ-to-string c))))
			(defgeneric llc-opt (a b &optional c))
			(defmethod llc-opt ((a integer) b &optional c) (list :int a b c))
			(defgeneric llc-key (a &key x))
			(defgeneric llc-rest (a &rest r))
			(defgeneric llc-plain (a))
			(defgeneric (setf llc-place) (v o))
			(defmethod llc-implicit ((a integer) b) (list :int a b))
			(defmethod llc-keys ((a integer) &key y) (list :int a y))
			(defclass llc-thing () ())
			(defclass llc-sink (rontolisp:fundamental-character-output-stream) ())
			(let ()
			  (print (llc-try (lambda () (defmethod llc-opt ((a string) b) (list :str a b)))))
			  (print (llc-try (lambda () (defmethod llc-opt ((a string) b &optional c d) a))))
			  (print (llc-try (lambda () (defmethod llc-opt ((a string) b c d) a))))
			  (print (llc-try (lambda () (defmethod llc-implicit ((a string)) a))))
			  (print (llc-try (lambda () (defmethod llc-rest ((a string)) a))))
			  (print (llc-try (lambda () (defmethod llc-plain ((a string) &key k) a))))
			  (print (llc-try (lambda () (defmethod llc-key ((a string) &key y) a))))
			  (print (llc-try (lambda () (defmethod llc-key ((a symbol) &rest r &key y) a))))
			  (print (llc-try (lambda () (defmethod (setf llc-place) (v) v))))
			  (print (llc-try (lambda () (defmethod initialize-instance :after ((o llc-thing)) o))))
			  (print (llc-try (lambda () (defmethod print-object ((o llc-thing)) o))))
			  (print (llc-try (lambda () (defmethod rontolisp:stream-write-string ((s llc-sink) str) str))))
			  (print (list (llc-try (lambda () (defmethod llc-key ((a integer) &rest r) (list :int a))))
			               (llc-try (lambda () (defmethod llc-key ((a string) &key y &allow-other-keys) (list :str a))))
			               (llc-try (lambda () (defmethod llc-key ((a symbol) &key ((:x other))) (list :sym other))))
			               (llc-try (lambda () (defmethod llc-rest ((a integer) &key) (list :int a))))
			               (llc-try (lambda () (defmethod llc-keys ((a string) &key z) (list :str a z))))
			               (llc-try (lambda () (defmethod initialize-instance :after ((o llc-thing) &key) o))))))
			(print (list (llc-opt 1 2) (handler-case (llc-opt "s" 2) (error () :no-method))
			             (llc-implicit 1 2) (handler-case (llc-implicit "s") (error () :no-method))
			             (llc-key 1 :x 2) (llc-key "s" :x 2) (llc-key 'a :x 3) (llc-rest 1) (llc-keys "s" :z 4)))
			""";

	/** What {@link #PROGRAM} prints. */
	public static final String EXPECTED = String.join("\n",
			"\"DEFMETHOD LLC-OPT: the method has fewer optional arguments than the generic function\"",
			"\"DEFMETHOD LLC-OPT: the method has more optional arguments than the generic function\"",
			"\"DEFMETHOD LLC-OPT: the method has more required arguments than the generic function\"",
			"\"DEFMETHOD LLC-IMPLICIT: the method has fewer required arguments than the generic function\"",
			"\"DEFMETHOD LLC-REST: the method and generic function differ in whether they accept &REST or &KEY arguments\"",
			"\"DEFMETHOD LLC-PLAIN: the method and generic function differ in whether they accept &REST or &KEY arguments\"",
			"\"DEFMETHOD LLC-KEY: the method does not accept each of the &KEY arguments (:X)\"",
			"\"DEFMETHOD LLC-KEY: the method does not accept each of the &KEY arguments (:X)\"",
			"\"DEFMETHOD (SETF LLC-PLACE): the method has fewer required arguments than the generic function\"",
			"\"DEFMETHOD INITIALIZE-INSTANCE: the method and generic function differ in whether they accept &REST or &KEY arguments\"",
			"\"DEFMETHOD PRINT-OBJECT: the method has fewer required arguments than the generic function\"",
			"\"DEFMETHOD RONTOLISP:STREAM-WRITE-STRING: the method has fewer optional arguments than the generic function\"",
			"(:ADDED :ADDED :ADDED :ADDED :ADDED :ADDED)",
			"((:INT 1 2 NIL) :NO-METHOD (:INT 1 2) :NO-METHOD (:INT 1) (:STR \"s\") (:SYM 3) (:INT 1) (:STR \"s\" 4))");

	/**
	 * A refusal no handler sees ends the program at the {@code defmethod}, after what the
	 * forms before it printed.
	 */
	public static final String UNCAUGHT_PROGRAM = """
			(defgeneric llu-g (a b &optional c))
			(print :before)
			(defmethod llu-g ((a integer) b) (list a b))
			(print :unreached)
			""";

	/** The report {@link #UNCAUGHT_PROGRAM} ends with. */
	public static final String UNCAUGHT_REPORT = "Unhandled condition: DEFMETHOD LLU-G: the method has fewer optional arguments than the generic function";

	/**
	 * A {@code defgeneric} whose lambda list an existing method is not congruent with is
	 * refused the same way, and the generic keeps its methods; one that only drops the
	 * methods of an earlier {@code defgeneric}'s {@code (:method ...)} options is not.
	 */
	public static final String DEFGENERIC_PROGRAM = """
			(defgeneric lld-inline (a) (:method ((a integer)) :inline))
			(defmethod lld-inline (a) :default)
			(defgeneric lld-inline (a))
			(print (list (lld-inline 1) (lld-inline "s")))
			(defmethod lld-g ((a integer)) (list :int a))
			(defgeneric lld-g (a &optional b))
			(print :unreached)
			""";

	/** What {@link #DEFGENERIC_PROGRAM} prints before its report. */
	public static final String DEFGENERIC_STDOUT = "(:DEFAULT :DEFAULT)";

	/** The report {@link #DEFGENERIC_PROGRAM} ends with. */
	public static final String DEFGENERIC_REPORT = "Unhandled condition: DEFGENERIC LLD-G: the lambda list is incompatible with an existing method: the method has fewer optional arguments than the generic function";

}
