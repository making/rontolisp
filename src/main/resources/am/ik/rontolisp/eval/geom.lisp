;; The geom package: solid modeling -- rigid transforms, a scene graph, and
;; boundary-represented solids with the triangle mesh a renderer consumes.
;; Written in rontolisp itself over the linalg kernels and NOTHING else (no
;; objc:, no java:, no filesystem), so one implementation runs on every backend
;; and in the browser playground: the interpreter loads these definitions lazily
;; on the first use of a geom: function, and the compile path splices them into
;; the program when it references the package (see GeomLibrary.java).
;;
;; The three types, and why they are shaped this way (.kb/geom.md):
;; - transform -- a rigid motion, and a VALUE: a translation 3-vector and a 3x3
;;   rotation, no parent, no identity, no cache. compose / invert /
;;   transform-point build new ones, and so do the node mutators, which REPLACE
;;   a node's local transform rather than assigning into it. A transform handed
;;   to two nodes is therefore shared safely.
;; - node -- HAS a local transform (composition, not inheritance), so a solid, a
;;   camera target and a bare joint frame are all nodes with no slot any of them
;;   does not use. The world transform is memoized and invalidated down the
;;   whole subtree on every pose change.
;; - solid -- a node carrying a boundary representation: one rank-2 (n 3) packed
;;   array of MODEL coordinates (so a whole-solid transform is one matmul) plus
;;   a list of index loops, each counter-clockwise seen from outside.
;;
;; Everything is float32 (:element-type 'single-float), because a packed
;; single-float array IS a GPU vertex buffer's bytes and objc:data takes one of
;; any rank -- so a mesh reaches Metal with no conversion (.kb/objc.md,
;; "Metal"). Every linalg transform preserves the width (.kb/linalg.md).
;;
;; Portability constraints honored here (like linalg.lisp): do loops always
;; declare at least one variable; parameters are never assigned with setq
;; (let-rebound instead); every make-array takes a LITERAL :element-type so each
;; backend picks the float[] representation statically.

(defconstant geom::+pi+ 3.141592653589793)

(defconstant geom::+tau+ 6.283185307179586)

;; --- vectors -----------------------------------------------------------------

(defun geom:vec3 (x y z)
  (let ((v (make-array 3 :element-type 'single-float :initial-element 0.0)))
    (setf (aref v 0) (float x 1.0))
    (setf (aref v 1) (float y 1.0))
    (setf (aref v 2) (float z 1.0))
    v))

(defun geom::%unit (v)
  (let ((n (linalg:norm v))) (linalg:mul v (/ 1.0 (if (< n 1e-9) 1e-9 n)))))

(defun geom::%identity-rotation () (linalg:eye 3 :element-type 'single-float))

;; :x / :y / :z / :-x / :-y / :-z, or a vector already.
(defun geom:axis-vector (axis)
  (cond ((eq axis :x) (geom:vec3 1 0 0))
        ((eq axis :y) (geom:vec3 0 1 0))
        ((eq axis :z) (geom:vec3 0 0 1))
        ((eq axis :-x) (geom:vec3 -1 0 0))
        ((eq axis :-y) (geom:vec3 0 -1 0))
        ((eq axis :-z) (geom:vec3 0 0 -1))
        (t axis)))

;; Rodrigues' rotation formula.
(defun geom:axis-angle-matrix (angle axis)
  (let* ((a (geom::%unit (geom:axis-vector axis)))
         (x (aref a 0))
         (y (aref a 1))
         (z (aref a 2))
         (c (cos angle))
         (s (sin angle))
         (k (- 1.0 c))
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

;; Roll about x, then pitch about y, then yaw about z.
(defun geom:rpy-matrix (roll pitch yaw)
  (linalg:matmul (geom:axis-angle-matrix yaw :z)
                 (linalg:matmul (geom:axis-angle-matrix pitch :y)
                                (geom:axis-angle-matrix roll :x))))

;; --- transform: a rigid motion, and a VALUE ----------------------------------
;;
;; Both slots are read-only: nothing mutates a transform in place, so the same
;; one may be the local transform of any number of nodes.

(defclass geom:transform ()
  ((translation :initarg :translation :reader geom:translation-of)
   (rotation :initarg :rotation :reader geom:rotation-of)))

(defun geom:make-transform (&key translation rotation rpy axis (angle 0.0))
  (make-instance 'geom:transform
                 :translation (if translation translation (geom:vec3 0 0 0))
                 :rotation (cond (rotation rotation)
                                 (rpy (geom:rpy-matrix (first rpy) (second rpy)
                                                       (third rpy)))
                                 (axis (geom:axis-angle-matrix angle axis))
                                 (t (geom::%identity-rotation)))))

;; OUTER applied to INNER: the transform that carries INNER's motion into
;; OUTER's frame.
(defun geom:compose (outer inner)
  (geom:make-transform
   :translation (linalg:add (linalg:matmul (geom:rotation-of outer)
                                           (geom:translation-of inner))
                            (geom:translation-of outer))
   :rotation (linalg:matmul (geom:rotation-of outer) (geom:rotation-of inner))))

(defun geom:invert (tf)
  (let ((rt (linalg:transpose (geom:rotation-of tf))))
    (geom:make-transform :rotation rt
                         :translation (linalg:mul (linalg:matmul rt
                                                   (geom:translation-of tf))
                                                  -1.0))))

(defun geom:transform-point (tf p)
  (linalg:add (linalg:matmul (geom:rotation-of tf) p) (geom:translation-of tf)))

(defun geom:inverse-transform-point (tf p)
  (linalg:matmul (linalg:transpose (geom:rotation-of tf))
                 (linalg:sub p (geom:translation-of tf))))

;; --- node: a scene graph, with the world transform memoized ------------------
;;
;; A node HAS a local transform rather than being one. Every slot is internal;
;; the pose is read through world-transform and changed only through the
;; mutators, so the memo below can never go stale unnoticed.

(defclass geom:node ()
  ((local :initarg :local :accessor geom::%local)
   (parent :initform nil :accessor geom::%parent)
   (children :initform nil :accessor geom::%children)
   (world :initform nil :accessor geom::%world)))

(defun geom:make-node
    (&key transform translation rotation rpy axis (angle 0.0) parent)
  (let ((n
         (make-instance 'geom:node
                        :local (if transform
                                   transform
                                   (geom:make-transform :translation translation
                                                        :rotation rotation
                                                        :rpy rpy
                                                        :axis axis
                                                        :angle angle)))))
    (when parent (geom:attach parent n))
    n))

(defun geom:local-transform (n) (geom::%local n))

(defun geom:parent-of (n) (geom::%parent n))

(defun geom:children-of (n) (geom::%children n))

;; Every pose change drops the memoized world transform of the whole subtree.
(defun geom::%stale (n)
  (setf (geom::%world n) nil)
  (dolist (k (geom::%children n) n) (geom::%stale k)))

(defun geom:world-transform (n)
  (when (null (geom::%world n))
    (let ((p (geom::%parent n)))
      (setf (geom::%world n)
            (if (null p)
                (geom::%local n)
                (geom:compose (geom:world-transform p) (geom::%local n))))))
  (geom::%world n))

(defun geom:world-translation (n)
  (geom:translation-of (geom:world-transform n)))

(defun geom:world-rotation (n) (geom:rotation-of (geom:world-transform n)))

(defun geom:attach (parent child)
  (setf (geom::%parent child) parent)
  (setf (geom::%children parent) (cons child (geom::%children parent)))
  (geom::%stale child)
  child)

(defun geom:detach (child)
  (let ((p (geom::%parent child)))
    (when p (setf (geom::%children p) (remove child (geom::%children p)))))
  (setf (geom::%parent child) nil)
  (geom::%stale child)
  child)

;; The mutators. FRAME is :local (the node's own axes, the default) or :parent
;; (the axes it is attached to) -- named rather than positional, because a
;; caller reading `:frame :parent` needs no manual. Each one REPLACES the local
;; transform with a fresh one, which is what keeps a transform a value.

(defun geom:move (n offset &key (frame :local))
  (let ((tf (geom::%local n)))
    (setf (geom::%local n)
          (geom:make-transform :translation
                               (linalg:add (geom:translation-of tf)
                                           (if (eq frame :parent)
                                               offset
                                               (linalg:matmul
                                                (geom:rotation-of tf) offset)))
                               :rotation (geom:rotation-of tf))))
  (geom::%stale n))

(defun geom:turn (n angle axis &key (frame :local))
  (let ((tf (geom::%local n)) (r (geom:axis-angle-matrix angle axis)))
    (setf (geom::%local n)
          (geom:make-transform :translation (geom:translation-of tf)
                               :rotation
                               (if (eq frame :parent)
                                   (linalg:matmul r (geom:rotation-of tf))
                                   (linalg:matmul (geom:rotation-of tf) r)))))
  (geom::%stale n))

;; Sets the pose outright rather than accumulating -- what an animation loop
;; wants, since repeated `turn` deltas drift. An omitted half is kept.
(defun geom:place (n &key transform translation rotation rpy axis (angle 0.0))
  (let ((tf (geom::%local n)))
    (setf (geom::%local n)
          (if transform
              transform
              (geom:make-transform :translation
                                   (if translation
                                       translation
                                       (geom:translation-of tf))
                                   :rotation
                                   (cond (rotation rotation)
                                    (rpy
                                     (geom:rpy-matrix (first rpy) (second rpy)
                                                      (third rpy)))
                                    (axis (geom:axis-angle-matrix angle axis))
                                    (t (geom:rotation-of tf)))))))
  (geom::%stale n))

(defun geom:reorient (n angle axis) (geom:place n :axis axis :angle angle))

;; --- solid: a boundary representation, and its cached mesh -------------------

(defclass geom:solid (geom:node)
  ((vertices :initarg :vertices :accessor geom::%vertices)
   (facets :initarg :facets :reader geom:facets-of)
   (color :initarg :color :accessor geom:color-of)
   (label :initarg :label :initform nil :accessor geom:label-of)
   (mesh-cache :initform nil :accessor geom::%mesh)
   (wire-cache :initform nil :accessor geom::%wire)
   ;; A slot a consumer hangs its own state on -- a renderer keeps its GPU
   ;; buffers here, where they live with the mesh they describe and cannot be
   ;; orphaned by a detach. It is a slot rather than the renderer's own hash
   ;; table because a table cannot key on a node AT ALL: :test 'eq is accepted
   ;; and ignored, so keys are compared STRUCTURALLY, and two sibling nodes with
   ;; equal slots collide into one entry -- a wrong answer, not a slow one
   ;; (.kb/geom.md, .kb/hash-tables.md).
   (user-data :initform nil :accessor geom:user-data)
   ;; What built this solid: nil for a primitive, (op a b) for a boolean result
   ;; -- so a program can re-run a model at a different parameter.
   (history :initform nil :accessor geom::%history)))

(defun geom::%build-solid (points facets &key color label)
  (make-instance 'geom:solid
                 :local (geom:make-transform)
                 :vertices (linalg:from-list points :element-type 'single-float)
                 :facets facets
                 :color (if color color (geom:vec3 0.72 0.76 0.84))
                 :label label))

(defun geom:vertices-of (s) (geom::%vertices s))

(defun geom::%invalidate-mesh (s)
  (setf (geom::%mesh s) nil)
  (setf (geom::%wire s) nil)
  (setf (geom:user-data s) nil)
  s)

;; The one vertex mutation the package offers, so it is also the one place that
;; has to drop the caches below.
(defun geom:scale (s factor)
  (setf (geom::%vertices s) (linalg:mul (geom::%vertices s) (float factor 1.0)))
  (geom::%invalidate-mesh s))

;; --- the mesh ----------------------------------------------------------------
;;
;; MODEL space, 18 floats a triangle: three corners of (position, normal),
;; fan-triangulated per facet with a Newell normal. Computed once and kept in a
;; slot, because a rigid solid's triangles never change and only its pose does:
;; a renderer uploads this once per solid and hands the GPU the solid's world
;; transform as a per-draw uniform. Colour is per solid, so it is a uniform too
;; and costs the mesh no bytes. This is a public function, not a renderer's
;; internal detail -- re-tessellating per frame is 42x slower (.kb/geom.md).

(defun geom:mesh (s)
  (when (null (geom::%mesh s))
    (let ((tris 0))
      (dolist (f (geom:facets-of s)) (setq tris (+ tris (- (length f) 2))))
      (let ((out
             (make-array (* tris 18)
                         :element-type 'single-float
                         :initial-element 0.0))
            (v (geom::%vertices s))
            (k 0))
        (dolist (facet (geom:facets-of s))
          (let* ((nrm (geom::%facet-normal v facet))
                 (nx (aref nrm 0))
                 (ny (aref nrm 1))
                 (nz (aref nrm 2))
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
        (setf (geom::%mesh s) out))))
  (geom::%mesh s))

(defun geom:mesh-triangle-count (s) (floor (length (geom:mesh s)) 18))

;; Every edge once, as endpoint pairs in model space: 6 floats a segment,
;; cached like the mesh.
(defun geom:wireframe (s)
  (when (null (geom::%wire s))
    (let ((seen (make-hash-table :test 'equal))
          (pairs '())
          (v (geom::%vertices s)))
      (dolist (facet (geom:facets-of s))
        (let ((m (length facet)))
          (dotimes (i m)
            (let* ((a (nth i facet))
                   (b (nth (mod (+ i 1) m) facet))
                   (key (if (< a b) (cons a b) (cons b a))))
              (unless (gethash key seen)
                (setf (gethash key seen) t)
                (push key pairs))))))
      (let ((out
             (make-array (* (length pairs) 6)
                         :element-type 'single-float
                         :initial-element 0.0))
            (k 0))
        (dolist (pr pairs)
          (dotimes (c 3) (setf (aref out (+ k c)) (aref v (car pr) c)))
          (dotimes (c 3) (setf (aref out (+ k 3 c)) (aref v (cdr pr) c)))
          (setq k (+ k 6)))
        (setf (geom::%wire s) out))))
  (geom::%wire s))

;; Newell's method: correct for a slightly non-planar loop, and it never takes
;; the cross product of a degenerate pair.
(defun geom::%facet-normal (v facet)
  (let ((nx 0.0)
        (ny 0.0)
        (nz 0.0)
        (idx (coerce facet 'vector))
        (m (length facet)))
    (dotimes (i m)
      (let* ((a (aref idx i))
             (b (aref idx (mod (+ i 1) m)))
             (ax (aref v a 0))
             (ay (aref v a 1))
             (az (aref v a 2))
             (bx (aref v b 0))
             (by (aref v b 1))
             (bz (aref v b 2)))
        (setq nx (+ nx (* (- ay by) (+ az bz))))
        (setq ny (+ ny (* (- az bz) (+ ax bx))))
        (setq nz (+ nz (* (- ax bx) (+ ay by))))))
    (geom::%unit (geom:vec3 nx ny nz))))

;; --- primitive solids --------------------------------------------------------
;;
;; Noun constructors taking keywords: nothing here is positional past the one
;; measurement that names the shape, so a call site says what it means.

(defun geom::%iota-list (n)
  (let ((out '())) (dotimes (i n (nreverse out)) (push i out))))

;; Origin at the centre. SIZE is a scalar (a cube) or an (x y z) list.
(defun geom:box (size &key color label)
  (let* ((dims (if (numberp size) (list size size size) size))
         (x (/ (float (first dims) 1.0) 2.0))
         (y (/ (float (second dims) 1.0) 2.0))
         (z (/ (float (third dims) 1.0) 2.0)))
    (geom::%build-solid
     (list (list (- x) (- y) (- z)) (list x (- y) (- z)) (list x y (- z))
           (list (- x) y (- z)) (list (- x) (- y) z) (list x (- y) z)
           (list x y z) (list (- x) y z))
     (list '(3 2 1 0) '(4 5 6 7) '(0 1 5 4) '(1 2 6 5) '(2 3 7 6) '(3 0 4 7))
     :color color
     :label label)))

;; PROFILE is a closed loop on z = 0, as (x y z) lists; ALONG is a height or an
;; offset vector. The general prism.
(defun geom:extrusion (profile &key (along 1.0) color label)
  (let* ((n (length profile))
         (d (if (numberp along) (list 0.0 0.0 (float along 1.0)) along))
         (top
          (mapcar (lambda (p)
                    (list (+ (first p) (first d)) (+ (second p) (second d))
                          (+ (third p) (third d)))) profile))
         (facets
          (list (reverse (geom::%iota-list n))
                (mapcar (lambda (i) (+ i n)) (geom::%iota-list n)))))
    (dotimes (i n)
      (let ((j (mod (+ i 1) n))) (push (list i j (+ j n) (+ i n)) facets)))
    (geom::%build-solid (append profile top) (nreverse facets)
                        :color color
                        :label label)))

(defun geom::%ring-profile (radius sides)
  (let ((out '()))
    (dotimes (i sides (nreverse out))
      (let ((a (/ (* geom::+tau+ i) sides)))
        (push (list (* radius (cos a)) (* radius (sin a)) 0.0) out)))))

(defun geom:cylinder (&key (radius 1.0) (height 1.0) (sides 24) color label)
  (geom:extrusion (geom::%ring-profile radius sides)
                  :along height
                  :color color
                  :label label))

;; APEX is a point; the base is a RADIUS-wide ring on z = 0.
(defun geom:cone (&key (radius 1.0) (height 1.0) (sides 24) apex color label)
  (let* ((ring (geom::%ring-profile radius sides))
         (n (length ring))
         (tip (if apex apex (list 0.0 0.0 (float height 1.0))))
         (facets (list (reverse (geom::%iota-list n)))))
    (dotimes (i n) (push (list i (mod (+ i 1) n) n) facets))
    (geom::%build-solid (append ring (list tip)) (nreverse facets)
                        :color color
                        :label label)))

;; PROFILE is a cross-section in the x >= 0 half of the xz-plane, as (x y z)
;; lists whose y is ignored, revolved about z. Caps are added where an end of
;; the profile does not reach the axis.
(defun geom:revolution (profile &key (sides 24) color label)
  (let ((m (length profile)) (points '()) (facets '()))
    (dotimes (s sides)
      (let* ((a (/ (* geom::+tau+ s) sides)) (c (cos a)) (sn (sin a)))
        (dolist (p profile)
          (push (list (* (first p) c) (* (first p) sn) (third p)) points))))
    (setq points (nreverse points))
    (dotimes (s sides)
      (let ((s2 (mod (+ s 1) sides)))
        (dotimes (i (- m 1))
          (push
           (list (+ (* s m) i) (+ (* s m) i 1) (+ (* s2 m) i 1) (+ (* s2 m) i))
           facets))))
    (let ((top '()) (bottom '()))
      (dotimes (s sides)
        (push (* s m) top)
        (push (+ (* s m) m -1) bottom))
      (when (> (abs (first (first profile))) 1e-6) (push (nreverse top) facets))
      (when (> (abs (first (car (last profile)))) 1e-6) (push bottom facets)))
    (geom::%build-solid points (nreverse facets) :color color :label label)))

(defun geom:sphere (&key (radius 1.0) (sides 24) (stacks 12) color label)
  (let ((profile '()))
    (dotimes (i (+ stacks 1))
      (let ((th (/ (* geom::+pi+ i) stacks)))
        (push (list (* radius (sin th)) 0.0 (* radius (cos th))) profile)))
    (geom:revolution (nreverse profile)
                     :sides sides
                     :color color
                     :label label)))

(defun geom:torus
    (&key (radius 1.0) (tube 0.25) (sides 32) (rings 16) color label)
  (let ((profile '()))
    (dotimes (i rings)
      (let ((a (/ (* geom::+tau+ i) rings)))
        (push (list (+ radius (* tube (cos a))) 0.0 (* tube (sin a))) profile)))
    (setq profile (nreverse profile))
    (geom:revolution (append profile (list (first profile)))
                     :sides sides
                     :color color
                     :label label)))

;; The escape hatch: raw points and index loops, for a mesh that came from a
;; file or an algorithm.
(defun geom:polyhedron (points facets &key color label)
  (geom::%build-solid points facets :color color :label label))

;; --- bounds, volume, centroid ------------------------------------------------

(defclass geom:bounds ()
  ((lower :initarg :lower :reader geom:lower-of)
   (upper :initarg :upper :reader geom:upper-of)))

(defun geom::%solid-bounds (s)
  (let* ((tf (geom:world-transform s))
         (v
          (linalg:add (linalg:matmul (geom::%vertices s)
                                     (linalg:transpose (geom:rotation-of tf)))
                      (linalg:reshape (geom:translation-of tf) '(1 3))))
         (n (first (linalg:shape v)))
         (lo (geom:vec3 1e30 1e30 1e30))
         (hi (geom:vec3 -1e30 -1e30 -1e30)))
    (dotimes (i n)
      (dotimes (k 3)
        (let ((x (aref v i k)))
          (when (< x (aref lo k)) (setf (aref lo k) x))
          (when (> x (aref hi k)) (setf (aref hi k) x)))))
    (make-instance 'geom:bounds :lower lo :upper hi)))

;; The axis-aligned box of a solid, or of a list of them, in WORLD coordinates.
(defun geom:bounds (thing)
  (if (listp thing)
      (let ((acc nil))
        (dolist (s thing acc)
          (setq acc
                (if acc
                    (geom:bounds-union acc (geom::%solid-bounds s))
                    (geom::%solid-bounds s)))))
      (geom::%solid-bounds thing)))

(defun geom:bounds-union (a b)
  (let ((lo (geom:vec3 0 0 0)) (hi (geom:vec3 0 0 0)))
    (dotimes (k 3)
      (setf (aref lo k)
            (min (aref (geom:lower-of a) k) (aref (geom:lower-of b) k)))
      (setf (aref hi k)
            (max (aref (geom:upper-of a) k) (aref (geom:upper-of b) k))))
    (make-instance 'geom:bounds :lower lo :upper hi)))

(defun geom:bounds-center (b)
  (linalg:mul (linalg:add (geom:lower-of b) (geom:upper-of b)) 0.5))

(defun geom:bounds-extent (b) (linalg:sub (geom:upper-of b) (geom:lower-of b)))

(defun geom::%det3 (a b c d e f g h i)
  (- (+ (* a (- (* e i) (* f h))) (* c (- (* d h) (* e g))))
     (* b (- (* d i) (* f g)))))

;; The divergence theorem over the mesh triangles. A facet wound the wrong way
;; subtracts, so this doubles as a winding check: a mis-wound solid answers a
;; grossly wrong volume rather than a slightly wrong one.
(defun geom:volume (s)
  (let ((m (geom:mesh s)) (sum 0.0) (n (length (geom:mesh s))))
    (do ((k 0 (+ k 18)))
        ((>= k n) (abs (/ sum 6.0)))
      (setq sum
            (+ sum
               (geom::%det3 (aref m k) (aref m (+ k 1)) (aref m (+ k 2))
                            (aref m (+ k 6)) (aref m (+ k 7)) (aref m (+ k 8))
                            (aref m (+ k 12)) (aref m (+ k 13))
                            (aref m (+ k 14))))))))

;; The centre of volume, in MODEL coordinates, by the same signed-tetrahedron
;; sum as volume.
(defun geom:centroid (s)
  (let ((m (geom:mesh s))
        (n (length (geom:mesh s)))
        (cx 0.0)
        (cy 0.0)
        (cz 0.0)
        (vol 0.0))
    (do ((k 0 (+ k 18)))
        ((>= k n)
         (if (< (abs vol) 1e-9)
             (geom:vec3 0 0 0)
             (geom:vec3 (/ cx vol) (/ cy vol) (/ cz vol))))
      (let ((v
             (geom::%det3 (aref m k) (aref m (+ k 1)) (aref m (+ k 2))
                          (aref m (+ k 6)) (aref m (+ k 7)) (aref m (+ k 8))
                          (aref m (+ k 12)) (aref m (+ k 13))
                          (aref m (+ k 14)))))
        (setq vol (+ vol v))
        (setq cx
         (+ cx (* v (/ (+ (aref m k) (aref m (+ k 6)) (aref m (+ k 12))) 4.0))))
        (setq cy
              (+ cy
                 (* v
                    (/ (+ (aref m (+ k 1)) (aref m (+ k 7)) (aref m (+ k 13)))
                       4.0))))
        (setq cz
              (+ cz
                 (* v
                    (/ (+ (aref m (+ k 2)) (aref m (+ k 8)) (aref m (+ k 14)))
                       4.0))))))))

(defun geom:surface-area (s)
  (let ((m (geom:mesh s)) (n (length (geom:mesh s))) (sum 0.0))
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

;; --- constructive solid geometry ---------------------------------------------
;;
;; Boolean operations by BSP-tree clipping (the csg.js formulation): each
;; operand's boundary polygons -- taken in WORLD coordinates, so
;; (geom:difference plate hole) means what it looks like after both have been
;; placed -- are sorted into a binary space partition, the two trees clip each
;; other's polygons, and the surviving fragments are the result's boundary.
;; The trade against the classic face-splitting/classification pipeline is
;; recorded in .kb/geom.md: the BSP formulation has no per-degeneracy special
;; cases (one epsilon classifies everything), at the cost of fragmenting faces
;; that did not strictly need splitting -- which costs geom nothing, because a
;; facet is fan-triangulated for rendering anyway.
;;
;; Every operation answers a NEW root solid (world-coordinate vertices, an
;; identity local transform) and leaves its operands untouched; the result
;; records what built it in (geom:history result) => (op a b).
;;
;; Precision: the whole pipeline runs in float64 -- scalar arithmetic here is
;; double, and reading an operand's packed float32 vertex widens on the aref
;; -- and narrows back to float32 only when the result's vertex array is built.
;; The near-degenerate classification the tolerance below guards against is
;; therefore judged with 29 more bits than the data carries.

;; The classification tolerance, RELATIVE to the operands: a point within
;; (* geom:*tolerance* extent) of a cutting plane counts as ON it, where extent
;; is the largest side of the operands' combined world bounding box. geom has
;; no unit of length, so an absolute epsilon cannot be right for both a
;; 0.001-scale and a 1000-scale model; a relative one survives both. Rebind it
;; around a call to loosen or tighten a single operation.
(defparameter geom:*tolerance* 1.0e-5)

(defun geom::%operand-epsilon (solids)
  (let* ((e (geom:bounds-extent (geom:bounds solids)))
         (m (max (aref e 0) (aref e 1) (aref e 2))))
    (* geom:*tolerance* (if (< m 1e-30) 1.0 m))))

;; Points are (x y z) lists of doubles inside the pipeline; a plane is
;; (nx ny nz w) with unit normal and n.p = w; a polygon is (plane . vertices)
;; wound counter-clockwise seen from its front.

(defun geom::%v- (a b)
  (list (- (first a) (first b)) (- (second a) (second b))
        (- (third a) (third b))))

(defun geom::%vdot (a b)
  (+ (* (first a) (first b)) (* (second a) (second b)) (* (third a) (third b))))

(defun geom::%vcross (a b)
  (list (- (* (second a) (third b)) (* (third a) (second b)))
        (- (* (third a) (first b)) (* (first a) (third b)))
        (- (* (first a) (second b)) (* (second a) (first b)))))

(defun geom::%vlerp (a b tt)
  (list (+ (first a) (* tt (- (first b) (first a))))
        (+ (second a) (* tt (- (second b) (second a))))
        (+ (third a) (* tt (- (third b) (third a))))))

(defun geom::%as-point (p)
  (if (listp p)
      (list (float (first p) 1.0) (float (second p) 1.0) (float (third p) 1.0))
      (list (aref p 0) (aref p 1) (aref p 2))))

;; Newell over the loop (correct for a slightly non-planar fragment), w
;; averaged over the vertices; nil for a fully degenerate loop.
(defun geom::%loop-plane (verts)
  (let ((nx 0.0)
        (ny 0.0)
        (nz 0.0)
        (m (length verts))
        (vv (coerce verts 'vector)))
    (dotimes (i m)
      (let ((a (aref vv i)) (b (aref vv (mod (+ i 1) m))))
        (setq nx (+ nx (* (- (second a) (second b)) (+ (third a) (third b)))))
        (setq ny (+ ny (* (- (third a) (third b)) (+ (first a) (first b)))))
        (setq nz (+ nz (* (- (first a) (first b)) (+ (second a) (second b)))))))
    (let ((len (sqrt (+ (* nx nx) (* ny ny) (* nz nz)))))
      (if (< len 1e-30)
          nil
          (let ((x (/ nx len)) (y (/ ny len)) (z (/ nz len)) (w 0.0))
            (dolist (v verts)
              (setq w
                    (+ w (+ (* x (first v)) (* y (second v)) (* z (third v))))))
            (list x y z (/ w m)))))))

(defun geom::%plane-flip (pl)
  (list (- (first pl)) (- (second pl)) (- (third pl)) (- (nth 3 pl))))

(defun geom::%poly-flip (poly)
  (cons (geom::%plane-flip (car poly)) (reverse (cdr poly))))

;; A solid's boundary polygons in world coordinates (each facet one polygon,
;; with its plane computed once and inherited by every fragment split from it).
(defun geom::%world-polygons (s)
  (let* ((tf (geom:world-transform s))
         (v
          (linalg:add (linalg:matmul (geom::%vertices s)
                                     (linalg:transpose (geom:rotation-of tf)))
                      (linalg:reshape (geom:translation-of tf) '(1 3))))
         (out '()))
    (dolist (facet (geom:facets-of s) (nreverse out))
      (let ((verts '()))
        (dolist (i facet)
          (push (list (aref v i 0) (aref v i 1) (aref v i 2)) verts))
        (let* ((vl (nreverse verts)) (pl (geom::%loop-plane vl)))
          (when pl (push (cons pl vl) out)))))))

;; Split POLY by PLANE into (coplanar-front coplanar-back front back), each a
;; list of polygons. The single place a point is classified: within EPS of the
;; plane is ON, and a polygon all of whose points are ON goes with the side its
;; own normal faces. Fragments inherit the parent polygon's plane.
(defun geom::%split-polygon (plane poly eps)
  (let* ((n (list (first plane) (second plane) (third plane)))
         (w (nth 3 plane))
         (verts (cdr poly))
         (m (length verts))
         (vv (coerce verts 'vector))
         (tv (make-array m :initial-element 0))
         (has-front nil)
         (has-back nil))
    (dotimes (i m)
      (let ((d (- (geom::%vdot n (aref vv i)) w)))
        (cond ((> d eps)
               (setf (aref tv i) 1)
               (setq has-front t))
              ((< d (- eps))
               (setf (aref tv i) 2)
               (setq has-back t))
              (t (setf (aref tv i) 0)))))
    (cond ((and (not has-front) (not has-back))
           (if (> (geom::%vdot n (car poly)) 0)
               (list (list poly) '() '() '())
               (list '() (list poly) '() '())))
          ((not has-back) (list '() '() (list poly) '()))
          ((not has-front) (list '() '() '() (list poly)))
          (t (let ((f '()) (b '()))
               (dotimes (i m)
                 (let* ((j (mod (+ i 1) m))
                        (vi (aref vv i))
                        (ti (aref tv i))
                        (tj (aref tv j)))
                   (when (not (= ti 2)) (push vi f))
                   (when (not (= ti 1)) (push vi b))
                   (when (or (and (= ti 1) (= tj 2)) (and (= ti 2) (= tj 1)))
                     (let* ((vj (aref vv j))
                            (tt
                             (/ (- w (geom::%vdot n vi))
                                (geom::%vdot n (geom::%v- vj vi))))
                            (v (geom::%vlerp vi vj tt)))
                       (push v f)
                       (push v b)))))
               (list '() '()
                (if (>= (length f) 3) (list (cons (car poly) (nreverse f))) '())
                (if (>= (length b) 3)
                    (list (cons (car poly) (nreverse b)))
                    '())))))))

;; A BSP node is #(plane polygons front back), built and clipped in place.
(defun geom::%bsp () (make-array 4 :initial-element nil))

(defun geom::%bsp-build (node polys eps)
  (when polys
    (when (null (aref node 0)) (setf (aref node 0) (car (first polys))))
    (let ((plane (aref node 0)) (keep '()) (front '()) (back '()))
      (dolist (p polys)
        (let ((sp (geom::%split-polygon plane p eps)))
          (dolist (q (first sp)) (push q keep))
          (dolist (q (second sp)) (push q keep))
          (dolist (q (nth 2 sp)) (push q front))
          (dolist (q (nth 3 sp)) (push q back))))
      (setf (aref node 1) (append (aref node 1) (nreverse keep)))
      (when front
        (when (null (aref node 2)) (setf (aref node 2) (geom::%bsp)))
        (geom::%bsp-build (aref node 2) (nreverse front) eps))
      (when back
        (when (null (aref node 3)) (setf (aref node 3) (geom::%bsp)))
        (geom::%bsp-build (aref node 3) (nreverse back) eps))))
  node)

;; Turns the tree's solid inside out.
(defun geom::%bsp-invert (node)
  (when node
    (setf (aref node 1)
          (mapcar (lambda (p) (geom::%poly-flip p)) (aref node 1)))
    (when (aref node 0) (setf (aref node 0) (geom::%plane-flip (aref node 0))))
    (let ((f (aref node 2)))
      (setf (aref node 2) (aref node 3))
      (setf (aref node 3) f))
    (geom::%bsp-invert (aref node 2))
    (geom::%bsp-invert (aref node 3)))
  node)

;; The fragments of POLYS outside the solid NODE describes; a fragment behind
;; every plane on its path (inside) is dropped with the leaf it lands in.
(defun geom::%bsp-clip-polygons (node polys eps)
  (if (null (aref node 0))
      polys
      (let ((front '()) (back '()))
        (dolist (p polys)
          (let ((sp (geom::%split-polygon (aref node 0) p eps)))
            (dolist (q (first sp)) (push q front))
            (dolist (q (nth 2 sp)) (push q front))
            (dolist (q (second sp)) (push q back))
            (dolist (q (nth 3 sp)) (push q back))))
        (append (if (aref node 2)
                    (geom::%bsp-clip-polygons (aref node 2) (nreverse front)
                                              eps)
                    (nreverse front))
                (if (aref node 3)
                    (geom::%bsp-clip-polygons (aref node 3) (nreverse back) eps)
                    '())))))

(defun geom::%bsp-clip-to (node other eps)
  (setf (aref node 1) (geom::%bsp-clip-polygons other (aref node 1) eps))
  (when (aref node 2) (geom::%bsp-clip-to (aref node 2) other eps))
  (when (aref node 3) (geom::%bsp-clip-to (aref node 3) other eps))
  node)

(defun geom::%bsp-all-polygons (node)
  (append (aref node 1)
          (if (aref node 2) (geom::%bsp-all-polygons (aref node 2)) '())
          (if (aref node 3) (geom::%bsp-all-polygons (aref node 3)) '())))

;; The three csg.js clip sequences, over the operands' world polygons.
(defun geom::%csg (op a b eps)
  (let ((an (geom::%bsp-build (geom::%bsp) (geom::%world-polygons a) eps))
        (bn (geom::%bsp-build (geom::%bsp) (geom::%world-polygons b) eps)))
    (cond ((eq op :union)
           (geom::%bsp-clip-to an bn eps)
           (geom::%bsp-clip-to bn an eps)
           (geom::%bsp-invert bn)
           (geom::%bsp-clip-to bn an eps)
           (geom::%bsp-invert bn)
           (geom::%bsp-build an (geom::%bsp-all-polygons bn) eps))
          ((eq op :difference)
           (geom::%bsp-invert an)
           (geom::%bsp-clip-to an bn eps)
           (geom::%bsp-clip-to bn an eps)
           (geom::%bsp-invert bn)
           (geom::%bsp-clip-to bn an eps)
           (geom::%bsp-invert bn)
           (geom::%bsp-build an (geom::%bsp-all-polygons bn) eps)
           (geom::%bsp-invert an))
          (t
           (geom::%bsp-invert an)
           (geom::%bsp-clip-to bn an eps)
           (geom::%bsp-invert bn)
           (geom::%bsp-clip-to an bn eps)
           (geom::%bsp-clip-to bn an eps)
           (geom::%bsp-build an (geom::%bsp-all-polygons bn) eps)
           (geom::%bsp-invert an)))
    (geom::%bsp-all-polygons an)))

(defun geom::%weld-key (v q)
  (list (round (/ (first v) q)) (round (/ (second v) q))
        (round (/ (third v) q))))

;; Result polygons -> a solid: vertices welded on an EPS grid (a shared edge
;; split from both sides lands on the same key even when the two interpolations
;; differ in the last bits), degenerate loops dropped, winding kept -- so the
;; result satisfies the same normals/winding invariant as a primitive and
;; volume stays the winding check. An empty polygon set (disjoint operands
;; intersected) answers an EMPTY solid: no vertices, no facets, volume 0.
(defun geom::%csg-solid (polys eps color label history)
  (let ((q (if (< eps 1e-30) 1e-30 eps))
        (weld (make-hash-table :test 'equal))
        (points '())
        (npts 0)
        (facets '()))
    (dolist (poly polys)
      (let ((idxs '()))
        (dolist (v (cdr poly))
          (let* ((key (geom::%weld-key v q)) (idx (gethash key weld)))
            (when (null idx)
              (setq idx npts)
              (setf (gethash key weld) idx)
              (push v points)
              (setq npts (+ npts 1)))
            (when (or (null idxs) (not (= idx (car idxs)))) (push idx idxs))))
        (let ((cleaned (nreverse idxs)))
          (when (and (>= (length cleaned) 2)
                     (= (car cleaned) (car (last cleaned))))
            (setq cleaned (reverse (cdr (reverse cleaned)))))
          (when (>= (length cleaned) 3) (push cleaned facets)))))
    (let ((sol
           (if (null points)
               (make-instance 'geom:solid
                :local (geom:make-transform)
                :vertices (linalg:zeros '(0 3) :element-type 'single-float)
                :facets '()
                :color (if color color (geom:vec3 0.72 0.76 0.84))
                :label label)
               (geom::%build-solid (nreverse points) (nreverse facets)
                                   :color color
                                   :label label))))
      (setf (geom::%history sol) history)
      sol)))

;; What built a solid: nil for a primitive, (op a b) -- e.g. (:union a b) --
;; for a boolean result, with the operand solids themselves.
(defun geom:history (s) (geom::%history s))

;; a united with b. Operands in world coordinates, untouched; the result is a
;; new root solid whose vertices are world coordinates.
(defun geom:union (a b &key color label)
  (let ((eps (geom::%operand-epsilon (list a b))))
    (geom::%csg-solid (geom::%csg :union a b eps) eps
                      (if color color (geom:color-of a)) label
                      (list :union a b))))

;; a with b removed.
(defun geom:difference (a b &key color label)
  (let ((eps (geom::%operand-epsilon (list a b))))
    (geom::%csg-solid (geom::%csg :difference a b eps) eps
                      (if color color (geom:color-of a)) label
                      (list :difference a b))))

;; The overlap of a and b; EMPTY (a solid with no facets) when they are
;; disjoint.
(defun geom:intersection (a b &key color label)
  (let ((eps (geom::%operand-epsilon (list a b))))
    (geom::%csg-solid (geom::%csg :intersection a b eps) eps
                      (if color color (geom:color-of a)) label
                      (list :intersection a b))))

;; --- planar section ----------------------------------------------------------
;;
;; The cross-section loops where a plane cuts a solid: each loop a rank-2
;; (n 3) packed float32 array of WORLD points, outer boundaries wound
;; counter-clockwise seen from the normal's positive side and holes clockwise.
;; The same classification problem as the booleans with one operand trivial,
;; under the same relative tolerance.

;; One facet against the plane: the segment where the facet's loop crosses (or
;; touches, edge-on) the plane, oriented along plane-normal x facet-normal so
;; the stitched loops wind consistently; nil for a miss, a single touch point,
;; or a facet lying in the plane (its boundary is covered by its neighbours).
(defun geom::%facet-section (poly n w eps)
  (let* ((verts (cdr poly))
         (m (length verts))
         (vv (coerce verts 'vector))
         (all-on t)
         (pts '()))
    (dolist (v verts)
      (when (> (abs (- (geom::%vdot n v) w)) eps) (setq all-on nil)))
    (if all-on
        nil
        (let ((q (if (< eps 1e-30) 1e-30 eps)))
          (dotimes (i m)
            (let* ((j (mod (+ i 1) m))
                   (vi (aref vv i))
                   (vj (aref vv j))
                   (di (- (geom::%vdot n vi) w))
                   (dj (- (geom::%vdot n vj) w)))
              (cond ((<= (abs di) eps) (push vi pts))
                    ((or (and (> di eps) (< dj (- eps)))
                         (and (< di (- eps)) (> dj eps)))
                     (push (geom::%vlerp vi vj (/ (- 0.0 di) (- dj di)))
                           pts)))))
          (let ((distinct '()) (keys (make-hash-table :test 'equal)))
            (dolist (p (nreverse pts))
              (let ((k (geom::%weld-key p q)))
                (when (null (gethash k keys))
                  (setf (gethash k keys) t)
                  (push p distinct))))
            (setq distinct (nreverse distinct))
            (if (not (= (length distinct) 2))
                nil
                (let* ((p1 (first distinct))
                       (p2 (second distinct))
                       (fp (car poly))
                       (fn (list (first fp) (second fp) (third fp)))
                       (d (geom::%vcross n fn)))
                  (if (< (geom::%vdot (geom::%v- p2 p1) d) 0)
                      (list p2 p1)
                      (list p1 p2)))))))))

(defun geom::%loop->array (pts)
  (let ((out
         (make-array (list (length pts) 3)
                     :element-type 'single-float
                     :initial-element 0.0))
        (i 0))
    (dolist (p pts out)
      (setf (aref out i 0) (float (first p) 1.0))
      (setf (aref out i 1) (float (second p) 1.0))
      (setf (aref out i 2) (float (third p) 1.0))
      (setq i (+ i 1)))))

;; Chains segments end to start (endpoints matched on the weld grid) into
;; closed loops; an unclosed chain -- a tangent touch -- is dropped rather
;; than answered as a broken loop.
(defun geom::%stitch-loops (segs eps)
  (let ((q (if (< eps 1e-30) 1e-30 eps))
        (start (make-hash-table :test 'equal))
        (loops '()))
    (dolist (seg segs)
      (setf (gethash (geom::%weld-key (first seg) q) start) seg))
    (dolist (seg segs (nreverse loops))
      (let ((k0 (geom::%weld-key (first seg) q)))
        (when (gethash k0 start)
          (let ((pts '())
                (cur seg)
                (steps 0)
                (limit (+ (length segs) 1))
                (closed nil))
            (do ((walking t))
                ((or (not walking) (null cur) (> steps limit)) nil)
              (setf (gethash (geom::%weld-key (first cur) q) start) nil)
              (push (first cur) pts)
              (let ((nk (geom::%weld-key (second cur) q)))
                (if (equal nk k0)
                    (progn
                      (setq closed t)
                      (setq walking nil))
                    (setq cur (gethash nk start))))
              (setq steps (+ steps 1)))
            (when (and closed (>= (length pts) 3))
              (push (geom::%loop->array (nreverse pts)) loops))))))))

;; The plane is :normal (an axis keyword or a vector) plus either :offset (the
;; signed distance from the origin along the normal) or :origin (a point on
;; the plane).
(defun geom:section (s &key (normal :z) (offset 0.0) origin)
  (let* ((n0 (geom::%as-point (geom:axis-vector normal)))
         (len (sqrt (geom::%vdot n0 n0)))
         (n (list (/ (first n0) len) (/ (second n0) len) (/ (third n0) len)))
         (w
          (if origin
              (geom::%vdot n (geom::%as-point origin))
              (float offset 1.0)))
         (eps (geom::%operand-epsilon (list s)))
         (segs '()))
    (dolist (poly (geom::%world-polygons s))
      (let ((seg (geom::%facet-section poly n w eps)))
        (when seg (push seg segs))))
    (geom::%stitch-loops (nreverse segs) eps)))
