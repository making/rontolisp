;;;; hello_world -- the size-comparison floor: one line to stdout.
;;;;
;;;; Run:
;;;;   rontolisp size-report/programs/hello_world/hello_world.lisp -o hello.wasm --optimize=size
;;;;   wasmtime run hello.wasm

(format t "Hello, World!~%")
