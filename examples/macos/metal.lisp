;;;; metal.lisp -- a Metal drawing surface in an appkit window, for rontolisp
;;;; examples.
;;;;
;;;; The macOS counterpart of examples/browser/webgl-common/gl.lisp: that file
;;;; imports a WebGL context from the page, this one builds a Metal one from the
;;;; `objc` package. Metal is almost entirely an Objective-C API, so `objc:send`
;;;; reaches all of it -- there is no C entry point to bind and no library to
;;;; ship. (OpenGL is the opposite and is out of reach for good: glClear and
;;;; friends are plain C functions, and deprecated on macOS besides.)
;;;;
;;;; The one C function Metal appears to need, MTLCreateSystemDefaultDevice(),
;;;; is avoidable: CAMetalLayer's `preferredDevice` is a PROPERTY and answers the
;;;; same device. That is the fact this whole file stands on.
;;;;
;;;; What lives here is the boilerplate every Metal program writes identically --
;;;; the layer on the window's content view, the drawable, the render pass, the
;;;; command buffer, present and commit. What does NOT live here is the shader,
;;;; the geometry and the draw calls: those are the program, and an example that
;;;; hid them behind a helper would show nothing.
;;;;
;;;;   (require :metal "metal.lisp")
;;;;
;;;; API:
;;;;   (metal:attach window &key clear scale)  -> a context on the window
;;;;   (metal:device ctx) (metal:layer ctx)    -> the MTLDevice / CAMetalLayer
;;;;   (metal:library ctx source)              -> compile MSL source (signals)
;;;;   (metal:pipeline ctx lib vfn ffn)        -> an MTLRenderPipelineState
;;;;   (metal:floats list)                     -> a packed single-float array
;;;;   (metal:buffer ctx floats)               -> an MTLBuffer holding them
;;;;   (metal:uniform encoder index floats)    -> set them as vertex bytes
;;;;   (metal:frame ctx fn)                    -> draw one frame; fn gets the
;;;;                                              render command encoder
;;;;   (metal:run ctx fn &key fps)             -> call metal:frame on a timer
;;;;
;;;; Reachable on macOS with a display -- under `java -jar`, in the `rontolisp`
;;;; native binary and in a compiled JVM class alike; only WASM refuses, having
;;;; no foreign function API (doc/en/guides/objc-appkit.md).

(provide :metal)

(defpackage metal
  (:use cl)
  (:export attach device layer queue library pipeline floats buffer uniform
           frame run +triangle+ +triangle-strip+ +cull-none+ +cull-front+
           +cull-back+ +winding-clockwise+ +winding-counter-clockwise+))

(in-package metal)

;;; --- the enumerations the examples name --------------------------------------
;;; Metal's enums are plain integers on the wire; these are the handful a
;;; drawing program spells out.

(defconstant +bgra8-unorm+ 80)      ; MTLPixelFormatBGRA8Unorm
(defconstant +load-clear+ 2)        ; MTLLoadActionClear
(defconstant +store-store+ 1)       ; MTLStoreActionStore
(defconstant +triangle+ 3)          ; MTLPrimitiveTypeTriangle
(defconstant +triangle-strip+ 4)    ; MTLPrimitiveTypeTriangleStrip
(defconstant +cull-none+ 0)         ; MTLCullModeNone
(defconstant +cull-front+ 1)        ; MTLCullModeFront
(defconstant +cull-back+ 2)         ; MTLCullModeBack
(defconstant +winding-clockwise+ 0) ; MTLWindingClockwise
(defconstant +winding-counter-clockwise+ 1)

;;; --- the surface --------------------------------------------------------------

