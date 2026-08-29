;;; hash -- 1,600,000 hash-table writes followed by 1,600,000 reads.
;;;
;;; EQL keys, so the table does no hashing of its own beyond the integers.
;;; What it separates is the cost of GETHASH and of growing a table.
(declaim (optimize (speed 3) (safety 0) (debug 0)))

(defun bench ()
  ;; SUM passes 3.8e12 and a fixnum is 31 bits wide on ABCL, so it is declared
  ;; INTEGER: a FIXNUM here would be a false promise on the implementations
  ;; with the narrower one.
  (let ((h (make-hash-table :test 'eql)) (n 1600000) (sum 0))
    (declare (type hash-table h) (type fixnum n) (type integer sum))
    (dotimes (i n) (setf (gethash i h) (* i 3)))
    (dotimes (i n) (setq sum (+ sum (gethash i h))))
    (+ sum (hash-table-count h))))

;; Every benchmark ends with this identical footer: run BENCH once, print the
;; answer and the milliseconds it took, both on one line the harness parses.
;; internal-time-units-per-second differs per implementation (1000 here, a
;; million on SBCL), so the elapsed count is normalised rather than reported raw.
(let* ((start (get-internal-real-time))
       (answer (bench))
       (elapsed (- (get-internal-real-time) start)))
  (format t "result=~a ms=~a~%" answer
          (round (* 1000 elapsed) internal-time-units-per-second)))
