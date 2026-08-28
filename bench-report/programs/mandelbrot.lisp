;;; mandelbrot -- double-float arithmetic in a tight loop.
;;;
;;; A 400x400 grid, 200 iterations per point, counting the points that stay
;;; bounded. No arrays and no allocation beyond the floats themselves, so what
;;; it separates is unboxed floating point from boxed.
(defun escapes-p (cr ci limit)
  (let ((zr 0.0d0) (zi 0.0d0))
    (dotimes (i limit nil)
      (let ((zr2 (* zr zr)) (zi2 (* zi zi)))
        (when (> (+ zr2 zi2) 4.0d0) (return t))
        (setq zi (+ (* 2.0d0 zr zi) ci))
        (setq zr (+ (- zr2 zi2) cr))))))

(defun bench ()
  (let ((n 400) (inside 0))
    (dotimes (y n inside)
      (dotimes (x n)
        (let ((cr (- (/ (* 3.0d0 x) n) 2.0d0)) (ci (- (/ (* 3.0d0 y) n) 1.5d0)))
          (unless (escapes-p cr ci 200) (setq inside (+ inside 1))))))))

;; Every benchmark ends with this identical footer: run BENCH once, print the
;; answer and the milliseconds it took, both on one line the harness parses.
;; internal-time-units-per-second differs per implementation (1000 here, a
;; million on SBCL), so the elapsed count is normalised rather than reported raw.
(let* ((start (get-internal-real-time))
       (answer (bench))
       (elapsed (- (get-internal-real-time) start)))
  (format t "result=~a ms=~a~%" answer
          (round (* 1000 elapsed) internal-time-units-per-second)))
