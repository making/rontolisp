;;;; gl.lisp -- the shared WebGL2 host boundary for the examples/webgl-* demos.
;;;;
;;;; Every browser demo used to repeat the same block of rontolisp:wasm-import
;;;; directives, WebGL enum constants and shader-compilation helpers. This file
;;;; factors that block into a `gl` package: a demo splices it in at compile
;;;; time with
;;;;
;;;;   (require :gl "../webgl-common/gl.lisp")
;;;;
;;;; and then calls (gl:create-shader ...), reads gl:+float+, or just builds a
;;;; whole pipeline with (gl:build-program vs fs). Imports the demo never calls
;;;; are dropped by --optimize (the tree-shaker removes unused host imports),
;;;; so requiring the full union below does not grow any page's import object:
;;;; each page only has to provide the entries its own demo actually reaches.
;;;;
;;;; The boundary convention (see the demos' index.html): GL objects (shaders,
;;;; programs, buffers, VAOs, uniform locations) cross as :int handles into a
;;;; table the page keeps; strings (GLSL source, uniform names, info logs)
;;;; cross as :string. Only the literal WebGL2 API entries live here -- each
;;;; demo keeps its own staging imports (setVertex, setFloat, ...), which are
;;;; page-specific by design.
;;;;
;;;; wasm-import registers the function under the exact (quoted) name it is
;;;; given, and quoted symbols are not package-resolved, so every name below
;;;; is written in its canonical package-qualified form.

(provide :gl)

(defpackage gl
  (:use cl)
  (:export create-shader shader-source compile-shader
           shader-compiled-p shader-info-log
           create-program attach-shader link-program
           program-linked-p program-info-log use-program
           get-uniform-location uniform1f uniform3f
           enable disable depth-mask blend-func
           create-buffer bind-buffer buffer-data
           create-vertex-array bind-vertex-array
           enable-vertex-attrib-array vertex-attrib-pointer
           viewport clear-color clear draw-arrays
           make-shader build-program
           +vertex-shader+ +fragment-shader+
           +compile-status+ +link-status+
           +array-buffer+ +static-draw+ +dynamic-draw+
           +float+ +blend+ +depth-test+ +one+
           +color-buffer-bit+ +depth-buffer-bit+
           +points+ +triangles+))

(in-package gl)

;; --- the WebGL2 API, one entry point at a time --------------------------------

