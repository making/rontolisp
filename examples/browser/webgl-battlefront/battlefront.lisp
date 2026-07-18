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
;;;; of the world -- you, the enemies, the walkers, the glowing blades and
;;;; bolts are all tessellated from rotated boxes each frame. The glowing
;;;; lightsabers and bolts are a second additive-blended pass over the same
;;;; buffer, so the blades bloom against the snow.
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
;; vertex is 10 floats -- position (3), normal (3), color (3) and an emissive
;; term (1) that makes the blades and bolts self-lit. Colors and the emissive
;; flag are constant per box, so set-color / set-emissive latch them and
;; set-vertex stages position + normal + those latched values (a call crosses
;; the WASM boundary with at most 7 parameters).
(rontolisp:wasm-import 'set-color :from "gl" :as "setColor"
                       :params '(:float :float :float) :returns :void)
(rontolisp:wasm-import 'set-emissive :from "gl" :as "setEmissive"
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
uniform mat4 uVP;                    // view-projection, computed in Lisp
out vec3 vN;
out vec3 vC;
out vec3 vW;
out float vE;
void main() {
  gl_Position = uVP * vec4(aPos, 1.0);
  vN = aNormal;
  vC = aColor;
  vW = aPos;
  vE = aEmit;
}")

(defconstant +solid-fs+ "#version 300 es
precision mediump float;
in vec3 vN;
in vec3 vC;
in vec3 vW;
in float vE;
uniform vec3 uEye;
out vec4 color;
void main() {
  vec3 n = normalize(vN);
  vec3 l = normalize(vec3(0.35, 0.82, 0.45));
  float diff = max(dot(n, l), 0.0);
  float amb  = 0.52 + 0.18 * n.y;           // hemisphere: tops brighter
  vec3 lit = vC * (amb + 0.55 * diff);
  vec3 shown = mix(lit, vC, vE);            // emissive: ignore the light
  vec3 sky = vec3(0.74, 0.83, 0.93);
  float fog = smoothstep(30.0, 95.0, distance(uEye, vW)) * (1.0 - vE);
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

(defconstant +max-verts+ 16384)         ; lit-triangle vertex capacity
(defconstant +stride+ 40)               ; 10 floats per vertex

(defun setup-gl ()
  (setq *prog* (gl:build-program +solid-vs+ +solid-fs+))
  (setq *u-vp* (gl:get-uniform-location *prog* "uVP"))
  (setq *u-eye* (gl:get-uniform-location *prog* "uEye"))
  (gl:enable gl:+depth-test+)
  ;; one VAO: position + normal + color + emissive
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
  (gl:vertex-attrib-pointer 3 1 gl:+float+ nil +stride+ 36))

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
(defvar *corners* nil)                  ; the current box's world corners, (3 8)

(defun emit-v (col nx ny nz)
  ;; stages corner `col` of *corners* with the latched color + the given normal
  (set-vertex *v* (aref *corners* 0 col) (aref *corners* 1 col) (aref *corners* 2 col)
              nx ny nz)
  (setq *v* (+ *v* 1)))

(defun emit-face (a b c d nx ny nz)
  (emit-v a nx ny nz)
  (emit-v b nx ny nz)
  (emit-v c nx ny nz)
  (emit-v a nx ny nz)
  (emit-v c nx ny nz)
  (emit-v d nx ny nz))

(defun emit-box (cx cy cz hx hy hz yaw)
  (let* ((c (cos yaw))
         (s (sin yaw))
         ;; the yaw rotation about +y, as a 3x3 matrix
         (rot (linalg:from-list (list (list c 0.0 s)
                                      (list 0.0 1.0 0.0)
                                      (list (- 0.0 s) 0.0 c))
                                'single-float))
         ;; the 8 local corners as columns of a (3 8) matrix
         (local (linalg:full '(3 8) 0.0 'single-float)))
    (dotimes (i 8)
      (setf (aref local 0 i) (if (= (logand i 1) 1) hx (- 0.0 hx)))
      (setf (aref local 1 i) (if (= (logand i 2) 2) hy (- 0.0 hy)))
      (setf (aref local 2 i) (if (= (logand i 4) 4) hz (- 0.0 hz))))
    ;; rotate every corner at once, then broadcast the center onto all 8
    (setq *corners* (linalg:add (linalg:matmul rot local)
                                (linalg:from-list (list (list cx) (list cy) (list cz))
                                                  'single-float)))
    ;; the face normals are the rotation's columns (yaw only tilts x / z)
    (emit-face 4 5 7 6 (aref rot 0 2) (aref rot 1 2) (aref rot 2 2))                     ; +z
    (emit-face 1 0 2 3 (- 0.0 (aref rot 0 2)) (- 0.0 (aref rot 1 2)) (- 0.0 (aref rot 2 2))) ; -z
    (emit-face 5 1 3 7 (aref rot 0 0) (aref rot 1 0) (aref rot 2 0))                     ; +x
    (emit-face 0 4 6 2 (- 0.0 (aref rot 0 0)) (- 0.0 (aref rot 1 0)) (- 0.0 (aref rot 2 0))) ; -x
    (emit-face 6 7 3 2 0.0 1.0 0.0)                                                      ; +y
    (emit-face 0 1 5 4 0.0 -1.0 0.0)))                                                   ; -y

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
  (setq *os* (sin yaw)))

(defun part (lx ly lz hx hy hz)
  (emit-box (+ *ox* (* lx *oc*) (* lz *os*))
            (+ *oy* ly)
            (+ *oz* (- (* lz *oc*) (* lx *os*)))
            hx hy hz *oyaw*))

;; colour + emissive helpers
;; *hit-tint* (0..1) reddens and lights whatever `col` draws next -- set around
;; an enemy's body while its damage flash is active, 0 otherwise, so a struck
;; enemy glows red without touching any call site.
(defvar *hit-tint* 0.0)
(defun col (r g b)
  (if (> *hit-tint* 0.0)
      (let ((k *hit-tint*))
        (set-emissive (* 0.55 k))
        (set-color (+ r (* k (- 1.0 r))) (* g (- 1.0 k)) (* b (- 1.0 k))))
      (progn (set-emissive 0.0) (set-color r g b))))
(defun glow-col (r g b) (set-emissive 1.0) (set-color r g b))

;; --- the snow field -----------------------------------------------------------
;;
;; Static geometry, baked once: the snow plate, low drifts, ice boulders, the
;; ring of Hoth mountains and a couple of clouds. Each block is
;; (x0 y0 z0 x1 y1 z1 r g b); nothing here collides -- the arena is open.

(defconstant +scenery+
  '((-40.0 -1.5 -40.0 60.0 0.0 40.0 0.90 0.93 0.98)       ; the snow plate
    ;; low snow drifts
    (6.0 0.0 -14.0 12.0 0.7 -9.0 0.95 0.97 1.00)
    (18.0 0.0 10.0 25.0 0.9 15.0 0.95 0.97 1.00)
    (-8.0 0.0 6.0 -2.0 0.6 11.0 0.95 0.97 1.00)
    (34.0 0.0 -10.0 41.0 1.0 -4.0 0.95 0.97 1.00)
    (28.0 0.0 18.0 36.0 0.8 24.0 0.95 0.97 1.00)
    ;; ice boulders (cold blue-grey)
    (2.0 0.0 12.0 4.4 1.8 14.4 0.66 0.74 0.82)
    (14.0 0.0 -6.0 15.8 1.3 -4.2 0.62 0.70 0.80)
    (30.0 0.0 4.0 32.6 2.2 6.6 0.66 0.74 0.82)
    (-6.0 0.0 -12.0 -4.0 1.5 -10.0 0.62 0.70 0.80)
    (44.0 0.0 8.0 47.0 2.6 11.0 0.66 0.74 0.82)
    ;; the ring of Hoth mountains on the horizon
    (-40.0 -1.0 -70.0 60.0 22.0 -46.0 0.80 0.86 0.94)
    (-40.0 -1.0 46.0 60.0 20.0 70.0 0.80 0.86 0.94)
    (-80.0 -1.0 -60.0 -46.0 24.0 60.0 0.80 0.86 0.94)
    (66.0 -1.0 -60.0 104.0 26.0 60.0 0.80 0.86 0.94)
    ;; a few clouds
    (10.0 15.0 -30.0 20.0 17.0 -25.0 0.99 0.99 1.00)
    (34.0 17.0 22.0 45.0 19.0 28.0 0.99 0.99 1.00)
    (-14.0 16.0 4.0 -4.0 18.0 10.0 0.99 0.99 1.00)))

(defun emit-block (b)
  (col (nth 6 b) (nth 7 b) (nth 8 b))
  (emit-box (* 0.5 (+ (nth 0 b) (nth 3 b)))
            (* 0.5 (+ (nth 1 b) (nth 4 b)))
            (* 0.5 (+ (nth 2 b) (nth 5 b)))
            (* 0.5 (- (nth 3 b) (nth 0 b)))
            (* 0.5 (- (nth 4 b) (nth 1 b)))
            (* 0.5 (- (nth 5 b) (nth 2 b)))
            0.0))

(defun bake-static ()
  (setq *v* 0)
  (dolist (b +scenery+) (emit-block b))
  (setq *static-verts* *v*)
  (gl:bind-buffer gl:+array-buffer+ *buf*)
  (gl-upload-vertices 0 (* *static-verts* 10)))

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
              (let ((muzzle (linalg:from-list (list (+ (aref p 0) (* (cos (aref *ayaw* i)) 2.4))
                                  4.6
                                  (- (aref p 2) (* (sin (aref *ayaw* i)) 2.4))) 'single-float)))
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
  (col 0.55 0.60 0.68)
  (emit-box x 0.02 z r 0.006 r 0.0))

;; You, in Hoth (Echo Base) gear: tan jacket over dark trousers and boots,
;; the field backpack, a knit cap, holding either the glowing blue lightsaber
;; or the blaster. Human-ish proportions, ~1.7 tall.
(defun emit-player (tm)
  (emit-shadow (aref *ppos* 0) (aref *ppos* 2) 0.36)
  (set-origin (aref *ppos* 0) (aref *ppos* 1) (aref *ppos* 2) *pyaw*)
  (let* ((sw (sin *run-phase*))
         (leg (* 0.15 sw))
         (arm (* -0.13 sw)))
    ;; boots
    (col 0.16 0.13 0.10)
    (part leg 0.07 -0.10 0.085 0.07 0.11)
    (part (- 0.0 leg) 0.07 0.10 0.085 0.07 0.11)
    ;; trouser legs (olive-tan)
    (col 0.48 0.44 0.33)
    (part leg 0.42 -0.10 0.09 0.30 0.085)
    (part (- 0.0 leg) 0.42 0.10 0.09 0.30 0.085)
    ;; hips / belt
    (col 0.42 0.37 0.28)
    (part 0.0 0.79 0.0 0.16 0.10 0.13)
    (col 0.28 0.22 0.16)
    (part 0.03 0.76 0.0 0.14 0.045 0.135)      ; belt
    ;; the tan jacket torso, with a darker vest panel
    (col 0.80 0.70 0.52)
    (part 0.0 1.07 0.0 0.185 0.22 0.135)
    (col 0.60 0.50 0.36)
    (part 0.05 1.05 0.0 0.13 0.17 0.13)
    (col 0.86 0.78 0.62)                        ; collar
    (part 0.0 1.28 0.0 0.125 0.05 0.115)
    ;; the Hoth field pack on the back
    (col 0.33 0.31 0.27)
    (part -0.17 1.05 0.0 0.08 0.19 0.15)
    (col 0.20 0.20 0.21)
    (part -0.25 1.09 0.0 0.03 0.10 0.09)
    ;; shoulders
    (col 0.74 0.64 0.47)
    (part 0.0 1.22 -0.20 0.07 0.06 0.075)
    (part 0.0 1.22 0.20 0.07 0.06 0.075)
    ;; the off hand (upper arm + glove), swinging; the weapon arm is drawn by
    ;; emit-arm-to, reaching to the animated weapon hand
    (col 0.74 0.64 0.47)
    (part (- 0.0 arm) 1.02 0.22 0.06 0.16 0.06)
    (col 0.18 0.15 0.12)
    (part (- 0.0 arm) 0.85 0.22 0.055 0.055 0.055)
    ;; neck + head (smaller head = less toy-like)
    (col 0.80 0.62 0.50)
    (part 0.0 1.37 0.0 0.05 0.05 0.05)
    (col 0.86 0.70 0.58)
    (part 0.02 1.49 0.0 0.095 0.105 0.095)
    (col 0.32 0.25 0.20)                        ; brow shadow
    (part 0.09 1.49 0.0 0.02 0.03 0.07)
    ;; the knit cap with its darker band
    (col 0.70 0.62 0.46)
    (part 0.0 1.60 0.0 0.11 0.055 0.11)
    (col 0.50 0.43 0.33)
    (part 0.0 1.55 0.0 0.113 0.02 0.113))
  (if (= *weapon* 0)
      (emit-player-saber tm)
      (emit-player-blaster)))

;; A forearm reaching from the weapon-side shoulder to the (animated) weapon
;; hand -- so the arm visibly follows the hand as it swings. yaw-only, so the
;; box tracks the hand in the horizontal plane; the small vertical tilt is
;; folded into the midpoint height.
(defun emit-arm-to (hx hy hz r g b)
  (let* ((arrx (- 0.0 (sin *cam-yaw*)))       ; aim-right
         (arrz (cos *cam-yaw*))
         (sx (+ (aref *ppos* 0) (* arrx -0.18)))   ; weapon-side shoulder
         (sz (+ (aref *ppos* 2) (* arrz -0.18)))
         (sy (+ (aref *ppos* 1) 1.16))
         (ddx (- hx sx)) (ddz (- hz sz))
         (len (sqrt (+ (* ddx ddx) (* ddz ddz)))))
    (col r g b)
    (emit-box (* 0.5 (+ sx hx)) (* 0.5 (+ sy hy)) (* 0.5 (+ sz hz))
              (max 0.06 (* 0.5 len)) 0.05 0.05 (atan2 (- 0.0 ddz) ddx))))

;; blade-glow inflation: pad the thin cross-axes more than the long axis
(defun glowh (h) (if (< h 0.2) (+ h 0.045) (+ h 0.03)))
(defun glowh2 (h) (if (< h 0.2) (+ h 0.09) (+ h 0.055)))

(defun emit-player-blaster ()
  ;; a compact grey blaster pistol, held out along the aim
  (let* ((theta *cam-yaw*)
         (fwx (cos theta)) (fwz (sin theta))
         (arx (- 0.0 (sin theta))) (arz (cos theta))
         (hx (+ (aref *ppos* 0) (* fwx 0.40) (* arx -0.15)))
         (hz (+ (aref *ppos* 2) (* fwz 0.40) (* arz -0.15)))
         (hy (+ (aref *ppos* 1) 0.98)))
    (emit-arm-to hx hy hz 0.74 0.64 0.47)
    (col 0.15 0.12 0.10)
    (emit-box hx hy hz 0.05 0.05 0.05 0.0)               ; glove
    (col 0.17 0.18 0.21)
    (emit-box (+ hx (* fwx 0.12)) (+ hy 0.01) (+ hz (* fwz 0.12))
              0.12 0.035 0.035 *pyaw*)                   ; barrel
    (col 0.13 0.13 0.15)
    (emit-box hx (- hy 0.06) hz 0.03 0.055 0.03 0.0)))   ; grip

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
  (col 0.16 0.13 0.11)                          ; glove
  (emit-box x y z 0.05 0.05 0.05 0.0)
  (col 0.76 0.77 0.81)                          ; metal hilt
  (emit-box x (+ y 0.05) z 0.028 0.055 0.028 0.0))

(defun emit-player-blade ()
  ;; the stored blade: a blue core with a white-hot inner line
  (glow-col 0.55 0.78 1.0)
  (emit-box *sab-cx* *sab-cy* *sab-cz* *sab-hx* *sab-hy* *sab-hz* *sab-yaw*)
  (glow-col 0.92 0.97 1.0)
  (emit-box *sab-cx* *sab-cy* *sab-cz*
            (max 0.012 (- *sab-hx* 0.016)) (max 0.012 (- *sab-hy* 0.016))
            (max 0.012 (- *sab-hz* 0.016)) *sab-yaw*))

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
    (emit-box *sab-cx* *sab-cy* *sab-cz*
              (glowh *sab-hx*) (glowh *sab-hy*) (glowh *sab-hz*) *sab-yaw*)
    (glow-col 0.12 0.28 0.85)
    (emit-box *sab-cx* *sab-cy* *sab-cz*
              (glowh2 *sab-hx*) (glowh2 *sab-hy*) (glowh2 *sab-hz*) *sab-yaw*)))

;; A stormtrooper: white armour plates over a black bodysuit, the iconic helmet
;; (dome + black brow band + vocoder snout), an E-11 blaster. ~1.65 tall.
(defun emit-trooper (i tm)
  (setq *hit-tint* (min 1.0 (* (aref *thit* i) 6.0)))   ; red when just struck
  (let ((p (aref *tpos* i)))
    (if (aref *talive* i)
        (let* ((sw (sin (aref *tstep* i)))
               (leg (* 0.13 sw))
               (arm (* -0.11 sw)))
          (set-origin (aref p 0) 0.0 (aref p 2) (aref *tyaw* i))
          (emit-shadow (aref p 0) (aref p 2) 0.34)
          ;; black bodysuit legs, white shin/thigh plates and boots
          (col 0.11 0.11 0.13)
          (part leg 0.40 -0.10 0.075 0.30 0.08)
          (part (- 0.0 leg) 0.40 0.10 0.075 0.30 0.08)
          (col 0.92 0.93 0.97)
          (part leg 0.24 -0.10 0.088 0.16 0.083)     ; shin plate
          (part (- 0.0 leg) 0.24 0.10 0.088 0.16 0.083)
          (part leg 0.06 -0.10 0.09 0.06 0.10)       ; boot
          (part (- 0.0 leg) 0.06 0.10 0.09 0.06 0.10)
          ;; black belt
          (col 0.10 0.10 0.12)
          (part 0.0 0.76 0.0 0.145 0.05 0.115)
          ;; the white chest + ab plates
          (col 0.93 0.94 0.97)
          (part 0.0 1.04 0.0 0.16 0.19 0.115)
          (col 0.82 0.84 0.88)
          (part 0.03 0.90 0.0 0.14 0.055 0.11)       ; ab line
          ;; shoulder bells + white arms + black hands
          (col 0.93 0.94 0.97)
          (part 0.0 1.20 -0.19 0.07 0.06 0.08)
          (part 0.0 1.20 0.19 0.07 0.06 0.08)
          (part arm 1.02 -0.22 0.058 0.16 0.058)
          (part (- 0.0 arm) 1.02 0.22 0.058 0.16 0.058)
          (col 0.10 0.10 0.12)
          (part 0.18 0.86 -0.22 0.05 0.055 0.05)
          (part (- 0.0 arm) 0.86 0.22 0.05 0.055 0.05)
          ;; black neck seal
          (col 0.10 0.10 0.12)
          (part 0.0 1.29 0.0 0.06 0.05 0.06)
          ;; the helmet: dome, black brow band, vocoder snout
          (col 0.95 0.96 0.99)
          (part 0.0 1.44 0.0 0.098 0.108 0.098)
          (col 0.10 0.10 0.12)
          (part 0.07 1.46 0.0 0.045 0.05 0.088)
          (col 0.90 0.91 0.95)
          (part 0.10 1.39 0.0 0.03 0.05 0.05)
          (col 0.18 0.18 0.20)
          (part 0.115 1.39 0.0 0.014 0.035 0.03)
          ;; the E-11 blaster held across the body
          (col 0.10 0.10 0.12)
          (part 0.30 0.90 -0.20 0.12 0.028 0.028)
          (part 0.20 0.84 -0.20 0.028 0.05 0.028))
        ;; a fallen trooper: a flattened heap with the helmet beside it
        (progn
          (set-origin (aref p 0) 0.0 (aref p 2) (aref *tyaw* i))
          (col 0.86 0.88 0.92)
          (part 0.0 0.08 0.0 0.24 0.08 0.15)
          (part 0.22 0.08 0.06 0.09 0.08 0.09))))
  (setq *hit-tint* 0.0))

(defun emit-atat (i tm)
  (setq *hit-tint* (min 1.0 (* (aref *ahit* i) 6.0)))   ; red when just struck
  (let* ((p (aref *apos* i))
         (dx (- (aref *ppos* 0) (aref p 0)))
         (dz (- (aref *ppos* 2) (aref p 2)))
         (far2 (+ (* dx dx) (* dz dz))))    ; squared horizontal distance to you
    (if (aref *aalive* i)
        (let* ((yaw (aref *ayaw* i))
               (walk (if (> far2 170.0) (sin (* tm 2.2)) 0.0)))
          (set-origin (aref p 0) 0.0 (aref p 2) yaw)
          ;; four legs
          (col 0.52 0.54 0.57)
          (part (* 0.35 walk) 1.6 -0.9 0.16 1.6 0.16)
          (part (* -0.35 walk) 1.6 0.9 0.16 1.6 0.16)
          (part (* -0.35 walk) 1.6 -0.9 0.16 1.6 0.16)
          (part (* 0.35 walk) 1.6 0.9 0.16 1.6 0.16)
          ;; the slab body
          (col 0.62 0.64 0.67)
          (part 0.0 3.7 0.0 1.5 0.8 0.95)
          (col 0.56 0.58 0.61)
          (part 0.3 3.7 0.0 1.1 0.62 0.8)
          ;; neck + head
          (col 0.6 0.62 0.65)
          (part 1.55 4.2 0.0 0.4 0.42 0.35)
          (col 0.66 0.68 0.71)
          (part 2.1 4.55 0.0 0.42 0.36 0.5)
          ;; the two chin guns
          (col 0.2 0.2 0.22)
          (part 2.45 4.35 -0.16 0.22 0.05 0.05)
          (part 2.45 4.35 0.16 0.22 0.05 0.05))
        ;; a smoking wreck: the body slumped to the snow
        (let ((k (min 1.0 (/ (aref *awreck* i) 1.2))))
          (set-origin (aref p 0) 0.0 (aref p 2) (aref *ayaw* i))
          (col 0.34 0.34 0.35)
          (part 0.0 (- 1.3 (* 0.8 k)) 0.0 1.5 0.7 0.95)
          (col 0.22 0.22 0.24)
          (part 1.4 (- 1.2 (* 0.7 k)) 0.0 0.5 0.4 0.45))))
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

;; Vader: all black armour under a heavy cape, the domed helmet with its
;; flared mask, the chest control box, a red lightsaber. Tall (~1.9) and broad.
;; Only shown once he engages -- dormant until the walkers fall.
(defun emit-vader (tm)
  (setq *vsab-vis* nil)
  (setq *hit-tint* (min 1.0 (* *vhitf* 6.0)))          ; red when just struck
  (when (and *valive* *vactive*)
    (let ((vdx (aref *vpos* 0)) (vdz (aref *vpos* 2)))
      (set-origin vdx 0.0 vdz *vyaw*)
      (emit-shadow vdx vdz 0.44)
      ;; the cape, drawn first: a broad slab down the back and a lower flare
      (col 0.04 0.04 0.05)
      (part -0.15 1.02 0.0 0.06 0.86 0.30)
      (part -0.11 0.42 0.0 0.10 0.42 0.34)
      ;; boots + legs
      (col 0.05 0.05 0.06)
      (part 0.0 0.09 -0.14 0.11 0.09 0.13)
      (part 0.0 0.09 0.14 0.11 0.09 0.13)
      (col 0.07 0.07 0.08)
      (part 0.0 0.52 -0.14 0.11 0.36 0.11)
      (part 0.0 0.52 0.14 0.11 0.36 0.11)
      ;; belt with side boxes
      (col 0.10 0.10 0.11)
      (part 0.0 0.92 0.0 0.20 0.08 0.15)
      (col 0.16 0.14 0.10)
      (part 0.13 0.92 -0.09 0.045 0.05 0.045)
      (part 0.13 0.92 0.09 0.045 0.05 0.045)
      ;; torso + the chest control box with its lights
      (col 0.08 0.08 0.09)
      (part 0.0 1.28 0.0 0.20 0.30 0.145)
      (col 0.13 0.13 0.15)
      (part 0.15 1.30 0.0 0.06 0.13 0.11)
      (glow-col 1.0 0.2 0.18)
      (emit-box (+ vdx (* (cos *vyaw*) 0.205)) 1.34
                (- vdz (* (sin *vyaw*) 0.205)) 0.02 0.02 0.02 0.0)
      (emit-box (+ vdx (* (cos *vyaw*) 0.205)) 1.27
                (- vdz (* (sin *vyaw*) 0.205)) 0.018 0.018 0.018 0.0)
      (glow-col 0.2 0.85 0.3)
      (emit-box (+ vdx (* (cos *vyaw*) 0.205)) 1.31
                (- vdz (* (sin *vyaw*) 0.205)) 0.016 0.016 0.016 0.0)
      ;; shoulders + arms + gloved hands
      (col 0.05 0.05 0.06)
      (part 0.0 1.52 -0.22 0.09 0.055 0.10)
      (part 0.0 1.52 0.22 0.09 0.055 0.10)
      (col 0.07 0.07 0.08)
      (part 0.0 1.30 -0.26 0.065 0.22 0.065)
      (part 0.14 1.28 0.26 0.065 0.20 0.065)
      (col 0.03 0.03 0.04)
      (part 0.0 1.05 -0.26 0.06 0.06 0.06)
      ;; neck
      (col 0.06 0.06 0.07)
      (part 0.0 1.62 0.0 0.075 0.06 0.075)
      ;; the helmet: domed back, flared sides, angled face mask, mouth grille
      (col 0.06 0.06 0.07)
      (part -0.01 1.78 0.0 0.125 0.135 0.135)
      (part 0.0 1.66 -0.14 0.075 0.10 0.055)
      (part 0.0 1.66 0.14 0.075 0.10 0.055)
      (col 0.09 0.09 0.10)
      (part 0.08 1.74 0.0 0.075 0.115 0.115)
      (col 0.03 0.03 0.04)                     ; the eye lenses
      (part 0.135 1.78 -0.05 0.02 0.028 0.03)
      (part 0.135 1.78 0.05 0.02 0.028 0.03)
      (col 0.12 0.12 0.13)                     ; the mouth grille
      (part 0.14 1.66 0.0 0.025 0.045 0.05)
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
              (col 0.18 0.18 0.20)
              (emit-box hx hh hz 0.028 0.06 0.028 0.0)
              (setq *vsab-cx* hx *vsab-cy* (+ hh 0.12 half) *vsab-cz* hz
                    *vsab-hx* 0.032 *vsab-hy* half *vsab-hz* 0.032
                    *vsab-yaw* 0.0 *vsab-vis* t)
              (emit-vader-blade))
            (let* ((hx (+ vdx (* fwx 0.44) (* arx 0.14)))
                   (hz (+ vdz (* fwz 0.44) (* arz 0.14)))
                   (hh 1.06) (half 0.66))
              (col 0.18 0.18 0.20)
              (emit-box hx hh hz 0.028 0.09 0.028 0.0)
              (setq *vsab-cx* hx *vsab-cy* (+ hh 0.12 half) *vsab-cz* hz
                    *vsab-hx* 0.032 *vsab-hy* half *vsab-hz* 0.032
                    *vsab-yaw* 0.0 *vsab-vis* t)
              (emit-vader-blade))))))
  (setq *hit-tint* 0.0))

(defun emit-vader-blade ()
  (glow-col 1.0 0.24 0.20)
  (emit-box *vsab-cx* *vsab-cy* *vsab-cz* *vsab-hx* *vsab-hy* *vsab-hz* *vsab-yaw*)
  (glow-col 1.0 0.82 0.80)
  (emit-box *vsab-cx* *vsab-cy* *vsab-cz*
            (max 0.012 (- *vsab-hx* 0.016)) (max 0.012 (- *vsab-hy* 0.016))
            (max 0.012 (- *vsab-hz* 0.016)) *vsab-yaw*))

(defun emit-vader-glow ()
  (when *vsab-vis*
    (glow-col 1.0 0.16 0.12)
    (emit-box *vsab-cx* *vsab-cy* *vsab-cz*
              (glowh *vsab-hx*) (glowh *vsab-hy*) (glowh *vsab-hz*) *vsab-yaw*)
    (glow-col 0.55 0.05 0.04)
    (emit-box *vsab-cx* *vsab-cy* *vsab-cz*
              (glowh2 *vsab-hx*) (glowh2 *vsab-hy*) (glowh2 *vsab-hz*) *vsab-yaw*)))

(defun emit-firework-cores ()
  ;; the solid, self-lit spark -- drawn in the OPAQUE pass so it keeps its own
  ;; vivid color against the bright sky (an additive-only spark washes out white)
  (dotimes (i +nfw+)
    (when (> (aref *fwt* i) 0.0)
      (let ((p (aref *fwpos* i)))
        (glow-col (aref *fwr* i) (aref *fwg* i) (aref *fwb* i))
        (emit-box (aref p 0) (aref p 1) (aref p 2) 0.14 0.14 0.14 0.0)))))

(defun emit-fireworks ()
  ;; an additive halo around each spark, fading as it dies -> the bloom
  (dotimes (i +nfw+)
    (when (> (aref *fwt* i) 0.0)
      (let ((k (/ (aref *fwt* i) (aref *fwmax* i)))
            (p (aref *fwpos* i)))
        (glow-col (* 0.7 k (aref *fwr* i)) (* 0.7 k (aref *fwg* i)) (* 0.7 k (aref *fwb* i)))
        (emit-box (aref p 0) (aref p 1) (aref p 2) 0.26 0.26 0.26 0.0)))))

(defun emit-bolt-core (i)
  (let* ((v (aref *bvel* i))
         (p (aref *bpos* i))
         (yaw (atan2 (- 0.0 (aref v 2)) (aref v 0)))
         (sz (aref *bsz* i)))
    (glow-col (aref *br* i) (aref *bg* i) (aref *bb* i))
    (emit-box (aref p 0) (aref p 1) (aref p 2)
              (* 0.30 sz) (* 0.05 sz) (* 0.05 sz) yaw)))

(defun emit-bolt-glow (i)
  (let* ((v (aref *bvel* i))
         (p (aref *bpos* i))
         (yaw (atan2 (- 0.0 (aref v 2)) (aref v 0)))
         (sz (aref *bsz* i)))
    (glow-col (* 0.5 (aref *br* i)) (* 0.5 (aref *bg* i)) (* 0.5 (aref *bb* i)))
    (emit-box (aref p 0) (aref p 1) (aref p 2)
              (* 0.42 sz) (* 0.13 sz) (* 0.13 sz) yaw)))

(defun emit-flash (i)
  (let ((k (/ (aref *ft* i) (aref *fttl* i)))
        (p (aref *fpos* i)))
    (glow-col (* k (aref *fr* i)) (* k (aref *fg* i)) (* k (aref *fb* i)))
    (let ((r (* (aref *fsz* i) (+ 0.4 (* 0.6 (- 1.0 k))))))
      (emit-box (aref p 0) (aref p 1) (aref p 2) r r r 0.0))))

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
    (gl-upload-vertices (* *static-verts* 10) (* (- *v* *static-verts*) 10))
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
