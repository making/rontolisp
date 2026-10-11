package am.ik.rontolisp.testsupport;

/**
 * A hash table's walk order and its copy, pinned identically on the interpreter, the JVM
 * and both WASM backends ({@code .kb/hash-tables.md}, "Representation" and "Copies share
 * structure"): {@code maphash} walks the keys in insertion order -- a re-store keeps its
 * place, a key removed and stored again goes last, and a table most of whose keys went
 * keeps the survivors' order -- and {@code rontolisp::%copy-equal-table} answers a fresh
 * {@code equal} table of the entries in that order which a later store into either table
 * leaves the other without, an {@code eql} or {@code equalp} table's keys placed again
 * under {@code equal} (each as first stored). The last form grows a table to 50,000 keys
 * one copy and one store at a time, every tenth-thousand version kept: a copy that walked
 * its source made that 1.25 x 10^9 entry copies, minutes on every backend.
 */
public final class HashTableCopyPrograms {

	private HashTableCopyPrograms() {
	}

	/** The program. */
	public static final String PROGRAM = """
			(defun htc-walk (h)
			  (let ((acc nil))
			    (maphash (lambda (k v) (push (list k v) acc)) h)
			    (nreverse acc)))
			(let ((h (make-hash-table :test 'equal)))
			  (dolist (k '("e" "b" "d" "a" "c")) (setf (gethash k h) 1))
			  (setf (gethash "b" h) 2)
			  (remhash "d" h)
			  (setf (gethash "d" h) 3)
			  (print (htc-walk h))
			  (dolist (k '("e" "a" "c")) (remhash k h))
			  (print (htc-walk h)))
			(let ((src (make-hash-table :test 'equal)) (k (list :c%keyword "a")) (v (vector 1 2)))
			  (dotimes (i 6) (setf (gethash i src) (* i 10)))
			  (setf (gethash k src) :list (gethash v src) :vector)
			  (let ((copy (rontolisp::%copy-equal-table src)))
			    (setf (gethash 2 copy) :copy (gethash 6 copy) :new)
			    (remhash 3 copy)
			    (setf (gethash 4 src) :src)
			    (remhash 0 src)
			    (print (htc-walk src))
			    (print (htc-walk copy))
			    (print (list (gethash (list :c%keyword "a") copy) (gethash v copy) (hash-table-test copy)))
			    (clrhash src)
			    (print (list (hash-table-count src) (hash-table-count copy) (gethash 5 copy)))))
			(let ((eqlt (make-hash-table :test 'eql)) (a (copy-seq "k")) (b (copy-seq "k")))
			  (setf (gethash a eqlt) 1 (gethash b eqlt) 2)
			  (let ((c (rontolisp::%copy-equal-table eqlt)))
			    (print (list (hash-table-count eqlt) (hash-table-count c) (hash-table-test c)
			                 (gethash "k" c) (eq (car (car (htc-walk c))) a)))))
			(let ((p (make-hash-table :test 'equalp)))
			  (setf (gethash "Key" p) 1 (gethash "KEY" p) 2 (gethash 1.0 p) :one)
			  (let ((c (rontolisp::%copy-equal-table p)))
			    (print (list (hash-table-test c) (htc-walk c) (gethash "KEY" c) (gethash 1 c)))))
			(let ((h (make-hash-table :test 'equal)) (versions nil))
			  (dotimes (i 50000)
			    (setq h (rontolisp::%copy-equal-table h))
			    (setf (gethash i h) (- i))
			    (when (zerop (mod i 10000)) (push h versions)))
			  (print (list (hash-table-count h) (gethash 49999 h) (mapcar #'hash-table-count versions)
			               (gethash 20001 (second versions) :absent) (gethash 30001 (second versions) :absent))))
			""";

	/** What {@link #PROGRAM} prints. */
	public static final String EXPECTED = """
			(("e" 1) ("b" 2) ("a" 1) ("c" 1) ("d" 3))
			(("b" 2) ("d" 3))
			((1 10) (2 20) (3 30) (4 :SRC) (5 50) ((:C%KEYWORD "a") :LIST) (#(1 2) :VECTOR))
			((0 0) (1 10) (2 :COPY) (4 40) (5 50) ((:C%KEYWORD "a") :LIST) (#(1 2) :VECTOR) (6 :NEW))
			(:LIST :VECTOR EQUAL)
			(0 8 50)
			(2 1 EQUAL 2 T)
			(EQUAL (("Key" 2) (1.0 :ONE)) NIL NIL)
			(50000 -49999 (40001 30001 20001 10001 1) -20001 :ABSENT)""";

}