(rontolisp:wasm-import 'gl:create-shader :from "gl" :as "createShader"
                       :params '(:int) :returns :int)
(rontolisp:wasm-import 'gl:shader-source :from "gl" :as "shaderSource"
                       :params '(:int :string) :returns :void)
(rontolisp:wasm-import 'gl:compile-shader :from "gl" :as "compileShader"
                       :params '(:int) :returns :void)
(rontolisp:wasm-import 'gl:shader-compiled-p :from "gl" :as "getShaderParameter"
                       :params '(:int :int) :returns :bool)
(rontolisp:wasm-import 'gl:shader-info-log :from "gl" :as "getShaderInfoLog"
                       :params '(:int) :returns :string)
(rontolisp:wasm-import 'gl:create-program :from "gl" :as "createProgram"
                       :params '() :returns :int)
(rontolisp:wasm-import 'gl:attach-shader :from "gl" :as "attachShader"
                       :params '(:int :int) :returns :void)
(rontolisp:wasm-import 'gl:link-program :from "gl" :as "linkProgram"
                       :params '(:int) :returns :void)
(rontolisp:wasm-import 'gl:program-linked-p :from "gl" :as "getProgramParameter"
                       :params '(:int :int) :returns :bool)
(rontolisp:wasm-import 'gl:program-info-log :from "gl" :as "getProgramInfoLog"
                       :params '(:int) :returns :string)
(rontolisp:wasm-import 'gl:use-program :from "gl" :as "useProgram"
                       :params '(:int) :returns :void)
(rontolisp:wasm-import 'gl:get-uniform-location :from "gl" :as "getUniformLocation"
                       :params '(:int :string) :returns :int)
(rontolisp:wasm-import 'gl:uniform1f :from "gl" :as "uniform1f"
                       :params '(:int :float) :returns :void)
(rontolisp:wasm-import 'gl:uniform3f :from "gl" :as "uniform3f"
                       :params '(:int :float :float :float) :returns :void)
(rontolisp:wasm-import 'gl:enable :from "gl" :as "enable"
                       :params '(:int) :returns :void)
(rontolisp:wasm-import 'gl:disable :from "gl" :as "disable"
                       :params '(:int) :returns :void)
(rontolisp:wasm-import 'gl:depth-mask :from "gl" :as "depthMask"
                       :params '(:bool) :returns :void)
(rontolisp:wasm-import 'gl:blend-func :from "gl" :as "blendFunc"
                       :params '(:int :int) :returns :void)
(rontolisp:wasm-import 'gl:create-buffer :from "gl" :as "createBuffer"
                       :params '() :returns :int)
(rontolisp:wasm-import 'gl:bind-buffer :from "gl" :as "bindBuffer"
                       :params '(:int :int) :returns :void)
(rontolisp:wasm-import 'gl:buffer-data :from "gl" :as "bufferData"
                       :params '(:int :int :int) :returns :void)
(rontolisp:wasm-import 'gl:create-vertex-array :from "gl" :as "createVertexArray"
                       :params '() :returns :int)
(rontolisp:wasm-import 'gl:bind-vertex-array :from "gl" :as "bindVertexArray"
                       :params '(:int) :returns :void)
(rontolisp:wasm-import 'gl:enable-vertex-attrib-array :from "gl" :as "enableVertexAttribArray"
                       :params '(:int) :returns :void)
(rontolisp:wasm-import 'gl:vertex-attrib-pointer :from "gl" :as "vertexAttribPointer"
                       :params '(:int :int :int :bool :int :int) :returns :void)
(rontolisp:wasm-import 'gl:viewport :from "gl" :as "viewport"
                       :params '(:int :int :int :int) :returns :void)
(rontolisp:wasm-import 'gl:clear-color :from "gl" :as "clearColor"
                       :params '(:float :float :float :float) :returns :void)
(rontolisp:wasm-import 'gl:clear :from "gl" :as "clear"
                       :params '(:int) :returns :void)
(rontolisp:wasm-import 'gl:draw-arrays :from "gl" :as "drawArrays"
                       :params '(:int :int :int) :returns :void)

;; Fatal-error reporting for the shader helpers below: shows the page's error
;; box (and stops the program by throwing on the JavaScript side). Internal to
;; this package -- demos report their own errors through their own imports.
(rontolisp:wasm-import 'gl::fail :from "ui" :as "fail"
                       :params '(:string) :returns :void)

;; --- WebGL constants -----------------------------------------------------------
;; The numeric enum values from the WebGL specification.

(defconstant +vertex-shader+ 35633)             ; 0x8B31
(defconstant +fragment-shader+ 35632)           ; 0x8B30
(defconstant +compile-status+ 35713)            ; 0x8B81
(defconstant +link-status+ 35714)               ; 0x8B82
(defconstant +array-buffer+ 34962)              ; 0x8892
(defconstant +static-draw+ 35044)               ; 0x88E4
(defconstant +dynamic-draw+ 35048)              ; 0x88E8
(defconstant +float+ 5126)                      ; 0x1406
(defconstant +blend+ 3042)                      ; 0x0BE2
(defconstant +depth-test+ 2929)                 ; 0x0B71
(defconstant +one+ 1)
(defconstant +color-buffer-bit+ 16384)          ; 0x4000
(defconstant +depth-buffer-bit+ 256)            ; 0x0100
(defconstant +points+ 0)
(defconstant +triangles+ 4)

;; --- shader helpers --------------------------------------------------------------

(defun make-shader (type source)
  ;; Compile one shader, failing loudly with the driver's info log.
  (let ((shader (create-shader type)))
    (shader-source shader source)
    (compile-shader shader)
    (unless (shader-compiled-p shader +compile-status+)
      (fail (shader-info-log shader)))
    shader))

(defun build-program (vs-source fs-source)
  ;; Compile both shaders and link them into a program (not yet in use).
  (let ((program (create-program)))
    (attach-shader program (make-shader +vertex-shader+ vs-source))
    (attach-shader program (make-shader +fragment-shader+ fs-source))
    (link-program program)
    (unless (program-linked-p program +link-status+)
      (fail (program-info-log program)))
    program))

(in-package cl-user)
