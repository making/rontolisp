;;;; metal-robot-arm.lisp -- a 3-D robot arm that reaches for wherever you
;;;; click, on the GPU of a Mac.
;;;;
;;;; The AppKit twin of examples/browser/webgl-robot-arm, and the largest of the
;;;; Metal examples: metal-triangle.lisp draws without a buffer, metal-cube.lisp
;;;; adds one mesh and one uniform, and this one adds everything a real renderer
;;;; needs -- a depth attachment, two pipelines (lit triangles and additive glow
;;;; sprites), geometry re-tessellated every frame into buffers kept in flight,
;;;; and mouse input.
;;;;
;;;; Click in the window: the arm reaches for that point in 3-D, the three-finger
;;;; gripper opening for the flight and closing on arrival. Drag to orbit the
;;;; camera, scroll to zoom.
;;;;
;;;; Everything below the window is the browser program's, unchanged in
;;;; substance: the commanded hand position travels along the minimum-jerk
;;;; profile 10u^3 - 15u^4 + 6u^5 (zero velocity and acceleration at both ends,
;;;; Flash & Hogan 1985), the chain is solved by damped-least-squares Jacobian
;;;; iterations -- dtheta = J^T (J J^T + lambda^2 I)^-1 e -- and the machine is
;;;; tessellated in Lisp into tapered tubes, discs and spheres with world-space
;;;; normals. (The browser demo offers two more solvers from its HUD; this one
;;;; carries the default, which is the interesting one.)
;;;;
;;;; What is NOT the browser program's is the host boundary, and that is the
;;;; whole point of the pair. There the page owns WebGL2, two staging
;;;; Float32Arrays and the pointer gestures, and Lisp reaches them through 44
;;;; imported functions. Here there is no host at all: `objc:send` IS the GPU
;;;; API, a packed single-float array IS the vertex buffer's bytes, and the
;;;; mouse arrives through an NSView subclass whose mouseDown: / mouseDragged: /
;;;; scrollWheel: are Lisp closures -- objc:define-class, the same verb
;;;; appkit.lisp uses to make an NSBox answer a click.
;;;;
;;;; Run it (macOS, on any of the three backends that carry the binding):
;;;;
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/macos/metal-robot-arm.lisp
;;;;   rontolisp examples/macos/metal-robot-arm.lisp
;;;;   rontolisp examples/macos/metal-robot-arm.lisp -o Arm.class --class-name Arm && java Arm

(require :metal "metal.lisp")

;;; --- the shaders --------------------------------------------------------------
;;;
;;; One library, four functions, two pipelines: lit triangles for the solid
;;; machine and additive point sprites for the glow. Neither uses a vertex
;;; descriptor -- the vertex function indexes a `const device` array by
;;; vertex_id, so the layout is the Lisp array's layout and nothing declares it
;;; twice. packed_float3 is 12 bytes, which is what makes the strides come out
;;; at 36 and 20.

