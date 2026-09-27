package am.ik.rontolisp;

/**
 * A program that hands every sequence operator, array accessor and hash-table accessor a
 * value that is not the sequence, array or hash table it reads -- directly, as a function
 * value and through the lowerings that rewrite one operator into another -- and prints
 * the {@code type-error} each signals: the operator it names, the datum and the expected
 * type ({@code SEQUENCE}, {@code ARRAY}, {@code HASH-TABLE}). Several of them answered
 * silently (nil, the value itself) or refused with a {@code simple-error} or a host cast
 * failure that carried no datum. Shared by the backend suites, so every backend is held
 * to one expected text; {@code ci-spec.yaml}'s
 * {@code sequence-and-accessor-operators-name-their-wrong-type-argument} pins the same
 * rows on the native binary.
 */
public final class WrongTypeArgumentFixture {

	private WrongTypeArgumentFixture() {
	}

	/** The program. */
	public static final String SOURCE = """
			(defun te (thunk)
			  (handler-case (list :value (funcall thunk))
			    (type-error (e) (list (princ-to-string e) (type-error-datum e) (type-error-expected-type e)))
			    (error (e) (list :not-a-type-error (princ-to-string e)))))
			(defvar *wt-five* 5)
			(defvar *wt-grid* (make-array '(2 2) :initial-element 0))
			(defvar *wt-table* (make-hash-table))
			(print (te (lambda () (every #'numberp *wt-five*))))
			(print (te (lambda () (notany #'numberp *wt-five*))))
			(print (te (lambda () (sort *wt-five* #'<))))
			(print (te (lambda () (stable-sort *wt-five* #'<))))
			(print (te (lambda () (sort *wt-five* #'< :key #'identity))))
			(print (te (lambda () (find 1 *wt-five*))))
			(print (te (lambda () (position-if #'numberp *wt-five*))))
			(print (te (lambda () (find 1 *wt-grid*))))
			(print (te (lambda () (count 1 *wt-five*))))
			(print (te (lambda () (count-if-not #'numberp *wt-five*))))
			(print (te (lambda () (remove 1 *wt-five*))))
			(print (te (lambda () (delete-if #'numberp *wt-five*))))
			(print (te (lambda () (nsubstitute 2 1 *wt-five*))))
			(print (te (lambda () (remove-duplicates *wt-five*))))
			(print (te (lambda () (reduce #'+ *wt-five*))))
			(print (te (lambda () (reduce #'+ *wt-five* :start 0))))
			(print (te (lambda () (subseq *wt-five* 0))))
			(print (te (lambda () (copy-seq *wt-five*))))
			(print (te (lambda () (fill *wt-five* 0))))
			(print (te (lambda () (replace (list 1 2) *wt-five*))))
			(print (te (lambda () (concatenate 'list '(1) *wt-five*))))
			(print (te (lambda () (concatenate 'string *wt-five*))))
			(print (te (lambda () (coerce *wt-five* 'vector))))
			(print (te (lambda () (coerce *wt-five* 'string))))
			(print (te (lambda () (coerce *wt-five* '(vector (unsigned-byte 8))))))
			(print (te (lambda () (map 'list #'identity *wt-five*))))
			(print (te (lambda () (map-into *wt-five* #'identity))))
			(print (te (lambda () (mismatch '(1) *wt-five*))))
			(print (te (lambda () (search '(1) *wt-five*))))
			(print (te (lambda () (funcall #'find 1 *wt-five*))))
			(print (te (lambda () (funcall #'map 'list #'+ '(1) *wt-five*))))
			(print (te (lambda () (funcall #'concatenate 'list *wt-five*))))
			(print (te (lambda () (funcall #'copy-seq *wt-five*))))
			(print (te (lambda () (aref *wt-five* 0))))
			(print (te (lambda () (svref *wt-table* 0))))
			(print (te (lambda () (setf (aref *wt-five* 0) 1))))
			(print (te (lambda () (row-major-aref *wt-five* 0))))
			(print (te (lambda () (array-rank *wt-five*))))
			(print (te (lambda () (apply #'aref *wt-five* '(0)))))
			(print (te (lambda () (+ 1 (aref *wt-five* 0)))))
			(print (te (lambda () (elt *wt-five* 0))))
			(print (te (lambda () (gethash 1 *wt-five*))))
			(print (te (lambda () (setf (gethash 1 *wt-five*) 2))))
			(print (te (lambda () (remhash 1 *wt-five*))))
			(print (te (lambda () (maphash #'list *wt-five*))))
			(print (te (lambda () (hash-table-count *wt-five*))))
			(print (te (lambda () (hash-table-rehash-size *wt-five*))))
			(print (te (lambda () (funcall #'gethash 1 *wt-five*))))
			(print (te (lambda () (gethash 1 (vector 1)))))
			(print (list (find 2 (vector 1 2)) (coerce #(1 2) 'list) (sort (vector 3 1 2) #'<) (subseq "abc" 1)
			             (coerce '(1 2) 'vector) (aref *wt-grid* 1 1) (gethash 1 *wt-table* :none)))
			""";

