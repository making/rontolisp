;; The metal package: a Metal drawing surface on an appkit window -- the layer,
;; the device, the command queue, the render pass, the drawable, present and
;; commit, plus the shader, pipeline and buffer helpers every Metal program
;; writes identically. Written in rontolisp itself over the objc: verbs and
;; shipped inside the interpreter (see MetalLibrary.java): the interpreter loads
;; these definitions lazily on the first use of a metal: function, so a bare REPL
;; can draw with nothing required and nothing to copy.
;;
;; The macOS counterpart of examples/browser/webgl-common/gl.lisp: that file
;; imports a WebGL context from the page, this one builds a Metal one from the
;; objc: package. Metal is almost entirely an Objective-C API, so objc:send
;; reaches all of it -- there is no C entry point to bind and no library to ship.
;; The one C function Metal appears to need, MTLCreateSystemDefaultDevice(), is
;; avoidable: CAMetalLayer's preferredDevice is a PROPERTY and answers the same
;; device. That is the fact this whole file stands on.
;;
;; What does NOT live here is the shader source, the geometry and the draw calls:
;; those are the program. scene.lisp is one consumer (the 3-D viewer over geom);
;; examples/macos/metal-*.lisp are four more, and they use this surface DIRECTLY,
;; without geom or scene. Nothing here may become a private detail of the viewer.
;;
;; Portability constraints honored here (like linalg.lisp): do loops always
;; declare at least one variable; parameters are never assigned with setq.
;;
;; Threads: every objc:send hops to thread 0 on its own, so a sequence of them is
;; wrapped in ONE objc:on-main to pay the hop once rather than per selector
;; (.kb/objc.md).

;; --- the enumerations a drawing program names --------------------------------
;;
;; Metal's enums are plain integers on the wire. Only the members a PROGRAM
;; spells out are exported: the primitive it draws and the pipeline state it
;; configures. The pixel formats, the load/store actions, the blend factors and
;; the storage modes are attach / pipeline / frame's own business and stay
;; internal, so the public surface is the decisions a caller makes rather than
;; every constant this file happens to use.

(defconstant metal:+point+ 0) ; MTLPrimitiveTypePoint

(defconstant metal:+line+ 1) ; MTLPrimitiveTypeLine

(defconstant metal:+triangle+ 3) ; MTLPrimitiveTypeTriangle

(defconstant metal:+triangle-strip+ 4) ; MTLPrimitiveTypeTriangleStrip

(defconstant metal:+cull-none+ 0) ; MTLCullModeNone

(defconstant metal:+cull-front+ 1) ; MTLCullModeFront

(defconstant metal:+cull-back+ 2) ; MTLCullModeBack

(defconstant metal:+winding-clockwise+ 0) ; MTLWindingClockwise

(defconstant metal:+winding-counter-clockwise+ 1)

(defconstant metal:+compare-less+ 1) ; MTLCompareFunctionLess

(defconstant metal:+compare-always+ 7) ; MTLCompareFunctionAlways

(defconstant metal::+bgra8-unorm+ 80) ; MTLPixelFormatBGRA8Unorm

(defconstant metal::+depth32-float+ 252) ; MTLPixelFormatDepth32Float

(defconstant metal::+load-clear+ 2) ; MTLLoadActionClear

(defconstant metal::+store-store+ 1) ; MTLStoreActionStore

(defconstant metal::+store-dont-care+ 0) ; MTLStoreActionDontCare

(defconstant metal::+blend-add+ 0) ; MTLBlendOperationAdd

(defconstant metal::+factor-one+ 1) ; MTLBlendFactorOne

(defconstant metal::+storage-private+ 2) ; MTLStorageModePrivate

(defconstant metal::+usage-render-target+ 4)

;; --- the context -------------------------------------------------------------
;;
;; A class rather than the hash table this surface used while it was an example:
;; the type is public now, so its slots must be the ones it means to promise.
;; Only the three objects a program legitimately reaches for are readable; the
;; clear colour has a setter of its own and the rest is internal.

