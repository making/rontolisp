;;;; pi_approx -- --no-gc edition: the same Leibniz loop as a reactor.
;;;; princ instead of format (outside the subset), so it prints the shortest
;;;; round-trip decimal, not 15 fixed decimals -- hence reported apart from
;;;; the main table.
;;;;
;;;; Run:
;;;;   rontolisp size-report/programs/pi_approx/pi_approx-nogc.lisp \
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
