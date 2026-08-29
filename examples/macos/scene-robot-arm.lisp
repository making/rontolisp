;;;; scene-robot-arm.lisp -- a four-joint arm that reaches for a moving target,
;;;; modelled in `geom` and drawn by `scene`.
;;;;
;;;; The same machine as metal-robot-arm.lisp, written the other way round, and
;;;; the pair is the point. THERE the program owns the geometry: it tessellates
;;;; tapered tubes into a vertex array every frame, uploads them into shared
;;;; MTLBuffers rotated through three slots, and computes world-space normals
;;;; itself -- ~900 lines, of which the arm is a small part. HERE the machine is
;;;; six `geom` solids hung off a kinematic chain, and the whole per-frame cost
;;;; is four joint angles: a rigid solid's triangles never change, so each mesh
;;;; went to the GPU once and a frame hands the GPU one 4x4 matrix per solid
;;;; (.kb/geom.md -- 380 ms a frame against 9.0 on a 60-solid model).
;;;;
;;;; What did NOT move into the library is the SOLVER. Inverse kinematics is a
;;;; program's business, not a modeller's, so the damped-least-squares Jacobian
;;;; iteration below is ordinary Lisp over `linalg` -- and it reads as the matrix
;;;; expression it is, because a geom transform answers world positions and axes
;;;; directly.
;;;;
;;;; Drag to orbit, shift-drag to pan, scroll to dolly, resize the window. The
;;;; body triads (scene:axes :both) are each joint's OWN frame, which is what
;;;; makes a chain readable.
;;;;
;;;; Run it (macOS, on any of the three backends that carry the binding):
;;;;
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/macos/scene-robot-arm.lisp
;;;;   rontolisp examples/macos/scene-robot-arm.lisp
;;;;   rontolisp examples/macos/scene-robot-arm.lisp -o Arm.class --class-name Arm && java Arm

;;; --- the machine ---------------------------------------------------------------
;;;
;;; A joint is a geom:node: something that HAS a local transform. A link is a
;;; geom:solid ATTACHED to that node, so posing the joint poses everything below
;;; it and no code walks the chain by hand.

(defvar *l1* 60.0)  ; the column above the base plate
(defvar *l2* 190.0) ; upper arm
(defvar *l3* 165.0) ; forearm
(defvar *l4* 110.0) ; wrist

(defvar *base* (geom:make-node))

(defvar *j0* (geom:make-node :parent *base*)) ; yaw
(defvar *j1* (geom:make-node :parent *j0* :translation (geom:vec3 0 0 *l1*)))
(defvar *j2* (geom:make-node :parent *j1* :translation (geom:vec3 0 0 *l2*)))
(defvar *j3* (geom:make-node :parent *j2* :translation (geom:vec3 0 0 *l3*)))
(defvar *tip* (geom:make-node :parent *j3* :translation (geom:vec3 0 0 *l4*)))

;; The joints, and the axis each one turns about: the first about z (the column
;; swivels), the other three about y (the arm folds in its own plane).
(defvar *joints* (list *j0* *j1* *j2* *j3*))
(defvar *axes* (list :z :y :y :y))
(defvar *angles* (list 0.6 0.5 -0.9 -0.4))

(defvar *steel* (geom:vec3 0.62 0.68 0.78))

(defvar *links*
  (list (geom:attach *base*
                     (geom:cylinder :radius 96
                                    :height 18
                                    :sides 48
                                    :color (geom:vec3 0.30 0.34 0.42)
                                    :label "plate"))
        (geom:attach *j0*
                     (geom:cylinder :radius 52
                                    :height *l1*
                                    :sides 36
                                    :color *steel*
                                    :label "column"))
        (geom:attach *j1*
                     (geom:cylinder :radius 34
                                    :height *l2*
                                    :sides 32
                                    :color *steel*
                                    :label "upper arm"))
        (geom:attach *j2*
                     (geom:cylinder :radius 28
                                    :height *l3*
                                    :sides 32
                                    :color *steel*
                                    :label "forearm"))
        (geom:attach *j3*
                     (geom:cylinder :radius 22
                                    :height *l4*
                                    :sides 32
                                    :color *steel*
                                    :label "wrist"))
        (geom:attach *tip*
                     (geom:sphere :radius 26
                                  :sides 32
                                  :stacks 20
                                  :color (geom:vec3 0.98 0.62 0.35)
                                  :label "hand"))))

