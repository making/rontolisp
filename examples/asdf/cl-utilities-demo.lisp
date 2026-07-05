;; Loads the REAL cl-utilities v1.2.4 (public domain) via asdf:load-system and
;; exercises its whole public API. Run with:
;;   rontolisp examples/asdf/cl-utilities-demo.lisp --system-path src/test/resources/cl-utilities
;; (see examples/asdf/README.md for the compile-path variants).

(asdf:load-system :cl-utilities)

;; split-sequence (cl-utilities' own copy, via apply #'position)
(print (cl-utilities:split-sequence #\, "a,b,,c"))
(print (cl-utilities:split-sequence #\, "a,b,,c" :remove-empty-subseqs t))
(print (cl-utilities:split-sequence-if #'evenp '(1 2 3 4 5)))
(print (cl-utilities:split-sequence-if-not #'oddp '(1 2 3 4 5)))

;; extremum family (once-only / with-check-length macro templates)
(print (cl-utilities:extremum '(3 1 4 1 5 9 2 6) #'<))
(print (cl-utilities:extremum '(3 1 4 1 5 9 2 6) #'>))
(print (cl-utilities:extremum '((1 . "one") (3 . "three") (2 . "two")) #'> :key #'car))
(print (cl-utilities:extremum '(9 8 3 1 2) #'< :start 2))
(print (cl-utilities:extremum-fastkey '(3 1 4 1 5) #'< :key #'identity))
(print (cl-utilities:extrema '(3 1 4 1 5 9 2 6 1) #'<))
(print (cl-utilities:n-most-extreme 3 '(3 1 4 1 5 9 2 6) #'<))

;; read-delimited (read-char + multiple-value-setq + (setf (elt ...)))
(print (with-input-from-string (s "hello,world")
         (let ((buf (make-array 20 :initial-element nil)))
           (multiple-value-bind (pos found) (cl-utilities:read-delimited buf s :delimiter #\,)
             (list pos found (subseq (coerce buf 'list) 0 pos))))))

;; expt-mod
(print (cl-utilities:expt-mod 2 10 1000))
(print (cl-utilities:expt-mod 12 34 235))

;; collecting / with-collectors (tail collection)
(print (cl-utilities:collecting
         (dotimes (x 5)
           (cl-utilities:collect (* x x)))))
(print (multiple-value-list
        (cl-utilities:with-collectors (evens odds)
          (dolist (n '(1 2 3 4 5 6))
            (if (evenp n) (evens n) (odds n))))))

;; once-only / with-unique-names / with-gensyms used from user macros
(defmacro my-square (x)
  (cl-utilities:once-only (x)
    `(* ,x ,x)))
(let ((counter 0))
  (flet ((bump () (incf counter)))
    (print (my-square (bump)))
    (print counter)))

(defmacro my-swap (a b)
  (cl-utilities:with-unique-names (tmp)
    `(let ((,tmp ,a))
       (setq ,a ,b)
       (setq ,b ,tmp)
       (list ,a ,b))))
(let ((p 1) (q 2))
  (print (my-swap p q)))

(defmacro my-double (x)
  (cl-utilities:with-gensyms (g)
    `(let ((,g ,x)) (+ ,g ,g))))
(print (my-double 21))

;; rotate-byte (return-from)
(print (cl-utilities:rotate-byte 3 (byte 8 0) 1))
(print (cl-utilities:rotate-byte 2 (byte 8 0) 255))
(print (cl-utilities:rotate-byte -1 (byte 4 0) 1))

;; copy-array (apply #'make-array)
(let* ((a (vector 1 2 3))
       (b (cl-utilities:copy-array a)))
  (setf (aref b 0) 99)
  (print (list (aref a 0) (aref b 0))))

;; compose (reduce #'funcall :from-end)
(print (funcall (cl-utilities:compose #'1+ #'1+) 40))
(print (mapcar (cl-utilities:compose #'car #'cdr) '((1 2 3) (4 5 6))))
