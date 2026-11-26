;;;; scene-robot-reach.lisp -- metal-robot-arm.lisp, modelled in `geom` and drawn
;;;; by `scene`: click in the window and the arm reaches for that point in 3-D,
;;;; the three-finger gripper opening for the flight and closing on arrival.
;;;;
;;;; The pair is the point. THERE the program owns the renderer: it tessellates
;;;; tapered tubes, discs and spheres into a vertex array EVERY FRAME, uploads
;;;; them into shared MTLBuffers rotated through three slots, computes
;;;; world-space normals itself, writes its own shaders and unprojects the click
;;;; by hand -- ~970 lines, of which the machine is a small part. HERE the
;;;; machine is the program and nothing else: fifty-odd `geom` solids hung off a
;;;; kinematic chain, and a frame changes nothing but poses. A rigid solid's
;;;; triangles never change, so each mesh went to the GPU once and a frame hands
;;;; the GPU one 4x4 matrix per solid (.kb/geom.md -- 380 ms a frame against 9.0
;;;; on a 60-solid model).
;;;;
;;;; scene-robot-arm.lisp is the same chain with the interaction left out -- a
;;;; target on a Lissajous figure and no hand. This one is the whole of the Metal
;;;; program, so the three things that program does BESIDES drawing are all here,
;;;; and all of them are ordinary Lisp over `linalg`:
;;;;
;;;;   - the minimum-jerk trajectory 10u^3 - 15u^4 + 6u^5 (Flash & Hogan 1985),
;;;;   - the damped-least-squares Jacobian IK, dtheta = J^T (J J^T + l^2 I)^-1 e,
;;;;   - the gripper, which is a sub-chain of nodes rather than a mesh.
;;;;
;;;; What DID move into the library is the unprojection. A pixel names a line
;;;; through the world, and the viewer is the only thing that knows the camera,
;;;; so `scene:on-click` hands a program the world point where that line meets
;;;; the plane through the orbit target -- "click where you see", from any
;;;; viewpoint, in one line. (`scene:ray` is the line itself, for a program that
;;;; wants a different plane.)
;;;;
;;;; The IK is also the better of the two, and the scene graph is why. There the
;;;; joints are POSITIONS, the linearized rotations stretch the links and a
;;;; re-normalization pass puts the lengths back every iteration. Here a joint is
;;;; a node whose translation is fixed in its parent's frame and whose ROTATION
;;;; is the unknown, so a link cannot stretch in the first place and there is
;;;; nothing to repair.
;;;;
;;;; Click to reach, drag to orbit, shift-drag to pan, scroll to dolly, resize
;;;; the window.
;;;;
;;;; Run it (macOS, on any of the three backends that carry the binding):
;;;;
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/macos/scene-robot-reach.lisp
;;;;   rontolisp examples/macos/scene-robot-reach.lisp
;;;;   rontolisp examples/macos/scene-robot-reach.lisp -o Reach.class --class-name Reach && java Reach

;;; --- proportions ---------------------------------------------------------------
;;;
;;; Every measurement below is metal-robot-arm.lisp's, scaled by one number: that
;;; program models a 1.25-long arm and this one works in the hundreds `scene`'s
;;; default camera is framed for, so +u+ is its unit of length and every literal
;;; that follows is its literal. The two machines are the same machine.

(defconstant +u+ 416.0)
(defconstant +links+ 4)               ; arm links, shoulder to wrist
(defconstant +reach+ (* 1.25 +u+))    ; their total length, fixed
(defconstant +tool+ (* 0.115 +u+))    ; wrist -> the grasp point between the fingertips
(defconstant +pedestal+ (* 0.21 +u+)) ; ground -> shoulder
(defconstant +trail+ 24)              ; spheres in the hand's wake
(defconstant +tau+ 6.283185307179586)

(defvar *steel* (geom:vec3 0.72 0.76 0.84))
(defvar *dark* (geom:vec3 0.30 0.34 0.44))
(defvar *ember* (geom:vec3 1.00 0.52 0.16))

