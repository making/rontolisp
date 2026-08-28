;;; hash -- 1,600,000 hash-table writes followed by 1,600,000 reads.
;;;
;;; EQL keys, so the table does no hashing of its own beyond the integers.
;;; What it separates is the cost of GETHASH and of growing a table.
(defun bench ()
  (let ((h (make-hash-table :test 'eql)) (n 1600000) (sum 0))
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
