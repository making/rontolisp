;;;; Line-numbering file tool (like `cat -n`) in rontolisp
;;;; Writes a small sample text file, reads it back line by line, produces a
;;;; line-numbered copy, and reports line and character counts. Uses only
;;;; with-open-file, read-line, write-line, length and `format nil` -> runs on
;;;; all three backends (interpreter / JVM / WASM).
;;;;
;;;; NOTE: the WASM backend needs a preopened directory for file access, and
;;;; with-open-file rides the exception-handling proposal:
;;;;   wasmtime run --dir . line-numbers.wasm
;;;;
;;;; Run:
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/console/line-numbers.lisp
;;;;   java -jar ...-exec.jar examples/console/line-numbers.lisp -o LineNumbers.class && java LineNumbers
;;;;   java -jar ...-exec.jar examples/console/line-numbers.lisp -o ln.wasm && wasmtime run --dir . ln.wasm

(defparameter *src* "poem.txt")
(defparameter *dst* "poem-numbered.txt")

;;; Create a small input file so the example is self-contained.
(with-open-file (out *src* :direction :output)
  (write-line "the quick brown fox" out)
  (write-line "jumps over" out)
  (write-line "the lazy dog" out))

;;; Read every line from `in`, write it to `out` prefixed with a right-aligned
;;; line number, and return (line-count . char-count). `format` only writes to
;;; t/nil, so we build each numbered line with `format nil` and `write-line` it.
(defun number-file (in out)
  (let ((n 0) (chars 0) (line (read-line in)))
    (while line
      (setq n (+ n 1))
      (setq chars (+ chars (length line)))
      (write-line (format nil "~4d  ~a" n line) out)
      (setq line (read-line in)))
    (cons n chars)))

(let ((counts nil))
  (with-open-file (in *src*)
    (with-open-file (out *dst* :direction :output)
      (setq counts (number-file in out))))
  (format t "Wrote ~d lines (~d characters) to ~a~%~%" (car counts) (cdr counts)
          *dst*)
  (format t "Contents of ~a:~%" *dst*)
  (with-open-file (in *dst*)
    (let ((line (read-line in)))
      (while line
        (princ line)
        (terpri)
        (setq line (read-line in))))))
