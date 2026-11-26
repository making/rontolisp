;;;; Sorting algorithms in rontolisp
;;;; Hand-written quicksort and merge sort over number lists, each parameterized
;;;; by a comparator passed as a first-class function, and cross-checked against
;;;; the built-in `sort`. Pure list/number code -> runs on all three backends
;;;; (interpreter / JVM / WASM).
;;;;
;;;; Run:
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/console/sorting.lisp
;;;;   java -jar ...-exec.jar examples/console/sorting.lisp -o Sorting.class && java Sorting
;;;;   java -jar ...-exec.jar examples/console/sorting.lisp -o sorting.wasm && wasmtime run sorting.wasm

;;; Quicksort: pivot on the first element, recurse on the two partitions.
;;; `less` is a two-argument comparator (e.g. #'< for ascending order).
(defun quicksort (less lst)
  (if (null lst)
      nil
      (let* ((pivot (car lst))
             (rest (cdr lst))
             (smaller (remove-if-not (lambda (x) (funcall less x pivot)) rest))
             (larger (remove-if (lambda (x) (funcall less x pivot)) rest)))
        (append (quicksort less smaller) (list pivot)
                (quicksort less larger)))))

;;; Merge two already-sorted lists under `less`.
(defun merge2 (less a b)
  (cond ((null a) b)
        ((null b) a)
        ((funcall less (car a) (car b)) (cons (car a) (merge2 less (cdr a) b)))
        (t (cons (car b) (merge2 less a (cdr b))))))

;;; Front `n` elements of a list.
(defun take (n lst)
  (if (or (= n 0) (null lst)) nil (cons (car lst) (take (- n 1) (cdr lst)))))

;;; Merge sort: split in half (using ash for portable integer halving), sort
;;; each half, then merge.
(defun merge-sort (less lst)
  (let ((n (length lst)))
    (if (<= n 1)
        lst
        (let* ((half (ash n -1))
               (front (take half lst))
               (back (nthcdr half lst)))
          (merge2 less (merge-sort less front) (merge-sort less back))))))

(defparameter *data* '(5 -3 8 -1 2 -7 4 0 6))

(format t "input:               ~a~%" *data*)
(format t "quicksort   (<):     ~a~%" (quicksort #'< *data*))
(format t "merge-sort  (<):     ~a~%" (merge-sort #'< *data*))
(format t "built-in sort (<):   ~a~%" (sort (copy-list *data*) #'<))
(format t "quicksort |x| desc:  ~a~%"
        (quicksort (lambda (a b) (> (abs a) (abs b))) *data*))
