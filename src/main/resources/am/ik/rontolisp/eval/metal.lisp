;; The metal package: a Metal drawing surface on an appkit window -- the layer,
;; the device, the command queue, the render pass, the drawable, present and
;; commit, plus the shader, pipeline and buffer helpers every Metal program
;; writes identically -- or on no window at all (metal:offscreen /
;; metal:pixels), which is the same surface drawing into a texture the CPU can
;; read. Written in rontolisp itself over the objc package and
;; shipped inside the interpreter (see MetalLibrary.java): the interpreter loads
;; these definitions lazily on the first use of a metal: function, so a bare REPL
;; can draw with nothing required and nothing to copy.
;;
;; The macOS counterpart of examples/browser/webgl-common/gl.lisp: that file
;; imports a WebGL context from the page, this one builds a Metal one from the
;; objc: package. Metal is almost entirely an Objective-C API, so objc:invoke
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
;; Threads: every objc:invoke hops to thread 0 on its own, so a sequence of them is
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

(defconstant metal::+storage-shared+ 0) ; MTLStorageModeShared

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
   (depth :initarg :depth :accessor metal::%depth)
   ;; The colour texture a frame draws into when there is no layer -- what
   ;; metal:offscreen builds and nothing else sets. nil here IS the question
   ;; "is this context on screen?", which metal:frame asks once per frame.
   (target :initarg :target :initform nil :accessor metal::%target)
   (target-size :initarg :target-size
                :initform nil
                :accessor metal::%target-size)))

;; A depth attachment the size of the drawable. Nothing but a convex shape can be
;; drawn without one (metal-cube.lisp is that exception and asks for none): a
;; machine made of overlapping tubes and spheres needs the per-pixel depth test,
;; which costs one private texture the pass clears and every pipeline drawing
;; into it must declare.
(defun metal::%depth-texture (dev width height)
  (let ((desc
         (objc:invoke "MTLTextureDescriptor"
          "texture2DDescriptorWithPixelFormat:width:height:mipmapped:"
          metal::+depth32-float+ (floor width) (floor height) nil)))
    (objc:invoke desc "setStorageMode:" metal::+storage-private+)
    (objc:invoke desc "setUsage:" metal::+usage-render-target+)
    (objc:invoke dev "newTextureWithDescriptor:" desc)))

;; The colour attachment an offscreen context draws into: the layer's pixel
;; format, so the pipelines are the SAME pipelines a window gets, and SHARED
;; storage, so the CPU can read the pixels back without a blit.
(defun metal::%color-texture (dev width height)
  (let ((desc
         (objc:invoke "MTLTextureDescriptor"
          "texture2DDescriptorWithPixelFormat:width:height:mipmapped:"
          metal::+bgra8-unorm+ (floor width) (floor height) nil)))
    (objc:invoke desc "setStorageMode:" metal::+storage-shared+)
    (objc:invoke desc "setUsage:" metal::+usage-render-target+)
    (objc:invoke dev "newTextureWithDescriptor:" desc)))

