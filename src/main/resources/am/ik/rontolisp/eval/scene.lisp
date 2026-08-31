;; The scene package: a 3-D viewer for geom solids, on Metal. Written in
;; rontolisp itself over geom (the model), metal (the surface) and appkit/objc
;; (the window and the mouse), and shipped inside the interpreter the way
;; appkit.lisp is (see SceneLibrary.java): a bare REPL types
;;
;;   (defvar *v* (scene:viewer))
;;   (scene:add *v* (geom:cylinder :radius 60 :height 140))
;;   (scene:fit *v*)
;;
;; with nothing required and nothing to copy. Drag to orbit, shift-drag to pan,
;; scroll to dolly.
;;
;; macOS only, like everything that reaches objc:: the interpreter (java -jar or
;; the rontolisp binary) and a compiled .class / .jar carry it; both WASM
;; backends refuse a program that references the package. The browser answer to
;; the same model is examples/browser/webgl-solids/, a renderer over WebGL2 that
;; consumes geom:mesh and geom:world-transform exactly as this one does.
;;
;; scene:offscreen is the same viewer with no window, drawing into a texture
;; scene:snapshot reads back. It exists because no test may open a window, and
;; because it is the SAME scene::%render an offscreen frame is evidence about
;; what a window shows (.kb/geom.md, "How the renderer is tested").
;;
;; THE DESIGN POINT, and the reason this file is shaped the way it is: no
;; triangle is touched by Lisp during a frame. A solid's model-space mesh
;; (geom:mesh) goes into an MTLBuffer of its own the first time it is drawn, and
;; a frame sets one 4x4 model matrix and one colour per solid and issues one draw
;; call. Re-transforming the vertices every frame instead costs 380 ms against
;; 9.0 on a 60-solid model (.kb/geom.md). The vertex function therefore takes vp
;; and model as SEPARATE uniforms and transforms the normal by model too, so a
;; solid that moves needs no re-upload.
;;
;; A viewer is a CLOS instance rather than a set of globals, so two windows can
;; exist in one image. AppKit's callbacks are process-wide, so the input class is
;; defined once and every callback finds its viewer by the ADDRESS of the view
;; that received the event -- appkit::*actions* keyed by widget address is the
;; precedent (.kb/objc.md, "Where the line goes").
;;
;; Portability constraints honored here (like linalg.lisp): do loops always
;; declare at least one variable; parameters are never assigned with setq.

(defconstant scene::+fov+ 0.7853981633974483)

(defconstant scene::+backing-scale+ 2)