(defvar *shaders*
  "
#include <metal_stdlib>
using namespace metal;

struct Vertex {
  packed_float3 position;   // world space, from Lisp
  packed_float3 normal;     // world space, from Lisp
  packed_float3 color;
};

struct VertexOut {
  float4 position [[position]];
  float3 normal;
  float3 color;
  float3 world;
};

vertex VertexOut solid_vertex(uint id [[vertex_id]],
                              const device Vertex *vertices [[buffer(0)]],
                              constant float4x4 &vp [[buffer(1)]]) {
  VertexOut out;
  float3 p = vertices[id].position;
  out.position = vp * float4(p, 1.0);
  out.normal = vertices[id].normal;
  out.color = vertices[id].color;
  out.world = p;
  return out;
}

// one key light + a hemisphere ambient + blinn-phong spec + a cool rim
fragment float4 solid_fragment(VertexOut in [[stage_in]],
                               constant float4 &eye [[buffer(0)]]) {
  float3 n = normalize(in.normal);
  float3 l = normalize(float3(0.5, 0.85, 0.35));
  float3 e = normalize(eye.xyz - in.world);
  float3 h = normalize(l + e);
  float diff = max(dot(n, l), 0.0);
  float amb  = 0.30 + 0.14 * n.y;
  float spec = pow(max(dot(n, h), 0.0), 48.0) * 0.45;
  float rim  = pow(1.0 - max(dot(n, e), 0.0), 3.0) * 0.18;
  return float4(in.color * (amb + 0.75 * diff) + float3(spec)
                  + float3(0.35, 0.55, 0.9) * rim, 1.0);
}

struct Sprite {
  packed_float3 position;   // world space, from Lisp
  float tone;               // 0..1 palette position
  float size;               // in drawable pixels at w = 1
};

struct SpriteOut {
  float4 position [[position]];
  float size [[point_size]];
  float tone;
};

vertex SpriteOut sprite_vertex(uint id [[vertex_id]],
                               const device Sprite *sprites [[buffer(0)]],
                               constant float4x4 &vp [[buffer(1)]]) {
  SpriteOut out;
  float4 p = vp * float4(sprites[id].position, 1.0);
  out.position = p;
  out.size = sprites[id].size / max(p.w, 0.1);  // perspective-sized sprites
  out.tone = sprites[id].tone;
  return out;
}

// tone ~0.5 -> trail cyan, ~0.7 -> ember, 1 -> white-hot target.
static float3 tint(float h) {
  float3 steel = float3(0.30, 0.44, 0.85);
  float3 cyan  = float3(0.30, 0.95, 0.85);
  float3 ember = float3(1.00, 0.55, 0.18);
  float3 hot   = float3(1.00, 0.97, 0.90);
  return h < 0.45 ? mix(steel, cyan, h / 0.45)
       : h < 0.80 ? mix(cyan, ember, (h - 0.45) / 0.35)
       :            mix(ember, hot, (h - 0.80) / 0.20);
}

// a soft round sprite under additive blending
fragment float4 sprite_fragment(SpriteOut in [[stage_in]],
                                float2 uv [[point_coord]]) {
  float d = length(uv - 0.5) * 2.0;
  float a = exp(-3.0 * d * d) * (1.0 - smoothstep(0.8, 1.0, d));
  float glow = 0.22 + 0.78 * in.tone;
  return float4(tint(in.tone) * a * glow, a * glow);
}
")

(defconstant +pi+ 3.141592653589793)
(defconstant +two-pi+ 6.283185307179586)

;;; --- points are arrays, and arithmetic on them is linalg ----------------------
;;;
;;; Not one coordinate in this program is a scalar variable: a point, a
;;; direction, a colour and a camera axis are all packed single-float 3-vectors,
;;; a joint chain is a rank-2 (joint xyz) array, and combining them is a
;;; `linalg` call rather than three lines of the same expression with x, y and z
;;; spelled into it. That is not only shorter -- it is what makes the matrix
;;; steps below (the look-at, the Jacobian, the damped normal equations) READ as
;;; the matrix expressions they are, and a packed single-float array is exactly
;;; the float32 the GPU wants, so nothing is converted on the way out either.
;;;
;;; The one place that stays scalar is the innermost tessellation loop: it
;;; writes six floats straight into the vertex array, several thousand times a
;;; frame, and a fresh 3-vector per corner would be an allocation per float
;;; written. State is vectors; the loop that turns state into bytes is not.

(defun vec3 (x y z)
  (let ((v (make-array 3 :element-type 'single-float)))
    (setf (aref v 0) x)
    (setf (aref v 1) y)
    (setf (aref v 2) z)
    v))

(defun normalized (v)
  (let ((n (linalg:norm v)))
    (linalg:mul v (/ 1.0 (if (< n 0.000001) 0.000001 n)))))

;;; --- 4x4 matrices -------------------------------------------------------------
;;;
;;; As in metal-cube.lisp: a linalg result is a PACKED float array and objc:data
;;; takes one of any rank, so a matrix reaches the GPU with no conversion step.
;;; :element-type 'single-float makes them float32 (what a Metal float4x4 holds)
;;; and every linalg transform preserves that width. The matrices multiply a
;;; COLUMN vector, so VP = P . V is one linalg:matmul, and the single
;;; linalg:transpose on the way out bridges linalg's row-major storage to
;;; Metal's column-major float4x4.

(defconstant +fovy+ 0.7853981633974483) ; 45 degrees
(defconstant +half-fov+ 0.39269908169872414)

;; Metal's clip space puts z in [0, 1], not OpenGL's [-1, 1] -- the one line of
;; the browser program that could not be copied.
(defun perspective (aspect near far)
  (let ((m (linalg:zeros '(4 4) :element-type 'single-float))
        (f (/ 1.0 (tan (/ +fovy+ 2.0))))
        (depth (- near far)))
    (setf (aref m 0 0) (/ f aspect))
    (setf (aref m 1 1) f)
    (setf (aref m 2 2) (/ far depth))
    (setf (aref m 2 3) (/ (* near far) depth))
    (setf (aref m 3 2) -1.0)
    m))

;;; --- the orbit camera ---------------------------------------------------------
;;;
;;; The eye circles *centre* at *radius*; drag gestures reach `orbit` (yaw/pitch
;;; deltas) and `zoom` from the input view below. The camera basis is one (3 3)
;;; matrix whose rows are right, up and forward -- and it is the same three rows
;;; that unproject a click and billboard the target ring, so there is exactly
;;; one place the camera's orientation lives.

(defvar *centre* (vec3 0.0 0.42 0.0))

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
  ;; drag deltas, normalized by the view height
  (setq *yaw* (- *yaw* (* 3.4 dx)))
  (let ((p (+ *pitch* (* 2.6 dy))))
    (setq *pitch* (cond ((< p -0.45) -0.45) ((> p 1.45) 1.45) (t p)))))

(defun zoom (dz)
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
         (right (normalized (linalg:cross forward (vec3 0.0 1.0 0.0))))
         (up (linalg:cross right forward))
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
                                    (linalg:from-list '((0.0 0.0 0.0 1.0))
                                     :element-type 'single-float))
                              :axis 0)))
    (setq *eye* eye)
    (setq *basis* (linalg:stack (list right up forward)))
    (setq *vp* (linalg:matmul (perspective *aspect* 0.1 20.0) v))))