;; Replaces WINDOW's content view backing with a CAMetalLayer and answers the
;; context every other function here takes. CLEAR is the (r g b a) the frame
;; starts from; SCALE is the backing-store factor, 2 for a Retina display.
;;
;; setLayer: before setWantsLayer: -- the other order makes AppKit build a layer
;; of its own first and the one handed over never becomes the backing store.
(defun metal:attach (window &key (clear '(0.05 0.06 0.09 1.0)) (scale 2) depth)
  (objc:on-main
   (lambda ()
     (let* ((view (objc:invoke window "contentView"))
            (bounds (objc:invoke view "frame"))
            (width (aref bounds 2))
            (height (aref bounds 3))
            (lyr (objc:invoke "CAMetalLayer" "layer"))
            (dev (objc:invoke lyr "preferredDevice")))
       (unless dev (error "metal: this machine has no Metal device"))
       (objc:invoke lyr "setDevice:" dev)
       (objc:invoke lyr "setPixelFormat:" metal::+bgra8-unorm+)
       (objc:invoke lyr "setFramebufferOnly:" t)
       (objc:invoke lyr "setFrame:" (vector 0.0 0.0 width height))
       (objc:invoke lyr "setDrawableSize:"
                    (vector (* scale width) (* scale height)))
       (objc:invoke view "setLayer:" lyr)
       (objc:invoke view "setWantsLayer:" t)
       (make-instance 'metal:context
                      :device dev
                      :layer lyr
                      :queue (objc:invoke dev "newCommandQueue")
                      :clear clear
                      :scale scale
                      :depth-wanted (if depth t nil)
                      :depth (if depth
                                 (metal::%depth-texture dev (* scale width)
                                                        (* scale height))
                                 nil))))))

;; A context with no window at all: the frame is drawn into a texture of its own
;; and metal:pixels reads it back. WIDTH and HEIGHT are PIXELS (there is no
;; backing scale without a screen to have one), and everything else -- library,
;; pipeline, depth-state, buffer, uniform, frame -- is the same function a
;; layer-backed context takes, which is the whole point: what this renders is
;; what the window renders, not a second path that resembles it.
;;
;; The device comes from a throwaway CAMetalLayer's preferredDevice, exactly as
;; attach's does; no display is needed for that property to answer (.kb/objc.md).
(defun metal:offscreen
    (&key (width 512) (height 512) (clear '(0.05 0.06 0.09 1.0)) depth)
  (objc:on-main
   (lambda ()
     (let* ((w (floor width))
            (h (floor height))
            (lyr (objc:invoke "CAMetalLayer" "layer"))
            (dev (objc:invoke lyr "preferredDevice")))
       (unless dev (error "metal: this machine has no Metal device"))
       (make-instance 'metal:context
                      :device dev
                      :layer nil
                      :queue (objc:invoke dev "newCommandQueue")
                      :clear clear
                      :scale 1
                      :depth-wanted (if depth t nil)
                      :depth (if depth (metal::%depth-texture dev w h) nil)
                      :target (metal::%color-texture dev w h)
                      :target-size (list w h))))))

;; The last frame an offscreen context drew, as a packed (unsigned-byte 8)
;; vector of width*height*4 bytes in the texture's own order: BGRA, row 0 at the
;; TOP, tightly packed. Not converted to RGBA -- the format is the layer's
;; format, and a reader that has to know one order can as well know the real one.
(defun metal:pixels (ctx)
  (let ((tex (metal::%target ctx)))
    (unless tex
      (error "metal:pixels: this context draws into a window, not a texture"))
    (let* ((size (metal::%target-size ctx))
           (w (first size))
           (h (second size))
           (store (objc:invoke "NSMutableData" "dataWithLength:" (* w h 4))))
      (objc:on-main
       (lambda ()
         (objc:invoke tex "getBytes:bytesPerRow:fromRegion:mipmapLevel:"
                      (objc:invoke store "mutableBytes") (* w 4)
                      (vector 0 0 0 w h 1) 0)))
      (objc:bytes store))))

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
       ;; an offscreen context has no layer to follow: the target texture IS the
       ;; drawable, and it is rebuilt at the new size
       (if lyr
           (progn
             (objc:invoke lyr "setFrame:" (vector 0.0 0.0 w h))
             (objc:invoke lyr "setDrawableSize:" (vector (* s w) (* s h))))
           (progn
             (setf (metal::%target ctx)
                   (metal::%color-texture (metal:device ctx) (* s w) (* s h)))
             (setf (metal::%target-size ctx)
                   (list (floor (* s w)) (floor (* s h))))))
       (when (metal::%depth-wanted ctx)
         (setf (metal::%depth ctx)
               (metal::%depth-texture (metal:device ctx) (* s w) (* s h)))))))
  nil)

;; --- shaders -----------------------------------------------------------------

;; Compiles Metal Shading Language SOURCE at run time. invoke-with-error is what
;; makes a bad shader readable: without the NSError the selector answers a bare
;; nil, and with it the failure is an objc:ns-error carrying the compiler's own
;; diagnostics, line and caret included.
(defun metal:library (ctx source)
  (objc:invoke-with-error (metal:device ctx)
                          "newLibraryWithSource:options:error:" source nil))

