;;;; ik-jacobian.lisp -- the Jacobian method with damped least squares
;;;; (Wampler 1986), the numerical IK used across robotics.
;;;;
;;;; Every joint is a ball joint contributing three columns a_k x (p_tip -
;;;; p_i) to the 3 x 3(n+1) position Jacobian J -- which is the SKEW matrix
;;;; of p_tip - p_i, three columns at a time -- and each iteration solves
;;;; the damped normal equations dtheta = J^T (J J^T + lambda^2 I)^-1 e --
;;;; J J^T built with linalg:matmul / linalg:transpose, the 3x3 system
;;;; solved exactly by linalg:solve (Gaussian elimination) -- then applies
;;;; the rotations linearized and re-normalizes the link lengths.
;;;;
;;;; Spliced into robot-arm.lisp at compile time by its literal top-level
;;;; (load "ik-jacobian.lisp"); shares the joint matrix, the commanded
;;;; target and the `place` helper defined there.

(defconstant +dls-lambda2+ 0.05) ; damping^2: keeps J J^T well-conditioned
(defconstant +dls-max-err+ 0.35) ; clamp per-iteration error (stabilizes)

;; The three columns joint I contributes to J: the skew matrix of P, since
;; e_k x p is column k of it.
(defun skew-into (jac column p)
  (setf (aref jac 1 column) (- 0.0 (aref p 2)))
  (setf (aref jac 2 column) (aref p 1))
  (setf (aref jac 0 (+ column 1)) (aref p 2))
  (setf (aref jac 2 (+ column 1)) (- 0.0 (aref p 0)))
  (setf (aref jac 0 (+ column 2)) (- 0.0 (aref p 1)))
  (setf (aref jac 1 (+ column 2)) (aref p 0)))

(defun solve-ik-jacobian ()
  ;; Damped-least-squares Jacobian IK. Each iteration: build the position
  ;; Jacobian about the current pose, solve the damped normal equations for
  ;; the joint rotations that move the grasp point toward the target, apply
  ;; them (linearized), and re-normalize the link lengths.
  (let ((m (* 3 *tip*))) ; 3 rotation DOFs a joint, joints 0..*links*
    (dotimes (iter 10)
      (let* ((raw (linalg:sub *target* (joint *tip*)))
             (d (linalg:norm raw))
             ;; clamp the error so early iterations stay in the linear regime
             (e
              (if (> d +dls-max-err+) (linalg:mul raw (/ +dls-max-err+ d)) raw))
             (jac (linalg:zeros (list 3 m)))
             (tip (joint *tip*)))
        (dotimes (i *tip*) (skew-into jac (* 3 i) (linalg:sub tip (joint i))))
        ;; dtheta = J^T (J J^T + lambda^2 I)^-1 e -- the 3x3 damped normal
        ;; equations, solved exactly by linalg
        (let ((a (linalg:matmul jac (linalg:transpose jac))))
          (dotimes (k 3) (setf (aref a k k) (+ (aref a k k) +dls-lambda2+)))
          (let ((dth (linalg:matmul (linalg:transpose jac) (linalg:solve a e))))
            ;; apply: joint i's rotation w moves every downstream joint j by
            ;; w x (p_j - p_i), the linearized rotation
            (dotimes (i *tip*)
              (let ((w
                     (vec3 (aref dth (* 3 i)) (aref dth (+ (* 3 i) 1))
                           (aref dth (+ (* 3 i) 2))))
                    (base (joint i)))
                (do ((jj (+ i 1) (+ jj 1)))
                    ((> jj *tip*))
                  (set-joint jj
                             (linalg:add (joint jj)
                              (cross w (linalg:sub (joint jj) base)))))))))
        ;; linearized rotations stretch the links; re-normalize them
        (dotimes (i *tip*) (place (+ i 1) i (aref *len* i)))))))