(defclass metal:context ()
  ((device :initarg :device :reader metal:device)
   (layer :initarg :layer :reader metal:layer)
   (queue :initarg :queue :reader metal:queue)
   (clear :initarg :clear :accessor metal::%clear)
   (scale :initarg :scale :reader metal::%scale)
   ;; whether attach was asked for a depth attachment, so resize knows to
   ;; rebuild one; the texture itself changes with the drawable size.
   (depth-wanted :initarg :depth-wanted :reader metal::%depth-wanted)
   (depth :initarg :depth :accessor metal::%depth)))

;; A depth attachment the size of the drawable. Nothing but a convex shape can be
;; drawn without one (metal-cube.lisp is that exception and asks for none): a
;; machine made of overlapping tubes and spheres needs the per-pixel depth test,
;; which costs one private texture the pass clears and every pipeline drawing
;; into it must declare.
(defun metal::%depth-texture (dev width height)
  (let ((desc
         (objc:send (objc:class "MTLTextureDescriptor")
                    "texture2DDescriptorWithPixelFormat:width:height:mipmapped:"
                    metal::+depth32-float+ (floor width) (floor height) nil)))
    (objc:send desc "setStorageMode:" metal::+storage-private+)
    (objc:send desc "setUsage:" metal::+usage-render-target+)
    (objc:send dev "newTextureWithDescriptor:" desc)))