;; A render pipeline over the two named functions of LIB, drawing into the
;; layer's pixel format.
(defun metal:pipeline (ctx lib vertex-name fragment-name &key blend)
  (objc:on-main
   (lambda ()
     (let* ((desc
             (objc:invoke (objc:invoke "MTLRenderPipelineDescriptor" "alloc")
                          "init"))
            (color
             (objc:invoke (objc:invoke desc "colorAttachments")
                          "objectAtIndexedSubscript:" 0)))
       (objc:invoke desc "setVertexFunction:"
                    (objc:invoke lib "newFunctionWithName:" vertex-name))
       (objc:invoke desc "setFragmentFunction:"
                    (objc:invoke lib "newFunctionWithName:" fragment-name))
       (objc:invoke color "setPixelFormat:" metal::+bgra8-unorm+)
       (when blend
         (objc:invoke color "setBlendingEnabled:" t)
         (objc:invoke color "setRgbBlendOperation:" metal::+blend-add+)
         (objc:invoke color "setAlphaBlendOperation:" metal::+blend-add+)
         (objc:invoke color "setSourceRGBBlendFactor:" metal::+factor-one+)
         (objc:invoke color "setSourceAlphaBlendFactor:" metal::+factor-one+)
         (objc:invoke color "setDestinationRGBBlendFactor:" metal::+factor-one+)
         (objc:invoke color "setDestinationAlphaBlendFactor:"
                      metal::+factor-one+))
       ;; a pipeline's attachment formats must match the pass it draws into, so
       ;; the depth format follows the context and is not the caller's
       (when (metal::%depth-wanted ctx)
         (objc:invoke desc "setDepthAttachmentPixelFormat:"
                      metal::+depth32-float+))
       (objc:invoke-with-error (metal:device ctx)
                               "newRenderPipelineStateWithDescriptor:error:"
                               desc)))))

;; How a pipeline uses the depth attachment. :writes nil is the glow pass: it
;; READS the depth the solid pass wrote, so a sprite behind the arm is hidden,
;; but writes none of its own, so sprites do not occlude each other.
(defun metal:depth-state (ctx &key (writes t) (compare metal:+compare-less+))
  (objc:on-main
   (lambda ()
     (let ((desc
            (objc:invoke (objc:invoke "MTLDepthStencilDescriptor" "alloc")
                         "init")))
       (objc:invoke desc "setDepthCompareFunction:" compare)
       (objc:invoke desc "setDepthWriteEnabled:" writes)
       (objc:invoke (metal:device ctx) "newDepthStencilStateWithDescriptor:"
                    desc)))))

;; --- getting numbers onto the GPU --------------------------------------------
;;
;; objc:data turns a packed buffer into an NSData holding exactly the bytes
;; write-sequence would write -- little-endian float32 for a packed single-float
;; array -- which is the layout a Metal buffer wants. A geom:mesh IS such an
;; array, so a solid reaches the GPU with no conversion at all. The per-frame
;; paths (upload, uniform) skip the NSData and copy the same bytes straight into
;; foreign memory through the byte primitive objc:data is written over
;; (objc::%octets, objc::%write-octets): one send a call instead of five.

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
    (objc:invoke (metal:device ctx) "newBufferWithBytes:length:options:"
                 (objc:invoke data "bytes") (objc:invoke data "length") 0)))

;; An MTLBuffer of BYTES bytes in shared storage, whose contents the CPU
;; rewrites -- what metal:buffer is not. A program that re-tessellates its
;; geometry every frame allocates once here and copies per frame; the buffers it
;; keeps in flight are its own business (see metal-robot-arm.lisp).
(defun metal:shared-buffer (ctx bytes)
  (objc:invoke (metal:device ctx) "newBufferWithLength:options:" bytes 0))

;; The bytes of VALUES (a list, or a packed buffer already), as objc:data lays
;; them out.
(defun metal::%octets (values)
  (objc::%octets (if (listp values) (metal:floats values) values)))

;; Copies VALUES into BUFFER, which must be one of the above and at least as
;; long: the bytes land at its `contents`.
(defun metal:upload (buffer values)
  (let ((octets (metal::%octets values)))
    (when (> (length octets) (objc:invoke buffer "length"))
      (error "metal:upload: ~a bytes do not fit a buffer of ~a" (length octets)
             (objc:invoke buffer "length")))
    (objc::%write-octets (fli:pointer-address (objc:invoke buffer "contents"))
                         octets)
    nil))

;; A block of foreign memory a uniform's bytes are staged in for the one send that
;; copies them (setVertexBytes: and setFragmentBytes: copy what they are given).
;; Only ever used inside a frame, which runs on thread 0, so one block serves
;; every uniform; it grows to the largest one asked for.
(defvar metal::*scratch* nil)