;;; --- mesh emitters ------------------------------------------------------------
;;;
;;; The machine is tessellated in Lisp every frame: tapered tubes (cylinders and
;;; arrow cones), discs and spheres, all as world-space triangles with
;;; per-vertex normals, written straight into the packed single-float array that
;;; becomes the vertex buffer's bytes. A ring table drives every circle; the unit
;;; sphere is precomputed once into a rank-2 (vertex xyz) array.

(defconstant +seg+ 10) ; ring segments
(defvar *ctab* nil)    ; cos table, +seg+ entries
(defvar *stab* nil)

(defconstant +sph-stacks+ 4)
(defconstant +sph-slices+ 7)
(defconstant +sph-verts+ 168) ; +sph-stacks+ * +sph-slices+ * 6
(defvar *sph* nil)            ; rank-2 (vertex xyz) unit sphere

;; Vertex capacity, generously over what the machine tessellates to: the array
;; is uploaded whole and only the written prefix is drawn.
(defconstant +max-verts+ 4096)
(defconstant +max-sprites+ 160)

(defvar *vbuf* nil)        ; 9 floats a vertex: position, normal, color
(defvar *sbuf* nil)        ; 5 floats a sprite: position, tone, size
(defvar *w* 0)             ; solid write cursor, in floats
(defvar *s* 0)             ; sprite write cursor, in floats
(defvar *static-floats* 0) ; axes + pedestal, written once into the front

;; The palette, and the colour the emitters are currently painting with: a mesh
;; has one, so it is latched rather than passed.
(defvar *steel* (vec3 0.72 0.76 0.84))
(defvar *dark* (vec3 0.30 0.34 0.44))
(defvar *ember* (vec3 1.00 0.52 0.16))
(defvar *color* (vec3 1.0 1.0 1.0))

(defun set-color (c) (setq *color* c))

(defun emit-v (x y z nx ny nz)
  (let ((w *w*))
    (setf (aref *vbuf* w) x)
    (setf (aref *vbuf* (+ w 1)) y)
    (setf (aref *vbuf* (+ w 2)) z)
    (setf (aref *vbuf* (+ w 3)) nx)
    (setf (aref *vbuf* (+ w 4)) ny)
    (setf (aref *vbuf* (+ w 5)) nz)
    (setf (aref *vbuf* (+ w 6)) (aref *color* 0))
    (setf (aref *vbuf* (+ w 7)) (aref *color* 1))
    (setf (aref *vbuf* (+ w 8)) (aref *color* 2))
    (setq *w* (+ w 9))))

(defun emit-s (p tone size)
  (let ((w *s*))
    (setf (aref *sbuf* w) (aref p 0))
    (setf (aref *sbuf* (+ w 1)) (aref p 1))
    (setf (aref *sbuf* (+ w 2)) (aref p 2))
    (setf (aref *sbuf* (+ w 3)) tone)
    (setf (aref *sbuf* (+ w 4)) size)
    (setq *s* (+ w 5))))

;; Two unit vectors perpendicular to AXIS and to each other, as the rows of a
;; (2 3) matrix: the frame every ring in this file is drawn in. The helper axis
;; is the world one AXIS is least aligned with, so the cross product never
;; degenerates.
(defun perp-basis (axis)
  (let* ((ay (abs (aref axis 1)))
         (helper (if (< ay 0.9) (vec3 0.0 1.0 0.0) (vec3 1.0 0.0 0.0)))
         (u (normalized (linalg:cross axis helper))))
    (linalg:stack (list u (linalg:cross axis u)))))

