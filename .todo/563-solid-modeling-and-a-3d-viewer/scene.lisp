;;;; scene.lisp -- SPIKE: a 3D viewer for geom solids, on Metal.
;;;;
;;;;   (defvar *v* (scene:viewer :title "..."))
;;;;   (scene:add *v* solid ...)   (scene:drop *v* s)   (scene:clear *v*)
;;;;   (scene:fit *v*)             ; frame everything
;;;;   (scene:camera *v* :azimuth a :elevation e :distance d :target v)
;;;;   (scene:grid *v* :extent 600 :spacing 50)
;;;;   (scene:shading *v* :solid | :wireframe | :both)
;;;;   (scene:refresh *v*)         ; one frame
;;;;   (scene:animate *v* fn)      ; fn per frame, then 60fps
;;;;   (scene:wait *v*)
;;;;
;;;; Drag to orbit, shift-drag to pan, scroll to dolly.
;;;;
;;;; The design point: a solid's mesh is uploaded ONCE into a GPU buffer of its
;;;; own and drawn with the solid's world transform as a per-draw uniform. The
;;;; CPU's per-frame work is one 4x4 matrix per solid -- not one triangle per
;;;; triangle. See ../README.md, result 3.

(provide :scene)
(require :geom "geom.lisp")
(require :metal "metal.lisp")

(defpackage scene
  (:use cl)
  (:export viewer add drop clear contents fit camera grid grid-color background
           shading refresh animate wait window-of))

(in-package scene)

(defconstant +line-primitive+ 1) ; MTLPrimitiveTypeLine
(defconstant +fov+ 0.7853981633974483)
(defconstant +backing-scale+ 2)