(defvar metal::*scratch-address* 0)

(defun metal::%stage (octets)
  (when (or (null metal::*scratch*)
            (> (length octets) (objc:invoke metal::*scratch* "length")))
    (setq metal::*scratch*
     (objc:invoke "NSMutableData" "dataWithLength:" (max 256 (length octets))))
    (setq metal::*scratch-address*
          (fli:pointer-address (objc:invoke metal::*scratch* "mutableBytes"))))
  (objc::%write-octets metal::*scratch-address* octets)
  metal::*scratch-address*)

;; Sets VALUES as the STAGE's bytes at buffer INDEX -- a per-frame uniform small
;; enough that Metal wants it inline rather than in a buffer. The vertex and
;; fragment stages number their buffers independently, so index 0 of one is not
;; index 0 of the other.
(defun metal:uniform (encoder index values &key (stage :vertex))
  (let ((octets (metal::%octets values)))
    (objc:invoke encoder
                 (if (eq stage :fragment)
                     "setFragmentBytes:length:atIndex:"
                     "setVertexBytes:length:atIndex:") (metal::%stage octets)
                 (length octets) index)))

;; --- a frame -----------------------------------------------------------------

;; One frame: take the texture to draw into, clear it, call FN with the render
;; command encoder so the program can set its pipeline and draw, then present.
;; FN runs on thread 0, inside the same hop as everything around it.
;;
;; The texture is the next drawable's on a layer-backed context and the context's
;; own on an offscreen one -- ONE encoding path, so a test that reads the pixels
;; back is reading the window's frame. nextDrawable answers nil when the layer
;; has none free (the window is off screen, or the display is ahead of us); the
;; frame is then skipped, which is what a dropped frame is. An offscreen frame is
;; never dropped and is WAITED for instead of presented, since the caller reads
;; its bytes on the next line.
(defun metal:frame (ctx fn)
  (objc:on-main
   (lambda ()
     (let* ((offscreen (metal::%target ctx))
            (drawable
             (if offscreen nil (objc:invoke (metal:layer ctx) "nextDrawable")))
            (texture
             (if offscreen
                 offscreen
                 (if drawable (objc:invoke drawable "texture") nil))))
       (when texture
         (let* ((pass
                 (objc:invoke "MTLRenderPassDescriptor" "renderPassDescriptor"))
                (color
                 (objc:invoke (objc:invoke pass "colorAttachments")
                              "objectAtIndexedSubscript:" 0))
                (commands (objc:invoke (metal:queue ctx) "commandBuffer")))
           (objc:invoke color "setTexture:" texture)
           (objc:invoke color "setLoadAction:" metal::+load-clear+)
           (objc:invoke color "setStoreAction:" metal::+store-store+)
           (objc:invoke color "setClearColor:"
                        (coerce (metal::%clear ctx) 'simple-vector))
           (let ((zbuf (metal::%depth ctx)))
             (when zbuf
               (let ((z (objc:invoke pass "depthAttachment")))
                 (objc:invoke z "setTexture:" zbuf)
                 (objc:invoke z "setLoadAction:" metal::+load-clear+)
                 (objc:invoke z "setClearDepth:" 1.0)
                 ;; nothing reads the depth after the frame, so it never leaves
                 ;; tile memory
                 (objc:invoke z "setStoreAction:" metal::+store-dont-care+))))
           ;; The frame is closed out even when FN signals. An encoder released
           ;; without endEncoding is a Metal ASSERTION, and an assertion is an
           ;; abort() -- it kills the process from under the callback guard that
           ;; caught the Lisp error a moment earlier (.kb/objc.md, "Metal"). So the cleanup ends the
           ;; encoder, presents whatever was
           ;; drawn and commits; a half-drawn frame is a picture, an aborted
           ;; process is not.
           (let ((encoder
                  (objc:invoke commands "renderCommandEncoderWithDescriptor:"
                               pass)))
             (unwind-protect (funcall fn encoder)
               (objc:invoke encoder "endEncoding")
               (when drawable
                 (objc:invoke commands "presentDrawable:" drawable))
               (objc:invoke commands "commit")
               (unless drawable
                 (objc:invoke commands "waitUntilCompleted"))))))))))

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
