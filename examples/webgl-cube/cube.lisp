;;;; cube.lisp -- hello 3D: a rotating cube, matrices and all, driven from Lisp.
;;;;
;;;; The middle step between ../webgl-triangle/ (hello world) and
;;;; ../webgl-galaxy/ (a full pipeline). This one adds the parts every real 3D
;;;; program needs -- a vertex buffer, a depth test, and 4x4 matrix math -- and
;;;; keeps them all in Lisp: the perspective projection, the rotation matrices
;;;; and their products are computed here every frame and cross the boundary as
;;;; 16 floats into a small staging array the page keeps.
;;;;
;;;; Compiled ahead of time to a --no-wasi reactor (build.sh). The page
;;;; instantiates the module and calls _initialize() -- which uploads the cube
;;;; geometry -- then calls the exported `frame` once per animation tick.

;; --- the host boundary ------------------------------------------------------
;; :as maps the Lisp name to the JavaScript property; :from names the
;; import-object key. GL objects cross the boundary as :int handles into a
;; table the page keeps; the GLSL source crosses as :string.

(rontolisp:wasm-import 'gl-create-shader :from "gl" :as "createShader"
                       :params '(:int) :returns :int)
(rontolisp:wasm-import 'gl-shader-source :from "gl" :as "shaderSource"
                       :params '(:int :string) :returns :void)
(rontolisp:wasm-import 'gl-compile-shader :from "gl" :as "compileShader"
                       :params '(:int) :returns :void)
(rontolisp:wasm-import 'gl-create-program :from "gl" :as "createProgram"
                       :params '() :returns :int)
(rontolisp:wasm-import 'gl-attach-shader :from "gl" :as "attachShader"
                       :params '(:int :int) :returns :void)
(rontolisp:wasm-import 'gl-link-program :from "gl" :as "linkProgram"
                       :params '(:int) :returns :void)
(rontolisp:wasm-import 'gl-use-program :from "gl" :as "useProgram"
                       :params '(:int) :returns :void)
(rontolisp:wasm-import 'gl-get-uniform-location :from "gl" :as "getUniformLocation"
                       :params '(:int :string) :returns :int)
(rontolisp:wasm-import 'gl-enable :from "gl" :as "enable"
                       :params '(:int) :returns :void)
(rontolisp:wasm-import 'gl-create-buffer :from "gl" :as "createBuffer"
                       :params '() :returns :int)
(rontolisp:wasm-import 'gl-bind-buffer :from "gl" :as "bindBuffer"
                       :params '(:int :int) :returns :void)
(rontolisp:wasm-import 'gl-enable-vertex-attrib-array :from "gl" :as "enableVertexAttribArray"
                       :params '(:int) :returns :void)
(rontolisp:wasm-import 'gl-vertex-attrib-pointer :from "gl" :as "vertexAttribPointer"
                       :params '(:int :int :int :bool :int :int) :returns :void)
(rontolisp:wasm-import 'gl-clear-color :from "gl" :as "clearColor"
                       :params '(:float :float :float :float) :returns :void)
(rontolisp:wasm-import 'gl-clear :from "gl" :as "clear"
                       :params '(:int) :returns :void)
(rontolisp:wasm-import 'gl-draw-arrays :from "gl" :as "drawArrays"
                       :params '(:int :int :int) :returns :void)

;; Bulk floats (vertex data, matrices) cannot cross the boundary one WASM value
;; at a time, so the page keeps one small Float32Array. set-float writes one
;; slot; gl-buffer-data-floats uploads the first COUNT floats as buffer data;
;; gl-uniform-matrix4fv hands the first 16 to a mat4 uniform. These three are
;; the only imports that are not literal WebGL2 API entries.
(rontolisp:wasm-import 'set-float :from "gl" :as "setFloat"
                       :params '(:int :float) :returns :void)
(rontolisp:wasm-import 'gl-buffer-data-floats :from "gl" :as "bufferDataFloats"
                       :params '(:int :int :int) :returns :void)
(rontolisp:wasm-import 'gl-uniform-matrix4fv :from "gl" :as "uniformMatrix4fv"
                       :params '(:int) :returns :void)

;; Canvas metrics (the backing store is fixed, read once for the aspect ratio).
(rontolisp:wasm-import 'canvas-width :from "canvas" :as "width"
                       :params '() :returns :float)
(rontolisp:wasm-import 'canvas-height :from "canvas" :as "height"
                       :params '() :returns :float)

;; The WASM backend has no transcendental built-ins, so borrow the host's:
;; these two lines are literally Math.sin / Math.cos on the JavaScript side.
(rontolisp:wasm-import 'sin :from "math" :params '(:float) :returns :float)
(rontolisp:wasm-import 'cos :from "math" :params '(:float) :returns :float)

;; --- WebGL constants --------------------------------------------------------
;; The numeric enum values from the WebGL specification.

(defconstant +gl-vertex-shader+ 35633)          ; 0x8B31
(defconstant +gl-fragment-shader+ 35632)        ; 0x8B30
(defconstant +gl-array-buffer+ 34962)           ; 0x8892
(defconstant +gl-static-draw+ 35044)            ; 0x88E4
(defconstant +gl-float+ 5126)                   ; 0x1406
(defconstant +gl-depth-test+ 2929)              ; 0x0B71
(defconstant +gl-color-buffer-bit+ 16384)       ; 0x4000
(defconstant +gl-depth-buffer-bit+ 256)         ; 0x0100
(defconstant +gl-triangles+ 4)

;; --- shaders ----------------------------------------------------------------

(defconstant +vertex-shader-source+ "#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aColor;
uniform mat4 uMvp;   // model-view-projection, computed in Lisp every frame
out vec3 vColor;
void main() {
  gl_Position = uMvp * vec4(aPos, 1.0);
  vColor = aColor;
}")

(defconstant +fragment-shader-source+ "#version 300 es
precision mediump float;
in vec3 vColor;
out vec4 color;
void main() {
  color = vec4(vColor, 1.0);
}")

;; --- 4x4 matrix math --------------------------------------------------------
;; Column-major (the OpenGL convention): element (row, col) lives at
;; index (+ row (* col 4)).

(defconstant +pi+ 3.141592653589793)

(defun mat4-zero ()
  (make-array 16))

(defun mat4-mul (a b)
  (let ((out (mat4-zero)))
    (dotimes (col 4)
      (dotimes (row 4)
        (let ((sum 0.0))
          (dotimes (k 4)
            (setq sum (+ sum (* (aref a (+ row (* k 4)))
                                (aref b (+ k (* col 4)))))))
          (setf (aref out (+ row (* col 4))) sum))))
    out))

(defun mat4-perspective (fovy aspect near far)
  (let* ((half (* 0.5 fovy))
         ;; tan = sin/cos, with sin and cos borrowed from JavaScript's Math
         (f (/ (cos half) (sin half)))
         (nf (/ 1.0 (- near far)))
         (m (mat4-zero)))
    (dotimes (i 16) (setf (aref m i) 0.0))
    (setf (aref m 0) (/ f aspect))
    (setf (aref m 5) f)
    (setf (aref m 10) (* (+ far near) nf))
    (setf (aref m 11) -1.0)
    (setf (aref m 14) (* 2.0 far near nf))
    m))

(defun mat4-identity ()
  (let ((m (mat4-zero)))
    (dotimes (i 16) (setf (aref m i) 0.0))
    (setf (aref m 0) 1.0)
    (setf (aref m 5) 1.0)
    (setf (aref m 10) 1.0)
    (setf (aref m 15) 1.0)
    m))

(defun mat4-translation (x y z)
  (let ((m (mat4-identity)))
    (setf (aref m 12) x)
    (setf (aref m 13) y)
    (setf (aref m 14) z)
    m))

(defun mat4-rotation-x (angle)
  (let ((m (mat4-identity))
        (c (cos angle))
        (s (sin angle)))
    (setf (aref m 5) c)
    (setf (aref m 6) s)
    (setf (aref m 9) (- 0.0 s))
    (setf (aref m 10) c)
    m))

(defun mat4-rotation-y (angle)
  (let ((m (mat4-identity))
        (c (cos angle))
        (s (sin angle)))
    (setf (aref m 0) c)
    (setf (aref m 2) (- 0.0 s))
    (setf (aref m 8) s)
    (setf (aref m 10) c)
    m))

;; --- the cube ---------------------------------------------------------------
;; Eight corners, six colored faces; each face quad becomes two triangles, so
;; the vertex buffer holds 36 vertices x (position + color) = 216 floats.

(defconstant +corners+
  '((-0.5 -0.5 -0.5) (0.5 -0.5 -0.5) (0.5 0.5 -0.5) (-0.5 0.5 -0.5)
    (-0.5 -0.5 0.5) (0.5 -0.5 0.5) (0.5 0.5 0.5) (-0.5 0.5 0.5)))

;; Each face: the corner indices of its quad (counter-clockwise seen from
;; outside) and its color.
(defconstant +faces+
  '(((4 5 6 7) (0.94 0.42 0.48))        ; front  (+z) rose
    ((1 0 3 2) (0.42 0.72 0.94))        ; back   (-z) sky
    ((5 1 2 6) (0.55 0.48 0.94))        ; right  (+x) violet
    ((0 4 7 3) (0.44 0.88 0.72))        ; left   (-x) mint
    ((7 6 2 3) (0.93 0.90 0.72))        ; top    (+y) cream
    ((0 1 5 4) (0.36 0.40 0.62))        ; bottom (-y) slate
    ))

(defvar *float-index* 0)                ; write cursor into the staging array

(defun push-float (v)
  (set-float *float-index* v)
  (setq *float-index* (+ *float-index* 1)))

(defun push-vertex (corner color)
  (dolist (v corner) (push-float v))
  (dolist (v color) (push-float v)))

(defun push-face (face)
  (let* ((quad (car face))
         (color (car (cdr face)))
         (c0 (nth (nth 0 quad) +corners+))
         (c1 (nth (nth 1 quad) +corners+))
         (c2 (nth (nth 2 quad) +corners+))
         (c3 (nth (nth 3 quad) +corners+)))
    ;; the quad c0-c1-c2-c3 as two triangles
    (push-vertex c0 color) (push-vertex c1 color) (push-vertex c2 color)
    (push-vertex c0 color) (push-vertex c2 color) (push-vertex c3 color)))

;; --- setup ------------------------------------------------------------------

(defvar *u-mvp* 0)                      ; uniform location handle for uMvp
(defvar *projection* nil)               ; fixed: the canvas size does not change

(defun make-shader (type source)
  (let ((shader (gl-create-shader type)))
    (gl-shader-source shader source)
    (gl-compile-shader shader)
    shader))

(defun setup-gl ()
  (let ((program (gl-create-program)))
    (gl-attach-shader program (make-shader +gl-vertex-shader+ +vertex-shader-source+))
    (gl-attach-shader program (make-shader +gl-fragment-shader+ +fragment-shader-source+))
    (gl-link-program program)
    (gl-use-program program)
    (setq *u-mvp* (gl-get-uniform-location program "uMvp"))
    (gl-enable +gl-depth-test+)
    ;; fill the staging array with the 216 floats of cube geometry and upload
    (gl-bind-buffer +gl-array-buffer+ (gl-create-buffer))
    (setq *float-index* 0)
    (dolist (face +faces+) (push-face face))
    (gl-buffer-data-floats +gl-array-buffer+ *float-index* +gl-static-draw+)
    ;; interleaved layout: vec3 position + vec3 color = 24 bytes per vertex
    (gl-enable-vertex-attrib-array 0)
    (gl-vertex-attrib-pointer 0 3 +gl-float+ nil 24 0)
    (gl-enable-vertex-attrib-array 1)
    (gl-vertex-attrib-pointer 1 3 +gl-float+ nil 24 12)
    (setq *projection*
          (mat4-perspective (/ +pi+ 4.0) (/ (canvas-width) (canvas-height)) 0.1 100.0))))

;; --- the frame --------------------------------------------------------------

(defun frame (tm)
  (let* ((model (mat4-mul (mat4-rotation-y tm) (mat4-rotation-x (* tm 0.7))))
         (view (mat4-translation 0.0 0.0 -2.6))
         (mvp (mat4-mul *projection* (mat4-mul view model))))
    (dotimes (i 16) (set-float i (aref mvp i)))
    (gl-uniform-matrix4fv *u-mvp*)
    (gl-clear-color 0.05 0.06 0.1 1.0)
    (gl-clear (+ +gl-color-buffer-bit+ +gl-depth-buffer-bit+))
    (gl-draw-arrays +gl-triangles+ 0 36)))

;; Build the pipeline and upload the geometry at load time: this runs inside
;; _initialize, after the page has created the WebGL2 context.
(setup-gl)

(rontolisp:wasm-export 'frame :params '(:float) :returns :void)
