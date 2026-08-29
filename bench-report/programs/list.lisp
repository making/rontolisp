;;; list -- cons, MAPCAR and REVERSE.
;;;
;;; 240 rounds over a 20,000-element list: build it a cons at a time, map a
;;; closure down it, reverse the result. Allocation-heavy on purpose -- this is
;;; the benchmark the garbage collector shows up in.
(declaim (optimize (speed 3) (safety 0) (debug 0)))

(defun build (n)
  (declare (type fixnum n))
  (let ((acc nil))
    (declare (type list acc))
    (dotimes (i n acc) (setq acc (cons i acc)))))

(defun bench ()
  (let ((total 0))
    (declare (type fixnum total))
    (dotimes (i 240 total)
      (let* ((l (build 20000))
             (doubled (mapcar (lambda (x) (* x 2)) l))
             (back (reverse doubled)))
        (declare (type list l doubled back))
        (setq total (+ total (length back) (car back)))))))

;; Every benchmark ends with this identical footer: run BENCH once, print the
;; answer and the milliseconds it took, both on one line the harness parses.
;; internal-time-units-per-second differs per implementation (1000 here, a
;; million on SBCL), so the elapsed count is normalised rather than reported raw.
(let* ((start (get-internal-real-time))
       (answer (bench))
       (elapsed (- (get-internal-real-time) start)))
  (format t "result=~a ms=~a~%" answer
          (round (* 1000 elapsed) internal-time-units-per-second)))