;; One library, two pipelines: lit triangles for the solids and flat lines for
;; the grid, the axes and every wireframe. Neither uses a vertex descriptor --
;; the vertex function indexes a `const device` array by vertex_id, so the layout
;; is the Lisp array's layout and nothing declares it twice.
(defvar scene::*shaders*
  "
#include <metal_stdlib>
using namespace metal;

struct Vertex { packed_float3 position; packed_float3 normal; };
struct VertexOut { float4 position [[position]]; float3 normal; float3 world; };

vertex VertexOut solid_vertex(uint id [[vertex_id]],
                              const device Vertex *vertices [[buffer(0)]],
                              constant float4x4 &vp [[buffer(1)]],
                              constant float4x4 &model [[buffer(2)]]) {
  VertexOut out;
  float4 w = model * float4(vertices[id].position, 1.0);
  out.position = vp * w;
  out.normal = (model * float4(vertices[id].normal, 0.0)).xyz;
  out.world = w.xyz;
  return out;
}

fragment float4 solid_fragment(VertexOut in [[stage_in]],
                               constant float4 &eye [[buffer(0)]],
                               constant float4 &tint [[buffer(1)]]) {
  float3 n = normalize(in.normal);
  float3 l = normalize(float3(0.45, 0.80, 0.40));
  float3 e = normalize(eye.xyz - in.world);
  float3 h = normalize(l + e);
  float diff = max(dot(n, l), 0.0);
  float amb  = 0.32 + 0.14 * n.z;
  float spec = pow(max(dot(n, h), 0.0), 42.0) * 0.35;
  float rim  = pow(1.0 - max(dot(n, e), 0.0), 3.0) * 0.16;
  return float4(tint.rgb * (amb + 0.70 * diff) + float3(spec)
                + float3(0.35, 0.55, 0.9) * rim, 1.0);
}

vertex float4 line_vertex(uint id [[vertex_id]],
                          const device packed_float3 *points [[buffer(0)]],
                          constant float4x4 &vp [[buffer(1)]],
                          constant float4x4 &model [[buffer(2)]]) {
  return vp * (model * float4(points[id], 1.0));
}

fragment float4 line_fragment(constant float4 &tint [[buffer(1)]]) {
  return tint;
}
")

;; View address -> the viewer that view belongs to. The one table every callback
;; goes through, so two viewers orbit independently.
(defvar scene::*views* (make-hash-table))

(defvar scene::*input-class* nil)

(defvar scene::*observer* nil)

(defvar scene::*identity4* nil)

;; --- the viewer ---------------------------------------------------------------

(defclass scene:viewer-state ()
  ((window :initarg :window :reader scene:window-of)
   (view :initarg :view :reader scene::%view)
   (ctx :initarg :ctx :reader scene:context-of)
   (solid-pipeline :initarg :solid-pipeline :reader scene::%solid-pipeline)
   (line-pipeline :initarg :line-pipeline :reader scene::%line-pipeline)
   (depth :initarg :depth :reader scene::%depth)
   (contents :initform nil :accessor scene::%contents)
   (grid-buffer :initform nil :accessor scene::%grid-buffer)
   (grid-points :initform 0 :accessor scene::%grid-points)
   (grid-rgb :initarg :grid-rgb :accessor scene::%grid-rgb)
   (axes-buffer :initform nil :accessor scene::%axes-buffer)
   (axes-mode :initform nil :accessor scene::%axes-mode)
   (shading :initform :both :accessor scene::%shading)
   (width :initarg :width :accessor scene::%width)
   (height :initarg :height :accessor scene::%height)
   (target :initarg :target :accessor scene::%target)
   (azimuth :initform 0.9 :accessor scene::%azimuth)
   (elevation :initform 0.45 :accessor scene::%elevation)
   (distance :initform 1200.0 :accessor scene::%distance)
   (basis :initform nil :accessor scene::%basis)
   (eye :initform nil :accessor scene::%eye)
   (view-projection :initform nil :accessor scene::%view-projection)
   (dragging :initform nil :accessor scene::%dragging)
   (panning :initform nil :accessor scene::%panning)
   (last-point :initform nil :accessor scene::%last-point)
   ;; Where the press landed and how far it has travelled since: what separates
   ;; a click from a drag on release, since one gesture is both until then.
   (down-point :initform nil :accessor scene::%down-point)
   (moved :initform 0.0 :accessor scene::%moved)
   (click-hook :initform nil :accessor scene::%click-hook)
   (frame-hook :initform nil :accessor scene::%frame-hook)))

;; The one NSView subclass, defined once per process: its five selectors are
;; declared by NSView, so the encodings are read off the superclass and every one
;; of them lands on a supported callback shape (.kb/objc.md).
(defun scene::%input-class ()
  (when (null scene::*input-class*)
    (setq scene::*input-class*
          (objc:define-class "RontoLispSceneView"
            "NSView"
            (list (list "mouseDown:" #'scene::%on-mouse-down)
                  (list "mouseDragged:" #'scene::%on-mouse-dragged)
                  (list "mouseUp:" #'scene::%on-mouse-up)
                  (list "scrollWheel:" #'scene::%on-scroll)
                  (list "acceptsFirstMouse:" #'scene::%on-first-mouse)))))
  scene::*input-class*)

;; The one notification observer, likewise: NSView posts its frame changes to it
;; and it forwards them to the viewer that owns the view.
(defun scene::%observer ()
  (when (null scene::*observer*)
    (let ((cls
           (objc:define-class "RontoLispSceneObserver"
             "NSObject"
             (list (list "frameChanged:" #'scene::%on-frame-changed)))))
      (setq scene::*observer* (objc:send (objc:send cls "alloc") "init"))))
  scene::*observer*)

(defun scene::%viewer-for (view) (gethash (objc:address view) scene::*views*))

(defun scene::%make-view (window width height)
  (objc:on-main
   (lambda ()
     (let ((view
            (objc:send (objc:send (scene::%input-class) "alloc")
                       "initWithFrame:"
                       (list 0.0 0.0 (float width 1.0) (float height 1.0)))))
       (objc:send window "setContentView:" view)
       ;; without this NSView posts nothing and a resize is invisible
       (objc:send view "setPostsFrameChangedNotifications:" t)
       view))))

(defun scene::%watch-resize (view)
  (objc:on-main
   (lambda ()
     (objc:send (objc:send "NSNotificationCenter" "defaultCenter")
                "addObserver:selector:name:object:" (scene::%observer)
                "frameChanged:" "NSViewFrameDidChangeNotification" view)))
  nil)

;; Everything a viewer is that does not depend on WHERE it draws: two pipelines
;; over the one shader library, the depth state, the camera and the fixed
;; furniture. The two constructors below differ only in the metal:context they
;; hand it -- and therefore share the render function, which is the only way an
;; offscreen frame can be evidence about the window's.
(defun scene::%viewer-over (ctx window view width height)
  (let* ((lib (metal:library ctx scene::*shaders*))
         (v
          (make-instance 'scene:viewer-state
           :window window
           :view view
           :ctx ctx
           :solid-pipeline (metal:pipeline ctx lib "solid_vertex"
                                           "solid_fragment")
           :line-pipeline (metal:pipeline ctx lib "line_vertex" "line_fragment")
           :depth (metal:depth-state ctx)
           :width (float width 1.0)
           :height (float height 1.0)
           :target (geom:vec3 0 0 0)
           :grid-rgb (geom:vec3 0.30 0.34 0.42))))
    (scene:grid v)
    (scene::%build-axes v)
    v))

(defun scene:viewer (&key (title "rontolisp scene") (width 900) (height 640)
                          (background '(0.055 0.065 0.09 1.0)))
  (let* ((win (appkit:window title :width width :height height :dark t))
         (view (scene::%make-view win width height))
         (ctx
          (metal:attach win
                        :clear background
                        :scale scene::+backing-scale+
                        :depth t))
         (v (scene::%viewer-over ctx win view width height)))
    (setf (gethash (objc:address view) scene::*views*) v)
    (scene::%watch-resize view)
    v))

;; A viewer with no window: the same pipelines, the same camera and the same
;; scene::%render, drawing into a texture metal:pixels can read back. WIDTH and
;; HEIGHT are pixels. It has no input -- there is nothing to click -- so the
;; camera moves through scene:camera and scene:fit, and a frame is
;; scene:snapshot.
;;
;; This is what makes the renderer testable: no test may open a window, so
;; without it the camera, the projection, the model matrices, the winding and
;; the depth test would ship with nothing checking them (.kb/geom.md).
(defun scene:offscreen
    (&key (width 640) (height 480) (background '(0.055 0.065 0.09 1.0)))
  (scene::%viewer-over
   (metal:offscreen :width width :height height :clear background :depth t) nil
   nil width height))

;; One frame of an offscreen viewer, as its pixels: width*height*4 bytes, BGRA,
;; row 0 at the top (metal:pixels). Drawn through scene:refresh like every other
;; frame.
(defun scene:snapshot (v)
  (scene:refresh v)
  (metal:pixels (scene:context-of v)))

;; --- helpers -------------------------------------------------------------------

(defun scene::%unit (v)
  (let ((n (linalg:norm v))) (linalg:mul v (/ 1.0 (if (< n 1e-9) 1e-9 n)))))

(defun scene::%float4 (v w)
  (linalg:concatenate
   (list v
         (linalg:from-list (list (float w 1.0)) :element-type 'single-float))))

(defun scene::%identity4 ()
  (when (null scene::*identity4*)
    (setq scene::*identity4* (linalg:eye 4 :element-type 'single-float)))
  scene::*identity4*)

;; A node's world transform as the column-major float4x4 Metal wants. This, once
;; per solid, IS the per-frame CPU cost of drawing it.
(defun scene::%model-matrix (node)
  (let ((m (linalg:zeros '(4 4) :element-type 'single-float))
        (tf (geom:world-transform node)))
    (dotimes (i 3)
      (dotimes (j 3) (setf (aref m j i) (aref (geom:rotation-of tf) i j)))
      (setf (aref m 3 i) (aref (geom:translation-of tf) i)))
    (setf (aref m 3 3) 1.0)
    m))

;; A uniform scale as a float4x4, for the axis triads.
(defun scene::%scale-matrix (s)
  (let ((m (linalg:zeros '(4 4) :element-type 'single-float)))
    (dotimes (i 3) (setf (aref m i i) (float s 1.0)))
    (setf (aref m 3 3) 1.0)
    m))

;; A node's world transform with a uniform scale folded in, so an axis triad can
;; be drawn from the same unit buffer at the body's own frame.
(defun scene::%body-matrix (node s)
  (let ((m (scene::%model-matrix node)))
    (dotimes (i 3)
      (dotimes (j 3) (setf (aref m i j) (* (aref m i j) (float s 1.0)))))
    m))

(defun scene::%perspective (aspect near far)
  (let ((m (linalg:zeros '(4 4) :element-type 'single-float))
        (f (/ 1.0 (tan (/ scene::+fov+ 2.0)))))
    (setf (aref m 0 0) (/ f aspect))
    (setf (aref m 1 1) f)
    (setf (aref m 2 2) (/ far (- near far)))
    (setf (aref m 2 3) (/ (* near far) (- near far)))
    (setf (aref m 3 2) -1.0)
    m))

;; --- contents ------------------------------------------------------------------

;; What a viewer's contents may hold, refused HERE rather than one frame later.
;; A non-solid used to be consed straight in, and the report arrived from inside
;; the draw callback as "No applicable method: GEOM:USER-DATA on CONS" -- a
;; message naming nothing the caller wrote, on a thread the caller is not on.
(defun scene::%check-solid (who s)
  (unless (typep s 'geom:solid) (error "~a: ~a is not a geom:solid" who s))
  s)

;; Every argument is a solid or a LIST of solids, spliced in order. geom:triad
;; answers three solids where geom:box answers one, so (scene:add v (geom:triad))
;; has to compose the same way as (scene:add v (geom:box 10)) -- otherwise every
;; caller and every doc page spells a dolist around the one constructor that
;; returns more than one thing. nil is the empty list and adds nothing.
;;
;; Nothing is added until every argument has been checked, so a bad one leaves
;; the viewer as it was.
(defun scene:add (v &rest solids)
  (let ((new '()))
    (dolist (s solids)
      (dolist (x (if (listp s) s (list s)))
        (scene::%check-solid "scene:add" x)
        (push x new)))
    (let ((ordered (nreverse new)))
      (setf (scene::%contents v) (append (scene::%contents v) ordered))
      (car (last ordered)))))

;; The shape scene:add takes, so what went in can come back out: a viewer given
;; (geom:triad) is emptied of it by (scene:drop v *triad*), not by three calls.
;; scene:clear needs no equivalent -- it names no solid at all.
(defun scene:drop (v &rest solids)
  (let ((victims '()))
    (dolist (s solids)
      (dolist (x (if (listp s) s (list s)))
        (scene::%check-solid "scene:drop" x)
        (push x victims)))
    (let ((ordered (nreverse victims)))
      (dolist (x ordered)
        (setf (scene::%contents v) (remove x (scene::%contents v)))
        (setf (geom:user-data x) nil))
      (car (last ordered)))))

(defun scene:clear (v)
  (dolist (s (scene::%contents v)) (setf (geom:user-data s) nil))
  (setf (scene::%contents v) nil)
  nil)

(defun scene:contents (v) (scene::%contents v))

(defun scene:shading (v mode)
  (setf (scene::%shading v) mode)
  nil)

;; nil (the default -- nothing), :world, :bodies or :both. These are the
;; viewer's FURNITURE: line triads with no thickness, and the world one is
;; scaled by the view distance so it stays legible at any zoom. An origin
;; indicator that is an OBJECT -- placed where the caller says, with a shaft
;; thickness and a pointed tip -- is (geom:triad), three geom:arrow solids added
;; like any other, which is why nothing is drawn here unless it was asked for
;; (.kb/geom.md).
;;
;; A body triad draws each solid's OWN frame, sized from that body's extent,
;; which is what makes a joint chain readable; there is no text -- geom:label-of
;; names a frame and the triad locates it, and glyph rendering in Metal is a
;; sub-problem of its own.
(defun scene:axes (v mode)
  (setf (scene::%axes-mode v) mode)
  nil)

;; Uploaded once per solid, and only when it is first drawn: the mesh buffer, its
;; triangle count, the wireframe buffer, its segment count, and the length an
;; axis triad at this body should be drawn at (from the MODEL-space extent, so it
;; does not change as the solid moves). The entry lives on the solid rather than
;; in a table keyed by it because a hash table cannot key on a node at all
;; (.kb/geom.md).
(defun scene::%gpu-buffers (v s)
  (when (null (geom:user-data s))
    (let* ((m (geom:mesh s))
           (w (geom:wireframe s))
           (extent (geom::%model-extent s)))
      (setf (geom:user-data s)
            (list (metal:buffer (scene:context-of v) m) (floor (length m) 6)
                  (metal:buffer (scene:context-of v) w) (floor (length w) 3)
                  (* 0.55 extent)))))
  (geom:user-data s))

;; --- camera --------------------------------------------------------------------

(defun scene:camera (v &key azimuth elevation distance target)
  (when azimuth (setf (scene::%azimuth v) azimuth))
  (when elevation (setf (scene::%elevation v) elevation))
  (when distance (setf (scene::%distance v) distance))
  (when target (setf (scene::%target v) target))
  nil)

(defun scene:fit (v)
  (let ((cs (scene::%contents v)))
    (when cs
      (let ((b (geom:bounds cs)))
        (setf (scene::%target v) (geom:bounds-center b))
        ;; The distance is RELATIVE to what is being framed and nothing else. An
        ;; absolute floor would be a unit -- and a model file brings its own: a
        ;; scanned mesh is 0.2 across in metres, a printable part 200 in
        ;; millimetres, and both have to fill the frame. The only guard left is
        ;; against a degenerate extent (one point, an empty solid), which names
        ;; no distance at all.
        (let ((extent (linalg:norm (geom:bounds-extent b))))
          (when (> extent 0.0) (setf (scene::%distance v) (* 1.9 extent)))))))
  nil)

(defun scene::%update-camera (v)
  (let* ((ce (cos (scene::%elevation v)))
         (se (sin (scene::%elevation v)))
         (d (scene::%distance v))
         (eye
          (linalg:add (scene::%target v)
                      (geom:vec3 (* d ce (cos (scene::%azimuth v)))
                                 (* d ce (sin (scene::%azimuth v))) (* d se))))
         (forward (scene::%unit (linalg:sub (scene::%target v) eye)))
         (right (scene::%unit (linalg:cross forward (geom:vec3 0 0 1))))
         (up (linalg:cross right forward))
         (r (linalg:stack (list right up (linalg:mul forward -1.0))))
         (view
          (linalg:concatenate (list (linalg:concatenate (list r
                                                              (linalg:reshape
                                                               (linalg:mul
                                                                (linalg:matmul r
                                                                 eye) -1.0)
                                                               '(3 1)))
                                                        :axis 1)
                                    (linalg:from-list '((0.0 0.0 0.0 1.0))
                                     :element-type 'single-float))
                              :axis 0))
         ;; The frustum follows the view distance, so it carries no unit either:
         ;; a near plane pinned at an absolute number puts a metre-scale model
         ;; behind it and the frame comes back empty (scene:fit above).
         (far (* 8.0 (if (> d 0.0) d 1.0))))
    (setf (scene::%eye v) eye)
    (setf (scene::%basis v) (linalg:stack (list right up forward)))
    (setf (scene::%view-projection v)
          (linalg:matmul (scene::%perspective
                          (/ (scene::%width v) (scene::%height v)) (* 0.002 far)
                          far) view))))

;; --- picking: what a click is ------------------------------------------------
;;
;; A viewer that can be orbited but cannot say WHERE a click landed is only half
;; a viewer, and the honest answer to "where" is not a point: a pixel names a
;; LINE through the world, and which point of that line was meant is the
;; program's question. So the primitive is scene:ray and scene:on-click is the
;; convenience over it -- the viewer knows its own orbit target, so the plane
;; through that target facing the camera is the one plane it can pick without
;; being told, and "click where you see" is exactly what a hook wants.

;; The world-space eye ray through a point of the view: (origin direction), two
;; 3-vectors, the direction a unit vector. X and Y are view coordinates in
;; points -- AppKit's, so the origin is the bottom-left corner and +y is up.
(defun scene:ray (v x y)
  (scene::%update-camera v)
  (let* ((th (tan (/ scene::+fov+ 2.0)))
         (aspect (/ (scene::%width v) (scene::%height v)))
         (cx (- (/ (* 2.0 x) (scene::%width v)) 1.0))
         (cy (- (/ (* 2.0 y) (scene::%height v)) 1.0))
         (basis (scene::%basis v)))
    (list (scene::%eye v)
          (scene::%unit
           (linalg:add (linalg:row basis 2)
                       (linalg:add
                        (linalg:mul (linalg:row basis 0) (* cx th aspect))
                        (linalg:mul (linalg:row basis 1) (* cy th))))))))

;; Where that ray meets the plane through the orbit target facing the camera.
;; The denominator cannot vanish -- the ray is inside the frustum, so it is
;; never perpendicular to the view direction -- but a viewer whose width or
;; height went to nothing would make it, and a division by zero is not the
;; report anyone wants from a mouse click.
(defun scene::%click-point (v x y)
  (let* ((r (scene:ray v x y))
         (o (first r))
         (d (second r))
         (forward (linalg:row (scene::%basis v) 2))
         (den (linalg:dot d forward))
         (tt
          (/ (linalg:dot (linalg:sub (scene::%target v) o) forward)
             (if (< (abs den) 1e-6) 1e-6 den))))
    (linalg:add o (linalg:mul d tt))))

;; HOOK is called with one argument, that world point, on the main thread; nil
;; removes it. A click is a press that was released without travelling more than
;; a few points -- the same classification the browser twin makes -- so orbiting
;; and clicking are one gesture and neither needs a modifier.
(defun scene:on-click (v hook)
  (setf (scene::%click-hook v) hook)
  nil)

;; --- the fixed furniture: a ground grid and the axis triad ---------------------

;; The ground plane, or none of it: :extent nil drops the grid the way
;; (scene:axes v nil) drops the triads, which a viewer that is a picture of one
;; solid and nothing else wants.
(defun scene:grid (v &key (extent 600.0) (spacing 50.0))
  (if (null extent)
      (progn
        (setf (scene::%grid-buffer v) nil)
        (setf (scene::%grid-points v) 0))
      (let* ((e (float extent 1.0))
             (sp (float spacing 1.0))
             (n (floor e sp))
             (lines (* 2 (+ (* 2 n) 1)))
             (pts
              (make-array (* lines 6)
                          :element-type 'single-float
                          :initial-element 0.0))
             (k 0))
        (do ((i (- n) (+ i 1)))
            ((> i n) nil)
          (let ((x (* i sp)))
            (setf (aref pts k) x)
            (setf (aref pts (+ k 1)) (- e))
            (setf (aref pts (+ k 3)) x)
            (setf (aref pts (+ k 4)) e)
            (setq k (+ k 6))
            (setf (aref pts k) (- e))
            (setf (aref pts (+ k 1)) x)
            (setf (aref pts (+ k 3)) e)
            (setf (aref pts (+ k 4)) x)
            (setq k (+ k 6))))
        (setf (scene::%grid-buffer v) (metal:buffer (scene:context-of v) pts))
        (setf (scene::%grid-points v) (* lines 2))))
  nil)

(defun scene:grid-color (v rgb)
  (setf (scene::%grid-rgb v) rgb)
  nil)

(defun scene:background (v rgba)
  (metal:set-clear-color (scene:context-of v) rgba)
  nil)

;; Three unit segments from the origin, drawn with three tints -- one buffer,
;; three draws of two, and one model matrix wherever a triad is wanted.
(defun scene::%build-axes (v)
  (let ((pts (make-array 18 :element-type 'single-float :initial-element 0.0)))
    (setf (aref pts 3) 1.0)
    (setf (aref pts 10) 1.0)
    (setf (aref pts 17) 1.0)
    (setf (scene::%axes-buffer v) (metal:buffer (scene:context-of v) pts)))
  nil)

;; --- input ---------------------------------------------------------------------

(defun scene::%view-point (event)
  (let ((p (objc:send event "locationInWindow")))
    (geom:vec3 (first p) (second p) 0.0)))

(defun scene::%on-mouse-down (self event)
  (let ((v (scene::%viewer-for self)))
    (when v
      (setf (scene::%dragging v) t)
      ;; 131072 is NSEventModifierFlagShift
      (setf (scene::%panning v)
            (> (logand (objc:send event "modifierFlags") 131072) 0))
      (setf (scene::%moved v) 0.0)
      (setf (scene::%down-point v) (scene::%view-point event))
      (setf (scene::%last-point v) (scene::%view-point event))))
  nil)

;; A drag arrives as a delta in VIEW coordinates, and AppKit's view coordinates
;; put +y UP. The browser twin (examples/browser/webgl-solids/) drives the very
;; same two constants from DOM client coordinates, where +y is DOWN, so the
;; elevation term is the one place the two dialects disagree about a sign. It is
;; negated HERE, in the orbit arm, and not in %view-point: the pan arm below
;; wants the y-up delta exactly as AppKit reports it, since moving the target
;; AGAINST the drag is what makes the model follow the cursor. Flipping the
;; point would invert the pan along with the orbit.
(defun scene::%orbit (v dx dy)
  (setf (scene::%azimuth v)
        (- (scene::%azimuth v) (* 3.4 (/ dx (scene::%height v)))))
  (let ((e (- (scene::%elevation v) (* 2.6 (/ dy (scene::%height v))))))
    (setf (scene::%elevation v) (cond ((< e -1.5) -1.5) ((> e 1.5) 1.5) (t e))))
  nil)

(defun scene::%pan (v dx dy)
  (setf (scene::%target v)
        (linalg:sub (scene::%target v)
                    (linalg:add (linalg:mul (linalg:row (scene::%basis v) 0)
                                            (* dx (scene::%distance v) 0.0016))
                                (linalg:mul (linalg:row (scene::%basis v) 1)
                                 (* dy (scene::%distance v) 0.0016)))))
  nil)

(defun scene::%on-mouse-dragged (self event)
  (let ((v (scene::%viewer-for self)))
    (when (and v (scene::%dragging v))
      (let* ((p (scene::%view-point event))
             (d (linalg:sub p (scene::%last-point v)))
             (dx (aref d 0))
             (dy (aref d 1)))
        (setf (scene::%last-point v) p)
        (setf (scene::%moved v) (+ (scene::%moved v) (abs dx) (abs dy)))
        (if (scene::%panning v) (scene::%pan v dx dy) (scene::%orbit v dx dy))
        ;; A camera change has to be SHOWN. The mutators below do not redraw --
        ;; a loop adding sixty solids must not draw sixty frames, and the REPL
        ;; step after them is scene:refresh -- but a drag is the one place where
        ;; the change and the frame are the same gesture, so a viewer that is
        ;; not animating still orbits.
        (scene:refresh v))))
  nil)

;; The release is where a gesture is classified: a press that has travelled no
;; more than a few points is a click, and the orbit it also performed over those
;; few points is invisible -- which is why the deadzone is here rather than in
;; the drag arm, where it would make a slow orbit start with a jump.
(defun scene::%on-mouse-up (self event)
  event
  (let ((v (scene::%viewer-for self)))
    (when v
      (let ((hook (scene::%click-hook v)) (p (scene::%down-point v)))
        (setf (scene::%dragging v) nil)
        (when (and hook p (not (scene::%panning v)) (<= (scene::%moved v) 4.0))
          (funcall hook (scene::%click-point v (aref p 0) (aref p 1)))
          ;; The hook is a program's change and the click is the gesture that
          ;; asked for it, so the frame belongs with it -- an idle viewer would
          ;; otherwise answer a click with nothing on screen.
          (scene:refresh v)))))
  nil)

(defun scene::%on-scroll (self event)
  (let ((v (scene::%viewer-for self)))
    (when v
      (let* ((dy (objc:send event "scrollingDeltaY"))
             (k (if (objc:send event "hasPreciseScrollingDeltas") -0.004 -0.20))
             (d (* (scene::%distance v) (+ 1.0 (* dy k)))))
        ;; The clamp exists to stop the scroll reaching zero (a degenerate
        ;; frustum and a dead orbit) or overflowing, not to say how big a world
        ;; is -- the old 10 .. 200000 window was a unit, and a model measured in
        ;; metres started outside it.
        (setf (scene::%distance v)
              (cond ((< d 1.0e-6) 1.0e-6) ((> d 1.0e9) 1.0e9) (t d)))
        (scene:refresh v))))
  nil)

(defun scene::%on-first-mouse (self event)
  self
  event
  t)

;; The window was resized: the CAMetalLayer's frame and drawable size have to
;; follow the view, and the projection's aspect with them. Redrawn immediately,
;; because a viewer that is not animating would otherwise show the old frame
;; stretched until something else asked for one.
(defun scene::%on-frame-changed (self note)
  self
  (let ((v (scene::%viewer-for (objc:send note "object"))))
    (when v
      (let* ((box (objc:send (scene::%view v) "frame"))
             (w (third box))
             (h (fourth box)))
        (when (and (> w 1.0) (> h 1.0)
                   (or (/= w (scene::%width v)) (/= h (scene::%height v))))
          (setf (scene::%width v) (float w 1.0))
          (setf (scene::%height v) (float h 1.0))
          (metal:resize (scene:context-of v) w h)
          (scene:refresh v)))))
  nil)

;; --- a frame -------------------------------------------------------------------
;;
;; The per-frame CPU cost is one model matrix and one draw call per solid. No
;; triangle is touched by Lisp here: the meshes went to the GPU when the solids
;; were first drawn and have not moved since.

(defun scene::%draw-lines (v encoder vp buffer count model tint)
  (objc:send encoder "setRenderPipelineState:" (scene::%line-pipeline v))
  (objc:send encoder "setVertexBuffer:offset:atIndex:" buffer 0 0)
  (metal:uniform encoder 1 vp)
  (metal:uniform encoder 2 model)
  (metal:uniform encoder 1 tint :stage :fragment)
  (objc:send encoder "drawPrimitives:vertexStart:vertexCount:" metal:+line+ 0
             count))

;; One triad: three draws of two vertices out of the unit axes buffer, under the
;; model matrix the caller chose.
(defun scene::%draw-axes (v encoder vp model)
  (objc:send encoder "setRenderPipelineState:" (scene::%line-pipeline v))
  (objc:send encoder "setVertexBuffer:offset:atIndex:" (scene::%axes-buffer v) 0
             0)
  (metal:uniform encoder 1 vp)
  (metal:uniform encoder 2 model)
  (dolist (row
           (list (list 0 (geom:vec3 1.0 0.28 0.28))
                 (list 2 (geom:vec3 0.30 1.0 0.40))
                 (list 4 (geom:vec3 0.38 0.55 1.0))))
    (metal:uniform encoder 1 (scene::%float4 (second row) 1.0) :stage :fragment)
    (objc:send encoder "drawPrimitives:vertexStart:vertexCount:" metal:+line+
               (first row) 2)))

(defun scene::%render (v encoder)
  (when (scene::%frame-hook v) (funcall (scene::%frame-hook v)))
  (scene::%update-camera v)
  (let ((vp (linalg:transpose (scene::%view-projection v)))
        (eye (scene::%float4 (scene::%eye v) 0.0))
        (mode (scene::%axes-mode v)))
    (objc:send encoder "setDepthStencilState:" (scene::%depth v))
    ;; geom winds a facet counter-clockwise seen from OUTSIDE in a right-handed
    ;; world, and Metal decides facing in CLIP space (y up), not in the y-down
    ;; framebuffer -- so an outward face arrives counter-clockwise and that is
    ;; the front. Stated rather than left to Metal's default (which is the other
    ;; one), because this is where the renderer agrees with the convention
    ;; geom's volume integral rests on: a facet wound the wrong way is invisible
    ;; here and subtracts there. Cull mode is a triangle rule, so the line
    ;; pipelines below are unaffected.
    (objc:send encoder "setFrontFacingWinding:"
               metal:+winding-counter-clockwise+)
    (objc:send encoder "setCullMode:" metal:+cull-back+)
    ;; the ground grid, in world coordinates
    (when (> (scene::%grid-points v) 0)
      (scene::%draw-lines v encoder vp (scene::%grid-buffer v)
                          (scene::%grid-points v) (scene::%identity4)
                          (scene::%float4 (scene::%grid-rgb v) 1.0)))
    ;; the world axes, scaled by the current view distance so they stay legible
    (when (or (eq mode :world) (eq mode :both))
      (scene::%draw-axes v encoder vp
                         (scene::%scale-matrix (* 0.16 (scene::%distance v)))))
    (dolist (s (scene::%contents v))
      (let ((bufs (scene::%gpu-buffers v s)) (model (scene::%model-matrix s)))
        (when (or (eq (scene::%shading v) :solid)
                  (eq (scene::%shading v) :both))
          (objc:send encoder "setRenderPipelineState:"
                     (scene::%solid-pipeline v))
          (objc:send encoder "setVertexBuffer:offset:atIndex:" (first bufs) 0 0)
          (metal:uniform encoder 1 vp)
          (metal:uniform encoder 2 model)
          (metal:uniform encoder 0 eye :stage :fragment)
          (metal:uniform encoder 1 (scene::%float4 (geom:color-of s) 1.0)
                         :stage :fragment)
          (objc:send encoder "drawPrimitives:vertexStart:vertexCount:"
                     metal:+triangle+ 0 (second bufs)))
        (when (or (eq (scene::%shading v) :wireframe)
                  (eq (scene::%shading v) :both))
          (scene::%draw-lines v encoder vp (third bufs) (fourth bufs) model
           (scene::%float4 (linalg:mul (geom:color-of s) 0.45) 1.0)))
        (when (or (eq mode :bodies) (eq mode :both))
          (scene::%draw-axes v encoder vp
                             (scene::%body-matrix s (nth 4 bufs))))))))

(defun scene:refresh (v)
  (metal:frame (scene:context-of v)
               (lambda (encoder) (scene::%render v encoder)))
  nil)

(defun scene:animate (v &optional hook)
  (when hook (setf (scene::%frame-hook v) hook))
  (metal:run (scene:context-of v) (lambda (encoder) (scene::%render v encoder)))
  nil)

(defun scene:wait (v) (appkit:wait (scene:window-of v)))
