;;;; ik-fabrik.lisp -- FABRIK (Forward And Backward Reaching Inverse
;;;; Kinematics, Aristidou & Lasenby 2011), the geometric method.
;;;;
;;;; No matrices at all: alternately pin the grasp point to the commanded
;;;; target and walk the chain back to the base re-normalizing each link's
;;;; length, then pin the base and walk forward. The target is always
;;;; inside the workspace (set-goal clamps it), so a few sweeps converge
;;;; far below a pixel.
;;;;
;;;; Spliced into robot-arm.lisp at compile time by its literal top-level
;;;; (load "ik-fabrik.lisp"); shares the joint arrays, the commanded
;;;; target and the `place` helper defined there.

(defun solve-ik-fabrik ()
  (let ((tip (+ *links* 1)))
    (dotimes (pass 8)
      ;; backward: grasp point -> base
      (setf (aref *jx* tip) *tx*)
      (setf (aref *jy* tip) *ty*)
      (setf (aref *jz* tip) *tz*)
      (do ((i (- tip 1) (- i 1)))
          ((< i 0))
        (place i (+ i 1) (aref *len* i)))
      ;; forward: base -> grasp point
      (setf (aref *jx* 0) 0.0)
      (setf (aref *jy* 0) 0.0)
      (setf (aref *jz* 0) 0.0)
      (dotimes (i tip) (place (+ i 1) i (aref *len* i))))))
