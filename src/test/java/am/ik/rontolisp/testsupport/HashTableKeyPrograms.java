package am.ik.rontolisp.testsupport;

/**
 * The key a hash table hands back, pinned identically on the interpreter, the JVM and
 * both WASM backends ({@code .kb/hash-tables.md}, "The key read back"): an {@code equalp}
 * table places by the key's fold but hands back the key as it was first stored, through
 * every reader ({@code maphash}, {@code with-hash-table-iterator},
 * {@code loop ... being the hash-keys}), across a rehash, after a {@code remhash} and a
 * {@code clrhash}; and re-storing under a key the table already has keeps the first key
 * object, under every test. The answers are SBCL's.
 */
public final class HashTableKeyPrograms {

	private HashTableKeyPrograms() {
	}

	/** The program. Entries are printed sorted, since iteration order is unspecified. */
	public static final String PROGRAM = """
			(defun htk-entries (h)
			  (let ((acc nil))
			    (maphash (lambda (k v) (push (prin1-to-string (list k v)) acc)) h)
			    (sort acc #'string<)))
			(let ((h (make-hash-table :test 'equalp)))
			  (setf (gethash "hello" h) 1 (gethash 2.0 h) 2 (gethash 0.5 h) 3
			        (gethash (list "ab" #\\c) h) 4 (gethash #\\b h) 6)
			  (setf (gethash "HELLO" h) 5)
			  (print (htk-entries h))
			  (print (list (gethash "Hello" h) (gethash 2 h) (gethash 1/2 h)
			               (gethash (list "AB" #\\C) h) (hash-table-count h)))
			  (let ((acc nil))
			    (with-hash-table-iterator (next h)
			      (loop (multiple-value-bind (more k v) (next)
			              (unless more (return))
			              (push (prin1-to-string (list k v)) acc))))
			    (print (sort acc #'string<)))
			  (print (sort (loop for k being the hash-keys of h using (hash-value v)
			                     collect (prin1-to-string (list k v)))
			               #'string<))
			  (remhash "HeLLo" h)
			  (setf (gethash "HeLLo" h) 7)
			  (print (htk-entries h))
			  (clrhash h)
			  (setf (gethash "aBc" h) 8)
			  (print (htk-entries h)))
			(let ((h (make-hash-table :test 'equalp)))
			  (dotimes (i 20) (setf (gethash (format nil "k~D" i) h) i))
			  (dotimes (i 20) (setf (gethash (format nil "K~D" i) h) (* i 10)))
			  (print (list (hash-table-count h) (gethash "k19" h)
			               (every (lambda (s) (char= (char s 2) #\\k)) (htk-entries h)))))
			(let ((h (make-hash-table :test 'equal)) (a (copy-seq "a")) (b (copy-seq "a")))
			  (setf (gethash a h) 1)
			  (setf (gethash b h) 2)
			  (maphash (lambda (k v) (print (list (eq k a) (eq k b) v))) h))
			""";

	/** What {@link #PROGRAM} prints. */
	public static final String EXPECTED = """
			("(\\"hello\\" 5)" "(#\\\\b 6)" "((\\"ab\\" #\\\\c) 4)" "(0.5 3)" "(2.0 2)")
			(5 2 3 4 5)
			("(\\"hello\\" 5)" "(#\\\\b 6)" "((\\"ab\\" #\\\\c) 4)" "(0.5 3)" "(2.0 2)")
			("(\\"hello\\" 5)" "(#\\\\b 6)" "((\\"ab\\" #\\\\c) 4)" "(0.5 3)" "(2.0 2)")
			("(\\"HeLLo\\" 7)" "(#\\\\b 6)" "((\\"ab\\" #\\\\c) 4)" "(0.5 3)" "(2.0 2)")
			("(\\"aBc\\" 8)")
			(20 190 T)
			(T NIL 2)""";

}
