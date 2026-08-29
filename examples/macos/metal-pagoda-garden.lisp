;;;; metal-pagoda-garden.lisp -- a voxel garden, a five-storey pagoda and a
;;;; cherry-blossom spring, on the GPU of a Mac.
;;;;
;;;; The brief this answers was written for a browser: "a very creative,
;;;; elaborate, detailed voxel art scene of a pagoda in a beautiful garden with
;;;; trees, including some cherry blossoms -- impressive, varied, colourful --
;;;; use whatever libraries, one HTML file I can paste into Chrome". There is no
;;;; page here and no library to pull in: `objc:send` IS the graphics API
;;;; (the built-in `metal` package), a packed single-float array IS the buffer's
;;;; bytes, and the
;;;; scene itself -- every voxel of it -- is built by the Lisp below.
;;;;
;;;; What the GPU is asked to draw is ONE cube, 36 vertices, and nothing else.
;;;; The vertex function divides `vertex_id` by 36 to find which voxel it is
;;;; drawing and takes the remainder for the corner, so a scene of 13,000 voxels
;;;; is one `drawPrimitives:vertexStart:vertexCount:` over a buffer of 13,000
;;;; records -- no instancing selector, no vertex descriptor, no index buffer.
;;;; That is the whole trick, and it is what lets a tree be 400 voxels without
;;;; being 400 draw calls.
;;;;
;;;; The garden is an island floating over a sea of cloud: an organic shore
;;;; (three sine terms), grass
;;;; and moss, a raked gravel karesansui with its rocks, a flagstone path from a
;;;; vermilion torii, a koi pond under an arched taiko-bashi, stone lanterns,
;;;; azaleas, bamboo, pines, maples and five cherry trees always in bloom. The
;;;; pagoda is five tiers of hollow walls -- corner posts, plaster panels, lit
;;;; lattice windows -- under stepped indigo roofs whose corners curve up the way
;;;; a real one's do, with a gold sorin and a wind bell at every eave.
;;;;
;;;; Four things move: the water (a wave the surface voxels ride), the koi (a
;;;; body that swims by wiggling toward its tail), the petals (they spiral down
;;;; on a breeze and are reborn in a canopy), and the sun. CLICK to turn night
;;;; on: the sky darkens into stars, the sun sets through its own dusk, the
;;;; lantern flames and the pagoda's windows come up, and the fireflies arrive.
;;;;
;;;; Three passes, three pipelines: a full-screen sky (a gradient symmetric about
;;;; the horizon, the sun, drifting cloud above and the sea of cloud below,
;;;; stars and a moon -- no buffer at all, the triangle is generated from
;;;; `vertex_id`), the lit voxels, and additive glow sprites.
;;;;
;;;; Nothing here draws a random number from the machine: `rnd` below is a
;;;; 32-bit LCG seeded by a constant, so the garden that grows is the SAME
;;;; garden on every run and on every backend.
;;;;
;;;; Run it (macOS, on any of the three backends that carry the binding):
;;;;
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/macos/metal-pagoda-garden.lisp
;;;;   rontolisp examples/macos/metal-pagoda-garden.lisp
;;;;   rontolisp examples/macos/metal-pagoda-garden.lisp -o Garden.class --class-name Garden && java Garden

;;; --- the shaders --------------------------------------------------------------
;;;
;;; One library, six functions, three pipelines. None of them declares a vertex
;;; descriptor: every one indexes a `const device` array by vertex_id, so the
;;; layout is the Lisp array's layout and nothing writes it down twice.
;;;
;;; `Scene` is the one uniform the sky and the voxels share -- where the eye is,
;;; where the sun is, what colour the haze is, and the camera's own three axes,
;;; which is what lets the sky shader rebuild a view ray per pixel without an
;;; inverse matrix. packed_float3 is 12 bytes, which is what makes the Voxel
;;; stride come out at 32 and the Sprite's at 20.

