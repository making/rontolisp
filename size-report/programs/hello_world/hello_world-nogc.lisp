;;;; hello_world -- --no-gc edition: MVP core module, no wasm-GC.
;;;; --no-gc takes only defun / wasm-export at top level, so this is a reactor
;;;; called by name, and it prints with princ (format is outside the subset).
;;;;
;;;; Run:
;;;;   rontolisp size-report/programs/hello_world/hello_world-nogc.lisp \
;;;;     -o hello-nogc.wasm --no-gc --optimize=size
;;;;   wasmtime run --invoke say-hello hello-nogc.wasm

(defun say-hello ()
  (princ "Hello, World!")
  (terpri))

(rontolisp:wasm-export 'say-hello :params '() :returns :void)
