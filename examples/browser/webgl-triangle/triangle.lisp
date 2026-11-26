;;;; triangle.lisp -- the WebGL hello world, driven from Lisp.
;;;;
;;;; The smallest complete rontolisp:wasm-import program: it draws one colored
;;;; triangle. There are no exports and no frame loop -- the whole program is
;;;; top-level forms, so the page only instantiates the module and calls
;;;; _initialize(); by the time that returns, the triangle is on the canvas.
;;;;
;;;; To stay minimal the triangle uses no vertex buffer at all: the vertex
;;;; shader looks its corner positions and colors up by gl_VertexID (WebGL2
;;;; allows attributeless draws), so the only GL work is compiling the two
;;;; shaders, linking, clearing and one draw call.
;;;;
;;;; Ten imported host functions, one line of JavaScript each. When you outgrow
;;;; this, ../webgl-galaxy/ is the full-pipeline version: vertex buffers,
;;;; uniforms, shader-error reporting and an animation loop.

;; --- the host boundary ------------------------------------------------------
;; :as maps the Lisp name to the JavaScript property; :from names the
;; import-object key. GL objects (shaders, the program) cross the boundary as
;; :int handles into a table the page keeps; the GLSL source crosses as
;; :string.

(rontolisp:wasm-import 'gl-create-shader
                       :from "gl"
                       :as "createShader"
                       :params '(:int)
                       :returns :int)
(rontolisp:wasm-import 'gl-shader-source
                       :from "gl"
                       :as "shaderSource"
                       :params '(:int :string)
                       :returns :void)
(rontolisp:wasm-import 'gl-compile-shader
                       :from "gl"
                       :as "compileShader"
                       :params '(:int)
                       :returns :void)
(rontolisp:wasm-import 'gl-create-program
                       :from "gl"
                       :as "createProgram"
                       :params '()
                       :returns :int)
(rontolisp:wasm-import 'gl-attach-shader
                       :from "gl"
                       :as "attachShader"
                       :params '(:int :int)
                       :returns :void)
(rontolisp:wasm-import 'gl-link-program
                       :from "gl"
                       :as "linkProgram"
                       :params '(:int)
                       :returns :void)
(rontolisp:wasm-import 'gl-use-program
                       :from "gl"
                       :as "useProgram"
                       :params '(:int)
                       :returns :void)
(rontolisp:wasm-import 'gl-clear-color
                       :from "gl"
                       :as "clearColor"
                       :params '(:float :float :float :float)
                       :returns :void)
(rontolisp:wasm-import 'gl-clear
                       :from "gl"
                       :as "clear"
                       :params '(:int)
                       :returns :void)
(rontolisp:wasm-import 'gl-draw-arrays
                       :from "gl"
                       :as "drawArrays"
                       :params '(:int :int :int)
                       :returns :void)

;; --- WebGL constants --------------------------------------------------------
;; The numeric enum values from the WebGL specification.

(defconstant +gl-vertex-shader+ 35633)    ; 0x8B31
(defconstant +gl-fragment-shader+ 35632)  ; 0x8B30
(defconstant +gl-color-buffer-bit+ 16384) ; 0x4000
(defconstant +gl-triangles+ 4)

;; --- shaders ----------------------------------------------------------------
;; The GLSL lives here, in Lisp, and reaches the GPU through the imported
;; gl-shader-source (a :string parameter crossing the boundary as (ptr,len)
;; into this module's linear memory).

(defconstant +vertex-shader-source+
  "#version 300 es
// the classic hello-world triangle: positions and colors baked into the
// shader, looked up by gl_VertexID -- no vertex buffer needed
const vec2 POSITION[3] = vec2[3](
  vec2( 0.0,   0.62),
  vec2(-0.65, -0.5),
  vec2( 0.65, -0.5)
);
const vec3 COLOR[3] = vec3[3](
  vec3(1.0, 0.25, 0.3),
  vec3(0.25, 1.0, 0.45),
  vec3(0.3, 0.45, 1.0)
);
out vec3 vColor;
void main() {
  gl_Position = vec4(POSITION[gl_VertexID], 0.0, 1.0);
  vColor = COLOR[gl_VertexID];
}")

(defconstant +fragment-shader-source+
  "#version 300 es
precision mediump float;
in vec3 vColor;
out vec4 color;
void main() {
  color = vec4(vColor, 1.0);
}")

;; --- the program ------------------------------------------------------------

(defun make-shader (type source)
  (let ((shader (gl-create-shader type)))
    (gl-shader-source shader source)
    (gl-compile-shader shader)
    shader))

(defun main ()
  (let ((program (gl-create-program)))
    (gl-attach-shader program
                      (make-shader +gl-vertex-shader+ +vertex-shader-source+))
    (gl-attach-shader program
     (make-shader +gl-fragment-shader+ +fragment-shader-source+))
    (gl-link-program program)
    (gl-use-program program)
    (gl-clear-color 0.07 0.08 0.12 1.0)
    (gl-clear +gl-color-buffer-bit+)
    (gl-draw-arrays +gl-triangles+ 0 3)))

;; Runs inside _initialize, after the page has created the WebGL2 context and
;; instantiated the module.
(main)
