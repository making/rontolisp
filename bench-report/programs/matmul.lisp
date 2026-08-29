;;; matmul -- 200x200 double-float matrix multiply.
;;;
;;; Eight million multiply-accumulates through two-dimensional AREF. The float
;;; arithmetic is mandelbrot's; what this one adds is the index arithmetic and
;;; the bounds check behind every element access.
(declaim (optimize (speed 3) (safety 0) (debug 0)))

(defun make-matrix (n seed)
  (declare (type fixnum n seed))
  (let ((m (make-array (list n n) :element-type 'double-float)) (s seed))
    (declare (type (simple-array double-float (* *)) m) (type fixnum s))
    (dotimes (i n m)
      (dotimes (j n)
        (setq s (mod (+ (* s 1103515245) 12345) 2147483648))
        (setf (aref m i j) (/ (float s 1.0d0) 2147483648.0d0))))))

(defun matmul (a b n)
  (declare (type (simple-array double-float (* *)) a b) (type fixnum n))
  (let ((c
         (make-array (list n n)
                     :element-type 'double-float
                     :initial-element 0.0d0)))
    (declare (type (simple-array double-float (* *)) c))
    (dotimes (i n c)
      (dotimes (k n)
        (let ((aik (aref a i k)))
          (declare (type double-float aik))
          (dotimes (j n)
            (setf (aref c i j) (+ (aref c i j) (* aik (aref b k j))))))))))

(defun bench ()
  (let* ((n 200)
         (c (matmul (make-matrix n 1) (make-matrix n 7) n))
         (diagonal 0.0d0))
    (declare (type fixnum n) (type (simple-array double-float (* *)) c)
             (type double-float diagonal))
    (dotimes (i n (round diagonal)) (setq diagonal (+ diagonal (aref c i i))))))

;; Every benchmark ends with this identical footer: run BENCH once, print the
;; answer and the milliseconds it took, both on one line the harness parses.
;; internal-time-units-per-second differs per implementation (1000 here, a
;; million on SBCL), so the elapsed count is normalised rather than reported raw.
(let* ((start (get-internal-real-time))
       (answer (bench))
       (elapsed (- (get-internal-real-time) start)))
  (format t "result=~a ms=~a~%" answer
          (round (* 1000 elapsed) internal-time-units-per-second)))
