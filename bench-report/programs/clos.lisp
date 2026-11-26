;;; clos -- generic function dispatch.
;;;
;;; Five million calls to a two-method generic function over two classes, so the
;;; number is the dispatch itself: the method bodies are a multiply each.
(declaim (optimize (speed 3) (safety 0) (debug 0)))

(defclass shape () ())

(defclass circle (shape) ((radius :initarg :radius :reader radius)))

(defclass square (shape) ((side :initarg :side :reader side)))

(defgeneric area (shape))

(defmethod area ((s circle)) (* 3 (radius s) (radius s)))

(defmethod area ((s square)) (* (side s) (side s)))

(defun bench ()
  (let ((shapes
         (list (make-instance 'circle :radius 2)
               (make-instance 'square :side 3)))
        (total 0))
    (declare (type list shapes) (type fixnum total))
    (dotimes (i 2500000 total)
      (dolist (s shapes) (setq total (+ total (area s)))))))

;; Every benchmark ends with this identical footer: run BENCH once, print the
;; answer and the milliseconds it took, both on one line the harness parses.
;; internal-time-units-per-second differs per implementation (1000 here, a
;; million on SBCL), so the elapsed count is normalised rather than reported raw.
(let* ((start (get-internal-real-time))
       (answer (bench))
       (elapsed (- (get-internal-real-time) start)))
  (format t "result=~a ms=~a~%" answer
          (round (* 1000 elapsed) internal-time-units-per-second)))
