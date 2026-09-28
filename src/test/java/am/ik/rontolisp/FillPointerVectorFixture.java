package am.ik.rontolisp;

/**
 * A program that hands the fill-pointer surface ({@code fill-pointer} and its
 * {@code setf}, {@code vector-push}, {@code vector-push-extend}, {@code vector-pop}) an
 * ARRAY that has no fill pointer -- a string literal, a simple general vector, a packed
 * float or integer vector, a rank-2 array, an adjustable vector -- and the neighbouring
 * refusals: a fill pointer set outside {@code [0, dimension]} and a pop of an empty
 * vector. Each prints the condition it signals. The first were a simple-error, a host
 * cast failure without a datum or a wasm trap. Shared by the backend suites, so every
 * backend is held to one expected text; {@code ci-spec.yaml}'s
 * {@code fill-pointer-surface-refuses-a-vector-without-one} pins a row per mechanism on
 * the native binary.
 */
public final class FillPointerVectorFixture {

	private FillPointerVectorFixture() {
	}

	/** The program. */
	public static final String SOURCE = """
			(defun te (thunk)
			  (handler-case (list :value (funcall thunk))
			    (type-error (e) (list (princ-to-string e) (type-error-datum e) (type-error-expected-type e)))
			    (error (e) (list :not-a-type-error (typep e 'simple-error) (princ-to-string e)))))
			(defvar *fp-string* "abc")
			(defvar *fp-simple* (vector 1 2))
			(defvar *fp-single* (make-array 2 :element-type 'single-float :initial-element 1.0))
			(defvar *fp-octets* (make-array 2 :element-type '(unsigned-byte 8)))
			(defvar *fp-grid* (make-array '(2 2) :initial-element 0))
			(defvar *fp-adjustable* (make-array 2 :adjustable t :initial-element 0))
			(defvar *fp-vector* (make-array 3 :fill-pointer 1 :initial-element 0))
			(defvar *fp-chars* (make-array 2 :element-type 'character :fill-pointer 0))
			(print (te (lambda () (fill-pointer *fp-string*))))
			(print (te (lambda () (fill-pointer *fp-simple*))))
			(print (te (lambda () (setf (fill-pointer *fp-string*) 0))))
			(print (te (lambda () (vector-push 1 *fp-simple*))))
			(print (te (lambda () (vector-push-extend #\\a *fp-string*))))
			(print (te (lambda () (vector-push-extend 1 *fp-simple* 'wt-symbol))))
			(print (te (lambda () (vector-pop *fp-simple*))))
			(print (te (lambda () (fill-pointer *fp-single*))))
			(print (te (lambda () (vector-pop *fp-octets*))))
			(print (te (lambda () (fill-pointer *fp-grid*))))
			(print (te (lambda () (vector-push-extend 1 *fp-adjustable*))))
			(print (te (lambda () (funcall #'vector-pop *fp-simple*))))
			(print (te (lambda () (funcall #'fill-pointer *fp-string*))))
			(print (te (lambda () (vector-push 1 5))))
			(print (handler-case (fill-pointer *fp-simple*)
			         (type-error (e) (equal (type-error-expected-type e)
			                                '(and vector (satisfies array-has-fill-pointer-p))))))
			(print (te (lambda () (setf (fill-pointer *fp-vector*) 4))))
			(print (te (lambda () (setf (fill-pointer *fp-vector*) -1))))
			(print (te (lambda () (setf (fill-pointer *fp-vector*) 'wt-symbol))))
			(print (te (lambda () (setf (fill-pointer *fp-chars*) 3))))
			(print (te (lambda () (vector-pop *fp-chars*))))
			(print (te (lambda () (vector-pop *fp-vector*))))
			(print (te (lambda () (vector-pop *fp-vector*))))
			(print (list (setf (fill-pointer *fp-vector*) 3) (vector-pop *fp-vector*) (fill-pointer *fp-vector*)
			             (vector-push #\\z *fp-chars*) *fp-chars*))
			""";

	/** What {@link #SOURCE} prints on every backend. */
	public static final String EXPECTED = """
			("FILL-POINTER: The value \\"abc\\" is not of type (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P))" "abc" (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P)))
			("FILL-POINTER: The value #(1 2) is not of type (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P))" #(1 2) (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P)))
			("(SETF FILL-POINTER): The value \\"abc\\" is not of type (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P))" "abc" (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P)))
			("VECTOR-PUSH: The value #(1 2) is not of type (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P))" #(1 2) (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P)))
			("VECTOR-PUSH-EXTEND: The value \\"abc\\" is not of type (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P))" "abc" (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P)))
			("VECTOR-PUSH-EXTEND: The value #(1 2) is not of type (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P))" #(1 2) (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P)))
			("VECTOR-POP: The value #(1 2) is not of type (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P))" #(1 2) (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P)))
			("FILL-POINTER: The value #f(1.0 1.0) is not of type (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P))" #f(1.0 1.0) (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P)))
			("VECTOR-POP: The value #(0 0) is not of type (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P))" #(0 0) (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P)))
			("FILL-POINTER: The value #2A((0 0) (0 0)) is not of type (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P))" #2A((0 0) (0 0)) (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P)))
			("VECTOR-PUSH-EXTEND: The value #(0 0) is not of type (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P))" #(0 0) (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P)))
			("VECTOR-POP: The value #(1 2) is not of type (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P))" #(1 2) (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P)))
			("FILL-POINTER: The value \\"abc\\" is not of type (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P))" "abc" (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P)))
			("VECTOR-PUSH: The value 5 is not of type ARRAY" 5 ARRAY)
			T
			("(SETF FILL-POINTER): The value 4 is not of type (INTEGER 0 3)" 4 (INTEGER 0 3))
			("(SETF FILL-POINTER): The value -1 is not of type (INTEGER 0 3)" -1 (INTEGER 0 3))
			("(SETF FILL-POINTER): The value WT-SYMBOL is not of type (INTEGER 0 3)" WT-SYMBOL (INTEGER 0 3))
			("(SETF FILL-POINTER): The value 3 is not of type (INTEGER 0 2)" 3 (INTEGER 0 2))
			(:NOT-A-TYPE-ERROR T "VECTOR-POP: there is nothing left to pop")
			(:VALUE 0)
			(:NOT-A-TYPE-ERROR T "VECTOR-POP: there is nothing left to pop")
			(3 0 2 0 "z")""";

}
