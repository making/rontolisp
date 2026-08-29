;;; sort -- SORT over a 400,000-element vector of integers.
;;;
;;; The one benchmark whose work is done inside the implementation's own
;;; library rather than in the benchmark's code, so it measures a built-in
;;; sequence function and the predicate calls it makes.
;;;
;;; The input is a linear congruential generator rather than RANDOM: every
;;; implementation seeds and steps its own RANDOM differently, and a benchmark
;;; whose answer depends on which one ran is not checkable.
(declaim (optimize (speed 3) (safety 0) (debug 0)))

(defun lcg-vector (n seed)
  (declare (type fixnum n seed))
  (let ((v (make-array n)) (s seed))
    (declare (type simple-vector v) (type fixnum s))
    (dotimes (i n v)
      (setq s (mod (+ (* s 1103515245) 12345) 2147483648))
      (setf (aref v i) s))))

(defun bench ()
  (let ((v (sort (lcg-vector 400000 42) #'<)))
    (declare (type simple-vector v))
    (+ (aref v 0) (aref v 200000) (aref v 399999))))

;; Every benchmark ends with this identical footer: run BENCH once, print the
;; answer and the milliseconds it took, both on one line the harness parses.
;; internal-time-units-per-second differs per implementation (1000 here, a
;; million on SBCL), so the elapsed count is normalised rather than reported raw.
(let* ((start (get-internal-real-time))
       (answer (bench))
       (elapsed (- (get-internal-real-time) start)))
  (format t "result=~a ms=~a~%" answer
          (round (* 1000 elapsed) internal-time-units-per-second)))
