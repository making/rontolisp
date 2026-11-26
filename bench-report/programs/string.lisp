;;; string -- build a string, then scan it.
;;;
;;; 30,000 rounds of appending 20 fragments through a string output stream and
;;; searching the 880-character result. Character-at-a-time work on the write
;;; side and a library sequence search on the read side.
(declaim (optimize (speed 3) (safety 0) (debug 0)))

(defun build-line (base times)
  (declare (type simple-string base) (type fixnum times))
  (with-output-to-string (out) (dotimes (i times) (write-string base out))))

(defun bench ()
  ;; S is whatever WITH-OUTPUT-TO-STRING returns, which is not simple on every
  ;; implementation, so it is declared STRING rather than SIMPLE-STRING.
  (let ((base "the quick brown fox jumps over the lazy dog ") (total 0))
    (declare (type simple-string base) (type fixnum total))
    (dotimes (i 30000 total)
      (let ((s (build-line base 20)))
        (declare (type string s))
        (setq total (+ total (length s)))
        (when (search "lazy dog" s) (setq total (+ total 1)))))))

;; Every benchmark ends with this identical footer: run BENCH once, print the
;; answer and the milliseconds it took, both on one line the harness parses.
;; internal-time-units-per-second differs per implementation (1000 here, a
;; million on SBCL), so the elapsed count is normalised rather than reported raw.
(let* ((start (get-internal-real-time))
       (answer (bench))
       (elapsed (- (get-internal-real-time) start)))
  (format t "result=~a ms=~a~%" answer
          (round (* 1000 elapsed) internal-time-units-per-second)))
