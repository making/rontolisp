;;;; battlefront.lisp -- a one-arena snow-battle skirmish, entirely in Lisp.
;;;;
;;;; Play as you on a Hoth snow field. Move with W/A/S/D (Minecraft style, the
;;;; mouse aims), switch between the lightsaber and the blaster with F, attack
;;;; with the left mouse button. Cut down the stormtroopers, bring down the two
;;;; AT-AT walkers, and once the walkers are gone Vader wakes -- his red
;;;; blade deflects blaster fire, so finish the boss with your own lightsaber.
;;;;
;;;; Everything that makes it a game lives here: the movement and the
;;;; camera-relative steering, the third-person follow/aim camera and its
;;;; look-at/perspective matrices, the blaster bolts (fired by you, the
;;;; troopers and the walkers) with their travel, lifetimes and collisions, the
;;;; lightsaber swing that both damages enemies and DEFLECTS incoming fire, the
;;;; trooper / AT-AT / Vader AI, the win/lose state machine, and every triangle
;;;; of the world -- flat parts (armor plates, packs, belts) are tessellated
;;;; from rotated boxes each frame, round ones (heads, limbs, gun/saber
;;;; barrels, bolts, sparks) from smooth-normaled cylinders and ellipsoids.
;;;; The glowing lightsabers and bolts are a second additive-blended pass over
;;;; the same buffer, so the blades bloom against the snow.
;;;;
;;;; The geometry math is the linalg package throughout. Every position is a
;;;; packed single-float vector (#f(x y z)); movement, distances and headings
;;;; are linalg:add / sub / mul / dot / norm on those vectors; a box's eight
;;;; corners are one linalg:matmul of the yaw rotation against the local-corner
;;;; matrix (then a broadcast add of the center); and the view-projection is a
;;;; linalg:matmul of two rank-2 (4 4) matrices flattened (transposed) into the
;;;; column-major run the GPU wants.
;;;;
;;;; JavaScript is the same one-line WebGL2 host boundary as the other webgl-*
;;;; demos, plus Pointer-Lock mouse/keyboard forwarding and the HUD.
;;;;
;;;; Compiled ahead of time to a --no-wasi reactor (build.sh), so the module
;;;; imports nothing but the host functions declared below and instantiates in
;;;; any wasm-GC-capable browser.

;; --- the host boundary ------------------------------------------------------
;;
;; The WebGL2 API itself -- the wasm-import directives, the enum constants and
;; the shader helpers -- lives in the shared gl package (../webgl-common/gl.lisp),
;; spliced in here at compile time; --optimize drops the entries this demo never
;; calls. Only the imports specific to this page stay below.

(require :gl "../webgl-common/gl.lisp")

;; The bulk-float staging path (see webgl-robot-arm / webgl-platformer): each
;; vertex is 11 floats -- position (3), normal (3), color (3), an emissive
;; term (1) that makes the blades and bolts self-lit, and a shine term (1)
;; that drives the specular highlight on polished materials (helmet domes,
;; gun/saber metal). Color, emissive and shine are constant per part, so
;; set-color / set-emissive / set-shine latch them and set-vertex stages
;; position + normal + those latched values (a call crosses the WASM boundary
;; with at most 7 parameters).
(rontolisp:wasm-import 'set-color :from "gl" :as "setColor"
                       :params '(:float :float :float) :returns :void)
(rontolisp:wasm-import 'set-emissive :from "gl" :as "setEmissive"
                       :params '(:float) :returns :void)
;; shine (0 = matte cloth/snow, 1 = polished metal/glass) drives the specular
;; highlight in the fragment shader -- latched like color/emissive, so armor
;; plates, helmet domes and blaster/saber metal can read as harder materials
;; than the fabric and snow around them.
(rontolisp:wasm-import 'set-shine :from "gl" :as "setShine"
                       :params '(:float) :returns :void)
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

;; The WASM backend has no transcendental built-ins, so borrow the host's; the
;; host also supplies entropy (trooper fire timing, muzzle jitter).
(rontolisp:wasm-import 'sin :from "math" :params '(:float) :returns :float)
(rontolisp:wasm-import 'cos :from "math" :params '(:float) :returns :float)
(rontolisp:wasm-import 'atan2 :from "math" :params '(:float :float) :returns :float)
(rontolisp:wasm-import 'host-random :from "math" :as "random" :params '() :returns :float)

(defconstant +pi+ 3.141592653589793)
(defconstant +two-pi+ 6.283185307179586)

(defun rand01 () (host-random))
(defun rand-range (a b) (+ a (* (- b a) (host-random))))


;; --- shaders ----------------------------------------------------------------
;;
;; One program: lit triangles under a cold Hoth key light, a sky-tinted
;; hemisphere ambient and a distance fog that fades the far scenery into the
;; pale sky. An emissive term (aEmit) lets a vertex ignore the lighting and fog
;; and glow at its own color -- that is what the lightsaber blades and the
;; blaster bolts use.

(defconstant +solid-vs+ "#version 300 es
layout(location=0) in vec3 aPos;     // world-space position from Lisp
layout(location=1) in vec3 aNormal;  // world-space normal from Lisp
layout(location=2) in vec3 aColor;
layout(location=3) in float aEmit;   // 0 = lit surface, 1 = self-lit
layout(location=4) in float aShine;  // 0 = matte, 1 = polished metal/glass
uniform mat4 uVP;                    // view-projection, computed in Lisp
out vec3 vN;
out vec3 vC;
out vec3 vW;
out float vE;
out float vS;
void main() {
  gl_Position = uVP * vec4(aPos, 1.0);
  vN = aNormal;
  vC = aColor;
  vW = aPos;
  vE = aEmit;
  vS = aShine;
}")

(defconstant +solid-fs+ "#version 300 es
precision mediump float;
in vec3 vN;
in vec3 vC;
in vec3 vW;
in float vE;
in float vS;
uniform vec3 uEye;
out vec4 color;
void main() {
  vec3 n = normalize(vN);
  vec3 l = normalize(vec3(0.35, 0.82, 0.45));
  vec3 v = normalize(uEye - vW);
  float diff = max(dot(n, l), 0.0);
  float amb  = 0.52 + 0.18 * n.y;           // hemisphere: tops brighter
  vec3 lit = vC * (amb + 0.55 * diff);

  // a Blinn-Phong highlight, harder and brighter the shinier the material --
  // this is what separates polished helmet domes / gun metal / saber hilts
  // from the matte cloth and snow around them (both packed in aShine).
  vec3 hFace = normalize(l + v);
  float shininess = mix(10.0, 90.0, vS);
  float spec = pow(max(dot(n, hFace), 0.0), shininess) * vS * 1.3;
  lit += spec;

  // a cheap outdoor rim light: the low Hoth sun grazes silhouette edges with
  // a touch of sky color, which reads as soft ambient occlusion's opposite --
  // it keeps rounded (ellipsoid/cylinder) surfaces from looking flat-lit.
  float rim = pow(1.0 - max(dot(n, v), 0.0), 2.5) * 0.22;
  vec3 sky = vec3(0.74, 0.83, 0.93);
  lit += rim * sky;

  vec3 shown = mix(lit, vC, vE);            // emissive: ignore the light
  // the fog reaches further than it used to: the horizon is a range of peaks
  // now rather than a wall, and saturating it at 95m erased the whole skyline
  float fog = smoothstep(45.0, 150.0, distance(uEye, vW)) * (1.0 - vE);
  color = vec4(mix(shown, sky, fog), 1.0);
}")

;; --- 4x4 matrix math: rank-2 arrays + the linalg package ----------------------
;;
;; The matrices are ordinary rank-2 (4 4) linalg arrays in textbook (row, col)
;; convention, VP = P x V is one linalg:matmul, and upload-vp flattens the
;; transpose into the column-major order WebGL expects.

(defconstant +half-fov+ 0.44)                   ; a touch under 50-degree fov

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

(defun build-view (eye target)
  ;; The look-at view matrix for eye -> target: rows are the camera basis
  ;; (right / up / -forward), the translation column is -(basis . eye). The
  ;; basis comes out of a couple of cross products -- kept in local scalars,
  ;; the matrix itself is the linalg (4 4) that combines with the projection.
  (let* ((ex (aref eye 0)) (ey (aref eye 1)) (ez (aref eye 2))
         (fx0 (- (aref target 0) ex))
         (fy0 (- (aref target 1) ey))
         (fz0 (- (aref target 2) ez))
         (fl (sqrt (+ (* fx0 fx0) (* fy0 fy0) (* fz0 fz0))))
         (fx (/ fx0 fl)) (fy (/ fy0 fl)) (fz (/ fz0 fl))
         ;; right = normalize(forward x up), up = (0 1 0)
         (rx0 (- 0.0 fz)) (rz0 fx)
         (rl (sqrt (+ (* rx0 rx0) (* rz0 rz0))))
         (rx (/ rx0 rl)) (rz (/ rz0 rl))
         ;; up = right x forward (right has ry = 0)
         (ux (- 0.0 (* rz fy)))
         (uy (- (* rz fx) (* rx fz)))
         (uz (* rx fy))
         (v (linalg:full '(4 4) 0.0)))
    (setf (aref v 0 0) rx) (setf (aref v 0 1) 0.0) (setf (aref v 0 2) rz)
    (setf (aref v 0 3) (- 0.0 (+ (* rx ex) (* rz ez))))
    (setf (aref v 1 0) ux) (setf (aref v 1 1) uy) (setf (aref v 1 2) uz)
    (setf (aref v 1 3) (- 0.0 (+ (* ux ex) (* uy ey) (* uz ez))))
    (setf (aref v 2 0) (- 0.0 fx)) (setf (aref v 2 1) (- 0.0 fy))
    (setf (aref v 2 2) (- 0.0 fz))
    (setf (aref v 2 3) (+ (* fx ex) (* fy ey) (* fz ez)))
    (setf (aref v 3 3) 1.0)
    v))

;; --- the follow / aim camera --------------------------------------------------
;;
;; A third-person camera orbiting a smoothed copy of the player. The mouse
;; drives the orbit (Pointer Lock on the page), so it doubles as the aim: you
;; face the way the camera looks and fire along that heading.

(defvar *cam* #f(0.0 0.0 0.0))          ; the smoothed follow point
(defconstant +cam-yaw-0+ 0.0)           ; 0 looks along +x, into the field
(defconstant +cam-pitch-0+ 0.32)
(defconstant +cam-dist-0+ 7.5)

(defvar *cam-yaw* +cam-yaw-0+)
(defvar *cam-pitch* +cam-pitch-0+)
(defvar *cam-dist* +cam-dist-0+)
(defvar *eye* #f(0.0 0.0 0.0))
(defvar *aspect* 1.0)
(defvar *vp* nil)                       ; the current view-projection matrix

;; The aim frame, refreshed from the camera yaw each frame: forward *aimf* is the
;; horizontal heading you face and fire along (y = 0), right *aimr* is
;; forward x up. Both are unit vectors in the ground plane.
(defvar *aimf* #f(1.0 0.0 0.0))
(defvar *aimr* #f(0.0 0.0 1.0))

(defun update-aim ()
  (setq *aimf* (linalg:from-list (list (cos *cam-yaw*) 0.0 (sin *cam-yaw*)) 'single-float))
  (setq *aimr* (linalg:from-list (list (- 0.0 (sin *cam-yaw*)) 0.0 (cos *cam-yaw*)) 'single-float)))

(defun orbit (dx dy)
  ;; Exported: mouse-look deltas, normalized by the canvas height.
  (setq *cam-yaw* (- *cam-yaw* (* 2.6 dx)))
  (let ((p (+ *cam-pitch* (* 2.4 dy))))
    (setq *cam-pitch* (max 0.08 (min 1.15 p)))))

(defun zoom (dz)
  ;; Exported: scroll-wheel deltas.
  (setq *cam-dist* (max 4.5 (min 13.0 (+ *cam-dist* dz)))))

(defun update-camera (dt)
  ;; smooth the follow point toward the player, then place the eye behind it
  (let ((k (min 1.0 (* 8.0 dt))))
    (setq *cam* (linalg:add *cam* (linalg:mul (linalg:sub *ppos* *cam*) k))))
  (let ((cp (cos *cam-pitch*))
        (sp (sin *cam-pitch*))
        (cy (cos *cam-yaw*))
        (sy (sin *cam-yaw*)))
    (setq *eye* (linalg:add *cam* (linalg:from-list (list (- 0.0 (* *cam-dist* cp cy))
                                        (+ 1.1 (* *cam-dist* sp))
                                        (- 0.0 (* *cam-dist* cp sy))) 'single-float)))
    (let ((target (linalg:add *cam* (linalg:from-list (list (* 2.0 cy) 1.1 (* 2.0 sy)) 'single-float))))
      (setq *vp* (linalg:matmul (mat4-perspective *aspect* 0.1 160.0)
                                (build-view *eye* target))))))

(defun upload-vp (loc)
  ;; WebGL wants column-major, which is the row-major layout of the transpose;
  ;; flatten it into the 16-float scratch and hand it over.
  (let ((flat (linalg:flatten (linalg:transpose *vp*))))
    (dotimes (i 16) (set-float i (aref flat i))))
  (gl-uniform-matrix4fv loc))

;; --- GL pipeline setup --------------------------------------------------------

(defvar *prog* 0)
(defvar *u-vp* 0)
(defvar *u-eye* 0)
(defvar *vao* 0)
(defvar *buf* 0)

;; Rounded parts (ellipsoid heads, tapered limbs, rounded-box armour and the
;; round bolts/sparks/shadows) cost far more triangles per feature than a box,
;; so the capacity is well above the old boxes-only budget; still trivial GPU
;; memory (+max-verts+ * +stride+ bytes ~ 8 MB). It MUST match the page's own
;; staging Float32Array (MAX_VERTS in index.html): the staging array is what
;; setVertex writes into, and a JS typed-array store past the end is silently
;; dropped, so a Lisp-side cap above the page's would lose triangles with no
;; error anywhere.
(defconstant +max-verts+ 190000)        ; lit-triangle vertex capacity
(defconstant +stride+ 44)               ; 11 floats per vertex

(defun setup-gl ()
  (setq *prog* (gl:build-program +solid-vs+ +solid-fs+))
  (setq *u-vp* (gl:get-uniform-location *prog* "uVP"))
  (setq *u-eye* (gl:get-uniform-location *prog* "uEye"))
  (gl:enable gl:+depth-test+)
  ;; one VAO: position + normal + color + emissive + shine
  (setq *vao* (gl:create-vertex-array))
  (gl:bind-vertex-array *vao*)
  (setq *buf* (gl:create-buffer))
  (gl:bind-buffer gl:+array-buffer+ *buf*)
  (gl:buffer-data gl:+array-buffer+ (* +max-verts+ +stride+) gl:+dynamic-draw+)
  (gl:enable-vertex-attrib-array 0)
  (gl:vertex-attrib-pointer 0 3 gl:+float+ nil +stride+ 0)
  (gl:enable-vertex-attrib-array 1)
  (gl:vertex-attrib-pointer 1 3 gl:+float+ nil +stride+ 12)
  (gl:enable-vertex-attrib-array 2)
  (gl:vertex-attrib-pointer 2 3 gl:+float+ nil +stride+ 24)
  (gl:enable-vertex-attrib-array 3)
  (gl:vertex-attrib-pointer 3 1 gl:+float+ nil +stride+ 36)
  (gl:enable-vertex-attrib-array 4)
  (gl:vertex-attrib-pointer 4 1 gl:+float+ nil +stride+ 40))

;; --- box tessellation ---------------------------------------------------------
;;
;; Everything in the world is a yaw-rotated box. emit-box builds the eight local
;; corners as the columns of a rank-2 (3 8) matrix, rotates them ALL with one
;; linalg:matmul against the 3x3 yaw matrix, broadcasts the center on with a
;; linalg:add, and stamps the 6 faces (12 triangles) with the face normals read
;; straight off the rotation's columns. Corner index bits: bit 0 = +x, bit 1 =
;; +y, bit 2 = +z in local space; *corners* holds the resulting world (3 8).

(defvar *v* 0)                          ; vertex write cursor
(defvar *static-verts* 0)               ; the baked snow field, uploaded once

;; Edge softening: a box face can be stamped with per-corner normals bent
;; towards the corner's own outward direction instead of the flat face normal.
;; The silhouette stays a box, but the shading gradient across each face is the
;; one a bevelled edge would produce -- which is most of what separates a
;; "moulded panel" from a "cardboard carton" at gameplay distance, and it costs
;; not one extra vertex. The factor is latched by `soften` and CONSUMED by
;; emit-box (reset to 0 afterwards), so a box that does not ask for softening
;; can never inherit the previous one's.
(defvar *ccx* 0.0)
(defvar *ccy* 0.0)
(defvar *ccz* 0.0)
(defvar *csoft* 0.0)

(defun soften (k) (setq *csoft* k))

(defun emit-v (col nx ny nz)
  ;; stages corner `col` of *corners* with the latched color + the given normal
  (when (< *v* +max-verts+)
   (let ((x (aref *corners* 0 col))
        (y (aref *corners* 1 col))
        (z (aref *corners* 2 col)))
    (if (> *csoft* 0.0)
        (let* ((dx (- x *ccx*)) (dy (- y *ccy*)) (dz (- z *ccz*))
               (dl (max 0.000001 (sqrt (+ (* dx dx) (* dy dy) (* dz dz)))))
               (k *csoft*)
               (j (- 1.0 k))
               (mx (+ (* j nx) (* k (/ dx dl))))
               (my (+ (* j ny) (* k (/ dy dl))))
               (mz (+ (* j nz) (* k (/ dz dl))))
               (ml (max 0.000001 (sqrt (+ (* mx mx) (* my my) (* mz mz))))))
          (set-vertex *v* x y z (/ mx ml) (/ my ml) (/ mz ml)))
        (set-vertex *v* x y z nx ny nz))
    (setq *v* (+ *v* 1)))))

(defun emit-face (a b c d nx ny nz)
  (emit-v a nx ny nz)
  (emit-v b nx ny nz)
  (emit-v c nx ny nz)
  (emit-v a nx ny nz)
  (emit-v c nx ny nz)
  (emit-v d nx ny nz))

;; The corner buffer is allocated ONCE and refilled in place. It used to be
;; rebuilt per box as (linalg:add (linalg:matmul rot local) centre) -- elegant,
;; and the right tool for the camera matrices, but the wrong one here: that is
;; four array allocations and a general nested-loop matmul for eight corners,
;; paid for every box in every frame. The cast is now boxes-plus-rounded-boxes
;; in the hundreds per frame, and the allocation dominated the frame. The
;; algebra below is the SAME rotation, written out: yaw only mixes x and z, so
;; the rotation's two interesting columns are the local +x and +z edge vectors,
;; and each corner is the centre plus or minus each of them.
(defvar *corners* (linalg:full '(3 8) 0.0 'single-float))

(defun emit-box (cx cy cz hx hy hz yaw)
  (setq *ccx* cx *ccy* cy *ccz* cz)
  (let* ((c (cos yaw))
         (s (sin yaw))
         (axx (* c hx)) (axz (- 0.0 (* s hx)))   ; the local +x edge, in world
         (azx (* s hz)) (azz (* c hz)))          ; the local +z edge, in world
    (dotimes (i 8)
      (let ((sx (if (= (logand i 1) 1) 1.0 -1.0))
            (sy (if (= (logand i 2) 2) hy (- 0.0 hy)))
            (sz (if (= (logand i 4) 4) 1.0 -1.0)))
        (setf (aref *corners* 0 i) (+ cx (* sx axx) (* sz azx)))
        (setf (aref *corners* 1 i) (+ cy sy))
        (setf (aref *corners* 2 i) (+ cz (* sx axz) (* sz azz)))))
    ;; the face normals are the same two edge directions, normalized (yaw only
    ;; tilts x / z, so +y and -y stay axis-aligned)
    (emit-face 4 5 7 6 s 0.0 c)                                  ; +z
    (emit-face 1 0 2 3 (- 0.0 s) 0.0 (- 0.0 c))                  ; -z
    (emit-face 5 1 3 7 c 0.0 (- 0.0 s))                          ; +x
    (emit-face 0 4 6 2 (- 0.0 c) 0.0 s)                          ; -x
    (emit-face 6 7 3 2 0.0 1.0 0.0)                              ; +y
    (emit-face 0 1 5 4 0.0 -1.0 0.0)                             ; -y
    (setq *csoft* 0.0)))

;; --- rounded primitives: cylinders and ellipsoids ------------------------------
;;
;; Boxes read as toy-blocky wherever a real silhouette is round: heads, helmet
;; domes, limbs, gun barrels, blade cores. These three build low-poly meshes
;; with SMOOTH per-vertex normals (the radial direction, not a flat per-face
;; one) -- under the lit shader a smooth normal gradient across a flat
;; triangle reads as curved, so even an 8-sided prism looks like a round rod.
;; No face culling is enabled (setup-gl), so triangle winding never matters
;; here -- only the explicit per-vertex normal does.

(defun emit-vertex (x y z nx ny nz)
  ;; the cursor is checked here (and in emit-v) rather than trusted: a frame
  ;; that overruns the staging array would otherwise upload a slice longer than
  ;; the page's buffer and take the whole canvas down, instead of just dropping
  ;; the triangles nobody budgeted for.
  (when (< *v* +max-verts+)
    (set-vertex *v* x y z nx ny nz)
    (setq *v* (+ *v* 1))))

;; NOTE ON SIGNATURES. The WASM backend's callable types stop at seven
;; parameters; a wider fixed-arity defun still compiles, but only because the
;; compiler rewrites it to bundle the surplus arguments into a freshly consed
;; list at EVERY call site. That is invisible in a cold helper and ruinous in
;; one called per triangle, so every function below that runs per vertex or per
;; triangle is kept at seven parameters or fewer, and the values that are
;; constant across a whole primitive (the ellipsoid's centre/radii/yaw, the
;; rounded box's extents) are latched in globals instead of threaded through
;; the signature. That is why there is no emit-tri: three emit-vertex calls
;; cost nothing, an 18-parameter helper costs twelve cons cells a triangle.

;; A vertical (local +y axis) cylinder: radius r, half-height hy, yaw-rotated
;; and centered at (cx cy cz) exactly like emit-box. Used for upright limbs
;; (thighs, shins, neck).
(defun emit-cylinder (cx cy cz r hy yaw nsides)
  (let ((c (cos yaw)) (s (sin yaw))
        (ytop (+ cy hy)) (ybot (- cy hy)))
    (dotimes (i nsides)
      (let* ((a0 (* +two-pi+ (/ (float i) (float nsides))))
             (a1 (* +two-pi+ (/ (float (+ i 1)) (float nsides))))
             (lx0 (cos a0)) (lz0 (sin a0))
             (lx1 (cos a1)) (lz1 (sin a1))
             ;; the local radial direction, rotated by yaw -- already unit,
             ;; so it doubles as the smooth side normal
             (n0x (+ (* c lx0) (* s lz0))) (n0z (- (* c lz0) (* s lx0)))
             (n1x (+ (* c lx1) (* s lz1))) (n1z (- (* c lz1) (* s lx1)))
             (x0 (+ cx (* r n0x))) (z0 (+ cz (* r n0z)))
             (x1 (+ cx (* r n1x))) (z1 (+ cz (* r n1z))))
        (emit-vertex x0 ytop z0 n0x 0.0 n0z)
        (emit-vertex x0 ybot z0 n0x 0.0 n0z)
        (emit-vertex x1 ybot z1 n1x 0.0 n1z)
        (emit-vertex x0 ytop z0 n0x 0.0 n0z)
        (emit-vertex x1 ybot z1 n1x 0.0 n1z)
        (emit-vertex x1 ytop z1 n1x 0.0 n1z)
        (emit-vertex cx ytop cz 0.0 1.0 0.0)
        (emit-vertex x0 ytop z0 0.0 1.0 0.0)
        (emit-vertex x1 ytop z1 0.0 1.0 0.0)
        (emit-vertex cx ybot cz 0.0 -1.0 0.0)
        (emit-vertex x1 ybot z1 0.0 -1.0 0.0)
        (emit-vertex x0 ybot z0 0.0 -1.0 0.0)))))

;; A horizontal beam cylinder, the round counterpart of emit-arm-to's oriented
;; box: its axis is the yaw-rotated local +x direction (half-len long), radius
;; r in the perpendicular plane. Used for arms, gun barrels and blade/hilt
;; rods -- anything currently built as a "beam between two points".
(defun emit-cyl-beam (cx cy cz half-len r yaw nsides)
  (let* ((c (cos yaw)) (s (sin yaw))
         (axx c) (axz (- 0.0 s))            ; axis direction (matches box hx)
         (perpx s) (perpz c))               ; horizontal perpendicular (box hz)
    (dotimes (i nsides)
      (let* ((a0 (* +two-pi+ (/ (float i) (float nsides))))
             (a1 (* +two-pi+ (/ (float (+ i 1)) (float nsides))))
             (ca0 (cos a0)) (sa0 (sin a0))
             (ca1 (cos a1)) (sa1 (sin a1))
             (n0x (* sa0 perpx)) (n0y ca0) (n0z (* sa0 perpz))
             (n1x (* sa1 perpx)) (n1y ca1) (n1z (* sa1 perpz))
             (bx0 (+ cx (* r n0x))) (by0 (+ cy (* r n0y))) (bz0 (+ cz (* r n0z)))
             (bx1 (+ cx (* r n1x))) (by1 (+ cy (* r n1y))) (bz1 (+ cz (* r n1z)))
             (fx0 (+ bx0 (* half-len axx))) (fz0 (+ bz0 (* half-len axz)))
             (kx0 (- bx0 (* half-len axx))) (kz0 (- bz0 (* half-len axz)))
             (fx1 (+ bx1 (* half-len axx))) (fz1 (+ bz1 (* half-len axz)))
             (kx1 (- bx1 (* half-len axx))) (kz1 (- bz1 (* half-len axz))))
        (emit-vertex fx0 by0 fz0 n0x n0y n0z)
        (emit-vertex kx0 by0 kz0 n0x n0y n0z)
        (emit-vertex kx1 by1 kz1 n1x n1y n1z)
        (emit-vertex fx0 by0 fz0 n0x n0y n0z)
        (emit-vertex kx1 by1 kz1 n1x n1y n1z)
        (emit-vertex fx1 by1 fz1 n1x n1y n1z)
        (emit-vertex (+ cx (* half-len axx)) cy (+ cz (* half-len axz)) axx 0.0 axz)
        (emit-vertex fx0 by0 fz0 axx 0.0 axz)
        (emit-vertex fx1 by1 fz1 axx 0.0 axz)
        (emit-vertex (- cx (* half-len axx)) cy (- cz (* half-len axz))
                     (- 0.0 axx) 0.0 (- 0.0 axz))
        (emit-vertex kx1 by1 kz1 (- 0.0 axx) 0.0 (- 0.0 axz))
        (emit-vertex kx0 by0 kz0 (- 0.0 axx) 0.0 (- 0.0 axz))))))

;; A yaw-rotated ellipsoid (rx ry rz half-extents; a sphere when they match) --
;; a UV mesh of lon-segs longitude wedges x lat-segs latitude bands, poles
;; included. Non-uniform radii need the inverse-square normal correction
;; (normalize(n/r) rather than n) to stay lit correctly.
;; The ellipsoid being sampled: latched once per primitive so the per-vertex
;; call carries only the unit-sphere direction (see the signature note above).
(defvar *el-cx* 0.0)
(defvar *el-cy* 0.0)
(defvar *el-cz* 0.0)
(defvar *el-rx* 1.0)
(defvar *el-ry* 1.0)
(defvar *el-rz* 1.0)
(defvar *el-c* 1.0)
(defvar *el-s* 0.0)

;; One ellipsoid vertex from a UNIT-sphere direction: scales it into the
;; ellipsoid, yaw-rotates the result into world space, and derives the
;; correctly-lit normal (scale by 1/r, then renormalize) the same way.
(defun ellipsoid-vertex (ux uy uz)
  (let* ((nx (/ ux *el-rx*)) (ny (/ uy *el-ry*)) (nz (/ uz *el-rz*))
         (nl (max 0.000001 (sqrt (+ (* nx nx) (* ny ny) (* nz nz)))))
         (nnx (/ nx nl)) (nny (/ ny nl)) (nnz (/ nz nl))
         (lx (* ux *el-rx*)) (lz (* uz *el-rz*))
         (wx (+ *el-cx* (* *el-c* lx) (* *el-s* lz)))
         (wz (+ *el-cz* (- (* *el-c* lz) (* *el-s* lx))))
         (wy (+ *el-cy* (* uy *el-ry*)))
         (rnx (+ (* *el-c* nnx) (* *el-s* nnz)))
         (rnz (- (* *el-c* nnz) (* *el-s* nnx))))
    (emit-vertex wx wy wz rnx nny rnz)))

(defun emit-ellipsoid (cx cy cz rx ry rz yaw lon-segs lat-segs)
  (setq *el-cx* cx *el-cy* cy *el-cz* cz)
  (setq *el-rx* rx *el-ry* ry *el-rz* rz)
  (setq *el-c* (cos yaw) *el-s* (sin yaw))
  (dotimes (j lat-segs)
    (let* ((th0 (* +pi+ (/ (float j) (float lat-segs))))
           (th1 (* +pi+ (/ (float (+ j 1)) (float lat-segs))))
           (y0 (cos th0)) (rad0 (sin th0))
           (y1 (cos th1)) (rad1 (sin th1)))
      (dotimes (i lon-segs)
        (let* ((p0 (* +two-pi+ (/ (float i) (float lon-segs))))
               (p1 (* +two-pi+ (/ (float (+ i 1)) (float lon-segs))))
               (cp0 (cos p0)) (sp0 (sin p0))
               (cp1 (cos p1)) (sp1 (sin p1))
               ;; the four unit-sphere corners of this lat/lon quad
               (u00x (* rad0 cp0)) (u00z (* rad0 sp0))
               (u01x (* rad0 cp1)) (u01z (* rad0 sp1))
               (u10x (* rad1 cp0)) (u10z (* rad1 sp0))
               (u11x (* rad1 cp1)) (u11z (* rad1 sp1)))
          (ellipsoid-vertex u00x y0 u00z)
          (ellipsoid-vertex u10x y1 u10z)
          (ellipsoid-vertex u11x y1 u11z)
          (ellipsoid-vertex u00x y0 u00z)
          (ellipsoid-vertex u11x y1 u11z)
          (ellipsoid-vertex u01x y0 u01z))))))

;; --- the articulated limb ------------------------------------------------------
;;
;; A tapered tube (a truncated cone) between two ARBITRARY world points. This is
;; the primitive the older shape vocabulary was missing: emit-cylinder is
;; upright and emit-cyl-beam is horizontal, so every limb built from them had to
;; be a straight vertical post or a straight horizontal plank -- which is
;; exactly why the walkers read as tables and the arms as broomsticks. With a
;; free-standing segment, a leg becomes thigh + knee ball + shin at real angles,
;; and it can taper the way a limb does.
;;
;; The frame: `a` is the unit axis, and t1/t2 are any two unit vectors
;; perpendicular to it (built from a reference vector deliberately chosen NOT to
;; be near-parallel to the axis). The side normal is the radial direction tilted
;; along the axis by the taper's slope, so a strongly-tapered cone is lit as a
;; cone and not as a cylinder.

;; The far-end radius and the ring count are latched rather than passed: the
;; signature is already at the seven-parameter ceiling with the two endpoints
;; and the near radius. `taper` is CONSUMED -- emit-limb resets it after use --
;; so a forgotten taper yields a plain cylinder rather than a stale cone.
(defvar *limb-r1* -1.0)                 ; < 0 = "same as r0", i.e. no taper
(defvar *limb-n* 8)                     ; ring segments, before the LOD scale
(defvar *limb-caps* t)                  ; end discs; off for a buried base

(defun taper (r1) (setq *limb-r1* r1))
(defun limb-sides (n) (setq *limb-n* n))
(defun limb-caps (b) (setq *limb-caps* b))

(defvar *lx0* 0.0)                      ; the limb's cached perpendicular frame
(defvar *ly0* 0.0)
(defvar *lz0* 0.0)
(defvar *lx1* 0.0)
(defvar *ly1* 0.0)
(defvar *lz1* 0.0)

(defun limb-frame (ax ay az)
  ;; t1 = normalize(u x a), t2 = a x t1, for a reference u that is (0 1 0)
  ;; unless the axis is itself near-vertical, in which case (1 0 0).
  (let* ((vert (> (* ay ay) 0.86))
         (ux (if vert 1.0 0.0))
         (uy (if vert 0.0 1.0))
         (p1x (* uy az))
         (p1y (- 0.0 (* ux az)))
         (p1z (- (* ux ay) (* uy ax)))
         (p1l (max 0.000001 (sqrt (+ (* p1x p1x) (* p1y p1y) (* p1z p1z))))))
    (setq *lx0* (/ p1x p1l) *ly0* (/ p1y p1l) *lz0* (/ p1z p1l))
    (setq *lx1* (- (* ay *lz0*) (* az *ly0*))
          *ly1* (- (* az *lx0*) (* ax *lz0*))
          *lz1* (- (* ax *ly0*) (* ay *lx0*)))))

(defun emit-limb (x0 y0 z0 x1 y1 z1 r0)
  (let* ((dx (- x1 x0)) (dy (- y1 y0)) (dz (- z1 z0))
         (r1 (if (< *limb-r1* 0.0) r0 *limb-r1*))
         (nsides (segs *limb-n*))
         (len (sqrt (+ (* dx dx) (* dy dy) (* dz dz)))))
    (setq *limb-r1* -1.0)               ; consumed
    (when (> len 0.000001)
      (let* ((ax (/ dx len)) (ay (/ dy len)) (az (/ dz len))
             (slope (/ (- r0 r1) len)))
        (limb-frame ax ay az)
        (dotimes (i nsides)
          (let* ((a0 (* +two-pi+ (/ (float i) (float nsides))))
                 (a1 (* +two-pi+ (/ (float (+ i 1)) (float nsides))))
                 (c0 (cos a0)) (s0 (sin a0))
                 (c1 (cos a1)) (s1 (sin a1))
                 ;; the two radial directions bounding this side quad
                 (d0x (+ (* c0 *lx0*) (* s0 *lx1*)))
                 (d0y (+ (* c0 *ly0*) (* s0 *ly1*)))
                 (d0z (+ (* c0 *lz0*) (* s0 *lz1*)))
                 (d1x (+ (* c1 *lx0*) (* s1 *lx1*)))
                 (d1y (+ (* c1 *ly0*) (* s1 *ly1*)))
                 (d1z (+ (* c1 *lz0*) (* s1 *lz1*)))
                 ;; the taper tilts the side normal along the axis
                 (m0x (+ d0x (* slope ax))) (m0y (+ d0y (* slope ay)))
                 (m0z (+ d0z (* slope az)))
                 (m0l (max 0.000001 (sqrt (+ (* m0x m0x) (* m0y m0y) (* m0z m0z)))))
                 (n0x (/ m0x m0l)) (n0y (/ m0y m0l)) (n0z (/ m0z m0l))
                 (m1x (+ d1x (* slope ax))) (m1y (+ d1y (* slope ay)))
                 (m1z (+ d1z (* slope az)))
                 (m1l (max 0.000001 (sqrt (+ (* m1x m1x) (* m1y m1y) (* m1z m1z)))))
                 (n1x (/ m1x m1l)) (n1y (/ m1y m1l)) (n1z (/ m1z m1l))
                 (a0x (+ x0 (* r0 d0x))) (a0y (+ y0 (* r0 d0y))) (a0z (+ z0 (* r0 d0z)))
                 (b0x (+ x1 (* r1 d0x))) (b0y (+ y1 (* r1 d0y))) (b0z (+ z1 (* r1 d0z)))
                 (a1x (+ x0 (* r0 d1x))) (a1y (+ y0 (* r0 d1y))) (a1z (+ z0 (* r0 d1z)))
                 (b1x (+ x1 (* r1 d1x))) (b1y (+ y1 (* r1 d1y))) (b1z (+ z1 (* r1 d1z))))
            (emit-vertex a0x a0y a0z n0x n0y n0z)
            (emit-vertex b0x b0y b0z n0x n0y n0z)
            (emit-vertex b1x b1y b1z n1x n1y n1z)
            (emit-vertex a0x a0y a0z n0x n0y n0z)
            (emit-vertex b1x b1y b1z n1x n1y n1z)
            (emit-vertex a1x a1y a1z n1x n1y n1z)
            ;; both ends are capped by default: a limb is nearly always met by
            ;; a joint ball or sunk into a hull, but the one time it is not, an
            ;; open tube shows the inside of the figure through it. A buried
            ;; end (a mountain's base) turns the caps off instead -- a lid
            ;; underground is not just wasted, it is the thing that surfaces as
            ;; a dark plate the moment the cone leans.
            (when *limb-caps*
              (emit-vertex x0 y0 z0 (- 0.0 ax) (- 0.0 ay) (- 0.0 az))
              (emit-vertex a1x a1y a1z (- 0.0 ax) (- 0.0 ay) (- 0.0 az))
              (emit-vertex a0x a0y a0z (- 0.0 ax) (- 0.0 ay) (- 0.0 az))
              (emit-vertex x1 y1 z1 ax ay az)
              (emit-vertex b0x b0y b0z ax ay az)
              (emit-vertex b1x b1y b1z ax ay az))))))))

;; --- the rounded box ------------------------------------------------------------
;;
;; A box with genuinely rounded edges and corners: the Minkowski sum of a
;; smaller "core" box (half-extents shrunk by the fillet radius br) and a sphere
;; of radius br. Sampling it is one uniform rule -- take a point P on the outer
;; box, clamp it into the core box to get q, and place the surface at
;; q + br * normalize(P - q), whose normal is exactly that same normalized
;; direction. On a face interior the clamp does nothing but move q inward by br,
;; so the flat face comes back exactly; near an edge or a corner the direction
;; swings and traces the fillet. Splitting each face's parameter range at the
;; core boundary (-h, -a, +a, +h) puts the sample lines exactly where the
;; curvature starts, so a 3x3 grid per face is enough: one flat centre quad,
;; four edge fillets, four corner fillets.
;;
;; This is what armour plates, hulls, packs and boots want -- shapes that ARE
;; boxes but were never machined with knife edges.

(defvar *rb-hx* 1.0)                    ; the box being sampled (local frame)
(defvar *rb-hy* 1.0)
(defvar *rb-hz* 1.0)
(defvar *rb-ax* 1.0)                    ; ... and its core half-extents
(defvar *rb-ay* 1.0)
(defvar *rb-az* 1.0)
(defvar *rb-br* 0.1)
(defvar *rb-cx* 0.0)
(defvar *rb-cy* 0.0)
(defvar *rb-cz* 0.0)
(defvar *rb-c* 1.0)
(defvar *rb-s* 0.0)

(defun clamp1 (v lim) (max (- 0.0 lim) (min lim v)))

(defun rbox-vertex (px py pz)
  ;; one sample of the rounded surface, from a point on the *outer* box
  (let* ((qx (clamp1 px *rb-ax*))
         (qy (clamp1 py *rb-ay*))
         (qz (clamp1 pz *rb-az*))
         (dx (- px qx)) (dy (- py qy)) (dz (- pz qz))
         (dl (max 0.000001 (sqrt (+ (* dx dx) (* dy dy) (* dz dz)))))
         (nx (/ dx dl)) (ny (/ dy dl)) (nz (/ dz dl))
         (lx (+ qx (* *rb-br* nx)))
         (ly (+ qy (* *rb-br* ny)))
         (lz (+ qz (* *rb-br* nz))))
    (emit-vertex (+ *rb-cx* (* *rb-c* lx) (* *rb-s* lz))
                 (+ *rb-cy* ly)
                 (+ *rb-cz* (- (* *rb-c* lz) (* *rb-s* lx)))
                 (+ (* *rb-c* nx) (* *rb-s* nz))
                 ny
                 (- (* *rb-c* nz) (* *rb-s* nx)))))

;; one quad of a face, given in that face's (u v) parameters plus the pinned
;; coordinate `f`; `axis` selects the permutation back to (x y z).
(defun rbox-quad (axis f u0 v0 u1 v1)
  (cond ((= axis 0)
         (rbox-vertex f u0 v0) (rbox-vertex f u1 v0) (rbox-vertex f u1 v1)
         (rbox-vertex f u0 v0) (rbox-vertex f u1 v1) (rbox-vertex f u0 v1))
        ((= axis 1)
         (rbox-vertex u0 f v0) (rbox-vertex u1 f v0) (rbox-vertex u1 f v1)
         (rbox-vertex u0 f v0) (rbox-vertex u1 f v1) (rbox-vertex u0 f v1))
        (t
         (rbox-vertex u0 v0 f) (rbox-vertex u1 v0 f) (rbox-vertex u1 v1 f)
         (rbox-vertex u0 v0 f) (rbox-vertex u1 v1 f) (rbox-vertex u0 v1 f))))

;; one face of the outer box, as a 3x3 grid split at the core boundary. The
;; face is addressed through a small permutation: `axis` says which coordinate
;; is pinned to the face (0 = x, 1 = y, 2 = z) and `sgn` which side.
(defun rbox-face (axis sgn)
  (let* ((f (if (= axis 0) (* sgn *rb-hx*) (if (= axis 1) (* sgn *rb-hy*) (* sgn *rb-hz*))))
         ;; the two in-plane axes and their split points
         (uh (if (= axis 0) *rb-hy* *rb-hx*))
         (ua (if (= axis 0) *rb-ay* *rb-ax*))
         (vh (if (= axis 2) *rb-hy* *rb-hz*))
         (va (if (= axis 2) *rb-ay* *rb-az*)))
    (dotimes (iu 3)
      (let ((u0 (if (= iu 0) (- 0.0 uh) (if (= iu 1) (- 0.0 ua) ua)))
            (u1 (if (= iu 0) (- 0.0 ua) (if (= iu 1) ua uh))))
        (dotimes (iv 3)
          (let ((v0 (if (= iv 0) (- 0.0 vh) (if (= iv 1) (- 0.0 va) va)))
                (v1 (if (= iv 0) (- 0.0 va) (if (= iv 1) va vh))))
            (rbox-quad axis f u0 v0 u1 v1)))))))

;; The yaw is latched (see the signature note): every call site is a figure
;; part, and the figure's frame already knows its heading.
(defun rbox-yaw (yaw) (setq *rb-c* (cos yaw) *rb-s* (sin yaw)))

(defun emit-rbox (cx cy cz hx hy hz br)
  (let ((r (max 0.004 (min br (* 0.98 (min hx (min hy hz)))))))
    (setq *rb-cx* cx *rb-cy* cy *rb-cz* cz)
    (setq *rb-hx* hx *rb-hy* hy *rb-hz* hz *rb-br* r)
    (setq *rb-ax* (max 0.0 (- hx r)) *rb-ay* (max 0.0 (- hy r))
          *rb-az* (max 0.0 (- hz r)))
    (rbox-face 0 1.0)
    (rbox-face 0 -1.0)
    (rbox-face 1 1.0)
    (rbox-face 1 -1.0)
    (rbox-face 2 1.0)
    (rbox-face 2 -1.0)))

;; --- level of detail -----------------------------------------------------------
;;
;; A rounded box is nine quads a face and a limb is a ring per side, so the cast
;; costs far more per figure than the old all-boxes one did. What keeps the
;; frame honest is that detail nobody can resolve is not drawn: *lod* is picked
;; per figure from its APPARENT size (world height over distance to the eye), so
;; the trooper in your face is fully machined and the one across the arena is
;; the same model at a coarser ring count with its fillets dropped. `segs`
;; scales a ring/band count, and `roundp` is the switch a part uses to fall back
;; from a rounded box to a soft-normalled plain one.

(defvar *lod* 2)

(defun set-lod (x z size)
  (let* ((dx (- x (aref *eye* 0)))
         (dy (- 1.0 (aref *eye* 1)))
         (dz (- z (aref *eye* 2)))
         (d (max 0.5 (sqrt (+ (* dx dx) (* dy dy) (* dz dz)))))
         (k (/ size d)))
    ;; the top tier is deliberately narrow: a rounded box is nine quads a face,
    ;; so full detail is worth paying for only on a figure that genuinely fills
    ;; part of the screen. At the default follow distance that is you, whoever
    ;; is in melee range, and a walker you are standing under
    (setq *lod* (cond ((> k 0.32) 2) ((> k 0.075) 1) (t 0)))))

(defun segs (n)
  (cond ((>= *lod* 2) n)
        ((= *lod* 1) (max 4 (floor (* 0.7 (float n)))))
        (t (max 3 (floor (* 0.45 (float n)))))))

(defun roundp () (>= *lod* 2))

;; A local frame for composite figures: set-origin latches a world position +
;; yaw, and part emits one box given in that frame's local coordinates. The
;; character part-lists below stay in readable scalar coordinates -- they are
;; fixed geometry offsets, not the game's moving state.
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
  (setq *os* (sin yaw))
  (rbox-yaw yaw))

;; local -> world, one coordinate at a time (a limb needs both endpoints
;; converted, and this Lisp has no cheap 3-value return worth the ceremony)
(defun lwx (lx lz) (+ *ox* (* lx *oc*) (* lz *os*)))
(defun lwy (ly) (+ *oy* ly))
(defun lwz (lx lz) (+ *oz* (- (* lz *oc*) (* lx *os*))))

;; The figure-local wrappers. There is no plain `part` any more: with the
;; rounded box, the tapered limb and the joint ball in the vocabulary, nothing
;; in the cast turned out to want a bare yaw-rotated box -- the handful of
;; genuinely machined slabs left (a pistol's receiver, its grip) call emit-box
;; directly, preceded by `soften`.
(defun part-cyl (lx ly lz r hy &optional (nsides 8))
  (emit-cylinder (lwx lx lz) (lwy ly) (lwz lx lz) r hy *oyaw* (segs nsides)))

(defun part-ellipsoid (lx ly lz rx ry rz &optional (lon-segs 8) (lat-segs 5))
  (emit-ellipsoid (lwx lx lz) (lwy ly) (lwz lx lz)
                  rx ry rz *oyaw* (segs lon-segs) (segs lat-segs)))

;; A rounded box in the local frame -- when the fillet is actually worth nine
;; times the triangles of a plain box, which is a question about THIS PART, not
;; about the figure it belongs to. A torso at arm's length earns its fillet; the
;; 2cm brow ridge on the same figure never will, and neither will anything at
;; all on a trooper across the arena. So the test is the part's own apparent
;; size -- its largest half-extent over its distance to the eye -- with the
;; figure's detail tier as a floor. Everything below falls back to the plain
;; box with soft edge normals, which at that size is indistinguishable.
(defun rbox-worth-it (x y z h)
  (let* ((dx (- x (aref *eye* 0)))
         (dy (- y (aref *eye* 1)))
         (dz (- z (aref *eye* 2)))
         (d (max 0.5 (sqrt (+ (* dx dx) (* dy dy) (* dz dz))))))
    (> (/ h d) 0.013)))

(defun part-rbox (lx ly lz hx hy hz br)
  (let ((wx (lwx lx lz)) (wy (lwy ly)) (wz (lwz lx lz)))
    (if (and (roundp) (rbox-worth-it wx wy wz (max hx (max hy hz))))
        (emit-rbox wx wy wz hx hy hz br)
        (progn (soften 0.5)
               (emit-box wx wy wz hx hy hz *oyaw*)))))

;; A free-standing tapered segment between two LOCAL points -- the articulated
;; limb in figure coordinates. Precede it with (taper r1) for a cone.
(defun part-limb (lx0 ly0 lz0 lx1 ly1 lz1 r0)
  (emit-limb (lwx lx0 lz0) (lwy ly0) (lwz lx0 lz0)
             (lwx lx1 lz1) (lwy ly1) (lwz lx1 lz1) r0))

;; A joint ball at a local point -- what turns two limb segments into a knee or
;; a shoulder instead of two sticks that happen to touch.
(defun part-joint (lx ly lz r)
  ;; coarser than a head or a helmet on purpose: a joint ball is small on
  ;; screen and there are a dozen of them per figure
  (emit-ellipsoid (lwx lx lz) (lwy ly) (lwz lx lz)
                  r r r *oyaw* (segs 6) (segs 3)))

;; colour + emissive + shine helpers
;; *hit-tint* (0..1) reddens and lights whatever `col` draws next -- set around
;; an enemy's body while its damage flash is active, 0 otherwise, so a struck
;; enemy glows red without touching any call site.
(defvar *hit-tint* 0.0)
(defun col (r g b &optional (shine 0.05))
  ;; shine defaults low (cloth/skin/snow); pass an explicit shine for armor
  ;; plates, helmet domes, gun metal and blade hilts -- see (metal ...) below.
  (set-shine shine)
  (if (> *hit-tint* 0.0)
      (let ((k *hit-tint*))
        (set-emissive (* 0.55 k))
        (set-color (+ r (* k (- 1.0 r))) (* g (- 1.0 k)) (* b (- 1.0 k))))
      (progn (set-emissive 0.0) (set-color r g b))))
(defun metal (r g b) (col r g b 0.85))
(defun glow-col (r g b) (set-shine 0.0) (set-emissive 1.0) (set-color r g b))

;; --- the snow field -----------------------------------------------------------
;;
;; Static geometry, baked once: the snow plate, low drifts, the ring of Hoth
;; mountains (all boxes -- broken terrain reads fine as facets), plus ice
;; boulders and clouds (ellipsoids -- the two static shapes that actually read
;; as round in life, so worth the baked-once extra triangles). Each block is
;; (x0 y0 z0 x1 y1 z1 r g b); nothing here collides -- the arena is open.

;; The snow plate. It runs far beyond the mountains, not merely up to them: the
;; plate is a solid slab, so wherever its rim falls inside the view you see the
;; slab's own edge and underside -- mid-grey and near-black under this light --
;; ruled across the snow. Pushing the rim past the fog's saturation distance is
;; what makes the field read as endless. It is also kept thin, so a stray
;; sightline under a peak sees as little of the edge as possible.
(defconstant +scenery+
  '((-260.0 -0.6 -260.0 300.0 0.0 260.0 0.90 0.93 0.98)))

;; The horizon used to be four tall boxes, which from inside the arena is a
;; flat grey wall with two conspicuous vertical corners running up the sky. It
;; is a broken RIDGE now: squat cones whose apexes are nudged off centre and
;; whose skirts overlap, at jittered sizes, so the skyline is a range. All of
;; it is baked once with the rest of the field, so the triangles cost nothing
;; per frame.
(defconstant +peak-base+ -2.6)          ; how far the cone is planted below the snow

(defun emit-peak (px pz r h)
  ;; The apex leans off the base's centre -- that lean is the difference
  ;; between a mountain and a party hat. Two bounds on it: a share of the
  ;; SMALLER of radius and height, or a squat wide cone leans over into a
  ;; wedge; and, decisively, little enough that the (tilted) base ring stays
  ;; buried. Tilting a cone tilts its base ring with it, and the ring's uphill
  ;; edge rises by about radius * lean / height -- for a 25m-wide apron that is
  ;; metres, so an unbounded lean lifts the buried end clean out of the snow.
  (let* ((rise (- h +peak-base+))
         (lean (min (* 0.30 (min r h))
                    (* 0.85 (/ (* (- 0.0 +peak-base+) rise) r)))))
    (col (rand-range 0.79 0.87) (rand-range 0.85 0.91) (rand-range 0.93 0.98))
    (limb-sides 7)
    (limb-caps nil)                     ; the base is underground, the tip a point
    (taper 0.0)                         ; a true point, so no lid is missed
    (emit-limb px +peak-base+ pz
               (+ px (rand-range (- 0.0 lean) lean)) h
               (+ pz (rand-range (- 0.0 lean) lean))
               r)
    (limb-caps t)))

;; The apron the ranges stand on: very wide, very low cones. A box shelf would
;; do the same job of stopping daylight showing between the peaks' skirts, but
;; a box has vertical faces, and the one facing away from the sun becomes a
;; dark grey band ruled across the horizon -- exactly the wall the peaks were
;; brought in to replace. A cone has no vertical face; every normal on it
;; points mostly up, so the whole apron stays snow-bright from any angle.
(defun emit-apron (x0 z0 dx dz n)
  (dotimes (i n)
    (let ((t0 (float i)))
      (emit-peak (+ x0 (* dx t0) (rand-range -8.0 8.0))
                 (+ z0 (* dz t0) (rand-range -8.0 8.0))
                 (rand-range 22.0 34.0)
                 (rand-range 1.5 3.4)))))

(defun emit-peaks (x0 z0 dx dz n hlo hhi)
  ;; the perpendicular jitter is deliberately as large as the step: a range
  ;; laid out on a straight line reads as a fence of cones
  (dotimes (i n)
    (let ((t0 (float i)))
      (emit-peak (+ x0 (* dx t0) (rand-range -9.0 9.0))
                 (+ z0 (* dz t0) (rand-range -9.0 9.0))
                 (rand-range 9.0 20.0)
                 (rand-range hlo hhi)))))

(defun emit-block (b)
  (col (nth 6 b) (nth 7 b) (nth 8 b))
  (emit-box (* 0.5 (+ (nth 0 b) (nth 3 b)))
            (* 0.5 (+ (nth 1 b) (nth 4 b)))
            (* 0.5 (+ (nth 2 b) (nth 5 b)))
            (* 0.5 (- (nth 3 b) (nth 0 b)))
            (* 0.5 (- (nth 4 b) (nth 1 b)))
            (* 0.5 (- (nth 5 b) (nth 2 b)))
            0.0))

;; low snow drifts: smooth wind-blown mounds, not plateaus -- same
;; (x0 y0 z0 x1 y1 z1 r g b) AABB shape as a +scenery+ block, but drawn as a
;; squashed ellipsoid whose base sits at y0 (flush with the snow plate) and
;; crests at y1, so it reads as a dome rather than a box. Baked once, so the
;; extra triangles over emit-block are free.
(defconstant +drifts+
  '((6.0 0.0 -14.0 12.0 0.7 -9.0 0.95 0.97 1.00)
    (18.0 0.0 10.0 25.0 0.9 15.0 0.95 0.97 1.00)
    (-8.0 0.0 6.0 -2.0 0.6 11.0 0.95 0.97 1.00)
    (34.0 0.0 -10.0 41.0 1.0 -4.0 0.95 0.97 1.00)
    (28.0 0.0 18.0 36.0 0.8 24.0 0.95 0.97 1.00)))

(defun emit-drift (b)
  (col (nth 6 b) (nth 7 b) (nth 8 b))
  (emit-ellipsoid (* 0.5 (+ (nth 0 b) (nth 3 b)))
                   (nth 1 b)
                   (* 0.5 (+ (nth 2 b) (nth 5 b)))
                   (* 0.5 (- (nth 3 b) (nth 0 b)))
                   (- (nth 4 b) (nth 1 b))
                   (* 0.5 (- (nth 5 b) (nth 2 b)))
                   0.0 12 6))

;; ice boulders (cold blue-grey): center + radius + a squash/yaw so a plain
;; sphere reads as a lumpy rock, not a ball-bearing.
(defconstant +boulders+
  '((3.2 0.9 13.2 1.2 0.62 0.9)
    (14.9 0.65 -5.1 0.9 0.55 -0.9)
    (31.3 1.1 5.3 1.3 0.68 1.7)
    (-5.0 0.75 -11.0 1.0 0.60 -1.3)
    (45.5 1.3 9.5 1.5 0.75 0.4)))

(defun emit-boulder (b)
  (col 0.64 0.72 0.81)
  (emit-ellipsoid (nth 0 b) (nth 1 b) (nth 2 b)
                   (nth 3 b) (nth 4 b) (* 0.9 (nth 3 b)) (nth 5 b) 10 6))

;; a few clouds: each is a small cluster of overlapping ellipsoid puffs
;; (center + half-width/height/depth), fluffier than one stretched blob.
(defconstant +clouds+
  '((15.0 16.0 -27.5 5.0 1.0 2.5)
    (39.5 18.0 25.0 5.5 1.0 3.0)
    (-9.0 17.0 7.0 5.0 1.0 3.0)))

(defun emit-cloud (c)
  (let ((cx (nth 0 c)) (cy (nth 1 c)) (cz (nth 2 c))
        (w (nth 3 c)) (h (nth 4 c)) (d (nth 5 c)))
    (col 0.99 0.99 1.00)
    (emit-ellipsoid cx cy cz (* 0.55 w) h (* 0.85 d) 0.0 10 5)
    (emit-ellipsoid (- cx (* 0.32 w)) (- cy (* 0.15 h)) cz
                     (* 0.40 w) (* 0.75 h) (* 0.70 d) 0.0 8 4)
    (emit-ellipsoid (+ cx (* 0.34 w)) (- cy (* 0.10 h)) cz
                     (* 0.42 w) (* 0.80 h) (* 0.72 d) 0.0 8 4)
    (emit-ellipsoid cx (+ cy (* 0.35 h)) (+ cz (* 0.10 d))
                     (* 0.36 w) (* 0.70 h) (* 0.60 d) 0.0 8 4)))

(defun bake-static ()
  (setq *v* 0)
  (dolist (b +scenery+) (emit-block b))
  ;; the apron first, then the four ranges: each is a near row of low hills
  ;; with a far row of tall peaks behind it, so the horizon has depth instead
  ;; of one silhouette
  (emit-apron -90.0 -66.0 22.0 0.0 10)
  (emit-apron -90.0 66.0 22.0 0.0 10)
  (emit-apron -70.0 -66.0 0.0 20.0 8)
  (emit-apron 90.0 -66.0 0.0 20.0 8)
  (emit-peaks -84.0 -60.0 13.0 0.0 15 5.0 11.0)
  (emit-peaks -84.0 -76.0 13.0 0.0 15 12.0 24.0)
  (emit-peaks -84.0 60.0 13.0 0.0 15 5.0 10.0)
  (emit-peaks -84.0 76.0 13.0 0.0 15 11.0 22.0)
  (emit-peaks -62.0 -60.0 0.0 12.0 11 5.0 11.0)
  (emit-peaks -80.0 -60.0 0.0 12.0 11 12.0 25.0)
  (emit-peaks 82.0 -60.0 0.0 12.0 11 5.0 11.0)
  (emit-peaks 100.0 -60.0 0.0 12.0 11 13.0 26.0)
  (limb-sides 8)                        ; restore the default ring count
  (dolist (b +drifts+) (emit-drift b))
  (dolist (b +boulders+) (emit-boulder b))
  (dolist (c +clouds+) (emit-cloud c))
  (setq *static-verts* *v*)
  (gl:bind-buffer gl:+array-buffer+ *buf*)
  (gl-upload-vertices 0 (* *static-verts* 11)))

;; --- the player (you) ---------------------------------------------------------

(defconstant +run-speed+ 6.0)
(defconstant +field-min-x+ -34.0)
(defconstant +field-max-x+ 56.0)
(defconstant +field-min-z+ -34.0)
(defconstant +field-max-z+ 34.0)
(defconstant +player-max-hp+ 120.0)
(defconstant +gravity+ 26.0)
(defconstant +jump-v+ 9.2)             ; a roomy hop, apex ~1.6
(defconstant +invuln+ 0.6)             ; i-frames after taking a hit

(defvar *ppos* #f(0.0 0.0 0.0))        ; player position
(defvar *pvel* #f(0.0 0.0 0.0))        ; velocity (y = vertical, jump / gravity)
(defvar *grounded* t)
(defvar *pyaw* 0.0)                     ; render facing = -cam-yaw
(defvar *php* 120.0)
(defvar *inv-t* 0.0)                    ; invulnerability countdown
(defvar *hurt-flash* 0.0)               ; brief red vignette timer (HUD)
(defvar *run-phase* 0.0)

(defvar *weapon* 1)                     ; 0 lightsaber, 1 blaster (start armed with the blaster)
(defvar *attack* 0.0)                   ; 1.0 while the mouse button is held
(defvar *attack-prev* nil)
(defvar *swing-t* 0.0)                  ; lightsaber swing countdown
(defvar *swing-cd* 0.0)
(defvar *swing-hit* nil)                ; damage applied once per swing
(defvar *fire-cd* 0.0)                  ; blaster cadence

;; game state: 0 playing, 1 victory, 2 defeat
(defvar *state* 0)
(defvar *state-t* 0.0)
(defvar *last-tm* 0.0)
(defvar *pending-reset* nil)

;; keyboard state, forwarded by the page: 1.0 while held
(defvar *in-l* 0.0)                     ; A
(defvar *in-r* 0.0)                     ; D
(defvar *in-f* 0.0)                     ; W
(defvar *in-b* 0.0)                     ; S
(defvar *in-jump* 0.0)                  ; Space
(defvar *jump-prev* nil)

(defun set-key (code down)
  ;; Exported: 0 = A, 1 = D, 2 = W, 3 = S, 4 = Space; down is 1 or 0.
  (let ((val (if (= down 1) 1.0 0.0)))
    (cond ((= code 0) (setq *in-l* val))
          ((= code 1) (setq *in-r* val))
          ((= code 2) (setq *in-f* val))
          ((= code 3) (setq *in-b* val))
          ((= code 4) (setq *in-jump* val)))))

(defun set-attack (down)
  ;; Exported: mouse button, 1 down / 0 up.
  (setq *attack* (if (= down 1) 1.0 0.0)))

(defun switch-weapon ()
  ;; Exported: F toggles the lightsaber and the blaster.
  (setq *weapon* (if (= *weapon* 0) 1 0)))

(defun restart ()
  (setq *pending-reset* t))

;; --- blaster bolts ------------------------------------------------------------
;;
;; One pool shared by you, the troopers and the walkers. owner 0 = player,
;; 1 = enemy. Player bolts damage enemies; enemy bolts damage you unless your
;; lightsaber swing deflects them. Every bolt is a glowing capsule; position and
;; velocity are #f vectors, the rest scalars.

(defconstant +nbolt+ 48)
(defvar *bpos* (make-array +nbolt+ :initial-element nil))
(defvar *bvel* (make-array +nbolt+ :initial-element nil))
(defvar *bt* (make-array +nbolt+ :initial-element 0.0))     ; life left
(defvar *bown* (make-array +nbolt+ :initial-element 0))     ; 0 player / 1 enemy
(defvar *bdmg* (make-array +nbolt+ :initial-element 0.0))
(defvar *bsz* (make-array +nbolt+ :initial-element 1.0))    ; visual scale
(defvar *br* (make-array +nbolt+ :initial-element 1.0))
(defvar *bg* (make-array +nbolt+ :initial-element 1.0))
(defvar *bb* (make-array +nbolt+ :initial-element 1.0))
(defvar *balive* (make-array +nbolt+ :initial-element nil))

(defun spawn-bolt (pos dir speed owner dmg sz r g b)
  ;; dir is a (not necessarily unit) heading vector; normalise and launch.
  (let ((len (linalg:norm dir))
        (slot -1))
    (dotimes (i +nbolt+)
      (when (and (< slot 0) (not (aref *balive* i)))
        (setq slot i)))
    (when (and (>= slot 0) (> len 0.0001))
      (setf (aref *bpos* slot) pos)
      (setf (aref *bvel* slot) (linalg:mul dir (/ speed len)))
      (setf (aref *bt* slot) 3.0)
      (setf (aref *bown* slot) owner)
      (setf (aref *bdmg* slot) dmg)
      (setf (aref *bsz* slot) sz)
      (setf (aref *br* slot) r)
      (setf (aref *bg* slot) g)
      (setf (aref *bb* slot) b)
      (setf (aref *balive* slot) t))))

;; --- flashes (muzzle, hit sparks, explosions) ---------------------------------
;;
;; Short-lived additive glow puffs, drawn in the bloom pass.

(defconstant +nflash+ 24)
(defvar *fpos* (make-array +nflash+ :initial-element nil))
(defvar *ft* (make-array +nflash+ :initial-element 0.0))
(defvar *fttl* (make-array +nflash+ :initial-element 0.3))
(defvar *fsz* (make-array +nflash+ :initial-element 0.4))
(defvar *fr* (make-array +nflash+ :initial-element 1.0))
(defvar *fg* (make-array +nflash+ :initial-element 1.0))
(defvar *fb* (make-array +nflash+ :initial-element 1.0))

(defun spawn-flash (pos ttl sz r g b)
  (let ((slot -1))
    (dotimes (i +nflash+)
      (when (and (< slot 0) (<= (aref *ft* i) 0.0)) (setq slot i)))
    (when (>= slot 0)
      (setf (aref *fpos* slot) pos)
      (setf (aref *ft* slot) ttl)
      (setf (aref *fttl* slot) ttl)
      (setf (aref *fsz* slot) sz)
      (setf (aref *fr* slot) r)
      (setf (aref *fg* slot) g)
      (setf (aref *fb* slot) b))))

(defun update-flashes (dt)
  (dotimes (i +nflash+)
    (when (> (aref *ft* i) 0.0)
      (setf (aref *ft* i) (- (aref *ft* i) dt)))))

;; --- victory fireworks --------------------------------------------------------
;;
;; A particle pool: on victory, bursts launch over the arena, each scattering a
;; shell of glowing sparks that arc under a little gravity and fade. All drawn in
;; the additive bloom pass.

(defconstant +nfw+ 128)
(defvar *fwpos* (make-array +nfw+ :initial-element nil))
(defvar *fwvel* (make-array +nfw+ :initial-element nil))
(defvar *fwt* (make-array +nfw+ :initial-element 0.0))     ; life left
(defvar *fwmax* (make-array +nfw+ :initial-element 1.0))
(defvar *fwr* (make-array +nfw+ :initial-element 1.0))
(defvar *fwg* (make-array +nfw+ :initial-element 1.0))
(defvar *fwb* (make-array +nfw+ :initial-element 1.0))
(defvar *fw-launch* 0.0)                ; time to the next burst

(defun spawn-firework (center r g b)
  ;; scatter a shell of sparks from the burst point
  (dotimes (n 26)
    (let ((slot -1))
      (dotimes (i +nfw+)
        (when (and (< slot 0) (<= (aref *fwt* i) 0.0)) (setq slot i)))
      (when (>= slot 0)
        (let ((spd (rand-range 3.5 7.5))
              (life (rand-range 1.3 2.2)))
          (setf (aref *fwpos* slot) center)
          (setf (aref *fwvel* slot)
                (linalg:from-list (list (* (- (rand01) 0.5) 2.0 spd)
                      (* (- (rand01) 0.35) 2.0 spd)
                      (* (- (rand01) 0.5) 2.0 spd)) 'single-float))
          (setf (aref *fwt* slot) life)
          (setf (aref *fwmax* slot) life)
          (setf (aref *fwr* slot) r)
          (setf (aref *fwg* slot) g)
          (setf (aref *fwb* slot) b))))))

(defun launch-firework ()
  ;; a burst in view above the arena, in one of a few bright colors -- kept low
  ;; and near the player so it fills the frame rather than drifting off-screen
  (let ((center (linalg:from-list (list (+ (aref *ppos* 0) (rand-range -9.0 9.0))
                      (rand-range 3.5 7.5)
                      (+ (aref *ppos* 2) (rand-range -9.0 9.0))) 'single-float))
        (c (floor (* 6.0 (rand01)))))
    (cond ((= c 0) (spawn-firework center 1.0 0.28 0.28))     ; red
          ((= c 1) (spawn-firework center 1.0 0.78 0.20))     ; gold
          ((= c 2) (spawn-firework center 0.30 0.68 1.0))     ; blue
          ((= c 3) (spawn-firework center 0.35 1.0 0.40))     ; green
          ((= c 4) (spawn-firework center 0.82 0.40 1.0))     ; violet
          (t       (spawn-firework center 1.0 1.0 0.9)))))     ; white

(defun update-fireworks (dt)
  (let ((g (linalg:from-list (list 0.0 (* -3.2 dt) 0.0) 'single-float)))    ; one gravity impulse, reused
    (dotimes (i +nfw+)
      (when (> (aref *fwt* i) 0.0)
        (setf (aref *fwvel* i) (linalg:add (aref *fwvel* i) g))
        (setf (aref *fwpos* i) (linalg:add (aref *fwpos* i) (linalg:mul (aref *fwvel* i) dt)))
        (setf (aref *fwt* i) (- (aref *fwt* i) dt))))))

;; --- stormtroopers ------------------------------------------------------------

(defconstant +trooper-list+
  '((11.0 -5.0) (15.0 4.0) (13.0 -1.0)
    (20.0 -7.0) (19.0 8.0) (25.0 2.0)))

(defconstant +trooper-hp+ 20.0)
(defconstant +trooper-speed+ 2.6)
(defconstant +trooper-standoff+ 5.5)   ; how close they press before holding

(defvar *ntrooper* 0)
(defvar *tpos* nil)                     ; array of ground positions (#f vectors)
(defvar *thp* nil)
(defvar *tyaw* nil)
(defvar *tfire* nil)                    ; cooldown to the next shot
(defvar *talive* nil)
(defvar *tstep* nil)                    ; walk-cycle phase
(defvar *thit* nil)                     ; damage-flash timer

(defun parse-troopers ()
  (setq *ntrooper* (length +trooper-list+))
  (setq *tpos* (make-array *ntrooper* :initial-element nil))
  (setq *thp* (make-array *ntrooper* :initial-element 0.0))
  (setq *tyaw* (make-array *ntrooper* :initial-element 0.0))
  (setq *tfire* (make-array *ntrooper* :initial-element 0.0))
  (setq *talive* (make-array *ntrooper* :initial-element t))
  (setq *tstep* (make-array *ntrooper* :initial-element 0.0))
  (setq *thit* (make-array *ntrooper* :initial-element 0.0)))

(defun reset-troopers ()
  (let ((i 0))
    (dolist (e +trooper-list+)
      (setf (aref *tpos* i) (linalg:from-list (list (nth 0 e) 0.0 (nth 1 e)) 'single-float))
      (setf (aref *thp* i) +trooper-hp+)
      (setf (aref *tyaw* i) 0.0)
      (setf (aref *tfire* i) (rand-range 1.4 3.4))
      (setf (aref *talive* i) t)
      (setf (aref *tstep* i) 0.0)
      (setf (aref *thit* i) 0.0)
      (setq i (+ i 1)))))

(defun troopers-alive ()
  (let ((n 0))
    (dotimes (i *ntrooper*)
      (when (aref *talive* i) (setq n (+ n 1))))
    n))

;; Your ground position (y = 0), shared by the enemy AI: distances and
;; headings on the snow ignore his jump height.
(defun player-ground ()
  (linalg:from-list (list (aref *ppos* 0) 0.0 (aref *ppos* 2)) 'single-float))

(defun update-troopers (dt)
  (let ((pg (player-ground)))
    (dotimes (i *ntrooper*)
      (when (aref *talive* i)
        (let* ((p (aref *tpos* i))
               (to (linalg:sub pg p))       ; horizontal offset to the player
               (d (linalg:norm to)))
          (setf (aref *tyaw* i) (atan2 (- 0.0 (aref to 2)) (aref to 0)))
          (when (> d 0.001)
            (if (> d +trooper-standoff+)
                (progn                     ; press in toward the player
                  (setf (aref *tpos* i)
                        (linalg:add p (linalg:mul to (/ (* +trooper-speed+ dt) d))))
                  (setf (aref *tstep* i) (+ (aref *tstep* i) (* 7.0 dt))))
                (setf (aref *tstep* i) 0.0)))
          ;; fire at your chest when in range (a slower, lighter cadence so the
          ;; field is survivable and the saber can be waded in behind)
          (setf (aref *tfire* i) (- (aref *tfire* i) dt))
          (when (and (<= (aref *tfire* i) 0.0) (< d 22.0))
            (setf (aref *tfire* i) (rand-range 1.9 3.6))
            (let ((muzzle (linalg:from-list (list (aref p 0) 1.02 (aref p 2)) 'single-float)))
              (spawn-bolt muzzle
                          (linalg:sub (linalg:from-list (list (+ (aref *ppos* 0) (rand-range -0.5 0.5))
                                            (+ (aref *ppos* 1) 1.0)
                                            (+ (aref *ppos* 2) (rand-range -0.5 0.5))) 'single-float)
                                      muzzle)
                          24.0 1 4.0 1.0 1.0 0.28 0.20)
              (spawn-flash muzzle 0.08 0.35 1.0 0.4 0.25))))))))

;; --- AT-AT walkers ------------------------------------------------------------

(defconstant +atat-list+
  '((30.0 -6.0) (36.0 9.0)))

(defconstant +atat-hp+ 150.0)
(defconstant +atat-speed+ 0.9)

(defvar *natat* 0)
(defvar *apos* nil)                     ; array of ground positions (#f vectors)
(defvar *ahp* nil)
(defvar *ayaw* nil)
(defvar *afire* nil)
(defvar *aalive* nil)
(defvar *awreck* nil)                   ; collapse animation timer
(defvar *ahit* nil)                     ; damage-flash timer

(defun parse-atats ()
  (setq *natat* (length +atat-list+))
  (setq *apos* (make-array *natat* :initial-element nil))
  (setq *ahp* (make-array *natat* :initial-element 0.0))
  (setq *ayaw* (make-array *natat* :initial-element 0.0))
  (setq *afire* (make-array *natat* :initial-element 0.0))
  (setq *aalive* (make-array *natat* :initial-element t))
  (setq *awreck* (make-array *natat* :initial-element 0.0))
  (setq *ahit* (make-array *natat* :initial-element 0.0)))

(defun reset-atats ()
  (let ((i 0))
    (dolist (e +atat-list+)
      (setf (aref *apos* i) (linalg:from-list (list (nth 0 e) 0.0 (nth 1 e)) 'single-float))
      (setf (aref *ahp* i) +atat-hp+)
      (setf (aref *ayaw* i) 0.0)
      (setf (aref *afire* i) (rand-range 1.0 2.5))
      (setf (aref *aalive* i) t)
      (setf (aref *awreck* i) 0.0)
      (setf (aref *ahit* i) 0.0)
      (setq i (+ i 1)))))

(defun atats-alive ()
  (let ((n 0))
    (dotimes (i *natat*)
      (when (aref *aalive* i) (setq n (+ n 1))))
    n))

(defun update-atats (dt)
  (let ((pg (player-ground)))
    (dotimes (i *natat*)
      (if (aref *aalive* i)
          (let* ((p (aref *apos* i))
                 (to (linalg:sub pg p))
                 (d (linalg:norm to)))
            (setf (aref *ayaw* i) (atan2 (- 0.0 (aref to 2)) (aref to 0)))
            (when (> d 13.0)               ; a ranged behemoth: keep its stand-off
              (setf (aref *apos* i) (linalg:add p (linalg:mul to (/ (* +atat-speed+ dt) d)))))
            (setf (aref *afire* i) (- (aref *afire* i) dt))
            (when (<= (aref *afire* i) 0.0)
              (setf (aref *afire* i) (rand-range 3.0 4.6))
              ;; the muzzle is the tip of the chin blasters (local x 3.4, y 3.04)
              (let ((muzzle (linalg:from-list (list (+ (aref p 0) (* (cos (aref *ayaw* i)) 3.4))
                                  3.04
                                  (- (aref p 2) (* (sin (aref *ayaw* i)) 3.4))) 'single-float)))
                (spawn-bolt muzzle
                            (linalg:sub (linalg:from-list (list (+ (aref *ppos* 0) (rand-range -0.7 0.7))
                                              (+ (aref *ppos* 1) 1.0)
                                              (+ (aref *ppos* 2) (rand-range -0.7 0.7))) 'single-float)
                                        muzzle)
                            26.0 1 9.0 1.8 1.0 0.32 0.16)
                (spawn-flash muzzle 0.14 0.7 1.0 0.5 0.2))))
          (when (< (aref *awreck* i) 3.0)
            (setf (aref *awreck* i) (+ (aref *awreck* i) dt)))))))

;; --- Vader (the boss) ---------------------------------------------------
;;
;; Dormant at the far edge until both walkers fall, then he engages: he chases
;; you and swings his red blade in melee. His own lightsaber deflects blaster
;; bolts, so only your lightsaber wounds him.

(defconstant +vader-hp+ 160.0)
(defconstant +vader-speed+ 3.0)

(defvar *vpos* #f(44.0 0.0 0.0))        ; Vader's ground position
(defvar *vhp* 160.0)
(defvar *vyaw* 0.0)
(defvar *vactive* nil)
(defvar *valive* t)
(defvar *vswing* 0.0)                   ; melee swing animation / hit window
(defvar *vcd* 0.0)                      ; melee cooldown
(defvar *vhit* nil)
(defvar *vhitf* 0.0)                    ; damage-flash timer

(defun reset-vader ()
  (setq *vpos* #f(46.0 0.0 0.0))
  (setq *vhp* +vader-hp+)
  (setq *vyaw* +pi+)
  (setq *vactive* nil)
  (setq *valive* t)
  (setq *vswing* 0.0)
  (setq *vcd* 0.0)
  (setq *vhit* nil)
  (setq *vhitf* 0.0))

(defun update-enemy-flashes (dt)
  ;; decay every enemy's damage-flash timer, alive or not
  (dotimes (i *ntrooper*)
    (when (> (aref *thit* i) 0.0) (setf (aref *thit* i) (- (aref *thit* i) dt))))
  (dotimes (i *natat*)
    (when (> (aref *ahit* i) 0.0) (setf (aref *ahit* i) (- (aref *ahit* i) dt))))
  (when (> *vhitf* 0.0) (setq *vhitf* (- *vhitf* dt))))

(defun update-vader (dt)
  (when *valive*
    (unless *vactive*
      (when (= (atats-alive) 0)
        (setq *vactive* t)
        ;; a dramatic entrance: Vader strides in ahead of the player
        (setq *vpos* (linalg:add (player-ground)
                                 (linalg:from-list (list (* (cos *cam-yaw*) 15.0) 0.0 (* (sin *cam-yaw*) 15.0)) 'single-float)))
        (spawn-flash (linalg:from-list (list (aref *vpos* 0) 1.4 (aref *vpos* 2)) 'single-float) 1.1 3.8 1.0 0.2 0.18)
        (spawn-flash (linalg:from-list (list (aref *vpos* 0) 0.5 (aref *vpos* 2)) 'single-float) 0.9 3.0 0.9 0.15 0.15)))
    (when *vactive*
      (let* ((to (linalg:sub (player-ground) *vpos*))
             (d (linalg:norm to)))
        (setq *vyaw* (atan2 (- 0.0 (aref to 2)) (aref to 0)))
        (when (> d 2.1)
          (setq *vpos* (linalg:add *vpos* (linalg:mul to (/ (* +vader-speed+ dt) d)))))
        (when (> *vcd* 0.0) (setq *vcd* (- *vcd* dt)))
        (when (> *vswing* 0.0) (setq *vswing* (- *vswing* dt)))
        ;; open a melee swing when in reach
        (when (and (< d 2.6) (<= *vcd* 0.0))
          (setq *vswing* 0.45)
          (setq *vcd* 0.9)
          (setq *vhit* nil))
        ;; the blade connects at mid-swing
        (when (and (> *vswing* 0.0) (< *vswing* 0.28) (not *vhit*) (< d 3.0))
          (setq *vhit* t)
          (hurt-player 15.0))))))

;; --- damage plumbing ----------------------------------------------------------

(defun hurt-player (amount)
  ;; ignore hits during the brief i-frame window so fire cannot stack-kill
  (when (and (= *state* 0) (<= *inv-t* 0.0))
    (setq *php* (- *php* amount))
    (setq *inv-t* +invuln+)
    (setq *hurt-flash* 0.35)
    (when (<= *php* 0.0)
      (setq *php* 0.0)
      (setq *state* 2)
      (setq *state-t* 0.0))))

(defun hit-trooper (i dmg)
  (setf (aref *thp* i) (- (aref *thp* i) dmg))
  (setf (aref *thit* i) 0.16)                 ; flash red
  (when (<= (aref *thp* i) 0.0)
    (setf (aref *talive* i) nil)
    (let ((p (aref *tpos* i)))
      (spawn-flash (linalg:from-list (list (aref p 0) 0.7 (aref p 2)) 'single-float) 0.4 1.1 1.0 0.7 0.4))))

(defun hit-atat (i dmg)
  (setf (aref *ahp* i) (- (aref *ahp* i) dmg))
  (setf (aref *ahit* i) 0.16)                 ; flash red
  (when (<= (aref *ahp* i) 0.0)
    (setf (aref *aalive* i) nil)
    (setf (aref *awreck* i) 0.0)
    (let ((p (aref *apos* i)))
      (spawn-flash (linalg:from-list (list (aref p 0) 3.0 (aref p 2)) 'single-float) 0.7 3.2 1.0 0.7 0.3)
      (spawn-flash (linalg:from-list (list (aref p 0) 5.0 (aref p 2)) 'single-float) 0.6 2.2 1.0 0.5 0.2))))

(defun hit-vader (dmg)
  (setq *vhp* (- *vhp* dmg))
  (setq *vhitf* 0.16)                          ; flash red
  (spawn-flash (linalg:from-list (list (aref *vpos* 0) 1.4 (aref *vpos* 2)) 'single-float) 0.25 0.8 1.0 0.4 0.3)
  (when (<= *vhp* 0.0)
    (setq *vhp* 0.0)
    (setq *valive* nil)
    (spawn-flash (linalg:from-list (list (aref *vpos* 0) 1.4 (aref *vpos* 2)) 'single-float) 0.9 3.0 1.0 0.6 0.3)
    (launch-firework) (launch-firework) (launch-firework)   ; an instant volley
    (launch-firework) (launch-firework)
    (setq *state* 1)
    (setq *state-t* 0.0)))

;; --- bolt integration + collisions --------------------------------------------

(defun bolt-hits-enemies (i)
  ;; a player bolt: test the troopers, the walkers and Vader; return t on a hit.
  (let ((bp (aref *bpos* i))
        (hit nil))
    ;; troopers -- a generous body capsule so aimed fire connects
    (dotimes (j *ntrooper*)
      (when (and (not hit) (aref *talive* j))
        (let* ((tp (aref *tpos* j))
               (diff (linalg:sub bp (linalg:from-list (list (aref tp 0) 1.0 (aref tp 2)) 'single-float))))
          (when (< (linalg:dot diff diff) 0.45)
            (hit-trooper j (aref *bdmg* i))
            (spawn-flash bp 0.16 0.45 1.0 0.7 0.4)
            (setq hit t)))))
    ;; walkers -- a tall body, so test a horizontal disc over a vertical span
    (dotimes (j *natat*)
      (when (and (not hit) (aref *aalive* j))
        (let* ((ap (aref *apos* j))
               (dx (- (aref bp 0) (aref ap 0)))
               (dz (- (aref bp 2) (aref ap 2))))
          (when (and (< (+ (* dx dx) (* dz dz)) 3.6)
                     (> (aref bp 1) 0.5) (< (aref bp 1) 6.2))
            (hit-atat j (aref *bdmg* i))
            (spawn-flash bp 0.18 0.5 1.0 0.7 0.4)
            (setq hit t)))))
    ;; Vader deflects blaster fire with his blade
    (when (and (not hit) *valive* *vactive*)
      (let ((diff (linalg:sub bp (linalg:from-list (list (aref *vpos* 0) 1.3 (aref *vpos* 2)) 'single-float))))
        (when (< (linalg:dot diff diff) 1.3)
          (spawn-flash bp 0.2 0.5 1.0 0.3 0.25)
          (setq hit t))))
    hit))

(defun player-chest ()
  (linalg:from-list (list (aref *ppos* 0) (+ (aref *ppos* 1) 1.0) (aref *ppos* 2)) 'single-float))

(defun bolt-hits-player (i)
  (let ((diff (linalg:sub (aref *bpos* i) (player-chest))))
    (< (linalg:dot diff diff) 0.4)))

(defun deflecting-p ()
  ;; the lightsaber swing sweeps a shield in front of you
  (and (= *weapon* 0) (> *swing-t* 0.0)))

(defun update-bolts (dt)
  (dotimes (i +nbolt+)
    (when (aref *balive* i)
      (setf (aref *bpos* i) (linalg:add (aref *bpos* i) (linalg:mul (aref *bvel* i) dt)))
      (setf (aref *bt* i) (- (aref *bt* i) dt))
      (cond
        ((<= (aref *bt* i) 0.0) (setf (aref *balive* i) nil))
        ((< (aref (aref *bpos* i) 1) 0.05) (setf (aref *balive* i) nil))
        ((= (aref *bown* i) 0)
         (when (bolt-hits-enemies i) (setf (aref *balive* i) nil)))
        (t                              ; enemy bolt
         (cond
           ((and (deflecting-p)
                 (let ((diff (linalg:sub (aref *bpos* i) (player-chest))))
                   (< (linalg:dot diff diff) 2.5)))
            ;; deflected: bat it away as a harmless spark
            (spawn-flash (aref *bpos* i) 0.18 0.5 0.6 0.85 1.0)
            (setf (aref *balive* i) nil))
           ((bolt-hits-player i)
            (hurt-player (aref *bdmg* i))
            (spawn-flash (aref *bpos* i) 0.14 0.4 1.0 0.5 0.3)
            (setf (aref *balive* i) nil))))))))

;; --- your attacks -----------------------------------------------------------

;; A generous frontal reach: hit anything within `reach` whose bearing is
;; within ~120 degrees of the aim (dot > -0.5*d), so a swing near an enemy
;; connects even when the aim is a little off. `epos` is the enemy's ground
;; position (a vector, y = 0).
(defun in-saber-arc-p (epos reach)
  (let* ((to (linalg:sub epos (player-ground)))
         (d (linalg:norm to)))
    (and (< d reach) (> (linalg:dot to *aimf*) (* d -0.5)))))

(defun saber-strike ()
  ;; one swing's worth of damage across the frontal arc
  (dotimes (j *ntrooper*)
    (when (and (aref *talive* j) (in-saber-arc-p (aref *tpos* j) 2.9))
      (hit-trooper j 40.0)))
  (dotimes (j *natat*)
    (when (and (aref *aalive* j) (in-saber-arc-p (aref *apos* j) 3.7))
      (hit-atat j 20.0)))
  (when (and *valive* *vactive* (in-saber-arc-p *vpos* 3.1))
    (hit-vader 20.0)))

(defun update-attack (dt)
  (when (> *swing-cd* 0.0) (setq *swing-cd* (- *swing-cd* dt)))
  (when (> *swing-t* 0.0) (setq *swing-t* (- *swing-t* dt)))
  (when (> *fire-cd* 0.0) (setq *fire-cd* (- *fire-cd* dt)))
  (let ((held (> *attack* 0.5)))
    (if (= *weapon* 0)
        ;; lightsaber: start a swing; deal its damage once at mid-arc
        (progn
          (when (and held (<= *swing-cd* 0.0))
            (setq *swing-t* 0.32)
            (setq *swing-cd* 0.40)
            (setq *swing-hit* nil))
          (when (and (> *swing-t* 0.0) (< *swing-t* 0.28) (not *swing-hit*))
            (setq *swing-hit* t)
            (saber-strike)))
        ;; blaster: automatic fire on a short cadence
        (when (and held (<= *fire-cd* 0.0))
          (setq *fire-cd* 0.16)
          (let ((muzzle (linalg:add (player-chest)
                                    (linalg:add (linalg:mul *aimf* 0.55)
                                                (linalg:mul *aimr* 0.16)))))
            (spawn-bolt muzzle *aimf* 46.0 0 12.0 1.0 0.35 1.0 0.45)
            (spawn-flash muzzle 0.06 0.3 0.5 1.0 0.5)))))
  (setq *attack-prev* (> *attack* 0.5)))

;; --- the playing-state step ---------------------------------------------------

(defun steer (dt)
  (let* ((fwd (- *in-f* *in-b*))
         (rgt (- *in-r* *in-l*))
         (n (if (or (= fwd 0.0) (= rgt 0.0)) 1.0 0.7071))
         (cy (cos *cam-yaw*))
         (sy (sin *cam-yaw*))
         (tx (* +run-speed+ n (- (* fwd cy) (* rgt sy))))
         (tz (* +run-speed+ n (+ (* fwd sy) (* rgt cy))))
         (acc (* dt 40.0)))
    ;; accelerate the horizontal velocity toward the steer target, capped by acc
    (setq *pvel*
          (linalg:from-list (list (+ (aref *pvel* 0) (max (- 0.0 acc) (min acc (- tx (aref *pvel* 0)))))
                (aref *pvel* 1)
                (+ (aref *pvel* 2) (max (- 0.0 acc) (min acc (- tz (aref *pvel* 2)))))) 'single-float))))

(defun jump-control (dt)
  ;; Space jumps off the ground; a real hop, gravity does the rest
  (let ((held (> *in-jump* 0.5)))
    (when (and held (not *jump-prev*) *grounded*)
      (setq *pvel* (linalg:from-list (list (aref *pvel* 0) +jump-v+ (aref *pvel* 2)) 'single-float))
      (setq *grounded* nil))
    (setq *jump-prev* held)))

(defun move-player (dt)
  ;; integrate gravity on the vertical component, then advance and clamp
  (let* ((vy (max -32.0 (- (aref *pvel* 1) (* +gravity+ dt))))
         (nx (max +field-min-x+ (min +field-max-x+ (+ (aref *ppos* 0) (* (aref *pvel* 0) dt)))))
         (nz (max +field-min-z+ (min +field-max-z+ (+ (aref *ppos* 2) (* (aref *pvel* 2) dt)))))
         (ny (+ (aref *ppos* 1) (* vy dt))))
    (when (<= ny 0.0)                    ; landed on the snow at y = 0
      (setq ny 0.0)
      (setq vy 0.0)
      (setq *grounded* t))
    (setq *pvel* (linalg:from-list (list (aref *pvel* 0) vy (aref *pvel* 2)) 'single-float))
    (setq *ppos* (linalg:from-list (list nx ny nz) 'single-float))))

(defun step-playing (dt)
  (when (> *inv-t* 0.0) (setq *inv-t* (- *inv-t* dt)))
  (steer dt)
  (jump-control dt)
  (move-player dt)
  (setq *pyaw* (- 0.0 *cam-yaw*))       ; face the aim
  (let ((sp (sqrt (+ (* (aref *pvel* 0) (aref *pvel* 0))
                     (* (aref *pvel* 2) (aref *pvel* 2))))))
    (if (and *grounded* (> sp 0.4))
        (setq *run-phase* (+ *run-phase* (* sp 1.9 dt)))
        (setq *run-phase* 0.0)))
  (update-attack dt)
  (update-troopers dt)
  (update-atats dt)
  (update-vader dt)
  (when (> *hurt-flash* 0.0)
    (setq *hurt-flash* (- *hurt-flash* dt))))

(defun step-victory (dt)
  ;; the celebration: keep launching fireworks over the arena, and let you
  ;; hold your lightsaber raised (a slow idle sway through the run oscillator)
  (setq *weapon* 0)
  (setq *pyaw* (- 0.0 *cam-yaw*))
  (setq *run-phase* (+ *run-phase* (* 1.4 dt)))
  (setq *fw-launch* (- *fw-launch* dt))
  (when (<= *fw-launch* 0.0)
    (setq *fw-launch* (rand-range 0.16 0.32))
    (launch-firework)                    ; two bursts at a time for a fuller sky
    (launch-firework))
  (update-fireworks dt))

;; --- drawing the cast ---------------------------------------------------------

(defun emit-shadow (x z r)
  ;; a flattened ellipsoid reads as a soft round blob shadow, not a square
  (col 0.55 0.60 0.68)
  (emit-ellipsoid x 0.02 z r 0.006 r 0.0 10 3))

;; scratch for the leg's ankle hand-off and for Vader's cape sampler
(defvar *cp-x* 0.0)
(defvar *cp-y* 0.0)
(defvar *cp-z* 0.0)

;; --- the humanoid leg ------------------------------------------------------
;;
;; Shared by all three figures: hip ball, tapered thigh, knee ball, tapered
;; shin, ankle, and a boot the caller draws. `sw` is the walk cycle's -1..1
;; swing for this leg, so the knee leads the ankle and the foot lifts as it
;; comes forward -- the thing a rigid pair of posts sliding fore and aft can
;; never do. Colours are whatever the caller latched, except the boot.
(defun humanoid-leg (lz sw hipy scale)
  (let* ((kx (* 0.11 sw scale))
         (ky (* hipy 0.53))
         (ax (* 0.24 sw scale))
         (ay (+ (* hipy 0.135) (max 0.0 (* 0.055 sw scale)))))
    ;; both segments are bracketed -- hip ball above, knee ball between, boot
    ;; below -- so their end discs are inside solid geometry. Dropping them
    ;; halves the leg's triangles for nothing you could ever see.
    (limb-caps nil)
    (part-joint 0.0 hipy lz (* 0.125 scale))
    (taper (* 0.098 scale))
    (part-limb 0.0 hipy lz kx ky lz (* 0.115 scale))
    (part-joint kx ky lz (* 0.098 scale))
    (taper (* 0.078 scale))
    (part-limb kx ky lz ax ay lz (* 0.096 scale))
    (limb-caps t)
    ;; no ankle ball either: the boot swallows it, and a sphere nobody can see
    ;; is the most expensive kind of detail there is.
    ;; the ankle position is left in these two globals for the boot
    (setq *cp-x* ax *cp-y* ay)))

;; You, in Hoth (Echo Base) gear: tan jacket over dark trousers and boots,
;; the field backpack, the knit cap with its snow goggles pushed up on it,
;; holding either the glowing blue lightsaber or the blaster. ~1.72 tall.
(defun emit-player (tm)
  (emit-shadow (aref *ppos* 0) (aref *ppos* 2) 0.36)
  ;; you are always the closest figure on screen, so never let the distance
  ;; heuristic coarsen you
  (setq *lod* 2)
  (set-origin (aref *ppos* 0) (aref *ppos* 1) (aref *ppos* 2) *pyaw*)
  (let* ((sw (sin *run-phase*))
         (arm (* -0.13 sw)))
    ;; legs: dark olive trousers over the articulated frame, then the boots
    (col 0.44 0.41 0.31)
    (humanoid-leg -0.105 sw 0.80 1.0)
    (let ((bx *cp-x*) (by *cp-y*))
      (col 0.15 0.12 0.10)
      (part-rbox (+ bx 0.025) (- by 0.045) -0.105 0.095 0.062 0.083 0.035))
    (col 0.44 0.41 0.31)
    (humanoid-leg 0.105 (- 0.0 sw) 0.80 1.0)
    (let ((bx *cp-x*) (by *cp-y*))
      (col 0.15 0.12 0.10)
      (part-rbox (+ bx 0.025) (- by 0.045) 0.105 0.095 0.062 0.083 0.035))
    ;; hips and the utility belt
    (col 0.42 0.38 0.29)
    (part-rbox 0.0 0.84 0.0 0.152 0.105 0.125 0.055)
    (col 0.26 0.22 0.17)
    (part-rbox 0.0 0.75 0.0 0.158 0.042 0.130 0.028)
    (metal 0.55 0.47 0.28)
    (part-rbox 0.15 0.75 0.0 0.022 0.032 0.040 0.012)   ; buckle
    (col 0.22 0.19 0.15)                                 ; holster
    (part-rbox 0.02 0.70 -0.145 0.045 0.075 0.035 0.022)
    ;; the tan quilted jacket: a chest and a slightly narrower waist, so the
    ;; torso has a taper instead of being one carton
    (col 0.80 0.70 0.52)
    (part-rbox 0.0 1.14 0.0 0.185 0.135 0.140 0.075)
    (part-rbox 0.0 0.97 0.0 0.163 0.115 0.125 0.065)
    (col 0.62 0.53 0.38)                                 ; the vest panel
    (part-rbox 0.075 1.08 0.0 0.115 0.155 0.118 0.055)
    (col 0.86 0.78 0.62)                                 ; the fur collar
    (part-cyl 0.0 1.29 0.0 0.112 0.048 10)
    ;; the Hoth field pack, its straps and a canteen
    (col 0.33 0.31 0.27)
    (part-rbox -0.185 1.06 0.0 0.075 0.185 0.145 0.055)
    (col 0.24 0.22 0.19)
    (part-rbox -0.06 1.14 -0.105 0.115 0.075 0.026 0.013)
    (part-rbox -0.06 1.14 0.105 0.115 0.075 0.026 0.013)
    (metal 0.42 0.44 0.46)
    (part-limb -0.27 1.02 0.085 -0.27 1.16 0.085 0.048)
    ;; shoulders and the off arm (the weapon arm is drawn by emit-arm-to)
    (col 0.78 0.68 0.50)
    (part-joint 0.0 1.215 -0.178 0.076)
    (part-joint 0.0 1.215 0.178 0.076)
    (col 0.74 0.64 0.47)
    (taper 0.047)
    (part-limb 0.0 1.215 0.192 (* 0.6 arm) 1.02 0.212 0.056)
    (part-joint (* 0.6 arm) 1.02 0.212 0.048)
    (taper 0.041)
    (part-limb (* 0.6 arm) 1.02 0.212 arm 0.855 0.218 0.047)
    (col 0.18 0.15 0.12)
    (part-ellipsoid arm 0.833 0.218 0.048 0.052 0.048 7 4)
    ;; neck and head
    (col 0.78 0.60 0.48)
    (part-cyl 0.0 1.335 0.0 0.05 0.05)
    (col 0.86 0.70 0.58)
    (part-ellipsoid 0.012 1.455 0.0 0.092 0.103 0.092 10 6)
    (part-ellipsoid 0.088 1.445 0.0 0.026 0.022 0.024 6 4)  ; nose
    (col 0.34 0.27 0.21)
    (part-rbox 0.078 1.492 0.0 0.022 0.016 0.070 0.008)     ; brow
    ;; the knit cap, its band, and the snow goggles pushed up onto it
    (col 0.70 0.62 0.46)
    (part-ellipsoid 0.0 1.545 0.0 0.108 0.075 0.108 10 5)
    (col 0.50 0.43 0.33)
    (part-cyl 0.0 1.528 0.0 0.112 0.020 10)
    (metal 0.20 0.20 0.22)
    (part-limb 0.055 1.560 -0.100 0.055 1.560 0.100 0.030)
    (col 0.30 0.42 0.48)
    (part-ellipsoid 0.082 1.560 -0.048 0.020 0.026 0.030 6 4)
    (part-ellipsoid 0.082 1.560 0.048 0.020 0.026 0.030 6 4))
  (if (= *weapon* 0)
      (emit-player-saber tm)
      (emit-player-blaster)))

;; The weapon arm, reaching from the weapon-side shoulder to the (animated)
;; weapon hand. It is a real two-segment arm: the elbow sits off the straight
;; shoulder-to-hand line, dropped and pushed outboard, so a raised weapon bends
;; the arm the way an arm bends instead of running one rigid pole from the
;; shoulder to the fist. Everything here is WORLD space -- the hand is already
;; solved in the aim frame by the caller, not in the figure's local frame.
(defun emit-arm-to (hx hy hz r g b)
  (let* ((arrx (- 0.0 (sin *cam-yaw*)))       ; aim-right
         (arrz (cos *cam-yaw*))
         (sx (+ (aref *ppos* 0) (* arrx -0.18)))   ; weapon-side shoulder
         (sz (+ (aref *ppos* 2) (* arrz -0.18)))
         (sy (+ (aref *ppos* 1) 1.16))
         ;; the elbow: midway, sagging, and bowed out along the aim-right axis
         (ex (+ (* 0.5 (+ sx hx)) (* arrx -0.07)))
         (ez (+ (* 0.5 (+ sz hz)) (* arrz -0.07)))
         (ey (- (* 0.5 (+ sy hy)) 0.085)))
    (col r g b)
    (limb-caps nil)                     ; shoulder ball, elbow ball, then a fist
    (emit-ellipsoid sx sy sz 0.070 0.070 0.070 0.0 (segs 6) (segs 3))
    (taper 0.042)
    (emit-limb sx sy sz ex ey ez 0.052)
    (emit-ellipsoid ex ey ez 0.044 0.044 0.044 0.0 (segs 6) (segs 3))
    (taper 0.039)
    (emit-limb ex ey ez hx hy hz 0.045)
    (limb-caps t)))

;; blade-glow inflation: pad the thin cross-axes more than the long axis
(defun glowh (h) (if (< h 0.2) (+ h 0.045) (+ h 0.03)))
(defun glowh2 (h) (if (< h 0.2) (+ h 0.09) (+ h 0.055)))

(defun emit-player-blaster ()
  ;; a compact grey blaster pistol, held out along the aim: a stepped barrel
  ;; with a muzzle collar, a receiver block, a sight rib and a raked grip
  (let* ((theta *cam-yaw*)
         (fwx (cos theta)) (fwz (sin theta))
         (arx (- 0.0 (sin theta))) (arz (cos theta))
         (hx (+ (aref *ppos* 0) (* fwx 0.40) (* arx -0.15)))
         (hz (+ (aref *ppos* 2) (* fwz 0.40) (* arz -0.15)))
         (hy (+ (aref *ppos* 1) 0.98)))
    (emit-arm-to hx hy hz 0.74 0.64 0.47)
    (col 0.15 0.12 0.10)
    (emit-ellipsoid hx hy hz 0.052 0.055 0.052 0.0 (segs 8) (segs 5))  ; fist
    ;; the pistol's blocks are centimetres across -- plain soft-edged boxes,
    ;; never rounded ones; only the round parts of it are round
    (soften 0.5)
    (emit-box (+ hx (* fwx 0.03)) (+ hy 0.015) (+ hz (* fwz 0.03))
              0.075 0.045 0.032 *pyaw*)
    (metal 0.20 0.21 0.24)                                ; barrel
    (taper 0.026)
    (emit-limb (+ hx (* fwx 0.07)) (+ hy 0.022) (+ hz (* fwz 0.07))
               (+ hx (* fwx 0.25)) (+ hy 0.022) (+ hz (* fwz 0.25)) 0.030)
    (metal 0.34 0.35 0.38)                                ; muzzle collar
    (emit-limb (+ hx (* fwx 0.23)) (+ hy 0.022) (+ hz (* fwz 0.23))
               (+ hx (* fwx 0.26)) (+ hy 0.022) (+ hz (* fwz 0.26)) 0.037)
    (col 0.11 0.11 0.13)                                  ; sight rib
    (soften 0.5)
    (emit-box (+ hx (* fwx 0.10)) (+ hy 0.062) (+ hz (* fwz 0.10))
              0.055 0.012 0.010 *pyaw*)
    (col 0.13 0.13 0.15)                                  ; grip
    (soften 0.5)
    (emit-box (- hx (* fwx 0.015)) (- hy 0.075) (- hz (* fwz 0.015))
              0.030 0.062 0.028 *pyaw*)))

;; The lightsaber is stored as a generic oriented box (center + half-extents +
;; yaw) so both the opaque core pass and the additive bloom pass can redraw it,
;; whether it is held vertical (idle) or swept horizontal (mid-slash).
(defvar *sab-cx* 0.0)
(defvar *sab-cy* 0.0)
(defvar *sab-cz* 0.0)
(defvar *sab-hx* 0.03)
(defvar *sab-hy* 0.6)
(defvar *sab-hz* 0.03)
(defvar *sab-yaw* 0.0)
(defvar *sab-vis* nil)

(defun emit-hilt (x y z)
  (col 0.16 0.13 0.11)                          ; fist
  (emit-ellipsoid x y z 0.05 0.05 0.05 0.0 8 5)
  (metal 0.76 0.77 0.81)                        ; metal hilt -- a round rod
  (emit-cylinder x (+ y 0.05) z 0.028 0.055 0.0 8))

(defun emit-player-blade ()
  ;; the stored blade: a blue core with a white-hot inner line -- a true
  ;; cylinder (hx and hz always match), not a slab
  (glow-col 0.55 0.78 1.0)
  (emit-cylinder *sab-cx* *sab-cy* *sab-cz* *sab-hx* *sab-hy* *sab-yaw* 8)
  (glow-col 0.92 0.97 1.0)
  (emit-cylinder *sab-cx* *sab-cy* *sab-cz*
                 (max 0.012 (- *sab-hx* 0.016)) (max 0.012 (- *sab-hy* 0.016))
                 *sab-yaw* 8))

(defun emit-player-saber (tm)
  (let* ((theta *cam-yaw*)
         (fwx (cos theta)) (fwz (sin theta))
         (arx (- 0.0 (sin theta))) (arz (cos theta)))
    (if (> *swing-t* 0.0)
        ;; a diagonal downward slash: the blade stays upright while the hand
        ;; arcs from upper-right to lower-left across the front, reaching out at
        ;; mid-swing -- the arm tracks it, so the whole strike reads cleanly
        (let* ((p (- 1.0 (/ *swing-t* 0.32)))
               (side (- 0.44 (* 0.88 p)))                ; right -> left
               (reachf (+ 0.34 (* 0.24 (sin (* p +pi+)))))
               (hh (+ (aref *ppos* 1) (- 1.16 (* 0.40 p))))  ; high -> low
               (hx (+ (aref *ppos* 0) (* fwx reachf) (* arx side)))
               (hz (+ (aref *ppos* 2) (* fwz reachf) (* arz side)))
               (half 0.6))
          (emit-arm-to hx hh hz 0.74 0.64 0.47)
          (emit-hilt hx hh hz)
          (setq *sab-cx* hx *sab-cy* (+ hh 0.12 half) *sab-cz* hz
                *sab-hx* 0.032 *sab-hy* half *sab-hz* 0.032
                *sab-yaw* 0.0 *sab-vis* t)
          (emit-player-blade))
        ;; idle: held upright in the weapon hand
        (let* ((hx (+ (aref *ppos* 0) (* fwx 0.40) (* arx -0.18)))
               (hz (+ (aref *ppos* 2) (* fwz 0.40) (* arz -0.18)))
               (hh (+ (aref *ppos* 1) 0.95))
               (half 0.62))
          (emit-arm-to hx (+ hh 0.08) hz 0.74 0.64 0.47)
          (emit-hilt hx hh hz)
          (setq *sab-cx* hx *sab-cy* (+ hh 0.14 half) *sab-cz* hz
                *sab-hx* 0.032 *sab-hy* half *sab-hz* 0.032
                *sab-yaw* 0.0 *sab-vis* t)
          (emit-player-blade)))))

(defun emit-player-saber-glow ()
  (when *sab-vis*
    (glow-col 0.28 0.5 1.0)
    (emit-cylinder *sab-cx* *sab-cy* *sab-cz*
                   (glowh *sab-hx*) (glowh *sab-hy*) *sab-yaw* 8)
    (glow-col 0.12 0.28 0.85)
    (emit-cylinder *sab-cx* *sab-cy* *sab-cz*
                   (glowh2 *sab-hx*) (glowh2 *sab-hy*) *sab-yaw* 8)))

;; A stormtrooper: white armour over a black bodysuit. The armour is a SET OF
;; SEPARATE ROUNDED PLATES riding on the black limbs underneath -- chest, abdo,
;; shoulder bells, biceps, forearms, thigh and shin guards, boots -- because
;; that gap between plate and suit is the whole look; a single white cylinder
;; per limb reads as a robot. The helmet is built from the features people
;; actually recognise it by: the dome, the brow band, the two eye lenses, the
;; frown grille and the tube stripes on the cheeks. ~1.68 tall.
(defun emit-trooper (i tm)
  (setq *hit-tint* (min 1.0 (* (aref *thit* i) 6.0)))   ; red when just struck
  (let ((p (aref *tpos* i)))
    (set-lod (aref p 0) (aref p 2) 1.68)
    (if (aref *talive* i)
        (let* ((sw (sin (aref *tstep* i)))
               (arm (* -0.11 sw)))
          (set-origin (aref p 0) 0.0 (aref p 2) (aref *tyaw* i))
          (emit-shadow (aref p 0) (aref p 2) 0.34)
          ;; the black bodysuit legs, then the white plates over them
          (col 0.11 0.11 0.13)
          (humanoid-leg -0.10 sw 0.78 0.95)
          (let ((ax *cp-x*) (ay *cp-y*))
            (col 0.93 0.94 0.97)
            (part-rbox (* 0.06 sw) 0.58 -0.10 0.088 0.13 0.088 0.045)   ; thigh
            (part-rbox (* 0.18 sw) 0.28 -0.10 0.082 0.13 0.082 0.040)   ; shin
            (part-rbox (+ ax 0.03) (- ay 0.035) -0.10 0.098 0.062 0.088 0.032))
          (col 0.11 0.11 0.13)
          (humanoid-leg 0.10 (- 0.0 sw) 0.78 0.95)
          (let ((ax *cp-x*) (ay *cp-y*))
            (col 0.93 0.94 0.97)
            (part-rbox (* -0.06 sw) 0.58 0.10 0.088 0.13 0.088 0.045)
            (part-rbox (* -0.18 sw) 0.28 0.10 0.082 0.13 0.082 0.040)
            (part-rbox (+ ax 0.03) (- ay 0.035) 0.10 0.098 0.062 0.088 0.032))
          ;; hip block and the black belt with its side pouches
          (col 0.90 0.91 0.95)
          (part-rbox 0.0 0.82 0.0 0.142 0.085 0.112 0.05)
          (col 0.09 0.09 0.11)
          (part-rbox 0.0 0.735 0.0 0.148 0.038 0.118 0.022)
          (part-rbox 0.02 0.735 -0.115 0.045 0.050 0.030 0.018)
          (part-rbox 0.02 0.735 0.115 0.045 0.050 0.030 0.018)
          ;; the chest and abdominal plates, and the back plate behind them
          (col 0.94 0.95 0.98)
          (part-rbox 0.005 1.08 0.0 0.158 0.115 0.115 0.06)
          (col 0.88 0.89 0.93)
          (part-rbox 0.02 0.935 0.0 0.140 0.055 0.108 0.035)
          (col 0.90 0.91 0.95)
          (part-rbox -0.06 1.05 0.0 0.095 0.145 0.108 0.05)
          (col 0.11 0.11 0.13)                       ; the chest control panel
          (part-rbox 0.15 1.115 -0.045 0.020 0.030 0.038 0.008)
          (col 0.70 0.20 0.18)
          (part-rbox 0.15 1.115 0.030 0.020 0.014 0.020 0.006)
          ;; shoulder bells, black under-arms and the white arm plates
          (col 0.94 0.95 0.98)
          (part-rbox 0.0 1.205 -0.185 0.078 0.062 0.085 0.045)
          (part-rbox 0.0 1.205 0.185 0.078 0.062 0.085 0.045)
          (col 0.11 0.11 0.13)
          (taper 0.048)
          (part-limb 0.0 1.19 -0.20 (* 0.5 arm) 1.00 -0.215 0.058)
          (part-joint (* 0.5 arm) 1.00 -0.215 0.050)
          (taper 0.042)
          (part-limb (* 0.5 arm) 1.00 -0.215 arm 0.845 -0.222 0.050)
          (taper 0.048)
          (part-limb 0.0 1.19 0.20 (* -0.5 arm) 1.00 0.215 0.058)
          (part-joint (* -0.5 arm) 1.00 0.215 0.050)
          (taper 0.042)
          (part-limb (* -0.5 arm) 1.00 0.215 (- 0.0 arm) 0.845 0.222 0.050)
          (col 0.93 0.94 0.97)
          (part-rbox (* 0.25 arm) 1.115 -0.208 0.062 0.060 0.062 0.030)
          (part-rbox (* -0.25 arm) 1.115 0.208 0.062 0.060 0.062 0.030)
          (part-rbox (* 0.8 arm) 0.925 -0.218 0.058 0.058 0.058 0.028)
          (part-rbox (* -0.8 arm) 0.925 0.218 0.058 0.058 0.058 0.028)
          (col 0.10 0.10 0.12)                       ; gloves
          (part-ellipsoid arm 0.815 -0.222 0.048 0.052 0.048 7 4)
          (part-ellipsoid (- 0.0 arm) 0.815 0.222 0.048 0.052 0.048 7 4)
          ;; the black neck seal
          (col 0.10 0.10 0.12)
          (part-cyl 0.0 1.245 0.0 0.062 0.048)
          ;; the helmet
          (metal 0.95 0.96 0.99)
          (part-ellipsoid -0.005 1.375 0.0 0.098 0.105 0.100 10 6)
          (part-rbox 0.062 1.360 0.0 0.048 0.082 0.086 0.030)   ; faceplate
          (col 0.09 0.09 0.11)                                  ; brow band
          (part-rbox 0.090 1.408 0.0 0.032 0.020 0.080 0.008)
          (part-rbox 0.098 1.372 -0.042 0.026 0.026 0.028 0.008) ; eye lenses
          (part-rbox 0.098 1.372 0.042 0.026 0.026 0.028 0.008)
          (part-rbox 0.096 1.312 0.0 0.028 0.024 0.040 0.010)    ; frown grille
          (metal 0.20 0.20 0.22)                                 ; cheek tubes
          (taper 0.008)
          (part-limb 0.086 1.330 -0.062 0.062 1.296 -0.075 0.011)
          (taper 0.008)
          (part-limb 0.086 1.330 0.062 0.062 1.296 0.075 0.011)
          (col 0.88 0.89 0.93)                                   ; ear vents
          (part-rbox 0.010 1.345 -0.095 0.038 0.040 0.014 0.010)
          (part-rbox 0.010 1.345 0.095 0.038 0.040 0.014 0.010)
          ;; the E-11 held across the body: barrel, receiver, folding stock,
          ;; magazine and the scope rail
          (metal 0.13 0.13 0.15)
          (part-rbox 0.20 0.905 -0.20 0.075 0.038 0.030 0.014)
          (metal 0.18 0.18 0.21)
          (taper 0.020)
          (part-limb 0.27 0.912 -0.20 0.46 0.912 -0.20 0.024)
          (col 0.10 0.10 0.12)
          (part-rbox 0.19 0.845 -0.20 0.026 0.045 0.024 0.010)   ; magazine
          (part-rbox 0.08 0.905 -0.20 0.060 0.024 0.022 0.010)   ; stock
          (metal 0.22 0.22 0.25)
          (part-rbox 0.24 0.955 -0.20 0.048 0.014 0.014 0.006))  ; scope rail
        ;; a fallen trooper: face down in the snow, one arm flung out and the
        ;; helmet rolled clear of the body
        (progn
          (set-origin (aref p 0) 0.0 (aref p 2) (aref *tyaw* i))
          (emit-shadow (aref p 0) (aref p 2) 0.36)
          (col 0.11 0.11 0.13)
          (taper 0.075)
          (part-limb -0.10 0.10 -0.06 -0.44 0.07 -0.11 0.088)
          (taper 0.075)
          (part-limb -0.10 0.10 0.06 -0.42 0.07 0.14 0.088)
          (col 0.88 0.90 0.94)
          (part-rbox -0.02 0.10 0.0 0.20 0.095 0.135 0.06)
          (col 0.11 0.11 0.13)
          (taper 0.048)
          (part-limb 0.10 0.11 -0.14 0.34 0.06 -0.30 0.055)
          (metal 0.92 0.93 0.97)
          (part-ellipsoid 0.34 0.095 0.10 0.095 0.095 0.098 8 5)
          (col 0.09 0.09 0.11)
          (part-rbox 0.40 0.11 0.14 0.030 0.026 0.036 0.008))))
  (setq *hit-tint* 0.0))

;; --- the AT-AT ------------------------------------------------------------
;;
;; The walker used to be four vertical posts under a slab, which reads as a
;; table on legs from any angle. A real AT-AT's whole silhouette is its GAIT:
;; each leg is two long segments meeting at a knee that stands well forward of
;; the line from hip to foot, and the foot is a broad pad that stays flat on the
;; snow. So the leg is solved rather than drawn -- the foot is animated
;; (swinging fore and aft, lifting only while it travels forward), and the knee
;; is placed by the two-link inverse kinematics that the fixed thigh and shin
;; lengths force. That is what makes it stride instead of slide.

;; The two segments are only a little longer than the hip-to-foot distance, so
;; the knee stands proud of the leg line without the deep insect crouch a bigger
;; excess would give.
(defconstant +atat-thigh+ 1.56)
(defconstant +atat-shin+ 1.40)
(defconstant +atat-hip-y+ 3.15)

(defun atat-leg (fx fz th walking)
  (let* ((sw (if walking (* 0.44 (sin th)) 0.0))
         ;; lift only on the forward half of the stroke, so the planted foot
         ;; never skates
         (lift (if walking (max 0.0 (* 0.26 (cos th))) 0.0))
         (ankx (+ fx 0.10 sw))
         (anky (+ 0.44 lift))
         (dx (- ankx fx))
         (dy (- anky +atat-hip-y+))
         (d (min (* 0.995 (+ +atat-thigh+ +atat-shin+))
                 (max 0.3 (sqrt (+ (* dx dx) (* dy dy))))))
         (ux (/ dx d)) (uy (/ dy d))
         ;; cosine rule: how far along hip->ankle the knee's foot-point lies,
         ;; and how far it stands off that line
         (a (/ (+ (* d d) (- (* +atat-thigh+ +atat-thigh+)
                             (* +atat-shin+ +atat-shin+)))
               (* 2.0 d)))
         (hh (sqrt (max 0.0 (- (* +atat-thigh+ +atat-thigh+) (* a a)))))
         ;; the offset direction, chosen to put the knee FORWARD (+x local)
         (kx (+ fx (* a ux) (* hh (- 0.0 uy))))
         (ky (+ +atat-hip-y+ (* a uy) (* hh ux))))
    ;; hip housing and ball
    (metal 0.56 0.58 0.61)
    (part-rbox fx +atat-hip-y+ fz 0.34 0.30 0.34 0.14)
    (metal 0.40 0.42 0.45)
    (part-joint fx +atat-hip-y+ fz 0.28)
    ;; thigh, knee, shin, ankle -- each segment runs ball to ball, so no caps
    (metal 0.58 0.60 0.63)
    (limb-caps nil)
    (taper 0.23)
    (part-limb fx +atat-hip-y+ fz kx ky fz 0.31)
    (metal 0.40 0.42 0.45)
    (part-joint kx ky fz 0.26)
    (metal 0.58 0.60 0.63)
    (taper 0.19)
    (part-limb kx ky fz ankx anky fz 0.24)
    (limb-caps t)
    (metal 0.40 0.42 0.45)
    (part-joint ankx anky fz 0.20)
    ;; the broad foot pad
    (metal 0.50 0.52 0.55)
    (part-rbox (+ ankx 0.05) (+ lift 0.16) fz 0.40 0.15 0.33 0.11)))

(defun emit-atat (i tm)
  (setq *hit-tint* (min 1.0 (* (aref *ahit* i) 6.0)))   ; red when just struck
  (let* ((p (aref *apos* i))
         (dx (- (aref *ppos* 0) (aref p 0)))
         (dz (- (aref *ppos* 2) (aref p 2)))
         (far2 (+ (* dx dx) (* dz dz))))    ; squared horizontal distance to you
    (set-lod (aref p 0) (aref p 2) 5.5)
    (if (aref *aalive* i)
        (let* ((yaw (aref *ayaw* i))
               (walking (> far2 170.0))
               (th (* tm 2.1)))
          (set-origin (aref p 0) 0.0 (aref p 2) yaw)
          ;; the four legs, on the diagonal gait a quadruped actually uses:
          ;; front-left with rear-right, front-right with rear-left
          (atat-leg 1.05 -0.92 th walking)
          (atat-leg -1.05 0.92 th walking)
          (atat-leg 1.05 0.92 (+ th +pi+) walking)
          (atat-leg -1.05 -0.92 (+ th +pi+) walking)
          ;; the hull: a belly the legs hang from, the main armoured box, a
          ;; dorsal ridge and the rear engine block with its two thrusters
          (metal 0.54 0.56 0.59)
          (part-rbox 0.0 3.28 0.0 1.30 0.24 0.82 0.16)
          (metal 0.64 0.66 0.69)
          (part-rbox 0.0 3.88 0.0 1.55 0.55 0.98 0.26)
          (metal 0.58 0.60 0.63)
          (part-rbox -0.15 4.48 0.0 1.12 0.13 0.60 0.09)
          (metal 0.46 0.48 0.51)
          (part-rbox 0.10 3.80 -1.00 1.05 0.30 0.07 0.05)
          (part-rbox 0.10 3.80 1.00 1.05 0.30 0.07 0.05)
          (metal 0.44 0.46 0.49)
          (part-rbox -1.64 3.82 0.0 0.22 0.42 0.72 0.13)
          (metal 0.26 0.27 0.29)
          (part-limb -1.80 3.82 -0.36 -2.00 3.82 -0.36 0.17)
          (part-limb -1.80 3.82 0.36 -2.00 3.82 0.36 0.17)
          ;; the neck: a narrow trunk that slopes DOWN and forward out of the
          ;; hull's chest, with two armour rings, so the head hangs clear
          ;; below the hull line the way the real machine's does -- run it
          ;; level with the hull instead and the whole thing reads as a dog
          (metal 0.50 0.52 0.55)
          (taper 0.28)
          (part-limb 1.42 3.86 0.0 2.14 3.52 0.0 0.42)
          (metal 0.60 0.62 0.65)
          (part-limb 1.60 3.78 0.0 1.70 3.73 0.0 0.40)
          (part-limb 1.92 3.63 0.0 2.02 3.58 0.0 0.34)
          ;; the head: a blunt armoured box with a dark visor band, a jaw, two
          ;; temple cannons and the heavy chin blasters
          (metal 0.66 0.68 0.71)
          (part-rbox 2.54 3.40 0.0 0.42 0.40 0.50 0.13)
          (col 0.13 0.14 0.16)
          (part-rbox 2.94 3.52 0.0 0.07 0.12 0.40 0.04)
          (metal 0.58 0.60 0.63)
          (part-rbox 2.60 3.02 0.0 0.34 0.13 0.38 0.07)
          (metal 0.44 0.46 0.49)
          (part-rbox 2.30 3.60 -0.56 0.16 0.14 0.12 0.05)
          (part-rbox 2.30 3.60 0.56 0.16 0.14 0.12 0.05)
          (metal 0.22 0.22 0.24)
          (part-limb 2.42 3.60 -0.56 2.90 3.60 -0.56 0.055)
          (part-limb 2.42 3.60 0.56 2.90 3.60 0.56 0.055)
          (metal 0.20 0.20 0.22)
          (taper 0.058)
          (part-limb 2.70 3.08 -0.20 3.34 3.04 -0.20 0.085)
          (taper 0.058)
          (part-limb 2.70 3.08 0.20 3.34 3.04 0.20 0.085))
        ;; a smoking wreck: the hull slumped into the snow with its neck bent
        ;; under it, and a column of soot still lifting off the engine block
        (let* ((k (min 1.0 (/ (aref *awreck* i) 1.2)))
               (drop (* 0.9 k)))
          (set-origin (aref p 0) 0.0 (aref p 2) (aref *ayaw* i))
          (metal 0.34 0.34 0.35)
          (part-rbox 0.0 (- 1.5 drop) 0.0 1.5 0.55 0.95 0.24)
          (metal 0.28 0.28 0.30)
          (part-rbox -1.5 (- 1.4 drop) 0.0 0.24 0.40 0.70 0.13)
          (taper 0.30)
          (part-limb 1.3 (- 1.4 drop) 0.0 2.0 (- 0.6 (* 0.3 k)) 0.0 0.44)
          (col 0.22 0.22 0.24)
          (part-rbox 2.35 (- 0.62 (* 0.3 k)) 0.0 0.40 0.36 0.46 0.12)
          ;; the collapsed legs, splayed where they folded
          (metal 0.38 0.40 0.43)
          (taper 0.16)
          (part-limb 1.0 (- 1.2 drop) -0.9 1.9 0.22 -1.5 0.24)
          (taper 0.16)
          (part-limb -1.0 (- 1.2 drop) 0.9 -1.9 0.22 1.5 0.24)
          (taper 0.16)
          (part-limb 1.0 (- 1.2 drop) 0.9 1.7 0.22 1.6 0.24)
          (taper 0.16)
          (part-limb -1.0 (- 1.2 drop) -0.9 -1.8 0.22 -1.4 0.24)
          ;; soot, thinning as it climbs
          (col 0.30 0.30 0.32)
          (part-ellipsoid -1.4 (+ 2.1 (* 0.8 k)) 0.0 0.55 0.40 0.50 7 4)
          (col 0.42 0.42 0.44)
          (part-ellipsoid -1.1 (+ 3.0 (* 1.4 k)) 0.2 0.42 0.34 0.40 6 3))))
  (setq *hit-tint* 0.0))

;; Vader's blade, stored as a generic oriented box like yours.
(defvar *vsab-cx* 0.0)
(defvar *vsab-cy* 0.0)
(defvar *vsab-cz* 0.0)
(defvar *vsab-hx* 0.03)
(defvar *vsab-hy* 0.66)
(defvar *vsab-hz* 0.03)
(defvar *vsab-yaw* 0.0)
(defvar *vsab-vis* nil)

;; --- the cape --------------------------------------------------------------
;;
;; A cape is the one part of the boss that a box can never stand in for: its
;; whole character is that it is a SURFACE -- it wraps the shoulders, widens
;; towards the hem, and hangs in folds. So it is sampled from a parametric
;; patch, u running from the collar (0) to the hem (1) and v across the back
;; (-1 to 1). The half-angle it subtends and its distance from the body axis
;; both grow with u, which is the flare; a sine in v adds the standing folds,
;; and a slow drift in the phase makes them breathe as he walks.
;;
;; Normals come from finite differences of the same function, so the folds
;; catch the light instead of being painted-on stripes. The patch is emitted in
;; the figure's local frame like everything else.
(defconstant +cape-u+ 7)                ; bands from collar to hem
(defconstant +cape-v+ 9)                ; panels across the back

(defun cape-set (u v ph)
  (let* ((spread (+ 0.80 (* 0.45 u)))          ; half-angle around the body
         (ang (* v spread))
         (fold (* 0.05 u (sin (+ (* 4.0 v) ph))))
         (rad (+ 0.21 (* 0.26 u u) fold))
         ;; the top edge follows the shoulder line down towards the front
         ;; instead of running level, or the collar reads as a shelf
         (yy (- (- 1.58 (* 0.13 v v)) (* 1.40 u))))
    (setq *cp-x* (- 0.0 (* rad (cos ang)))
          *cp-y* yy
          *cp-z* (* rad (sin ang)))))

(defun cape-vertex (u v ph)
  (cape-set u v ph)
  (let ((px *cp-x*) (py *cp-y*) (pz *cp-z*))
    (cape-set (min 1.0 (+ u 0.03)) v ph)
    (let ((ax (- *cp-x* px)) (ay (- *cp-y* py)) (az (- *cp-z* pz)))
      (cape-set u (min 1.0 (+ v 0.03)) ph)
      (let* ((bx (- *cp-x* px)) (by (- *cp-y* py)) (bz (- *cp-z* pz))
             (nx (- (* ay bz) (* az by)))
             (ny (- (* az bx) (* ax bz)))
             (nz (- (* ax by) (* ay bx)))
             (nl (max 0.000001 (sqrt (+ (* nx nx) (* ny ny) (* nz nz)))))
             ;; face the normal AWAY from the body axis -- the cross product's
             ;; sign flips as v crosses the back's centre line
             (sgn (if (< (+ (* nx px) (* nz pz)) 0.0) (- 0.0 1.0) 1.0))
             (mx (* sgn (/ nx nl))) (my (* sgn (/ ny nl))) (mz (* sgn (/ nz nl))))
        (emit-vertex (lwx px pz) (lwy py) (lwz px pz)
                     (+ (* *oc* mx) (* *os* mz)) my (- (* *oc* mz) (* *os* mx)))))))

(defun emit-cape (ph)
  (dotimes (j +cape-u+)
    (let ((u0 (/ (float j) (float +cape-u+)))
          (u1 (/ (float (+ j 1)) (float +cape-u+))))
      (dotimes (i +cape-v+)
        (let ((v0 (- (* 2.0 (/ (float i) (float +cape-v+))) 1.0))
              (v1 (- (* 2.0 (/ (float (+ i 1)) (float +cape-v+))) 1.0)))
          (cape-vertex u0 v0 ph)
          (cape-vertex u1 v0 ph)
          (cape-vertex u1 v1 ph)
          (cape-vertex u0 v0 ph)
          (cape-vertex u1 v1 ph)
          (cape-vertex u0 v1 ph))))))

;; Vader: all black armour under a heavy cape, the domed helmet with its
;; flared mask, the chest control box, a red lightsaber. Tall (~1.9) and broad.
;; Only shown once he engages -- dormant until the walkers fall.
(defun emit-vader (tm)
  (setq *vsab-vis* nil)
  (setq *hit-tint* (min 1.0 (* *vhitf* 6.0)))          ; red when just struck
  (when (and *valive* *vactive*)
    (let ((vdx (aref *vpos* 0)) (vdz (aref *vpos* 2)))
      (set-lod vdx vdz 1.95)
      (set-origin vdx 0.0 vdz *vyaw*)
      (emit-shadow vdx vdz 0.46)
      ;; legs and boots, under the cape
      (col 0.07 0.07 0.08)
      (humanoid-leg -0.135 0.0 0.90 1.10)
      (col 0.05 0.05 0.06)
      (part-rbox 0.045 0.075 -0.135 0.115 0.075 0.105 0.045)
      (col 0.07 0.07 0.08)
      (humanoid-leg 0.135 0.0 0.90 1.10)
      (col 0.05 0.05 0.06)
      (part-rbox 0.045 0.075 0.135 0.115 0.075 0.105 0.045)
      ;; hips, the wide belt and its side boxes
      (col 0.08 0.08 0.09)
      (part-rbox 0.0 0.96 0.0 0.185 0.10 0.145 0.07)
      (col 0.11 0.11 0.12)
      (part-rbox 0.0 0.88 0.0 0.195 0.048 0.152 0.026)
      (metal 0.34 0.29 0.16)
      (part-rbox 0.14 0.88 -0.095 0.042 0.048 0.042 0.016)
      (part-rbox 0.14 0.88 0.095 0.042 0.048 0.042 0.016)
      (part-rbox 0.16 0.88 0.0 0.030 0.036 0.038 0.014)
      ;; torso: a broad armoured chest over a narrower midriff
      (col 0.08 0.08 0.09)
      (part-rbox 0.0 1.36 0.0 0.195 0.185 0.155 0.085)
      (part-rbox 0.0 1.12 0.0 0.165 0.115 0.135 0.065)
      ;; the chest control box and its indicator lights
      (metal 0.14 0.14 0.16)
      (part-rbox 0.16 1.32 0.0 0.055 0.115 0.105 0.024)
      (glow-col 1.0 0.2 0.18)
      (part-ellipsoid 0.215 1.375 -0.045 0.018 0.018 0.018 6 3)
      (part-ellipsoid 0.215 1.300 -0.045 0.016 0.016 0.016 6 3)
      (glow-col 0.2 0.85 0.3)
      (part-ellipsoid 0.215 1.338 0.040 0.015 0.015 0.015 6 3)
      (metal 0.18 0.18 0.20)                   ; the shoulder-strap clasps
      (part-limb -0.02 1.55 -0.085 0.13 1.20 -0.075 0.022)
      (part-limb -0.02 1.55 0.085 0.13 1.20 0.075 0.022)
      ;; shoulder mantles, arms and gloved fists
      (metal 0.10 0.10 0.12)
      (part-rbox 0.0 1.545 -0.215 0.105 0.055 0.115 0.05)
      (part-rbox 0.0 1.545 0.215 0.105 0.055 0.115 0.05)
      (col 0.07 0.07 0.08)
      (taper 0.062)
      (part-limb 0.0 1.50 -0.235 0.05 1.24 -0.255 0.078)
      (part-joint 0.05 1.24 -0.255 0.064)
      (taper 0.055)
      (part-limb 0.05 1.24 -0.255 0.02 1.03 -0.262 0.064)
      (col 0.03 0.03 0.04)
      (part-ellipsoid 0.02 1.005 -0.262 0.058 0.062 0.058 7 4)
      ;; neck, and the standing collar behind it -- the cape hangs off this,
      ;; and its two raked panels are as much of the silhouette as the helmet
      (col 0.06 0.06 0.07)
      (part-cyl 0.0 1.655 0.0 0.078 0.055)
      (col 0.05 0.05 0.06)
      (part-rbox -0.115 1.660 -0.105 0.055 0.115 0.075 0.028)
      (part-rbox -0.115 1.660 0.105 0.055 0.115 0.075 0.028)
      (part-rbox -0.145 1.630 0.0 0.045 0.090 0.115 0.030)
      ;; the helmet: the dome, the flared skirt that drops over the neck (a
      ;; cone widening downward -- that flare IS the silhouette), the raked
      ;; face mask, the eye lenses, the mouth grille and its ribs
      ;; a shade lighter than the cloth around it, and fully polished: black
      ;; armour against a black cape is legible only by its highlight
      (metal 0.11 0.11 0.13)
      (part-ellipsoid -0.012 1.815 0.0 0.122 0.128 0.126 10 6)
      (taper 0.152)
      (part-limb -0.012 1.815 0.0 -0.012 1.640 0.0 0.118)
      (metal 0.14 0.14 0.16)                   ; the raked face plate
      (part-rbox 0.088 1.800 0.0 0.062 0.105 0.100 0.030)
      (part-rbox 0.070 1.690 0.0 0.070 0.055 0.078 0.026)
      (col 0.02 0.02 0.03)                     ; the eye lenses
      (part-rbox 0.140 1.828 -0.050 0.022 0.030 0.034 0.010)
      (part-rbox 0.140 1.828 0.050 0.022 0.030 0.034 0.010)
      (col 0.05 0.05 0.06)                     ; the brow ridge between them
      (part-rbox 0.142 1.872 0.0 0.020 0.020 0.088 0.008)
      (col 0.13 0.13 0.14)                     ; the mouth grille
      (part-rbox 0.140 1.712 0.0 0.028 0.042 0.052 0.014)
      (metal 0.24 0.24 0.26)
      (part-limb 0.166 1.752 -0.030 0.166 1.674 -0.030 0.008)
      (part-limb 0.166 1.752 0.0 0.166 1.674 0.0 0.008)
      (part-limb 0.166 1.752 0.030 0.166 1.674 0.030 0.008)
      ;; the cape last, so it is drawn over the shoulders it hangs from
      (col 0.045 0.045 0.055)
      (emit-cape (* tm 1.4))
      ;; the red blade -- swept horizontally during a melee strike, like yours
      (let* ((theta (- 0.0 *vyaw*))
             (fwx (cos theta)) (fwz (sin theta))
             (arx (- 0.0 (sin theta))) (arz (cos theta)))
        (if (> *vswing* 0.0)
            ;; the same upright diagonal slash as yours
            (let* ((p (- 1.0 (/ *vswing* 0.45)))
                   (side (- 0.46 (* 0.92 p)))
                   (reachf (+ 0.38 (* 0.24 (sin (* p +pi+)))))
                   (hh (+ 1.22 (* -0.42 p)))
                   (hx (+ vdx (* fwx reachf) (* arx side)))
                   (hz (+ vdz (* fwz reachf) (* arz side)))
                   (half 0.66))
              (metal 0.18 0.18 0.20)
              (emit-cylinder hx hh hz 0.028 0.06 0.0 8)
              (setq *vsab-cx* hx *vsab-cy* (+ hh 0.12 half) *vsab-cz* hz
                    *vsab-hx* 0.032 *vsab-hy* half *vsab-hz* 0.032
                    *vsab-yaw* 0.0 *vsab-vis* t)
              (emit-vader-blade))
            (let* ((hx (+ vdx (* fwx 0.44) (* arx 0.14)))
                   (hz (+ vdz (* fwz 0.44) (* arz 0.14)))
                   (hh 1.06) (half 0.66))
              (metal 0.18 0.18 0.20)
              (emit-cylinder hx hh hz 0.028 0.09 0.0 8)
              (setq *vsab-cx* hx *vsab-cy* (+ hh 0.12 half) *vsab-cz* hz
                    *vsab-hx* 0.032 *vsab-hy* half *vsab-hz* 0.032
                    *vsab-yaw* 0.0 *vsab-vis* t)
              (emit-vader-blade))))))
  (setq *hit-tint* 0.0))

(defun emit-vader-blade ()
  (glow-col 1.0 0.24 0.20)
  (emit-cylinder *vsab-cx* *vsab-cy* *vsab-cz* *vsab-hx* *vsab-hy* *vsab-yaw* 8)
  (glow-col 1.0 0.82 0.80)
  (emit-cylinder *vsab-cx* *vsab-cy* *vsab-cz*
                 (max 0.012 (- *vsab-hx* 0.016)) (max 0.012 (- *vsab-hy* 0.016))
                 *vsab-yaw* 8))

(defun emit-vader-glow ()
  (when *vsab-vis*
    (glow-col 1.0 0.16 0.12)
    (emit-cylinder *vsab-cx* *vsab-cy* *vsab-cz*
                   (glowh *vsab-hx*) (glowh *vsab-hy*) *vsab-yaw* 8)
    (glow-col 0.55 0.05 0.04)
    (emit-cylinder *vsab-cx* *vsab-cy* *vsab-cz*
                   (glowh2 *vsab-hx*) (glowh2 *vsab-hy*) *vsab-yaw* 8)))

;; round sparks (a low-segment sphere, cheap enough for a per-frame particle)
;; instead of glowing cubes.
(defun emit-firework-cores ()
  ;; the solid, self-lit spark -- drawn in the OPAQUE pass so it keeps its own
  ;; vivid color against the bright sky (an additive-only spark washes out white)
  (dotimes (i +nfw+)
    (when (> (aref *fwt* i) 0.0)
      (let ((p (aref *fwpos* i)))
        (glow-col (aref *fwr* i) (aref *fwg* i) (aref *fwb* i))
        (emit-ellipsoid (aref p 0) (aref p 1) (aref p 2) 0.14 0.14 0.14 0.0 6 3)))))

(defun emit-fireworks ()
  ;; an additive halo around each spark, fading as it dies -> the bloom
  (dotimes (i +nfw+)
    (when (> (aref *fwt* i) 0.0)
      (let ((k (/ (aref *fwt* i) (aref *fwmax* i)))
            (p (aref *fwpos* i)))
        (glow-col (* 0.7 k (aref *fwr* i)) (* 0.7 k (aref *fwg* i)) (* 0.7 k (aref *fwb* i)))
        (emit-ellipsoid (aref p 0) (aref p 1) (aref p 2) 0.26 0.26 0.26 0.0 6 3)))))

;; a round beam instead of a box -- reads as a glowing capsule (per the
;; README) rather than a spinning rectangular slab.
(defun emit-bolt-core (i)
  (let* ((v (aref *bvel* i))
         (p (aref *bpos* i))
         (yaw (atan2 (- 0.0 (aref v 2)) (aref v 0)))
         (sz (aref *bsz* i)))
    (glow-col (aref *br* i) (aref *bg* i) (aref *bb* i))
    (emit-cyl-beam (aref p 0) (aref p 1) (aref p 2)
                   (* 0.30 sz) (* 0.05 sz) yaw 6)))

(defun emit-bolt-glow (i)
  (let* ((v (aref *bvel* i))
         (p (aref *bpos* i))
         (yaw (atan2 (- 0.0 (aref v 2)) (aref v 0)))
         (sz (aref *bsz* i)))
    (glow-col (* 0.5 (aref *br* i)) (* 0.5 (aref *bg* i)) (* 0.5 (aref *bb* i)))
    (emit-cyl-beam (aref p 0) (aref p 1) (aref p 2)
                   (* 0.42 sz) (* 0.13 sz) yaw 6)))

(defun emit-flash (i)
  (let ((k (/ (aref *ft* i) (aref *fttl* i)))
        (p (aref *fpos* i)))
    (glow-col (* k (aref *fr* i)) (* k (aref *fg* i)) (* k (aref *fb* i)))
    (let ((r (* (aref *fsz* i) (+ 0.4 (* 0.6 (- 1.0 k))))))
      (emit-ellipsoid (aref p 0) (aref p 1) (aref p 2) r r r 0.0 6 3))))

;; --- the frame ----------------------------------------------------------------

(defun draw (tm)
  (let ((w (canvas-width))
        (h (canvas-height)))
    (gl:viewport 0 0 (floor w) (floor h)))
  (gl:clear-color 0.74 0.83 0.93 1.0)
  (gl:clear (+ gl:+color-buffer-bit+ gl:+depth-buffer-bit+))
  (gl:use-program *prog*)
  (upload-vp *u-vp*)
  (gl:uniform3f *u-eye* (aref *eye* 0) (aref *eye* 1) (aref *eye* 2))

  ;; --- opaque pass: bodies, walkers, bolt cores, blade cores --------------
  (setq *v* *static-verts*)
  (emit-player tm)
  (dotimes (i *ntrooper*) (emit-trooper i tm))
  (dotimes (i *natat*) (emit-atat i tm))
  (emit-vader tm)
  (dotimes (i +nbolt+)
    (when (aref *balive* i) (emit-bolt-core i)))
  (emit-firework-cores)
  (let ((opaque-end *v*))
    ;; --- bloom pass: additive blade shells, bolt halos, flashes -----------
    (emit-player-saber-glow)
    (emit-vader-glow)
    (dotimes (i +nbolt+)
      (when (aref *balive* i) (emit-bolt-glow i)))
    (dotimes (i +nflash+)
      (when (> (aref *ft* i) 0.0) (emit-flash i)))
    (emit-fireworks)
    (gl:bind-buffer gl:+array-buffer+ *buf*)
    (gl-upload-vertices (* *static-verts* 11) (* (- *v* *static-verts*) 11))
    (gl:bind-vertex-array *vao*)
    ;; opaque geometry writes depth as usual
    (gl:draw-arrays gl:+triangles+ 0 opaque-end)
    ;; the glow accumulates additively and does not write depth
    (gl:enable gl:+blend+)
    (gl:blend-func gl:+one+ gl:+one+)
    (gl:depth-mask nil)
    (gl:draw-arrays gl:+triangles+ opaque-end (- *v* opaque-end))
    (gl:depth-mask t)
    (gl:disable gl:+blend+)))

(defun reset-game ()
  (setq *ppos* #f(0.0 0.0 0.0))
  (setq *pvel* #f(0.0 0.0 0.0))
  (setq *grounded* t)
  (setq *in-jump* 0.0)
  (setq *jump-prev* nil)
  (setq *inv-t* 0.0)
  (setq *php* +player-max-hp+)
  (setq *weapon* 1)                     ; start armed with the blaster
  (setq *attack* 0.0)
  (setq *swing-t* 0.0)
  (setq *swing-cd* 0.0)
  (setq *fire-cd* 0.0)
  (setq *hurt-flash* 0.0)
  (setq *state* 0)
  (setq *state-t* 0.0)
  (setq *cam-yaw* +cam-yaw-0+)
  (setq *cam-pitch* +cam-pitch-0+)
  (setq *cam-dist* +cam-dist-0+)
  (setq *cam* #f(0.0 0.0 0.0))
  (dotimes (i +nbolt+) (setf (aref *balive* i) nil))
  (dotimes (i +nflash+) (setf (aref *ft* i) 0.0))
  (dotimes (i +nfw+) (setf (aref *fwt* i) 0.0))
  (setq *fw-launch* 0.0)
  (reset-troopers)
  (reset-atats)
  (reset-vader))

(defun frame (tm)
  (when *pending-reset*
    (setq *pending-reset* nil)
    (reset-game))
  (let ((dt (min 0.05 (max 0.0 (- tm *last-tm*)))))
    (setq *last-tm* tm)
    (setq *aspect* (/ (canvas-width) (canvas-height)))
    (setq *state-t* (+ *state-t* dt))
    (update-aim)
    (cond ((= *state* 0) (step-playing dt))
          ((= *state* 1) (step-victory dt)))
    (update-bolts dt)
    (update-flashes dt)
    (update-enemy-flashes dt)
    (update-camera dt)
    (draw tm)))

;; --- HUD taps -----------------------------------------------------------------

(defun get-state () *state*)
(defun get-weapon () *weapon*)
(defun get-hp ()                        ; 0..100 percentage, for the HP bar
  (floor (* 100.0 (/ *php* +player-max-hp+))))
(defun get-troopers () (troopers-alive))
(defun get-walkers () (atats-alive))
(defun boss-active () (if (and *vactive* *valive*) 1 0))   ; hide the bar once he falls
(defun boss-hp ()                       ; 0..100, for the boss bar
  (if *valive* (floor (* 100.0 (/ *vhp* +vader-hp+))) 0))
(defun get-hurt () *hurt-flash*)
(defun get-px () (aref *ppos* 0))
(defun get-py () (aref *ppos* 1))
(defun get-pz () (aref *ppos* 2))

;; --- boot ---------------------------------------------------------------------
;; Runs inside _initialize, after the page has created the WebGL2 context: build
;; the pipeline, parse the roster and bake the snow field.

(setup-gl)
(parse-troopers)
(parse-atats)
(bake-static)
(reset-game)

(rontolisp:wasm-export 'frame :params '(:float) :returns :void)
(rontolisp:wasm-export 'set-key :as "setKey" :params '(:int :int) :returns :void)
(rontolisp:wasm-export 'set-attack :as "setAttack" :params '(:int) :returns :void)
(rontolisp:wasm-export 'switch-weapon :as "switchWeapon" :params '() :returns :void)
(rontolisp:wasm-export 'orbit :params '(:float :float) :returns :void)
(rontolisp:wasm-export 'zoom :params '(:float) :returns :void)
(rontolisp:wasm-export 'restart :params '() :returns :void)
(rontolisp:wasm-export 'get-state :as "getState" :params '() :returns :int)
(rontolisp:wasm-export 'get-weapon :as "getWeapon" :params '() :returns :int)
(rontolisp:wasm-export 'get-hp :as "getHp" :params '() :returns :int)
(rontolisp:wasm-export 'get-troopers :as "getTroopers" :params '() :returns :int)
(rontolisp:wasm-export 'get-walkers :as "getWalkers" :params '() :returns :int)
(rontolisp:wasm-export 'boss-active :as "bossActive" :params '() :returns :int)
(rontolisp:wasm-export 'boss-hp :as "bossHp" :params '() :returns :int)
(rontolisp:wasm-export 'get-hurt :as "getHurt" :params '() :returns :float)
(rontolisp:wasm-export 'get-px :as "getPx" :params '() :returns :float)
(rontolisp:wasm-export 'get-py :as "getPy" :params '() :returns :float)
(rontolisp:wasm-export 'get-pz :as "getPz" :params '() :returns :float)
