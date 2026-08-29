;;;; solids.lisp -- geom's solid modeller in the browser: the macOS viewer's twin.
;;;;
;;;; geom (.kb/geom.md) is backend-independent -- transforms, a scene graph,
;;;; boundary-represented solids, the CSG booleans -- and runs identically on
;;;; the interpreter, the JVM and both WASM backends. Its shipped RENDERER,
;;;; scene.lisp, is macOS only, because it bottoms out in Metal through objc:.
;;;; This program is the other renderer: the same geom model, the same design,
;;;; drawn with WebGL2.
;;;;
;;;; There is deliberately NO modeling code here. Every solid comes from geom's
;;;; own constructors and booleans, every triangle from geom:mesh and every pose
;;;; from geom:world-transform. A second modeling layer in the browser would
;;;; drift from the one in the package, and geom would grow a browser dialect.
;;;;
;;;; The design point is the one scene.lisp measures (.kb/geom.md): NO TRIANGLE
;;;; IS TOUCHED PER FRAME. A solid's model-space mesh goes into a vertex buffer
;;;; of its own the first time it is drawn -- cached in geom:user-data, the slot
;;;; the package provides for exactly this -- and a frame sets one 4x4 model
;;;; matrix and one colour per solid and issues one draw call. Re-transforming
;;;; the vertices every frame instead costs 380 ms against 9.0 on a 60-solid
;;;; model. The vertex shader therefore takes uVP and uModel as SEPARATE
;;;; uniforms and transforms the normal by uModel too, so a solid that moves
;;;; needs no re-upload.
;;;;
;;;; Compiled ahead of time to a --no-wasi reactor (build.sh). The page
;;;; instantiates the module and calls _initialize() -- which builds the model
;;;; and uploads it -- then calls the exported `frame` once per animation tick,
;;;; and `orbit` / `zoom` as the pointer drags and scrolls.

;; --- the host boundary ------------------------------------------------------
;; The WebGL2 API itself lives in the shared gl package
;; (../webgl-common/gl.lisp), spliced in at compile time; --optimize drops the
;; entries this demo never calls.

(require :gl "../webgl-common/gl.lisp")

;; Bulk floats cannot cross the boundary one WASM value at a time, so the page
;; keeps one staging Float32Array. set-float writes one slot;
;; gl-buffer-data-floats uploads the first COUNT of them as buffer data;
;; gl-uniform-matrix4fv hands the first 16 to a mat4 uniform. A mesh crosses
;; once, at startup; a matrix crosses 16 floats at a time, per solid per frame.
(rontolisp:wasm-import 'set-float
                       :from "gl"
                       :as "setFloat"
                       :params '(:int :float)
                       :returns :void)

(rontolisp:wasm-import 'gl-buffer-data-floats
                       :from "gl"
                       :as "bufferDataFloats"
                       :params '(:int :int :int)
                       :returns :void)

(rontolisp:wasm-import 'gl-uniform-matrix4fv
                       :from "gl"
                       :as "uniformMatrix4fv"
                       :params '(:int)
                       :returns :void)

(rontolisp:wasm-import 'canvas-width
                       :from "canvas"
                       :as "width"
                       :params '()
                       :returns :float)

(rontolisp:wasm-import 'canvas-height
                       :from "canvas"
                       :as "height"
                       :params '()
                       :returns :float)

;; The two WebGL2 constants outside gl.wit's set. gl:enable takes the cap as a
;; plain integer, so culling needs no new binding -- and GL's default winding
;; (counter-clockwise front) is already geom's, so nothing has to be said about
;; it. That agreement is the same one scene.lisp states explicitly for Metal.
(defconstant +cull-face+ 2884)

(defconstant +lines+ 1)

;; --- the model ---------------------------------------------------------------
;;
;; Plain geom. Nothing below this line knows it is going to be drawn, and
;; nothing above it knows what a vertex buffer is.

(defvar *solids* nil)

(defvar *spinner* nil)

;; One bored hole: a cylinder long enough to pass right through the block, laid
;; along y and slid to x. geom:cylinder STANDS on z = 0, so the local move --
;; which applies the node's own rotation -- is what centres it on the block.
(defun bore (x)
  (let ((c (geom:cylinder :radius 50.0 :height 400.0 :sides 24)))
    (geom:turn c 1.5707963267948966 :x)
    (geom:move c (geom:vec3 0.0 0.0 -200.0))
    (geom:move c (geom:vec3 x 0.0 0.0) :frame :parent)
    c))