;; The target the hand chases, drawn as a small sphere of its own.
(defvar *goal* (geom:vec3 320 120 260))

(defvar *marker*
  (geom:sphere :radius 14
               :sides 20
               :stacks 14
               :color (geom:vec3 0.40 0.95 0.70)
               :label "goal"))

;;; --- the solver ----------------------------------------------------------------
;;;
;;; Damped least squares: dq = J^T (J J^T + lambda^2 I)^-1 e. The position
;;; Jacobian's i-th column is a_i x (p - o_i) -- the world axis of joint i
;;; crossed with the vector from that joint to the hand -- and geom answers both
;;; halves outright, so the loop below is the formula and nothing else.

(defun pose ()
  (let ((qs *angles*) (as *axes*))
    (dolist (j *joints*)
      (geom:place j :axis (car as) :angle (car qs))
      (setq qs (cdr qs))
      (setq as (cdr as)))))

(defun jacobian (hand)
  (let ((cols '()) (as *axes*))
    (dolist (j *joints*)
      (let ((axis
             (linalg:matmul (geom:world-rotation j)
                            (geom:axis-vector (car as))))
            (origin (geom:world-translation j)))
        (push (linalg:cross axis (linalg:sub hand origin)) cols)
        (setq as (cdr as))))
    ;; one column per joint, so the 3xN matrix is the transpose of the stack
    (linalg:transpose (linalg:stack (nreverse cols)))))

(defun solve-step (lambda-damping)
  (pose)
  (let* ((hand (geom:world-translation *tip*))
         (err (linalg:sub *goal* hand))
         (j (jacobian hand))
         (jt (linalg:transpose j))
         (a
          (linalg:add (linalg:matmul j jt)
                      (linalg:mul (linalg:eye 3 :element-type 'single-float)
                                  (* lambda-damping lambda-damping))))
         (dq (linalg:matmul jt (linalg:solve a err)))
         (out '())
         (i 0))
    (dolist (q *angles*)
      ;; a per-step cap keeps the chain from snapping across the workspace
      (let ((d (aref dq i)))
        (push (+ q (cond ((< d -0.12) -0.12) ((> d 0.12) 0.12) (t d))) out))
      (setq i (+ i 1)))
    (setq *angles* (nreverse out))
    (pose)
    (linalg:norm err)))

;;; --- the window ----------------------------------------------------------------

(defvar *view* (scene:viewer :title "geom robot arm" :width 1000 :height 700))

(apply #'scene:add (cons *view* (cons *marker* *links*)))

(scene:grid *view* :extent 700 :spacing 50)
(scene:axes *view* :both)
(scene:camera *view*
              :azimuth 0.85
              :elevation 0.42
              :distance 1250
              :target (geom:vec3 0 0 220))

(defvar *t* 0.0)

;; One IK iteration per frame, which is what makes the arm SETTLE onto a moving
;; target rather than teleport: the target itself moves on a slow Lissajous
;; figure, so the hand is always chasing.
(scene:animate *view*
               (lambda ()
                 (setq *t* (+ *t* 0.016))
                 (setq *goal*
                       (geom:vec3 (* 330 (cos (* 0.55 *t*)))
                                  (* 330 (sin (* 0.37 *t*)))
                                  (+ 260 (* 130 (sin (* 0.83 *t*))))))
                 (geom:place *marker* :translation *goal*)
                 (solve-step 24.0)))

(scene:wait *view*)
