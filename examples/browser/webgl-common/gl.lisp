;;;; gl.lisp -- the shared WebGL2 host boundary for the examples/webgl-* demos.
;;;;
;;;; Every browser demo used to repeat the same block of host-import directives,
;;;; WebGL enum constants and shader-compilation helpers. This file factors that
;;;; block into a `gl` package: a demo splices it in at compile time with
;;;;
;;;;   (require :gl "../webgl-common/gl.lisp")
;;;;
;;;; and then calls (gl:create-shader ...), reads gl:+float+, or just builds a
;;;; whole pipeline with (gl:build-program vs fs).
;;;;
;;;; The boundary itself is not written here: it is `gl.wit`, and the two
;;;; rontolisp:wit-import directives below bind it. Each WIT function lowers into
;;;; exactly the host import a hand-written rontolisp:wasm-import would have
;;;; declared, so the compiled module is unchanged -- but the page's import
;;;; object is GENERATED from the same file (gl-imports.js), so the Lisp side and
;;;; the JavaScript side can no longer drift apart. See gl.wit for the type
;;;; conventions (GL objects cross as s32 handles into a table the page keeps;
;;;; GLSL sources, uniform names and info logs cross as strings).
;;;;
;;;; Imports the demo never calls are dropped by --optimize (the tree-shaker
;;;; removes unused host imports), so binding the full WebGL2 union below does
;;;; not grow any page's module: each page only imports what its own demo
;;;; actually reaches. Each demo still declares its own staging imports
;;;; (setVertex, setFloat, ...) with rontolisp:wasm-import next to its own code --
;;;; those are page-specific by design and deliberately stay off the WIT.
;;;;
;;;; The directives bind into the CURRENT package rather than naming one, so the
;;;; bindings land in `gl` beside the constants and helpers below: under
;;;; (in-package gl) each WIT label canonicalizes to gl:label (or gl::fail for
;;;; the unexported fail helper), which is what call sites resolve to. The
;;;; defpackage stays hand-written for the same reason -- it has to export the
;;;; constants and helpers too, which no directive knows about.

(provide :gl)

(defpackage gl
  (:use cl)
  (:export create-shader shader-source compile-shader get-shader-parameter
           get-shader-info-log create-program attach-shader link-program
           get-program-parameter get-program-info-log use-program
           get-uniform-location uniform1f uniform3f enable disable depth-mask
           blend-func create-buffer bind-buffer buffer-data create-vertex-array
           bind-vertex-array enable-vertex-attrib-array vertex-attrib-pointer
           viewport clear-color clear draw-arrays make-shader build-program
           +vertex-shader+ +fragment-shader+ +compile-status+ +link-status+
           +array-buffer+ +static-draw+ +dynamic-draw+ +float+ +blend+
           +depth-test+ +one+ +color-buffer-bit+ +depth-buffer-bit+ +points+
           +triangles+))

(in-package gl)

;; --- the WebGL2 API, and the page's fatal-error reporting ----------------------
;; Fatal-error reporting for the shader helpers below shows the page's error box
;; (and stops the program by throwing on the JavaScript side). It is internal to
;; this package -- demos report their own errors through their own imports.

(rontolisp:wit-import "gl.wit" :interface "local:webgl/gl")
(rontolisp:wit-import "gl.wit" :interface "local:webgl/ui")

;; --- WebGL constants -----------------------------------------------------------
;; The numeric enum values from the WebGL specification.

(defconstant +vertex-shader+ 35633)   ; 0x8B31
(defconstant +fragment-shader+ 35632) ; 0x8B30
(defconstant +compile-status+ 35713)  ; 0x8B81
(defconstant +link-status+ 35714)     ; 0x8B82
(defconstant +array-buffer+ 34962)    ; 0x8892
(defconstant +static-draw+ 35044)     ; 0x88E4
(defconstant +dynamic-draw+ 35048)    ; 0x88E8
(defconstant +float+ 5126)            ; 0x1406
(defconstant +blend+ 3042)            ; 0x0BE2
(defconstant +depth-test+ 2929)       ; 0x0B71
(defconstant +one+ 1)
(defconstant +color-buffer-bit+ 16384) ; 0x4000
(defconstant +depth-buffer-bit+ 256)   ; 0x0100
(defconstant +points+ 0)
(defconstant +triangles+ 4)

;; --- shader helpers --------------------------------------------------------------

(defun make-shader (type source)
  ;; Compile one shader, failing loudly with the driver's info log.
  (let ((shader (create-shader type)))
    (shader-source shader source)
    (compile-shader shader)
    (unless (get-shader-parameter shader +compile-status+)
      (fail (get-shader-info-log shader)))
    shader))

(defun build-program (vs-source fs-source)
  ;; Compile both shaders and link them into a program (not yet in use).
  (let ((program (create-program)))
    (attach-shader program (make-shader +vertex-shader+ vs-source))
    (attach-shader program (make-shader +fragment-shader+ fs-source))
    (link-program program)
    (unless (get-program-parameter program +link-status+)
      (fail (get-program-info-log program)))
    program))

(in-package cl-user)
