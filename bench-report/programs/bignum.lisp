;;; bignum -- arbitrary-precision integer arithmetic.
;;;
;;; 48 runs of 3000!, which grows past 32,000 bits, so almost every multiply is
;;; a bignum multiply. INTEGER-LENGTH of the product is the checksum.
(defun fact (n)
  (let ((acc 1))
    (do ((i 2 (+ i 1)))
        ((> i n) acc)
      (setq acc (* acc i)))))

(defun bench ()
  (let ((total 0))
    (dotimes (i 48 total) (setq total (+ total (integer-length (fact 3000)))))))

;; Every benchmark ends with this identical footer: run BENCH once, print the
;; answer and the milliseconds it took, both on one line the harness parses.
;; internal-time-units-per-second differs per implementation (1000 here, a
;; million on SBCL), so the elapsed count is normalised rather than reported raw.
(let* ((start (get-internal-real-time))
       (answer (bench))
       (elapsed (- (get-internal-real-time) start)))
  (format t "result=~a ms=~a~%" answer
          (round (* 1000 elapsed) internal-time-units-per-second)))
