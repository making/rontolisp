;;;; app -- a Lisp command that calls the `geometry` interface.
;;;;
;;;; It IMPORTS the interface shapes.lisp exports, and passes it the same Lisp
;;;; values that file receives: a record is a plist, a variant a keyword or a
;;;; (keyword . payload), a list a list. What answers is decided per backend:
;;;;
;;;;   rontolisp app.lisp                          the interpreter -- shapes.lisp's
;;;;                                               own functions answer, in process
;;;;   rontolisp app.lisp -o App.class && java App the JVM -- the same
;;;;   rontolisp app.lisp -o app.wasm --component
;;;;     + wac plug + wasmtime                     a component -- the composed
;;;;                                               shapes.wasm answers instead
;;;;
;;;; The output is the same either way.

;;; total-area is an async func: :blocking t binds it as a call that waits for
;;; its answer, as every other function here is.
(rontolisp:wit-import "wit/shapes.wit"
                      :interface "example:shapes/geometry"
                      :package geo
                      :blocking t)

;;; On the interpreter and the JVM there is no component to call: the functions
;;; of shapes.lisp are the provider. A component is given its provider by the
;;; composition, so there neither form is read.
#-rontolisp-wasm (load "shapes.lisp")

#-rontolisp-wasm
(rontolisp:wit-provide "example:shapes/geometry@0.1.0"
                       #'(lambda (member &rest args)
                           (apply (cond ((string= member "flip") #'flip)
                                   ((string= member "squares") #'squares)
                                   ((string= member "bounds") #'bounds)
                                   ((string= member "area") #'area)
                                   ((string= member "caption") #'caption)
                                   ((string= member "embolden") #'embolden)
                                   ((string= member "total-area") #'total-area)
                                   (t (error "geometry: unknown member ~a"
                                             member))) args)))

(format t "flip     ~s~%" (geo:flip (list :x 1 :y 2)))
(format t "squares  ~s~%" (geo:squares 3))
(format t "bounds   ~s~%" (geo:bounds (geo:squares 3)))
(format t "bounds   ~s~%" (geo:bounds nil))
(format t "area     ~s~%" (geo:area (cons :circle 2.0d0)))
(format t "area     ~s~%" (geo:area (cons :rect (list :x 3 :y 4))))
(format t "area     ~s~%"
        (handler-case (geo:area :dot)
          (rontolisp:wit-error (e) (rontolisp:wit-error-payload e))))
(format t "caption  ~s~%"
        (geo:caption
         (list :shape :dot
               :color :green
               :style '(:filled :bold)
               :label "origin")))
(format t "caption  ~s~%"
        (geo:caption (list :shape (cons :circle 1.0d0) :color :red)))
(format t "embolden ~s~%" (geo:embolden '(:dashed)))
(format t "total    ~s~%"
 (geo:total-area (list (cons :circle 1.0d0) (cons :rect (list :x 3 :y 4)))))
(format t "total    ~s~%"
        (handler-case (geo:total-area (list (cons :circle 1.0d0) :dot))
          (rontolisp:wit-error (e) (rontolisp:wit-error-payload e))))
