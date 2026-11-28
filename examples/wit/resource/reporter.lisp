;;;; reporter -- a world that takes a resource another component exports.
;;;;
;;;; It implements the world `reporter` of wit/counters.wit: it IMPORTS the
;;;; interface `registry` and EXPORTS `report`, whose function takes a
;;;; `counter`. The counter is not this component's: counters.lisp keeps it, and
;;;; here a counter is a handle -- the integer a wit-import of `registry` takes,
;;;; as in app.lisp.
;;;;
;;;;   rontolisp reporter.lisp -o reporter.wasm --component
;;;;
;;;; build.sh plugs it between app.lisp and counters.lisp -- see README.md.

(rontolisp:wit-import "wit/counters.wit"
                      :interface "example:counters/registry"
                      :package reg)

;;; WIT (interface report): summary: func(c: borrow<counter>) -> string
;;; `c` is lent for the call: the export releases the handle once this answers,
;;; so it is not dropped here.
(defun summary (c)
  (let ((reading (reg:counter-read c)))
    (format nil "~a is at ~d" (getf reading :name) (getf reading :value))))

(rontolisp:wit-export "wit/counters.wit" :world reporter :reactor t)
