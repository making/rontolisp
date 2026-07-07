;;;; galaxy.lisp -- a spiral galaxy: the simulation AND the WebGL pipeline,
;;;; all driven from Lisp.
;;;;
;;;; This is the rontolisp:wasm-import showcase. The page (index.html) does not
;;;; know how to render a galaxy: it only exposes the WebGL2 API one function at
;;;; a time (a handle table resolves :int handles to GL objects, every binding
;;;; is one line of JavaScript). Lisp compiles the shaders -- the GLSL source
;;;; lives in this file as string constants -- links the program, sets up the
;;;; vertex buffer and blending, and issues every draw call. JavaScript keeps
;;;; only the UI (canvas sizing, the star-count select, the HUD meters) and the
;;;; final vertex upload.
;;;;
;;;; Compiled ahead of time to a --no-wasi reactor (build.sh), so the module
;;;; imports nothing but the host functions declared below and instantiates in
;;;; any wasm-GC-capable browser.
;;;;
;;;; The galaxy is the classic density-wave toy model: every star follows a
;;;; fixed ellipse, and the ellipses' orientations twist with radius, so the
;;;; crowded parts of neighbouring orbits line up into spiral arms. Nothing is
;;;; random: star i is scattered with the golden angle, so the disc looks
;;;; even without an entropy source (a --no-wasi reactor has none).

;; --- the host boundary ------------------------------------------------------
;;
;; The WebGL2 API itself -- the wasm-import directives, the enum constants and
;; the shader helpers -- lives in the shared gl package
;; (../webgl-common/gl.lisp), spliced in here at compile time; --optimize
;; drops the entries this demo never calls. Only the imports specific to this
;; page stay below. GL objects (shaders, programs, buffers, uniform locations)
;; cross the boundary as :int handles into a table the page keeps; strings
;; (GLSL source, uniform names, info logs) cross as :string.

(require :gl "../webgl-common/gl.lisp")