;; The arm tapers from shoulder to wrist.
(defun link-radius (i) (* 0.052 +u+ (- 1.0 (* 0.55 (/ (* 1.0 i) +links+)))))

;; Each link is 0.82x the previous, normalized so the four always sum to +reach+.
(defun link-lengths ()
  (let ((raw '()) (sum 0.0) (l 1.0))
    (dotimes (i +links+)
      (push l raw)
      (setq sum (+ sum l))
      (setq l (* l 0.82)))
    (mapcar (lambda (x) (* x (/ +reach+ sum))) (nreverse raw))))

(defvar *lengths* (link-lengths))

;; A frustum from a RADIUS-wide base on z = 0 to a TOP-wide ring at z = H: a
;; two-point profile revolved about z, which is the whole of a tapered link.
(defun frustum (radius top h color)
  (geom:revolution (list (list top 0.0 h) (list radius 0.0 0.0))
                   :sides 28
                   :color color))

(defun unit (v) (linalg:mul v (/ 1.0 (max 1e-9 (linalg:norm v)))))

;;; --- the machine ---------------------------------------------------------------
;;;
;;; Five ball joints -- four arm joints and the wrist -- and the grasp point a
;;; rigid +tool+ beyond the last of them. The solver moves the wrist AND the TCP,
;;; so the fingers close exactly on the clicked goal and the approach direction
;;; comes out of the solver for free.
;;;
;;; A joint's TRANSLATION is fixed in its parent's frame; only its rotation is an
;;; unknown. That one fact is what makes the chain rigid by construction.

(defvar *ground* (geom:make-node))

(defvar *base*
  (geom:make-node :parent *ground* :translation (geom:vec3 0 0 +pedestal+)))

(defvar *joints*
  (let ((out (list (geom:make-node :parent *base*))) (parent nil))
    (dolist (l *lengths* (nreverse out))
      (setq parent (car out))
      (push (geom:make-node :parent parent :translation (geom:vec3 0 0 l))
            out))))

(defvar *wrist* (car (last *joints*)))

(defvar *tcp*
  (geom:make-node :parent *wrist* :translation (geom:vec3 0 0 +tool+)))

;;; The gripper: three two-phalanx fingers 120 degrees apart around the tool
;;; axis, as a sub-chain rather than as a mesh. Each finger is a frame on the
;;; palm rim (local +x radial, local +z the tool axis) carrying two phalanx
;;; frames, so closing the hand is six rotations -- and the fingertips meet on
;;; the axis at +tool+, which is the very point the solver pins to the goal.

(defvar *palm*
  (geom:make-node :parent *wrist* :translation (geom:vec3 0 0 (* 0.035 +u+))))

(defvar *fingers*
  (let ((out '()))
    (dotimes (f 3 (nreverse out))
      (let* ((a (/ (* +tau+ f) 3.0))
             (r (* 0.030 +u+))
             (base
              (geom:make-node :parent *palm*
               :translation (geom:vec3 (* r (cos a)) (* r (sin a)) 0)
               :axis :z
               :angle a))
             (p1 (geom:make-node :parent base))
             (p2
              (geom:make-node :parent p1
                              :translation (geom:vec3 0 0 (* 0.05 +u+))))
             (tip
              (geom:make-node :parent p2
                              :translation (geom:vec3 0 0 (* 0.05 +u+)))))
        (push (list p1 p2 tip) out)))))

;;; --- the solids ----------------------------------------------------------------
;;;
;;; Built once, attached to the frames above, and never touched again: no code
;;; below this point mentions a vertex.

(defvar *marker* (geom:make-node))

(defvar *ring* (geom:make-node :parent *marker*))

(defvar *trail-spheres*
  ;; Oldest first, so the wake tapers and cools: a sphere's radius and colour ARE
  ;; its age, fixed at construction, and a frame only moves it. That is the
  ;; sprite trail the Metal program fades in the shader, done the one way a
  ;; renderer that touches no triangle can do it.
  (let ((out '()))
    (dotimes (i +trail+ (nreverse out))
      (let ((f (/ (* 1.0 i) (- +trail+ 1))))
        (push (geom:sphere :radius (* +u+ (+ 0.005 (* 0.013 f)))
                           :sides 10
                           :stacks 6
                           :color (geom:vec3 (* 0.30 f) (+ 0.35 (* 0.60 f))
                                             (+ 0.45 (* 0.40 f)))) out)))))

(defun build-machine ()
  (let ((out '()) (i 0))
    ;; the pedestal, and the shoulder plate on top of it
    (push (geom:attach *ground*
                       (frustum (* 0.26 +u+) (* 0.20 +u+) +pedestal+ *dark*))
          out)
    ;; a tapered link per joint, a dark ball at each joint
    (dolist (l *lengths*)
      (push (geom:attach (nth i *joints*)
             (frustum (link-radius i) (link-radius (+ i 1)) l *steel*)) out)
      (push (geom:attach (nth i *joints*)
                         (geom:sphere :radius (* 1.45 (link-radius i))
                                      :sides 20
                                      :stacks 12
                                      :color *dark*)) out)
      (setq i (+ i 1)))
    ;; the wrist ball and the palm
    (push (geom:attach *wrist*
                       (geom:sphere :radius (* 1.35 (link-radius +links+))
                                    :sides 20
                                    :stacks 12
                                    :color *dark*)) out)
    (push (geom:attach *wrist*
           (frustum (* 0.030 +u+) (* 0.036 +u+) (* 0.035 +u+) *dark*)) out)
    ;; three fingers: two phalanges, a knuckle and a glowing fingertip
    (dolist (finger *fingers*)
      (push (geom:attach (first finger)
             (frustum (* 0.012 +u+) (* 0.009 +u+) (* 0.05 +u+) *steel*)) out)
      (push (geom:attach (second finger)
             (frustum (* 0.009 +u+) (* 0.006 +u+) (* 0.05 +u+) *steel*)) out)
      (push (geom:attach (second finger)
                         (geom:sphere :radius (* 0.011 +u+)
                                      :sides 14
                                      :stacks 8
                                      :color *dark*)) out)
      (push (geom:attach (third finger)
                         (geom:sphere :radius (* 0.008 +u+)
                                      :sides 14
                                      :stacks 8
                                      :color *ember*)) out))
    ;; the goal: a gyro ring around a hot core, spun so it reads from any angle
    ;; The ring is wider than the hand on purpose: the goal is where the hand
    ;; ENDS UP, so a marker the size of the Metal program's sprite ring would
    ;; spend every arrival hidden inside the gripper it marks.
    (push (geom:attach *ring*
                       (geom:torus :radius (* 0.105 +u+)
                                   :tube (* 0.008 +u+)
                                   :sides 36
                                   :rings 10
                                   :color (geom:vec3 0.40 0.95 0.70))) out)
    (push (geom:attach *marker*
                       (geom:sphere :radius (* 0.010 +u+)
                                    :sides 16
                                    :stacks 10
                                    :color (geom:vec3 0.95 1.00 0.85))) out)
    (nreverse out)))

(defvar *solids* (build-machine))

;;; --- the minimum-jerk trajectory -----------------------------------------------

(defvar *start* nil)
(defvar *goal* nil)
(defvar *target* nil) ; the COMMANDED hand position; the IK chases this, not *goal*
(defvar *t0* -10.0)
(defvar *dur* 1.0)

(defun now () (/ (get-internal-real-time) 1000.0))

(defun min-jerk (u)
  ;; s(u) = 10u^3 - 15u^4 + 6u^5: the unique 5th-order polynomial with zero
  ;; velocity and zero acceleration at both ends, i.e. the trajectory minimizing
  ;; the integral of squared jerk (Flash & Hogan 1985).
  (* u u u (+ 10.0 (* u (+ -15.0 (* u 6.0))))))

(defun set-goal (p tm)
  ;; Clamp the requested point into the spherical shell the arm can reach around
  ;; its shoulder (and above the pedestal), then restart the min-jerk clock. A
  ;; click mid-flight restarts the profile from the currently COMMANDED point, so
  ;; the hand never jumps.
  (let* ((shoulder (geom:world-translation (first *joints*)))
         (floor-z (+ (aref shoulder 2) (* 0.05 +u+)))
         (q (geom:vec3 (aref p 0) (aref p 1) (max floor-z (aref p 2))))
         (d0 (linalg:sub q shoulder))
         (d (max 1e-6 (linalg:norm d0)))
         (lo (* 0.18 +reach+))
         (hi (* 0.985 (+ +reach+ +tool+)))
         (k (cond ((< d lo) (/ lo d)) ((> d hi) (/ hi d)) (t 1.0))))
    (setq *start* *target*)
    (setq *goal* (linalg:add shoulder (linalg:mul d0 k)))
    (setq *t0* tm)
    ;; duration grows with distance: quick nearby hops, ~1.5 s across
    (setq *dur*
          (+ 0.45 (* 0.55 (/ (linalg:norm (linalg:sub *goal* *start*)) +u+))))))

(defun update-target (tm)
  ;; One lerp; the whole point of the profile is the shape of s.
  (let ((u (/ (- tm *t0*) *dur*)))
    (setq *target*
          (cond ((>= u 1.0) *goal*)
                ((<= u 0.0) *start*)
                (t (linalg:add *start*
                    (linalg:mul (linalg:sub *goal* *start*) (min-jerk u))))))))

;;; --- inverse kinematics: the Jacobian with damped least squares ----------------
;;;
;;; Wampler 1986. Every joint is a ball joint contributing three columns
;;; a_k x (p_tcp - p_i) to the 3 x 3n position Jacobian -- which is the SKEW
;;; matrix of p_tcp - p_i, three columns at a time -- and each iteration solves
;;; the damped normal equations dtheta = J^T (J J^T + lambda^2 I)^-1 e, the 3x3
;;; system solved exactly by linalg:solve.
;;;
;;; The chain answers both halves of a column outright -- geom:world-translation
;;; is the joint's origin and the tip's -- so the loop below is the formula and
;;; nothing else.

(defconstant +iterations+ 8)

;; Both of these are metal-robot-arm.lisp's, carried across in the units they
;; are measured in -- and they are not the same units. A Jacobian entry is a
;; LENGTH, so J J^T is a length squared and the damping added to its diagonal
;; must be too: scaling the model by +u+ and leaving lambda^2 at 0.05 would
;; leave the solver effectively undamped -- and a straight-up arm is exactly the
;; singular pose damping is there for. The error clamp is a length and scales
;; once.
(defconstant +lambda2+ (* 0.05 +u+ +u+)) ; damping^2: keeps J J^T well-conditioned
(defconstant +max-err+ (* 0.35 +u+))     ; clamp per-iteration error (stabilizes)

;; The three columns joint I contributes. The tip moves by w x d when the joint
;; turns at w, so with M the matrix whose column k is e_k x d -- the skew matrix
;; of d -- the columns are M . R, R the joint's WORLD rotation: a joint's angular
;; velocity is stated in its own frame, and R is what carries it out to the
;; world.
;;
;; Stating it locally is not a preference. The world-frame form solves for w in
;; world coordinates and has to carry it back into the joint's frame as
;; Rp^T Rot(w) Rp . R -- and that sandwich DOUBLES the parent's orthogonality
;; error into the child every time it runs. Eight iterations a frame down a
;; five-joint chain is 2^8 a frame, and the arm shears itself apart in under a
;; second (measured: the error goes 1e-16 -> 1e-2 in five frames, and the links
;; stretch by tens of units). The local form's update is R . Rot(w), a product
;; of two rotations, which drifts like arithmetic and not like a feedback loop.
(defun jacobian-block (d r)
  (let ((m (linalg:zeros '(3 3) :element-type 'single-float)))
    (setf (aref m 1 0) (- 0.0 (aref d 2)))
    (setf (aref m 2 0) (aref d 1))
    (setf (aref m 0 1) (aref d 2))
    (setf (aref m 2 1) (- 0.0 (aref d 0)))
    (setf (aref m 0 2) (- 0.0 (aref d 1)))
    (setf (aref m 1 2) (aref d 0))
    (linalg:matmul m r)))

(defun block-into (jac column b)
  (dotimes (r 3) (dotimes (c 3) (setf (aref jac r (+ column c)) (aref b r c)))))

;; Turn joint J by W, an angular velocity in the joint's OWN frame. The link
;; lengths need no repair afterwards, because the node's translation never moved.
(defun turn-joint (j w)
  (let ((a (linalg:norm w)))
    (when (> a 1e-7)
      (geom:place j
                  :rotation (linalg:matmul
                             (geom:rotation-of (geom:local-transform j))
                             (geom:axis-angle-matrix a w))))))

(defun solve-ik ()
  (let ((m (* 3 (length *joints*))))
    (dotimes (iter +iterations+)
      (let* ((tip (geom:world-translation *tcp*))
             (raw (linalg:sub *target* tip))
             (d (linalg:norm raw))
             ;; clamp the error so early iterations stay in the linear regime
             (e (if (> d +max-err+) (linalg:mul raw (/ +max-err+ d)) raw))
             (jac (linalg:zeros (list 3 m) :element-type 'single-float))
             (i 0))
        (dolist (j *joints*)
          (block-into jac (* 3 i)
                      (jacobian-block
                       (linalg:sub tip (geom:world-translation j))
                       (geom:world-rotation j)))
          (setq i (+ i 1)))
        (let ((a (linalg:matmul jac (linalg:transpose jac))))
          (dotimes (k 3) (setf (aref a k k) (+ (aref a k k) +lambda2+)))
          (let ((dth (linalg:matmul (linalg:transpose jac) (linalg:solve a e)))
                (n 0))
            (dolist (j *joints*)
              (turn-joint j
                          (geom:vec3 (aref dth (* 3 n)) (aref dth (+ (* 3 n) 1))
                                     (aref dth (+ (* 3 n) 2))))
              (setq n (+ n 1)))))))))

;; With the update above, what is left is ordinary float32 drift: a rotation
;; multiplied into itself eight times a frame leaves the orthogonal group by
;; about 1e-8 a frame (measured -- 1e-5 after a thousand frames of travel), and
;; a sheared rotation shears the links with it. One Gram-Schmidt per joint puts
;; it back. At 1.0 ms it is too dear to pay every frame and, against drift that
;; takes hours to show, pointless to -- so the frame hook calls it once a second
;; (+tidy-every+ below).
(defun tidy-rotations ()
  (dolist (j *joints*)
    (let* ((r (geom:rotation-of (geom:local-transform j)))
           (x (unit (linalg:row r 0)))
           (y0 (linalg:row r 1))
           (y (unit (linalg:sub y0 (linalg:mul x (linalg:dot x y0))))))
      (geom:place j :rotation (linalg:stack (list x y (linalg:cross x y)))))))

;;; --- the gripper, the wake and the goal ----------------------------------------

(defvar *grip* 1.0) ; 0 open .. 1 closed
(defvar *last-tm* 0.0)

(defun update-grip (tm)
  ;; open while the min-jerk flight is in progress, close on arrival; eased with
  ;; a time-based rate so the motion is frame-rate independent
  (let* ((dt0 (- tm *last-tm*))
         (dt (cond ((< dt0 0.0) 0.0) ((> dt0 0.05) 0.05) (t dt0)))
         (goal (if (>= (/ (- tm *t0*) *dur*) 1.0) 1.0 0.0))
         (k (* dt (if (> goal *grip*) 10.0 7.0)))
         (kk (if (> k 1.0) 1.0 k)))
    (setq *grip* (+ *grip* (* kk (- goal *grip*))))
    (setq *last-tm* tm)))

;; Six rotations: each phalanx frame's local +z is the phalanx, so the angle a
;; finger makes with the tool axis IS a rotation about the frame's own y. Splayed
;; when open, curled so the tips meet on the axis at +tool+ when closed.
(defun pose-gripper ()
  (let* ((a1 (- 0.70 (* 0.58 *grip*))) (a2 (- 0.35 (* 1.15 *grip*))))
    (dolist (finger *fingers*)
      (geom:place (first finger) :axis :y :angle a1)
      (geom:place (second finger) :axis :y :angle (- a2 a1)))))

(defvar *path* nil) ; the hand's recent positions, NEWEST first

(defun push-trail ()
  (setq *path* (cons (geom:world-translation *tcp*) (butlast *path*)))
  ;; *path* runs newest-first and *trail-spheres* oldest-first, so the two walk
  ;; in opposite directions: one reverse a frame, and no sphere is ever rebuilt.
  (let ((ps (reverse *path*)))
    (dolist (s *trail-spheres*)
      (geom:place s :translation (car ps))
      (setq ps (cdr ps)))))

(defun spin-marker (tm)
  (geom:place *marker*
              :translation *goal*
              :rotation (geom:axis-angle-matrix (* 0.6 tm) :z))
  (geom:place *ring* :axis :y :angle (* 1.3 tm)))

;;; --- the window ------------------------------------------------------------------

(defvar *view*
  (scene:viewer :title "geom robot arm -- click to reach"
                :width 1040
                :height 720))

;; Every argument is a solid or a list of solids, spliced in order.
(scene:add *view* *solids* *trail-spheres* (geom:triad :length 150))

(scene:shading *view* :solid)
(scene:grid *view* :extent 700 :spacing 70)
(scene:camera *view*
              :azimuth 0.85
              :elevation 0.32
              :distance 1250
              :target (geom:vec3 0 0 (* 0.45 +reach+)))

;; The whole of the input: a pixel is a line through the world, the viewer is the
;; only thing that knows the camera, and the plane through the orbit target is
;; the one plane it can pick without being told. Everything else -- orbit, pan,
;; dolly, resize -- the viewer already answers.
(scene:on-click *view* (lambda (p) (set-goal p (now))))

;;; --- go ------------------------------------------------------------------------

;; The initial pose is the one the chain was built in: straight up, the hand at
;; full extension. Idle until the first click, so the trajectory starts already
;; at its goal.
(setq *target* (geom:world-translation *tcp*))
(setq *start* *target*)
(setq *goal* *target*)
(setq *t0* (- (now) 10.0))
(setq *last-tm* (now))
(setq *path* (let ((out '())) (dotimes (i +trail+ out) (push *target* out))))

(pose-gripper)
(push-trail)
(spin-marker 0.0)

(format t "click to reach, drag to orbit, shift-drag to pan, scroll to dolly~%")
(format t "~a solids, ~a triangles~%" (length (scene:contents *view*))
        (let ((n 0))
          (dolist (s (scene:contents *view*) n)
            (setq n (+ n (geom:mesh-triangle-count s))))))

(defconstant +tidy-every+ 60)

(defvar *frames* 0)

(scene:animate *view*
               (lambda ()
                 (let ((tm (now)))
                   (setq *frames* (+ *frames* 1))
                   (update-target tm)
                   (update-grip tm)
                   (solve-ik)
                   (when (= 0 (mod *frames* +tidy-every+)) (tidy-rotations))
                   (pose-gripper)
                   (push-trail)
                   (spin-marker tm))))

(scene:wait *view*)
