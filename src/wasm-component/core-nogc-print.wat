;; Stub core whose only purpose is to make `wasm-tools component new` emit the imports for
;; import-block-nogc-print.bin (the wasi:cli/stdout@0.3.0 interface the --no-gc print
;; micro-adapter binds; see uni-nogc-print.wit). Imports the one lowered function
;; bridge-nogc-print.wat uses (the stream/future built-ins are canon built-ins, not
;; interface imports, so they never reach the sliced block). Exports a memory +
;; cabi_realloc + run only to satisfy `wasm-tools component new`; none of them reach the
;; sliced import block.
(module
  (import "wasi:cli/stdout@0.3.0" "write-via-stream" (func (param i32) (result i32)))
  (memory (export "memory") 1)
  (global $hp (mut i32) (i32.const 65536))
  (func (export "cabi_realloc") (param i32 i32 i32 i32) (result i32) (global.get $hp))
  (func (export "run") (result i32) (i32.const 0)))
