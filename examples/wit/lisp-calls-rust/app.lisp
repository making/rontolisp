;;;; app -- a Lisp command that calls the `casing` interface.
;;;;
;;;; It IMPORTS the `casing` interface (wit/textkit.wit) and calls `shout` on a
;;;; few phrases. `tk:shout` is an ordinary Lisp function the compiler wrote from
;;;; the WIT; WHAT answers it is decided per backend, and nothing below changes:
;;;;
;;;;   rontolisp app.lisp                          the interpreter -- the Lisp
;;;;                                               provider below answers
;;;;   rontolisp app.lisp -o App.class && java App the JVM -- same provider
;;;;   rontolisp app.lisp -o app.wasm --component --optimize
;;;;     + wac plug + wasmtime                     a WASI component -- the composed
;;;;                                               Rust component answers instead
;;;;
;;;; So on the interpreter and JVM this is Lisp calling Lisp; composed with the
;;;; Rust component it is Lisp calling Rust. The output is identical either way.

(rontolisp:wit-import "wit/textkit.wit"
                      :interface "example:textkit/casing"
                      :package tk)

;;; A wit-import needs a provider on the interpreter and JVM (there is no Rust
;;; component to call there). This lambda reimplements `shout` in two lines: it
;;; takes the bound function's member name ("shout") and that function's
;;; arguments. On every WASM backend a rontolisp:wit-provide is INERT (the
;;; composed component is the provider), so the very same file compiles and runs
;;; there too.
(rontolisp:wit-provide "example:textkit/casing"
                       #'(lambda (member &rest args)
                           (cond
                            ((string= member "shout")
                             (concatenate 'string (string-upcase (first args))
                                          "!"))
                            (t (error "casing: unknown member ~a" member)))))

(defvar *phrases* '("hello world" "component model" "rust and lisp"))

(dolist (phrase *phrases*) (format t "~a  ->  ~a~%" phrase (tk:shout phrase)))
