;;;; bench.lisp -- the measurement the renderer's design rests on.
;;;;
;;;; A 30-joint chain of cylinders and spheres -- 60 solids, ~13,800 triangles,
;;;; the scale of one articulated model -- animated by rotating its root.
;;;;
;;;; A: what a naive viewer does. Every frame, transform every vertex of every
;;;;    solid into world space and hand the renderer a fresh triangle soup.
;;;; B: what this design does. Tessellate each solid into MODEL space once; per
;;;;    frame compute one 4x4 matrix per solid and let the GPU do the rest.
;;;;
;;;; No Metal here: both numbers are the CPU work, which is the whole difference.

(require :geom "geom.lisp")

(defun v3 (x y z) (geom:vec3 x y z))
(defun ms () (get-internal-real-time))

(defvar *root* (geom:make-node))
(defvar *solids* '())

(let ((parent *root*))
  (dotimes (i 30)
    (let ((j (geom:make-node :translation (v3 0 0 100) :parent parent))
          (link (geom:cylinder :radius 20 :height 100 :sides 20))
          (ball (geom:sphere :radius 25 :sides 16 :stacks 12)))
      (geom:attach j link)
      (geom:attach j ball)
      (push link *solids*)
      (push ball *solids*)
      (setq parent j))))

(let ((tris 0))
  (dolist (s *solids*) (setq tris (+ tris (geom:mesh-triangle-count s))))
  (format t "~a solids, ~a triangles~%~%" (length *solids*) tris))

;;; --- A: world-space triangle soup, rebuilt every frame -------------------------

(defun world-soup (s out at)
  (let* ((tf (geom:world-transform s))
         (r (geom:rotation-of tf))
         (p (geom:translation-of tf))
         (m (geom:mesh s))
         (n (length m))
         (k at))
    (do ((i 0 (+ i 6)))
        ((>= i n) k)
      ;; position through the full transform, normal through the rotation only
      (dotimes (c 3)
        (setf (aref out (+ k c))
              (+ (* (aref r c 0) (aref m i))
                 (* (aref r c 1) (aref m (+ i 1)))
                 (* (aref r c 2) (aref m (+ i 2)))
                 (aref p c)))
        (setf (aref out (+ k 3 c))
              (+ (* (aref r c 0) (aref m (+ i 3)))
                 (* (aref r c 1) (aref m (+ i 4)))
                 (* (aref r c 2) (aref m (+ i 5))))))
      (setq k (+ k 6)))))

(defvar *soup*
  (let ((n 0))
    (dolist (s *solids*) (setq n (+ n (length (geom:mesh s)))))
    (make-array n :element-type 'single-float)))

(let ((t0 (ms)) (frames 20))
  (dotimes (f frames)
    (geom:turn *root* 0.01 :z)
    (let ((at 0))
      (dolist (s *solids*) (setq at (world-soup s *soup* at)))))
  (format t "A  world-space soup per frame : ~a ms a frame~%"
          (/ (- (ms) t0) (* 1.0 frames))))

;;; --- B: cached model mesh, one 4x4 per solid ------------------------------------

(defun model-matrix (s)
  (let ((m (linalg:zeros '(4 4) :element-type 'single-float))
        (tf (geom:world-transform s)))
    (dotimes (i 3)
      (dotimes (j 3) (setf (aref m j i) (aref (geom:rotation-of tf) i j)))
      (setf (aref m 3 i) (aref (geom:translation-of tf) i)))
    (setf (aref m 3 3) 1.0)
    m))

(let ((t0 (ms)) (frames 60) (sink 0.0))
  (dotimes (f frames)
    (geom:turn *root* 0.01 :z)
    (dolist (s *solids*) (setq sink (+ sink (aref (model-matrix s) 3 2)))))
  (format t "B  model matrix per solid     : ~a ms a frame~%"
          (/ (- (ms) t0) (* 1.0 frames)))
  sink)

;;; and what B pays once, at load time
(let ((t0 (ms)) (fresh '()))
  (dotimes (i 60) (push (geom:cylinder :radius 20 :height 100 :sides 20) fresh))
  (dolist (s fresh) (geom:mesh s) (geom:wireframe s))
  (format t "~%B's one-time tessellation of 60 solids: ~a ms~%" (- (ms) t0)))
