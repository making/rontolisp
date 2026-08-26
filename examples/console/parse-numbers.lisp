;;;; Numeric-column + character-classification demo in rontolisp
;;;; Writes a small data file, reads it back line by line, turns each line into
;;;; an integer with parse-integer, and reports the count, sum, min and max.
;;;; Then classifies the characters of a string with the character predicates.
;;;; Uses with-open-file, read-line, parse-integer, char, alpha-char-p,
;;;; digit-char-p -> runs on all three backends (interpreter / JVM / WASM).
;;;;
;;;; NOTE: the WASM backend needs a preopened directory for file access, and
;;;; with-open-file rides the exception-handling proposal:
;;;;   wasmtime run --dir . parse-numbers.wasm
;;;;
;;;; Run:
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/console/parse-numbers.lisp
;;;;   java -jar ...-exec.jar examples/console/parse-numbers.lisp -o ParseNumbers.class && java ParseNumbers
;;;;   java -jar ...-exec.jar examples/console/parse-numbers.lisp -o pn.wasm && wasmtime run --dir . pn.wasm

(defparameter *data* "numbers.txt")

;;; Create a small input file so the example is self-contained.
(with-open-file (out *data* :direction :output)
  (write-line "10" out)
  (write-line "25" out)
  (write-line "3" out)
  (write-line "42" out)
  (write-line "17" out))

;;; Fold the integers in `in` (one per line) into (count sum min max).
(defun summarize (in)
  (let ((count 0) (sum 0) (lo nil) (hi nil) (line (read-line in)))
    (while line
      (let ((n (parse-integer line)))
        (setq count (+ count 1))
        (setq sum (+ sum n))
        (when (or (null lo) (< n lo)) (setq lo n))
        (when (or (null hi) (> n hi)) (setq hi n)))
      (setq line (read-line in)))
    (list count sum lo hi)))

(with-open-file (in *data*)
  (let ((stats (summarize in)))
    (format t "count=~d sum=~d min=~d max=~d~%" (first stats) (second stats)
            (third stats) (fourth stats))))

;;; Character classification: count the letters and digits in a string by
;;; indexing it with `char` and testing each character.
(defun count-kinds (s)
  (let ((i 0) (n (length s)) (letters 0) (digits 0))
    (while (< i n)
      (let ((c (char s i)))
        (when (alpha-char-p c) (setq letters (+ letters 1)))
        (when (digit-char-p c) (setq digits (+ digits 1))))
      (setq i (+ i 1)))
    (list letters digits)))

(let ((kinds (count-kinds "rontolisp 0.1 (2026)")))
  (format t "letters=~d digits=~d~%" (first kinds) (second kinds)))
