;; Stub core whose only purpose is to make `wasm-tools component new` emit the WASI 0.3
;; imports for import-block.bin. Imports every lowered 0.3 function rontolisp's component
;; uses (the adapter binds these names), exports a memory + cabi_realloc + run.
(module
  (import "wasi:cli/stdout@0.3.0" "write-via-stream" (func (param i32) (result i32)))
  (import "wasi:cli/stderr@0.3.0" "write-via-stream" (func (param i32) (result i32)))
  (import "wasi:cli/stdin@0.3.0" "read-via-stream" (func (param i32)))
  (import "wasi:cli/environment@0.3.0" "get-environment" (func (param i32)))
  (import "wasi:clocks/system-clock@0.3.0" "now" (func (param i32)))
  (import "wasi:clocks/monotonic-clock@0.3.0" "now" (func (result i64)))
  (import "wasi:clocks/monotonic-clock@0.3.0" "[async-lower]wait-for" (func (param i64) (result i32)))
  (import "wasi:filesystem/types@0.3.0" "[method]descriptor.read-via-stream" (func (param i32 i64 i32)))
  (import "wasi:filesystem/types@0.3.0" "[method]descriptor.append-via-stream" (func (param i32 i32) (result i32)))
  (import "wasi:filesystem/types@0.3.0" "[method]descriptor.open-at" (func (param i32 i32 i32 i32 i32 i32 i32)))
  (import "wasi:filesystem/preopens@0.3.0" "get-directories" (func (param i32)))
  (import "wasi:random/random@0.3.0" "get-random-u64" (func (result i64)))
  (memory (export "memory") 6)
  (global $hp (mut i32) (i32.const 65536))
  (func (export "cabi_realloc") (param i32 i32 i32 i32) (result i32) (global.get $hp))
  (func (export "run") (result i32) (i32.const 0)))
