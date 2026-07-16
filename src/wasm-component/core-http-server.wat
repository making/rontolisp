;; Stub core whose only purpose is to make `wasm-tools component new` emit the imports
;; for import-block-http-server.bin: it imports every lowered wasi:http@0.3.0 function
;; http.lisp binds (both halves -- the block is shared by plain serve and serve+fetch)
;; plus the service-world entropy / clock / stdio functions the preview1 bridge
;; (adapter-http-server-p1.wat) maps to the rontolisp core's wasi_snapshot_preview1
;; imports. The stream/future built-ins and the resource drops are NOT imported here:
;; they are canon entries the builder emits itself, not instance exports the block must
;; declare. Exports a memory (16 pages, shared canonical scratch) + cabi_realloc + run.
(module
  ;; wasi:http/types 0.3: fields + request + response (flat core sigs per the
  ;; canonical ABI; `wasm-tools component new` validates each one)
  (import "wasi:http/types@0.3.0" "[constructor]fields" (func (result i32)))
  (import "wasi:http/types@0.3.0" "[method]fields.append" (func (param i32 i32 i32 i32 i32 i32)))
  (import "wasi:http/types@0.3.0" "[method]fields.copy-all" (func (param i32 i32)))
  (import "wasi:http/types@0.3.0" "[static]request.new" (func (param i32 i32 i32 i32 i32 i32 i32)))
  (import "wasi:http/types@0.3.0" "[method]request.get-method" (func (param i32 i32)))
  (import "wasi:http/types@0.3.0" "[method]request.set-method" (func (param i32 i32 i32 i32) (result i32)))
  (import "wasi:http/types@0.3.0" "[method]request.get-path-with-query" (func (param i32 i32)))
  (import "wasi:http/types@0.3.0" "[method]request.set-path-with-query" (func (param i32 i32 i32 i32) (result i32)))
  (import "wasi:http/types@0.3.0" "[method]request.set-scheme" (func (param i32 i32 i32 i32 i32) (result i32)))
  (import "wasi:http/types@0.3.0" "[method]request.set-authority" (func (param i32 i32 i32 i32) (result i32)))
  (import "wasi:http/types@0.3.0" "[method]request.get-headers" (func (param i32) (result i32)))
  (import "wasi:http/types@0.3.0" "[static]request.consume-body" (func (param i32 i32 i32)))
  (import "wasi:http/types@0.3.0" "[static]response.new" (func (param i32 i32 i32 i32 i32)))
  (import "wasi:http/types@0.3.0" "[method]response.get-status-code" (func (param i32) (result i32)))
  (import "wasi:http/types@0.3.0" "[method]response.set-status-code" (func (param i32 i32) (result i32)))
  (import "wasi:http/types@0.3.0" "[method]response.get-headers" (func (param i32) (result i32)))
  (import "wasi:http/types@0.3.0" "[static]response.consume-body" (func (param i32 i32 i32)))
  ;; wasi:http/client 0.3 (the outgoing half; sync-shape here -- the block's instance
  ;; type declares `send` async from the WIT either way)
  (import "wasi:http/client@0.3.0" "send" (func (param i32 i32)))
  ;; wasi:random / wasi:clocks / wasi:cli 0.3 (service world): entropy, clocks and
  ;; stdout/stderr for the preview1 bridge (adapter-http-server-p1.wat)
  (import "wasi:random/random@0.3.0" "get-random-u64" (func (result i64)))
  (import "wasi:clocks/system-clock@0.3.0" "now" (func (param i32)))
  (import "wasi:clocks/monotonic-clock@0.3.0" "now" (func (result i64)))
  (import "wasi:cli/stdout@0.3.0" "write-via-stream" (func (param i32) (result i32)))
  (import "wasi:cli/stderr@0.3.0" "write-via-stream" (func (param i32) (result i32)))
  (memory (export "memory") 16)
  (global $hp (mut i32) (i32.const 65536))
  (func (export "cabi_realloc") (param i32 i32 i32 i32) (result i32) (global.get $hp))
  (func (export "run") (result i32) (i32.const 0)))
