;;;; pi_approx -- a million-iteration floating-point loop
;;;; Leibniz formula: pi/4 = 1 - 1/3 + 1/5 - 1/7 + ...
;;;;
;;;; The second size-comparison program: unlike hello_world it drags real
;;;; arithmetic and real number formatting into the module, so the artifact
;;;; size stops being "what does the runtime cost to start" and becomes "what
;;;; does the language's number tower cost to carry".
;;;;
;;;; Every literal is a float (`0.0`, `2.0`), so the accumulator never enters
;;;; rontolisp's exact-rational path -- `(/ 1 3)` would be the ratio 1/3, and
;;;; a million exact terms is not what the other languages are measuring.
;;;;
;;;; Run:
;;;;   rontolisp examples/wasm-size/pi_approx/pi_approx.lisp
;;;;   rontolisp examples/wasm-size/pi_approx/pi_approx.lisp -o pi.wasm --optimize=size
;;;;   wasmtime run -W gc pi.wasm

(let ((sum 0.0) (sign 1.0))
  (dotimes (i 1000000)
    (setq sum (+ sum (/ sign (+ (* 2.0 i) 1.0))))
    (setq sign (- sign)))
  (format t "pi = ~,15F~%" (* sum 4.0)))
