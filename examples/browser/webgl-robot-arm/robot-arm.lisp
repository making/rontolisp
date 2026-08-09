;;;; robot-arm.lisp -- a 3-D robot arm that reaches for wherever you click,
;;;; solved by FABRIK inverse kinematics and animated along a minimum-jerk
;;;; trajectory, entirely in Lisp.
;;;;
;;;; Drag to orbit the camera, scroll to zoom, click (or tap) to set the
;;;; target: the page forwards pointer gestures through the exported
;;;; `pointer` / `orbit` / `zoom` functions. Every frame Lisp advances the
;;;; commanded hand position along the minimum-jerk profile
;;;; 10u^3 - 15u^4 + 6u^5 (zero velocity and acceleration at both ends --
;;;; the classic model of smooth human reaching motion), solves the joint
;;;; positions with damped-least-squares Jacobian iterations (or 3-D FABRIK
;;;; sweeps, or the closed-form 2R solution -- the HUD toggles the solver;
;;;; one file per solver, spliced in by compile-time load), and renders
;;;; the arm as lit
;;;; cylinders and spheres it tessellates itself -- plus the RGB = XYZ axis
;;;; arrows at the origin and a glowing sprite pass for the target ring and
;;;; the hand's trail. The camera matrices are rank-2 (4 4) arrays combined
;;;; with linalg:matmul; the click-ray unprojection and the lighting setup
;;;; are computed here too. JavaScript is the same one-line WebGL2 host
;;;; boundary as webgl-galaxy and webgl-heat3d, plus pointer-gesture
;;;; forwarding and the HUD.
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

;; The bulk-float staging path (see webgl-galaxy / webgl-cube): per-vertex
;; floats cannot cross into GPU memory one WASM value at a time, so the page
;; keeps two Float32Arrays -- one for the lit-triangle vertices (position,
;; normal, color = 9 floats), one for the glow sprites (position, tone,
;; size = 5 floats) -- plus a 16-float scratch for mat4 uniforms. Colors are
;; constant per mesh, so set-color latches the current color and set-vertex
;; stages position + normal + that color (functions cross the WASM boundary
;; with at most 7 parameters).
(rontolisp:wasm-import 'set-color
                       :from "gl"
                       :as "setColor"
                       :params '(:float :float :float)
                       :returns :void)
(rontolisp:wasm-import 'set-vertex
                       :from "gl"
                       :as "setVertex"
                       :params '(:int :float :float :float :float :float :float)
                       :returns :void)
(rontolisp:wasm-import 'set-sprite
                       :from "gl"
                       :as "setSprite"
                       :params '(:int :float :float :float :float :float)
                       :returns :void)
(rontolisp:wasm-import 'gl-upload-vertices
                       :from "gl"
                       :as "uploadVertices"
                       :params '(:int :int)
                       :returns :void)
(rontolisp:wasm-import 'gl-upload-sprites
                       :from "gl"
                       :as "uploadSprites"
                       :params '(:int)
                       :returns :void)
(rontolisp:wasm-import 'set-float
                       :from "gl"
                       :as "setFloat"
                       :params '(:int :float)
                       :returns :void)
