;;; fib -- naive recursive Fibonacci.
;;;
;;; Nothing but function calls and small-integer arithmetic: 18 million calls,
;;; no allocation, no data structure. What it separates is how cheap a call is.
(defun fib (n) (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))

(defun bench () (fib 34))

;; Every benchmark ends with this identical footer: run BENCH once, print the
;; answer and the milliseconds it took, both on one line the harness parses.
;; internal-time-units-per-second differs per implementation (1000 here, a
;; million on SBCL), so the elapsed count is normalised rather than reported raw.
(let* ((start (get-internal-real-time))
       (answer (bench))
       (elapsed (- (get-internal-real-time) start)))
  (format t "result=~a ms=~a~%" answer
          (round (* 1000 elapsed) internal-time-units-per-second)))
