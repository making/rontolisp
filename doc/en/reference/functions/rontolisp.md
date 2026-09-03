# rontolisp Package Functions

The `rontolisp` package provides implementation-specific functions that are
**not part of Common Lisp**. Reference them with the `rontolisp:` qualifier (or
unqualified after `(in-package rontolisp)`); see [Packages](../packages.md) for the
package system. Each name below links to its own page.

| Function | Example | Result |
|----------|---------|--------|
| `rontolisp:version` | `(rontolisp:version)` | a property list of build info (`:version`, `:build-timestamp`, `:git-commit`, `:git-branch`) |
| `rontolisp:random-bytes` | `(rontolisp:random-bytes 16)` | a vector of cryptographically strong random bytes (`SecureRandom` / WASI `random_get`) |
| `rontolisp:bfloat16-bits` | `(rontolisp:bfloat16-bits 1.0)` | the bfloat16 bit pattern of a real, rounded to nearest even (0-65535) |
| `rontolisp:bits-bfloat16` | `(rontolisp:bits-bfloat16 16256)` | the float a bfloat16 bit pattern encodes; exact for all 65536 patterns |
| `rontolisp:float16-bits` | `(rontolisp:float16-bits 1.0)` | the IEEE binary16 (f16) bit pattern of a real, rounded to nearest even (0-65535) |
| `rontolisp:bits-float16` | `(rontolisp:bits-float16 15360)` | the float an f16 bit pattern encodes |
| `rontolisp:widen-float-bits` | `(rontolisp:widen-float-bits bits :float16 dst)` | widen a packed `(unsigned-byte 16)` vector of f16/bfloat16 bit patterns into a packed float array, in bulk |
| `rontolisp:narrow-float-bits` | `(rontolisp:narrow-float-bits src :bfloat16 dst)` | narrow a packed float array into a packed `(unsigned-byte 16)` vector of f16/bfloat16 bit patterns, in bulk |
| `rontolisp:make-mutex` | `(rontolisp:make-mutex)` | a fresh mutual-exclusion lock, as an opaque handle (real on the interpreter and the JVM, a no-op on WASM) |
| `rontolisp:mutex-acquire` | `(rontolisp:mutex-acquire m)` | block until this thread holds the mutex; returns it (prefer `rontolisp:with-mutex`) |
| `rontolisp:mutex-release` | `(rontolisp:mutex-release m)` | release one acquisition of the mutex; returns it |
| `rontolisp:make-thread` | `(rontolisp:make-thread fn bindings)` | spawn a virtual thread running the zero-argument function, with optional `(symbol . value)` dynamic bindings established in it; returns an opaque handle (interpreter and JVM; the WASM shims signal) |
| `rontolisp:join-thread` | `(rontolisp:join-thread th)` | wait for the thread and yield its function's value; an error it died on is re-signaled here |
| `rontolisp:threadp` | `(rontolisp:threadp v)` | `t` if the value is a thread handle |
| `rontolisp:thread-alive-p` | `(rontolisp:thread-alive-p th)` | `t` while the thread is still running (`nil` after a join) |
| `rontolisp:destroy-thread` | `(rontolisp:destroy-thread th)` | interrupt the thread; returns the handle |
| `rontolisp:current-thread` | `(rontolisp:current-thread)` | the calling thread's own handle, `eq`-stable per thread (works for any thread, not only `make-thread` spawns) |
| `rontolisp:fetch` | `(rontolisp:fetch "http://example.com/")` | start an HTTP request asynchronously; returns a future |
| `rontolisp:futurep` | `(rontolisp:futurep v)` | `t` if the value is a future (as returned by calling an `async-defun` function, `rontolisp:fetch`, `rontolisp:stream-read`, ...) |
| `rontolisp:streamp` | `(rontolisp:streamp v)` | `t` if the value is an asynchronous stream (a different predicate from `cl:streamp`, which answers file streams) |
| `rontolisp:make-stream` | `(rontolisp:make-stream)` | create a fresh open asynchronous stream; one value owns both the read and the write end |
| `rontolisp:stream-read` | `(rontolisp:stream-read s)` | a future settling to the stream's next chunk, or `nil` at end of stream |
| `rontolisp:stream-write` | `(rontolisp:stream-write s "chunk")` | append a chunk (never `nil`); returns a future that settles when the stream accepted it |
| `rontolisp:stream-close` | `(rontolisp:stream-close s)` | close the write end; buffered chunks stay readable, then reads observe end of stream |
| `rontolisp:read-all` | `(rontolisp:read-all s)` | a future settling to the remaining chunks drained into one string (octet chunks -- every HTTP body stream's -- UTF-8 decoded) |
| `rontolisp:wait-for` | `(rontolisp:wait-for 100)` | a future settling to `nil` after the given milliseconds; the async counterpart of `cl:sleep` |
| `rontolisp:then` | `(rontolisp:then f (lambda (v) (* 2 v)))` | attach a transform to a future as a value; returns a fresh future on the success channel (JavaScript `.then`) |
| `rontolisp:then*` | `(rontolisp:then* f #'1+ #'1+)` | variadic chain sugar for `rontolisp:then`; each function receives the previous stage's flattened value |
| `rontolisp:catch` | `(rontolisp:catch f (lambda (c) :fallback))` | attach an error fallback to a future as a value (JavaScript `.catch`); distinct from `cl:catch`/`throw` |
| `rontolisp:finally` | `(rontolisp:finally f (lambda () (cleanup)))` | run a cleanup thunk on both success and error channels; the original outcome carries through |
| `rontolisp:http-handler` | `(rontolisp:http-handler 'handle 8080)` | serve HTTP requests with a handler function taking the Clack environment plist and returning `(status headers body)` (a blocking server; a `wasi:http` component under `--component`) |
| `rontolisp:json-parse` | `(rontolisp:json-parse "{\"n\": 1}")` | parse a JSON string (jzon-compatible): objects become hash tables with string keys, arrays vectors |
| `rontolisp:json-stringify` | `(rontolisp:json-stringify (vector 1 2))` | serialize a value to a JSON string (hash tables and CLOS instances become objects, lists and vectors arrays) |
| `rontolisp:plist-hash-table` | `(rontolisp:plist-hash-table (list :n 1))` | build a hash table from a property list (subset of `alexandria:plist-hash-table`); handy for JSON objects |
| `rontolisp:hash-table-plist` | `(rontolisp:hash-table-plist h)` | property list of a hash table's pairs (subset of `alexandria:hash-table-plist`) |
| `rontolisp:alist-hash-table` | `(rontolisp:alist-hash-table al)` | build a hash table from an association list (subset of `alexandria:alist-hash-table`) |
| `rontolisp:hash-table-alist` | `(rontolisp:hash-table-alist h)` | association list of a hash table's pairs (subset of `alexandria:hash-table-alist`) |
| `rontolisp:alist-plist` | `(rontolisp:alist-plist al)` | property list with an association list's keys and values, order preserved (subset of `alexandria:alist-plist`) |
| `rontolisp:plist-alist` | `(rontolisp:plist-alist pl)` | association list with a property list's keys and values, order preserved (subset of `alexandria:plist-alist`) |
| `rontolisp:tcp-connect` | `(rontolisp:tcp-connect "127.0.0.1" 7777)` | open a blocking TCP connection; returns a bidirectional stream handle |
| `rontolisp:tcp-listen` | `(rontolisp:tcp-listen 7777)`, `(rontolisp:tcp-listen 0 "127.0.0.1")` | bind a listening TCP socket and return a listener handle; port `0` picks a free ephemeral port |
| `rontolisp:tcp-accept` | `(rontolisp:tcp-accept listener)` | wait for a client connection (blocking); returns a bidirectional stream handle |
| `rontolisp:tcp-local-port` | `(rontolisp:tcp-local-port listener)` | the local port a listener or socket is actually bound to |
| `rontolisp:tcp-local-address` | `(rontolisp:tcp-local-address listener)` | the local IP address a listener or socket is bound to, as a string |
| `rontolisp:tcp-peer-address` | `(rontolisp:tcp-peer-address sock)` | the remote IP address of a connected socket, as a string |
| `rontolisp:tcp-peer-port` | `(rontolisp:tcp-peer-port sock)` | the remote port of a connected socket |
| `rontolisp:tcp-set-timeout` | `(rontolisp:tcp-set-timeout sock 5000)` | set a read deadline in milliseconds (`nil` clears); a timed-out read signals a catchable error |
| `rontolisp:tls-connect` | `(rontolisp:tls-connect "example.com" 443)` | open an encrypted (TLS) client connection; returns the same kind of stream handle as `tcp-connect` |
| `rontolisp:tls-listen` | `(rontolisp:tls-listen "server.p12" "changeit" 8443)` | bind an encrypted listening socket from a PKCS12 keystore; accept with `tcp-accept` |
| `rontolisp:tls-listen-pem` | `(rontolisp:tls-listen-pem "cert.pem" "key.pem" 8443)` | bind an encrypted listening socket from PEM certificate/key files |
| `rontolisp:tls-upgrade` | `(rontolisp:tls-upgrade sock "example.com")` | wrap an already-connected stream handle in TLS as a client; returns a new stream handle |
| `rontolisp:wasm-export` | `(rontolisp:wasm-export 'fact :params '(:int) :returns :int)` | mark a `defun` as host-callable when compiling to a WASM core module |
| `rontolisp:jvm-export` | `(rontolisp:jvm-export 'fact :params '(:s64) :returns :s64)` | declare a typed, Java-callable static method for a `defun` when compiling to a JVM class |
| `rontolisp:wasm-import` | `(rontolisp:wasm-import 'add :from "host" :params '(:int :int) :returns :int)` | declare a host function callable from Lisp when compiling to a WASM core module |
| `rontolisp:wit-export` | `(rontolisp:wit-export "greeter.wit" :world greeter)` | declare that the program implements a WIT world: its exports are checked against the program's `defun`s, and their types come from the WIT |
| `rontolisp:wit-import` | `(rontolisp:wit-import "store.wit" :interface "wasi:keyvalue/store@0.2.0" :package kv)` | declare that the program calls a WIT interface: every function it declares is bound as an ordinary Lisp function (`kv:bucket-get`), against a provider on the interpreter/JVM, a WASM import on Preview 1, and a `canon lower`ed component-model import under `--component`, where the host is the provider |
| `rontolisp:wit-provide` | `(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'my-store)` | bind the implementation of a `wit-import`ed interface on the interpreter and JVM backends (inert on WASM, where the host provides it) |

`rontolisp:fetch` starts an outgoing HTTP request and returns a future, resolved with
`rontolisp:await`; see the
[HTTP Requests guide](../../guides/http-fetch.md) for a worked overview, and the
[fetch](rontolisp-fetch.md),
[await](../special-forms/rontolisp-await.md) and
[futurep](rontolisp-futurep.md) reference pages for options, the
result plist, backend support, and limitations. `rontolisp:http-handler` is
the incoming counterpart of `fetch` -- it serves HTTP requests with a handler
function over the Clack environment plist and `(status headers body)`
response list; see the
[Serving HTTP guide](../../guides/http-handler.md) for a worked example on every
backend, and the [http-handler](rontolisp-http-handler.md) reference
page for backend support and limitations. `rontolisp:json-parse` and
`rontolisp:json-stringify` convert between JSON documents and Lisp values
(a lightweight, `com.inuoe.jzon`-compatible subset) -- for example to parse a
fetch response body; see the
[json-parse](rontolisp-json-parse.md) and
[json-stringify](rontolisp-json-stringify.md) reference pages for
the value mapping and limitations. The tcp functions
(`rontolisp:tcp-connect` / `tcp-listen` / `tcp-accept` / `tcp-local-port` and
the [address accessors](rontolisp-tcp-addresses.md))
open plain TCP sockets whose handles work with the standard stream functions
(`read-line` / `write-line` / `read-byte` / `write-byte` / `close`); see the
[TCP Sockets guide](../../guides/tcp-sockets.md) for a worked echo server, and
the [tcp-connect](rontolisp-tcp-connect.md),
[tcp-listen](rontolisp-tcp-listen.md),
[tcp-accept](rontolisp-tcp-accept.md) and
[tcp-local-port](rontolisp-tcp-local-port.md) reference pages for
backend support and limitations. A
[usocket-compatible shim](usocket.md) is layered over them for
portability with existing Common Lisp code. The TLS variants (`rontolisp:tls-connect` /
`tls-upgrade` / `tls-listen` / `tls-listen-pem`) wrap the same stream handles in TLS; see the
[tls-connect](rontolisp-tls-connect.md),
[tls-upgrade](rontolisp-tls-upgrade.md),
[tls-listen](rontolisp-tls-listen.md) and
[tls-listen-pem](rontolisp-tls-listen-pem.md) reference pages.
`rontolisp:wasm-export`,
`rontolisp:jvm-export`,
`rontolisp:wasm-import`, `rontolisp:wit-export` and `rontolisp:wit-import` are
compile-time directives; `jvm-export` is `wasm-export`'s JVM twin — a typed,
Java-callable entry point on a compiled class
([jvm-export](rontolisp-jvm-export.md)); the WIT pair take a `.wit` file as the single source of
truth for a boundary, so the types are never hand-written. `wit-export` declares
that the program **implements** a WIT world (and `--scaffold-wit` generates the
implementation's skeleton from it); `wit-import` declares that it **calls** a WIT
interface, binding every function the interface declares as an ordinary Lisp
function — dispatched on the interpreter and JVM backends to a *provider*
([`rontolisp:wit-provide`](rontolisp-wit-provide.md)), and lowered to
`rontolisp:wasm-import` on Preview 1 WASM, where the host is the provider, so one
source runs on every backend. rontolisp ships **no provider for any interface**:
it knows the provider mechanism, not what any particular interface is, so an
implementation of a WIT interface is ordinary Lisp code. A WIT `result`'s error
arm signals the `rontolisp:wit-error` condition, whose payload is read with
`rontolisp:wit-error-payload`. See their
[wasm-export](rontolisp-wasm-export.md),
[wasm-import](rontolisp-wasm-import.md),
[wit-export](rontolisp-wit-export.md),
[wit-import](rontolisp-wit-import.md) and
[wit-provide](rontolisp-wit-provide.md) reference pages and the
[Compiling to WebAssembly](../../compiling/wasm.md) guide.

