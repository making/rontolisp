;;;; hello_world -- the size-comparison floor
;;;; A minimal WASI command that writes one line to stdout. The whole point is
;;;; the size of the compiled artifact, so the program is deliberately the
;;;; smallest thing a language can be asked to do.
;;;;
;;;; Run:
;;;;   rontolisp examples/wasm-size/hello_world/hello_world.lisp
;;;;   rontolisp examples/wasm-size/hello_world/hello_world.lisp -o hello.wasm --optimize=size
;;;;   wasmtime run -W gc hello.wasm

(format t "Hello, World!~%")