(defvar *shaders* "
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

;;; --- helpers ---------------------------------------------------------------------

(defun vec3 (x y z) (geom:vec3 x y z))

(defun unit (v)
  (let ((n (linalg:norm v))) (linalg:mul v (/ 1.0 (if (< n 1e-9) 1e-9 n)))))

(defun float4 (v w)
  (linalg:concatenate (list v (linalg:from-list (list (float w 1.0))
                                                :element-type 'single-float))))

;; A solid's world transform as the column-major float4x4 Metal wants.
(defun model-matrix (node)
  (let ((m (linalg:zeros '(4 4) :element-type 'single-float))
        (tf (geom:world-transform node)))
    (dotimes (i 3)
      (dotimes (j 3) (setf (aref m j i) (aref (geom:rotation-of tf) i j)))
      (setf (aref m 3 i) (aref (geom:translation-of tf) i)))
    (setf (aref m 3 3) 1.0)
    m))

(defun perspective (aspect near far)
  (let ((m (linalg:zeros '(4 4) :element-type 'single-float))
        (f (/ 1.0 (tan (/ +fov+ 2.0)))))
    (setf (aref m 0 0) (/ f aspect))
    (setf (aref m 1 1) f)
    (setf (aref m 2 2) (/ far (- near far)))
    (setf (aref m 2 3) (/ (* near far) (- near far)))
    (setf (aref m 3 2) -1.0)
    m))

;;; --- the viewer --------------------------------------------------------------------
;;;
;;; One CLOS instance rather than a set of globals, so two windows can exist.
;;; The AppKit callbacks are process-wide, though, so the spike routes them
;;; through *active* -- an item that ships this has to key the handler by view.

(defclass viewer-state ()
  ((window :initarg :window :accessor window-of)
   (ctx :initarg :ctx :accessor ctx-of)
   (solid-pipeline :accessor solid-pipeline-of)
   (line-pipeline :accessor line-pipeline-of)
   (depth :accessor depth-of)
   (contents :initform nil :accessor contents-of)
   (grid-buffer :initform nil :accessor grid-buffer-of)
   (grid-points :initform 0 :accessor grid-points-of)
   (grid-rgb :initform nil :accessor grid-rgb-of)
   (axes-buffer :initform nil :accessor axes-buffer-of)
   (shading :initform :both :accessor shading-of)
   (width :initarg :width :accessor width-of)
   (height :initarg :height :accessor height-of)
   (target :accessor target-of)
   (azimuth :initform 0.9 :accessor azimuth-of)
   (elevation :initform 0.45 :accessor elevation-of)
   (distance :initform 1200.0 :accessor distance-of)
   (basis :initform nil :accessor basis-of)
   (eye :initform nil :accessor eye-of)
   (view-projection :initform nil :accessor view-projection-of)
   (frame-hook :initform nil :accessor frame-hook-of)))

(defvar *active* nil)
(defvar *input-class* nil)

(defun viewer (&key (title "rontolisp scene") (width 900) (height 640)
                    (background '(0.055 0.065 0.09 1.0)))
  (when (null *input-class*)
    (setq *input-class*
          (objc:define-class "RontoLispSceneView" "NSView"
            (list (list "mouseDown:" #'on-mouse-down)
                  (list "mouseDragged:" #'on-mouse-dragged)
                  (list "mouseUp:" #'on-mouse-up)
                  (list "scrollWheel:" #'on-scroll)
                  (list "acceptsFirstMouse:" #'on-first-mouse)))))
  (let* ((win (appkit:window title :width width :height height :dark t))
         (v (make-instance 'viewer-state :window win :width width :height height)))
    (objc:on-main
     (lambda ()
       (objc:send win "setContentView:"
                  (objc:send (objc:send *input-class* "alloc") "initWithFrame:"
                             (list 0.0 0.0 (* 1.0 width) (* 1.0 height))))))
    (setf (ctx-of v) (metal:attach win :clear background :scale +backing-scale+ :depth t))
    (setf (target-of v) (vec3 0 0 0))
    (setf (grid-rgb-of v) (vec3 0.30 0.34 0.42))
    (let ((lib (metal:library (ctx-of v) *shaders*)))
      (setf (solid-pipeline-of v)
            (metal:pipeline (ctx-of v) lib "solid_vertex" "solid_fragment"))
      (setf (line-pipeline-of v)
            (metal:pipeline (ctx-of v) lib "line_vertex" "line_fragment")))
    (setf (depth-of v) (metal:depth-state (ctx-of v)))
    (grid v)
    (build-axes v)
    (setq *active* v)
    v))

;;; --- contents --------------------------------------------------------------------

(defun add (v &rest solids)
  (dolist (s solids) (setf (contents-of v) (append (contents-of v) (list s))))
  (car (last solids)))

(defun drop (v s)
  (setf (contents-of v) (remove s (contents-of v)))
  (setf (geom:user-data s) nil)
  s)

(defun clear (v)
  (dolist (s (contents-of v)) (setf (geom:user-data s) nil))
  (setf (contents-of v) nil)
  nil)

(defun contents (v) (contents-of v))

(defun shading (v mode) (setf (shading-of v) mode) nil)

;; Uploaded once per solid, and only when it is first drawn. The entry lives on
;; the solid rather than in a table keyed by it: `gethash` on an `eq` table is
;; not identity-keyed here and does not terminate for a key inside a scene graph
;; (../README.md, result 5).
(defun gpu-buffers (v s)
  (when (null (geom:user-data s))
    (let* ((m (geom:mesh s))
           (w (geom:wireframe s)))
      (setf (geom:user-data s)
            (list (metal:buffer (ctx-of v) m) (floor (length m) 6)
                  (metal:buffer (ctx-of v) w) (floor (length w) 3)))))
  (geom:user-data s))

;;; --- camera ----------------------------------------------------------------------

(defun camera (v &key azimuth elevation distance target)
  (when azimuth (setf (azimuth-of v) azimuth))
  (when elevation (setf (elevation-of v) elevation))
  (when distance (setf (distance-of v) distance))
  (when target (setf (target-of v) target))
  nil)

(defun fit (v)
  (let ((cs (contents-of v)))
    (when cs
      (let ((b (geom:bounds cs)))
        (setf (target-of v) (geom:bounds-center b))
        (setf (distance-of v)
              (max 100.0 (* 1.9 (linalg:norm (geom:bounds-extent b))))))))
  nil)

(defun update-camera (v)
  (let* ((ce (cos (elevation-of v))) (se (sin (elevation-of v)))
         (d (distance-of v))
         (eye (linalg:add (target-of v)
                          (vec3 (* d ce (cos (azimuth-of v)))
                                (* d ce (sin (azimuth-of v)))
                                (* d se))))
         (forward (unit (linalg:sub (target-of v) eye)))
         (right (unit (linalg:cross forward (vec3 0 0 1))))
         (up (linalg:cross right forward))
         (r (linalg:stack (list right up (linalg:mul forward -1.0))))
         (view (linalg:concatenate
                (list (linalg:concatenate
                       (list r (linalg:reshape
                                (linalg:mul (linalg:matmul r eye) -1.0) '(3 1)))
                       :axis 1)
                      (linalg:from-list '((0.0 0.0 0.0 1.0)) :element-type 'single-float))
                :axis 0))
         (far (* 8.0 (max d 100.0))))
    (setf (eye-of v) eye)
    (setf (basis-of v) (linalg:stack (list right up forward)))
    (setf (view-projection-of v)
          (linalg:matmul (perspective (/ (* 1.0 (width-of v)) (height-of v))
                                      (* 0.002 far) far)
                         view))))

;;; --- the fixed furniture: a ground grid and the world axes -------------------------

(defun grid (v &key (extent 600.0) (spacing 50.0))
  (let* ((e (float extent 1.0)) (sp (float spacing 1.0))
         (n (floor e sp))
         (lines (* 2 (+ (* 2 n) 1)))
         (pts (make-array (* lines 6) :element-type 'single-float))
         (k 0))
    (do ((i (- n) (+ i 1)))
        ((> i n) nil)
      (let ((x (* i sp)))
        (setf (aref pts k) x) (setf (aref pts (+ k 1)) (- e)) (setf (aref pts (+ k 2)) 0.0)
        (setf (aref pts (+ k 3)) x) (setf (aref pts (+ k 4)) e) (setf (aref pts (+ k 5)) 0.0)
        (setq k (+ k 6))
        (setf (aref pts k) (- e)) (setf (aref pts (+ k 1)) x) (setf (aref pts (+ k 2)) 0.0)
        (setf (aref pts (+ k 3)) e) (setf (aref pts (+ k 4)) x) (setf (aref pts (+ k 5)) 0.0)
        (setq k (+ k 6))))
    (setf (grid-buffer-of v) (metal:buffer (ctx-of v) pts))
    (setf (grid-points-of v) (* lines 2)))
  nil)

(defun grid-color (v rgb) (setf (grid-rgb-of v) rgb) nil)

(defun background (v rgba) (setf (gethash 'clear (ctx-of v)) rgba) nil)

;; Three segments, drawn with three tints -- one buffer, three draws of two.
(defun build-axes (v)
  (let ((pts (make-array 18 :element-type 'single-float)))
    (dotimes (i 18) (setf (aref pts i) 0.0))
    (setf (aref pts 3) 1.0) (setf (aref pts 10) 1.0) (setf (aref pts 17) 1.0)
    (setf (axes-buffer-of v) (metal:buffer (ctx-of v) pts)))
  nil)

;;; --- input ----------------------------------------------------------------------------

(defvar *dragging* nil) (defvar *panning* nil) (defvar *last* nil)

(defun view-point (event)
  (let ((p (objc:send event "locationInWindow")))
    (vec3 (first p) (second p) 0.0)))

(defun on-mouse-down (self event)
  self
  (setq *dragging* t)
  (setq *panning* (> (logand (objc:send event "modifierFlags") 131072) 0))
  (setq *last* (view-point event))
  nil)

(defun on-mouse-dragged (self event)
  self
  (when (and *dragging* *active*)
    (let* ((v *active*)
           (p (view-point event))
           (d (linalg:sub p *last*))
           (dx (aref d 0)) (dy (aref d 1)))
      (setq *last* p)
      (if *panning*
          (setf (target-of v)
                (linalg:sub (target-of v)
                            (linalg:add
                             (linalg:mul (linalg:row (basis-of v) 0)
                                         (* dx (distance-of v) 0.0016))
                             (linalg:mul (linalg:row (basis-of v) 1)
                                         (* dy (distance-of v) 0.0016)))))
          (progn
            (setf (azimuth-of v)
                  (- (azimuth-of v) (* 3.4 (/ dx (* 1.0 (height-of v))))))
            (let ((e (+ (elevation-of v) (* 2.6 (/ dy (* 1.0 (height-of v)))))))
              (setf (elevation-of v)
                    (cond ((< e -1.5) -1.5) ((> e 1.5) 1.5) (t e))))))))
  nil)

(defun on-mouse-up (self event) self event (setq *dragging* nil) nil)

(defun on-scroll (self event)
  self
  (when *active*
    (let* ((v *active*)
           (dy (objc:send event "scrollingDeltaY"))
           (k (if (objc:send event "hasPreciseScrollingDeltas") -0.004 -0.20))
           (d (* (distance-of v) (+ 1.0 (* dy k)))))
      (setf (distance-of v)
            (cond ((< d 10.0) 10.0) ((> d 200000.0) 200000.0) (t d)))))
  nil)

(defun on-first-mouse (self event) self event t)

;;; --- a frame -------------------------------------------------------------------------
;;;
;;; The per-frame CPU cost is one model matrix and one draw call per solid. No
;;; triangle is touched by Lisp here: the meshes went to the GPU when the solids
;;; were first drawn and have not moved since.

(defvar *identity4* nil)

(defun identity4 ()
  (when (null *identity4*)
    (setq *identity4* (linalg:eye 4 :element-type 'single-float)))
  *identity4*)

(defun draw-lines (v encoder buffer count model tint)
  (objc:send encoder "setRenderPipelineState:" (line-pipeline-of v))
  (objc:send encoder "setVertexBuffer:offset:atIndex:" buffer 0 0)
  (metal:uniform encoder 1 (linalg:transpose (view-projection-of v)))
  (metal:uniform encoder 2 model)
  (metal:uniform encoder 1 tint :stage :fragment)
  (objc:send encoder "drawPrimitives:vertexStart:vertexCount:"
             +line-primitive+ 0 count))

(defun render (v encoder)
  (when (frame-hook-of v) (funcall (frame-hook-of v)))
  (update-camera v)
  (let ((vp (linalg:transpose (view-projection-of v)))
        (eye (float4 (eye-of v) 0.0)))
    (objc:send encoder "setDepthStencilState:" (depth-of v))
    ;; the ground grid, in world coordinates
    (draw-lines v encoder (grid-buffer-of v) (grid-points-of v) (identity4)
                (float4 (grid-rgb-of v) 1.0))
    ;; the world axes, scaled by the current view distance so they stay legible
    (let ((s (* 0.16 (distance-of v)))
          (m (linalg:mul (linalg:eye 4 :element-type 'single-float) 1.0)))
      (dotimes (i 3) (setf (aref m i i) s))
      (setf (aref m 3 3) 1.0)
      (objc:send encoder "setRenderPipelineState:" (line-pipeline-of v))
      (objc:send encoder "setVertexBuffer:offset:atIndex:" (axes-buffer-of v) 0 0)
      (metal:uniform encoder 1 vp)
      (metal:uniform encoder 2 m)
      (dolist (row (list (list 0 (vec3 1.0 0.28 0.28)) (list 2 (vec3 0.30 1.0 0.40))
                         (list 4 (vec3 0.38 0.55 1.0))))
        (metal:uniform encoder 1 (float4 (second row) 1.0) :stage :fragment)
        (objc:send encoder "drawPrimitives:vertexStart:vertexCount:"
                   +line-primitive+ (first row) 2)))
    ;; the solids
    (dolist (s (contents-of v))
      (let ((bufs (gpu-buffers v s))
            (model (model-matrix s)))
        (when (or (eq (shading-of v) :solid) (eq (shading-of v) :both))
          (objc:send encoder "setRenderPipelineState:" (solid-pipeline-of v))
          (objc:send encoder "setVertexBuffer:offset:atIndex:" (first bufs) 0 0)
          (metal:uniform encoder 1 vp)
          (metal:uniform encoder 2 model)
          (metal:uniform encoder 0 eye :stage :fragment)
          (metal:uniform encoder 1 (float4 (geom:color-of s) 1.0) :stage :fragment)
          (objc:send encoder "drawPrimitives:vertexStart:vertexCount:"
                     metal:+triangle+ 0 (second bufs)))
        (when (or (eq (shading-of v) :wireframe) (eq (shading-of v) :both))
          (draw-lines v encoder (third bufs) (fourth bufs) model
                      (float4 (linalg:mul (geom:color-of s) 0.45) 1.0)))))))

(defun refresh (v)
  (metal:frame (ctx-of v) (lambda (encoder) (render v encoder)))
  nil)

(defun animate (v &optional hook)
  (when hook (setf (frame-hook-of v) hook))
  (metal:run (ctx-of v) (lambda (encoder) (render v encoder)))
  nil)

(defun wait (v) (appkit:wait (window-of v)))
