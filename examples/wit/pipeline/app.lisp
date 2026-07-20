;;;; app -- the Lisp command that drives the pipeline.
;;;;
;;;; It IMPORTS the `shout` interface (wit/pipeline.wit) and calls `emphasize` on
;;;; a few phrases. `sh:emphasize` is an ordinary Lisp function the compiler wrote
;;;; from the WIT; behind it -- once the three components are composed -- is the
;;;; Rust shouter, which itself calls back into a Lisp component. One line of Lisp
;;;; here, two languages and three components underneath.
;;;;
;;;;   rontolisp app.lisp -o app.wasm --component --optimize
;;;;
;;;; produces a command component that imports `example:pipeline/shout`. It is not
;;;; run on its own -- `wac compose` wires all three components together (see
;;;; README.md / composition.wac / build.sh).

(rontolisp:wit-import "wit/pipeline.wit"
                      :interface "example:pipeline/shout"
                      :package sh)

(defvar *phrases*
  '("hello world"
    "component model"
    "rust and lisp"))

(dolist (phrase *phrases*)
  (format t "~a  ->  ~a~%" phrase (sh:emphasize phrase)))