(defvar *shaders*
  "
#include <metal_stdlib>
using namespace metal;

struct Scene {
  float4 eye;    // xyz the eye, w the night 0 (noon) .. 1 (midnight)
  float4 sun;    // xyz toward the sun, w the key light's strength
  float4 fog;    // rgb the haze, w the time in seconds
  float4 right;  // camera right,   w = tan(fov/2) * aspect
  float4 up;     // camera up,      w = tan(fov/2)
  float4 fwd;    // camera forward
};

static float hash21(float2 p) {
  float3 q = fract(float3(p.x, p.y, p.x) * 0.1031);
  q += dot(q, q.yzx + 33.33);
  return fract((q.x + q.y) * q.z);
}

static float hash31(float3 p) {
  p = fract(p * 0.1031);
  p += dot(p, p.zyx + 31.32);
  return fract((p.x + p.y) * p.z);
}

static float vnoise(float2 p) {
  float2 i = floor(p);
  float2 f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  float a = hash21(i);
  float b = hash21(i + float2(1.0, 0.0));
  float c = hash21(i + float2(0.0, 1.0));
  float d = hash21(i + float2(1.0, 1.0));
  return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// ---- the sky: one oversized triangle, generated, with no buffer at all ------

struct SkyOut {
  float4 position [[position]];
  float2 ndc;
};

vertex SkyOut sky_vertex(uint id [[vertex_id]]) {
  float2 p = float2(float((id << 1) & 2), float(id & 2));  // (0,0) (2,0) (0,2)
  SkyOut out;
  out.ndc = p * 2.0 - 1.0;                                 // covers [-1,1]^2
  out.position = float4(out.ndc, 1.0, 1.0);                // at the far plane
  return out;
}

fragment float4 sky_fragment(SkyOut in [[stage_in]], constant Scene &s [[buffer(0)]]) {
  float night = s.eye.w;
  float dusk = 1.0 - abs(night * 2.0 - 1.0);   // peaks halfway through the turn
  float3 dir = normalize(s.fwd.xyz + s.right.xyz * (in.ndc.x * s.right.w)
                                   + s.up.xyz * (in.ndc.y * s.up.w));
  // The garden FLOATS, and the camera looks down at it from above -- so most of
  // what is behind it is the sky BELOW the horizon, and the gradient has to be
  // symmetric about it or two thirds of the window is one flat colour.
  float ay = abs(dir.y);
  float3 lo = mix(float3(0.78, 0.88, 0.96), float3(0.07, 0.09, 0.22), night)
              + float3(0.42, 0.15, -0.07) * dusk;
  float3 hi = mix(float3(0.17, 0.44, 0.86), float3(0.01, 0.02, 0.07), night);
  float t = clamp(ay * 1.5 + 0.12, 0.0, 1.0);
  float3 col = mix(lo, hi, t * t);

  float3 sd = normalize(s.sun.xyz);
  float sa = max(dot(dir, sd), 0.0);
  col += float3(1.00, 0.93, 0.74) * pow(sa, 2200.0) * (1.0 - night) * 3.0;
  col += float3(1.00, 0.64, 0.32) * pow(sa, 9.0) * (0.18 + 0.62 * dusk);

  // clouds, on both sides of the horizon: thin drifting ones above, and the sea
  // of cloud the island floats over below
  float2 cp = dir.xz / max(ay, 0.02) * (dir.y > 0.0 ? 2.4 : 1.1)
              + float2(s.fog.w * 0.006, s.fog.w * 0.003);
  float n = vnoise(cp) * 0.56 + vnoise(cp * 2.17 + 5.3) * 0.29 + vnoise(cp * 4.3) * 0.15;
  float3 cc = mix(float3(1.00, 0.99, 0.97), float3(0.13, 0.16, 0.31), night)
              + float3(0.28, 0.08, -0.06) * dusk;
  if (dir.y > 0.0) {
    col = mix(col, cc * (0.80 + 0.30 * n),
              smoothstep(0.50, 0.80, n) * smoothstep(0.015, 0.22, ay) * 0.80);
  }
  else {
    // the sea of cloud the island floats over: it closes in as the eye looks
    // further down, and its sunlit tops are a second, sharper band of the noise
    col = mix(col, cc * (0.66 + 0.46 * n), smoothstep(0.02, 0.36, ay) * (0.40 + 0.50 * n));
    col += cc * 0.22 * smoothstep(0.60, 0.86, n) * smoothstep(0.05, 0.45, ay);
  }

  // stars and a moon, in the half of the sky that has them
  if (night > 0.02 && dir.y > 0.0) {
    float3 q = dir * 110.0;
    float r = hash31(floor(q));
    if (r > 0.9905) {
      float d = length(fract(q) - 0.5) * 2.0;
      float tw = 0.5 + 0.5 * sin(s.fog.w * 2.3 + r * 500.0);
      col += float3(0.92, 0.95, 1.00) * smoothstep(1.0, 0.0, d) * (0.35 + 0.65 * tw)
             * night * smoothstep(0.0, 0.10, dir.y);
    }
    float3 md = normalize(float3(-sd.x, 0.34, -sd.z));   // the moon rides opposite
    float ma = max(dot(dir, md), 0.0);
    col += night * (float3(0.98, 0.97, 0.90) * smoothstep(0.99955, 0.99975, ma)
                      * (0.86 + 0.14 * vnoise((dir.xz - md.xz) * 900.0)) * 1.35
                    + float3(0.50, 0.58, 0.85) * pow(ma, 300.0) * 0.55);
  }
  return float4(col, 1.0);
}

// ---- the voxels -------------------------------------------------------------
//
// 36 corners and 6 face normals live in the shader as constants; the buffer
// carries one 32-byte record per voxel and nothing else. id / 36 is the voxel,
// id % 36 is the corner, (id % 36) / 6 is the face -- so the normal is exact
// (no dfdx guessing) and the classic voxel-art per-face key comes for free.

struct Voxel {
  packed_float3 pos;
  packed_float3 color;
  float size;
  float emit;
};

constant float3 CORNER[36] = {
  float3(-0.5,-0.5, 0.5), float3( 0.5,-0.5, 0.5), float3( 0.5, 0.5, 0.5),
  float3(-0.5,-0.5, 0.5), float3( 0.5, 0.5, 0.5), float3(-0.5, 0.5, 0.5),
  float3( 0.5,-0.5,-0.5), float3(-0.5,-0.5,-0.5), float3(-0.5, 0.5,-0.5),
  float3( 0.5,-0.5,-0.5), float3(-0.5, 0.5,-0.5), float3( 0.5, 0.5,-0.5),
  float3( 0.5,-0.5, 0.5), float3( 0.5,-0.5,-0.5), float3( 0.5, 0.5,-0.5),
  float3( 0.5,-0.5, 0.5), float3( 0.5, 0.5,-0.5), float3( 0.5, 0.5, 0.5),
  float3(-0.5,-0.5,-0.5), float3(-0.5,-0.5, 0.5), float3(-0.5, 0.5, 0.5),
  float3(-0.5,-0.5,-0.5), float3(-0.5, 0.5, 0.5), float3(-0.5, 0.5,-0.5),
  float3(-0.5, 0.5, 0.5), float3( 0.5, 0.5, 0.5), float3( 0.5, 0.5,-0.5),
  float3(-0.5, 0.5, 0.5), float3( 0.5, 0.5,-0.5), float3(-0.5, 0.5,-0.5),
  float3(-0.5,-0.5,-0.5), float3( 0.5,-0.5,-0.5), float3( 0.5,-0.5, 0.5),
  float3(-0.5,-0.5,-0.5), float3( 0.5,-0.5, 0.5), float3(-0.5,-0.5, 0.5)
};

constant float3 FACE_N[6] = {
  float3(0.0, 0.0, 1.0), float3(0.0, 0.0,-1.0), float3(1.0, 0.0, 0.0),
  float3(-1.0, 0.0, 0.0), float3(0.0, 1.0, 0.0), float3(0.0,-1.0, 0.0)
};

// the light a face keeps before any lamp reaches it: sky above, dirt below
constant float FACE_K[6] = { 0.80, 0.68, 0.87, 0.73, 1.00, 0.46 };

struct VoxOut {
  float4 position [[position]];
  float3 color;
  float3 world;
  float3 normal;
  float shade;
  float emit;
};

vertex VoxOut vox_vertex(uint id [[vertex_id]],
                         const device Voxel *voxels [[buffer(0)]],
                         constant float4x4 &vp [[buffer(1)]]) {
  uint ci = id % 36u;
  Voxel v = voxels[id / 36u];
  float3 p = float3(v.pos) + CORNER[ci] * v.size;
  VoxOut out;
  out.position = vp * float4(p, 1.0);
  out.color = float3(v.color);
  out.world = p;
  out.normal = FACE_N[ci / 6u];
  out.shade = FACE_K[ci / 6u];
  out.emit = v.emit;
  return out;
}

fragment float4 vox_fragment(VoxOut in [[stage_in]], constant Scene &s [[buffer(0)]]) {
  float night = s.eye.w;
  float dusk = 1.0 - abs(night * 2.0 - 1.0);
  float3 n = in.normal;
  float3 sd = normalize(s.sun.xyz);
  float3 e = normalize(s.eye.xyz - in.world);
  float key = max(dot(n, sd), 0.0) * s.sun.w;
  float3 sunc = mix(float3(1.00, 0.95, 0.84), float3(1.00, 0.52, 0.26), dusk);
  float3 skyc = mix(float3(0.44, 0.54, 0.70), float3(0.15, 0.20, 0.42), night);
  float3 gndc = mix(float3(0.27, 0.24, 0.18), float3(0.06, 0.07, 0.15), night);
  float3 amb = mix(gndc, skyc, n.y * 0.5 + 0.5);
  float spec = pow(max(dot(n, normalize(sd + e)), 0.0), 26.0) * 0.20 * s.sun.w;
  float3 col = in.color * in.shade * (amb + sunc * key) + sunc * spec;
  col += in.color * in.emit * (0.22 + 1.60 * night);     // lamps, windows, gold
  col += mix(float3(0.36, 0.56, 0.90), float3(0.24, 0.34, 0.78), night)
         * pow(1.0 - max(dot(n, e), 0.0), 3.0) * 0.10;   // a cool rim
  float d = length(s.eye.xyz - in.world);
  return float4(mix(col, s.fog.rgb, clamp((d - 70.0) / 260.0, 0.0, 0.72)), 1.0);
}

// ---- the glow: additive point sprites ---------------------------------------

struct Sprite {
  packed_float3 position;
  float tone;   // 0 water glint .. 1 gold
  float size;   // in drawable pixels at w = 1
};

struct SpriteOut {
  float4 position [[position]];
  float size [[point_size]];
  float tone;
};

vertex SpriteOut glow_vertex(uint id [[vertex_id]],
                             const device Sprite *sprites [[buffer(0)]],
                             constant float4x4 &vp [[buffer(1)]]) {
  SpriteOut out;
  float4 p = vp * float4(sprites[id].position, 1.0);
  out.position = p;
  out.size = clamp(sprites[id].size / max(p.w, 0.1), 1.0, 280.0);
  out.tone = sprites[id].tone;
  return out;
}

static float3 glow_tint(float h) {
  float3 water = float3(0.55, 0.85, 1.00);
  float3 petal = float3(1.00, 0.60, 0.80);
  float3 lamp  = float3(1.00, 0.70, 0.28);
  float3 gold  = float3(1.00, 0.95, 0.78);
  return h < 0.34 ? mix(water, petal, h / 0.34)
       : h < 0.68 ? mix(petal, lamp, (h - 0.34) / 0.34)
       :            mix(lamp, gold, (h - 0.68) / 0.32);
}

fragment float4 glow_fragment(SpriteOut in [[stage_in]], float2 uv [[point_coord]]) {
  float d = length(uv - 0.5) * 2.0;
  float a = exp(-3.2 * d * d) * (1.0 - smoothstep(0.75, 1.0, d));
  return float4(glow_tint(in.tone) * a, a);
}
")

(defconstant +pi+ 3.141592653589793)
(defconstant +two-pi+ 6.283185307179586)
(defconstant +fovy+ 0.7853981633974483) ; 45 degrees
(defconstant +tan-half+ 0.41421356)     ; tan(fovy / 2), the sky ray's scale

;;; --- a garden that grows the same way every time ------------------------------
;;;
;;; Every choice the scene builder makes -- which pink a blossom is, where a
;;; bamboo stalk stands, how bright a patch of moss -- is a draw from this
;;; 32-bit linear congruential generator, seeded by a constant. `random` would
;;; have done as well for a single run, but a garden that is different every
;;; time cannot be TUNED: nothing below could be judged by looking at it. So the
;;; whole scene is a pure function of one integer, and changing that integer
;;; grows a different garden.

(defvar *seed* 20260827)

(defun rnd ()
  (setq *seed* (logand (+ (* *seed* 1103515245) 12345) #xffffffff))
  (/ (* 1.0 (ash *seed* -8)) 16777216.0))

(defun chance (p) (< (rnd) p))

(defun rnd-between (a b) (+ a (* (- b a) (rnd))))

(defun rint (n) (floor (* n (rnd))))

(defun rr (v) (floor (+ v 0.5)))

;;; --- one sine table, read by everything that moves ----------------------------
;;;
;;; The terrain is 4,000 cells of trigonometry and a frame is a few thousand
;;; more, so both read a 1024-entry table rather than calling the libm one. The
;;; camera does not: a look-at built from table samples wobbles visibly.

(defconstant +tab+ 1024)
(defconstant +tab-scale+ 162.97466) ; +tab+ / 2 pi

(defvar *sin-tab* nil)

(defun sn (a) (aref *sin-tab* (mod (floor (* a +tab-scale+)) +tab+)))

(defun cs (a) (sn (+ a 1.5707964)))

;;; --- points are arrays, and arithmetic on them is linalg ----------------------
;;;
;;; The camera half of this file is metal-robot-arm.lisp's, unchanged in
;;; substance: a point, a direction and a camera axis are packed single-float
;;; 3-vectors, the basis is one (3 3) matrix, and the look-at is the matrix
;;; expression it looks like. The SCENE half is the opposite and deliberately so
;;; -- it writes eight floats per voxel some thirteen thousand times, and a
;;; fresh 3-vector per voxel would be an allocation per float written.

(defun vec3 (x y z)
  (let ((v (make-array 3 :element-type 'single-float)))
    (setf (aref v 0) x)
    (setf (aref v 1) y)
    (setf (aref v 2) z)
    v))

(defun normalized (v)
  (let ((n (linalg:norm v)))
    (linalg:mul v (/ 1.0 (if (< n 0.000001) 0.000001 n)))))

;; Metal's clip space puts z in [0, 1], not OpenGL's [-1, 1].
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

(defvar *centre* (vec3 -1.0 16.0 1.0))
(defvar *yaw* -0.38)
(defvar *pitch* 0.30)
(defvar *radius* 102.0)
(defvar *aspect* 1.0)
(defvar *eye* nil)
(defvar *basis* nil) ; rank-2 (3 3): rows right, up, forward
(defvar *vp* nil)

(defun cam-right () (linalg:row *basis* 0))

(defun cam-up () (linalg:row *basis* 1))

(defun cam-forward () (linalg:row *basis* 2))

(defun orbit (dx dy)
  (setq *yaw* (- *yaw* (* 3.4 dx)))
  (let ((p (+ *pitch* (* 2.6 dy))))
    (setq *pitch* (cond ((< p -0.10) -0.10) ((> p 1.30) 1.30) (t p)))))

(defun zoom (dz)
  (let ((r (+ *radius* dz)))
    (setq *radius* (cond ((< r 34.0) 34.0) ((> r 230.0) 230.0) (t r)))))

(defun update-camera ()
  (let* ((cp (cos *pitch*))
         (sp (sin *pitch*))
         (eye
          (linalg:add *centre*
                      (vec3 (* *radius* cp (sin *yaw*)) (* *radius* sp)
                            (* *radius* cp (cos *yaw*)))))
         (forward (normalized (linalg:sub *centre* eye)))
         (right (normalized (linalg:cross forward (vec3 0.0 1.0 0.0))))
         (up (linalg:cross right forward))
         (r (linalg:stack (list right up (linalg:mul forward -1.0))))
         ;; V = [ R | -R.eye ] over (0 0 0 1)
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
    (setq *vp* (linalg:matmul (perspective *aspect* 1.0 460.0) v))))

;;; --- the voxel writer ---------------------------------------------------------
;;;
;;; Eight floats a voxel: centre, colour, edge length, glow. Two arrays are
;;; written through it -- the STATIC scene, built once at startup and uploaded
;;; once, and the DYNAMIC one, rewritten every frame -- so the cursor and the
;;; array it writes into are globals the builders switch between, exactly as a
;;; mesh emitter latches its colour rather than taking one per call.

(defconstant +vox-floats+ 8)

(defvar *buf* nil) ; the array being written
(defvar *w* 0)     ; the write cursor, in floats
(defvar *cap* 0)   ; where that array ends, in floats

(defvar *cr* 1.0)
(defvar *cg* 1.0)
(defvar *cb* 1.0)
(defvar *ce* 0.0)   ; the latched glow
(defvar *jit* 0.06) ; per-voxel brightness scatter, which is what stops a wall
; of one colour from reading as a flat rectangle

(defun target (buf cap)
  (setq *buf* buf)
  (setq *cap* cap)
  (setq *w* 0))

;; Painting CLEARS the glow: a lamp is the exception, and an exception that has
;; to be turned off again is one that leaks into the next thousand voxels.
(defun paint (r g b)
  (setq *cr* r)
  (setq *cg* g)
  (setq *cb* b)
  (setq *ce* 0.0))

(defun paint-lit (r g b e)
  (paint r g b)
  (setq *ce* e))

(defun vxs (x y z size)
  (let ((w *w*))
    (when (<= w (- *cap* +vox-floats+))
      (let ((k (+ (- 1.0 *jit*) (* 2.0 *jit* (rnd)))))
        (setf (aref *buf* w) (* 1.0 x))
        (setf (aref *buf* (+ w 1)) (* 1.0 y))
        (setf (aref *buf* (+ w 2)) (* 1.0 z))
        (setf (aref *buf* (+ w 3)) (* k *cr*))
        (setf (aref *buf* (+ w 4)) (* k *cg*))
        (setf (aref *buf* (+ w 5)) (* k *cb*))
        (setf (aref *buf* (+ w 6)) size)
        (setf (aref *buf* (+ w 7)) *ce*)
        (setq *w* (+ w +vox-floats+))))))

(defun vx (x y z) (vxs x y z 1.0))

(defun voxels () (floor *w* +vox-floats+))

;;; --- the glow writer ----------------------------------------------------------

(defconstant +max-sprites+ 460)
(defconstant +scale+ 2) ; the backing-store factor, as in metal:attach

(defvar *sbuf* nil)
(defvar *sw* 0)

;; PIX is the sprite's diameter in points at the distance the garden sits at
;; (about 100 voxels), so a halo stays a halo however far the camera orbits out
;; and still shrinks with perspective when it is across the island.
(defun sp (x y z tone pix)
  (let ((w *sw*))
    (when (<= w (- (* +max-sprites+ 5) 5))
      (setf (aref *sbuf* w) (* 1.0 x))
      (setf (aref *sbuf* (+ w 1)) (* 1.0 y))
      (setf (aref *sbuf* (+ w 2)) (* 1.0 z))
      (setf (aref *sbuf* (+ w 3)) tone)
      (setf (aref *sbuf* (+ w 4)) (* pix 100.0 +scale+))
      (setq *sw* (+ w 5)))))

;;; --- the palettes -------------------------------------------------------------
;;;
;;; A canopy is not one green or one pink: it is four or five tones drawn with
;;; weights that shift with height, so the underside of a cherry cluster is the
;;; deep pink and its crown is nearly white. UP is 1 for a voxel in the upper
;;; half of its cluster and 0 below, and it is the only argument the shape has to
;;; hand the palette.

(defconstant +pal-sakura+ 0)
(defconstant +pal-maple+ 1)
(defconstant +pal-pine+ 2)
(defconstant +pal-bamboo+ 3)
(defconstant +pal-azalea+ 4)

(defun leaf-paint (pal up)
  (let ((k (rnd)))
    (cond ((= pal +pal-sakura+)
           (cond ((< k (- 0.34 (* 0.20 up))) (paint 0.88 0.42 0.60))
                 ((< k 0.64) (paint 0.98 0.62 0.76))
                 ((< k 0.88) (paint 1.00 0.78 0.86))
                 (t (paint 1.00 0.94 0.96))))
          ((= pal +pal-maple+)
           (cond ((< k (- 0.32 (* 0.18 up))) (paint 0.66 0.13 0.14))
                 ((< k 0.62) (paint 0.90 0.28 0.16))
                 ((< k 0.86) (paint 0.97 0.50 0.14))
                 (t (paint 1.00 0.72 0.22))))
          ((= pal +pal-pine+)
           (cond ((< k (- 0.38 (* 0.20 up))) (paint 0.07 0.25 0.15))
                 ((< k 0.74) (paint 0.13 0.36 0.21))
                 (t (paint 0.18 0.47 0.25))))
          ((= pal +pal-bamboo+)
           (if (< k 0.5) (paint 0.28 0.54 0.24) (paint 0.36 0.64 0.28)))
          ;; an azalea is a green bush wearing a cap of flowers
          ((< (rnd) (* 0.8 up))
           (cond ((< k 0.34) (paint 0.92 0.26 0.52))
                 ((< k 0.64) (paint 1.00 0.56 0.74))
                 ((< k 0.86) (paint 0.99 0.90 0.94))
                 (t (paint 0.78 0.30 0.72))))
          ((< k 0.5) (paint 0.16 0.40 0.20))
          (t (paint 0.22 0.48 0.24)))))

;;; --- the island ---------------------------------------------------------------
;;;
;;; The garden is a 65 x 65 cell grid of columns, and where it ENDS is a radius
;;; that wanders: three sine terms of the cell's own coordinates, which is
;;; enough to make a shore that never repeats and never needs a coastline to be
;;; stored. Every cell is classified once into the zone table below, because the
;;; pond's bank, the path's edge and the terrace rim all have to ask about their
;;; neighbours and asking means reading a byte rather than redoing the trigonometry.

(defconstant +half+ 32)
(defconstant +span+ 65)

(defconstant +z-none+ 0) ; off the island
(defconstant +z-grass+ 1)
(defconstant +z-path+ 2)
(defconstant +z-gravel+ 3) ; the raked karesansui
(defconstant +z-stone+ 4)  ; the pagoda's terrace
(defconstant +z-water+ 5)
(defconstant +z-shore+ 6) ; the pebble rim around the pond

(defconstant +pond-x+ -17.0)
(defconstant +pond-z+ 8.0)
(defconstant +terrace+ 8) ; the terrace's half extent
(defconstant +terrace-top+ 2)

(defvar *zone* nil)

(defun shore-limit (x z)
  (+ 29.0 (* 2.6 (sn (* x 0.21))) (* 2.2 (cs (* z 0.235)))
     (* 1.7 (sn (* (+ x z) 0.135)))))

;; Under 1 is open water; the sine term keeps the rim off a drawn ellipse.
(defun pond-d (x z)
  (let ((dx (/ (- x +pond-x+) 7.5)) (dz (/ (- z +pond-z+) 8.5)))
    (+ (* dx dx) (* dz dz) (* 0.10 (sn (+ (* x 0.55) (* z 0.42)))))))

;; The path does not run straight at the pagoda; it drifts, which is what makes
;; a garden path a garden path.
(defun path-x (z) (* 2.0 (sn (* z 0.16))))

(defun classify (x z)
  (let* ((fx (* 1.0 x)) (fz (* 1.0 z)) (d (sqrt (+ (* fx fx) (* fz fz)))))
    (cond ((> d (shore-limit fx fz)) +z-none+)
          ((and (<= (abs x) +terrace+) (<= (abs z) +terrace+)) +z-stone+)
          ((< (pond-d fx fz) 1.0) +z-water+)
          ((< (pond-d fx fz) 1.4) +z-shore+)
          ((and (>= z 9) (<= z 28) (< (abs (- fx (path-x fz))) 2.6)) +z-path+)
          ;; the way west, over the bridge and on into the trees
          ((and (>= x -27) (<= x -5) (<= (abs (- fz 8.0)) 1.6)) +z-path+)
          ((and (>= x 8) (<= x 25) (>= z -7) (<= z 15)) +z-gravel+)
          (t +z-grass+))))

(defun build-zones ()
  (setq *zone*
        (make-array (list +span+ +span+) :element-type '(unsigned-byte 8)))
  (dotimes (ix +span+)
    (dotimes (iz +span+)
      (setf (aref *zone* ix iz) (classify (- ix +half+) (- iz +half+))))))

(defun zone-at (x z)
  (if (or (< x (- +half+)) (> x +half+) (< z (- +half+)) (> z +half+))
      +z-none+
      (aref *zone* (+ x +half+) (+ z +half+))))

(defun bank-p (x z)
  (or (/= (zone-at (+ x 1) z) +z-water+) (/= (zone-at (- x 1) z) +z-water+)
      (/= (zone-at x (+ z 1)) +z-water+) (/= (zone-at x (- z 1)) +z-water+)))

;;; --- what each kind of ground is made of --------------------------------------

(defun grass-paint (x z)
  ;; two low-frequency waves give the lawn its patches; six per cent is moss
  (let ((m
         (+ 0.5 (* 0.25 (sn (+ (* x 0.17) (* z 0.11))))
            (* 0.25 (cs (- (* x 0.09) (* z 0.21)))))))
    (if (chance 0.06)
        (paint (+ 0.19 (* 0.06 m)) (+ 0.39 (* 0.11 m)) (+ 0.20 (* 0.04 m)))
        (paint (+ 0.24 (* 0.16 m)) (+ 0.47 (* 0.19 m)) (+ 0.19 (* 0.07 m))))))

(defun path-paint (x z)
  ;; 2 x 2 flagstones, two greys, from the parity of the halved coordinates
  (if (= 0 (mod (+ (ash (+ x 64) -1) (ash (+ z 64) -1)) 2))
      (paint 0.61 0.59 0.56)
      (paint 0.51 0.50 0.49)))

(defun gravel-paint (x z)
  ;; raked rings around the great rock: sin of the distance, period ~3 cells
  (let* ((dx (- (* 1.0 x) 17.0))
         (dz (- (* 1.0 z) 4.0))
         (d (sqrt (+ (* dx dx) (* dz dz)))))
    (if (> (sn (* d 2.0)) 0.0) (paint 0.82 0.77 0.64) (paint 0.69 0.64 0.52))))

(defun flower (x z)
  (let ((k (rint 4)))
    (cond ((= k 0) (paint 0.98 0.92 0.40))
          ((= k 1) (paint 0.96 0.62 0.80))
          ((= k 2) (paint 0.95 0.96 0.98))
          (t (paint 0.72 0.52 0.92))))
  (vxs x 0.75 z 0.5))

;; The island's underside, only where it can be SEEN: a column of soil and rock
;; under every cell within three of the shore, deepening inward until the rim
;; hides it. The middle of the island is hollow, and nothing looking at it can
;; tell.
(defun rim-column (x z)
  (let* ((fx (* 1.0 x))
         (fz (* 1.0 z))
         (m (- (shore-limit fx fz) (sqrt (+ (* fx fx) (* fz fz))))))
    (when (< m 3.0)
      (let ((depth (+ 1 (floor (* 2.6 m)))))
        (setq *jit* 0.11)
        (do ((y 1 (+ y 1)))
            ((> y depth))
          (cond ((= y 1) (paint 0.36 0.26 0.17))
                ((< y 4) (paint 0.42 0.30 0.20))
                (t (paint 0.35 0.33 0.32)))
          (vx x (- y) z))))))

(defun land-cell (x z k)
  (setq *jit* 0.07)
  (cond ((= k +z-stone+) (paint 0.62 0.60 0.57))
        ((= k +z-path+) (path-paint x z))
        ((= k +z-gravel+) (gravel-paint x z))
        ((= k +z-shore+) (paint 0.62 0.59 0.53))
        (t (grass-paint x z)))
  (if (= k +z-stone+)
      (progn
        (vx x +terrace-top+ z)
        ;; only the terrace's rim needs the two courses beneath its top
        (when (or (= (abs x) +terrace+) (= (abs z) +terrace+))
          (paint 0.56 0.54 0.51)
          (vx x 1 z)
          (paint 0.48 0.46 0.44)
          (vx x 0 z)))
      (vx x 0 z))
  (rim-column x z)
  (when (and (= k +z-grass+) (chance 0.017)) (flower x z)))

(defun pond-cell (x z)
  (setq *jit* 0.10)
  (paint 0.33 0.29 0.21)
  (vx x -2 z)
  (when (bank-p x z)
    (paint 0.40 0.36 0.27)
    (vx x -1 z)))

;;; The pond's cells are collected as they are classified: the water SURFACE is
;;; rewritten every frame (it rides a wave), so it is the one part of the ground
;;; that is not in the static buffer, and the frame needs the list rather than
;;; the grid.
(defconstant +max-pond+ 300)

(defvar *pond* nil)
(defvar *pond-n* 0)

(defun build-ground ()
  (setq *pond* (linalg:zeros (list +max-pond+ 2) :element-type 'single-float))
  (setq *pond-n* 0)
  (dotimes (ix +span+)
    (dotimes (iz +span+)
      (let ((x (- ix +half+)) (z (- iz +half+)) (k (aref *zone* ix iz)))
        (cond ((= k +z-none+) nil)
              ((= k +z-water+)
               (pond-cell x z)
               (when (< *pond-n* +max-pond+)
                 (setf (aref *pond* *pond-n* 0) (* 1.0 x))
                 (setf (aref *pond* *pond-n* 1) (* 1.0 z))
                 (setq *pond-n* (+ *pond-n* 1))))
              (t (land-cell x z k)))))))

;;; --- the pagoda ---------------------------------------------------------------
;;;
;;; Five tiers, each a HOLLOW body -- only the cells on the wall's perimeter
;;; exist -- under a roof of four stacked rings. Nothing inside is ever built,
;;; which is why a tower 54 voxels tall costs about 2,000 of them.
;;;
;;; The roofs are what make it a pagoda rather than a stack of boxes. Each ring
;;; is one cell narrower than the one below, and each ring cell is LIFTED by
;;; 2.6 t^3, where t is how far along the eave the cell sits (0 at the middle of
;;; a face, 1 at a corner). The cube is the exponent's whole point: the eave
;;; stays flat for most of its run and then sweeps up in the last few cells, the
;;; way a real hip roof's corner does.

(defvar *tier-body* '(7 6 5 4 3))
(defvar *tier-roof* '(10 9 8 7 6))
(defvar *tier-high* '(5 4 4 4 3))

(defun wall-paint (i b sx sz k h)
  (let* ((ax (abs sx))
         (az (abs sz))
         (edge (if (= ax b) az ax)) ; how far along its own face this cell is
         (mid (and (<= edge 1) (>= k 2) (<= k (- h 2)))))
    (cond ((and (= ax b) (= az b)) (paint 0.52 0.13 0.10))    ; corner post
          ((or (= k 0) (= k (- h 1))) (paint 0.28 0.17 0.13)) ; beam
          ;; the ground tier's south door, before anything else claims the cell
          ((and (= i 0) (= sz b) (<= ax 1) (< k 4)) (paint 0.10 0.07 0.06))
          ;; a lattice window at the middle of every face, lit from inside
          (mid (if (= 0 (mod (+ edge k) 2))
                   (paint-lit 1.00 0.82 0.42 1.0)
                   (paint 0.34 0.20 0.14)))
          ((= 0 (mod edge 3)) (paint 0.76 0.19 0.13)) ; pillar
          (t (paint 0.90 0.87 0.80)))))               ; plaster

(defun tier-body (i b h y0)
  (setq *jit* 0.05)
  (do ((sx (- b) (+ sx 1)))
      ((> sx b))
    (do ((sz (- b) (+ sz 1)))
        ((> sz b))
      (when (or (= (abs sx) b) (= (abs sz) b))
        (dotimes (k h)
          (wall-paint i b sx sz k h)
          (vx sx (+ y0 k) sz))))))

;; A wind bell under every corner of every eave: twenty of them, and each one is
;; also a glow sprite once the sun is down.
(defun eave-bells (r y0)
  (dolist (c (list (list r r) (list r (- r)) (list (- r) r) (list (- r) (- r))))
    (paint 0.88 0.70 0.30)
    (vxs (first c) (+ y0 2) (second c) 0.5)
    (paint-lit 1.00 0.86 0.44 0.8)
    (vxs (first c) (+ y0 1) (second c) 0.42)))

(defun tier-roof (r y0)
  (setq *jit* 0.06)
  (dotimes (k 4)
    (let ((m (- r k)))
      (do ((sx (- m) (+ sx 1)))
          ((> sx m))
        (do ((sz (- m) (+ sz 1)))
            ((> sz m))
          (when (or (= (abs sx) m) (= (abs sz) m))
            (let* ((tt (/ (* 1.0 (min (abs sx) (abs sz))) m))
                   (up (rr (* 2.6 tt tt tt))))
              (cond ((= k 0) (paint 0.86 0.66 0.28))         ; copper eave
                    ((= 0 (mod k 2)) (paint 0.19 0.23 0.42)) ; tile courses
                    (t (paint 0.24 0.29 0.50)))
              (vx sx (+ y0 k up) sz)))))))
  (eave-bells r y0))

;; The sorin: the mast, five rings, the water-flame and the jewel, all of it
;; gold and the top of it lit, so the spire reads against a night sky.
(defun sorin (y0)
  (setq *jit* 0.04)
  (paint 0.84 0.66 0.26)
  (dotimes (k 11) (vxs 0 (+ y0 k) 0 (if (> k 8) 0.6 0.85)))
  (dotimes (k 5)
    (let ((y (+ y0 1 (* k 2))) (m (max 1 (- 2 (rr (* 0.3 k))))))
      (paint 0.92 0.76 0.34)
      (do ((sx (- m) (+ sx 1)))
          ((> sx m))
        (do ((sz (- m) (+ sz 1)))
            ((> sz m))
          (when (= m (max (abs sx) (abs sz))) (vxs sx y sz 0.7))))))
  (paint-lit 1.00 0.88 0.46 0.9)
  (dolist (d '((1 0) (-1 0) (0 1) (0 -1)))
    (vxs (first d) (+ y0 9) (second d) 0.6))
  (paint-lit 1.00 0.95 0.62 1.0)
  (vxs 0 (+ y0 11) 0 1.1))

(defvar *sorin-y* 0)

(defun build-pagoda ()
  (let ((y (+ +terrace-top+ 1)))
    (dotimes (i 5)
      (let ((b (nth i *tier-body*))
            (r (nth i *tier-roof*))
            (h (nth i *tier-high*)))
        (tier-body i b h y)
        ;; where each face's lattice window came out, for the glow pass; the
        ;; top tier is too short to carry one
        (when (< i 4)
          (let ((wy (+ y 2.5)))
            (push (list b wy 0) *windows*)
            (push (list (- b) wy 0) *windows*)
            (push (list 0 wy b) *windows*)
            (push (list 0 wy (- b)) *windows*)))
        (setq y (+ y h))
        (tier-roof r y)
        (setq y (+ y 4))))
    (setq *sorin-y* y)
    (sorin y))
  ;; one step down off the terrace, on the side the path arrives from
  (setq *jit* 0.05)
  (paint 0.60 0.58 0.55)
  (do ((sx -3 (+ sx 1)))
      ((> sx 3))
    (vx sx 1 (+ +terrace+ 1))))

;;; --- the torii, the bridge, the lanterns, the rocks ---------------------------

(defun torii ()
  (setq *jit* 0.05)
  (paint 0.80 0.18 0.14)
  (dolist (px '(-6 -5 5 6))
    (dotimes (k 9)
      (do ((z 21 (+ z 1)))
          ((> z 22))
        (vx px k z))))
  ;; the nuki, the tie beam the plaque hangs from
  (do ((x -7 (+ x 1)))
      ((> x 7))
    (do ((z 21 (+ z 1)))
        ((> z 22))
      (vx x 6 z)))
  ;; the shimaki and the kasagi above it, the ends of both sweeping up
  (paint 0.72 0.16 0.12)
  (do ((x -8 (+ x 1)))
      ((> x 8))
    (do ((z 21 (+ z 1)))
        ((> z 22))
      (vx x (+ 9 (if (> (abs x) 6) 1 0)) z)))
  (paint 0.15 0.13 0.15)
  (do ((x -9 (+ x 1)))
      ((> x 9))
    (do ((z 21 (+ z 1)))
        ((> z 22))
      (vx x (+ 10 (if (> (abs x) 6) 1 0)) z)))
  ;; the gakuzuka: a gold plaque between the beams
  (paint-lit 0.88 0.72 0.32 0.55)
  (do ((x -1 (+ x 1)))
      ((> x 1))
    (do ((k 7 (+ k 1)))
        ((> k 8))
      (vx x k 21))))

(defun bridge ()
  (setq *jit* 0.06)
  (do ((x -25 (+ x 1)))
      ((> x -8))
    (let* ((u (/ (* 1.0 (+ x 25)) 17.0)) (y (rr (* 2.8 (sn (* +pi+ u))))))
      (paint 0.78 0.24 0.16)
      (do ((z 7 (+ z 1)))
          ((> z 9))
        (vx x y z))
      ;; a pier down to the bed wherever the deck is clear of the water
      (when (and (> y 1) (= 0 (mod (+ x 25) 5)))
        (paint 0.34 0.21 0.15)
        (do ((k -1 (+ k 1)))
            ((>= k y))
          (vx x k 8)))
      ;; the handrail: a post every third plank, a rail along the top
      (paint 0.62 0.16 0.12)
      (vxs x (+ y 2) 7 0.7)
      (vxs x (+ y 2) 9 0.7)
      (when (= 0 (mod (+ x 25) 3))
        (vxs x (+ y 1) 7 0.6)
        (vxs x (+ y 1) 9 0.6)))))

(defvar *lanterns* '((5 12) (-7 18) (-4 -13) (14 4)))

(defun lantern (x z)
  (setq *jit* 0.06)
  (paint 0.54 0.54 0.50)
  (do ((dx -1 (+ dx 1)))
      ((> dx 1))
    (do ((dz -1 (+ dz 1)))
        ((> dz 1))
      (vx (+ x dx) 0 (+ z dz))))
  (paint 0.50 0.50 0.47)
  (dotimes (k 3) (vxs x (+ 1 k) z 0.8))
  (paint 0.58 0.58 0.54)
  (do ((dx -1 (+ dx 1)))
      ((> dx 1))
    (do ((dz -1 (+ dz 1)))
        ((> dz 1))
      (vxs (+ x dx) 4 (+ z dz) 0.95)))
  ;; the fire box, hollow: the flame that fills it is redrawn every frame
  (paint 0.60 0.60 0.56)
  (dotimes (k 2)
    (do ((dx -1 (+ dx 1)))
        ((> dx 1))
      (do ((dz -1 (+ dz 1)))
          ((> dz 1))
        (when (or (/= dx 0) (/= dz 0)) (vx (+ x dx) (+ 5 k) (+ z dz))))))
  (paint 0.46 0.47 0.45)
  (do ((dx -1 (+ dx 1)))
      ((> dx 1))
    (do ((dz -1 (+ dz 1)))
        ((> dz 1))
      (vx (+ x dx) (+ 7 (if (and (/= dx 0) (/= dz 0)) 1 0)) (+ z dz))))
  (paint 0.70 0.62 0.36)
  (vxs x 8 z 0.7))

(defun rock (x0 y0 z0 r)
  (let ((ir (rr r)))
    (setq *jit* 0.12)
    (do ((dx (- ir) (+ dx 1)))
        ((> dx ir))
      (do ((dy 0 (+ dy 1)))
          ((> dy ir))
        (do ((dz (- ir) (+ dz 1)))
            ((> dz ir))
          (let ((d (sqrt (+ (* 1.0 dx dx) (* 2.0 dy dy) (* 1.0 dz dz)))))
            (when (< d r)
              (cond ((and (> d (- r 1.1)) (chance 0.28)) (paint 0.27 0.44 0.25))
                    ((chance 0.3) (paint 0.39 0.42 0.45))
                    (t (paint 0.50 0.51 0.52)))
              (vx (+ x0 dx) (+ y0 dy) (+ z0 dz)))))))))

;;; --- things that grow ---------------------------------------------------------
;;;
;;; A canopy is a handful of overlapping SHELLS, not a solid ball: a voxel is
;;; emitted where it is within a cluster's radius and either near its surface or
;;; one of the fourteen per cent kept from inside, and a further tenth is dropped
;;; outright so the silhouette breaks up. That is what makes a cherry read as
;;; blossom rather than as a sphere -- and it is also what keeps five trees to
;;; two thousand voxels rather than eight.

(defun cluster (cx cy cz r pal)
  (let ((ir (rr r)))
    (do ((dx (- ir) (+ dx 1)))
        ((> dx ir))
      (do ((dy (- ir) (+ dy 1)))
          ((> dy ir))
        (do ((dz (- ir) (+ dz 1)))
            ((> dz ir))
          (let ((d (sqrt (+ (* 1.0 dx dx) (* 1.15 dy dy) (* 1.0 dz dz)))))
            (when (and (< d r) (or (> d (- r 1.4)) (chance 0.14)) (chance 0.88))
              (leaf-paint pal (if (> dy 0) 1.0 0.0))
              (vx (+ cx dx) (+ cy dy) (+ cz dz)))))))))

(defun branch (x0 y0 z0 pal)
  (let* ((a (* +two-pi+ (rnd)))
         (dx (cs a))
         (dz (sn a))
         (n (rr (rnd-between 2.0 3.8))))
    (setq *jit* 0.10)
    (paint 0.31 0.21 0.15)
    (dotimes (i (+ n 1))
      (vxs (+ x0 (rr (* dx i))) (+ y0 (rr (* 0.55 i))) (+ z0 (rr (* dz i)))
           0.85))
    (setq *jit* 0.08)
    (cluster (+ x0 (rr (* dx n))) (+ y0 (rr (* 0.55 n)) 1) (+ z0 (rr (* dz n)))
             (rnd-between 2.0 3.0) pal)))

(defun tree (x0 z0 h pal)
  (let ((lx (rnd-between -0.10 0.10)) (lz (rnd-between -0.10 0.10)))
    (setq *jit* 0.10)
    (paint 0.33 0.22 0.16)
    (dotimes (y h)
      (let ((tx (+ x0 (rr (* lx y)))) (tz (+ z0 (rr (* lz y)))))
        (vx tx y tz)
        (when (< y 2) ; roots flaring at the foot
          (vxs (+ tx 1) y tz 0.9)
          (vxs tx y (+ tz 1) 0.9))))
    (let ((tx (+ x0 (rr (* lx h)))) (tz (+ z0 (rr (* lz h)))))
      (setq *jit* 0.08)
      (cluster tx (+ h 1) tz (rnd-between 2.8 3.6) pal)
      (dotimes (b (+ 3 (rint 3))) (branch tx (- h 1 (rint 4)) tz pal)))))

;; A pine is the other shape entirely: layered boughs on a bare trunk, each
;; layer a disc that is mostly its own rim.
(defun pine-bough (x0 y z0 r)
  (let ((ir (rr r)))
    (do ((dx (- ir) (+ dx 1)))
        ((> dx ir))
      (do ((dz (- ir) (+ dz 1)))
          ((> dz ir))
        (let ((d (sqrt (+ (* 1.0 dx dx) (* 1.0 dz dz)))))
          (when (and (<= d r) (or (> d (- r 1.6)) (chance 0.28)))
            (leaf-paint +pal-pine+ 0.0)
            (vx (+ x0 dx) y (+ z0 dz))))))))

(defun pine (x0 z0 h)
  (setq *jit* 0.10)
  (paint 0.30 0.21 0.15)
  (dotimes (y (+ h 1)) (vx x0 y z0))
  (let* ((base (rr (* 0.30 h))) (layers (- (+ h 4) base)))
    (setq *jit* 0.09)
    (dotimes (k layers)
      (let ((r (* (- 1.0 (/ (* 1.0 k) layers)) (+ 2.6 (* 0.17 h)))))
        (when (> r 0.7) (pine-bough x0 (+ base k) z0 r))))))

(defun bamboo-grove (x0 z0 n)
  (dotimes (i n)
    (let ((x (+ x0 (- (rint 13) 6)))
          (z (+ z0 (- (rint 13) 6)))
          (h (+ 7 (rint 6))))
      (setq *jit* 0.07)
      (unless (on-island-p x z) (setq h 0))
      (dotimes (y h)
        (if (= 0 (mod y 4))
            (paint 0.52 0.60 0.30) ; the node
            (paint 0.41 0.66 0.30))
        (vxs x y z 0.8))
      (setq *jit* 0.10)
      (dotimes (k 9)
        (leaf-paint +pal-bamboo+ 1.0)
        (vxs (+ x (- (rint 3) 1)) (- h (rint 5)) (+ z (- (rint 3) 1)) 0.8)))))

(defun bush (x0 z0 r pal)
  (let ((ir (rr r)))
    (setq *jit* 0.09)
    (do ((dx (- ir) (+ dx 1)))
        ((> dx ir))
      (do ((dy 0 (+ dy 1)))
          ((> dy ir))
        (do ((dz (- ir) (+ dz 1)))
            ((> dz ir))
          (let ((d (sqrt (+ (* 1.0 dx dx) (* 1.6 dy dy) (* 1.0 dz dz)))))
            (when (and (<= d r) (chance 0.9))
              (leaf-paint pal (if (> dy (* 0.35 r)) 1.0 0.0))
              (vx (+ x0 dx) dy (+ z0 dz)))))))))

;; A fence of split bamboo either side of the approach: posts, two rails, and
;; the shore for a boundary -- the classifier already knows where the island
;; ends, so the fence asks it rather than working the radius out again.
(defun on-island-p (x z) (/= (zone-at x z) +z-none+))

(defun fence (z)
  (setq *jit* 0.08)
  (do ((x -21 (+ x 1)))
      ((> x 21))
    (when (and (on-island-p x z) (> (abs (- (* 1.0 x) (path-x (* 1.0 z)))) 3.2))
      (paint 0.44 0.62 0.30)
      (vxs x 1 z 0.55)
      (vxs x 2 z 0.55)
      (when (= 0 (mod (+ x 21) 4))
        (paint 0.36 0.26 0.17)
        (vxs x 0 z 0.8)
        (vxs x 1 z 0.8)
        (vxs x 2 z 0.8)
        (vxs x 3 z 0.8)))))

;; A mound of moss with a stone half-buried in it -- the thing a garden puts
;; where nothing else needs to be.
(defun mound (x0 z0 r)
  (let ((ir (rr r)))
    (setq *jit* 0.10)
    (do ((dx (- ir) (+ dx 1)))
        ((> dx ir))
      (do ((dz (- ir) (+ dz 1)))
          ((> dz ir))
        (let ((d (sqrt (+ (* 1.0 dx dx) (* 1.0 dz dz)))))
          (when (and (< d r) (on-island-p (+ x0 dx) (+ z0 dz)))
            (if (chance 0.35) (paint 0.20 0.44 0.24) (paint 0.25 0.50 0.27))
            (dotimes (k (rr (- r d))) (vx (+ x0 dx) k (+ z0 dz)))))))))

;;; The five cherries are the one placement the rest of the program reads back:
;;; a petal is reborn in the canopy of one of them.
(defvar *cherries* '((-6 19) (9 18) (-19 -6) (13 -16) (-3 -20)))

(defun build-plants ()
  (let ((h '(13 12 14 12 11)) (i 0))
    (dolist (c *cherries*)
      (tree (first c) (second c) (nth i h) +pal-sakura+)
      (setq i (+ i 1))))
  (tree -12 -16 12 +pal-maple+)
  (tree 18 -10 11 +pal-maple+)
  (tree -13 20 11 +pal-maple+)
  (pine 2 -19 12)
  (pine -8 -19 13)
  (pine -22 -6 11)
  (bamboo-grove -11 -11 18)
  (bamboo-grove 24 -3 9)
  (dolist (b
           '((-5 14) (-5 25) (4 26) (-6 -12) (7 -12) (-14 -4) (-9 21) (-24 1)
             (-25 -8) (3 -13) (-16 -20) (13 -20) (11 22) (-12 24) (14 18)
             (-6 24) (22 2) (-19 -12)))
    (bush (first b) (second b) (rnd-between 1.8 3.0) +pal-azalea+))
  (dolist (r
           '((17 0 -2 4.4) (21 0 6 2.8) (12 0 2 2.4) (-22 0 -13 3.0)
             (8 0 -16 2.2) (-6 0 -22 2.6) (9 0 -22 2.4) (-24 0 -3 2.2)))
    (rock (first r) (second r) (third r) (nth 3 r)))
  (mound -18 -8 4.5)
  (mound 15 -21 3.5)
  (fence 26)
  ;; stepping stones, standing just clear of the water
  (setq *jit* 0.06)
  (dolist (s '((-12 10) (-13 12) (-14 15) (-12 3)))
    (paint 0.52 0.52 0.50)
    (do ((dx 0 (+ dx 1)))
        ((> dx 1))
      (do ((dz 0 (+ dz 1)))
          ((> dz 1))
        (vxs (+ (first s) dx) 0 (+ (second s) dz) 0.95))))
  (dolist (l *lanterns*) (lantern (first l) (second l)))
  (torii)
  (bridge))

;;; --- the hours ----------------------------------------------------------------
;;;
;;; One number runs the whole sky. *night* eases from 0 to 1 and back when the
;;; window is clicked, and everything that has to know the hour reads it: the
;;; sun sinks through its own sunset rather than switching off, the haze the
;;; distance fades into follows the horizon it is standing in front of, and the
;;; lamps -- which are the same `emit` byte all day -- come up because the
;;; fragment shader weights it by the dark.

(defvar *epoch* (/ (get-internal-real-time) 1000.0))

;; Seconds since the program started. NOT since the epoch: the shader animates
;; on this number in float32, where 1.7e9 has no fractional part left at all.
(defun now () (- (/ (get-internal-real-time) 1000.0) *epoch*))

(defvar *night* 0.0)
(defvar *night-target* 0.0)
(defvar *last-tm* 0.0)

(defun sun-dir ()
  (normalized (vec3 (+ 0.42 (* 0.12 *night*)) (- 0.92 (* 1.20 *night*)) 0.34)))

(defun sun-strength () (max 0.04 (- 1.0 (* 1.15 *night*))))

(defun fog-color ()
  (let ((n *night*) (dusk (- 1.0 (abs (- (* 2.0 *night*) 1.0)))))
    (vec3 (+ (* (- 1.0 n) 0.72) (* n 0.06) (* dusk 0.26))
          (+ (* (- 1.0 n) 0.82) (* n 0.08) (* dusk 0.05))
          (+ (* (- 1.0 n) 0.92) (* n 0.19) (* dusk -0.05)))))

;;; --- the water, the lilies, the koi -------------------------------------------

(defun wave (x z tm)
  (+ (* 0.5 (sn (+ (* x 0.7) (* tm 1.5))))
     (* 0.5 (cs (+ (* z 0.55) (* tm 1.1))))))

(defun water-paint (w)
  (let ((k (+ 0.5 (* 0.5 w))) (n *night*) (m (- 1.0 *night*)))
    (paint-lit (+ (* m (+ 0.07 (* 0.17 k))) (* n (+ 0.03 (* 0.13 k))))
               (+ (* m (+ 0.30 (* 0.32 k))) (* n (+ 0.08 (* 0.18 k))))
               (+ (* m (+ 0.44 (* 0.26 k))) (* n (+ 0.20 (* 0.32 k))))
               (* 0.10 n))))

(defun emit-water (tm)
  (setq *jit* 0.04)
  (dotimes (i *pond-n*)
    (let* ((x (aref *pond* i 0)) (z (aref *pond* i 1)) (w (wave x z tm)))
      (water-paint w)
      (vx x (* 0.17 w) z))))

(defvar *lilies* '((-19 3) (-21 11) (-14 12) (-19 14) (-13 4)))

(defun emit-lilies (tm)
  (setq *jit* 0.07)
  (dolist (l *lilies*)
    (let* ((x (first l)) (z (second l)) (y (+ 0.45 (* 0.17 (wave x z tm)))))
      (paint 0.20 0.50 0.26)
      (vxs x y z 1.0)
      (vxs (+ x 1) y z 0.9)
      (vxs (- x 1) y z 0.9)
      (vxs x y (+ z 1) 0.9)
      (vxs x y (- z 1) 0.9)
      (paint 0.98 0.62 0.78)
      (vxs (+ x 1) (+ y 0.7) (+ z 1) 0.6))))

;;; A koi is seven voxels laid along its heading, each one swung further off the
;;; line than the last and a beat behind it: that travelling sine IS the swim,
;;; and it is also what the tail fin rides on.
(defvar *koi*
  '((-17.0 8.0 4.5 4.5 0.50 0.0 0) (-17.0 8.0 6.0 3.0 -0.40 2.1 1)
    (-17.0 8.0 3.0 5.5 0.72 4.0 2) (-17.0 8.0 5.5 2.5 0.34 1.1 3)))

(defun koi-paint (kind j)
  (cond ((= kind 0)
         (if (or (= j 1) (= j 3))
             (paint 0.94 0.34 0.12)
             (paint 0.97 0.95 0.92)))
        ((= kind 1) (if (= j 2) (paint 0.14 0.13 0.16) (paint 0.96 0.94 0.90)))
        ((= kind 2) (paint 0.95 0.72 0.20))
        (t (if (< j 3) (paint 0.90 0.22 0.20) (paint 0.98 0.60 0.30)))))

(defun emit-koi (tm)
  (setq *jit* 0.05)
  (dolist (k *koi*)
    (let* ((rx (nth 2 k))
           (rz (nth 3 k))
           (ph (nth 5 k))
           (a (+ ph (* tm (nth 4 k))))
           (cx (+ (nth 0 k) (* rx (cs a))))
           (cz (+ (nth 1 k) (* rz (sn a))))
           (hx (* (- 0.0 rx) (sn a)))
           (hz (* rz (cs a)))
           (hl (sqrt (+ (* hx hx) (* hz hz))))
           (ux (/ hx hl))
           (uz (/ hz hl))
           (y (+ -0.7 (* 0.14 (sn (+ (* tm 1.7) ph))))))
      (dotimes (j 7)
        (let* ((along (- 3.0 j))
               (wig
                (* 0.7 (/ (* 1.0 j) 6.0) (sn (- (+ (* tm 6.0) ph) (* j 0.7)))))
               (px (+ cx (* ux along) (* (- 0.0 uz) wig)))
               (pz (+ cz (* uz along) (* ux wig))))
          (koi-paint (nth 6 k) j)
          (vxs px y pz (cond ((= j 0) 0.7) ((> j 4) 0.6) (t 0.95)))
          ;; the tail fans out where the wiggle is widest
          (when (= j 6)
            (vxs (+ px (* (- 0.0 uz) 0.7)) y (+ pz (* ux 0.7)) 0.5)
            (vxs (- px (* (- 0.0 uz) 0.7)) y (- pz (* ux 0.7)) 0.5)))))))

;;; --- the petals ---------------------------------------------------------------

(defconstant +petals+ 170)

(defvar *petal* nil) ; rank-2 (petal, [x y z phase tone])

(defun spawn-petal (i)
  (let ((c (nth (rint 5) *cherries*)))
    (setf (aref *petal* i 0) (+ (* 1.0 (first c)) (rnd-between -6.0 6.0)))
    (setf (aref *petal* i 1) (rnd-between 11.0 24.0))
    (setf (aref *petal* i 2) (+ (* 1.0 (second c)) (rnd-between -6.0 6.0)))
    (setf (aref *petal* i 3) (* +two-pi+ (rnd)))
    (setf (aref *petal* i 4) (rnd))))

(defun update-petals (dt)
  (dotimes (i +petals+)
    (let ((y (- (aref *petal* i 1) (* dt (+ 1.0 (* 1.5 (aref *petal* i 4)))))))
      (setf (aref *petal* i 1) y)
      (setf (aref *petal* i 0) (+ (aref *petal* i 0) (* dt 1.1))) ; the breeze
      (when (< y 0.4) (spawn-petal i)))))

(defun emit-petals (tm)
  (setq *jit* 0.05)
  (dotimes (i +petals+)
    (let* ((ph (aref *petal* i 3))
           (tone (aref *petal* i 4))
           (x (+ (aref *petal* i 0) (* 1.7 (sn (+ (* tm 1.3) ph)))))
           (z (+ (aref *petal* i 2) (* 1.4 (cs (+ (* tm 1.05) (* ph 1.7)))))))
      (cond ((< tone 0.40) (paint 1.00 0.74 0.84))
            ((< tone 0.80) (paint 0.98 0.60 0.76))
            (t (paint 1.00 0.92 0.94)))
      (vxs x (aref *petal* i 1) z 0.45))))

;;; --- the flames ---------------------------------------------------------------

(defun emit-flames (tm)
  (setq *jit* 0.06)
  (dolist (l *lanterns*)
    (let* ((x (first l))
           (z (second l))
           (f (+ 0.75 (* 0.25 (sn (+ (* tm 7.3) (* 0.7 x) z)))))
           (e (* f (+ 0.5 (* 1.3 *night*)))))
      (paint-lit 1.00 0.80 0.34 e)
      (vxs x 5 z (* f 0.9))
      (paint-lit 1.00 0.92 0.60 e)
      (vxs x 6 z (* f 0.6)))))

;;; --- the glow -----------------------------------------------------------------
;;;
;;; Additive sprites over the finished scene, reading the depth the voxel pass
;;; wrote and writing none of their own: a firefly behind a tree is hidden, and
;;; two fireflies in front of one do not occlude each other.

;; A point sprite carries ONE depth for every pixel of itself -- the centre's --
;; so a halo emitted where its own lamp is loses the depth test to the stone in
;; front of it and never appears at all. OUT nudges it that far toward the eye,
;; which is the whole fix and the reason the glow pass knows where the eye is.
(defun sp-near (x y z tone pix out)
  (let* ((dx (- (aref *eye* 0) x))
         (dy (- (aref *eye* 1) y))
         (dz (- (aref *eye* 2) z))
         (n (/ out (sqrt (+ (* dx dx) (* dy dy) (* dz dz))))))
    (sp (+ x (* dx n)) (+ y (* dy n)) (+ z (* dz n)) tone pix)))

(defconstant +flies+ 46)

(defvar *fly-homes*
  '((-8.0 14.0) (7.0 15.0) (-14.0 -6.0) (10.0 -12.0) (-20.0 8.0) (16.0 4.0)))

(defvar *windows* '()) ; where the pagoda's lit lattice faces out

(defun emit-fireflies (tm)
  (when (> *night* 0.05)
    (dotimes (i +flies+)
      (let* ((home (nth (mod i 6) *fly-homes*))
             (a (+ (* tm (+ 0.28 (* 0.05 (mod i 5)))) (* i 1.37)))
             (b (+ (* tm (+ 0.19 (* 0.04 (mod i 7)))) (* i 2.11)))
             (pulse (+ 0.30 (* 0.70 (max 0.0 (sn (+ (* tm 3.1) (* i 0.9))))))))
        (sp (+ (first home) (* 6.5 (sn a)) (* 2.0 (cs b)))
            (+ 2.2 (* 1.8 (sn (+ b 1.0))) (* 0.6 (mod i 3)))
            (+ (second home) (* 6.0 (cs (* 0.9 a))) (* 2.0 (sn b))) 0.58
            (* *night* pulse 5.5))))))

(defun emit-glow (tm)
  (let ((day (- 1.0 *night*)))
    ;; the lantern flames, haloed
    (dolist (l *lanterns*)
      (let ((x (* 1.0 (first l))) (z (* 1.0 (second l))))
        (sp-near x 5.6 z 0.62 (+ 9.0 (* 24.0 *night*)) 2.6)
        (dotimes (m 6)
          (let ((a (+ (* tm 0.6) (/ (* +two-pi+ m) 6))))
            (sp-near (+ x (* 1.6 (cs a))) (+ 5.6 (* 1.1 (sn (+ a (* tm 2.0)))))
                     (+ z (* 1.6 (sn a))) 0.52 (+ 2.0 (* 5.0 *night*)) 1.4)))))
    ;; the lit lattice of every tier, seen from outside
    (dolist (w *windows*)
      (sp-near (first w) (second w) (third w) 0.68 (+ 1.6 (* 9.0 *night*)) 1.3))
    ;; the sorin's jewel, turning
    (dotimes (m 7)
      (let ((a (+ (* tm 0.9) (/ (* +two-pi+ m) 7))))
        (sp (* 2.2 (cs a)) (+ *sorin-y* 10.5 (* 0.7 (sn (* 2.0 a))))
            (* 2.2 (sn a)) 0.92 (+ 3.5 (* 4.5 *night*)))))
    (sp-near 0 (+ *sorin-y* 11) 0 1.0 (+ 8.0 (* 8.0 *night*)) 1.4)
    ;; sun on the water: only the crests, and only by day
    (when (> day 0.15)
      (do ((i 0 (+ i 3)))
          ((>= i *pond-n*))
        (let ((x (aref *pond* i 0)) (z (aref *pond* i 1)))
          (when (> (wave x z tm) 0.55) (sp x 0.3 z 0.05 (* day 3.0))))))
    ;; a sixth of the petals catches the light
    (do ((i 0 (+ i 6)))
        ((>= i +petals+))
      (sp (aref *petal* i 0) (aref *petal* i 1) (aref *petal* i 2) 0.38 2.2))
    (emit-fireflies tm)))

;;; --- the frame's uniforms -----------------------------------------------------
;;;
;;; Six float4s, filled in place: the eye and the hour, the sun and its strength,
;;; the haze and the clock, then the camera's own three axes -- which is what
;;; lets the sky's fragment shader build a view ray per pixel without ever being
;;; handed an inverse matrix.

(defvar *scene* nil)

(defun put3 (i v)
  (setf (aref *scene* i) (aref v 0))
  (setf (aref *scene* (+ i 1)) (aref v 1))
  (setf (aref *scene* (+ i 2)) (aref v 2)))

(defun fill-scene (tm)
  (put3 0 *eye*)
  (setf (aref *scene* 3) *night*)
  (put3 4 (sun-dir))
  (setf (aref *scene* 7) (sun-strength))
  (put3 8 (fog-color))
  (setf (aref *scene* 11) tm)
  (put3 12 (cam-right))
  (setf (aref *scene* 15) (* +tan-half+ *aspect*))
  (put3 16 (cam-up))
  (setf (aref *scene* 19) +tan-half+)
  (put3 20 (cam-forward))
  (setf (aref *scene* 23) 0.0))

;;; --- building the garden ------------------------------------------------------

(defconstant +max-vox+ 20000) ; the static scene's ceiling
(defconstant +max-dyn+ 900)   ; and the moving one's

(defvar *static* nil)
(defvar *static-count* 0)
(defvar *dyn* nil)

(defun build ()
  (setq *sin-tab* (linalg:zeros +tab+ :element-type 'single-float))
  (dotimes (i +tab+) (setf (aref *sin-tab* i) (sin (/ (* +two-pi+ i) +tab+))))
  (setq *scene* (make-array 24 :element-type 'single-float))
  (setq *sbuf* (linalg:zeros (* +max-sprites+ 5) :element-type 'single-float))
  (setq *petal* (linalg:zeros (list +petals+ 5) :element-type 'single-float))
  (setq *static*
        (linalg:zeros (* +max-vox+ +vox-floats+) :element-type 'single-float))
  (setq *dyn*
        (linalg:zeros (* +max-dyn+ +vox-floats+) :element-type 'single-float))
  (build-zones)
  (target *static* (* +max-vox+ +vox-floats+))
  (build-ground)
  (build-pagoda)
  (build-plants)
  (setq *static-count* (voxels))
  ;; the petals start already scattered down the air, not all at the top
  (dotimes (i +petals+) (spawn-petal i))
  (dotimes (i +petals+) (setf (aref *petal* i 1) (rnd-between 0.5 24.0))))

;;; --- the window, and the mouse ------------------------------------------------
;;;
;;; The drawing surface is also the input surface: one NSView subclass defined at
;;; run time whose mouse methods are the Lisp closures below, installed as the
;;; window's content view before metal:attach puts the CAMetalLayer on it.
;;; A drag that has moved orbits the camera; a press that has not, released,
;;; turns the hour.

(defvar *width* 940)
(defvar *height* 660)

(defvar *dragging* nil)
(defvar *moved* 0.0)
(defvar *last* nil)
(defvar *click* nil)
(defvar *view* nil)

(defun view-point (event)
  (let ((p (objc:send event "locationInWindow")) (f (objc:send *view* "frame")))
    (vec3 (- (first p) (first f)) (- (second p) (second f)) 0.0)))

(defun on-mouse-down (self event)
  (setq *dragging* t)
  (setq *moved* 0.0)
  (setq *last* (view-point event))
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
        (orbit (/ dx (* 1.0 *height*)) (/ (- 0.0 dy) (* 1.0 *height*))))))
  nil)

(defun on-mouse-up (self event)
  (when (and *dragging* (<= *moved* 4.0)) (setq *click* t))
  (setq *dragging* nil)
  nil)

(defun on-scroll (self event)
  (let ((dy (objc:send event "scrollingDeltaY")))
    (zoom (* dy (if (objc:send event "hasPreciseScrollingDeltas") -0.20 -3.0))))
  nil)

(defun on-first-mouse (self event) t)

(defvar *input-class*
  (objc:define-class "RontoLispPagodaView"
    "NSView"
    (list (list "mouseDown:" #'on-mouse-down)
          (list "mouseDragged:" #'on-mouse-dragged)
          (list "mouseUp:" #'on-mouse-up) (list "scrollWheel:" #'on-scroll)
          (list "acceptsFirstMouse:" #'on-first-mouse))))

;;; --- go -----------------------------------------------------------------------

(build)

(defvar *window*
  (appkit:window "Pagoda garden" :width *width* :height *height* :dark t))

(setq *view*
      (objc:on-main
       (lambda ()
         (let ((v
                (objc:send (objc:send *input-class* "alloc") "initWithFrame:"
                           (list 0.0 0.0 (* 1.0 *width*) (* 1.0 *height*)))))
           (objc:send *window* "setContentView:" v)
           v))))

(defvar *metal*
  (metal:attach *window* :clear '(0.02 0.03 0.07 1.0) :scale +scale+ :depth t))
(defvar *library* (metal:library *metal* *shaders*))
(defvar *sky* (metal:pipeline *metal* *library* "sky_vertex" "sky_fragment"))
(defvar *solid* (metal:pipeline *metal* *library* "vox_vertex" "vox_fragment"))
(defvar *glow*
  (metal:pipeline *metal* *library* "glow_vertex" "glow_fragment" :blend t))
(defvar *depth-write* (metal:depth-state *metal*))
(defvar *depth-read* (metal:depth-state *metal* :writes nil))
;; the sky is drawn first and stands behind everything: it neither tests the
;; depth buffer nor writes it
(defvar *depth-off*
  (metal:depth-state *metal* :writes nil :compare metal:+compare-always+))

;; The static scene crosses to the GPU once and never changes. What moves is
;; rewritten every frame, so the CPU writes next frame's voxels while the GPU
;; may still be reading this frame's: three copies of each, rotated.
(defconstant +slots+ 3)

(defvar *static-buffer* (metal:buffer *metal* *static*))
(defvar *dyn-buffers* nil)
(defvar *sprite-buffers* nil)
(defvar *slot* 0)

(defun make-buffers (bytes)
  (let ((out '()))
    (dotimes (i +slots+ (nreverse out))
      (push (metal:shared-buffer *metal* bytes) out))))

(defun draw (encoder tm)
  (let ((vp (linalg:transpose *vp*))
        (dyn (nth *slot* *dyn-buffers*))
        (spr (nth *slot* *sprite-buffers*)))
    ;; the sky, from three generated vertices and the Scene uniform alone
    (objc:send encoder "setCullMode:" metal:+cull-none+)
    (objc:send encoder "setRenderPipelineState:" *sky*)
    (objc:send encoder "setDepthStencilState:" *depth-off*)
    (metal:uniform encoder 0 *scene* :stage :fragment)
    (objc:send encoder "drawPrimitives:vertexStart:vertexCount:"
               metal:+triangle+ 0 3)
    ;; the garden, lit and depth-tested
    (objc:send encoder "setCullMode:" metal:+cull-back+)
    (objc:send encoder "setFrontFacingWinding:"
               metal:+winding-counter-clockwise+)
    (objc:send encoder "setRenderPipelineState:" *solid*)
    (objc:send encoder "setDepthStencilState:" *depth-write*)
    (metal:uniform encoder 1 vp)
    (metal:uniform encoder 0 *scene* :stage :fragment)
    (objc:send encoder "setVertexBuffer:offset:atIndex:" *static-buffer* 0 0)
    (objc:send encoder "drawPrimitives:vertexStart:vertexCount:"
               metal:+triangle+ 0 (* 36 *static-count*))
    ;; the water, the lilies, the koi, the petals and the flames
    (target *dyn* (* +max-dyn+ +vox-floats+))
    (emit-water tm)
    (emit-lilies tm)
    (emit-koi tm)
    (emit-petals tm)
    (emit-flames tm)
    (let ((n (voxels)))
      (metal:upload dyn *dyn*)
      (objc:send encoder "setVertexBuffer:offset:atIndex:" dyn 0 0)
      (objc:send encoder "drawPrimitives:vertexStart:vertexCount:"
                 metal:+triangle+ 0 (* 36 n)))
    ;; the glow: additive, reading the depth the garden wrote
    (setq *sw* 0)
    (emit-glow tm)
    (let ((n (floor *sw* 5)))
      (metal:upload spr *sbuf*)
      (objc:send encoder "setCullMode:" metal:+cull-none+)
      (objc:send encoder "setRenderPipelineState:" *glow*)
      (objc:send encoder "setDepthStencilState:" *depth-read*)
      (objc:send encoder "setVertexBuffer:offset:atIndex:" spr 0 0)
      (metal:uniform encoder 1 vp)
      (objc:send encoder "drawPrimitives:vertexStart:vertexCount:" metal:+point+
                 0 n))))

(defun frame (encoder)
  (let* ((tm (now))
         (dt0 (- tm *last-tm*))
         (dt (cond ((< dt0 0.0) 0.0) ((> dt0 0.05) 0.05) (t dt0))))
    (setq *last-tm* tm)
    (when *click*
      (setq *night-target* (if (> *night-target* 0.5) 0.0 1.0))
      (setq *click* nil))
    (let ((k (min 1.0 (* dt 0.75))))
      (setq *night* (+ *night* (* k (- *night-target* *night*)))))
    (update-petals dt)
    (update-camera)
    (fill-scene tm)
    (setq *slot* (mod (+ *slot* 1) +slots+))
    (draw encoder tm)))

(setq *aspect* (/ (* 1.0 *width*) *height*))
(setq *last-tm* (now))
(setq *dyn-buffers* (make-buffers (* +max-dyn+ +vox-floats+ 4)))
(setq *sprite-buffers* (make-buffers (* +max-sprites+ 20)))

(format t "device: ~a~%"
        (objc:send (objc:send (metal:device *metal*) "name") "UTF8String"))
(format t "~a voxels in the garden, ~a of them moving~%" *static-count*
        +max-dyn+)
(format t "drag to orbit, scroll to zoom, click for night~%")

(metal:run *metal* #'frame)

(appkit:wait *window*)
