;;;; ik-analytic.lisp -- the analytic (closed-form) method + forward
;;;; kinematics through homogeneous transform matrices.
;;;;
;;;; The textbook 2R solution: base yaw from atan2, the elbow angle from
;;;; the law of cosines, the shoulder from atan2 -- no iteration, one
;;;; exact solution per frame. Closed forms only exist for specific
;;;; structures, so init splits the chain into two rigid groups at the
;;;; joint that best balances their lengths (*split*, *ga*, *gb*) and the
;;;; extra joints ride frozen inside them. The joint positions then come
;;;; from FORWARD kinematics: a chain of 4x4 homogeneous transforms
;;;; yaw(q0) . rotz(q1) . transx(s) [. rotz(q2) . transx(s)] combined
;;;; with linalg:matmul, each joint read off the translation column.
;;;;
;;;; Spliced into robot-arm.lisp at compile time by its literal top-level
;;;; (load "ik-analytic.lisp"); shares the joint matrix, the commanded
;;;; target and the elbow split computed there.

(defun mat-yaw (q)
  ;; local +x -> the horizontal direction (sin q, 0, cos q); +y stays up
  (let ((m (linalg:eye 4)) (c (cos q)) (s (sin q)))
    (setf (aref m 0 0) s)
    (setf (aref m 2 0) c)
    (setf (aref m 0 2) (- 0.0 c))
    (setf (aref m 2 2) s)
    m))

(defun mat-rot-z (q)
  ;; rotates local +x toward +y: the shoulder / elbow pitch in the arm plane
  (let ((m (linalg:eye 4)) (c (cos q)) (s (sin q)))
    (setf (aref m 0 0) c)
    (setf (aref m 0 1) (- 0.0 s))
    (setf (aref m 1 0) s)
    (setf (aref m 1 1) c)
    m))

(defun mat-trans-x (d)
  (let ((m (linalg:eye 4)))
    (setf (aref m 0 3) d)
    m))

(defun %fk-place (m i)
  ;; joint i = the translation column of the accumulated transform
  (set-joint i (vec3 (aref m 0 3) (aref m 1 3) (aref m 2 3))))

(defun solve-ik-analytic ()
  ;; Closed-form angles (elbow-up branch). The commanded point is clamped
  ;; into the two-segment annulus, so a target the frozen-joint structure
  ;; cannot fold to is reached as closely as the geometry allows (the
  ;; HUD's "to target" shows the residual).
  (let* ((tx (aref *target* 0))
         (ty (aref *target* 1))
         (tz (aref *target* 2))
         (q0 (atan2 tx tz))
         (r (sqrt (+ (* tx tx) (* tz tz))))
         (d (sqrt (+ (* r r) (* ty ty))))
         (la *ga*)
         (lb *gb*)
         (dmin (+ (if (> la lb) (- la lb) (- lb la)) 0.001))
         (dmax (- (+ la lb) 0.001))
         (dc (cond ((< d dmin) dmin) ((> d dmax) dmax) (t d)))
         (ce (/ (- (* dc dc) (* la la) (* lb lb)) (* 2.0 la lb)))
         (q2 (- 0.0 (acos (cond ((> ce 1.0) 1.0) ((< ce -1.0) -1.0) (t ce)))))
         (q1 (- (atan2 ty r) (atan2 (* lb (sin q2)) (+ la (* lb (cos q2)))))))
    ;; FORWARD kinematics: walk the transform chain and place every joint.
    (let ((t1 (linalg:matmul (mat-yaw q0) (mat-rot-z q1))) (s 0.0))
      (set-joint 0 (vec3 0.0 0.0 0.0))
      (do ((i 1 (+ i 1)))
          ((> i *split*))
        (setq s (+ s (aref *len* (- i 1))))
        (%fk-place (linalg:matmul t1 (mat-trans-x s)) i))
      (let ((t2
             (linalg:matmul t1
                            (linalg:matmul (mat-trans-x la) (mat-rot-z q2)))))
        (setq s 0.0)
        (do ((i (+ *split* 1) (+ i 1)))
            ((> i *tip*))
          (setq s (+ s (aref *len* (- i 1))))
          (%fk-place (linalg:matmul t2 (mat-trans-x s)) i))))))
