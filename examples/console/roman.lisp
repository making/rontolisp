;;;; Roman numeral encoder and decoder in rontolisp
;;;; Demonstrates association lists, string concatenation, recursion -- and an
;;;; example that CHECKS ITSELF: the 1..3999 round-trip is a rove assertion, so
;;;; a broken encoder fails the run instead of printing a line nobody reads.
;;;; Runs on all three backends (interpreter / JVM / WASM).
;;;;
;;;; rove is loaded with asdf, so pass the directories holding its .asd files
;;;; (rove, dissect and cl-ppcre, all vendored in this repository) with
;;;; --system-path; outside this repository (ql:quickload "rove") fetches the
;;;; same sources instead. The compile paths splice the system in at compile
;;;; time, so the produced class / module is self-contained. See the Testing
;;;; guide: doc/en/guides/testing.md
;;;;
;;;; Run:
;;;;   SP=src/test/resources/rove:src/test/resources/dissect:src/test/resources/cl-ppcre
;;;;   rontolisp examples/console/roman.lisp --system-path $SP
;;;;   rontolisp examples/console/roman.lisp --system-path $SP -o Roman.class && java Roman
;;;;   rontolisp examples/console/roman.lisp --system-path $SP -o roman.wasm && \
;;;;     wasmtime run -W gc -W exceptions=y roman.wasm

(asdf:load-system :rove)
(use-package :rove)
;; rove colors its report for a terminal; a checked pipeline wants plain text.
(setf *enable-colors* nil)

;;; Mapping of integer values to Roman numeral strings, sorted descending.
(defparameter *roman-values*
  (list (cons 1000 "M") (cons 900 "CM") (cons 500 "D") (cons 400 "CD")
        (cons 100 "C") (cons 90 "XC") (cons 50 "L") (cons 40 "XL") (cons 10 "X")
        (cons 9 "IX") (cons 5 "V") (cons 4 "IV") (cons 1 "I")))

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
      (let ((value (car pair)) (numeral (cdr pair)))
        (while (>= n value)
          (setq result (concatenate 'string result numeral))
          (setq n (- n value)))))
    result))

(defun roman-to-integer (s)
  "Convert a Roman numeral string to an integer.
   Algorithm: scan right-to-left; add if current >= last, else subtract."
  (let ((result 0) (last 0) (i (1- (length s))))
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

;;; --- the demonstration ------------------------------------------------------

(format t "Integer -> Roman:~%")
(dolist (n '(1 4 9 14 42 99 399 400 999 1999 2024 3999))
  (format t "  ~4d -> ~a~%" n (integer-to-roman n)))

(format t "~%Roman -> Integer:~%")
(dolist (s '("I" "IV" "IX" "XIV" "XLII" "XCIX" "CMXCIX" "MCMXCIX" "MMXXIV"))
  (format t "  ~-6s -> ~a~%" s (roman-to-integer s)))

(terpri)

;;; --- the assertions ---------------------------------------------------------

(deftest encoding
  (testing "the subtractive pairs and the extremes"
    (ok (string= (integer-to-roman 1) "I"))
    (ok (string= (integer-to-roman 4) "IV"))
    (ok (string= (integer-to-roman 400) "CD"))
    (ok (string= (integer-to-roman 3999) "MMMCMXCIX")))
  (testing "out of range"
    (ok (signals (integer-to-roman 0)))
    (ok (signals (integer-to-roman 4000)))))

(deftest decoding
  (testing "a smaller numeral before a larger one subtracts"
    (ok (= (roman-to-integer "MCMXCIX") 1999))
    (ok (= (roman-to-integer "XLII") 42)))
  (testing "lowercase is accepted" (ok (= (roman-to-integer "mmxxiv") 2024)))
  (testing "an unknown character is an error"
    (ok (signals (roman-to-integer "MMZ")))))

;;; The whole point of the pair: every value in range survives the trip out and
;;; back. Reported as ONE assertion carrying the first counter-example, so a
;;; broken encoder names the value it broke on instead of printing 3999 lines.
(deftest round-trip
  (testing "every integer 1..3999 encodes and decodes back to itself"
    (let ((mismatch nil))
      (dotimes (i 3999)
        (let ((n (1+ i)))
          (when (and (null mismatch)
                     (/= n (roman-to-integer (integer-to-roman n))))
            (setq mismatch n))))
      (ok (null mismatch)
          (if mismatch
              (format nil "first mismatch at ~d" mismatch)
              "all 3999 round-trips")))))

;;; Loading this file runs its suite (rove's file-driven entry point), and the
;;; exit code is the verdict -- which is what makes a broken example fail a
;;; build instead of scrolling past.
(uiop:quit (if (run-suite *package*) 0 1))
