;;;; pi_approx -- Leibniz formula (pi/4 = 1 - 1/3 + 1/5 - ...), 1M iterations.
;;;; Measures what the number tower and float formatting cost in the artifact.
;;;; Every literal is a float so the sum stays off the exact-rational path.
;;;;
;;;; Run:
;;;;   rontolisp size-report/programs/pi_approx/pi_approx.lisp -o pi.wasm --optimize=size
;;;;   wasmtime run pi.wasm

(let ((sum 0.0) (sign 1.0))
  (dotimes (i 1000000)
    (setq sum (+ sum (/ sign (+ (* 2.0 i) 1.0))))
    (setq sign (- sign)))
  (format t "pi = ~,15F~%" (* sum 4.0)))
