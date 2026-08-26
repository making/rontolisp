;;;; metal-cube.lisp -- a spinning, shaded cube on the GPU of a Mac.
;;;;
;;;; The AppKit twin of examples/browser/webgl-cube, and the full-pipeline
;;;; version of metal-triangle.lisp: a vertex buffer, a per-frame uniform, back
;;;; face culling and an animation loop, all through `objc:send` into Metal.
;;;;
;;;; Two things carry the numbers to the GPU, and both are `objc:data`:
;;;;
;;;;   - the mesh, once: 36 vertices of position + colour, built as a packed
;;;;     single-float array and handed over as an MTLBuffer. objc:data writes
;;;;     exactly the bytes write-sequence would -- little-endian float32, row
;;;;     major -- which is the layout `packed_float3` reads.
;;;;   - the model-view-projection matrix, every frame: 16 floats, small enough
;;;;     that Metal takes them inline (setVertexBytes:), so no buffer is
;;;;     allocated per frame.
;;;;
;;;; No depth buffer: a cube is CONVEX, so back-face culling alone leaves exactly
;;;; the visible faces, and a depth attachment would be one more texture to
;;;; create and resize for nothing. The face normals come from dfdx/dfdy of the
;;;; interpolated model position in the fragment shader, so the vertex format
;;;; stays position + colour.
;;;;
;;;; Run it (macOS, on any of the three backends that carry the binding):
;;;;
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/macos/metal-cube.lisp
;;;;   rontolisp examples/macos/metal-cube.lisp
;;;;   rontolisp examples/macos/metal-cube.lisp -o Cube.class --class-name Cube && java Cube

(require :metal "metal.lisp")

;;; --- the shaders --------------------------------------------------------------

(defvar *shaders*
  "
#include <metal_stdlib>
using namespace metal;

struct Vertex {
  packed_float3 position;
  packed_float3 color;
};

struct VertexOut {
  float4 position [[position]];
  float3 color;
  float3 model;
};

vertex VertexOut vertex_main(uint id [[vertex_id]],
                             const device Vertex *vertices [[buffer(0)]],
                             constant float4x4 &mvp [[buffer(1)]]) {
  VertexOut out;
  out.position = mvp * float4(vertices[id].position, 1.0);
  out.color = vertices[id].color;
  out.model = vertices[id].position;
  return out;
}

// The face normal is the cross product of the screen-space derivatives of the
// model position: constant across a flat face, so each face gets one shade.
fragment float4 fragment_main(VertexOut in [[stage_in]]) {
  float3 normal = normalize(cross(dfdx(in.model), dfdy(in.model)));
  float lambert = 0.55 + 0.45 * saturate(dot(normal, normalize(float3(0.4, 0.7, 1.0))));
  return float4(in.color * lambert, 1.0);
}
")

;;; --- the mesh -----------------------------------------------------------------
;;; One entry per face: four corners counter-clockwise seen from OUTSIDE, then
;;; the face colour. The winding is what back-face culling reads, so a corner out
;;; of order turns that face inside out and it disappears.

(defvar *faces*
  '(((-1 -1 1) (1 -1 1) (1 1 1) (-1 1 1) (0.95 0.26 0.21))       ; +z
    ((1 -1 -1) (-1 -1 -1) (-1 1 -1) (1 1 -1) (0.30 0.69 0.31))   ; -z
    ((1 -1 1) (1 -1 -1) (1 1 -1) (1 1 1) (0.13 0.59 0.95))       ; +x
    ((-1 -1 -1) (-1 -1 1) (-1 1 1) (-1 1 -1) (1.00 0.76 0.03))   ; -x
    ((-1 1 1) (1 1 1) (1 1 -1) (-1 1 -1) (0.61 0.15 0.69))       ; +y
    ((-1 -1 -1) (1 -1 -1) (1 -1 1) (-1 -1 1) (0.00 0.74 0.83)))) ; -y

