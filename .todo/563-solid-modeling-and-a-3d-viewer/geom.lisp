;;;; geom.lisp -- SPIKE: a solid-modeling package for rontolisp.
;;;;
;;;; Rigid transforms, a scene-graph node, boundary-represented solids, and the
;;;; cached triangle mesh a renderer consumes. Pure Lisp over `linalg`: no objc,
;;;; no Metal, so it runs on all four backends.
;;;;
;;;; The API is designed here, not ported: a transform is a value with no
;;;; identity, a node OWNS a local transform rather than inheriting from one,
;;;; mutators take an explicit :frame rather than a positional flag, primitives
;;;; are noun constructors taking keywords, and a solid's mesh is cached on the
;;;; solid because a rigid body's triangles never change -- only its pose does.

(provide :geom)

(defpackage geom
  (:use cl)
  (:export transform make-transform translation-of rotation-of
           compose invert transform-point inverse-transform-point
           axis-angle-matrix rpy-matrix axis-vector vec3
           node make-node local-transform world-transform
           world-translation world-rotation parent-of children-of
           attach detach move turn place reorient
           solid box cylinder cone sphere torus extrusion revolution polyhedron
           facets-of vertices-of color-of label-of scale
           mesh wireframe mesh-triangle-count user-data
           bounds lower-of upper-of bounds-center bounds-extent bounds-union
           volume centroid surface-area))

(in-package geom)

(defconstant +pi+ 3.141592653589793)
(defconstant +tau+ 6.283185307179586)

;;; --- vectors are packed single-float linalg arrays -----------------------------
;;;
;;; float32 throughout: it is what a GPU vertex buffer holds, so a mesh reaches
;;; the renderer with no conversion (`.kb/objc.md`, "Metal").

