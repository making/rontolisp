;;;; Roman numeral encoder and decoder in rontolisp
;;;; Demonstrates association lists, string concatenation, recursion,
;;;; and a round-trip correctness check over all 3999 values.
;;;; Runs on all three backends (interpreter / JVM / WASM).
;;;;
;;;; Run:
;;;;   rontolisp examples/console/roman.lisp
;;;;   rontolisp examples/console/roman.lisp -o Roman.class && java Roman
;;;;   rontolisp examples/console/roman.lisp -o roman.wasm && wasmtime run -W gc roman.wasm

;;; Mapping of integer values to Roman numeral strings, sorted descending.
(defparameter *roman-values*
  (list (cons 1000 "M") (cons 900 "CM") (cons 500 "D") (cons 400 "CD")
        (cons 100 "C") (cons 90 "XC") (cons 50 "L") (cons 40 "XL")
        (cons 10 "X") (cons 9 "IX") (cons 5 "V") (cons 4 "IV") (cons 1 "I")))

;;; Mapping of Roman numeral characters to integer values.
(defparameter *roman-char-table*
  (list (cons #\M 1000) (cons #\D 500) (cons #\C 100) (cons #\L 50)
        (cons #\X 10) (cons #\V 5) (cons #\I 1)))

(defun integer-to-roman (n)
  "Convert an integer (1-3999) to a Roman numeral string."
  (when (or (< n 1) (> n 3999))
    (error "INTEGER-TO-ROMAN: ~d is out of range (1-3999)" n))
  (let ((result ""))
    (dolist (pair *roman-values*)
      (let ((value (car pair))
            (numeral (cdr pair)))
        (while (>= n value)
          (setq result (concatenate 'string result numeral))
          (setq n (- n value)))))
    result))

(defun roman-to-integer (s)
  "Convert a Roman numeral string to an integer.
   Algorithm: scan right-to-left; add if current >= last, else subtract."
  (let ((result 0)
        (last 0)
        (i (1- (length s))))
    (while (>= i 0)
      (let ((ch (char-upcase (char s i))))
        (setq i (1- i))
        (let ((current (cdr (assoc ch *roman-char-table*))))
          (when (null current)
            (error "ROMAN-TO-INTEGER: invalid character ~a" ch))
          (if (>= current last)
              (setq result (+ result current))
              (setq result (- result current)))
          (setq last current))))
    result))

(format t "Integer -> Roman:~%")
(dolist (n '(1 4 9 14 42 99 399 400 999 1999 2024 3999))
  (format t "  ~4d -> ~a~%" n (integer-to-roman n)))

(format t "~%Roman -> Integer:~%")
(dolist (s '("I" "IV" "IX" "XIV" "XLII" "XCIX" "CMXCIX" "MCMXCIX" "MMXXIV"))
  (format t "  ~-6s -> ~a~%" s (roman-to-integer s)))

(format t "~%Round-trip check (1..3999):~%")
(let ((all-match t))
  (dotimes (i 3999)
    (let ((n (1+ i)))
      (when (not (= n (roman-to-integer (integer-to-roman n))))
        (format t "  MISMATCH at ~d!~%" n)
        (setq all-match nil))))
  (when all-match
    (format t "  All 3999 round-trips passed!~%")))
