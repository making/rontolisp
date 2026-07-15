;; Stub core whose only purpose is to make `wasm-tools component new` emit the imports for
;; import-block-http-server-client.bin (the serve + fetch variant: rontolisp:http-handler AND
;; rontolisp:fetch in one program). Both halves are Lisp libraries over wit-imported wasi:http
;; now (serve.lisp handles the incoming side, fetch.lisp the outgoing side), so this imports
;; the UNION of what they lower: serve.lisp's incoming-request / outgoing-response machinery
;; (including incoming-request.headers, which the serve adapter never read) PLUS fetch.lisp's
;; outgoing-request / future / incoming-response machinery, plus wasi:io/poll and
;; wasi:http/outgoing-handler, plus the proxy-world entropy / clock / stdio the preview1 bridge
;; (adapter-http-server-p1.wat) provides. Exports a memory (16 pages, shared canonical scratch)
;; + cabi_realloc + run.
(module
  ;; wasi:http/types 0.2: incoming request + outgoing response machinery (adapter-http-server)
  (import "wasi:http/types@0.2.0" "[method]incoming-request.method" (func (param i32 i32)))
  (import "wasi:http/types@0.2.0" "[method]incoming-request.path-with-query" (func (param i32 i32)))
  (import "wasi:http/types@0.2.0" "[method]incoming-request.headers" (func (param i32) (result i32)))
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
  ;; wasi:http/types 0.2: outgoing request machinery (rontolisp:fetch, bridge)
  (import "wasi:http/types@0.2.0" "[method]fields.append" (func (param i32 i32 i32 i32 i32 i32)))
  (import "wasi:http/types@0.2.0" "[method]fields.entries" (func (param i32 i32)))
  (import "wasi:http/types@0.2.0" "[constructor]outgoing-request" (func (param i32) (result i32)))
  (import "wasi:http/types@0.2.0" "[method]outgoing-request.set-method" (func (param i32 i32 i32 i32) (result i32)))
  (import "wasi:http/types@0.2.0" "[method]outgoing-request.set-scheme" (func (param i32 i32 i32 i32 i32) (result i32)))
  (import "wasi:http/types@0.2.0" "[method]outgoing-request.set-authority" (func (param i32 i32 i32 i32) (result i32)))
  (import "wasi:http/types@0.2.0" "[method]outgoing-request.set-path-with-query" (func (param i32 i32 i32 i32) (result i32)))
  (import "wasi:http/types@0.2.0" "[method]outgoing-request.body" (func (param i32 i32)))
  (import "wasi:http/types@0.2.0" "[method]future-incoming-response.subscribe" (func (param i32) (result i32)))
  (import "wasi:http/types@0.2.0" "[method]future-incoming-response.get" (func (param i32 i32)))
  (import "wasi:http/types@0.2.0" "[method]incoming-response.status" (func (param i32) (result i32)))
  (import "wasi:http/types@0.2.0" "[method]incoming-response.headers" (func (param i32) (result i32)))
  (import "wasi:http/types@0.2.0" "[method]incoming-response.consume" (func (param i32 i32)))
  (import "wasi:http/types@0.2.0" "[resource-drop]fields" (func (param i32)))
  (import "wasi:http/types@0.2.0" "[resource-drop]outgoing-request" (func (param i32)))
  (import "wasi:http/types@0.2.0" "[resource-drop]outgoing-body" (func (param i32)))
  (import "wasi:http/types@0.2.0" "[resource-drop]future-incoming-response" (func (param i32)))
  (import "wasi:http/types@0.2.0" "[resource-drop]incoming-response" (func (param i32)))
  ;; wasi:io/streams 0.2: request/response body streaming (both directions)
  (import "wasi:io/streams@0.2.0" "[method]input-stream.blocking-read" (func (param i32 i64 i32)))
  (import "wasi:io/streams@0.2.0" "[method]output-stream.blocking-write-and-flush" (func (param i32 i32 i32 i32)))
  (import "wasi:io/streams@0.2.0" "[resource-drop]input-stream" (func (param i32)))
  (import "wasi:io/streams@0.2.0" "[resource-drop]output-stream" (func (param i32)))
  ;; wasi:io/poll 0.2: fetch-await blocks on the response pollable
  (import "wasi:io/poll@0.2.0" "[method]pollable.block" (func (param i32)))
  (import "wasi:io/poll@0.2.0" "[resource-drop]pollable" (func (param i32)))
  ;; wasi:http/outgoing-handler 0.2: sends the outgoing request
  (import "wasi:http/outgoing-handler@0.2.0" "handle" (func (param i32 i32 i32 i32)))
  ;; wasi:random / wasi:clocks / wasi:cli 0.2 (proxy world): entropy, clocks and
  ;; stdout/stderr for the preview1 bridge (adapter-http-server-client-p1.wat)
  (import "wasi:random/random@0.2.0" "get-random-u64" (func (result i64)))
  (import "wasi:clocks/wall-clock@0.2.0" "now" (func (param i32)))
  (import "wasi:clocks/monotonic-clock@0.2.0" "now" (func (result i64)))
  (import "wasi:cli/stdout@0.2.0" "get-stdout" (func (result i32)))
  (import "wasi:cli/stderr@0.2.0" "get-stderr" (func (result i32)))
  (memory (export "memory") 16)
  (global $hp (mut i32) (i32.const 65536))
  (func (export "cabi_realloc") (param i32 i32 i32 i32) (result i32) (global.get $hp))
  (func (export "run") (result i32) (i32.const 0)))