(defun build-model ()
  (let* ((plate
          (geom:box '(900.0 900.0 40.0)
                    :color (geom:vec3 0.30 0.33 0.42)
                    :label "plate"))
         ;; CSG: a block with three holes bored through it, the shape the
         ;; primitives alone cannot express (geom:difference, .kb/geom.md).
         (block-solid (geom:box '(360.0 200.0 200.0)))
         (drilled
          (geom:difference (geom:difference
                            (geom:difference block-solid (bore -110.0))
                            (bore 0.0)) (bore 110.0)
                           :color (geom:vec3 0.92 0.42 0.30)
                           :label "drilled"))
         (ball
          (geom:sphere :radius 95.0
                       :sides 24
                       :stacks 16
                       :color (geom:vec3 0.45 0.75 1.0)
                       :label "ball"))
         (ring
          (geom:torus :radius 85.0
                      :tube 24.0
                      :sides 32
                      :rings 16
                      :color (geom:vec3 0.55 0.90 0.55)
                      :label "ring"))
         (spire
          (geom:cone :radius 80.0
                     :height 240.0
                     :sides 32
                     :color (geom:vec3 0.95 0.80 0.35)
                     :label "spire")))
    (geom:move plate (geom:vec3 0.0 0.0 -20.0))
    (geom:move drilled (geom:vec3 -170.0 -170.0 100.0))
    (geom:move ball (geom:vec3 210.0 -190.0 95.0))
    (geom:move spire (geom:vec3 -210.0 210.0 0.0))
    ;; The ring hangs off a joint of its own, and the joint is what the frame
    ;; hook turns: geom:world-transform composes down the graph and the ring's
    ;; triangles never move.
    (setq *spinner* (geom:make-node :translation (geom:vec3 270.0 270.0 190.0)))
    (geom:attach *spinner* ring)
    ;; The origin indicator is an OBJECT here too: three geom:arrow solids with
    ;; a shaft thickness and three tips, placed where this program says. The
    ;; renderer below knows nothing about arrows -- it consumes geom:mesh, so a
    ;; new primitive in the modeller reaches the browser for free.
    (setq *solids*
          (append (list plate drilled ball ring spire)
                  (geom:triad :length 260.0
                              :radius 10.0
                              :head-radius 26.0
                              :head-length 60.0
                              :at (geom:vec3 0.0 0.0 0.0))))))

;; --- matrices ----------------------------------------------------------------
;;
;; linalg, exactly as scene.lisp does it. The ONE difference from the Metal
;; renderer is the projection: OpenGL's clip space puts z in [-1, 1] where
;; Metal's puts it in [0, 1], so the third row differs and nothing else does.

(defconstant +fov+ 0.7853981633974483)

(defvar *azimuth* 0.9)

(defvar *elevation* 0.42)

(defvar *distance* 1500.0)

(defvar *target* nil)

(defvar *eye* nil)

(defvar *view-projection* nil)

(defun unit (v)
  (let ((n (linalg:norm v))) (linalg:mul v (/ 1.0 (if (< n 1e-9) 1e-9 n)))))

(defun perspective (aspect near far)
  (let ((m (linalg:zeros '(4 4) :element-type 'single-float))
        (f (/ 1.0 (tan (/ +fov+ 2.0)))))
    (setf (aref m 0 0) (/ f aspect))
    (setf (aref m 1 1) f)
    (setf (aref m 2 2) (/ (+ far near) (- near far)))
    (setf (aref m 2 3) (/ (* 2.0 far near) (- near far)))
    (setf (aref m 3 2) -1.0)
    m))

(defun update-camera ()
  (let* ((ce (cos *elevation*))
         (se (sin *elevation*))
         (d *distance*)
         (eye
          (linalg:add *target*
                      (geom:vec3 (* d ce (cos *azimuth*))
                                 (* d ce (sin *azimuth*)) (* d se))))
         (forward (unit (linalg:sub *target* eye)))
         (right (unit (linalg:cross forward (geom:vec3 0.0 0.0 1.0))))
         (up (linalg:cross right forward))
         (r (linalg:stack (list right up (linalg:mul forward -1.0))))
         (view
          (linalg:concatenate (list (linalg:concatenate (list r
                                                              (linalg:reshape
                                                               (linalg:mul
                                                                (linalg:matmul r
                                                                 eye) -1.0)
                                                               '(3 1)))
                                                        :axis 1)
                                    (linalg:from-list '((0.0 0.0 0.0 1.0))
                                     :element-type 'single-float))
                              :axis 0))
         (far (* 8.0 (max d 100.0))))
    (setq *eye* eye)
    (setq *view-projection*
          (linalg:matmul
           (perspective (/ (canvas-width) (canvas-height)) (* 0.002 far) far)
           view))))

;; A node's world transform as a 4x4, in the same row/column order linalg's own
;; products use. This, once per solid, IS the per-frame CPU cost of drawing it.
(defun model-matrix (node)
  (let ((m (linalg:zeros '(4 4) :element-type 'single-float))
        (tf (geom:world-transform node)))
    (dotimes (i 3)
      (dotimes (j 3) (setf (aref m i j) (aref (geom:rotation-of tf) i j)))
      (setf (aref m i 3) (aref (geom:translation-of tf) i)))
    (setf (aref m 3 3) 1.0)
    m))

;; WebGL wants column-major: element (row, col) lands at row + col*4.
(defun upload-matrix (loc m)
  (dotimes (c 4) (dotimes (r 4) (set-float (+ r (* c 4)) (aref m r c))))
  (gl-uniform-matrix4fv loc))

;; --- the pipelines -----------------------------------------------------------

(defconstant +solid-vertex-shader+
  "#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNormal;
uniform mat4 uVP;
uniform mat4 uModel;
out vec3 vNormal;
out vec3 vWorld;
void main() {
  vec4 w = uModel * vec4(aPos, 1.0);
  gl_Position = uVP * w;
  vNormal = (uModel * vec4(aNormal, 0.0)).xyz;
  vWorld = w.xyz;
}")

(defconstant +solid-fragment-shader+
  "#version 300 es
precision highp float;
in vec3 vNormal;
in vec3 vWorld;
uniform vec3 uEye;
uniform vec3 uTint;
out vec4 color;
void main() {
  vec3 n = normalize(vNormal);
  vec3 l = normalize(vec3(0.45, 0.80, 0.40));
  vec3 e = normalize(uEye - vWorld);
  vec3 h = normalize(l + e);
  float diff = max(dot(n, l), 0.0);
  float amb  = 0.32 + 0.14 * n.z;
  float spec = pow(max(dot(n, h), 0.0), 42.0) * 0.35;
  float rim  = pow(1.0 - max(dot(n, e), 0.0), 3.0) * 0.16;
  color = vec4(uTint * (amb + 0.70 * diff) + vec3(spec)
               + vec3(0.35, 0.55, 0.9) * rim, 1.0);
}")

(defconstant +line-vertex-shader+
  "#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uVP;
void main() { gl_Position = uVP * vec4(aPos, 1.0); }")

(defconstant +line-fragment-shader+
  "#version 300 es
precision highp float;
uniform vec3 uTint;
out vec4 color;
void main() { color = vec4(uTint, 1.0); }")

(defvar *solid-program* nil)

(defvar *solid-vp* nil)

(defvar *solid-model* nil)

(defvar *solid-eye* nil)

(defvar *solid-tint* nil)

(defvar *line-program* nil)

(defvar *line-vp* nil)

(defvar *line-tint* nil)

(defvar *grid-array* nil)

(defvar *grid-points* 0)

;; --- geometry on the GPU -----------------------------------------------------
;;
;; Uploaded once per solid, and only when it is first drawn. The entry lives in
;; geom:user-data rather than in a table keyed by the solid, because a hash
;; table cannot key on a node at all (.kb/geom.md) -- the same reason, and the
;; same slot, as the Metal renderer's.

(defun stage-floats (data count)
  (dotimes (i count) (set-float i (aref data i))))

(defun gpu-buffers (s)
  (when (null (geom:user-data s))
    (let* ((mesh (geom:mesh s))
           (floats (length mesh))
           (array (gl:create-vertex-array)))
      (gl:bind-vertex-array array)
      (gl:bind-buffer gl:+array-buffer+ (gl:create-buffer))
      (stage-floats mesh floats)
      (gl-buffer-data-floats gl:+array-buffer+ floats gl:+static-draw+)
      (gl:enable-vertex-attrib-array 0)
      (gl:vertex-attrib-pointer 0 3 gl:+float+ nil 24 0)
      (gl:enable-vertex-attrib-array 1)
      (gl:vertex-attrib-pointer 1 3 gl:+float+ nil 24 12)
      (setf (geom:user-data s) (list array (floor floats 6)))))
  (geom:user-data s))

;; The ground plane, one line pair per division: fixed geometry, uploaded once.
(defun build-grid (extent spacing)
  (let* ((n (floor extent spacing))
         (lines (* 2 (+ (* 2 n) 1)))
         (pts
          (make-array (* lines 6)
                      :element-type 'single-float
                      :initial-element 0.0))
         (k 0))
    (do ((i (- n) (+ i 1)))
        ((> i n) nil)
      (let ((x (* i spacing)))
        (setf (aref pts k) x)
        (setf (aref pts (+ k 1)) (- extent))
        (setf (aref pts (+ k 3)) x)
        (setf (aref pts (+ k 4)) extent)
        (setq k (+ k 6))
        (setf (aref pts k) (- extent))
        (setf (aref pts (+ k 1)) x)
        (setf (aref pts (+ k 3)) extent)
        (setf (aref pts (+ k 4)) x)
        (setq k (+ k 6))))
    (setq *grid-array* (gl:create-vertex-array))
    (gl:bind-vertex-array *grid-array*)
    (gl:bind-buffer gl:+array-buffer+ (gl:create-buffer))
    (stage-floats pts (length pts))
    (gl-buffer-data-floats gl:+array-buffer+ (length pts) gl:+static-draw+)
    (gl:enable-vertex-attrib-array 0)
    (gl:vertex-attrib-pointer 0 3 gl:+float+ nil 12 0)
    (setq *grid-points* (* lines 2))))

;; --- setup and the frame -----------------------------------------------------

(defun setup-gl ()
  (build-model)
  ;; scene:fit's arithmetic: frame the whole model from the bounds of what is
  ;; in it, so nothing here is a hand-tuned camera position.
  (let ((b (geom:bounds *solids*)))
    (setq *target* (geom:bounds-center b))
    (setq *distance* (* 1.25 (linalg:norm (geom:bounds-extent b)))))
  (setq *solid-program*
        (gl:build-program +solid-vertex-shader+ +solid-fragment-shader+))
  (setq *solid-vp* (gl:get-uniform-location *solid-program* "uVP"))
  (setq *solid-model* (gl:get-uniform-location *solid-program* "uModel"))
  (setq *solid-eye* (gl:get-uniform-location *solid-program* "uEye"))
  (setq *solid-tint* (gl:get-uniform-location *solid-program* "uTint"))
  (setq *line-program*
        (gl:build-program +line-vertex-shader+ +line-fragment-shader+))
  (setq *line-vp* (gl:get-uniform-location *line-program* "uVP"))
  (setq *line-tint* (gl:get-uniform-location *line-program* "uTint"))
  (gl:enable gl:+depth-test+)
  ;; geom winds every facet counter-clockwise seen from outside, which is
  ;; exactly what GL calls front-facing by default: half the triangles of a
  ;; closed solid never reach the rasterizer.
  (gl:enable +cull-face+)
  (build-grid 900.0 75.0)
  (gl:use-program *solid-program*)
  (dolist (s *solids*) (gpu-buffers s)))

(defun frame (tm)
  (geom:place *spinner* :axis :x :angle (* 0.7 tm))
  (update-camera)
  (gl:viewport 0 0 (floor (canvas-width)) (floor (canvas-height)))
  (gl:clear-color 0.055 0.065 0.09 1.0)
  (gl:clear (+ gl:+color-buffer-bit+ gl:+depth-buffer-bit+))
  (gl:use-program *line-program*)
  (upload-matrix *line-vp* *view-projection*)
  (gl:uniform3f *line-tint* 0.20 0.23 0.30)
  (gl:bind-vertex-array *grid-array*)
  (gl:draw-arrays +lines+ 0 *grid-points*)
  (gl:use-program *solid-program*)
  (upload-matrix *solid-vp* *view-projection*)
  (gl:uniform3f *solid-eye* (aref *eye* 0) (aref *eye* 1) (aref *eye* 2))
  (dolist (s *solids*)
    (let ((bufs (gpu-buffers s)) (tint (geom:color-of s)))
      (upload-matrix *solid-model* (model-matrix s))
      (gl:uniform3f *solid-tint* (aref tint 0) (aref tint 1) (aref tint 2))
      (gl:bind-vertex-array (first bufs))
      (gl:draw-arrays gl:+triangles+ 0 (second bufs)))))

;; --- the camera the page drives ----------------------------------------------

(defun orbit (dx dy)
  (setq *azimuth* (- *azimuth* (* 3.4 dx)))
  (let ((e (+ *elevation* (* 2.6 dy))))
    (setq *elevation* (cond ((< e -1.5) -1.5) ((> e 1.5) 1.5) (t e)))))

(defun zoom (dy)
  (let ((d (* *distance* (+ 1.0 dy))))
    (setq *distance* (cond ((< d 200.0) 200.0) ((> d 20000.0) 20000.0) (t d)))))

;; How many triangles the page's meter shows: geom's count, not a renderer's.
(defun triangle-count ()
  (let ((total 0))
    (dolist (s *solids* total)
      (setq total (+ total (geom:mesh-triangle-count s))))))

;; And how many solids, likewise counted rather than written into the page: the
;; model decides what is in it.
(defun solid-count () (length *solids*))

(setup-gl)

(rontolisp:wasm-export 'frame :params '(:float) :returns :void)

(rontolisp:wasm-export 'orbit :params '(:float :float) :returns :void)

(rontolisp:wasm-export 'zoom :params '(:float) :returns :void)

(rontolisp:wasm-export 'triangle-count
                       :as "triangleCount"
                       :params '()
                       :returns :int)

(rontolisp:wasm-export 'solid-count :as "solidCount" :params '() :returns :int)
