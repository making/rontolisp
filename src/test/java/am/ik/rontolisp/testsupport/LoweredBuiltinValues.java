package am.ik.rontolisp.testsupport;

/**
 * One program, pinned identically on the interpreter, the JVM and WASM: every CL FUNCTION
 * the frontends lower in OPERATOR position, taken as a first-class VALUE.
 *
 * <p>
 * The three backends share the text because the bug it pins was a cross-backend one -- a
 * name with an {@code evalCons}/{@code compileCons} case and no
 * {@code BuiltinFunctionWrappers} entry answered "The function NAME is undefined"
 * everywhere -- and the fix is one catalog. A per-class copy of the program would let one
 * backend's expectation drift; the fourth backend (the WASI component) covers it through
 * the {@code lowered-builtin-function-values} {@code ci-spec.yaml} case.
 */
public final class LoweredBuiltinValues {

	private LoweredBuiltinValues() {
	}

	/** The program. */
	public static final String PROGRAM = """
			(print (funcall #'coerce #("a" "b") 'list))
			(print (funcall #'elt '(1 2 3) 1))
			(print (funcall #'elt "abc" 2))
			(print (funcall #'endp '()))
			(print (apply #'list* '(1 2 (3 4))))
			(print (funcall #'vector 1 2 3))
			(print (funcall #'svref (vector 7 8 9) 1))
			(print (funcall #'array-rank (make-array '(2 3))))
			(print (funcall #'array-dimension (make-array '(2 3)) 1))
			(print (funcall #'array-total-size (make-array '(2 3))))
			(print (funcall #'array-row-major-index (make-array '(2 3)) 1 2))
			(print (funcall #'revappend '(1 2 3) '(4 5)))
			(print (funcall #'nreconc (list 1 2 3) (list 4 5)))
			(print (funcall #'map 'list #'1+ '(1 2 3)))
			(print (funcall #'map 'string #'char-upcase "abc"))
			(print (funcall #'map 'vector #'+ '(1 2) '(10 20)))
			(print (funcall #'map nil #'1+ '(1 2 3)))
			(print (funcall #'map-into (list 0 0 0) #'1+ '(1 2 3)))
			(print (funcall #'notany #'evenp '(1 3 5)))
			(print (funcall #'notevery #'evenp '(2 4 5)))
			(print (funcall #'typep 3 'integer))
			(print (funcall #'typep "x" 'integer))
			(print (funcall #'/= 1 2))
			(print (funcall #'readtable-case nil))
			""";

	/** What every backend prints for {@link #PROGRAM}. */
	public static final String OUTPUT = """
			("a" "b")
			2
			#\\c
			T
			(1 2 3 4)
			#(1 2 3)
			8
			2
			3
			6
			5
			(3 2 1 4 5)
			(3 2 1 4 5)
			(2 3 4)
			"ABC"
			#(11 22)
			NIL
			(2 3 4)
			T
			T
			T
			NIL
			T
			:UPCASE""";

}
