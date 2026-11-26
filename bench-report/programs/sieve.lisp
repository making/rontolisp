;;; sieve -- Sieve of Eratosthenes up to 2,000,000, three times over.
;;;
;;; A single simple-vector written and read a few million times per round. What it
;;; separates is element access on a general vector: the answer, 148933, is the
;;; number of primes below the limit.
(declaim (optimize (speed 3) (safety 0) (debug 0)))

(declaim (ftype (function (fixnum) fixnum) sieve))

(defun sieve (n)
  (declare (type fixnum n))
  (let ((flags (make-array (+ n 1) :initial-element 1)) (found 0))
    (declare (type simple-vector flags) (type fixnum found))
    (do ((i 2 (+ i 1)))
        ((> i n) found)
      (declare (type fixnum i))
      (when (= 1 (aref flags i))
        (setq found (+ found 1))
        ;; J starts at (* i i), which passes the 31-bit fixnum ABCL has, so its
        ;; declaration is the range J really takes and not FIXNUM: a promise
        ;; narrower than the value silently wraps at (safety 0) and the inner
        ;; loop then never ends.
        (do ((j (* i i) (+ j i)))
            ((> j n))
          (declare (type (integer 0 4000004000001) j))
          (setf (aref flags j) 0))))))

(defun bench ()
  (let ((found 0))
    (declare (type fixnum found))
    (dotimes (i 3 found) (setq found (sieve 2000000)))))

;; Every benchmark ends with this identical footer: run BENCH once, print the
;; answer and the milliseconds it took, both on one line the harness parses.
;; internal-time-units-per-second differs per implementation (1000 here, a
;; million on SBCL), so the elapsed count is normalised rather than reported raw.
(let* ((start (get-internal-real-time))
       (answer (bench))
       (elapsed (- (get-internal-real-time) start)))
  (format t "result=~a ms=~a~%" answer
          (round (* 1000 elapsed) internal-time-units-per-second)))