;; Two triangles a face, six floats a vertex: 36 vertices, 216 floats.
(defun cube-vertices ()
  (let ((out '()))
    (dolist (face *faces* (nreverse out))
      (let ((a (first face))
            (b (second face))
            (c (third face))
            (d (fourth face))
            (color (nth 4 face)))
        (dolist (corner (list a b c a c d))
          (dolist (n corner) (push (float n 1.0) out))
          (dolist (n color) (push (float n 1.0) out)))))))

;;; --- 4x4 matrices, from the built-in linalg package --------------------------
;;;
;;; Nothing here is hand-written arithmetic: `linalg` is a built-in package, its
;;; results are PACKED float arrays, and objc:data takes a packed array of any
;;; rank -- so a matrix goes from linalg:matmul straight to the GPU with no
;;; conversion step at all. :element-type 'single-float makes them `#f` matrices
;;; (float32, which is what a Metal float4x4 holds) and every linalg transform
;;; preserves that width.
;;;
;;; Each one starts from an identity (or from zeros) and names only the entries
;;; that are not it, so the matrices multiply a COLUMN vector -- (mvp . p), the
;;; textbook convention. linalg stores a matrix ROW-major and Metal reads a
;;; float4x4 COLUMN-major, so one linalg:transpose on the way out is the whole
;;; bridge between the two.

(defun perspective (fovy aspect near far)
  (let ((m (linalg:zeros '(4 4) :element-type 'single-float))
        (f (/ 1.0 (tan (/ fovy 2.0))))
        (depth (- near far)))
    (setf (aref m 0 0) (/ f aspect))
    (setf (aref m 1 1) f)
    (setf (aref m 2 2) (/ far depth))
    (setf (aref m 2 3) (/ (* near far) depth))
    (setf (aref m 3 2) -1.0)
    m))

(defun translation (x y z)
  (let ((m (linalg:eye 4 :element-type 'single-float)))
    (setf (aref m 0 3) x)
    (setf (aref m 1 3) y)
    (setf (aref m 2 3) z)
    m))

(defun rotation-y (angle)
  (let ((m (linalg:eye 4 :element-type 'single-float))
        (c (cos angle))
        (s (sin angle)))
    (setf (aref m 0 0) c)
    (setf (aref m 0 2) s)
    (setf (aref m 2 0) (- s))
    (setf (aref m 2 2) c)
    m))

(defun rotation-x (angle)
  (let ((m (linalg:eye 4 :element-type 'single-float))
        (c (cos angle))
        (s (sin angle)))
    (setf (aref m 1 1) c)
    (setf (aref m 1 2) (- s))
    (setf (aref m 2 1) s)
    (setf (aref m 2 2) c)
    m))

;;; --- the window ---------------------------------------------------------------

(defvar *width* 520)
(defvar *height* 400)
(defvar *window*
  (appkit:window "Metal cube" :width *width* :height *height* :dark t))
(defvar *metal* (metal:attach *window* :clear '(0.05 0.06 0.09 1.0)))
(defvar *pipeline*
  (metal:pipeline *metal* (metal:library *metal* *shaders*) "vertex_main"
                  "fragment_main"))

;; The mesh crosses once and stays on the GPU; only the matrix moves per frame.
(defvar *mesh* (metal:buffer *metal* (cube-vertices)))
(defvar *vertex-count* (/ (length (cube-vertices)) 6))
(defvar *angle* 0.0)

(defun mvp ()
  (linalg:matmul (perspective 1.0 (/ (float *width* 1.0) *height*) 0.1 100.0)
                 (linalg:matmul (translation 0.0 0.0 -5.5)
                                (linalg:matmul (rotation-y *angle*)
                                               (rotation-x (* 0.6 *angle*))))))

(format t "device: ~a, ~a vertices~%"
        (objc:send (objc:send (metal:device *metal*) "name") "UTF8String")
        *vertex-count*)

(metal:run *metal*
           (lambda (encoder)
             (objc:send encoder "setRenderPipelineState:" *pipeline*)
             (objc:send encoder "setCullMode:" metal:+cull-back+)
             (objc:send encoder "setFrontFacingWinding:"
                        metal:+winding-counter-clockwise+)
             (objc:send encoder "setVertexBuffer:offset:atIndex:" *mesh* 0 0)
             (metal:uniform encoder 1 (linalg:transpose (mvp)))
             (objc:send encoder "drawPrimitives:vertexStart:vertexCount:"
                        metal:+triangle+ 0 *vertex-count*)
             (incf *angle* 0.02)))

(appkit:wait *window*)
