;;;; metal-triangle.lisp -- the WebGL hello world, on the GPU of a Mac.
;;;;
;;;; The AppKit twin of examples/browser/webgl-triangle: one colored triangle,
;;;; and nothing more than it takes to draw one. The browser version reaches a
;;;; WebGL context through ten imported host functions; this one reaches Metal
;;;; through `objc:send`, because Metal is an Objective-C API and the `objc`
;;;; package is a generic binding to Objective-C -- no host, no shim, no
;;;; dependency.
;;;;
;;;; Like the browser version it uses NO vertex buffer at all: the vertex shader
;;;; looks its corner positions and colors up by vertex_id, so the only GPU work
;;;; is compiling two shader functions, clearing and one draw call. The shader is
;;;; a Lisp string compiled at run time -- there is no build step here either.
;;;;
;;;; Run it (macOS, on any of the three backends that carry the binding):
;;;;
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/macos/metal-triangle.lisp
;;;;   rontolisp examples/macos/metal-triangle.lisp
;;;;   rontolisp examples/macos/metal-triangle.lisp -o Triangle.class --class-name Triangle && java Triangle
;;;;
;;;; When you outgrow this, metal-cube.lisp is the full-pipeline version: a
;;;; vertex buffer, a per-frame uniform, back-face culling and an animation loop.

;;; --- the shaders --------------------------------------------------------------
;;; Metal Shading Language, compiled by the Metal compiler inside this process
;;; when metal:library runs. A syntax error here is an ordinary Lisp condition
;;; carrying the compiler's message: the :error out-parameter of
;;; newLibraryWithSource:options:error: is what makes that possible.

(defvar *shaders*
  "
#include <metal_stdlib>
using namespace metal;

struct VertexOut {
  float4 position [[position]];
  float4 color;
};

// An attributeless draw: three corners and three colors, looked up by index.
vertex VertexOut vertex_main(uint id [[vertex_id]]) {
  float2 corners[3] = { float2(0.0, 0.6), float2(-0.6, -0.5), float2(0.6, -0.5) };
  float3 colors[3] = { float3(1.0, 0.2, 0.2), float3(0.2, 1.0, 0.3), float3(0.25, 0.5, 1.0) };
  VertexOut out;
  out.position = float4(corners[id], 0.0, 1.0);
  out.color = float4(colors[id], 1.0);
  return out;
}

// The rasterizer interpolates `color` across the triangle, which is where the
// gradient comes from -- nothing here computes it.
fragment float4 fragment_main(VertexOut in [[stage_in]]) {
  return in.color;
}
")

;;; --- the window ---------------------------------------------------------------

(defvar *window*
  (appkit:window "Metal triangle" :width 480 :height 360 :dark t))
(defvar *metal* (metal:attach *window* :clear '(0.05 0.05 0.09 1.0)))
(defvar *pipeline*
  (metal:pipeline *metal* (metal:library *metal* *shaders*) "vertex_main"
                  "fragment_main"))

(format t "device: ~a~%"
        (objc:send (objc:send (metal:device *metal*) "name") "UTF8String"))

;;; --- one frame ----------------------------------------------------------------
;;; Nothing changes between frames, so a single frame is the whole program: the
;;; drawable keeps what was drawn into it until the window closes.

(metal:frame *metal*
             (lambda (encoder)
               (objc:send encoder "setRenderPipelineState:" *pipeline*)
               (objc:send encoder "drawPrimitives:vertexStart:vertexCount:"
                          metal:+triangle+ 0 3)))

(appkit:wait *window*)
