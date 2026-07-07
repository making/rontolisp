;;;; ik-jacobian.lisp -- the Jacobian method with damped least squares
;;;; (Wampler 1986), the numerical IK used across robotics.
;;;;
;;;; Every joint is a ball joint contributing three columns a_k x (p_tip -
;;;; p_i) to the 3 x 3(n+1) position Jacobian J, and each iteration solves
;;;; the damped normal equations dtheta = J^T (J J^T + lambda^2 I)^-1 e --
;;;; J J^T built with linalg:matmul / linalg:transpose, the 3x3 system
;;;; solved exactly by linalg:solve (Gaussian elimination) -- then applies
;;;; the rotations linearized and re-normalizes the link lengths.
;;;;
;;;; Spliced into robot-arm.lisp at compile time by its literal top-level
;;;; (load "ik-jacobian.lisp"); shares the joint arrays, the commanded
;;;; target and the `place` helper defined there.

(defconstant +dls-lambda2+ 0.05)        ; damping^2: keeps J J^T well-conditioned
(defconstant +dls-max-err+ 0.35)        ; clamp per-iteration error (stabilizes)

(defun solve-ik-jacobian ()
  ;; Damped-least-squares Jacobian IK. Each iteration: build the position
  ;; Jacobian about the current pose, solve the damped normal equations for
  ;; the joint rotations that move the grasp point toward the target, apply
  ;; them (linearized), and re-normalize the link lengths.
  (let* ((tip (+ *links* 1))
         (m (* 3 tip)))                 ; 3 rotation DOFs per joint 0..*links*
    (dotimes (iter 10)
      (let ((ex (- *tx* (aref *jx* tip)))
            (ey (- *ty* (aref *jy* tip)))
            (ez (- *tz* (aref *jz* tip))))
        ;; clamp the error so early iterations stay in the linear regime
        (let ((d (sqrt (+ (* ex ex) (* ey ey) (* ez ez)))))
          (when (> d +dls-max-err+)
            (let ((r (/ +dls-max-err+ d)))
              (setq ex (* ex r))
              (setq ey (* ey r))
              (setq ez (* ez r)))))
        ;; J: column 3i+k is e_k x (p_tip - p_i), the tip velocity from
        ;; spinning joint i about the world axis e_k
        (let ((jac (make-array (list 3 m) :initial-element 0.0))
              (e (make-array 3 :initial-element 0.0)))
          (dotimes (i tip)
            (let ((px (- (aref *jx* tip) (aref *jx* i)))
                  (py (- (aref *jy* tip) (aref *jy* i)))
                  (pz (- (aref *jz* tip) (aref *jz* i)))
                  (c (* 3 i)))
              (setf (aref jac 1 c) (- 0.0 pz))          ; e_x x p
              (setf (aref jac 2 c) py)
              (setf (aref jac 0 (+ c 1)) pz)            ; e_y x p
              (setf (aref jac 2 (+ c 1)) (- 0.0 px))
              (setf (aref jac 0 (+ c 2)) (- 0.0 py))    ; e_z x p
              (setf (aref jac 1 (+ c 2)) px)))
          (setf (aref e 0) ex)
          (setf (aref e 1) ey)
          (setf (aref e 2) ez)
          ;; dtheta = J^T (J J^T + lambda^2 I)^-1 e  -- the 3x3 damped
          ;; normal equations, solved exactly by linalg
          (let ((a (linalg:matmul jac (linalg:transpose jac))))
            (dotimes (k 3)
              (setf (aref a k k) (+ (aref a k k) +dls-lambda2+)))
            (let ((dth (linalg:matmul (linalg:transpose jac) (linalg:solve a e))))
              ;; apply: joint i's rotation w moves every downstream joint
              ;; j by w x (p_j - p_i), the linearized rotation
              (dotimes (i tip)
                (let ((wx (aref dth (* 3 i)))
                      (wy (aref dth (+ (* 3 i) 1)))
                      (wz (aref dth (+ (* 3 i) 2))))
                  (do ((jj (+ i 1) (+ jj 1)))
                      ((> jj tip))
                    (let ((qx (- (aref *jx* jj) (aref *jx* i)))
                          (qy (- (aref *jy* jj) (aref *jy* i)))
                          (qz (- (aref *jz* jj) (aref *jz* i))))
                      (setf (aref *jx* jj) (+ (aref *jx* jj) (- (* wy qz) (* wz qy))))
                      (setf (aref *jy* jj) (+ (aref *jy* jj) (- (* wz qx) (* wx qz))))
                      (setf (aref *jz* jj) (+ (aref *jz* jj) (- (* wx qy) (* wy qx))))))))))
          ;; linearized rotations stretch the links; re-normalize them
          (dotimes (i tip)
            (place (+ i 1) i (aref *len* i))))))))
