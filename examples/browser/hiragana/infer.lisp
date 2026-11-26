;;;; infer.lisp -- the command-line inference program (interpreter, JVM, WASM
;;;; Preview 1 and the WASI 0.3 component all run this file unchanged).
;;;;
;;;; It reads ONE flattened 24x24 bitmap from stdin as a Lisp list of 576 values
;;;; in [0, 1] -- e.g. "(0.0 0.0 1.0 ... 0.0)", which is what samples/*.txt hold
;;;; -- runs the trained convnet forward and prints the predicted class plus
;;;; every class score.
;;;;
;;;; The weights come from weights.bin at startup, so the WASM backends need a
;;;; preopened directory (wasmtime --dir .) and the weights path is relative to
;;;; the directory you run in.  The (load ...) path, by contrast, resolves
;;;; relative to THIS file, so the program compiles from anywhere.
;;;;
;;;; The browser front-end does NOT use this program -- it cannot pipe stdin into
;;;; a module it wants to keep alive across strokes.  recognize.lisp is the same
;;;; net exported as a host-callable function instead.

(load "net.lisp")

(defparameter *net* (load-hiragana-net "weights.bin"))

(print-prediction (classify *net* (linalg:from-list (read))))
