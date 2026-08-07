;;;; hello_world -- non-GC (--no-gc) edition
;;;; The companion to hello_world.lisp, compiled with --no-gc: a plain MVP core
;;;; module with no wasm-GC types at all, so it runs on any MVP-class runtime
;;;; without `-W gc`.
;;;;
;;;; Two things change, and both are what --no-gc costs:
;;;;
;;;;   1. --no-gc accepts only (defun ...) and rontolisp:wasm-export at top
;;;;      level, so this is a REACTOR, not a command. There is no `_start`; the
;;;;      host calls the export by name.
;;;;   2. `format` is outside the --no-gc subset. `princ` + `terpri` are not,
;;;;      and for a string literal they say the same thing.
;;;;
;;;; Run:
;;;;   rontolisp examples/wasm-size/hello_world/hello_world-nogc.lisp \
;;;;     -o hello-nogc.wasm --no-gc --optimize=size
;;;;   wasmtime run --invoke say-hello hello-nogc.wasm

(defun say-hello ()
  (princ "Hello, World!")
  (terpri))

(rontolisp:wasm-export 'say-hello :params '() :returns :void)
