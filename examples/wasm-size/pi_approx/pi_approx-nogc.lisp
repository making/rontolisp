;;;; pi_approx -- non-GC (--no-gc) edition
;;;; The companion to pi_approx.lisp: the same Leibniz loop as a --no-gc
;;;; reactor -- a plain MVP core module, no wasm-GC, called by name instead of
;;;; through `_start`.
;;;;
;;;; The loop itself is unchanged (--no-gc is a numeric subset, and f64
;;;; arithmetic is exactly what it is good at). Only the printing differs:
;;;; `format`'s directive interpreter is outside the subset, so this prints
;;;; with `princ`, which uses rontolisp's default float shape (6 significant
;;;; digits) rather than the reference programs' 15 decimal places. That is a
;;;; real difference in output, and it is why this variant is reported apart
;;;; from the main table rather than inside it.
;;;;
;;;; Run:
;;;;   rontolisp examples/wasm-size/pi_approx/pi_approx-nogc.lisp \
;;;;     -o pi-nogc.wasm --no-gc --optimize=size
;;;;   wasmtime run --invoke approx-pi pi-nogc.wasm

(defun approx-pi ()
  (let ((sum 0.0) (sign 1.0))
    (dotimes (i 1000000)
      (setq sum (+ sum (/ sign (+ (* 2.0 i) 1.0))))
      (setq sign (- sign)))
    (princ (* sum 4.0))
    (terpri)))

(rontolisp:wasm-export 'approx-pi :params '() :returns :void)
