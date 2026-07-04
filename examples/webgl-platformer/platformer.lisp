;;;; platformer.lisp -- a one-stage 3D platformer, entirely in Lisp.
;;;;
;;;; Run with W/A/S/D (W is forward, into the screen), jump with Space,
;;;; reach the flag pole. Everything that makes it a game lives here: the
;;;; physics (gravity, jump buffering, coyote time, variable jump height),
;;;; the per-axis AABB collision resolution against the level geometry, the
;;;; enemy patrols and the stomp-or-die rule, the coin pickups, the goal
;;;; trigger, the follow camera with its look-at/perspective matrices, and
;;;; every triangle of the world -- the level blocks, the scenery, the
;;;; little robot explorer (antenna, visor and all), the enemies and the
;;;; spinning coins are tessellated from rotated boxes each frame.
;;;; JavaScript is the same one-line WebGL2 host boundary as the other
;;;; webgl-* demos, plus keyboard forwarding and the HUD.
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

;; The bulk-float staging path (see webgl-robot-arm): per-vertex floats cannot
;; cross into GPU memory one WASM value at a time, so the page keeps one
;; Float32Array of 9-float vertices (position, normal, color) plus a 16-float
;; scratch for the mat4 uniform. Colors are constant per box, so set-color
;; latches the current color and set-vertex stages position + normal + that
;; color (functions cross the WASM boundary with at most 7 parameters).
(rontolisp:wasm-import 'set-color :from "gl" :as "setColor"
                       :params '(:float :float :float) :returns :void)
(rontolisp:wasm-import 'set-vertex :from "gl" :as "setVertex"
                       :params '(:int :float :float :float :float :float :float)
                       :returns :void)
(rontolisp:wasm-import 'gl-upload-vertices :from "gl" :as "uploadVertices"
                       :params '(:int :int) :returns :void)
(rontolisp:wasm-import 'set-float :from "gl" :as "setFloat"
                       :params '(:int :float) :returns :void)
(rontolisp:wasm-import 'gl-uniform-matrix4fv :from "gl" :as "uniformMatrix4fv"
                       :params '(:int) :returns :void)

;; Canvas metrics, owned by the page.
(rontolisp:wasm-import 'canvas-width :from "canvas" :as "width"
                       :params '() :returns :float)
(rontolisp:wasm-import 'canvas-height :from "canvas" :as "height"
                       :params '() :returns :float)

;; The WASM backend has no transcendental built-ins, so borrow the host's.
(rontolisp:wasm-import 'sin :from "math" :params '(:float) :returns :float)
(rontolisp:wasm-import 'cos :from "math" :params '(:float) :returns :float)
(rontolisp:wasm-import 'atan2 :from "math" :params '(:float :float) :returns :float)

(defconstant +pi+ 3.141592653589793)
(defconstant +two-pi+ 6.283185307179586)

;; --- shaders ----------------------------------------------------------------
;;
;; One program: lit triangles under a warm daylight key, a sky-tinted
;; hemisphere ambient and a distance fog that fades the far scenery into the
;; sky color.

(defconstant +solid-vs+ "#version 300 es
layout(location=0) in vec3 aPos;     // world-space position from Lisp
layout(location=1) in vec3 aNormal;  // world-space normal from Lisp
layout(location=2) in vec3 aColor;
uniform mat4 uVP;                    // view-projection, computed in Lisp
out vec3 vN;
out vec3 vC;
out vec3 vW;
void main() {
  gl_Position = uVP * vec4(aPos, 1.0);
  vN = aNormal;
  vC = aColor;
  vW = aPos;
}")

(defconstant +solid-fs+ "#version 300 es
precision mediump float;
in vec3 vN;
in vec3 vC;
in vec3 vW;
uniform vec3 uEye;
out vec4 color;
void main() {
  vec3 n = normalize(vN);
  vec3 l = normalize(vec3(0.45, 0.80, 0.35));
  float diff = max(dot(n, l), 0.0);
  float amb  = 0.42 + 0.16 * n.y;           // hemisphere: tops brighter
  vec3 lit = vC * (amb + 0.62 * diff);
  vec3 sky = vec3(0.52, 0.74, 0.98);
  float fog = smoothstep(18.0, 55.0, distance(uEye, vW));
  color = vec4(mix(lit, sky, fog), 1.0);
}")

;; --- 4x4 matrix math ----------------------------------------------------------
;; Column-major flat 16-element arrays (the OpenGL convention, as in
;; webgl-cube): element (row, col) lives at index (+ row (* col 4)).

(defun mat4-zero ()
  (make-array 16 :initial-element 0.0))

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

(defconstant +half-fov+ 0.39269908169872414)    ; pi/8: half the 45-degree fov

(defun mat4-perspective (aspect near far)
  (let* ((f (/ (cos +half-fov+) (sin +half-fov+)))
         (nf (/ 1.0 (- near far)))
         (m (mat4-zero)))
    (setf (aref m 0) (/ f aspect))
    (setf (aref m 5) f)
    (setf (aref m 10) (* (+ far near) nf))
    (setf (aref m 11) -1.0)
    (setf (aref m 14) (* 2.0 far near nf))
    m))

;; --- the follow camera --------------------------------------------------------
;;
;; The eye orbits a smoothed copy of the player's position: drag gestures
;; arrive through the exported `orbit` (yaw/pitch deltas) and `zoom`, as in
;; webgl-robot-arm. At the default yaw the camera sits at -x looking down
;; the course, so W runs toward the flag in the distance -- and steering is
;; camera-relative, so W stays "into the screen" from any angle.

(defvar *camx* 0.0)                     ; the smoothed follow point
(defvar *camy* 0.0)
(defvar *camz* 0.0)
;; The default orbit reproduces the original fixed follow camera: 7.2 back,
;; 3.3 up, looking down the course. A restart puts the orbit back here.
(defconstant +cam-yaw-0+ 0.0)           ; 0 looks along +x, down the course
(defconstant +cam-pitch-0+ 0.43)
(defconstant +cam-dist-0+ 7.9)

(defvar *cam-yaw* +cam-yaw-0+)
(defvar *cam-pitch* +cam-pitch-0+)
(defvar *cam-dist* +cam-dist-0+)
(defvar *eyex* 0.0)
(defvar *eyey* 0.0)
(defvar *eyez* 0.0)
(defvar *aspect* 1.0)
(defvar *vp* nil)                       ; the current view-projection matrix

(defun build-view (tx ty tz)
  ;; The look-at view matrix for eye -> target, straight into column-major
  ;; slots: rows are camera right / up / -forward.
  (let* ((fx (- tx *eyex*))
         (fy (- ty *eyey*))
         (fz (- tz *eyez*))
         (fl (sqrt (+ (* fx fx) (* fy fy) (* fz fz))))
         (nfx (/ fx fl))
         (nfy (/ fy fl))
         (nfz (/ fz fl))
         ;; right = normalize(cross(forward, world-up))
         (rx0 (- 0.0 nfz))
         (rz0 nfx)
         (rl (sqrt (+ (* rx0 rx0) (* rz0 rz0))))
         (rx (/ rx0 rl))
         (rz (/ rz0 rl))
         ;; up = cross(right, forward), with right.y = 0
         (ux (- 0.0 (* rz nfy)))
         (uy (- (* rz nfx) (* rx nfz)))
         (uz (* rx nfy))
         (v (mat4-zero)))
    (setf (aref v 0) rx)
    (setf (aref v 4) 0.0)
    (setf (aref v 8) rz)
    (setf (aref v 12) (- 0.0 (+ (* rx *eyex*) (* rz *eyez*))))
    (setf (aref v 1) ux)
    (setf (aref v 5) uy)
    (setf (aref v 9) uz)
    (setf (aref v 13) (- 0.0 (+ (* ux *eyex*) (* uy *eyey*) (* uz *eyez*))))
    (setf (aref v 2) (- 0.0 nfx))
    (setf (aref v 6) (- 0.0 nfy))
    (setf (aref v 10) (- 0.0 nfz))
    (setf (aref v 14) (+ (* nfx *eyex*) (* nfy *eyey*) (* nfz *eyez*)))
    (setf (aref v 15) 1.0)
    v))

(defun orbit (dx dy)
  ;; Exported: drag deltas, normalized by the canvas height.
  (setq *cam-yaw* (- *cam-yaw* (* 3.4 dx)))
  (let ((p (+ *cam-pitch* (* 2.6 dy))))
    (setq *cam-pitch* (max 0.12 (min 1.35 p)))))

(defun zoom (dz)
  ;; Exported: scroll-wheel deltas.
  (setq *cam-dist* (max 4.0 (min 14.0 (+ *cam-dist* dz)))))

(defun update-camera (dt)
  ;; Ease the follow point toward the player (never below the horizon while
  ;; falling into a pit), then place the eye on its orbit around it.
  (let ((k (min 1.0 (* 5.0 dt)))
        (ty (max *py* -0.5)))
    (setq *camx* (+ *camx* (* k (- *px* *camx*))))
    (setq *camy* (+ *camy* (* k (- ty *camy*))))
    (setq *camz* (+ *camz* (* k (- *pz* *camz*)))))
  (let ((cp (cos *cam-pitch*))
        (sp (sin *cam-pitch*))
        (cy (cos *cam-yaw*))
        (sy (sin *cam-yaw*)))
    (setq *eyex* (- *camx* (* *cam-dist* cp cy)))
    (setq *eyey* (+ *camy* (* *cam-dist* sp)))
    (setq *eyez* (- *camz* (* *cam-dist* cp sy)))
    (setq *vp* (mat4-mul (mat4-perspective *aspect* 0.1 90.0)
                         (build-view (+ *camx* (* 1.5 cy))
                                     (+ *camy* 1.0)
                                     (+ *camz* (* 1.5 sy)))))))

;; --- GL pipeline setup --------------------------------------------------------

(defvar *prog* 0)
(defvar *u-vp* 0)
(defvar *u-eye* 0)
(defvar *vao* 0)
(defvar *buf* 0)

(defconstant +max-verts+ 8192)          ; lit-triangle vertex capacity

(defun setup-gl ()
  (setq *prog* (gl:build-program +solid-vs+ +solid-fs+))
  (setq *u-vp* (gl:get-uniform-location *prog* "uVP"))
  (setq *u-eye* (gl:get-uniform-location *prog* "uEye"))
  (gl:enable gl:+depth-test+)
  ;; one VAO: position + normal + color, 36 bytes per vertex
  (setq *vao* (gl:create-vertex-array))
  (gl:bind-vertex-array *vao*)
  (setq *buf* (gl:create-buffer))
  (gl:bind-buffer gl:+array-buffer+ *buf*)
  (gl:buffer-data gl:+array-buffer+ (* +max-verts+ 36) gl:+dynamic-draw+)
  (gl:enable-vertex-attrib-array 0)
  (gl:vertex-attrib-pointer 0 3 gl:+float+ nil 36 0)
  (gl:enable-vertex-attrib-array 1)
  (gl:vertex-attrib-pointer 1 3 gl:+float+ nil 36 12)
  (gl:enable-vertex-attrib-array 2)
  (gl:vertex-attrib-pointer 2 3 gl:+float+ nil 36 24))

;; --- box tessellation -----------------------------------------------------------
;;
;; Everything in the world is a yaw-rotated box. emit-box writes the 8 world
;; corners into scratch arrays, then stamps the 6 faces (12 triangles) with
;; face normals rotated by the same yaw. Corner index bits: bit 0 = +x,
;; bit 1 = +y, bit 2 = +z in local space.

(defvar *v* 0)                          ; vertex write cursor
(defvar *static-verts* 0)               ; level + scenery, uploaded once

(defvar *cwx* (make-array 8 :initial-element 0.0))
(defvar *cwy* (make-array 8 :initial-element 0.0))
(defvar *cwz* (make-array 8 :initial-element 0.0))

(defun emit-v (x y z nx ny nz)
  ;; stages one vertex with the color latched by the last set-color call
  (set-vertex *v* x y z nx ny nz)
  (setq *v* (+ *v* 1)))

(defun emit-face (a b c d nx ny nz)
  ;; one box face (two triangles) from the scratch corners
  (emit-v (aref *cwx* a) (aref *cwy* a) (aref *cwz* a) nx ny nz)
  (emit-v (aref *cwx* b) (aref *cwy* b) (aref *cwz* b) nx ny nz)
  (emit-v (aref *cwx* c) (aref *cwy* c) (aref *cwz* c) nx ny nz)
  (emit-v (aref *cwx* a) (aref *cwy* a) (aref *cwz* a) nx ny nz)
  (emit-v (aref *cwx* c) (aref *cwy* c) (aref *cwz* c) nx ny nz)
  (emit-v (aref *cwx* d) (aref *cwy* d) (aref *cwz* d) nx ny nz))

(defun emit-box (cx cy cz hx hy hz yaw)
  ;; a box centered at c with half extents h, rotated around y by yaw;
  ;; local +x rotates to world (cos yaw, 0, -sin yaw)
  (let ((c (cos yaw))
        (s (sin yaw)))
    (dotimes (i 8)
      (let ((lx (if (= (logand i 1) 1) hx (- 0.0 hx)))
            (ly (if (= (logand i 2) 2) hy (- 0.0 hy)))
            (lz (if (= (logand i 4) 4) hz (- 0.0 hz))))
        (setf (aref *cwx* i) (+ cx (* lx c) (* lz s)))
        (setf (aref *cwy* i) (+ cy ly))
        (setf (aref *cwz* i) (+ cz (- (* lz c) (* lx s))))))
    (emit-face 4 5 7 6 s 0.0 c)                           ; +z
    (emit-face 1 0 2 3 (- 0.0 s) 0.0 (- 0.0 c))           ; -z
    (emit-face 5 1 3 7 c 0.0 (- 0.0 s))                   ; +x
    (emit-face 0 4 6 2 (- 0.0 c) 0.0 s)                   ; -x
    (emit-face 6 7 3 2 0.0 1.0 0.0)                       ; +y
    (emit-face 0 1 5 4 0.0 -1.0 0.0)))                    ; -y

;; A local frame for composite figures (the player, the enemies, the coins):
;; set-origin latches a world position + yaw, and part emits one box given in
;; that frame's local coordinates.
(defvar *ox* 0.0)
(defvar *oy* 0.0)
(defvar *oz* 0.0)
(defvar *oyaw* 0.0)
(defvar *oc* 1.0)
(defvar *os* 0.0)

(defun set-origin (x y z yaw)
  (setq *ox* x)
  (setq *oy* y)
  (setq *oz* z)
  (setq *oyaw* yaw)
  (setq *oc* (cos yaw))
  (setq *os* (sin yaw)))

(defun part (lx ly lz hx hy hz)
  (emit-box (+ *ox* (* lx *oc*) (* lz *os*))
            (+ *oy* ly)
            (+ *oz* (- (* lz *oc*) (* lx *os*)))
            hx hy hz *oyaw*))

;; --- the stage ------------------------------------------------------------------
;;
;; One course along +x: grass runs broken by two pits, a pipe, brick
;; platforms, a staircase and the goal flag on the far ground. Each solid
;; block is (x0 y0 z0 x1 y1 z1 r g b); the same list drives both the baked
;; static mesh and the collision arrays. Scenery blocks render identically
;; but nothing collides with them.

(defconstant +solids+
  '((-4.0 -1.0 -3.5 14.0 0.0 3.5 0.38 0.70 0.34)          ; grass run A
    (-4.0 -2.2 -3.5 14.0 -1.0 3.5 0.47 0.34 0.24)         ; its dirt band
    (7.0 1.2 0.6 9.5 1.8 2.6 0.76 0.47 0.29)              ; brick platform
    (16.0 -1.0 -3.5 31.0 0.0 3.5 0.38 0.70 0.34)          ; grass run B
    (16.0 -2.2 -3.5 31.0 -1.0 3.5 0.47 0.34 0.24)
    (20.0 0.0 -2.6 21.6 1.3 -1.0 0.22 0.62 0.30)          ; the pipe body
    (19.85 1.3 -2.75 21.75 1.75 -0.85 0.26 0.70 0.34)     ; the pipe lip
    (23.0 1.6 -3.0 26.0 2.2 -0.5 0.76 0.47 0.29)          ; high bricks
    (32.0 0.5 -0.7 33.4 1.1 0.9 0.64 0.62 0.60)           ; stone step, pit B
    (34.0 -1.0 -3.5 48.0 0.0 3.5 0.38 0.70 0.34)          ; grass run C
    (34.0 -2.2 -3.5 48.0 -1.0 3.5 0.47 0.34 0.24)
    (49.0 0.4 -0.8 50.4 1.0 0.8 0.64 0.62 0.60)           ; stone step, pit C
    (51.0 -1.0 -3.5 68.0 0.0 3.5 0.38 0.70 0.34)          ; goal ground
    (51.0 -2.2 -3.5 68.0 -1.0 3.5 0.47 0.34 0.24)
    (55.0 0.0 -2.2 59.0 0.7 0.2 0.82 0.66 0.40)           ; the staircase
    (56.0 0.7 -2.2 59.0 1.4 0.2 0.82 0.66 0.40)
    (57.0 1.4 -2.2 59.0 2.1 0.2 0.82 0.66 0.40)
    (58.0 2.1 -2.2 59.0 2.8 0.2 0.82 0.66 0.40)
    (61.4 0.0 -1.6 62.6 0.5 -0.4 0.64 0.62 0.60)          ; flag pole base
    (64.6 0.0 -3.4 67.6 2.4 -0.9 0.86 0.83 0.78)          ; castle keep
    (65.6 2.4 -2.7 66.6 3.4 -1.6 0.86 0.83 0.78)))        ; castle tower

(defconstant +scenery+
  '((61.95 0.5 -1.03 62.05 4.4 -0.97 0.80 0.83 0.88)      ; the flag pole
    (61.97 4.4 -1.05 62.03 4.52 -0.95 1.00 0.83 0.25)     ; its finial
    (61.99 3.65 -0.95 62.01 4.15 -0.25 0.24 0.70 0.34)    ; the flag, facing the camera
    (64.55 0.0 -2.5 64.65 1.1 -1.7 0.35 0.25 0.18)        ; castle door
    (65.9 3.4 -2.17 65.98 4.1 -2.13 0.80 0.83 0.88)       ; castle banner pole
    (65.92 3.75 -2.13 65.96 4.1 -1.61 0.90 0.30 0.24)     ; castle banner
    (6.0 -1.0 -13.0 18.0 2.6 -7.5 0.30 0.58 0.31)         ; hills, left bank
    (24.0 -1.0 -15.0 42.0 4.2 -9.0 0.27 0.53 0.30)
    (46.0 -1.0 -13.0 60.0 2.2 -7.8 0.30 0.58 0.31)
    (10.0 -1.0 7.5 22.0 3.0 12.5 0.28 0.55 0.30)          ; hills, right bank
    (30.0 -1.0 8.0 44.0 2.4 13.0 0.30 0.58 0.31)
    (52.0 -1.0 7.5 64.0 3.4 12.5 0.28 0.55 0.30)
    (76.0 -1.0 -16.0 100.0 8.0 16.0 0.26 0.50 0.29)       ; the far range ahead
    (2.0 5.6 -11.0 6.0 6.6 -9.6 0.99 0.99 0.99)           ; clouds
    (20.0 6.8 -13.0 25.5 7.9 -11.4 0.99 0.99 0.99)
    (37.0 5.9 -11.5 41.5 6.9 -10.1 0.99 0.99 0.99)
    (54.0 6.7 -13.5 59.0 7.8 -11.9 0.99 0.99 0.99)
    (14.0 6.2 8.5 18.5 7.2 10.2 0.99 0.99 0.99)
    (44.0 7.0 9.0 48.5 8.0 10.8 0.99 0.99 0.99)))

;; The collision arrays, parsed once from +solids+.
(defvar *nsolid* 0)
(defvar *sx0* nil)
(defvar *sy0* nil)
(defvar *sz0* nil)
(defvar *sx1* nil)
(defvar *sy1* nil)
(defvar *sz1* nil)

(defun parse-solids ()
  (setq *nsolid* (length +solids+))
  (setq *sx0* (make-array *nsolid* :initial-element 0.0))
  (setq *sy0* (make-array *nsolid* :initial-element 0.0))
  (setq *sz0* (make-array *nsolid* :initial-element 0.0))
  (setq *sx1* (make-array *nsolid* :initial-element 0.0))
  (setq *sy1* (make-array *nsolid* :initial-element 0.0))
  (setq *sz1* (make-array *nsolid* :initial-element 0.0))
  (let ((i 0))
    (dolist (b +solids+)
      (setf (aref *sx0* i) (nth 0 b))
      (setf (aref *sy0* i) (nth 1 b))
      (setf (aref *sz0* i) (nth 2 b))
      (setf (aref *sx1* i) (nth 3 b))
      (setf (aref *sy1* i) (nth 4 b))
      (setf (aref *sz1* i) (nth 5 b))
      (setq i (+ i 1)))))

(defun emit-block (b)
  ;; one static block: an axis-aligned box from its corner list entry
  (set-color (nth 6 b) (nth 7 b) (nth 8 b))
  (emit-box (* 0.5 (+ (nth 0 b) (nth 3 b)))
            (* 0.5 (+ (nth 1 b) (nth 4 b)))
            (* 0.5 (+ (nth 2 b) (nth 5 b)))
            (* 0.5 (- (nth 3 b) (nth 0 b)))
            (* 0.5 (- (nth 4 b) (nth 1 b)))
            (* 0.5 (- (nth 5 b) (nth 2 b)))
            0.0))

(defun bake-static ()
  ;; the level and the scenery, tessellated once into the front of the buffer
  (setq *v* 0)
  (dolist (b +solids+) (emit-block b))
  (dolist (b +scenery+) (emit-block b))
  (setq *static-verts* *v*)
  (gl:bind-buffer gl:+array-buffer+ *buf*)
  (gl-upload-vertices 0 (* *static-verts* 9)))

;; --- coins ----------------------------------------------------------------------

(defconstant +coins+
  '((7.6 2.4 1.6) (8.4 2.4 1.6) (9.2 2.4 1.6)             ; over the bricks
    (14.6 1.1 0.0) (15.2 1.5 0.0) (15.8 1.1 0.0)          ; the arc over pit A
    (23.6 2.9 -1.7) (24.5 2.9 -1.7) (25.4 2.9 -1.7)       ; on the high bricks
    (41.0 0.7 0.5) (42.0 0.7 0.5) (43.0 0.7 0.5)          ; the run-C row
    (49.2 1.8 0.0) (50.2 1.8 0.0)))                       ; the arc over pit C

(defvar *ncoin* 0)
(defvar *coinx* nil)
(defvar *coiny* nil)
(defvar *coinz* nil)
(defvar *ctaken* nil)                   ; t once collected
(defvar *coins* 0)                      ; the HUD counter

(defun parse-coins ()
  (setq *ncoin* (length +coins+))
  (setq *coinx* (make-array *ncoin* :initial-element 0.0))
  (setq *coiny* (make-array *ncoin* :initial-element 0.0))
  (setq *coinz* (make-array *ncoin* :initial-element 0.0))
  (setq *ctaken* (make-array *ncoin* :initial-element nil))
  (let ((i 0))
    (dolist (c +coins+)
      (setf (aref *coinx* i) (nth 0 c))
      (setf (aref *coiny* i) (nth 1 c))
      (setf (aref *coinz* i) (nth 2 c))
      (setq i (+ i 1)))))

;; --- enemies --------------------------------------------------------------------
;;
;; Each entry is (x z minx maxx): a patroller shuffling along x on flat
;; ground (y 0), turning at its bounds. Stomp it from above to squash it;
;; touch it any other way and you lose a life.

(defconstant +enemy-list+
  '((18.0 1.2 17.2 28.5)
    (36.0 -1.5 35.0 46.5)
    (43.0 1.8 36.5 47.0)
    (53.0 1.6 51.8 54.4)))

(defconstant +enemy-speed+ 1.4)

(defvar *nenemy* 0)
(defvar *ex* nil)
(defvar *ez* nil)
(defvar *eminx* nil)
(defvar *emaxx* nil)
(defvar *edir* nil)                     ; +1.0 / -1.0
(defvar *ealive* nil)                   ; t while walking
(defvar *esquash* nil)                  ; squashed-remains countdown

(defun parse-enemies ()
  (setq *nenemy* (length +enemy-list+))
  (setq *ex* (make-array *nenemy* :initial-element 0.0))
  (setq *ez* (make-array *nenemy* :initial-element 0.0))
  (setq *eminx* (make-array *nenemy* :initial-element 0.0))
  (setq *emaxx* (make-array *nenemy* :initial-element 0.0))
  (setq *edir* (make-array *nenemy* :initial-element 1.0))
  (setq *ealive* (make-array *nenemy* :initial-element t))
  (setq *esquash* (make-array *nenemy* :initial-element 0.0))
  (let ((i 0))
    (dolist (e +enemy-list+)
      (setf (aref *ex* i) (nth 0 e))
      (setf (aref *ez* i) (nth 1 e))
      (setf (aref *eminx* i) (nth 2 e))
      (setf (aref *emaxx* i) (nth 3 e))
      (setf (aref *edir* i) 1.0)
      (setf (aref *ealive* i) t)
      (setf (aref *esquash* i) 0.0)
      (setq i (+ i 1)))))

(defun update-enemies (dt)
  (dotimes (i *nenemy*)
    (if (aref *ealive* i)
        (let ((x (+ (aref *ex* i) (* (aref *edir* i) +enemy-speed+ dt))))
          (when (> x (aref *emaxx* i))
            (setq x (aref *emaxx* i))
            (setf (aref *edir* i) -1.0))
          (when (< x (aref *eminx* i))
            (setq x (aref *eminx* i))
            (setf (aref *edir* i) 1.0))
          (setf (aref *ex* i) x))
        (when (> (aref *esquash* i) 0.0)
          (setf (aref *esquash* i) (- (aref *esquash* i) dt))))))

;; --- the player -----------------------------------------------------------------
;;
;; Position is the feet; the collision volume is the AABB
;; [p - (hx, 0, hz), p + (hx, height, hz)].

(defconstant +p-hx+ 0.30)
(defconstant +p-hz+ 0.30)
(defconstant +p-h+ 0.95)                ; standing height
(defconstant +run-speed+ 4.6)
(defconstant +gravity+ 26.0)
(defconstant +jump-v+ 10.0)             ; apex ~1.9, just over two blocks
(defconstant +spawn-x+ 0.0)
(defconstant +spawn-y+ 0.5)
(defconstant +spawn-z+ 0.0)
(defconstant +goal-x+ 62.0)             ; the flag pole
(defconstant +goal-z+ -1.0)

(defvar *px* 0.0)
(defvar *py* 0.0)
(defvar *pz* 0.0)
(defvar *vx* 0.0)
(defvar *vy* 0.0)
(defvar *vz* 0.0)
(defvar *grounded* nil)
(defvar *coyote* 0.0)                   ; grace after running off an edge
(defvar *jump-buf* 0.0)                 ; grace before landing
(defvar *yaw* 0.0)                      ; facing; 0 looks along +x
(defvar *run-phase* 0.0)                ; the run-cycle oscillator

;; game state: 0 playing, 1 dying, 2 course clear
(defvar *state* 0)
(defvar *state-t* 0.0)                  ; time in the current state
(defvar *deaths* 0)
(defvar *start-tm* -1.0)                ; wall time when the run started
(defvar *elapsed* 0.0)                  ; frozen at the moment of clearing
(defvar *last-tm* 0.0)
(defvar *pending-reset* nil)

;; keyboard state, forwarded by the page: 1.0 while held
(defvar *in-l* 0.0)                     ; A
(defvar *in-r* 0.0)                     ; D
(defvar *in-f* 0.0)                     ; W (away from the camera)
(defvar *in-b* 0.0)                     ; S (toward the camera)
(defvar *in-jump* 0.0)                  ; Space
(defvar *jump-prev* nil)

(defun set-key (code down)
  ;; Exported: 0 = A, 1 = D, 2 = W, 3 = S, 4 = Space; down is 1 or 0.
  (let ((v (if (= down 1) 1.0 0.0)))
    (cond ((= code 0) (setq *in-l* v))
          ((= code 1) (setq *in-r* v))
          ((= code 2) (setq *in-f* v))
          ((= code 3) (setq *in-b* v))
          ((= code 4) (setq *in-jump* v)))))

(defun restart ()
  ;; Exported: back to the start, coins restored, enemies alive, clock zero.
  (setq *pending-reset* t))

(defun respawn ()
  (setq *px* +spawn-x+)
  (setq *py* +spawn-y+)
  (setq *pz* +spawn-z+)
  (setq *vx* 0.0)
  (setq *vy* 0.0)
  (setq *vz* 0.0)
  (setq *yaw* 0.0)
  (setq *grounded* nil)
  (setq *coyote* 0.0)
  (setq *jump-buf* 0.0)
  (setq *state* 0)
  (setq *state-t* 0.0)
  (setq *camx* *px*)
  (setq *camy* *py*)
  (setq *camz* *pz*))

(defun reset-game (tm)
  (respawn)
  (setq *cam-yaw* +cam-yaw-0+)
  (setq *cam-pitch* +cam-pitch-0+)
  (setq *cam-dist* +cam-dist-0+)
  (setq *coins* 0)
  (setq *deaths* 0)
  (setq *start-tm* tm)
  (setq *elapsed* 0.0)
  (dotimes (i *ncoin*)
    (setf (aref *ctaken* i) nil))
  (dotimes (i *nenemy*)
    (setf (aref *ex* i) (nth 0 (nth i +enemy-list+)))
    (setf (aref *edir* i) 1.0)
    (setf (aref *ealive* i) t)
    (setf (aref *esquash* i) 0.0)))

(defun die ()
  (setq *state* 1)
  (setq *state-t* 0.0)
  (setq *deaths* (+ *deaths* 1))
  (setq *vy* 8.0))                      ; the little farewell hop

;; --- collision -------------------------------------------------------------------
;;
;; Classic per-axis resolution: integrate one axis, then push the player's
;; AABB out of any solid it entered, killing that axis' velocity. The y pass
;; also decides groundedness.

(defun overlap-p (i)
  (and (> (+ *px* +p-hx+) (aref *sx0* i)) (< (- *px* +p-hx+) (aref *sx1* i))
       (> (+ *py* +p-h+) (aref *sy0* i)) (< *py* (aref *sy1* i))
       (> (+ *pz* +p-hz+) (aref *sz0* i)) (< (- *pz* +p-hz+) (aref *sz1* i))))

(defun resolve-x ()
  (dotimes (i *nsolid*)
    (when (overlap-p i)
      (if (> *vx* 0.0)
          (setq *px* (- (aref *sx0* i) +p-hx+))
          (setq *px* (+ (aref *sx1* i) +p-hx+)))
      (setq *vx* 0.0))))

(defun resolve-z ()
  (dotimes (i *nsolid*)
    (when (overlap-p i)
      (if (> *vz* 0.0)
          (setq *pz* (- (aref *sz0* i) +p-hz+))
          (setq *pz* (+ (aref *sz1* i) +p-hz+)))
      (setq *vz* 0.0))))

(defun resolve-y ()
  (dotimes (i *nsolid*)
    (when (overlap-p i)
      (if (<= *vy* 0.0)
          (progn
            (setq *py* (aref *sy1* i))
            (setq *grounded* t))
          (setq *py* (- (aref *sy0* i) +p-h+)))
      (setq *vy* 0.0))))

(defun move-player (dt)
  (setq *px* (+ *px* (* *vx* dt)))
  (resolve-x)
  (setq *pz* (+ *pz* (* *vz* dt)))
  (resolve-z)
  (setq *grounded* nil)
  (setq *py* (+ *py* (* *vy* dt)))
  (resolve-y))

;; --- the playing-state step -----------------------------------------------------

(defun steer (dt)
  ;; Accelerate toward the held direction; the ground grips harder than air.
  ;; Steering is camera-relative: W runs away from the camera, A/D strafe
  ;; across it, whatever the current orbit yaw.
  (let* ((f (- *in-f* *in-b*))
         (r (- *in-r* *in-l*))
         ;; keep diagonals at running speed
         (n (if (or (= f 0.0) (= r 0.0)) 1.0 0.7071))
         (cy (cos *cam-yaw*))
         (sy (sin *cam-yaw*))
         (tx (* +run-speed+ n (- (* f cy) (* r sy))))
         (tz (* +run-speed+ n (+ (* f sy) (* r cy))))
         (acc (* dt (if *grounded* 34.0 16.0))))
    (let ((dx (- tx *vx*))
          (dz (- tz *vz*)))
      (setq *vx* (+ *vx* (max (- 0.0 acc) (min acc dx))))
      (setq *vz* (+ *vz* (max (- 0.0 acc) (min acc dz)))))))

(defun update-facing (dt)
  ;; Turn toward the velocity, the short way around.
  (let ((sp (+ (* *vx* *vx*) (* *vz* *vz*))))
    (when (> sp 0.09)
      (let ((d (- (atan2 (- 0.0 *vz*) *vx*) *yaw*)))
        (while (> d +pi+) (setq d (- d +two-pi+)))
        (while (< d (- 0.0 +pi+)) (setq d (+ d +two-pi+)))
        (setq *yaw* (+ *yaw* (* d (min 1.0 (* 14.0 dt)))))))))

(defun jump-control (dt)
  ;; Buffered, coyote-timed, variable-height jumping.
  (let ((held (> *in-jump* 0.5)))
    (when (and held (not *jump-prev*))
      (setq *jump-buf* 0.12))
    (setq *jump-prev* held)
    (when (> *jump-buf* 0.0)
      (setq *jump-buf* (- *jump-buf* dt)))
    (if *grounded*
        (setq *coyote* 0.10)
        (when (> *coyote* 0.0)
          (setq *coyote* (- *coyote* dt))))
    (when (and (> *jump-buf* 0.0) (or *grounded* (> *coyote* 0.0)))
      (setq *vy* +jump-v+)
      (setq *grounded* nil)
      (setq *coyote* 0.0)
      (setq *jump-buf* 0.0))
    ;; release early for a shorter hop
    (when (and (not held) (> *vy* 3.5))
      (setq *vy* 3.5))))

(defun check-coins ()
  (dotimes (i *ncoin*)
    (unless (aref *ctaken* i)
      (let ((dx (- (aref *coinx* i) *px*))
            (dy (- (aref *coiny* i) (+ *py* 0.55)))
            (dz (- (aref *coinz* i) *pz*)))
        (when (< (+ (* dx dx) (* dy dy) (* dz dz)) 0.5)
          (setf (aref *ctaken* i) t)
          (setq *coins* (+ *coins* 1)))))))

(defun check-enemies ()
  (dotimes (i *nenemy*)
    (when (aref *ealive* i)
      (let ((dx (- *px* (aref *ex* i)))
            (dz (- *pz* (aref *ez* i))))
        (when (and (< (abs dx) (+ +p-hx+ 0.27))
                   (< (abs dz) (+ +p-hz+ 0.25))
                   (> (+ *py* +p-h+) 0.05)
                   (< *py* 0.58))
          (if (and (< *vy* -0.5) (> *py* 0.12))
              (progn                     ; the stomp
                (setf (aref *ealive* i) nil)
                (setf (aref *esquash* i) 0.6)
                (setq *vy* 8.0))
              (die)))))))

(defun check-goal ()
  (when (and (> *px* (- +goal-x+ 0.95)) (< *px* (+ +goal-x+ 0.95))
             (> *pz* (- +goal-z+ 1.3)) (< *pz* (+ +goal-z+ 1.3))
             (< *py* 4.4))
    (setq *state* 2)
    (setq *state-t* 0.0)
    (setq *elapsed* (- *last-tm* *start-tm*))))

(defun step-playing (dt)
  (steer dt)
  (jump-control dt)
  (setq *vy* (max -22.0 (- *vy* (* +gravity+ dt))))
  (move-player dt)
  (update-facing dt)
  ;; the run cycle swings the limbs while grounded and moving
  (let ((sp (sqrt (+ (* *vx* *vx*) (* *vz* *vz*)))))
    (if (and *grounded* (> sp 0.4))
        (setq *run-phase* (+ *run-phase* (* sp 3.2 dt)))
        (setq *run-phase* 0.0)))
  (check-coins)
  (check-enemies)
  (check-goal)
  (when (< *py* -7.0)
    (die)
    (setq *vy* 0.0)))                   ; already falling: no farewell hop

(defun step-dying (dt)
  ;; a spin and a fall, free of the world, then back to the start
  (setq *vy* (- *vy* (* +gravity+ dt)))
  (setq *py* (+ *py* (* *vy* dt)))
  (setq *yaw* (+ *yaw* (* 12.0 dt)))
  (when (> *state-t* 1.4)
    (respawn)))

(defun step-clear (dt)
  ;; land, stop and face the camera for the bow
  (setq *vx* (* *vx* (max 0.0 (- 1.0 (* 8.0 dt)))))
  (setq *vz* 0.0)
  (setq *vy* (max -22.0 (- *vy* (* +gravity+ dt))))
  (move-player dt)
  (let ((d (- (atan2 (sin *cam-yaw*) (- 0.0 (cos *cam-yaw*))) *yaw*)))
    (while (> d +pi+) (setq d (- d +two-pi+)))
    (while (< d (- 0.0 +pi+)) (setq d (+ d +two-pi+)))
    (setq *yaw* (+ *yaw* (* d (min 1.0 (* 8.0 dt)))))))

;; --- drawing the cast -----------------------------------------------------------

(defun emit-shadow ()
  ;; a soft dark pad on the highest solid top under the feet, scaled down
  ;; with altitude -- the landing aid every platformer owes its player
  (let ((top -99.0))
    (dotimes (i *nsolid*)
      (when (and (> (+ *px* 0.2) (aref *sx0* i)) (< (- *px* 0.2) (aref *sx1* i))
                 (> (+ *pz* 0.2) (aref *sz0* i)) (< (- *pz* 0.2) (aref *sz1* i))
                 (<= (aref *sy1* i) (+ *py* 0.05))
                 (> (aref *sy1* i) top))
        (setq top (aref *sy1* i))))
    (when (> top -50.0)
      (let ((k (max 0.3 (- 1.0 (* 0.16 (- *py* top))))))
        (set-color 0.16 0.28 0.16)
        (emit-box *px* (+ top 0.02) *pz*
                  (* 0.30 k) 0.008 (* 0.30 k) 0.0)))))

(defun emit-player (tm)
  ;; the robot explorer: teal chassis, white head with a wraparound visor,
  ;; an antenna and an orange field pack -- built from a dozen rotated boxes
  (set-origin *px* *py* *pz* *yaw*)
  (let* ((swing (if *grounded* (sin *run-phase*) 0.6))
         (leg (* 0.11 swing))
         (arm (if (= *state* 2) 0.0 (* -0.10 swing)))
         (army (if (= *state* 2) (+ 0.72 (* 0.02 (sin (* tm 6.0)))) 0.55)))
    ;; legs (charcoal), swinging front-to-back on the run cycle
    (set-color 0.23 0.25 0.31)
    (part leg 0.17 -0.10 0.08 0.17 0.07)
    (part (- 0.0 leg) 0.17 0.10 0.08 0.17 0.07)
    ;; the teal torso and its lighter chest plate
    (set-color 0.13 0.62 0.58)
    (part 0.0 0.50 0.0 0.17 0.19 0.14)
    (set-color 0.72 0.88 0.85)
    (part 0.15 0.52 0.0 0.03 0.10 0.08)
    ;; arms (charcoal) -- raised overhead once the course is clear
    (set-color 0.23 0.25 0.31)
    (part arm army -0.21 0.05 0.14 0.05)
    (part (- 0.0 arm) army 0.21 0.05 0.14 0.05)
    ;; the orange field pack on the back
    (set-color 0.95 0.55 0.16)
    (part -0.20 0.56 0.0 0.05 0.12 0.10)
    ;; the white head with its dark wraparound visor
    (set-color 0.90 0.93 0.95)
    (part 0.0 0.84 0.0 0.13 0.115 0.12)
    (set-color 0.09 0.11 0.17)
    (part 0.115 0.86 0.0 0.025 0.05 0.095)
    ;; the antenna: a thin stalk and its glowing tip
    (set-color 0.55 0.58 0.62)
    (part 0.0 1.0 0.0 0.012 0.05 0.012)
    (set-color 1.0 0.62 0.20)
    (part 0.0 1.07 0.0 0.032 0.032 0.032)))

(defun emit-enemy (i tm)
  (let ((yaw (if (> (aref *edir* i) 0.0) 0.0 +pi+)))
    (set-origin (aref *ex* i) 0.0 (aref *ez* i) yaw)
    (if (aref *ealive* i)
        (let ((sw (sin (+ (* tm 9.0) (* 1.7 i)))))
          ;; a plum-colored walker: shuffling feet, one wide cyclops eye
          ;; and a pair of stubby horns
          (set-color 0.56 0.34 0.66)
          (part 0.0 0.32 0.0 0.27 0.27 0.25)
          (set-color 0.30 0.18 0.38)
          (part (* 0.09 sw) 0.05 -0.13 0.09 0.05 0.08)
          (part (* -0.09 sw) 0.05 0.13 0.09 0.05 0.08)
          (part 0.0 0.62 -0.14 0.035 0.06 0.035)
          (part 0.0 0.62 0.14 0.035 0.06 0.035)
          (set-color 0.97 0.95 0.90)
          (part 0.25 0.40 0.0 0.028 0.065 0.11)
          (set-color 0.12 0.10 0.10)
          (part 0.272 0.39 0.0 0.012 0.032 0.032))
        (when (> (aref *esquash* i) 0.0)
          ;; freshly stomped: a fading pancake
          (set-color 0.44 0.27 0.52)
          (part 0.0 0.05 0.0 0.30 0.05 0.28)))))

(defun emit-coin (i tm)
  (unless (aref *ctaken* i)
    (set-origin (aref *coinx* i)
                (+ (aref *coiny* i) (* 0.07 (sin (+ (* tm 2.4) (* 0.9 i)))))
                (aref *coinz* i)
                (* tm 3.0))
    (set-color 1.0 0.82 0.25)
    (part 0.0 0.0 0.0 0.16 0.16 0.03)
    (set-color 1.0 0.92 0.55)
    (part 0.0 0.0 0.0 0.09 0.09 0.036)))

;; --- the frame --------------------------------------------------------------------

(defun draw (tm)
  (let ((w (canvas-width))
        (h (canvas-height)))
    (gl:viewport 0 0 (floor w) (floor h)))
  (gl:clear-color 0.52 0.74 0.98 1.0)
  (gl:clear (+ gl:+color-buffer-bit+ gl:+depth-buffer-bit+))
  (gl:use-program *prog*)
  (dotimes (i 16) (set-float i (aref *vp* i)))
  (gl-uniform-matrix4fv *u-vp*)
  (gl:uniform3f *u-eye* *eyex* *eyey* *eyez*)
  ;; the dynamic cast goes in the buffer right after the baked level
  (setq *v* *static-verts*)
  (when (= *state* 0)
    (emit-shadow))
  (emit-player tm)
  (dotimes (i *nenemy*) (emit-enemy i tm))
  (dotimes (i *ncoin*) (emit-coin i tm))
  (gl:bind-buffer gl:+array-buffer+ *buf*)
  (gl-upload-vertices (* *static-verts* 9) (* (- *v* *static-verts*) 9))
  (gl:bind-vertex-array *vao*)
  (gl:draw-arrays gl:+triangles+ 0 *v*))

(defun frame (tm)
  (when (or *pending-reset* (< *start-tm* 0.0))
    (setq *pending-reset* nil)
    (reset-game tm))
  (let ((dt (min 0.05 (max 0.0 (- tm *last-tm*)))))
    (setq *last-tm* tm)
    (setq *aspect* (/ (canvas-width) (canvas-height)))
    (setq *state-t* (+ *state-t* dt))
    (cond ((= *state* 0) (step-playing dt))
          ((= *state* 1) (step-dying dt))
          (t (step-clear dt)))
    (update-enemies dt)
    (update-camera dt)
    (draw tm)))

;; --- HUD taps ---------------------------------------------------------------------

(defun get-coins () *coins*)
(defun coin-total () *ncoin*)
(defun get-deaths () *deaths*)
(defun get-state () *state*)
;; the player's position, for tests and external instrumentation
(defun get-px () *px*)
(defun get-py () *py*)
(defun get-pz () *pz*)
(defun get-time ()
  (if (= *state* 2)
      *elapsed*
      (if (< *start-tm* 0.0) 0.0 (- *last-tm* *start-tm*))))

;; --- boot --------------------------------------------------------------------------
;; Runs inside _initialize, after the page has created the WebGL2 context:
;; build the pipeline, parse the stage and bake its mesh. The clock starts on
;; the first frame.

(setup-gl)
(parse-solids)
(parse-coins)
(parse-enemies)
(bake-static)
(respawn)

(rontolisp:wasm-export 'frame :params '(:float) :returns :void)
(rontolisp:wasm-export 'set-key :as "setKey" :params '(:int :int) :returns :void)
(rontolisp:wasm-export 'orbit :params '(:float :float) :returns :void)
(rontolisp:wasm-export 'zoom :params '(:float) :returns :void)
(rontolisp:wasm-export 'restart :params '() :returns :void)
(rontolisp:wasm-export 'get-coins :as "getCoins" :params '() :returns :int)
(rontolisp:wasm-export 'coin-total :as "coinTotal" :params '() :returns :int)
(rontolisp:wasm-export 'get-deaths :as "getDeaths" :params '() :returns :int)
(rontolisp:wasm-export 'get-state :as "getState" :params '() :returns :int)
(rontolisp:wasm-export 'get-time :as "getTime" :params '() :returns :float)
(rontolisp:wasm-export 'get-px :as "getPx" :params '() :returns :float)
(rontolisp:wasm-export 'get-py :as "getPy" :params '() :returns :float)
(rontolisp:wasm-export 'get-pz :as "getPz" :params '() :returns :float)
