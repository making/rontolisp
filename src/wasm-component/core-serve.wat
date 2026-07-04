;; Stub core whose only purpose is to make `wasm-tools component new` emit the imports for
;; import-block-serve.bin. Imports every lowered function the serve-variant adapter
;; (adapter-serve.wat) binds: the WASI 0.2 http/io incoming-handler machinery. Exports a
;; memory (16 pages, shared canonical scratch) + cabi_realloc + run.
(module
  ;; wasi:http/types 0.2: incoming request + outgoing response machinery
  (import "wasi:http/types@0.2.0" "[method]incoming-request.method" (func (param i32 i32)))
  (import "wasi:http/types@0.2.0" "[method]incoming-request.path-with-query" (func (param i32 i32)))
  (import "wasi:http/types@0.2.0" "[method]incoming-request.consume" (func (param i32 i32)))
  (import "wasi:http/types@0.2.0" "[method]incoming-body.stream" (func (param i32 i32)))
  (import "wasi:http/types@0.2.0" "[constructor]fields" (func (result i32)))
  (import "wasi:http/types@0.2.0" "[constructor]outgoing-response" (func (param i32) (result i32)))
  (import "wasi:http/types@0.2.0" "[method]outgoing-response.set-status-code" (func (param i32 i32) (result i32)))
  (import "wasi:http/types@0.2.0" "[method]outgoing-response.body" (func (param i32 i32)))
  (import "wasi:http/types@0.2.0" "[method]outgoing-body.write" (func (param i32 i32)))
  (import "wasi:http/types@0.2.0" "[static]outgoing-body.finish" (func (param i32 i32 i32 i32)))
  (import "wasi:http/types@0.2.0" "[static]response-outparam.set" (func (param i32 i32 i32 i32 i64 i32 i32 i32 i32)))
  (import "wasi:http/types@0.2.0" "[resource-drop]incoming-request" (func (param i32)))
  (import "wasi:http/types@0.2.0" "[resource-drop]incoming-body" (func (param i32)))
  ;; wasi:io/streams 0.2: request/response body streaming
  (import "wasi:io/streams@0.2.0" "[method]input-stream.blocking-read" (func (param i32 i64 i32)))
  (import "wasi:io/streams@0.2.0" "[method]output-stream.blocking-write-and-flush" (func (param i32 i32 i32 i32)))
  (import "wasi:io/streams@0.2.0" "[resource-drop]input-stream" (func (param i32)))
  (import "wasi:io/streams@0.2.0" "[resource-drop]output-stream" (func (param i32)))
  (memory (export "memory") 16)
  (global $hp (mut i32) (i32.const 65536))
  (func (export "cabi_realloc") (param i32 i32 i32 i32) (result i32) (global.get $hp))
  (func (export "run") (result i32) (i32.const 0)))