	/** What {@link #SOURCE} prints on every backend. */
	public static final String EXPECTED = """
			("EVERY: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("SOME: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("SORT: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("STABLE-SORT: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("STABLE-SORT: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("FIND: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("POSITION-IF: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("FIND: The value #2A((0 0) (0 0)) is not of type SEQUENCE" #2A((0 0) (0 0)) SEQUENCE)
			("COUNT: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("COUNT-IF-NOT: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("REMOVE: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("DELETE-IF: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("NSUBSTITUTE: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("REMOVE-DUPLICATES: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("REDUCE: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("REDUCE: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("SUBSEQ: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("SUBSEQ: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("FILL: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("REPLACE: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("CONCATENATE: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("CONCATENATE: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("COERCE: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("COERCE: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("COERCE: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("MAP: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("MAP-INTO: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("MISMATCH: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("SEARCH: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("FIND: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("MAP: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("CONCATENATE: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("SUBSEQ: The value 5 is not of type SEQUENCE" 5 SEQUENCE)
			("AREF: The value 5 is not of type ARRAY" 5 ARRAY)
			("AREF: The value #<HASH-TABLE :TEST EQUAL :COUNT 0> is not of type ARRAY" #<HASH-TABLE :TEST EQUAL :COUNT 0> ARRAY)
			("(SETF AREF): The value 5 is not of type ARRAY" 5 ARRAY)
			("ROW-MAJOR-AREF: The value 5 is not of type ARRAY" 5 ARRAY)
			("ARRAY-DIMENSIONS: The value 5 is not of type ARRAY" 5 ARRAY)
			("AREF: The value 5 is not of type ARRAY" 5 ARRAY)
			("AREF: The value 5 is not of type ARRAY" 5 ARRAY)
			("AREF: The value 5 is not of type ARRAY" 5 ARRAY)
			("GETHASH: The value 5 is not of type HASH-TABLE" 5 HASH-TABLE)
			("(SETF GETHASH): The value 5 is not of type HASH-TABLE" 5 HASH-TABLE)
			("REMHASH: The value 5 is not of type HASH-TABLE" 5 HASH-TABLE)
			("MAPHASH: The value 5 is not of type HASH-TABLE" 5 HASH-TABLE)
			("HASH-TABLE-COUNT: The value 5 is not of type HASH-TABLE" 5 HASH-TABLE)
			("HASH-TABLE-REHASH-SIZE: The value 5 is not of type HASH-TABLE" 5 HASH-TABLE)
			("GETHASH: The value 5 is not of type HASH-TABLE" 5 HASH-TABLE)
			("GETHASH: The value #(1) is not of type HASH-TABLE" #(1) HASH-TABLE)
			(2 (1 2) #(1 2 3) "bc" #(1 2) 0 :NONE)""";

}