(rontolisp:wasm-import 'gl-uniform-matrix4fv
                       :from "gl"
                       :as "uniformMatrix4fv"
                       :params '(:int)
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
(rontolisp:wasm-import 'atan2
                       :from "math"
                       :params '(:float :float)
                       :returns :float)
(rontolisp:wasm-import 'acos :from "math" :params '(:float) :returns :float)

(defconstant +pi+ 3.141592653589793)
(defconstant +two-pi+ 6.283185307179586)

;; --- shaders ----------------------------------------------------------------
;;
;; Two programs: lit triangles for the solid machine (arm, joints, pedestal,
;; axis arrows) and additive point sprites for the glow (target ring, trail).

(defconstant +solid-vs+
  "#version 300 es
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

(defconstant +solid-fs+
  "#version 300 es
precision mediump float;
in vec3 vN;
in vec3 vC;
in vec3 vW;
uniform vec3 uEye;
out vec4 color;
void main() {
  // one key light + a hemisphere ambient + blinn-phong spec + a cool rim
  vec3 n = normalize(vN);
  vec3 l = normalize(vec3(0.5, 0.85, 0.35));
  vec3 e = normalize(uEye - vW);
  vec3 h = normalize(l + e);
  float diff = max(dot(n, l), 0.0);
  float amb  = 0.30 + 0.14 * n.y;
  float spec = pow(max(dot(n, h), 0.0), 48.0) * 0.45;
  float rim  = pow(1.0 - max(dot(n, e), 0.0), 3.0) * 0.18;
  color = vec4(vC * (amb + 0.75 * diff) + vec3(spec) + vec3(0.35, 0.55, 0.9) * rim, 1.0);
}")

(defconstant +sprite-vs+
  "#version 300 es
layout(location=0) in vec3 aPos;    // world-space position from Lisp
layout(location=1) in float aTone;  // 0..1 palette position from Lisp
layout(location=2) in float aSize;
uniform mat4 uVP;
uniform float uDpr;
out float vTone;
void main() {
  vec4 p = uVP * vec4(aPos, 1.0);
  gl_Position = p;
  gl_PointSize = aSize * uDpr / max(p.w, 0.1);  // perspective-sized sprites
  vTone = aTone;
}")

(defconstant +sprite-fs+
  "#version 300 es
precision mediump float;
in float vTone;
out vec4 color;
// tone ~0.5 -> trail cyan, ~0.7 -> ember, 1 -> white-hot target.
vec3 tint(float h) {
  vec3 steel = vec3(0.30, 0.44, 0.85);
  vec3 cyan  = vec3(0.30, 0.95, 0.85);
  vec3 ember = vec3(1.00, 0.55, 0.18);
  vec3 hot   = vec3(1.00, 0.97, 0.90);
  return h < 0.45 ? mix(steel, cyan, h / 0.45)
       : h < 0.80 ? mix(cyan, ember, (h - 0.45) / 0.35)
       :            mix(ember, hot, (h - 0.80) / 0.20);
}
void main() {
  // a soft round sprite under additive blending
  float d = length(gl_PointCoord - 0.5) * 2.0;
  float a = exp(-3.0 * d * d) * (1.0 - smoothstep(0.8, 1.0, d));
  float glow = 0.22 + 0.78 * vTone;
  color = vec4(tint(vTone) * a * glow, a * glow);
}")

;; --- 4x4 matrix math: rank-2 arrays + the linalg package ----------------------
;;
;; The matrices are ordinary rank-2 (4 4) arrays in textbook (row, col)
;; convention, and VP = P x V is one linalg:matmul; upload-vp transposes
;; into the column-major order WebGL expects as it stages the floats.

(defconstant +half-fov+ 0.39269908169872414) ; pi/8: half the 45-degree fov

(defun mat4-perspective (aspect near far)
  (let* ((f (/ (cos +half-fov+) (sin +half-fov+)))
         (nf (/ 1.0 (- near far)))
         (m (linalg:full '(4 4) 0.0)))
    (setf (aref m 0 0) (/ f aspect))
    (setf (aref m 1 1) f)
    (setf (aref m 2 2) (* (+ far near) nf))
    (setf (aref m 2 3) (* 2.0 far near nf))
    (setf (aref m 3 2) -1.0)
    m))

;; --- the orbit camera ---------------------------------------------------------
;;
;; The eye circles the point +ctr+ at *radius*; drag gestures arrive through
;; the exported `orbit` (yaw/pitch deltas) and `zoom`. The camera basis
;; (*rx*.. right, *ux*.. up, *fx*.. forward) doubles as the click-ray
;; unprojector and the billboard frame for the target ring.

(defconstant +ctr-x+ 0.0)
(defconstant +ctr-y+ 0.42)
(defconstant +ctr-z+ 0.0)

(defvar *yaw* 0.65)
(defvar *pitch* 0.34)
(defvar *radius* 2.9)
(defvar *aspect* 1.0)
(defvar *ex* 0.0)
(defvar *ey* 0.0)
(defvar *ez* 0.0)
(defvar *fx* 0.0) ; forward (eye -> centre)
(defvar *fy* 0.0)
(defvar *fz* -1.0)
(defvar *rx* 1.0) ; right
(defvar *ry* 0.0)
(defvar *rz* 0.0)
(defvar *ux* 0.0) ; up
(defvar *uy* 1.0)
(defvar *uz* 0.0)
(defvar *vp* nil) ; the current view-projection matrix

(defun orbit (dx dy)
  ;; Exported: drag deltas, normalized by the canvas height.
  (setq *yaw* (- *yaw* (* 3.4 dx)))
  (let ((p (+ *pitch* (* 2.6 dy))))
    (setq *pitch* (cond ((< p -0.45) -0.45) ((> p 1.45) 1.45) (t p)))))

(defun zoom (dz)
  ;; Exported: scroll-wheel deltas.
  (let ((r (+ *radius* dz)))
    (setq *radius* (cond ((< r 1.6) 1.6) ((> r 5.5) 5.5) (t r)))))

(defun update-camera ()
  (let ((cp (cos *pitch*)) (sp (sin *pitch*)))
    (setq *ex* (+ +ctr-x+ (* *radius* cp (sin *yaw*))))
    (setq *ey* (+ +ctr-y+ (* *radius* sp)))
    (setq *ez* (+ +ctr-z+ (* *radius* cp (cos *yaw*)))))
  ;; forward = normalize(centre - eye)
  (let* ((fx (- +ctr-x+ *ex*))
         (fy (- +ctr-y+ *ey*))
         (fz (- +ctr-z+ *ez*))
         (fl (sqrt (+ (* fx fx) (* fy fy) (* fz fz)))))
    (setq *fx* (/ fx fl))
    (setq *fy* (/ fy fl))
    (setq *fz* (/ fz fl)))
  ;; right = normalize(cross(forward, world-up)); up = cross(right, forward)
  (let* ((rx (- 0.0 *fz*)) (rz *fx*) (rl (sqrt (+ (* rx rx) (* rz rz)))))
    (setq *rx* (/ rx rl))
    (setq *ry* 0.0)
    (setq *rz* (/ rz rl)))
  (setq *ux* (- (* *ry* *fz*) (* *rz* *fy*)))
  (setq *uy* (- (* *rz* *fx*) (* *rx* *fz*)))
  (setq *uz* (- (* *rx* *fy*) (* *ry* *fx*)))
  ;; the look-at view matrix (rows = camera right / up / -forward),
  ;; then VP = P x V as a rank-2 matrix product
  (let ((v (linalg:full '(4 4) 0.0)))
    (setf (aref v 0 0) *rx*)
    (setf (aref v 0 1) *ry*)
    (setf (aref v 0 2) *rz*)
    (setf (aref v 0 3) (- 0.0 (+ (* *rx* *ex*) (* *ry* *ey*) (* *rz* *ez*))))
    (setf (aref v 1 0) *ux*)
    (setf (aref v 1 1) *uy*)
    (setf (aref v 1 2) *uz*)
    (setf (aref v 1 3) (- 0.0 (+ (* *ux* *ex*) (* *uy* *ey*) (* *uz* *ez*))))
    (setf (aref v 2 0) (- 0.0 *fx*))
    (setf (aref v 2 1) (- 0.0 *fy*))
    (setf (aref v 2 2) (- 0.0 *fz*))
    (setf (aref v 2 3) (+ (* *fx* *ex*) (* *fy* *ey*) (* *fz* *ez*)))
    (setf (aref v 3 3) 1.0)
    (setq *vp* (linalg:matmul (mat4-perspective *aspect* 0.1 20.0) v))))

;; --- GL pipeline setup ------------------------------------------------------

(defvar *prog-solid* 0)
(defvar *prog-sprite* 0)
(defvar *vao-solid* 0)
(defvar *vao-sprite* 0)
(defvar *buf-solid* 0)
(defvar *buf-sprite* 0)
(defvar *u-vp-solid* 0)
(defvar *u-eye* 0)
(defvar *u-vp-sprite* 0)
(defvar *u-dpr* 0)

(defconstant +max-verts+ 8192)   ; lit-triangle vertex capacity
(defconstant +max-sprites+ 2048) ; glow sprite capacity

(defun setup-gl ()
  (setq *prog-solid* (gl:build-program +solid-vs+ +solid-fs+))
  (setq *u-vp-solid* (gl:get-uniform-location *prog-solid* "uVP"))
  (setq *u-eye* (gl:get-uniform-location *prog-solid* "uEye"))
  (setq *prog-sprite* (gl:build-program +sprite-vs+ +sprite-fs+))
  (setq *u-vp-sprite* (gl:get-uniform-location *prog-sprite* "uVP"))
  (setq *u-dpr* (gl:get-uniform-location *prog-sprite* "uDpr"))
  (gl:enable gl:+depth-test+)
  ;; solid VAO: position + normal + color, 36 bytes per vertex
  (setq *vao-solid* (gl:create-vertex-array))
  (gl:bind-vertex-array *vao-solid*)
  (setq *buf-solid* (gl:create-buffer))
  (gl:bind-buffer gl:+array-buffer+ *buf-solid*)
  (gl:buffer-data gl:+array-buffer+ (* +max-verts+ 36) gl:+dynamic-draw+)
  (gl:enable-vertex-attrib-array 0)
  (gl:vertex-attrib-pointer 0 3 gl:+float+ nil 36 0)
  (gl:enable-vertex-attrib-array 1)
  (gl:vertex-attrib-pointer 1 3 gl:+float+ nil 36 12)
  (gl:enable-vertex-attrib-array 2)
  (gl:vertex-attrib-pointer 2 3 gl:+float+ nil 36 24)
  ;; sprite VAO: position + tone + size, 20 bytes per sprite
  (setq *vao-sprite* (gl:create-vertex-array))
  (gl:bind-vertex-array *vao-sprite*)
  (setq *buf-sprite* (gl:create-buffer))
  (gl:bind-buffer gl:+array-buffer+ *buf-sprite*)
  (gl:buffer-data gl:+array-buffer+ (* +max-sprites+ 20) gl:+dynamic-draw+)
  (gl:enable-vertex-attrib-array 0)
  (gl:vertex-attrib-pointer 0 3 gl:+float+ nil 20 0)
  (gl:enable-vertex-attrib-array 1)
  (gl:vertex-attrib-pointer 1 1 gl:+float+ nil 20 12)
  (gl:enable-vertex-attrib-array 2)
  (gl:vertex-attrib-pointer 2 1 gl:+float+ nil 20 16)
  (gl:blend-func gl:+one+ gl:+one+))

;; --- mesh emitters ------------------------------------------------------------
;;
;; The machine is tessellated in Lisp every frame: tapered tubes (cylinders
;; and arrow cones), discs and spheres, all as world-space triangles with
;; per-vertex normals. A 12-slot sin/cos table drives every ring; the unit
;; sphere is precomputed once into a rank-2 (verts 3) array.

(defconstant +seg+ 12) ; ring segments
(defvar *ctab* nil)    ; cos table, +seg+ entries
(defvar *stab* nil)

(defconstant +sph-stacks+ 5)
(defconstant +sph-slices+ 8)
(defconstant +sph-verts+ 240) ; +sph-stacks+ * +sph-slices+ * 6
(defvar *sph* nil)            ; rank-2 (vertex . xyz) unit sphere

(defvar *v* 0)            ; solid vertex write cursor
(defvar *s* 0)            ; sprite write cursor
(defvar *static-verts* 0) ; axes + pedestal, uploaded once

(defun emit-v (x y z nx ny nz)
  ;; stages one vertex with the color latched by the last set-color call
  (set-vertex *v* x y z nx ny nz)
  (setq *v* (+ *v* 1)))

(defun emit-s (x y z tone size)
  (set-sprite *s* x y z tone size)
  (setq *s* (+ *s* 1)))

;; A pair of unit vectors perpendicular to the given axis, into *pu*/*pv*.
(defvar *pux* 0.0)
(defvar *puy* 0.0)
(defvar *puz* 0.0)
(defvar *pvx* 0.0)
(defvar *pvy* 0.0)
(defvar *pvz* 0.0)

(defun perp-basis (tx ty tz)
  (let* ((ay (if (< ty 0.0) (- 0.0 ty) ty))
         ;; helper axis least aligned with t: world-up unless t is vertical
         (hx (if (< ay 0.9) 0.0 1.0))
         (hy (if (< ay 0.9) 1.0 0.0))
         ;; u = normalize(cross(t, h)) with h = (hx hy 0)
         (cx (- 0.0 (* tz hy)))
         (cy (* tz hx))
         (cz (- (* tx hy) (* ty hx)))
         (cl (sqrt (+ (* cx cx) (* cy cy) (* cz cz)))))
    (setq *pux* (/ cx cl))
    (setq *puy* (/ cy cl))
    (setq *puz* (/ cz cl))
    (setq *pvx* (- (* ty *puz*) (* tz *puy*)))
    (setq *pvy* (- (* tz *pux*) (* tx *puz*)))
    (setq *pvz* (- (* tx *puy*) (* ty *pux*)))))

;; Tube radii travel through globals (set-radii) rather than parameters,
;; keeping every function within the WASM backend's 7-parameter limit.
(defvar *r-a* 0.0) ; tube start / disc radius
(defvar *r-b* 0.0) ; tube end radius (0 = cone)

(defun set-radii (a b)
  (setq *r-a* a)
  (setq *r-b* b))

(defun emit-tube (x0 y0 z0 x1 y1 z1)
  ;; A tube from p0 (radius *r-a*) to p1 (radius *r-b*); *r-b* = 0 makes a
  ;; cone. Normals get the proper slant for the taper.
  (let* ((r0 *r-a*)
         (r1 *r-b*)
         (ax (- x1 x0))
         (ay (- y1 y0))
         (az (- z1 z0))
         (len (sqrt (+ (* ax ax) (* ay ay) (* az az))))
         (l (if (< len 0.000001) 0.000001 len))
         (tx (/ ax l))
         (ty (/ ay l))
         (tz (/ az l))
         (k (/ (- r0 r1) l)) ; radius change per unit length
         (nf (/ 1.0 (sqrt (+ 1.0 (* k k))))))
    (perp-basis tx ty tz)
    (let ((ux *pux*) (uy *puy*) (uz *puz*) (vx *pvx*) (vy *pvy*) (vz *pvz*))
      (dotimes (seg +seg+)
        (let* ((s2 (mod (+ seg 1) +seg+))
               (ca (aref *ctab* seg))
               (sa (aref *stab* seg))
               (cb2 (aref *ctab* s2))
               (sb2 (aref *stab* s2))
               ;; the two radial unit directions of this quad
               (dax (+ (* ca ux) (* sa vx)))
               (day (+ (* ca uy) (* sa vy)))
               (daz (+ (* ca uz) (* sa vz)))
               (dbx (+ (* cb2 ux) (* sb2 vx)))
               (dby (+ (* cb2 uy) (* sb2 vy)))
               (dbz (+ (* cb2 uz) (* sb2 vz)))
               ;; slanted normals: radial + k * axis, normalized
               (nax (* nf (+ dax (* k tx))))
               (nay (* nf (+ day (* k ty))))
               (naz (* nf (+ daz (* k tz))))
               (nbx (* nf (+ dbx (* k tx))))
               (nby (* nf (+ dby (* k ty))))
               (nbz (* nf (+ dbz (* k tz)))))
          (emit-v (+ x0 (* r0 dax)) (+ y0 (* r0 day)) (+ z0 (* r0 daz)) nax nay
                  naz)
          (emit-v (+ x0 (* r0 dbx)) (+ y0 (* r0 dby)) (+ z0 (* r0 dbz)) nbx nby
                  nbz)
          (emit-v (+ x1 (* r1 dbx)) (+ y1 (* r1 dby)) (+ z1 (* r1 dbz)) nbx nby
                  nbz)
          (emit-v (+ x0 (* r0 dax)) (+ y0 (* r0 day)) (+ z0 (* r0 daz)) nax nay
                  naz)
          (emit-v (+ x1 (* r1 dbx)) (+ y1 (* r1 dby)) (+ z1 (* r1 dbz)) nbx nby
                  nbz)
          (emit-v (+ x1 (* r1 dax)) (+ y1 (* r1 day)) (+ z1 (* r1 daz)) nax nay
                  naz))))))

(defun emit-disc (cx cy cz nx ny nz)
  ;; A filled circle of radius *r-a* at c with the given face normal.
  (perp-basis nx ny nz)
  (let ((r *r-a*)
        (ux *pux*)
        (uy *puy*)
        (uz *puz*)
        (vx *pvx*)
        (vy *pvy*)
        (vz *pvz*))
    (dotimes (seg +seg+)
      (let* ((s2 (mod (+ seg 1) +seg+))
             (ca (aref *ctab* seg))
             (sa (aref *stab* seg))
             (cb2 (aref *ctab* s2))
             (sb2 (aref *stab* s2)))
        (emit-v cx cy cz nx ny nz)
        (emit-v (+ cx (* r (+ (* ca ux) (* sa vx))))
                (+ cy (* r (+ (* ca uy) (* sa vy))))
                (+ cz (* r (+ (* ca uz) (* sa vz)))) nx ny nz)
        (emit-v (+ cx (* r (+ (* cb2 ux) (* sb2 vx))))
                (+ cy (* r (+ (* cb2 uy) (* sb2 vy))))
                (+ cz (* r (+ (* cb2 uz) (* sb2 vz)))) nx ny nz)))))

(defun emit-sphere (cx cy cz r)
  (dotimes (i +sph-verts+)
    (let ((nx (aref *sph* i 0)) (ny (aref *sph* i 1)) (nz (aref *sph* i 2)))
      (emit-v (+ cx (* r nx)) (+ cy (* r ny)) (+ cz (* r nz)) nx ny nz))))

(defun %sph-write (w b s)
  (let* ((th (/ (* +pi+ b) +sph-stacks+))
         (ph (/ (* +two-pi+ s) +sph-slices+))
         (sth (sin th)))
    (setf (aref *sph* w 0) (* sth (cos ph)))
    (setf (aref *sph* w 1) (cos th))
    (setf (aref *sph* w 2) (* sth (sin ph)))))

(defun build-tables ()
  (setq *ctab* (make-array +seg+ :initial-element 0.0))
  (setq *stab* (make-array +seg+ :initial-element 0.0))
  (dotimes (i +seg+)
    (let ((a (/ (* +two-pi+ i) +seg+)))
      (setf (aref *ctab* i) (cos a))
      (setf (aref *stab* i) (sin a))))
  ;; the unit sphere as a triangle list (quads; the pole slivers degenerate)
  (setq *sph* (make-array (list +sph-verts+ 3) :initial-element 0.0))
  (let ((w 0))
    (dotimes (b +sph-stacks+)
      (dotimes (s +sph-slices+)
        (%sph-write w b s)
        (%sph-write (+ w 1) (+ b 1) s)
        (%sph-write (+ w 2) (+ b 1) (+ s 1))
        (%sph-write (+ w 3) b s)
        (%sph-write (+ w 4) (+ b 1) (+ s 1))
        (%sph-write (+ w 5) b (+ s 1))
        (setq w (+ w 6))))))

;; --- the static scene: XYZ axis arrows + the pedestal -------------------------

(defun emit-arrow (dx dy dz cr cg cb)
  ;; A unit-axis arrow from the origin: shaft, cone base cap, cone tip.
  (set-color cr cg cb)
  (let ((s 0.52)    ; shaft length
        (tip 0.15)) ; cone length
    (set-radii 0.011 0.011)
    (emit-tube 0.0 0.0 0.0 (* dx s) (* dy s) (* dz s))
    (set-radii 0.034 0.0)
    (emit-disc (* dx s) (* dy s) (* dz s) (- 0.0 dx) (- 0.0 dy) (- 0.0 dz))
    (emit-tube (* dx s) (* dy s) (* dz s) (* dx (+ s tip)) (* dy (+ s tip))
               (* dz (+ s tip)))))

(defun emit-static-scene ()
  ;; the pedestal column under the base joint
  (set-color 0.20 0.23 0.31)
  (set-radii 0.13 0.095)
  (emit-tube 0.0 -0.30 0.0 0.0 -0.02 0.0)
  (set-color 0.16 0.18 0.25)
  (emit-disc 0.0 -0.30 0.0 0.0 -1.0 0.0)
  ;; the RGB = XYZ axis arrows at the origin
  (emit-arrow 1.0 0.0 0.0 0.90 0.25 0.31)  ; +X red
  (emit-arrow 0.0 1.0 0.0 0.30 0.82 0.42)  ; +Y green
  (emit-arrow 0.0 0.0 1.0 0.32 0.53 0.96)) ; +Z blue

;; --- the arm ------------------------------------------------------------------
;;
;; The base joint sits at the origin (where the axis arrows are). The chain
;; is *links* links whose lengths taper geometrically and always sum to
;; +reach+, so every link count has the same workspace.

(defconstant +reach+ 1.25)
(defconstant +tool+ 0.115) ; wrist -> grasp point (the gripper)
(defconstant +trail+ 90)

;; The gripper rides the chain as one extra "tool" link: FABRIK solves the
;; wrist AND the grasp point (the TCP, joint *links* + 1), so the fingers
;; close exactly on the clicked goal and the approach direction comes out
;; of the solver for free.
(defvar *links* 0)
(defvar *len* nil) ; rank-1 array: link lengths + the tool
(defvar *jx* nil)  ; joint positions, *links* + 2 entries;
(defvar *jy* nil)  ; joint 0 is the base, joint *links*
(defvar *jz* nil)  ; the wrist, joint *links*+1 the TCP

;; The minimum-jerk trajectory state: the commanded hand position
;; (*tx* *ty* *tz*) travels from (*sx* ..) to the goal (*gx* ..) over *dur*
;; seconds starting at *t0*.
(defvar *sx* 0.0)
(defvar *sy* 0.0)
(defvar *sz* 0.0)
(defvar *gx* 0.0)
(defvar *gy* 0.0)
(defvar *gz* 0.0)
(defvar *tx* 0.0)
(defvar *ty* 0.0)
(defvar *tz* 0.0)
(defvar *t0* -10.0)
(defvar *dur* 1.0)

;; The latest click, in clip coordinates; picked up by the next frame (which
;; knows the camera and the time).
(defvar *click-x* 0.0)
(defvar *click-y* 0.0)
(defvar *clicked* nil)

;; The hand's recent path, a ring buffer drawn as a fading trail.
(defvar *trail-x* nil)
(defvar *trail-y* nil)
(defvar *trail-z* nil)
(defvar *trail-head* 0)
(defvar *trail-count* 0)

(defun init (n)
  (build-tables)
  (setq *links* n)
  (setq *len* (make-array (+ n 1) :initial-element 0.0))
  (setq *jx* (make-array (+ n 2) :initial-element 0.0))
  (setq *jy* (make-array (+ n 2) :initial-element 0.0))
  (setq *jz* (make-array (+ n 2) :initial-element 0.0))
  ;; each link is 0.82x the previous, normalized to the fixed total reach;
  ;; the rigid tool link (wrist -> grasp point) rides at the end
  (let ((sum 0.0) (l 1.0))
    (dotimes (i n)
      (setf (aref *len* i) l)
      (setq sum (+ sum l))
      (setq l (* l 0.82)))
    (dotimes (i n) (setf (aref *len* i) (* (aref *len* i) (/ +reach+ sum)))))
  (setf (aref *len* n) +tool+)
  ;; the analytic solver's elbow: split the chain (tool included) into two
  ;; rigid groups at the joint that best balances their lengths
  (let ((total 0.0) (best 1) (bestd 999.0) (sa 0.0))
    (dotimes (i (+ n 1)) (setq total (+ total (aref *len* i))))
    (do ((k 1 (+ k 1)))
        ((> k (- n 1)))
      (setq sa (+ sa (aref *len* (- k 1))))
      (let ((dd (- (* 2.0 sa) total)))
        (when (< dd 0.0) (setq dd (- 0.0 dd)))
        (when (< dd bestd)
          (setq bestd dd)
          (setq best k))))
    (setq *split* best)
    (setq *ga* 0.0)
    (dotimes (i best) (setq *ga* (+ *ga* (aref *len* i))))
    (setq *gb* (- total *ga*)))
  ;; initial pose: straight up, grasp point at full extension
  (dotimes (i (+ n 1))
    (setf (aref *jy* (+ i 1)) (+ (aref *jy* i) (aref *len* i))))
  ;; idle until the first click: the trajectory is already at its goal
  (setq *tx* 0.0)
  (setq *ty* (aref *jy* (+ n 1)))
  (setq *tz* 0.0)
  (setq *sx* *tx*)
  (setq *sy* *ty*)
  (setq *sz* *tz*)
  (setq *gx* *tx*)
  (setq *gy* *ty*)
  (setq *gz* *tz*)
  (setq *t0* -10.0)
  (setq *dur* 1.0)
  (setq *trail-x* (make-array +trail+ :initial-element 0.0))
  (setq *trail-y* (make-array +trail+ :initial-element 0.0))
  (setq *trail-z* (make-array +trail+ :initial-element 0.0))
  (setq *trail-head* 0)
  (setq *trail-count* 0)
  ;; bake the axes and the pedestal into the front of the vertex buffer
  (setq *v* 0)
  (emit-static-scene)
  (setq *static-verts* *v*)
  (gl:bind-buffer gl:+array-buffer+ *buf-solid*)
  (gl-upload-vertices 0 (* *static-verts* 9)))

(defun pointer (cx cy)
  ;; Exported: the page calls this on every click/tap with clip-space coords.
  (setq *click-x* cx)
  (setq *click-y* cy)
  (setq *clicked* t))

;; --- the minimum-jerk trajectory ----------------------------------------------

(defun min-jerk (u)
  ;; The minimum-jerk position profile s(u) = 10u^3 - 15u^4 + 6u^5 for
  ;; u in [0, 1]: the unique 5th-order polynomial with zero velocity and
  ;; zero acceleration at both ends, i.e. the trajectory minimizing the
  ;; integral of squared jerk (Flash & Hogan 1985).
  (* u u u (+ 10.0 (* u (+ -15.0 (* u 6.0))))))

(defun set-goal (mx my mz tm)
  ;; Clamp the requested point into the sphere shell the arm can reach (and
  ;; above the pedestal), then restart the min-jerk clock. A click mid-flight
  ;; restarts the profile from the currently commanded point, so the hand
  ;; never jumps.
  (when (< my 0.05) (setq my 0.05))
  (let* ((d (sqrt (+ (* mx mx) (* my my) (* mz mz))))
         (lo (* 0.18 +reach+))
         (hi (* 0.985 (+ +reach+ +tool+))))
    (when (< d 0.0001)
      (setq my 1.0)
      (setq d 1.0))
    (let ((r (cond ((< d lo) (/ lo d)) ((> d hi) (/ hi d)) (t 1.0))))
      (setq *sx* *tx*)
      (setq *sy* *ty*)
      (setq *sz* *tz*)
      (setq *gx* (* mx r))
      (setq *gy* (* my r))
      (setq *gz* (* mz r))
      (setq *t0* tm)
      ;; duration grows with distance: quick nearby hops, ~1.5 s across
      (let* ((ex (- *gx* *sx*))
             (ey (- *gy* *sy*))
             (ez (- *gz* *sz*))
             (dist (sqrt (+ (* ex ex) (* ey ey) (* ez ez)))))
        (setq *dur* (+ 0.45 (* 0.55 dist)))))))

(defun set-goal-from-click (ccx ccy tm)
  ;; Unproject the click: a ray from the eye through the clip point,
  ;; intersected with the plane through the orbit centre facing the camera.
  ;; Everything needed (eye, camera basis, fov) is already in globals.
  (let* ((th (/ (sin +half-fov+) (cos +half-fov+)))
         (hx (* ccx th *aspect*))
         (hy (* ccy th))
         (dx (+ *fx* (* hx *rx*) (* hy *ux*)))
         (dy (+ *fy* (* hx *ry*) (* hy *uy*)))
         (dz (+ *fz* (* hx *rz*) (* hy *uz*)))
         (denom (+ (* dx *fx*) (* dy *fy*) (* dz *fz*)))
         (tt
          (/ (+ (* (- +ctr-x+ *ex*) *fx*) (* (- +ctr-y+ *ey*) *fy*)
                (* (- +ctr-z+ *ez*) *fz*)) denom)))
    (set-goal (+ *ex* (* tt dx)) (+ *ey* (* tt dy)) (+ *ez* (* tt dz)) tm)))

(defun update-target (tm)
  ;; Advance the commanded hand position along the min-jerk profile.
  (let ((u (/ (- tm *t0*) *dur*)))
    (cond ((>= u 1.0)
           (setq *tx* *gx*)
           (setq *ty* *gy*)
           (setq *tz* *gz*))
          ((<= u 0.0)
           (setq *tx* *sx*)
           (setq *ty* *sy*)
           (setq *tz* *sz*))
          (t (let ((s (min-jerk u)))
               (setq *tx* (+ *sx* (* s (- *gx* *sx*))))
               (setq *ty* (+ *sy* (* s (- *gy* *sy*))))
               (setq *tz* (+ *sz* (* s (- *gz* *sz*)))))))))

;; --- inverse kinematics (in 3-D): Jacobian DLS, FABRIK, or analytic ------------
;;
;; Three solvers over the same joint-position state, switchable from the
;; HUD. Each lives in its own file, spliced in here at compile time by the
;; literal top-level (load ...) below -- paths resolve relative to this
;; file, and the compiled .wasm sees the defuns natively:
;;
;;   ik-jacobian.lisp  0 = damped least squares over the position Jacobian
;;                         (linalg:matmul / transpose / solve, iterative)
;;   ik-fabrik.lisp    1 = FABRIK, the geometric method (no matrices)
;;   ik-analytic.lisp  2 = the closed form (atan2 + the law of cosines)
;;                         + forward kinematics through 4x4 homogeneous
;;                         transforms (linalg:matmul, exact, no iteration)

(defvar *solver* 0) ; 0 = jacobian, 1 = FABRIK, 2 = analytic

(defvar *split* 1) ; the analytic elbow: joint index
(defvar *ga* 0.0)  ; upper-group length (base -> elbow)
(defvar *gb* 0.0)  ; lower-group length (elbow -> grasp)

(defun set-solver (s)
  ;; Exported: the HUD's solver selector.
  (setq *solver* s))

(defun place (i from len)
  ;; Move joint i to distance len from joint `from`, preserving direction.
  ;; Shared by both iterative solvers (FABRIK sweeps, DLS re-normalization).
  (let* ((dx (- (aref *jx* i) (aref *jx* from)))
         (dy (- (aref *jy* i) (aref *jy* from)))
         (dz (- (aref *jz* i) (aref *jz* from)))
         (d (sqrt (+ (* dx dx) (* dy dy) (* dz dz))))
         (r (/ len (if (< d 0.000001) 0.000001 d))))
    (setf (aref *jx* i) (+ (aref *jx* from) (* dx r)))
    (setf (aref *jy* i) (+ (aref *jy* from) (* dy r)))
    (setf (aref *jz* i) (+ (aref *jz* from) (* dz r)))))

(load "ik-jacobian.lisp")
(load "ik-fabrik.lisp")
(load "ik-analytic.lisp")

(defun solve-ik ()
  (cond ((= *solver* 0) (solve-ik-jacobian))
        ((= *solver* 1) (solve-ik-fabrik))
        (t (solve-ik-analytic))))

;; The HUD polls this: how far the grasp point still is from the goal.
(defun ik-error ()
  (let* ((tip (+ *links* 1))
         (dx (- (aref *jx* tip) *gx*))
         (dy (- (aref *jy* tip) *gy*))
         (dz (- (aref *jz* tip) *gz*)))
    (sqrt (+ (* dx dx) (* dy dy) (* dz dz)))))

;; --- rendering ------------------------------------------------------------------

(defun link-radius (i)
  ;; the arm tapers from shoulder to wrist
  (* 0.052 (- 1.0 (* 0.55 (/ (* 1.0 i) *links*)))))

(defun push-trail ()
  (let ((tip (+ *links* 1)))
    (setf (aref *trail-x* *trail-head*) (aref *jx* tip))
    (setf (aref *trail-y* *trail-head*) (aref *jy* tip))
    (setf (aref *trail-z* *trail-head*) (aref *jz* tip)))
  (setq *trail-head* (mod (+ *trail-head* 1) +trail+))
  (when (< *trail-count* +trail+) (setq *trail-count* (+ *trail-count* 1))))

;; --- the gripper ---------------------------------------------------------------
;;
;; Three two-phalanx fingers, 120 degrees apart around the tool axis. The
;; grip state *grip* eases between 0 (open, while the hand is flying) and 1
;; (closed): the finger segments pivot from splayed-out angles to angles
;; that make the tips meet exactly at the grasp point, +tool+ ahead of the
;; wrist -- the very point FABRIK pins to the goal.

(defvar *grip* 1.0) ; 0 open .. 1 closed
(defvar *last-tm* 0.0)

(defun update-grip (tm)
  ;; open while the min-jerk flight is in progress, close on arrival;
  ;; eased with a time-based rate so the motion is frame-rate independent
  (let* ((dt0 (- tm *last-tm*))
         (dt (cond ((< dt0 0.0) 0.0) ((> dt0 0.05) 0.05) (t dt0)))
         (target (if (>= (/ (- tm *t0*) *dur*) 1.0) 1.0 0.0))
         (k (* dt (if (> target *grip*) 10.0 7.0)))
         (kk (if (> k 1.0) 1.0 k)))
    (setq *grip* (+ *grip* (* kk (- target *grip*))))
    (setq *last-tm* tm)))

(defun emit-gripper ()
  (let* ((n *links*)
         (wx (aref *jx* n)) ; the wrist
         (wy (aref *jy* n))
         (wz (aref *jz* n))
         ;; approach axis: the solved tool link, wrist -> grasp point
         (ax (/ (- (aref *jx* (+ n 1)) wx) +tool+))
         (ay (/ (- (aref *jy* (+ n 1)) wy) +tool+))
         (az (/ (- (aref *jz* (+ n 1)) wz) +tool+))
         ;; finger phalanx angles from the axis: splayed when open, curled
         ;; so the tips meet on the axis at +tool+ when closed
         (a1 (- 0.70 (* 0.58 *grip*)))
         (a2 (- 0.35 (* 1.15 *grip*)))
         (c1 (cos a1))
         (s1 (sin a1))
         (c2 (cos a2))
         (s2 (sin a2)))
    (perp-basis ax ay az)
    (let ((ux *pux*)
          (uy *puy*)
          (uz *puz*) ; capture: emit-tube reuses
          (vx *pvx*)
          (vy *pvy*)
          (vz *pvz*)) ; the perp-basis globals
      ;; the wrist ball and the palm
      (set-color 0.30 0.34 0.44)
      (emit-sphere wx wy wz (* 1.35 (link-radius n)))
      (set-radii 0.030 0.036)
      (emit-tube wx wy wz (+ wx (* 0.035 ax)) (+ wy (* 0.035 ay))
                 (+ wz (* 0.035 az)))
      ;; three fingers, at ring-table angles 0, 120, 240 degrees
      (dotimes (f 3)
        (let* ((ca (aref *ctab* (* f 4)))
               (sa (aref *stab* (* f 4)))
               (rkx (+ (* ca ux) (* sa vx)))
               (rky (+ (* ca uy) (* sa vy)))
               (rkz (+ (* ca uz) (* sa vz)))
               ;; phalanx directions: rotate the axis toward the radial
               (d1x (+ (* c1 ax) (* s1 rkx)))
               (d1y (+ (* c1 ay) (* s1 rky)))
               (d1z (+ (* c1 az) (* s1 rkz)))
               (d2x (+ (* c2 ax) (* s2 rkx)))
               (d2y (+ (* c2 ay) (* s2 rky)))
               (d2z (+ (* c2 az) (* s2 rkz)))
               ;; knuckle on the palm rim, then two phalanges
               (bx (+ wx (* 0.035 ax) (* 0.030 rkx)))
               (by (+ wy (* 0.035 ay) (* 0.030 rky)))
               (bz (+ wz (* 0.035 az) (* 0.030 rkz)))
               (kx (+ bx (* 0.05 d1x)))
               (ky (+ by (* 0.05 d1y)))
               (kz (+ bz (* 0.05 d1z)))
               (px (+ kx (* 0.05 d2x)))
               (py (+ ky (* 0.05 d2y)))
               (pz (+ kz (* 0.05 d2z))))
          (set-color 0.72 0.76 0.84)
          (set-radii 0.012 0.009)
          (emit-tube bx by bz kx ky kz)
          (set-radii 0.009 0.006)
          (emit-tube kx ky kz px py pz)
          (set-color 0.30 0.34 0.44)
          (emit-sphere kx ky kz 0.011)
          (set-color 1.0 0.52 0.16)
          (emit-sphere px py pz 0.008))))))

(defun emit-arm ()
  ;; tapered tube per link, dark sphere per joint, the gripper at the wrist
  (set-color 0.72 0.76 0.84)
  (dotimes (i *links*)
    (set-radii (link-radius i) (link-radius (+ i 1)))
    (emit-tube (aref *jx* i) (aref *jy* i) (aref *jz* i) (aref *jx* (+ i 1))
               (aref *jy* (+ i 1)) (aref *jz* (+ i 1))))
  (set-color 0.30 0.34 0.44)
  (dotimes (i *links*)
    (emit-sphere (aref *jx* i) (aref *jy* i) (aref *jz* i)
                 (* 1.45 (link-radius i))))
  (emit-gripper))

(defun emit-glow (tm)
  ;; the trail: the hand's recent path, fading with age
  (dotimes (m *trail-count*)
    (let* ((idx (mod (+ (- *trail-head* *trail-count*) m +trail+) +trail+))
           (age (/ (* 1.0 (+ m 1)) *trail-count*)) ; 0 oldest .. 1 newest
           (fade (* age age)))
      (emit-s (aref *trail-x* idx) (aref *trail-y* idx) (aref *trail-z* idx)
              (+ 0.50 (* 0.20 fade)) (+ 5.0 (* 12.0 fade)))))
  ;; the goal: a slowly turning, pulsing ring billboarded on the camera basis
  (let ((r (+ 0.045 (* 0.010 (sin (* tm 5.0))))))
    (dotimes (m 36)
      (let* ((a (+ (* tm 0.8) (/ (* +two-pi+ m) 36))) (ca (cos a)) (sa (sin a)))
        (emit-s (+ *gx* (* r (+ (* ca *rx*) (* sa *ux*))))
                (+ *gy* (* r (+ (* ca *ry*) (* sa *uy*))))
                (+ *gz* (* r (+ (* ca *rz*) (* sa *uz*)))) 1.0 9.0)))
    (emit-s *gx* *gy* *gz* 1.0 14.0)))

(defun upload-vp (loc)
  ;; WebGL wants column-major: element (row, col) lands at row + col*4
  (dotimes (c 4) (dotimes (r 4) (set-float (+ r (* c 4)) (aref *vp* r c))))
  (gl-uniform-matrix4fv loc))

(defun draw (tm)
  (update-camera)
  (let ((w (canvas-width)) (h (canvas-height)))
    (gl:viewport 0 0 (floor w) (floor h)))
  (gl:clear-color 0.012 0.016 0.045 1.0)
  (gl:clear (+ gl:+color-buffer-bit+ gl:+depth-buffer-bit+))
  ;; solid pass: the machine, lit and depth-tested
  (gl:disable gl:+blend+)
  (gl:depth-mask t)
  (gl:use-program *prog-solid*)
  (upload-vp *u-vp-solid*)
  (gl:uniform3f *u-eye* *ex* *ey* *ez*)
  (setq *v* *static-verts*)
  (emit-arm)
  (gl:bind-buffer gl:+array-buffer+ *buf-solid*)
  (gl-upload-vertices (* *static-verts* 9) (* (- *v* *static-verts*) 9))
  (gl:bind-vertex-array *vao-solid*)
  (gl:draw-arrays gl:+triangles+ 0 *v*)
  ;; glow pass: additive sprites that read depth but do not write it
  (gl:enable gl:+blend+)
  (gl:depth-mask nil)
  (gl:use-program *prog-sprite*)
  (upload-vp *u-vp-sprite*)
  (gl:uniform1f *u-dpr* (device-pixel-ratio))
  (setq *s* 0)
  (emit-glow tm)
  (gl:bind-buffer gl:+array-buffer+ *buf-sprite*)
  (gl-upload-sprites (* *s* 5))
  (gl:bind-vertex-array *vao-sprite*)
  (gl:draw-arrays gl:+points+ 0 *s*))

(defun frame (tm)
  (setq *aspect* (/ (canvas-width) (canvas-height)))
  ;; a pending click is unprojected with this frame's camera and time
  (when *clicked*
    (setq *clicked* nil)
    (set-goal-from-click *click-x* *click-y* tm))
  (update-target tm)
  (update-grip tm)
  (solve-ik)
  (push-trail)
  (draw tm))

;; Build the pipeline at load time: this runs inside _initialize, after the
;; page has created the WebGL2 context and instantiated the module.
(setup-gl)

(rontolisp:wasm-export 'init :params '(:int) :returns :void)
(rontolisp:wasm-export 'frame :params '(:float) :returns :void)
(rontolisp:wasm-export 'pointer :params '(:float :float) :returns :void)
(rontolisp:wasm-export 'orbit :params '(:float :float) :returns :void)
(rontolisp:wasm-export 'zoom :params '(:float) :returns :void)
(rontolisp:wasm-export 'set-solver
                       :as "setSolver"
                       :params '(:int)
                       :returns :void)
(rontolisp:wasm-export 'ik-error :as "ikError" :params '() :returns :float)