;; Tube radii travel through globals, like the colour: the browser twin needed
;; that to stay inside the WASM backend's 7-parameter limit, and it reads no
;; worse here.
(defvar *r-a* 0.0) ; tube start / disc radius
(defvar *r-b* 0.0) ; tube end radius (0 = cone)

(defun set-radii (a b)
  (setq *r-a* a)
  (setq *r-b* b))

(defun emit-tube (p0 p1)
  ;; A tube from P0 (radius *r-a*) to P1 (radius *r-b*); *r-b* = 0 makes a cone.
  ;; Normals get the proper slant for the taper.
  (let* ((r0 *r-a*)
         (r1 *r-b*)
         (span (linalg:sub p1 p0))
         (len (linalg:norm span))
         (l (if (< len 0.000001) 0.000001 len))
         (axis (linalg:mul span (/ 1.0 l)))
         (pb (perp-basis axis))
         (k (/ (- r0 r1) l)) ; radius change per unit length
         (nf (/ 1.0 (sqrt (+ 1.0 (* k k)))))
         ;; the ring loop below runs +seg+ * 6 times a tube and writes the
         ;; vertex array directly, so it reads the frame out into scalars once
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
  (setq *ctab* (linalg:zeros +seg+ :element-type 'single-float))
  (setq *stab* (linalg:zeros +seg+ :element-type 'single-float))
  (dotimes (i +seg+)
    (let ((a (/ (* +two-pi+ i) +seg+)))
      (setf (aref *ctab* i) (cos a))
      (setf (aref *stab* i) (sin a))))
  ;; the unit sphere as a triangle list (quads; the pole slivers degenerate)
  (setq *sph* (linalg:zeros (list +sph-verts+ 3) :element-type 'single-float))
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

;;; --- the static scene: XYZ axis arrows + the pedestal -------------------------

(defun emit-arrow (dir color)
  ;; A unit-axis arrow from the origin: shaft, cone base cap, cone tip.
  (set-color color)
  (let* ((shaft (linalg:mul dir 0.52)) (tip (linalg:mul dir 0.67)))
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

;;; --- the arm ------------------------------------------------------------------
;;;
;;; The base joint sits at the origin (where the axis arrows are). The chain is
;;; +links+ links whose lengths taper geometrically and always sum to +reach+.
;;; The gripper rides it as one extra "tool" link: the solver moves the wrist
;;; AND the grasp point (the TCP, the last row of *joints*), so the fingers
;;; close exactly on the clicked goal and the approach direction comes out of
;;; the solver for free.

(defconstant +links+ 4)
(defconstant +reach+ 1.25)
(defconstant +tool+ 0.115) ; wrist -> grasp point (the gripper)
(defconstant +trail+ 72)
(defconstant +tip+ (+ +links+ 1)) ; the row the goal is pinned to

(defvar *len* nil)    ; rank-1: link lengths, the tool last
(defvar *joints* nil) ; rank-2 (+links+ + 2, 3): row 0 the base, +tip+ the TCP

(defun joint (i) (linalg:row *joints* i))

;; Writing a 3-vector back into row I of A -- the inverse of linalg:row, which
;; answers a copy.
(defun set-row (a i p) (dotimes (k 3) (setf (aref a i k) (aref p k))))

(defun set-joint (i p) (set-row *joints* i p))

;; The minimum-jerk trajectory: the commanded hand position *target* travels
;; from *start* to *goal* over *dur* seconds starting at *t0*.
(defvar *start* nil)
(defvar *goal* nil)
(defvar *target* nil)
(defvar *t0* -10.0)
(defvar *dur* 1.0)

;; The hand's recent path, a ring buffer drawn as a fading trail.
(defvar *trail* nil) ; rank-2 (+trail+ 3)
(defvar *trail-head* 0)
(defvar *trail-count* 0)

(defun init ()
  (build-tables)
  (setq *vbuf* (linalg:zeros (* +max-verts+ 9) :element-type 'single-float))
  (setq *sbuf* (linalg:zeros (* +max-sprites+ 5) :element-type 'single-float))
  (setq *len* (linalg:zeros (+ +links+ 1) :element-type 'single-float))
  (setq *joints*
        (linalg:zeros (list (+ +links+ 2) 3) :element-type 'single-float))
  ;; each link is 0.82x the previous, normalized to the fixed total reach;
  ;; the rigid tool link (wrist -> grasp point) rides at the end
  (let ((sum 0.0) (l 1.0))
    (dotimes (i +links+)
      (setf (aref *len* i) l)
      (setq sum (+ sum l))
      (setq l (* l 0.82)))
    (dotimes (i +links+)
      (setf (aref *len* i) (* (aref *len* i) (/ +reach+ sum)))))
  (setf (aref *len* +links+) +tool+)
  ;; initial pose: straight up, grasp point at full extension
  (dotimes (i (+ +links+ 1))
    (setf (aref *joints* (+ i 1) 1) (+ (aref *joints* i 1) (aref *len* i))))
  ;; idle until the first click: the trajectory is already at its goal
  (setq *target* (joint +tip+))
  (setq *start* *target*)
  (setq *goal* *target*)
  (setq *trail* (linalg:zeros (list +trail+ 3) :element-type 'single-float))
  (setq *trail-head* 0)
  (setq *trail-count* 0)
  ;; bake the axes and the pedestal into the front of the vertex array: they
  ;; never move, so every later frame rewrites only what follows them
  (setq *w* 0)
  (emit-static-scene)
  (setq *static-floats* *w*))

;;; --- the minimum-jerk trajectory ----------------------------------------------

(defun min-jerk (u)
  ;; s(u) = 10u^3 - 15u^4 + 6u^5 for u in [0, 1]: the unique 5th-order
  ;; polynomial with zero velocity and zero acceleration at both ends, i.e. the
  ;; trajectory minimizing the integral of squared jerk (Flash & Hogan 1985).
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

(defun set-goal-from-click (ccx ccy tm)
  ;; Unproject the click: a ray from the eye through the clip point, intersected
  ;; with the plane through the orbit centre facing the camera -- so "click
  ;; where you see" works from any viewpoint. Both steps are the camera basis
  ;; and two dot products; nothing here mentions an axis by name.
  (let* ((th (tan +half-fov+))
         (forward (cam-forward))
         (dir
          (linalg:add forward
                      (linalg:add (linalg:mul (cam-right) (* ccx th *aspect*))
                                  (linalg:mul (cam-up) (* ccy th)))))
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

;;; --- inverse kinematics: the Jacobian with damped least squares ---------------
;;;
;;; Wampler 1986, the numerical IK used across robotics. Every joint is a ball
;;; joint contributing three columns a_k x (p_tip - p_i) to the 3 x 3(n+1)
;;; position Jacobian J -- which is the SKEW matrix of p_tip - p_i, three
;;; columns at a time -- and each iteration solves the damped normal equations
;;; dtheta = J^T (J J^T + lambda^2 I)^-1 e, J J^T built with linalg:matmul /
;;; linalg:transpose and the 3x3 system solved exactly by linalg:solve
;;; (Gaussian elimination). The rotations are then applied linearized and the
;;; link lengths re-normalized.

(defconstant +dls-lambda2+ 0.05) ; damping^2: keeps J J^T well-conditioned
(defconstant +dls-max-err+ 0.35) ; clamp per-iteration error (stabilizes)

(defun place (i from len)
  ;; Move joint I to distance LEN from joint FROM, preserving direction: what
  ;; the linearized rotations stretch, this puts back.
  (let* ((d (linalg:sub (joint i) (joint from))) (n (linalg:norm d)))
    (set-joint i
               (linalg:add (joint from)
                (linalg:mul d (/ len (if (< n 0.000001) 0.000001 n)))))))

;; The columns joint I contributes to J: the skew matrix of P, since
;; e_k x p is column k of it.
(defun skew-into (jac column p)
  (setf (aref jac 1 column) (- 0.0 (aref p 2)))
  (setf (aref jac 2 column) (aref p 1))
  (setf (aref jac 0 (+ column 1)) (aref p 2))
  (setf (aref jac 2 (+ column 1)) (- 0.0 (aref p 0)))
  (setf (aref jac 0 (+ column 2)) (- 0.0 (aref p 1)))
  (setf (aref jac 1 (+ column 2)) (aref p 0)))

(defun solve-ik ()
  (let ((m (* 3 +tip+))) ; 3 rotation DOFs a joint, joints 0..+links+
    (dotimes (iter 10)
      (let* ((raw (linalg:sub *target* (joint +tip+)))
             (d (linalg:norm raw))
             ;; clamp the error so early iterations stay in the linear regime
             (e
              (if (> d +dls-max-err+) (linalg:mul raw (/ +dls-max-err+ d)) raw))
             (jac (linalg:zeros (list 3 m) :element-type 'single-float))
             (tip (joint +tip+)))
        (dotimes (i +tip+) (skew-into jac (* 3 i) (linalg:sub tip (joint i))))
        (let ((a (linalg:matmul jac (linalg:transpose jac))))
          (dotimes (k 3) (setf (aref a k k) (+ (aref a k k) +dls-lambda2+)))
          (let ((dth (linalg:matmul (linalg:transpose jac) (linalg:solve a e))))
            ;; apply: joint i's rotation w moves every downstream joint j by
            ;; w x (p_j - p_i), the linearized rotation
            (dotimes (i +tip+)
              (let ((w
                     (vec3 (aref dth (* 3 i)) (aref dth (+ (* 3 i) 1))
                           (aref dth (+ (* 3 i) 2))))
                    (base (joint i)))
                (do ((jj (+ i 1) (+ jj 1)))
                    ((> jj +tip+))
                  (set-joint jj
                             (linalg:add (joint jj)
                                         (linalg:cross w
                                          (linalg:sub (joint jj) base)))))))))
        (dotimes (i +tip+) (place (+ i 1) i (aref *len* i)))))))

;;; --- rendering the machine ----------------------------------------------------

(defun link-radius (i)
  ;; the arm tapers from shoulder to wrist
  (* 0.052 (- 1.0 (* 0.55 (/ (* 1.0 i) +links+)))))

(defun push-trail ()
  (set-row *trail* *trail-head* (joint +tip+))
  (setq *trail-head* (mod (+ *trail-head* 1) +trail+))
  (when (< *trail-count* +trail+) (setq *trail-count* (+ *trail-count* 1))))

;;; The gripper: three two-phalanx fingers, 120 degrees apart around the tool
;;; axis. The grip state *grip* eases between 0 (open, while the hand is flying)
;;; and 1 (closed): the finger segments pivot from splayed-out angles to angles
;;; that make the tips meet exactly at the grasp point, +tool+ ahead of the
;;; wrist -- the very point the solver pins to the goal.

(defvar *grip* 1.0) ; 0 open .. 1 closed
(defvar *last-tm* 0.0)

(defun update-grip (tm)
  ;; open while the min-jerk flight is in progress, close on arrival; eased with
  ;; a time-based rate so the motion is frame-rate independent
  (let* ((dt0 (- tm *last-tm*))
         (dt (cond ((< dt0 0.0) 0.0) ((> dt0 0.05) 0.05) (t dt0)))
         (target (if (>= (/ (- tm *t0*) *dur*) 1.0) 1.0 0.0))
         (k (* dt (if (> target *grip*) 10.0 7.0)))
         (kk (if (> k 1.0) 1.0 k)))
    (setq *grip* (+ *grip* (* kk (- target *grip*))))
    (setq *last-tm* tm)))

(defun emit-gripper ()
  (let* ((wrist (joint +links+))
         ;; approach axis: the solved tool link, wrist -> grasp point
         (axis (linalg:mul (linalg:sub (joint +tip+) wrist) (/ 1.0 +tool+)))
         (pb (perp-basis axis))
         (palm (linalg:add wrist (linalg:mul axis 0.035)))
         ;; finger phalanx angles from the axis: splayed when open, curled so
         ;; the tips meet on the axis at +tool+ when closed
         (a1 (- 0.70 (* 0.58 *grip*)))
         (a2 (- 0.35 (* 1.15 *grip*))))
    ;; the wrist ball and the palm
    (set-color *dark*)
    (emit-sphere wrist (* 1.35 (link-radius +links+)))
    (set-radii 0.030 0.036)
    (emit-tube wrist palm)
    ;; three fingers, 120 degrees apart in the perpendicular frame
    (dotimes (f 3)
      (let* ((a (/ (* +two-pi+ f) 3.0))
             (radial
              (linalg:add (linalg:mul (linalg:row pb 0) (cos a))
                          (linalg:mul (linalg:row pb 1) (sin a))))
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
  (dotimes (i +links+)
    (set-radii (link-radius i) (link-radius (+ i 1)))
    (emit-tube (joint i) (joint (+ i 1))))
  (set-color *dark*)
  (dotimes (i +links+) (emit-sphere (joint i) (* 1.45 (link-radius i))))
  (emit-gripper))

;; A sprite's size is in DRAWABLE pixels, so it carries the backing-store scale
;; the way the browser twin carries devicePixelRatio.
(defconstant +scale+ 2)

(defun emit-glow (tm)
  ;; the trail: the hand's recent path, fading with age
  (dotimes (m *trail-count*)
    (let* ((idx (mod (+ (- *trail-head* *trail-count*) m +trail+) +trail+))
           (age (/ (* 1.0 (+ m 1)) *trail-count*)) ; 0 oldest .. 1 newest
           (fade (* age age)))
      (emit-s (linalg:row *trail* idx) (+ 0.50 (* 0.20 fade))
              (* +scale+ (+ 5.0 (* 12.0 fade))))))
  ;; the goal: a slowly turning, pulsing ring billboarded on the camera basis
  (let ((right (cam-right))
        (up (cam-up))
        (r (+ 0.045 (* 0.010 (sin (* tm 5.0))))))
    (dotimes (m 24)
      (let ((a (+ (* tm 0.8) (/ (* +two-pi+ m) 24))))
        (emit-s (linalg:add *goal*
                            (linalg:add (linalg:mul right (* r (cos a)))
                                        (linalg:mul up (* r (sin a))))) 1.0
                (* +scale+ 9.0))))
    (emit-s *goal* 1.0 (* +scale+ 14.0))))

;;; --- the window, and the mouse --------------------------------------------------
;;;
;;; The drawing surface is also the input surface: one NSView subclass defined at
;;; run time, whose mouse methods are the Lisp closures below, installed as the
;;; window's content view before metal:attach puts the CAMetalLayer on it. That
;;; is `objc:define-class`, the verb appkit.lisp uses to make an NSBox answer a
;;; click -- an NSView answers all of these already, so the runtime reads each
;;; method's type encoding off NSView and nothing here declares a signature.
;;;
;;; The gesture is classified exactly as the browser page classifies it: a drag
;;; that has moved more than a few points orbits, and a press that has not,
;;; released, is a click. The camera itself lives in Lisp either way.

(defvar *width* 720)
(defvar *height* 560)

(defvar *dragging* nil)
(defvar *moved* 0.0)
(defvar *down* nil) ; where the press landed, in view points
(defvar *last* nil) ; the previous drag position

;; The latest click, in clip coordinates; picked up by the next frame, which
;; knows the camera and the time.
(defvar *click* nil)

(defvar *view* nil)

(defun view-point (event)
  ;; The event's position inside the drawing view, in points, y up. The content
  ;; view's frame origin is the window coordinate system's origin, so this
  ;; subtraction is the whole conversion.
  (let ((p (objc:send event "locationInWindow")) (f (objc:send *view* "frame")))
    (vec3 (- (first p) (first f)) (- (second p) (second f)) 0.0)))

(defun on-mouse-down (self event)
  (let ((p (view-point event)))
    (setq *dragging* t)
    (setq *moved* 0.0)
    (setq *down* p)
    (setq *last* p))
  nil)

(defun on-mouse-dragged (self event)
  (when *dragging*
    (let* ((p (view-point event))
           (d (linalg:sub p *last*))
           (dx (aref d 0))
           (dy (aref d 1)))
      (setq *last* p)
      (setq *moved* (+ *moved* (abs dx) (abs dy)))
      (when (> *moved* 4.0)
        ;; the view's y grows upward and the browser page's grows downward,
        ;; which is the whole difference between the two orbit calls
        (orbit (/ dx (* 1.0 *height*)) (/ (- 0.0 dy) (* 1.0 *height*))))))
  nil)

(defun on-mouse-up (self event)
  (when (and *dragging* (<= *moved* 4.0))
    ;; view points -> clip coordinates, both axes at once
    (setq *click*
          (linalg:sub
           (linalg:mul (linalg:div *down* (vec3 *width* *height* 1.0)) 2.0)
           1.0)))
  (setq *dragging* nil)
  nil)

(defun on-scroll (self event)
  ;; A trackpad reports pixels and a wheel reports lines, so the gain follows
  ;; hasPreciseScrollingDeltas -- the same distinction a browser draws between
  ;; deltaMode 0 and 1.
  (let ((dy (objc:send event "scrollingDeltaY")))
    (zoom
     (* dy (if (objc:send event "hasPreciseScrollingDeltas") -0.004 -0.20))))
  nil)

;; Answering YES to acceptsFirstMouse: makes the click that ACTIVATES the window
;; count as a click in it too, which is what a drawing surface wants.
(defun on-first-mouse (self event) t)

(defvar *input-class*
  (objc:define-class "RontoLispMetalArmView"
    "NSView"
    (list (list "mouseDown:" #'on-mouse-down)
          (list "mouseDragged:" #'on-mouse-dragged)
          (list "mouseUp:" #'on-mouse-up) (list "scrollWheel:" #'on-scroll)
          (list "acceptsFirstMouse:" #'on-first-mouse))))

(defvar *window*
  (appkit:window "Metal robot arm" :width *width* :height *height* :dark t))

(setq *view*
      (objc:on-main
       (lambda ()
         (let ((v
                (objc:send (objc:send *input-class* "alloc") "initWithFrame:"
                           (list 0.0 0.0 (* 1.0 *width*) (* 1.0 *height*)))))
           (objc:send *window* "setContentView:" v)
           v))))

(defvar *metal*
  (metal:attach *window*
                :clear '(0.012 0.016 0.045 1.0)
                :scale +scale+
                :depth t))
(defvar *library* (metal:library *metal* *shaders*))
(defvar *solid*
  (metal:pipeline *metal* *library* "solid_vertex" "solid_fragment"))
(defvar *glow*
  (metal:pipeline *metal* *library* "sprite_vertex" "sprite_fragment" :blend t))
(defvar *depth-write* (metal:depth-state *metal*))
(defvar *depth-read* (metal:depth-state *metal* :writes nil))

;;; Buffers in flight. The frame writes next frame's vertices while the GPU may
;;; still be reading this frame's, so each buffer is rotated through +slots+
;;; copies before it comes round again -- the standard Metal answer, and the one
;;; thing WebGL hid from the browser twin (bufferSubData renames behind your
;;; back).

(defconstant +slots+ 3)
(defvar *mesh-buffers* nil)
(defvar *sprite-buffers* nil)
(defvar *slot* 0)

(defun make-buffers (bytes)
  (let ((out '()))
    (dotimes (i +slots+ (nreverse out))
      (push (metal:shared-buffer *metal* bytes) out))))

;;; --- the frame ------------------------------------------------------------------

(defun now () (/ (get-internal-real-time) 1000.0))

(defun draw (encoder tm)
  (update-camera)
  (setq *slot* (mod (+ *slot* 1) +slots+))
  (let ((vp (linalg:transpose *vp*))
        ;; the fragment stage wants a float4, so the eye rides with one pad
        (eye
         (linalg:concatenate
          (list *eye* (linalg:zeros 1 :element-type 'single-float))))
        (mesh (nth *slot* *mesh-buffers*))
        (sprites (nth *slot* *sprite-buffers*)))
    ;; solid pass: the machine, lit and depth-tested. The static scene is
    ;; already in the front of the array; only what follows it is rewritten.
    (setq *w* *static-floats*)
    (emit-arm)
    (let ((verts (floor *w* 9)))
      (metal:upload mesh *vbuf*)
      (objc:send encoder "setRenderPipelineState:" *solid*)
      (objc:send encoder "setDepthStencilState:" *depth-write*)
      (objc:send encoder "setVertexBuffer:offset:atIndex:" mesh 0 0)
      (metal:uniform encoder 1 vp)
      (metal:uniform encoder 0 eye :stage :fragment)
      (objc:send encoder "drawPrimitives:vertexStart:vertexCount:"
                 metal:+triangle+ 0 verts))
    ;; glow pass: additive sprites that read depth but do not write it
    (setq *s* 0)
    (emit-glow tm)
    (let ((count (floor *s* 5)))
      (metal:upload sprites *sbuf*)
      (objc:send encoder "setRenderPipelineState:" *glow*)
      (objc:send encoder "setDepthStencilState:" *depth-read*)
      (objc:send encoder "setVertexBuffer:offset:atIndex:" sprites 0 0)
      (metal:uniform encoder 1 vp)
      (objc:send encoder "drawPrimitives:vertexStart:vertexCount:" metal:+point+
                 0 count))))

(defun frame (encoder)
  (let ((tm (now)))
    ;; a pending click is unprojected with this frame's camera and time
    (when *click*
      (set-goal-from-click (aref *click* 0) (aref *click* 1) tm)
      (setq *click* nil))
    (update-target tm)
    (update-grip tm)
    (solve-ik)
    (push-trail)
    (draw encoder tm)))

;;; --- go ---------------------------------------------------------------------

(init)
(setq *aspect* (/ (* 1.0 *width*) *height*))
(setq *t0* (- (now) 10.0))
(setq *last-tm* (now))
(setq *mesh-buffers* (make-buffers (* +max-verts+ 36)))
(setq *sprite-buffers* (make-buffers (* +max-sprites+ 20)))

(format t "device: ~a~%"
        (objc:send (objc:send (metal:device *metal*) "name") "UTF8String"))
(format t "click to reach, drag to orbit, scroll to zoom~%")

(metal:run *metal* #'frame)

(appkit:wait *window*)