(defun vec3 (x y z)
  (let ((v (make-array 3 :element-type 'single-float)))
    (setf (aref v 0) (float x 1.0))
    (setf (aref v 1) (float y 1.0))
    (setf (aref v 2) (float z 1.0))
    v))

(defun unit (v)
  (let ((n (linalg:norm v)))
    (linalg:mul v (/ 1.0 (if (< n 1e-9) 1e-9 n)))))

(defun identity-rotation () (linalg:eye 3 :element-type 'single-float))

;; :x / :y / :z / :-x / :-y / :-z, or a vector already.
(defun axis-vector (axis)
  (cond ((eq axis :x) (vec3 1 0 0))
        ((eq axis :y) (vec3 0 1 0))
        ((eq axis :z) (vec3 0 0 1))
        ((eq axis :-x) (vec3 -1 0 0))
        ((eq axis :-y) (vec3 0 -1 0))
        ((eq axis :-z) (vec3 0 0 -1))
        (t axis)))

;; Rodrigues' rotation formula.
(defun axis-angle-matrix (angle axis)
  (let* ((a (unit (axis-vector axis)))
         (x (aref a 0)) (y (aref a 1)) (z (aref a 2))
         (c (cos angle)) (s (sin angle)) (k (- 1.0 c))
         (m (linalg:zeros '(3 3) :element-type 'single-float)))
    (setf (aref m 0 0) (+ c (* k x x)))
    (setf (aref m 0 1) (- (* k x y) (* s z)))
    (setf (aref m 0 2) (+ (* k x z) (* s y)))
    (setf (aref m 1 0) (+ (* k y x) (* s z)))
    (setf (aref m 1 1) (+ c (* k y y)))
    (setf (aref m 1 2) (- (* k y z) (* s x)))
    (setf (aref m 2 0) (- (* k z x) (* s y)))
    (setf (aref m 2 1) (+ (* k z y) (* s x)))
    (setf (aref m 2 2) (+ c (* k z z)))
    m))

(defun rpy-matrix (roll pitch yaw)
  (linalg:matmul (axis-angle-matrix yaw :z)
                 (linalg:matmul (axis-angle-matrix pitch :y)
                                (axis-angle-matrix roll :x))))

;;; --- transform: a rigid motion, and a VALUE ---------------------------------------
;;;
;;; No parent, no identity, no cache. Everything that has a pose holds one of
;;; these; nothing mutates one in place.

(defclass transform ()
  ((translation :initarg :translation :accessor translation-of)
   (rotation :initarg :rotation :accessor rotation-of)))

(defun make-transform (&key translation rotation rpy axis (angle 0.0))
  (make-instance 'transform
                 :translation (if translation translation (vec3 0 0 0))
                 :rotation (cond (rotation rotation)
                                 (rpy (rpy-matrix (first rpy) (second rpy) (third rpy)))
                                 (axis (axis-angle-matrix angle axis))
                                 (t (identity-rotation)))))

;; a then b, i.e. the transform that applies A's motion inside B's frame.
(defun compose (outer inner)
  (make-transform
   :translation (linalg:add (linalg:matmul (rotation-of outer) (translation-of inner))
                            (translation-of outer))
   :rotation (linalg:matmul (rotation-of outer) (rotation-of inner))))

(defun invert (tf)
  (let ((rt (linalg:transpose (rotation-of tf))))
    (make-transform :rotation rt
                    :translation (linalg:mul (linalg:matmul rt (translation-of tf)) -1.0))))

(defun transform-point (tf p)
  (linalg:add (linalg:matmul (rotation-of tf) p) (translation-of tf)))

(defun inverse-transform-point (tf p)
  (linalg:matmul (linalg:transpose (rotation-of tf)) (linalg:sub p (translation-of tf))))

;;; --- node: a scene graph, with the world transform memoized ------------------------
;;;
;;; A node HAS a local transform rather than being one. Composition keeps
;;; `transform` a value and lets a solid, a camera target and a bare frame all
;;; be nodes without a slot any of them does not use.

(defclass node ()
  ((local :initarg :local :accessor local-transform)
   (parent :initarg :parent :accessor parent-of :initform nil)
   (children :initform nil :accessor children-of)
   (world :initform nil :accessor %world)))

(defun make-node (&key transform translation rotation rpy axis (angle 0.0) parent)
  (let ((n (make-instance 'node
                          :local (if transform
                                     transform
                                     (make-transform :translation translation
                                                     :rotation rotation :rpy rpy
                                                     :axis axis :angle angle)))))
    (when parent (attach parent n))
    n))

;; Every pose change drops the memoized world transform of the whole subtree.
(defun stale (n)
  (setf (%world n) nil)
  (dolist (k (children-of n) n) (stale k)))

(defun world-transform (n)
  (when (null (%world n))
    (let ((p (parent-of n)))
      (setf (%world n)
            (if (null p) (local-transform n) (compose (world-transform p) (local-transform n))))))
  (%world n))

(defun world-translation (n) (translation-of (world-transform n)))

(defun world-rotation (n) (rotation-of (world-transform n)))

(defun attach (parent child)
  (setf (parent-of child) parent)
  (setf (children-of parent) (cons child (children-of parent)))
  (stale child)
  child)

(defun detach (child)
  (let ((p (parent-of child)))
    (when p (setf (children-of p) (remove child (children-of p)))))
  (setf (parent-of child) nil)
  (stale child)
  child)

;;; The mutators. FRAME is :local (the node's own axes, the default) or :parent
;;; (the axes it is attached to) -- named rather than positional, because a
;;; caller reading `:frame :parent` needs no manual.

(defun move (n offset &key (frame :local))
  (let ((tf (local-transform n)))
    (setf (translation-of tf)
          (linalg:add (translation-of tf)
                      (if (eq frame :parent) offset (linalg:matmul (rotation-of tf) offset)))))
  (stale n))

(defun turn (n angle axis &key (frame :local))
  (let ((tf (local-transform n))
        (r (axis-angle-matrix angle axis)))
    (setf (rotation-of tf)
          (if (eq frame :parent)
              (linalg:matmul r (rotation-of tf))
              (linalg:matmul (rotation-of tf) r))))
  (stale n))

;; Sets the pose outright rather than accumulating -- what an animation loop
;; wants, since repeated `turn` deltas drift.
(defun place (n &key translation rotation rpy axis (angle 0.0))
  (let ((tf (local-transform n)))
    (when translation (setf (translation-of tf) translation))
    (cond (rotation (setf (rotation-of tf) rotation))
          (rpy (setf (rotation-of tf) (rpy-matrix (first rpy) (second rpy) (third rpy))))
          (axis (setf (rotation-of tf) (axis-angle-matrix angle axis)))))
  (stale n))

(defun reorient (n angle axis) (place n :axis axis :angle angle))

;;; --- solid: a boundary representation, and its cached mesh --------------------------
;;;
;;; VERTICES is a rank-2 (n 3) packed array of MODEL coordinates; FACETS is a
;;; list of index loops, each counter-clockwise seen from outside. Keeping the
;;; vertices in one array makes a whole-solid transform a single matmul, and
;;; keeping the mesh cached on the solid is what a renderer needs: a rigid
;;; solid's triangles never change, only its pose does, so the transform belongs
;;; to the frame and the triangles do not.

(defclass solid (node)
  ((vertices :initarg :vertices :accessor vertices-of)
   (facets :initarg :facets :accessor facets-of)
   (color :initarg :color :accessor color-of)
   (label :initarg :label :accessor label-of :initform nil)
   (mesh-cache :initform nil :accessor %mesh)
   (wire-cache :initform nil :accessor %wire)
   ;; A slot a consumer hangs its own state on -- the renderer keeps its GPU
   ;; buffers here. It is here rather than in the renderer's own `eq` hash table
   ;; because `gethash` is not identity-keyed: `:test 'eq` is accepted and
   ;; ignored, so two sibling nodes with equal slots are ONE entry (../564-...md,
   ;; and ../012-hash-table-test-semantics.md).
   (user-data :initform nil :accessor user-data)))

(defun make-solid (points facets &key color label)
  (make-instance 'solid
                 :local (make-transform)
                 :vertices (linalg:from-list points :element-type 'single-float)
                 :facets facets
                 :color (if color color (vec3 0.72 0.76 0.84))
                 :label label))

(defun invalidate-mesh (s)
  (setf (%mesh s) nil)
  (setf (%wire s) nil)
  (setf (user-data s) nil)
  s)

(defun scale (s factor)
  (setf (vertices-of s) (linalg:mul (vertices-of s) (float factor 1.0)))
  (invalidate-mesh s))

;;; --- the mesh ------------------------------------------------------------------------
;;;
;;; MODEL space, 18 floats a triangle: three corners of (position, normal). The
;;; renderer uploads this once per solid and hands the GPU the solid's world
;;; transform as a per-draw uniform. Colour is per solid, so it is a uniform too
;;; and costs the mesh no bytes.

(defun mesh (s)
  (when (null (%mesh s))
    (let ((tris 0))
      (dolist (f (facets-of s)) (setq tris (+ tris (- (length f) 2))))
      (let ((out (make-array (* tris 18) :element-type 'single-float))
            (v (vertices-of s))
            (k 0))
        (dolist (facet (facets-of s))
          (let* ((nrm (facet-normal v facet))
                 (nx (aref nrm 0)) (ny (aref nrm 1)) (nz (aref nrm 2))
                 (idx (coerce facet 'vector))
                 (m (length facet))
                 (a (aref idx 0)))
            (do ((i 1 (+ i 1)))
                ((>= i (- m 1)) nil)
              (dolist (p (list a (aref idx i) (aref idx (+ i 1))))
                (setf (aref out k) (aref v p 0))
                (setf (aref out (+ k 1)) (aref v p 1))
                (setf (aref out (+ k 2)) (aref v p 2))
                (setf (aref out (+ k 3)) nx)
                (setf (aref out (+ k 4)) ny)
                (setf (aref out (+ k 5)) nz)
                (setq k (+ k 6))))))
        (setf (%mesh s) out))))
  (%mesh s))

(defun mesh-triangle-count (s) (floor (length (mesh s)) 18))

;; Every edge once, as endpoint pairs in model space: 6 floats a segment.
(defun wireframe (s)
  (when (null (%wire s))
    (let ((seen (make-hash-table :test 'equal))
          (pairs '())
          (v (vertices-of s)))
      (dolist (facet (facets-of s))
        (let ((m (length facet)))
          (dotimes (i m)
            (let* ((a (nth i facet))
                   (b (nth (mod (+ i 1) m) facet))
                   (key (if (< a b) (cons a b) (cons b a))))
              (unless (gethash key seen)
                (setf (gethash key seen) t)
                (push key pairs))))))
      (let ((out (make-array (* (length pairs) 6) :element-type 'single-float))
            (k 0))
        (dolist (pr pairs)
          (dotimes (c 3) (setf (aref out (+ k c)) (aref v (car pr) c)))
          (dotimes (c 3) (setf (aref out (+ k 3 c)) (aref v (cdr pr) c)))
          (setq k (+ k 6)))
        (setf (%wire s) out))))
  (%wire s))

;; Newell's method: correct for a slightly non-planar loop, and it never takes
;; the cross product of a degenerate pair.
(defun facet-normal (v facet)
  (let ((nx 0.0) (ny 0.0) (nz 0.0)
        (idx (coerce facet 'vector))
        (m (length facet)))
    (dotimes (i m)
      (let* ((a (aref idx i))
             (b (aref idx (mod (+ i 1) m)))
             (ax (aref v a 0)) (ay (aref v a 1)) (az (aref v a 2))
             (bx (aref v b 0)) (by (aref v b 1)) (bz (aref v b 2)))
        (setq nx (+ nx (* (- ay by) (+ az bz))))
        (setq ny (+ ny (* (- az bz) (+ ax bx))))
        (setq nz (+ nz (* (- ax bx) (+ ay by))))))
    (unit (vec3 nx ny nz))))

;;; --- primitive solids ------------------------------------------------------------------
;;;
;;; Noun constructors taking keywords: nothing here is positional past the one
;;; measurement that names the shape, so a call site says what it means.

(defun iota-list (n)
  (let ((out '()))
    (dotimes (i n (nreverse out)) (push i out))))

;; Origin at the centre. SIZE is a scalar (a cube) or an (x y z) list.
(defun box (size &key color label)
  (let* ((dims (if (numberp size) (list size size size) size))
         (x (/ (float (first dims) 1.0) 2.0))
         (y (/ (float (second dims) 1.0) 2.0))
         (z (/ (float (third dims) 1.0) 2.0)))
    (make-solid (list (list (- x) (- y) (- z)) (list x (- y) (- z))
                      (list x y (- z)) (list (- x) y (- z))
                      (list (- x) (- y) z) (list x (- y) z)
                      (list x y z) (list (- x) y z))
                (list '(3 2 1 0) '(4 5 6 7) '(0 1 5 4)
                      '(1 2 6 5) '(2 3 7 6) '(3 0 4 7))
                :color color :label label)))

;; PROFILE is a closed loop on z = 0, as (x y z) lists; ALONG is a height or an
;; offset vector. The general prism.
(defun extrusion (profile &key (along 1.0) color label)
  (let* ((n (length profile))
         (d (if (numberp along) (list 0.0 0.0 (float along 1.0)) along))
         (top (mapcar (lambda (p)
                        (list (+ (first p) (first d)) (+ (second p) (second d))
                              (+ (third p) (third d))))
                      profile))
         (facets (list (reverse (iota-list n))
                       (mapcar (lambda (i) (+ i n)) (iota-list n)))))
    (dotimes (i n)
      (let ((j (mod (+ i 1) n)))
        (push (list i j (+ j n) (+ i n)) facets)))
    (make-solid (append profile top) (nreverse facets) :color color :label label)))

(defun ring-profile (radius sides)
  (let ((out '()))
    (dotimes (i sides (nreverse out))
      (let ((a (/ (* +tau+ i) sides)))
        (push (list (* radius (cos a)) (* radius (sin a)) 0.0) out)))))

(defun cylinder (&key (radius 1.0) (height 1.0) (sides 24) color label)
  (extrusion (ring-profile radius sides) :along height :color color :label label))

;; APEX is a point; the base is a RADIUS-wide ring on z = 0.
(defun cone (&key (radius 1.0) (height 1.0) (sides 24) apex color label)
  (let* ((ring (ring-profile radius sides))
         (n (length ring))
         (tip (if apex apex (list 0.0 0.0 (float height 1.0))))
         (facets (list (reverse (iota-list n)))))
    (dotimes (i n)
      (push (list i (mod (+ i 1) n) n) facets))
    (make-solid (append ring (list tip)) (nreverse facets) :color color :label label)))

;; PROFILE is a cross-section in the x >= 0 half of the xz-plane, as (x y z)
;; lists whose y is ignored, revolved about z. Caps are added where an end of
;; the profile does not reach the axis.
(defun revolution (profile &key (sides 24) color label)
  (let* ((m (length profile))
         (points '())
         (facets '()))
    (dotimes (s sides)
      (let* ((a (/ (* +tau+ s) sides)) (c (cos a)) (sn (sin a)))
        (dolist (p profile)
          (push (list (* (first p) c) (* (first p) sn) (third p)) points))))
    (setq points (nreverse points))
    (dotimes (s sides)
      (let ((s2 (mod (+ s 1) sides)))
        (dotimes (i (- m 1))
          (push (list (+ (* s m) i) (+ (* s m) i 1) (+ (* s2 m) i 1) (+ (* s2 m) i))
                facets))))
    (let ((top '()) (bottom '()))
      (dotimes (s sides)
        (push (* s m) top)
        (push (+ (* s m) m -1) bottom))
      (when (> (abs (first (first profile))) 1e-6) (push (nreverse top) facets))
      (when (> (abs (first (car (last profile)))) 1e-6) (push bottom facets)))
    (make-solid points (nreverse facets) :color color :label label)))

(defun sphere (&key (radius 1.0) (sides 24) (stacks 12) color label)
  (let ((profile '()))
    (dotimes (i (+ stacks 1))
      (let ((th (/ (* +pi+ i) stacks)))
        (push (list (* radius (sin th)) 0.0 (* radius (cos th))) profile)))
    (revolution (nreverse profile) :sides sides :color color :label label)))

(defun torus (&key (radius 1.0) (tube 0.25) (sides 32) (rings 16) color label)
  (let ((profile '()))
    (dotimes (i rings)
      (let ((a (/ (* +tau+ i) rings)))
        (push (list (+ radius (* tube (cos a))) 0.0 (* tube (sin a))) profile)))
    (setq profile (nreverse profile))
    (revolution (append profile (list (first profile)))
                :sides sides :color color :label label)))

;; The escape hatch: raw points and index loops, for a mesh that came from a
;; file or an algorithm.
(defun polyhedron (points facets &key color label)
  (make-solid points facets :color color :label label))

;;; --- bounds, volume, centroid --------------------------------------------------------

(defclass bounds ()
  ((lower :initarg :lower :accessor lower-of)
   (upper :initarg :upper :accessor upper-of)))

(defun bounds-of-solid (s)
  (let* ((tf (world-transform s))
         (v (linalg:add (linalg:matmul (vertices-of s)
                                       (linalg:transpose (rotation-of tf)))
                        (linalg:reshape (translation-of tf) '(1 3))))
         (n (first (linalg:shape v)))
         (lo (vec3 1e30 1e30 1e30))
         (hi (vec3 -1e30 -1e30 -1e30)))
    (dotimes (i n)
      (dotimes (k 3)
        (let ((x (aref v i k)))
          (when (< x (aref lo k)) (setf (aref lo k) x))
          (when (> x (aref hi k)) (setf (aref hi k) x)))))
    (make-instance 'bounds :lower lo :upper hi)))

(defun bounds (thing)
  (if (listp thing)
      (let ((acc nil))
        (dolist (s thing acc)
          (setq acc (if acc (bounds-union acc (bounds-of-solid s)) (bounds-of-solid s)))))
      (bounds-of-solid thing)))

(defun bounds-union (a b)
  (let ((lo (vec3 0 0 0)) (hi (vec3 0 0 0)))
    (dotimes (k 3)
      (setf (aref lo k) (min (aref (lower-of a) k) (aref (lower-of b) k)))
      (setf (aref hi k) (max (aref (upper-of a) k) (aref (upper-of b) k))))
    (make-instance 'bounds :lower lo :upper hi)))

(defun bounds-center (b) (linalg:mul (linalg:add (lower-of b) (upper-of b)) 0.5))

(defun bounds-extent (b) (linalg:sub (upper-of b) (lower-of b)))

;; The divergence theorem over the mesh triangles. A facet wound the wrong way
;; subtracts, so this doubles as a winding check.
(defun volume (s)
  (let ((m (mesh s)) (sum 0.0) (i 0) (n (length (mesh s))))
    (do ((k 0 (+ k 18)))
        ((>= k n) (abs (/ sum 6.0)))
      (setq i k)
      (setq sum (+ sum (det3 (aref m i) (aref m (+ i 1)) (aref m (+ i 2))
                             (aref m (+ i 6)) (aref m (+ i 7)) (aref m (+ i 8))
                             (aref m (+ i 12)) (aref m (+ i 13)) (aref m (+ i 14))))))))

(defun det3 (a b c d e f g h i)
  (- (+ (* a (- (* e i) (* f h))) (* c (- (* d h) (* e g))))
     (* b (- (* d i) (* f g)))))

(defun centroid (s)
  (let ((m (mesh s)) (n (length (mesh s)))
        (cx 0.0) (cy 0.0) (cz 0.0) (vol 0.0))
    (do ((k 0 (+ k 18)))
        ((>= k n)
         (if (< (abs vol) 1e-9) (vec3 0 0 0) (vec3 (/ cx vol) (/ cy vol) (/ cz vol))))
      (let ((v (det3 (aref m k) (aref m (+ k 1)) (aref m (+ k 2))
                     (aref m (+ k 6)) (aref m (+ k 7)) (aref m (+ k 8))
                     (aref m (+ k 12)) (aref m (+ k 13)) (aref m (+ k 14)))))
        (setq vol (+ vol v))
        (setq cx (+ cx (* v (/ (+ (aref m k) (aref m (+ k 6)) (aref m (+ k 12))) 4.0))))
        (setq cy (+ cy (* v (/ (+ (aref m (+ k 1)) (aref m (+ k 7)) (aref m (+ k 13))) 4.0))))
        (setq cz (+ cz (* v (/ (+ (aref m (+ k 2)) (aref m (+ k 8)) (aref m (+ k 14))) 4.0))))))))

(defun surface-area (s)
  (let ((m (mesh s)) (n (length (mesh s))) (sum 0.0))
    (do ((k 0 (+ k 18)))
        ((>= k n) (/ sum 2.0))
      (let* ((ux (- (aref m (+ k 6)) (aref m k)))
             (uy (- (aref m (+ k 7)) (aref m (+ k 1))))
             (uz (- (aref m (+ k 8)) (aref m (+ k 2))))
             (vx (- (aref m (+ k 12)) (aref m k)))
             (vy (- (aref m (+ k 13)) (aref m (+ k 1))))
             (vz (- (aref m (+ k 14)) (aref m (+ k 2))))
             (cx (- (* uy vz) (* uz vy)))
             (cy (- (* uz vx) (* ux vz)))
             (cz (- (* ux vy) (* uy vx))))
        (setq sum (+ sum (sqrt (+ (* cx cx) (* cy cy) (* cz cz)))))))))