;; Replaces WINDOW's content view backing with a CAMetalLayer and answers the
;; context every other function here takes. CLEAR is the (r g b a) the frame
;; starts from; SCALE is the backing-store factor, 2 for a Retina display.
;;
;; setLayer: before setWantsLayer: -- the other order makes AppKit build a layer
;; of its own first and the one handed over never becomes the backing store.
(defun metal:attach (window &key (clear '(0.05 0.06 0.09 1.0)) (scale 2) depth)
  (objc:on-main
   (lambda ()
     (let* ((view (objc:send window "contentView"))
            (bounds (objc:send view "frame"))
            (width (third bounds))
            (height (fourth bounds))
            (lyr (objc:send (objc:class "CAMetalLayer") "layer"))
            (dev (objc:send lyr "preferredDevice")))
       (unless dev (error "metal: this machine has no Metal device"))
       (objc:send lyr "setDevice:" dev)
       (objc:send lyr "setPixelFormat:" metal::+bgra8-unorm+)
       (objc:send lyr "setFramebufferOnly:" t)
       (objc:send lyr "setFrame:" (list 0.0 0.0 width height))
       (objc:send lyr "setDrawableSize:"
                  (list (* scale width) (* scale height)))
       (objc:send view "setLayer:" lyr)
       (objc:send view "setWantsLayer:" t)
       (make-instance 'metal:context
                      :device dev
                      :layer lyr
                      :queue (objc:send dev "newCommandQueue")
                      :clear clear
                      :scale scale
                      :depth-wanted (if depth t nil)
                      :depth (if depth
                                 (metal::%depth-texture dev (* scale width)
                                                        (* scale height))
                                 nil))))))

;; The colour a frame starts from, as an (r g b a) list. A viewer changes it
;; after the fact and the context owns it, so this is a function rather than a
;; slot the caller reaches into.
(defun metal:set-clear-color (ctx rgba)
  (setf (metal::%clear ctx) rgba)
  nil)

;; Follows the layer to a new content size, in POINTS: the layer's frame, its
;; drawable size (points times the backing scale) and, when attach was asked for
;; one, a fresh depth texture -- a resized window is a different drawable and the
;; old attachment no longer matches it. A caller that tracks a resizable window
;; calls this and then draws a frame.
(defun metal:resize (ctx width height)
  (objc:on-main
   (lambda ()
     (let* ((w (float width 1.0))
            (h (float height 1.0))
            (s (metal::%scale ctx))
            (lyr (metal:layer ctx)))
       (objc:send lyr "setFrame:" (list 0.0 0.0 w h))
       (objc:send lyr "setDrawableSize:" (list (* s w) (* s h)))
       (when (metal::%depth-wanted ctx)
         (setf (metal::%depth ctx)
               (metal::%depth-texture (metal:device ctx) (* s w) (* s h)))))))
  nil)

;; --- shaders -----------------------------------------------------------------

;; Compiles Metal Shading Language SOURCE at run time. The :error marker is what
;; makes a bad shader readable: without it the selector answers a bare nil, and
;; with it the binding raises the compiler's own diagnostics, line and caret
;; included.
(defun metal:library (ctx source)
  (objc:send (metal:device ctx) "newLibraryWithSource:options:error:"
             (objc:string source) nil :error))

;; A render pipeline over the two named functions of LIB, drawing into the
;; layer's pixel format.
(defun metal:pipeline (ctx lib vertex-name fragment-name &key blend)
  (objc:on-main
   (lambda ()
     (let* ((desc
             (objc:send
              (objc:send (objc:class "MTLRenderPipelineDescriptor") "alloc")
              "init"))
            (color
             (objc:send (objc:send desc "colorAttachments")
                        "objectAtIndexedSubscript:" 0)))
       (objc:send desc "setVertexFunction:"
        (objc:send lib "newFunctionWithName:" (objc:string vertex-name)))
       (objc:send desc "setFragmentFunction:"
        (objc:send lib "newFunctionWithName:" (objc:string fragment-name)))
       (objc:send color "setPixelFormat:" metal::+bgra8-unorm+)
       (when blend
         (objc:send color "setBlendingEnabled:" t)
         (objc:send color "setRgbBlendOperation:" metal::+blend-add+)
         (objc:send color "setAlphaBlendOperation:" metal::+blend-add+)
         (objc:send color "setSourceRGBBlendFactor:" metal::+factor-one+)
         (objc:send color "setSourceAlphaBlendFactor:" metal::+factor-one+)
         (objc:send color "setDestinationRGBBlendFactor:" metal::+factor-one+)
         (objc:send color "setDestinationAlphaBlendFactor:"
                    metal::+factor-one+))
       ;; a pipeline's attachment formats must match the pass it draws into, so
       ;; the depth format follows the context and is not the caller's
       (when (metal::%depth-wanted ctx)
         (objc:send desc "setDepthAttachmentPixelFormat:"
                    metal::+depth32-float+))
       (objc:send (metal:device ctx)
                  "newRenderPipelineStateWithDescriptor:error:" desc :error)))))

;; How a pipeline uses the depth attachment. :writes nil is the glow pass: it
;; READS the depth the solid pass wrote, so a sprite behind the arm is hidden,
;; but writes none of its own, so sprites do not occlude each other.
(defun metal:depth-state (ctx &key (writes t) (compare metal:+compare-less+))
  (objc:on-main
   (lambda ()
     (let ((desc
            (objc:send
             (objc:send (objc:class "MTLDepthStencilDescriptor") "alloc")
             "init")))
       (objc:send desc "setDepthCompareFunction:" compare)
       (objc:send desc "setDepthWriteEnabled:" writes)
       (objc:send (metal:device ctx) "newDepthStencilStateWithDescriptor:"
                  desc)))))

;; --- getting numbers onto the GPU --------------------------------------------
;;
;; objc:data turns a packed buffer into an NSData holding exactly the bytes
;; write-sequence would write -- little-endian float32 for a packed single-float
;; array -- which is the layout a Metal buffer wants. A geom:mesh IS such an
;; array, so a solid reaches the GPU with no conversion at all.

;; A packed single-float array of a list of numbers.
(defun metal:floats (values)
  (let ((out
         (make-array (length values)
                     :element-type 'single-float
                     :initial-element 0.0))
        (i 0))
    (dolist (v values out)
      (setf (aref out i) (float v 1.0))
      (setq i (+ i 1)))))

;; An MTLBuffer holding VALUES (a list, or a packed single-float array already).
(defun metal:buffer (ctx values)
  (let ((data (objc:data (if (listp values) (metal:floats values) values))))
    (objc:send (metal:device ctx) "newBufferWithBytes:length:options:"
               (objc:send data "bytes") (objc:send data "length") 0)))

;; An MTLBuffer of BYTES bytes in shared storage, whose contents the CPU
;; rewrites -- what metal:buffer is not. A program that re-tessellates its
;; geometry every frame allocates once here and copies per frame; the buffers it
;; keeps in flight are its own business (see metal-robot-arm.lisp).
(defun metal:shared-buffer (ctx bytes)
  (objc:send (metal:device ctx) "newBufferWithLength:options:" bytes 0))

;; Copies VALUES into BUFFER, which must be one of the above and at least as
;; long. NSData's getBytes:length: is the memcpy: objc:data lays the numbers out
;; and `contents` is where they land.
(defun metal:upload (buffer values)
  (let ((data (objc:data (if (listp values) (metal:floats values) values))))
    (objc:send data "getBytes:length:" (objc:send buffer "contents")
               (objc:send data "length"))))

;; Sets VALUES as the STAGE's bytes at buffer INDEX -- a per-frame uniform small
;; enough that Metal wants it inline rather than in a buffer. The vertex and
;; fragment stages number their buffers independently, so index 0 of one is not
;; index 0 of the other.
(defun metal:uniform (encoder index values &key (stage :vertex))
  (let ((data (objc:data (if (listp values) (metal:floats values) values))))
    (objc:send encoder
               (if (eq stage :fragment)
                   "setFragmentBytes:length:atIndex:"
                   "setVertexBytes:length:atIndex:") (objc:send data "bytes")
               (objc:send data "length") index)))

;; --- a frame -----------------------------------------------------------------

;; One frame: take the next drawable, clear it, call FN with the render command
;; encoder so the program can set its pipeline and draw, then present. FN runs on
;; thread 0, inside the same hop as everything around it.
;;
;; nextDrawable answers nil when the layer has none free (the window is off
;; screen, or the display is ahead of us); the frame is then skipped, which is
;; what a dropped frame is.
(defun metal:frame (ctx fn)
  (objc:on-main
   (lambda ()
     (let ((drawable (objc:send (metal:layer ctx) "nextDrawable")))
       (when drawable
         (let* ((pass
                 (objc:send (objc:class "MTLRenderPassDescriptor")
                            "renderPassDescriptor"))
                (color
                 (objc:send (objc:send pass "colorAttachments")
                            "objectAtIndexedSubscript:" 0))
                (commands (objc:send (metal:queue ctx) "commandBuffer")))
           (objc:send color "setTexture:" (objc:send drawable "texture"))
           (objc:send color "setLoadAction:" metal::+load-clear+)
           (objc:send color "setStoreAction:" metal::+store-store+)
           (objc:send color "setClearColor:" (metal::%clear ctx))
           (let ((zbuf (metal::%depth ctx)))
             (when zbuf
               (let ((z (objc:send pass "depthAttachment")))
                 (objc:send z "setTexture:" zbuf)
                 (objc:send z "setLoadAction:" metal::+load-clear+)
                 (objc:send z "setClearDepth:" 1.0)
                 ;; nothing reads the depth after the frame, so it never leaves
                 ;; tile memory
                 (objc:send z "setStoreAction:" metal::+store-dont-care+))))
           (let ((encoder
                  (objc:send commands "renderCommandEncoderWithDescriptor:"
                             pass)))
             (funcall fn encoder)
             (objc:send encoder "endEncoding"))
           (objc:send commands "presentDrawable:" drawable)
           (objc:send commands "commit")))))))

;; Draws FN on a timer. The clock is appkit:timer, an NSTimer on thread 0, so the
;; frame runs where AppKit and Metal both want it.
(defun metal:run (ctx fn &key (fps 60))
  (metal:frame ctx fn)
  ;; The tick answers t whatever the frame did: appkit:timer reads a nil answer
  ;; as "stop the clock", and a frame answers nil both when it draws (the last
  ;; thing it sends is a void selector) and when it is dropped.
  (appkit:timer (/ 1.0 fps)
                (lambda ()
                  (metal:frame ctx fn)
                  t)))
