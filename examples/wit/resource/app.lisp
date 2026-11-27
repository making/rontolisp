;;;; app -- a Lisp command that uses the counters another component exports.
;;;;
;;;; It IMPORTS the two interfaces counters.lisp exports. On this side a
;;;; `counter` is a handle: an opaque value to pass back to the interface and to
;;;; release with `counter-drop` (or to hand over, where a function takes it
;;;; owned). What answers is decided per backend:
;;;;
;;;;   rontolisp app.lisp                          the interpreter -- the functions
;;;;                                               of counters.lisp, in process
;;;;   rontolisp app.lisp -o App.class && java App the JVM -- the same
;;;;   rontolisp app.lisp -o app.wasm --component
;;;;     + wac plug + wasmtime                     a component -- the composed
;;;;                                               counters.wasm answers instead
;;;;
;;;; The output is the same either way.

(rontolisp:wit-import "wit/counters.wit"
                      :interface "example:counters/registry"
                      :package reg)

(rontolisp:wit-import "wit/counters.wit"
                      :interface "example:counters/report"
                      :package report)

;;; On the interpreter and the JVM there is no component to call: the functions
;;; of counters.lisp are the provider, and a handle is the value itself. A
;;; component is given its provider by the composition, so there neither form
;;; is read.
#-rontolisp-wasm (load "counters.lisp")

#-rontolisp-wasm
(rontolisp:wit-provide "example:counters/registry@0.1.0"
                       #'(lambda (member &rest args)
                           (apply (cond ((string= member "counter-new")
                                         #'counter-new)
                                        ((string= member "counter-add")
                                         #'counter-add)
                                        ((string= member "counter-read")
                                         #'counter-read)
                                        ((string= member "counter-merge")
                                         #'counter-merge)
                                        ((string= member "counter-drop")
                                         #'counter-drop)
                                        ((string= member "total") #'total)
                                        ((string= member "leader") #'leader)
                                        ((string= member "live") #'live)
                                        (t (error "registry: unknown member ~a"
                                                  member))) args)))

#-rontolisp-wasm
(rontolisp:wit-provide "example:counters/report@0.1.0"
                       #'(lambda (member &rest args)
                           (declare (ignore member))
                           (apply #'summary args)))

(let ((apples (reg:counter-new "apples" 3))
      (pears (reg:counter-new "pears" 10)))
  (format t "add      ~s~%" (reg:counter-add apples 4))
  (format t "read     ~s~%" (reg:counter-read apples))
  (format t "total    ~s~%" (reg:total (list apples pears)))
  (format t "summary  ~s~%" (report:summary pears))
  (format t "live     ~s~%" (reg:live))
  ;; merge takes `apples` over: this handle of it is gone, the result is new
  (let ((fruit (reg:counter-merge apples pears)))
    (format t "merged   ~s~%" (reg:counter-read fruit))
    ;; leader takes both over and hands one back
    (let ((first (reg:leader (list fruit pears))))
      (format t "leader   ~s~%" (report:summary first))
      (format t "live     ~s~%" (reg:live))
      (reg:counter-drop first)))
  (format t "live     ~s~%" (reg:live))
  (format t "leader   ~s~%"
          (handler-case (reg:leader nil)
            (rontolisp:wit-error (e) (rontolisp:wit-error-payload e)))))