;; The vertex staging path: floats cannot be written into GPU memory across the
;; boundary one call at a time, so the page keeps one Float32Array. set-vertex
;; writes one star's (x y hue size) record into it; gl-buffer-sub-data uploads
;; the first COUNT floats of it to the bound buffer. These two are the only
;; imports that are not literal WebGL2 API entries.
(rontolisp:wasm-import 'set-vertex :from "gl" :as "setVertex"
                       :params '(:int :float :float :float :float) :returns :void)
(rontolisp:wasm-import 'gl-buffer-sub-data :from "gl" :as "bufferSubData"
                       :params '(:int :int :int) :returns :void)

;; Canvas metrics, owned by the page (it resizes the backing store on window
;; resize; Lisp reads the result every frame and sets the viewport itself).
(rontolisp:wasm-import 'canvas-width :from "canvas" :as "width"
                       :params '() :returns :float)
(rontolisp:wasm-import 'canvas-height :from "canvas" :as "height"
                       :params '() :returns :float)
(rontolisp:wasm-import 'device-pixel-ratio :from "canvas" :as "devicePixelRatio"
                       :params '() :returns :float)

;; The WASM backend has no transcendental built-ins, so borrow the host's:
;; these two lines are literally Math.sin / Math.cos on the JavaScript side.
(rontolisp:wasm-import 'sin :from "math" :params '(:float) :returns :float)
(rontolisp:wasm-import 'cos :from "math" :params '(:float) :returns :float)

;; --- shaders ----------------------------------------------------------------
;; The GLSL lives here, in Lisp, and reaches the GPU through the imported
;; gl:shader-source (a :string parameter crossing the boundary as (ptr,len)
;; into this module's linear memory).

(defconstant +vertex-shader-source+ "#version 300 es
layout(location=0) in vec2 aPos;    // clip-space position from Lisp
layout(location=1) in float aHue;   // 0..1 hue from Lisp
layout(location=2) in float aSize;  // point size from Lisp
uniform float uDpr;
out float vHue;
void main() {
  gl_Position = vec4(aPos, 0.0, 1.0);
  gl_PointSize = aSize * uDpr;
  vHue = aHue;
}")

(defconstant +fragment-shader-source+ "#version 300 es
precision mediump float;
in float vHue;
out vec4 color;
// hue = 0 at the core, 1 at the rim: warm white -> ice blue -> violet,
// the classic stellar-population gradient.
vec3 tint(float h) {
  vec3 core = vec3(1.00, 0.93, 0.78);
  vec3 mid  = vec3(0.62, 0.78, 1.00);
  vec3 rim  = vec3(0.66, 0.47, 1.00);
  return h < 0.5 ? mix(core, mid, h * 2.0) : mix(mid, rim, h * 2.0 - 1.0);
}
void main() {
  // a soft round sprite: bright center, glow falloff, additive blending
  float d = length(gl_PointCoord - 0.5) * 2.0;
  float a = exp(-3.2 * d * d) * (1.0 - smoothstep(0.8, 1.0, d));
  color = vec4(tint(vHue) * a, a);
}")

;; --- GL pipeline setup ------------------------------------------------------

(defvar *u-dpr* 0)                      ; uniform location handle for uDpr

(defun setup-gl ()
  (let ((program (gl:build-program +vertex-shader-source+ +fragment-shader-source+)))
    (gl:use-program program)
    (setq *u-dpr* (gl:get-uniform-location program "uDpr"))
    ;; additive blending: overlapping stars glow
    (gl:enable gl:+blend+)
    (gl:blend-func gl:+one+ gl:+one+)
    ;; one interleaved vertex buffer: x, y, hue, size = 16 bytes per star
    (gl:bind-vertex-array (gl:create-vertex-array))
    (gl:bind-buffer gl:+array-buffer+ (gl:create-buffer))
    (gl:enable-vertex-attrib-array 0)
    (gl:vertex-attrib-pointer 0 2 gl:+float+ nil 16 0)
    (gl:enable-vertex-attrib-array 1)
    (gl:vertex-attrib-pointer 1 1 gl:+float+ nil 16 8)
    (gl:enable-vertex-attrib-array 2)
    (gl:vertex-attrib-pointer 2 1 gl:+float+ nil 16 12)))

;; --- the galaxy -------------------------------------------------------------

;; Per-star orbit parameters, filled by `init`.
(defvar *n* 0)
(defvar *radius* nil)                   ; semi-major axis of the orbit
(defvar *phase* nil)                    ; where on the orbit the star starts
(defvar *speed* nil)                    ; angular speed (inner orbits run faster)
(defvar *tilt* nil)                     ; orientation of the ellipse

;; The golden angle scatters the stars' orbit phases evenly; sqrt(2)-1
;; scatters their radii. (The radius sequence must not be built on the golden
;; ratio: the golden angle is 2*pi*(1-phi), so frac(i*phi) would be an exact
;; function of the phase and every star would land on one curve.) The two
;; low-discrepancy sequences are independent, so the disc fills smoothly
;; without an entropy source.
(defconstant +golden-angle+ 2.399963229728653)

(defconstant +radius-step+ 0.414213562373095)

;; How strongly the ellipse orientation twists with radius; this is what
;; winds the orbits into spiral arms.
(defconstant +twist+ 5.4)

;; The fractional part of X (X non-negative).
(defun frac (x)
  (- x (floor x)))

(defun init (n)
  (setq *n* n)
  (setq *radius* (make-array n))
  (setq *phase* (make-array n))
  (setq *speed* (make-array n))
  (setq *tilt* (make-array n))
  ;; size the GPU buffer (and the page's staging array) for n stars
  (gl:buffer-data gl:+array-buffer+ (* n 16) gl:+dynamic-draw+)
  (dotimes (i n)
    (let* ((u (frac (* (+ i 1) +radius-step+)))
           ;; sqrt biases the stars toward the bright core
           (r (+ 0.03 (* 0.95 (sqrt u))))
           ;; a third sequence breaks up the residual lattice at the rim
           (jitter (* 0.5 (frac (* (+ i 1) 0.754877666)))))
      (setf (aref *radius* i) r)
      (setf (aref *phase* i) (+ (* i +golden-angle+) jitter))
      (setf (aref *speed* i) (/ 0.5 (+ 0.15 r)))
      (setf (aref *tilt* i) (* r +twist+)))))

(defun frame (tm)
  (let* ((w (canvas-width))
         (h (canvas-height))
         (aspect (/ w h)))
    (gl:viewport 0 0 (floor w) (floor h))
    (gl:uniform1f *u-dpr* (device-pixel-ratio))
    (gl:clear-color 0.012 0.016 0.045 1.0)
    (gl:clear gl:+color-buffer-bit+)
    (dotimes (i *n*)
      (let* ((r (aref *radius* i))
             (theta (+ (aref *phase* i) (* tm (aref *speed* i))))
             ;; the star's position on its (axis-aligned) ellipse
             (ex (* r (cos theta)))
             (ey (* r 0.55 (sin theta)))
             ;; rotate the ellipse: per-radius twist plus a slow global spin
             (rot (+ (aref *tilt* i) (* tm 0.04)))
             (c (cos rot))
             (s (sin rot))
             (x (- (* ex c) (* ey s)))
             (y (+ (* ex s) (* ey c)))
             ;; the fragment shader maps 0 -> warm core white, 1 -> violet rim
             (hue (+ r (* 0.1 (frac (* i 0.754877666)))))
             (size (+ 1.8 (* 3.4 (- 1.0 r) (- 1.0 r)))))
        (set-vertex i (/ x aspect) y hue size)))
    (gl-buffer-sub-data gl:+array-buffer+ 0 (* *n* 4))
    (gl:draw-arrays gl:+points+ 0 *n*)))

;; Build the pipeline at load time: this runs inside _initialize, after the
;; page has created the WebGL2 context and instantiated the module.
(setup-gl)

(rontolisp:wasm-export 'init :params '(:int) :returns :void)
(rontolisp:wasm-export 'frame :params '(:float) :returns :void)
