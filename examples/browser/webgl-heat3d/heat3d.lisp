;;;; heat3d.lisp -- heat diffusion in a rank-3 voxel grid, simulated in Lisp
;;;; and rendered as a glowing WebGL point cloud.
;;;;
;;;; This is the browser companion of examples/ml/heat3d.lisp and the rank-3
;;;; array showcase: the whole simulation state is ONE rank-3 (n n n)
;;;; make-array. Every frame Lisp injects heat at two orbiting sources
;;;; ((setf (aref grid i j k)) with three subscripts), runs one explicit
;;;; diffusion step over the lattice, normalizes the colors with the
;;;; rank-generic (linalg:amax grid), reports (linalg:sum grid) to the HUD,
;;;; and projects every voxel to a screen-space point itself -- the page only
;;;; exposes the WebGL2 API one line at a time (see webgl-galaxy, whose host
;;;; boundary this reuses verbatim).
;;;;
;;;; Unlike the exact-rational console example, the voxels hold floats: the
;;;; simulation runs forever, and exact ratio denominators would grow without
;;;; bound (and overflow the WASM backend's i31 fixnums within a few steps).
;;;;
;;;; Compiled ahead of time to a --no-wasi reactor (build.sh), so the module
;;;; imports nothing but the host functions declared below and instantiates
;;;; in any wasm-GC-capable browser.

;; --- the host boundary ------------------------------------------------------
;;
;; The WebGL2 API itself -- the wasm-import directives, the enum constants and
;; the shader helpers -- lives in the shared gl package
;; (../webgl-common/gl.lisp), spliced in here at compile time; --optimize
;; drops the entries this demo never calls. Only the imports specific to this
;; page stay below. GL objects cross as :int handles into a table the page
;; keeps; strings (GLSL source, info logs) cross as :string.

(require :gl "../webgl-common/gl.lisp")

;; The vertex staging path (see webgl-galaxy): per-voxel floats cannot cross
;; into GPU memory one call at a time, so the page keeps one Float32Array
;; that set-vertex fills and gl-buffer-sub-data uploads.
(rontolisp:wasm-import 'set-vertex
                       :from "gl"
                       :as "setVertex"
                       :params '(:int :float :float :float :float)
                       :returns :void)
(rontolisp:wasm-import 'gl-buffer-sub-data
                       :from "gl"
                       :as "bufferSubData"
                       :params '(:int :int :int)
                       :returns :void)

;; Canvas metrics, owned by the page.
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
(rontolisp:wasm-import 'device-pixel-ratio
                       :from "canvas"
                       :as "devicePixelRatio"
                       :params '()
                       :returns :float)

;; The WASM backend has no transcendental built-ins, so borrow the host's.
(rontolisp:wasm-import 'sin :from "math" :params '(:float) :returns :float)
(rontolisp:wasm-import 'cos :from "math" :params '(:float) :returns :float)

;; --- shaders ----------------------------------------------------------------

(defconstant +vertex-shader-source+
  "#version 300 es
layout(location=0) in vec2 aPos;    // clip-space position from Lisp
layout(location=1) in float aHeat;  // 0..1 normalized heat from Lisp
layout(location=2) in float aSize;  // point size from Lisp
uniform float uDpr;
out float vHeat;
void main() {
  gl_Position = vec4(aPos, 0.0, 1.0);
  gl_PointSize = aSize * uDpr;
  vHeat = aHeat;
}")

(defconstant +fragment-shader-source+
  "#version 300 es
precision mediump float;
in float vHeat;
out vec4 color;
// heat 0 -> faint lattice blue, 0.5 -> ember orange, 1 -> white heat.
vec3 tint(float h) {
  vec3 cold = vec3(0.10, 0.17, 0.48);
  vec3 warm = vec3(1.00, 0.45, 0.12);
  vec3 hot  = vec3(1.00, 0.97, 0.88);
  return h < 0.5 ? mix(cold, warm, h * 2.0) : mix(warm, hot, h * 2.0 - 1.0);
}
void main() {
  // a soft round sprite; cold voxels keep a faint glow so the lattice shows
  float d = length(gl_PointCoord - 0.5) * 2.0;
  float a = exp(-3.0 * d * d) * (1.0 - smoothstep(0.8, 1.0, d));
  float glow = 0.08 + 0.92 * vHeat;
  color = vec4(tint(vHeat) * a * glow, a * glow);
}")

;; --- GL pipeline setup ------------------------------------------------------

(defvar *u-dpr* 0) ; uniform location handle for uDpr

(defun setup-gl ()
  (let ((program
         (gl:build-program +vertex-shader-source+ +fragment-shader-source+)))
    (gl:use-program program)
    (setq *u-dpr* (gl:get-uniform-location program "uDpr"))
    ;; additive blending: overlapping voxels glow, no depth sorting needed
    (gl:enable gl:+blend+)
    (gl:blend-func gl:+one+ gl:+one+)
    ;; one interleaved vertex buffer: x, y, heat, size = 16 bytes per voxel
    (gl:bind-vertex-array (gl:create-vertex-array))
    (gl:bind-buffer gl:+array-buffer+ (gl:create-buffer))
    (gl:enable-vertex-attrib-array 0)
    (gl:vertex-attrib-pointer 0 2 gl:+float+ nil 16 0)
    (gl:enable-vertex-attrib-array 1)
    (gl:vertex-attrib-pointer 1 1 gl:+float+ nil 16 8)
    (gl:enable-vertex-attrib-array 2)
    (gl:vertex-attrib-pointer 2 1 gl:+float+ nil 16 12)))

;; --- the simulation ---------------------------------------------------------
;;
;; The whole state is one rank-3 array (plus its double buffer). Element
;; (i j k) is the heat of the voxel at lattice position (i j k); the flat
;; row-major data order is also exactly the order the voxels are written
;; into the vertex buffer, so vertex v is the voxel at
;; (array-row-major-index grid i j k) = v.

(defvar *n* 0)      ; lattice side
(defvar *grid* nil) ; rank-3 (n n n) array of floats
(defvar *next* nil) ; the double buffer

(defconstant +alpha+ 0.16) ; diffusion rate (stable: alpha <= 1/6)
(defconstant +cool+ 0.988) ; per-step global cooling

(defun init (n)
  (setq *n* n)
  (setq *grid* (make-array (list n n n) :initial-element 0.0))
  (setq *next* (make-array (list n n n) :initial-element 0.0))
  ;; size the GPU buffer (and the page's staging array) for n^3 voxels
  (gl:buffer-data gl:+array-buffer+ (* n n n 16) gl:+dynamic-draw+))

(defun add-heat (fi fj fk amount)
  ;; Deposits heat at the voxel containing the (float) lattice point.
  (let ((i (floor fi)) (j (floor fj)) (k (floor fk)))
    (setf (aref *grid* i j k) (+ (aref *grid* i j k) amount))))

(defun inject (tm)
  ;; Two counter-rotating heat sources orbit inside the cube. Their orbit
  ;; radius keeps the (floored) indices strictly inside the lattice, so no
  ;; bounds clamping is needed.
  (let* ((mid (* 0.5 (- *n* 1))) (r (- mid 1.5)))
    (add-heat (+ mid (* r (cos (* tm 1.1)))) (+ mid (* 0.6 r (sin (* tm 0.7))))
              (+ mid (* r (sin (* tm 1.1)))) 900.0)
    (add-heat (+ mid (* 0.7 r (cos (* tm -0.6)))) (+ mid (* r (sin (* tm 0.5))))
              (+ mid (* 0.7 r (sin (* tm -0.6)))) 600.0)))

(defun diffuse ()
  ;; One explicit Euler step with insulated boundaries into the double
  ;; buffer, exactly as in examples/ml/heat3d.lisp -- three-subscript aref all
  ;; the way -- plus a mild global cooling so the sources and the walls reach
  ;; a moving equilibrium.
  (let ((n *n*) (g *grid*) (out *next*))
    (dotimes (i n)
      (dotimes (j n)
        (dotimes (k n)
          (let ((c (aref g i j k)) (acc 0.0))
            (when (> i 0) (setq acc (+ acc (- (aref g (- i 1) j k) c))))
            (when (< i (- n 1)) (setq acc (+ acc (- (aref g (+ i 1) j k) c))))
            (when (> j 0) (setq acc (+ acc (- (aref g i (- j 1) k) c))))
            (when (< j (- n 1)) (setq acc (+ acc (- (aref g i (+ j 1) k) c))))
            (when (> k 0) (setq acc (+ acc (- (aref g i j (- k 1)) c))))
            (when (< k (- n 1)) (setq acc (+ acc (- (aref g i j (+ k 1)) c))))
            (setf (aref out i j k) (* +cool+ (+ c (* +alpha+ acc))))))))
    (setq *grid* out)
    (setq *next* g)))

;; The HUD polls this: the rank-generic linalg reduction over the rank-3 grid.
(defun total-heat () (linalg:sum *grid*))

;; --- rendering --------------------------------------------------------------

(defun frame (tm)
  (inject tm)
  (diffuse)
  (let* ((w (canvas-width))
         (h (canvas-height))
         (aspect (/ w h))
         (n *n*)
         (mid (* 0.5 (- n 1)))
         (scale (/ 1.6 n)) ; lattice -> model units (cube ~[-0.8, 0.8]^3)
         ;; a slow spin around Y plus a gently wobbling tilt around X
         (ry (* tm 0.4))
         (cy (cos ry))
         (sy (sin ry))
         (rx (+ 0.45 (* 0.15 (sin (* tm 0.31)))))
         (cx (cos rx))
         (sx (sin rx))
         ;; colors are normalized by the hottest voxel right now (the
         ;; rank-generic linalg:amax over the whole rank-3 grid); the sqrt
         ;; below tone-maps the ratio so mid heats stay visible next to the
         ;; freshly injected source voxels
         (top (linalg:amax *grid*))
         (norm (if (> top 0.0) (/ 1.0 top) 0.0))
         (v 0))
    (gl:viewport 0 0 (floor w) (floor h))
    (gl:uniform1f *u-dpr* (device-pixel-ratio))
    (gl:clear-color 0.012 0.016 0.045 1.0)
    (gl:clear gl:+color-buffer-bit+)
    (dotimes (i n)
      (dotimes (j n)
        (dotimes (k n)
          (let* ((heat (sqrt (* norm (aref *grid* i j k))))
                 (x0 (* scale (- i mid)))
                 (y0 (* scale (- j mid)))
                 (z0 (* scale (- k mid)))
                 ;; rotate around Y ...
                 (x1 (+ (* x0 cy) (* z0 sy)))
                 (z1 (- (* z0 cy) (* x0 sy)))
                 ;; ... then tilt around X ...
                 (y2 (- (* y0 cx) (* z1 sx)))
                 (z2 (+ (* y0 sx) (* z1 cx)))
                 ;; ... and a simple perspective divide
                 (persp (/ 1.0 (+ 3.0 z2)))
                 (px (* x1 persp 2.1))
                 (py (* y2 persp 2.1))
                 (size (* persp (+ 4.0 (* 36.0 heat)))))
            (set-vertex v (/ px aspect) py heat size)
            (setq v (+ v 1))))))
    (gl-buffer-sub-data gl:+array-buffer+ 0 (* v 4))
    (gl:draw-arrays gl:+points+ 0 v)))

;; Build the pipeline at load time: this runs inside _initialize, after the
;; page has created the WebGL2 context and instantiated the module.
(setup-gl)

(rontolisp:wasm-export 'init :params '(:int) :returns :void)
(rontolisp:wasm-export 'frame :params '(:float) :returns :void)
(rontolisp:wasm-export 'total-heat :as "totalHeat" :params '() :returns :float)
