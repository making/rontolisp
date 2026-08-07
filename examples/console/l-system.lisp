;;;; L-system (Lindenmayer system) fractal generator in rontolisp
;;;; Demonstrates string rewriting systems and hash-table rule dispatch.
;;;; Runs on all three backends (interpreter / JVM / WASM).
;;;;
;;;; Run:
;;;;   rontolisp examples/console/l-system.lisp
;;;;   rontolisp examples/console/l-system.lisp -o LSystem.class && java LSystem
;;;;   rontolisp examples/console/l-system.lisp -o l-system.wasm && wasmtime run -W gc l-system.wasm

(defun make-rule-table (&rest pairs)
  "Build a hash table from alternating key-value pairs."
  (let ((table (make-hash-table)))
    (dotimes (i (/ (length pairs) 2))
      (setf (gethash (nth (* i 2) pairs) table) (nth (1+ (* i 2)) pairs)))
    table))

(defun l-system-step (current rules)
  "Apply one iteration of L-system rules to CURRENT string."
  (let ((next ""))
    (dotimes (i (length current))
      (let ((ch (char current i)))
        (setq next
              (concatenate 'string next
                           (or (gethash ch rules) (format nil "~a" ch))))))
    next))

(defun l-system-string (axiom rules iterations)
  "Generate the L-system string after N iterations."
  (let ((current axiom))
    (dotimes (_ iterations) (setq current (l-system-step current rules)))
    current))

;;; Count occurrences of each character in a string
(defun char-counts (s)
  "Return a hash table of character -> count for string S."
  (let ((counts (make-hash-table)))
    (dotimes (i (length s))
      (setf (gethash (char s i) counts)
            (1+ (or (gethash (char s i) counts) 0))))
    counts))

(format t "L-system String Rewriting~%~%")

;;; Sierpinski triangle: F->FX, X->XFX
(let ((rules (make-rule-table #\F "FX" #\X "XFX")))
  (format t "Sierpinski triangle (F->FX, X->XFX):~%")
  (let ((axiom "F-X"))
    (dotimes (n 5)
      (let ((s (l-system-string axiom rules n)))
        (format t "  ~d: length=~6d  ~a~%" n (length s) s))))

  (format t "~%Character distribution at iteration 6:~%")
  (let ((s (l-system-string "F-X" rules 6)))
    (format t "  Total length: ~d~%" (length s))
    (let ((counts (char-counts s)))
      (maphash (lambda (ch count) (format t "  ~a: ~d~%" ch count)) counts))))

(format t "~%")

;;; Koch curve: F->F+F-F+F
(let ((rules (make-rule-table #\F "F+F-F+F")))
  (format t "Koch curve (F->F+F-F+F):~%")
  (dotimes (n 4)
    (let ((s (l-system-string "F" rules n)))
      (format t "  ~d: length=~6d  ~a~%" n (length s) s)))

  (format t "~%At iteration 3:~%")
  (let ((s (l-system-string "F" rules 3)))
    (format t "  Length: ~d characters~%" (length s))
    (let ((plus 0) (minus 0))
      (dotimes (i (length s))
        (when (char= (char s i) #\+) (setq plus (1+ plus)))
        (when (char= (char s i) #\-) (setq minus (1+ minus))))
      (format t "  Plus signs: ~d~%" plus)
      (format t "  Minus signs: ~d~%" minus))))

(format t "~%")

;;; Dragon curve: X->X+YF+, Y->-FX-Y
(let ((rules (make-rule-table #\X "X+YF+" #\Y "-FX-Y")))
  (format t "Dragon curve (X->X+YF+, Y->-FX-Y):~%")
  (dotimes (n 8)
    (let ((s (l-system-string "FX" rules n)))
      (format t "  ~d: length=~6d~%" n (length s))))

  (format t "~%At iteration 12:~%")
  (let ((s (l-system-string "FX" rules 12)))
    (format t "  Length: ~d characters~%" (length s))
    (let ((counts (char-counts s)))
      (maphash (lambda (ch count) (format t "  ~a: ~d~%" ch count)) counts))))