;; Replaces WINDOW's content view backing with a CAMetalLayer and answers the
;; context every other function here takes. CLEAR is the (r g b a) the frame
;; starts from; SCALE is the backing-store factor, 2 for a Retina display.
;;
;; setLayer: before setWantsLayer: -- the other order makes AppKit build a layer
;; of its own first and the one handed over never becomes the backing store.
(defun attach (window &key (clear '(0.05 0.06 0.09 1.0)) (scale 2))
  (let ((ctx (make-hash-table :test 'eq)))
    (objc:on-main
     (lambda ()
       (let* ((view (objc:send window "contentView"))
              (frame (objc:send view "frame"))
              (width (third frame))
              (height (fourth frame))
              (lyr (objc:send (objc:class "CAMetalLayer") "layer"))
              (dev (objc:send lyr "preferredDevice")))
         (unless dev (error "metal: this machine has no Metal device"))
         (objc:send lyr "setDevice:" dev)
         (objc:send lyr "setPixelFormat:" +bgra8-unorm+)
         (objc:send lyr "setFramebufferOnly:" t)
         (objc:send lyr "setFrame:" (list 0.0 0.0 width height))
         (objc:send lyr "setDrawableSize:"
                    (list (* scale width) (* scale height)))
         (objc:send view "setLayer:" lyr)
         (objc:send view "setWantsLayer:" t)
         (setf (gethash 'layer ctx) lyr)
         (setf (gethash 'device ctx) dev)
         (setf (gethash 'queue ctx) (objc:send dev "newCommandQueue"))
         (setf (gethash 'clear ctx) clear))))
    ctx))

(defun device (ctx) (gethash 'device ctx))

(defun layer (ctx) (gethash 'layer ctx))

(defun queue (ctx) (gethash 'queue ctx))

;;; --- shaders ------------------------------------------------------------------

;; Compiles Metal Shading Language SOURCE at run time. The :error marker is what
;; makes a bad shader readable: without it the selector answers a bare nil, and
;; with it the binding raises the compiler's own diagnostics, line and caret
;; included.
(defun library (ctx source)
  (objc:send (device ctx) "newLibraryWithSource:options:error:"
             (objc:string source) nil :error))

;; A render pipeline over the two named functions of LIB, drawing into the
;; layer's pixel format.
(defun pipeline (ctx lib vertex-name fragment-name)
  (objc:on-main
   (lambda ()
     (let ((desc
            (objc:send
             (objc:send (objc:class "MTLRenderPipelineDescriptor") "alloc")
             "init")))
       (objc:send desc "setVertexFunction:"
        (objc:send lib "newFunctionWithName:" (objc:string vertex-name)))
       (objc:send desc "setFragmentFunction:"
        (objc:send lib "newFunctionWithName:" (objc:string fragment-name)))
       (objc:send (objc:send (objc:send desc "colorAttachments")
                             "objectAtIndexedSubscript:" 0) "setPixelFormat:"
                  +bgra8-unorm+)
       (objc:send (device ctx) "newRenderPipelineStateWithDescriptor:error:"
                  desc :error)))))

;;; --- getting numbers onto the GPU ---------------------------------------------
;;;
;;; objc:data turns a packed buffer into an NSData holding exactly the bytes
;;; write-sequence would write -- little-endian float32 for a packed
;;; single-float array -- which is the layout a Metal buffer wants.

;; A packed single-float array of a list of numbers.
(defun floats (values)
  (let* ((n (length values))
         (out (make-array n :element-type 'single-float))
         (i 0))
    (dolist (v values out)
      (setf (aref out i) (float v 1.0))
      (incf i))))

;; An MTLBuffer holding VALUES (a list, or a packed single-float array already).
(defun buffer (ctx values)
  (let ((data (objc:data (if (listp values) (floats values) values))))
    (objc:send (device ctx) "newBufferWithBytes:length:options:"
               (objc:send data "bytes") (objc:send data "length") 0)))

;; Sets VALUES as the vertex-stage bytes at buffer INDEX -- a per-frame uniform
;; small enough that Metal wants it inline rather than in a buffer.
(defun uniform (encoder index values)
  (let ((data (objc:data (if (listp values) (floats values) values))))
    (objc:send encoder "setVertexBytes:length:atIndex:" (objc:send data "bytes")
               (objc:send data "length") index)))

;;; --- a frame ------------------------------------------------------------------

;; One frame: take the next drawable, clear it, call FN with the render command
;; encoder so the program can set its pipeline and draw, then present. FN runs on
;; thread 0, inside the same hop as everything around it.
;;
;; nextDrawable answers nil when the layer has none free (the window is off
;; screen, or the display is ahead of us); the frame is then skipped, which is
;; what a dropped frame is.
(defun frame (ctx fn)
  (objc:on-main
   (lambda ()
     (let ((drawable (objc:send (layer ctx) "nextDrawable")))
       (when drawable
         (let* ((pass
                 (objc:send (objc:class "MTLRenderPassDescriptor")
                            "renderPassDescriptor"))
                (color
                 (objc:send (objc:send pass "colorAttachments")
                            "objectAtIndexedSubscript:" 0))
                (commands (objc:send (queue ctx) "commandBuffer")))
           (objc:send color "setTexture:" (objc:send drawable "texture"))
           (objc:send color "setLoadAction:" +load-clear+)
           (objc:send color "setStoreAction:" +store-store+)
           (objc:send color "setClearColor:" (gethash 'clear ctx))
           (let ((encoder
                  (objc:send commands "renderCommandEncoderWithDescriptor:"
                             pass)))
             (funcall fn encoder)
             (objc:send encoder "endEncoding"))
           (objc:send commands "presentDrawable:" drawable)
           (objc:send commands "commit")))))))

;; Draws FN on a timer. The clock is appkit:timer, an NSTimer on thread 0, so the
;; frame runs where AppKit and Metal both want it.
(defun run (ctx fn &key (fps 60))
  (frame ctx fn)
  ;; The tick answers t whatever the frame did: appkit:timer reads a nil answer as
  ;; "stop the clock", and a frame answers nil both when it draws (the last thing
  ;; it sends is a void selector) and when it is dropped.
  (appkit:timer (/ 1.0 fps)
                (lambda ()
                  (frame ctx fn)
                  t)))
