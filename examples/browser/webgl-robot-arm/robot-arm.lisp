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
(rontolisp:wasm-import 'stage-color
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

;; --- points are arrays, and arithmetic on them is linalg ----------------------
;;
;; Not one coordinate in this program is a scalar variable: a point, a
;; direction, a colour and a camera axis are all float vectors, the joint chain
;; is a rank-2 (joint xyz) array, and combining them is a `linalg` call rather
;; than three lines of the same expression with x, y and z spelled into it.
;; That is what makes the matrix steps below -- the look-at, the position
;; Jacobian, the damped normal equations, the analytic solver's forward
;; kinematics -- READ as the matrix expressions they are.
;;
;; The one place that stays scalar is the innermost tessellation loop: it stages
;; six floats a vertex through set-vertex, several thousand times a frame, and a
;; fresh 3-vector per corner would be an allocation per float staged. State is
;; vectors; the loop that turns state into host calls is not.

(defun vec3 (x y z) (linalg:from-list (list x y z)))

;; The one vector operation linalg does not carry (numpy spells it np.cross).
(defun cross (a b)
  (vec3 (- (* (aref a 1) (aref b 2)) (* (aref a 2) (aref b 1)))
        (- (* (aref a 2) (aref b 0)) (* (aref a 0) (aref b 2)))
        (- (* (aref a 0) (aref b 1)) (* (aref a 1) (aref b 0)))))

(defun normalized (v)
  (let ((n (linalg:norm v)))
    (linalg:mul v (/ 1.0 (if (< n 0.000001) 0.000001 n)))))

;; Writing a 3-vector back into row I of A -- the inverse of linalg:row, which
;; answers a copy.
(defun set-row (a i p) (dotimes (k 3) (setf (aref a i k) (aref p k))))

;; --- 4x4 matrix math: rank-2 arrays + the linalg package ----------------------
;;
;; The matrices are ordinary rank-2 (4 4) arrays in textbook (row, col)
;; convention, and VP = P x V is one linalg:matmul; upload-vp transposes
;; into the column-major order WebGL expects as it stages the floats.

(defconstant +half-fov+ 0.39269908169872414) ; pi/8: half the 45-degree fov

(defun mat4-perspective (aspect near far)
  (let* ((f (/ (cos +half-fov+) (sin +half-fov+)))
         (nf (/ 1.0 (- near far)))
         (m (linalg:zeros '(4 4))))
    (setf (aref m 0 0) (/ f aspect))
    (setf (aref m 1 1) f)
    (setf (aref m 2 2) (* (+ far near) nf))
    (setf (aref m 2 3) (* 2.0 far near nf))
    (setf (aref m 3 2) -1.0)
    m))

;; --- the orbit camera ---------------------------------------------------------
;;
;; The eye circles *centre* at *radius*; drag gestures arrive through the
;; exported `orbit` (yaw/pitch deltas) and `zoom`. The camera basis is one (3 3)
;; matrix whose rows are right, up and forward -- and it is the same three rows
;; that unproject a click and billboard the target ring, so there is exactly one
;; place the camera's orientation lives.

(defvar *centre* nil)

(defvar *yaw* 0.65)
(defvar *pitch* 0.34)
(defvar *radius* 2.9)
(defvar *aspect* 1.0)
(defvar *eye* nil)   ; a 3-vector
(defvar *basis* nil) ; rank-2 (3 3): rows right, up, forward
(defvar *vp* nil)    ; the current view-projection matrix

(defun cam-right () (linalg:row *basis* 0))

(defun cam-up () (linalg:row *basis* 1))

(defun cam-forward () (linalg:row *basis* 2))

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
  (let* ((cp (cos *pitch*))
         (sp (sin *pitch*))
         (eye
          (linalg:add *centre*
                      (vec3 (* *radius* cp (sin *yaw*)) (* *radius* sp)
                            (* *radius* cp (cos *yaw*)))))
         (forward (normalized (linalg:sub *centre* eye)))
         ;; right = normalize(cross(forward, world-up)); up = cross(right, fwd)
         (right (normalized (cross forward (vec3 0.0 1.0 0.0))))
         (up (cross right forward))
         ;; the look-at rotation: its rows are the camera axes, with forward
         ;; negated because the camera looks down -z
         (r (linalg:stack (list right up (linalg:mul forward -1.0))))
         ;; V = [ R | -R.eye ] over (0 0 0 1): the translation column is one
         ;; matrix-vector product, and the whole matrix is two concatenations
         (v
          (linalg:concatenate (list (linalg:concatenate (list r
                                                              (linalg:reshape
                                                               (linalg:mul
                                                                (linalg:matmul r
                                                                 eye) -1.0)
                                                               '(3 1)))
                                                        :axis 1)
                                    (linalg:from-list '((0.0 0.0 0.0 1.0))))
                              :axis 0)))
    (setq *eye* eye)
    (setq *basis* (linalg:stack (list right up forward)))
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
;; sphere is precomputed once into a rank-2 (vertex xyz) array.

(defconstant +seg+ 12) ; ring segments
(defvar *ctab* nil)    ; cos table, +seg+ entries
(defvar *stab* nil)

(defconstant +sph-stacks+ 5)
(defconstant +sph-slices+ 8)
(defconstant +sph-verts+ 240) ; +sph-stacks+ * +sph-slices+ * 6
(defvar *sph* nil)            ; rank-2 (vertex xyz) unit sphere

(defvar *v* 0)            ; solid vertex write cursor
(defvar *s* 0)            ; sprite write cursor
(defvar *static-verts* 0) ; axes + pedestal, uploaded once

;; The palette, and the colour the emitters are currently painting with: a mesh
;; has one, so it is latched rather than passed.
(defvar *steel* nil)
(defvar *dark* nil)
(defvar *ember* nil)

(defun set-color (c) (stage-color (aref c 0) (aref c 1) (aref c 2)))

(defun emit-v (x y z nx ny nz)
  ;; stages one vertex with the color latched by the last set-color call
  (set-vertex *v* x y z nx ny nz)
  (setq *v* (+ *v* 1)))

(defun emit-s (p tone size)
  (set-sprite *s* (aref p 0) (aref p 1) (aref p 2) tone size)
  (setq *s* (+ *s* 1)))

;; Two unit vectors perpendicular to AXIS and to each other, as the rows of a
;; (2 3) matrix: the frame every ring in this file is drawn in. The helper axis
;; is the world one AXIS is least aligned with, so the cross product never
;; degenerates.
(defun perp-basis (axis)
  (let* ((ay (if (< (aref axis 1) 0.0) (- 0.0 (aref axis 1)) (aref axis 1)))
         (helper (if (< ay 0.9) (vec3 0.0 1.0 0.0) (vec3 1.0 0.0 0.0)))
         (u (normalized (cross axis helper))))
    (linalg:stack (list u (cross axis u)))))

;; Tube radii travel through globals (set-radii) rather than parameters,
;; keeping every function within the WASM backend's 7-parameter limit.
(defvar *r-a* 0.0) ; tube start / disc radius
(defvar *r-b* 0.0) ; tube end radius (0 = cone)

(defun set-radii (a b)
  (setq *r-a* a)
  (setq *r-b* b))

(defun emit-tube (p0 p1)
  ;; A tube from P0 (radius *r-a*) to P1 (radius *r-b*); *r-b* = 0 makes a
  ;; cone. Normals get the proper slant for the taper.
  (let* ((r0 *r-a*)
         (r1 *r-b*)
         (span (linalg:sub p1 p0))
         (len (linalg:norm span))
         (l (if (< len 0.000001) 0.000001 len))
         (axis (linalg:mul span (/ 1.0 l)))
         (pb (perp-basis axis))
         (k (/ (- r0 r1) l)) ; radius change per unit length
         (nf (/ 1.0 (sqrt (+ 1.0 (* k k)))))
         ;; the ring loop below runs +seg+ * 6 times a tube and stages every
         ;; vertex, so it reads the frame out into scalars once
         (x0 (aref p0 0))
         (y0 (aref p0 1))
         (z0 (aref p0 2))
         (x1 (aref p1 0))
         (y1 (aref p1 1))
         (z1 (aref p1 2))
         (tx (aref axis 0))
         (ty (aref axis 1))
         (tz (aref axis 2))
         (ux (aref pb 0 0))
         (uy (aref pb 0 1))
         (uz (aref pb 0 2))
         (wx (aref pb 1 0))
         (wy (aref pb 1 1))
         (wz (aref pb 1 2)))
    (dotimes (seg +seg+)
      (let* ((s2 (mod (+ seg 1) +seg+))
             (ca (aref *ctab* seg))
             (sa (aref *stab* seg))
             (cb2 (aref *ctab* s2))
             (sb2 (aref *stab* s2))
             ;; the two radial unit directions of this quad
             (dax (+ (* ca ux) (* sa wx)))
             (day (+ (* ca uy) (* sa wy)))
             (daz (+ (* ca uz) (* sa wz)))
             (dbx (+ (* cb2 ux) (* sb2 wx)))
             (dby (+ (* cb2 uy) (* sb2 wy)))
             (dbz (+ (* cb2 uz) (* sb2 wz)))
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
                naz)))))

(defun emit-disc (centre normal)
  ;; A filled circle of radius *r-a* at CENTRE with the given face normal.
  (let* ((pb (perp-basis normal))
         (r *r-a*)
         (cx (aref centre 0))
         (cy (aref centre 1))
         (cz (aref centre 2))
         (nx (aref normal 0))
         (ny (aref normal 1))
         (nz (aref normal 2))
         (ux (aref pb 0 0))
         (uy (aref pb 0 1))
         (uz (aref pb 0 2))
         (wx (aref pb 1 0))
         (wy (aref pb 1 1))
         (wz (aref pb 1 2)))
    (dotimes (seg +seg+)
      (let* ((s2 (mod (+ seg 1) +seg+))
             (ca (aref *ctab* seg))
             (sa (aref *stab* seg))
             (cb2 (aref *ctab* s2))
             (sb2 (aref *stab* s2)))
        (emit-v cx cy cz nx ny nz)
        (emit-v (+ cx (* r (+ (* ca ux) (* sa wx))))
                (+ cy (* r (+ (* ca uy) (* sa wy))))
                (+ cz (* r (+ (* ca uz) (* sa wz)))) nx ny nz)
        (emit-v (+ cx (* r (+ (* cb2 ux) (* sb2 wx))))
                (+ cy (* r (+ (* cb2 uy) (* sb2 wy))))
                (+ cz (* r (+ (* cb2 uz) (* sb2 wz)))) nx ny nz)))))

(defun emit-sphere (centre r)
  (let ((cx (aref centre 0)) (cy (aref centre 1)) (cz (aref centre 2)))
    (dotimes (i +sph-verts+)
      (let ((nx (aref *sph* i 0)) (ny (aref *sph* i 1)) (nz (aref *sph* i 2)))
        (emit-v (+ cx (* r nx)) (+ cy (* r ny)) (+ cz (* r nz)) nx ny nz)))))

(defun %sph-write (w b s)
  (let* ((th (/ (* +pi+ b) +sph-stacks+))
         (ph (/ (* +two-pi+ s) +sph-slices+))
         (sth (sin th)))
    (setf (aref *sph* w 0) (* sth (cos ph)))
    (setf (aref *sph* w 1) (cos th))
    (setf (aref *sph* w 2) (* sth (sin ph)))))

(defun build-tables ()
  (setq *ctab* (linalg:zeros +seg+))
  (setq *stab* (linalg:zeros +seg+))
  (dotimes (i +seg+)
    (let ((a (/ (* +two-pi+ i) +seg+)))
      (setf (aref *ctab* i) (cos a))
      (setf (aref *stab* i) (sin a))))
  ;; the unit sphere as a triangle list (quads; the pole slivers degenerate)
  (setq *sph* (linalg:zeros (list +sph-verts+ 3)))
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

(defun emit-arrow (dir color)
  ;; A unit-axis arrow from the origin: shaft, cone base cap, cone tip.
  (set-color color)
  (let ((shaft (linalg:mul dir 0.52)) (tip (linalg:mul dir 0.67)))
    (set-radii 0.011 0.011)
    (emit-tube (vec3 0.0 0.0 0.0) shaft)
    (set-radii 0.034 0.0)
    (emit-disc shaft (linalg:mul dir -1.0))
    (emit-tube shaft tip)))

(defun emit-static-scene ()
  ;; the pedestal column under the base joint
  (set-color (vec3 0.20 0.23 0.31))
  (set-radii 0.13 0.095)
  (emit-tube (vec3 0.0 -0.30 0.0) (vec3 0.0 -0.02 0.0))
  (set-color (vec3 0.16 0.18 0.25))
  (emit-disc (vec3 0.0 -0.30 0.0) (vec3 0.0 -1.0 0.0))
  ;; the RGB = XYZ axis arrows at the origin
  (emit-arrow (vec3 1.0 0.0 0.0) (vec3 0.90 0.25 0.31))
  (emit-arrow (vec3 0.0 1.0 0.0) (vec3 0.30 0.82 0.42))
  (emit-arrow (vec3 0.0 0.0 1.0) (vec3 0.32 0.53 0.96)))

;; --- the arm ------------------------------------------------------------------
;;
;; The base joint sits at the origin (where the axis arrows are). The chain
;; is *links* links whose lengths taper geometrically and always sum to
;; +reach+, so every link count has the same workspace.

(defconstant +reach+ 1.25)
(defconstant +tool+ 0.115) ; wrist -> grasp point (the gripper)
(defconstant +trail+ 90)

;; The gripper rides the chain as one extra "tool" link: the solver moves the
;; wrist AND the grasp point (the TCP, the last row of *joints*), so the
;; fingers close exactly on the clicked goal and the approach direction comes
;; out of the solver for free.
(defvar *links* 0)
(defvar *tip* 0)      ; *links* + 1, the row the goal is pinned to
(defvar *len* nil)    ; rank-1: link lengths, the tool last
(defvar *joints* nil) ; rank-2 (*links* + 2, 3): row 0 the base, *tip* the TCP

(defun joint (i) (linalg:row *joints* i))

(defun set-joint (i p) (set-row *joints* i p))

;; The minimum-jerk trajectory: the commanded hand position *target* travels
;; from *start* to *goal* over *dur* seconds starting at *t0*.
(defvar *start* nil)
(defvar *goal* nil)
(defvar *target* nil)
(defvar *t0* -10.0)
(defvar *dur* 1.0)

;; The latest click, in clip coordinates; picked up by the next frame (which
;; knows the camera and the time).
(defvar *click* nil)

;; The hand's recent path, a ring buffer drawn as a fading trail.
(defvar *trail* nil) ; rank-2 (+trail+ 3)
(defvar *trail-head* 0)
(defvar *trail-count* 0)

(defun init (n)
  (build-tables)
  (setq *centre* (vec3 0.0 0.42 0.0))
  (setq *steel* (vec3 0.72 0.76 0.84))
  (setq *dark* (vec3 0.30 0.34 0.44))
  (setq *ember* (vec3 1.0 0.52 0.16))
  (setq *links* n)
  (setq *tip* (+ n 1))
  (setq *len* (linalg:zeros (+ n 1)))
  (setq *joints* (linalg:zeros (list (+ n 2) 3)))
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
    (setf (aref *joints* (+ i 1) 1) (+ (aref *joints* i 1) (aref *len* i))))
  ;; idle until the first click: the trajectory is already at its goal
  (setq *target* (joint *tip*))
  (setq *start* *target*)
  (setq *goal* *target*)
  (setq *t0* -10.0)
  (setq *dur* 1.0)
  (setq *trail* (linalg:zeros (list +trail+ 3)))
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
  (setq *click* (vec3 cx cy 0.0)))

;; --- the minimum-jerk trajectory ----------------------------------------------

(defun min-jerk (u)
  ;; The minimum-jerk position profile s(u) = 10u^3 - 15u^4 + 6u^5 for
  ;; u in [0, 1]: the unique 5th-order polynomial with zero velocity and
  ;; zero acceleration at both ends, i.e. the trajectory minimizing the
  ;; integral of squared jerk (Flash & Hogan 1985).
  (* u u u (+ 10.0 (* u (+ -15.0 (* u 6.0))))))

(defun set-goal (p tm)
  ;; Clamp the requested point into the sphere shell the arm can reach (and
  ;; above the pedestal, which also keeps it off the origin), then restart the
  ;; min-jerk clock. A click mid-flight restarts the profile from the currently
  ;; commanded point, so the hand never jumps.
  (let* ((m (vec3 (aref p 0) (max 0.05 (aref p 1)) (aref p 2)))
         (d (linalg:norm m))
         (lo (* 0.18 +reach+))
         (hi (* 0.985 (+ +reach+ +tool+)))
         (r (cond ((< d lo) (/ lo d)) ((> d hi) (/ hi d)) (t 1.0))))
    (setq *start* *target*)
    (setq *goal* (linalg:mul m r))
    (setq *t0* tm)
    ;; duration grows with distance: quick nearby hops, ~1.5 s across
    (setq *dur* (+ 0.45 (* 0.55 (linalg:norm (linalg:sub *goal* *start*)))))))

(defun set-goal-from-click (clip tm)
  ;; Unproject the click: a ray from the eye through the clip point,
  ;; intersected with the plane through the orbit centre facing the camera.
  ;; Both steps are the camera basis and two dot products; nothing here
  ;; mentions an axis by name.
  (let* ((th (/ (sin +half-fov+) (cos +half-fov+)))
         (forward (cam-forward))
         (dir
          (linalg:add forward
                      (linalg:add
                       (linalg:mul (cam-right) (* (aref clip 0) th *aspect*))
                       (linalg:mul (cam-up) (* (aref clip 1) th)))))
         (tt
          (/ (linalg:dot (linalg:sub *centre* *eye*) forward)
             (linalg:dot dir forward))))
    (set-goal (linalg:add *eye* (linalg:mul dir tt)) tm)))

(defun update-target (tm)
  ;; Advance the commanded hand position along the min-jerk profile: one lerp,
  ;; and the whole point of the profile is the shape of s.
  (let ((u (/ (- tm *t0*) *dur*)))
    (setq *target*
          (cond ((>= u 1.0) *goal*)
                ((<= u 0.0) *start*)
                (t (linalg:add *start*
                    (linalg:mul (linalg:sub *goal* *start*) (min-jerk u))))))))

;; --- inverse kinematics (in 3-D): Jacobian DLS, FABRIK, or analytic ------------
;;
;; Three solvers over the same joint-position matrix, switchable from the
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
  ;; Move joint I to distance LEN from joint FROM, preserving direction.
  ;; Shared by both iterative solvers (FABRIK sweeps, DLS re-normalization).
  (let* ((d (linalg:sub (joint i) (joint from))) (n (linalg:norm d)))
    (set-joint i
               (linalg:add (joint from)
                (linalg:mul d (/ len (if (< n 0.000001) 0.000001 n)))))))

(load "ik-jacobian.lisp")
(load "ik-fabrik.lisp")
(load "ik-analytic.lisp")

(defun solve-ik ()
  (cond ((= *solver* 0) (solve-ik-jacobian))
        ((= *solver* 1) (solve-ik-fabrik))
        (t (solve-ik-analytic))))

;; The HUD polls this: how far the grasp point still is from the goal.
(defun ik-error () (linalg:norm (linalg:sub (joint *tip*) *goal*)))

;; --- rendering ------------------------------------------------------------------

(defun link-radius (i)
  ;; the arm tapers from shoulder to wrist
  (* 0.052 (- 1.0 (* 0.55 (/ (* 1.0 i) *links*)))))

(defun push-trail ()
  (set-row *trail* *trail-head* (joint *tip*))
  (setq *trail-head* (mod (+ *trail-head* 1) +trail+))
  (when (< *trail-count* +trail+) (setq *trail-count* (+ *trail-count* 1))))

;; --- the gripper ---------------------------------------------------------------
;;
;; Three two-phalanx fingers, 120 degrees apart around the tool axis. The
;; grip state *grip* eases between 0 (open, while the hand is flying) and 1
;; (closed): the finger segments pivot from splayed-out angles to angles
;; that make the tips meet exactly at the grasp point, +tool+ ahead of the
;; wrist -- the very point the solver pins to the goal.

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
  (let* ((wrist (joint *links*))
         ;; approach axis: the solved tool link, wrist -> grasp point
         (axis (linalg:mul (linalg:sub (joint *tip*) wrist) (/ 1.0 +tool+)))
         (pb (perp-basis axis))
         (palm (linalg:add wrist (linalg:mul axis 0.035)))
         ;; finger phalanx angles from the axis: splayed when open, curled
         ;; so the tips meet on the axis at +tool+ when closed
         (a1 (- 0.70 (* 0.58 *grip*)))
         (a2 (- 0.35 (* 1.15 *grip*))))
    ;; the wrist ball and the palm
    (set-color *dark*)
    (emit-sphere wrist (* 1.35 (link-radius *links*)))
    (set-radii 0.030 0.036)
    (emit-tube wrist palm)
    ;; three fingers, at ring-table angles 0, 120, 240 degrees
    (dotimes (f 3)
      (let* ((radial
              (linalg:add (linalg:mul (linalg:row pb 0) (aref *ctab* (* f 4)))
                          (linalg:mul (linalg:row pb 1) (aref *stab* (* f 4)))))
             ;; phalanx directions: rotate the axis toward the radial
             (d1
              (linalg:add (linalg:mul axis (cos a1))
                          (linalg:mul radial (sin a1))))
             (d2
              (linalg:add (linalg:mul axis (cos a2))
                          (linalg:mul radial (sin a2))))
             ;; knuckle on the palm rim, then two phalanges
             (base (linalg:add palm (linalg:mul radial 0.030)))
             (knuckle (linalg:add base (linalg:mul d1 0.05)))
             (fingertip (linalg:add knuckle (linalg:mul d2 0.05))))
        (set-color *steel*)
        (set-radii 0.012 0.009)
        (emit-tube base knuckle)
        (set-radii 0.009 0.006)
        (emit-tube knuckle fingertip)
        (set-color *dark*)
        (emit-sphere knuckle 0.011)
        (set-color *ember*)
        (emit-sphere fingertip 0.008)))))

(defun emit-arm ()
  ;; tapered tube per link, dark sphere per joint, the gripper at the wrist
  (set-color *steel*)
  (dotimes (i *links*)
    (set-radii (link-radius i) (link-radius (+ i 1)))
    (emit-tube (joint i) (joint (+ i 1))))
  (set-color *dark*)
  (dotimes (i *links*) (emit-sphere (joint i) (* 1.45 (link-radius i))))
  (emit-gripper))

(defun emit-glow (tm)
  ;; the trail: the hand's recent path, fading with age
  (dotimes (m *trail-count*)
    (let* ((idx (mod (+ (- *trail-head* *trail-count*) m +trail+) +trail+))
           (age (/ (* 1.0 (+ m 1)) *trail-count*)) ; 0 oldest .. 1 newest
           (fade (* age age)))
      (emit-s (linalg:row *trail* idx) (+ 0.50 (* 0.20 fade))
              (+ 5.0 (* 12.0 fade)))))
  ;; the goal: a slowly turning, pulsing ring billboarded on the camera basis
  (let ((right (cam-right))
        (up (cam-up))
        (r (+ 0.045 (* 0.010 (sin (* tm 5.0))))))
    (dotimes (m 36)
      (let ((a (+ (* tm 0.8) (/ (* +two-pi+ m) 36))))
        (emit-s (linalg:add *goal*
                            (linalg:add (linalg:mul right (* r (cos a)))
                                        (linalg:mul up (* r (sin a))))) 1.0
                9.0)))
    (emit-s *goal* 1.0 14.0)))

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
  (gl:uniform3f *u-eye* (aref *eye* 0) (aref *eye* 1) (aref *eye* 2))
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
  (when *click*
    (set-goal-from-click *click* tm)
    (setq *click* nil))
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
