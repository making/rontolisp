;; Stub core whose only purpose is to make `wasm-tools component new` emit the imports for
;; import-block-sockets.bin. Imports every lowered function the sockets-variant adapter binds:
;; the WASI 0.3 base functions (same as core.wat) plus the wasi:sockets@0.3.0 tcp-socket
;; functions for the rontolisp:tcp-* built-ins. Exports a memory + cabi_realloc + run.
(module
  ;; --- base I/O: WASI 0.3 (identical to core.wat) ---
  (import "wasi:cli/stdout@0.3.0" "write-via-stream" (func (param i32) (result i32)))
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
  ;; --- TCP sockets (rontolisp:tcp-*): WASI 0.3 ---
  ;; create/bind/connect/listen/get-local-address write their result through a retptr
  ;; (last i32 param); the ip-socket-address variant flattens to 12 i32s (disc + the
  ;; 11-slot ipv6 arm); send returns the future<result> handle directly; receive writes
  ;; the (stream<u8>, future<result>) tuple through the retptr.
  (import "wasi:sockets/types@0.3.0" "[static]tcp-socket.create" (func (param i32 i32)))
  (import "wasi:sockets/types@0.3.0" "[method]tcp-socket.bind" (func (param i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32)))
  (import "wasi:sockets/types@0.3.0" "[method]tcp-socket.connect" (func (param i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32)))
  (import "wasi:sockets/types@0.3.0" "[method]tcp-socket.listen" (func (param i32 i32)))
  (import "wasi:sockets/types@0.3.0" "[method]tcp-socket.send" (func (param i32 i32) (result i32)))
  (import "wasi:sockets/types@0.3.0" "[method]tcp-socket.receive" (func (param i32 i32)))
  (import "wasi:sockets/types@0.3.0" "[method]tcp-socket.get-local-address" (func (param i32 i32)))
  (import "wasi:cli/stderr@0.3.0" "write-via-stream" (func (param i32) (result i32)))
  (memory (export "memory") 6)
  (global $hp (mut i32) (i32.const 65536))
  (func (export "cabi_realloc") (param i32 i32 i32 i32) (result i32) (global.get $hp))
  (func (export "run") (result i32) (i32.const 0)))
