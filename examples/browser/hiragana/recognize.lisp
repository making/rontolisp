;;;; recognize.lisp -- the browser build: the same net as infer.lisp, but
;;;; exported as a host-callable WASM function instead of reading stdin.
;;;;
;;;;   rontolisp recognize.lisp -o infer.wasm      (WASI Preview 1, WASM GC)
;;;;
;;;; Why an export and not a command module.  The weights are no longer baked
;;;; into the program: startup reads ~150k parameters out of weights.bin, one
;;;; byte at a time through WASI.  A command module would redo that on every
;;;; keystroke.  As an export, the page instantiates the module ONCE, runs
;;;; _start (which loads the weights into the module's globals), and then calls
;;;; recognize(...) per stroke -- the load happens once per page, and each
;;;; recognition is just the forward pass.
;;;;
;;;; The :s-expr parameter crosses as (ptr, len) into linear memory and is parsed
;;;; by the embedded reader; the :string result comes back the same way.  See
;;;; index.html for the ten lines of host glue (__ronto_alloc + memory), and
;;;; wasi-shim.js for the virtual filesystem that serves weights.bin.

(load "net.lisp")

(defparameter *net* (load-hiragana-net "weights.bin"))

(defun recognize (image)
  ;; IMAGE is the flattened 24x24 bitmap as a list of 576 numbers.  Returns the
  ;; same text infer.lisp prints: "pred <i> <romaji>" then one "score" line per
  ;; class, which the page parses.
  (let* ((scores (classify *net* (linalg:from-list image)))
         (pred (linalg:argmax scores))
         (out (format nil "pred ~a ~a~%" pred (nth pred *labels*))))
    (dotimes (i *nclasses*)
      (setq out (format nil "~ascore ~a ~a~%" out (nth i *labels*) (aref scores i))))
    out))

(rontolisp:wasm-export 'recognize :params '(:s-expr) :returns :string)
