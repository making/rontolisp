# proxy-wasm: Envoy filters in Lisp

A [Proxy-Wasm](https://github.com/proxy-wasm/spec) SDK written from scratch in
Lisp, and an HTTP filter built on it that runs in Envoy. Use this as a starting
point for extending Envoy, or any other Proxy-Wasm host, with a filter written in
rontolisp. No other language or SDK is involved: the module is the output of
`rontolisp gateway.lisp --no-wasi`.

| File | Purpose |
| --- | --- |
| `proxy-wasm.lisp` | The SDK: the ABI v0.2.1 imports and exports, and `define-filter` over them |
| `gateway.lisp` | The filter |
| `envoy.yaml` | An Envoy running the filter in front of a small upstream |
| `check.sh` | Builds the filter, runs it in Envoy under Docker and checks it with curl |
| `sdk-examples/` | Four smaller filters on the same SDK |
| `no-gc/` | A second SDK, for `--no-gc`, and the same four filters on it |

## Running it

With the executable JAR built (`./mvnw clean package`) and Docker running:

```console
$ examples/proxy-wasm/check.sh
compiling gateway.lisp -> gateway.wasm
ok   - a plain request reaches the upstream
...
ok   - the bump heap is flat over 300 requests (46400)
all checks passed
```

To try it by hand, build it and start Envoy from this directory:

```console
$ java -jar ../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar gateway.lisp -o gateway.wasm --no-wasi --optimize
$ docker run --rm -p 18080:8080 -p 19901:9901 \
    -v "$PWD/envoy.yaml:/etc/envoy/envoy.yaml:ro" \
    -v "$PWD/gateway.wasm:/etc/envoy/gateway.wasm:ro" \
    envoyproxy/envoy:v1.40.2 -c /etc/envoy/envoy.yaml
$ curl -i localhost:18080/admin/users                  # 403 from the filter
$ curl -i -H 'x-user: alice' localhost:18080/guarded   # 200, after an authorization call
$ curl -i -H 'x-user: mallory' localhost:18080/guarded # 403 from the call's reply
$ curl localhost:18080/shout                           # HELLO FROM UPSTREAM
$ curl -s 'localhost:19901/stats?filter=gateway\.'     # the filter's metrics
```

## Writing a filter

A filter is one form, whose clauses are the callbacks it needs. Everything else in the
module is what those clauses call, so a filter pays for what it uses:

```lisp
(require :proxy-wasm "proxy-wasm.lisp")

(defpackage :my-filter (:use :cl))
(in-package :my-filter)

(proxy-wasm:define-filter
  (:on-request-headers (stream num-headers end-of-stream)
    (cond ((equal (proxy-wasm:get-header :request ":path") "/teapot")
           (proxy-wasm:send-local-response 418 :body "short and stout")
           :pause)
          (t (proxy-wasm:set-header :request "x-from-lisp" "hello")
             :continue))))
```

Compile with `--no-wasi`, and load the module with Envoy's
`envoy.filters.http.wasm` filter as `envoy.yaml` does.

| Clause | Parameters | Answers |
| --- | --- | --- |
| `:on-vm-start`, `:on-configure` | `(root size)` | A boolean: whether the VM or the plugin may start |
| `:on-tick` | `(root)` | |
| `:on-request-headers`, `:on-response-headers` | `(stream num-headers end-of-stream)` | `:continue` or `:pause` |
| `:on-request-body`, `:on-response-body` | `(stream body-size end-of-stream)` | `:continue` or `:pause` |
| `:on-request-trailers`, `:on-response-trailers` | `(stream num-trailers)` | `:continue` or `:pause` |
| `:on-http-call-response` | `(context token num-headers body-size num-trailers)` | |
| `:on-context-create` | `(id parent)`, both ids | |
| `:on-done` | `(context)` | A boolean: whether the context is finished |
| `:on-log`, `:on-delete` | `(context)` | |

A parameter a body does not use needs no declaration. `:on-done`, `:on-log` and
`:on-delete` run for any context, a root's too. A clause the SDK does not know, one given
twice, or one with the wrong number of parameters fails the compile, as a mistyped
`(:on-reqest-headers ...)` should.

A context is its id, an integer, and the module holds nothing per context. Two clauses
make it an object instead, of any kind but `nil`, and everything Lisp has works in it:

```lisp
(defclass my-root () ((banner :initform "rontolisp" :accessor banner)))
(defclass my-stream ()
  ((root :initarg :root :reader stream-root)
   (path :initform "" :accessor stream-path)))

(proxy-wasm:define-filter
  (:root (id) (make-instance 'my-root))
  (:stream (id root) (make-instance 'my-stream :root root))
  (:on-request-headers (stream num-headers end-of-stream)
    (setf (stream-path stream) (or (proxy-wasm:get-header :request ":path") ""))
    :continue)
  (:on-response-headers (stream num-headers end-of-stream)
    (proxy-wasm:add-header :response "x-powered-by" (banner (stream-root stream)))
    :continue))
```

A filter that wants generic functions writes them and calls them from its clauses; one that
wants a stream's id keeps the one `:stream` is given.

A module exporting `proxy_abi_version_*` starts without the transient array, at least
16 MiB, with which other wasm-GC modules grow the engine's GC heap for wasmtime's copying
collector. On a host running that collector, size its initial GC heap if a filter's
collections show up in its latency.

The SDK covers logging, the clock, ticks, header maps, body and configuration
buffers, local responses, outgoing HTTP calls, shared data, metrics and
properties. It does not cover gRPC calls, shared queues, foreign functions or the
TCP (network filter) callbacks.

A host call answering anything but OK logs the call's name and the status and signals,
except that a read of something absent answers `nil` and `proxy-wasm:http-call` answers
`nil` when the host refuses the call. An error signaled in a callback is logged and the
callback answers its default instead of trapping, because a trap would take the VM down:
`:continue`, false from `:on-vm-start` and `:on-configure`, and true from `:on-done`. A context
goes when the host deletes it, whatever `:on-delete` does. `proxy-wasm:panic` traps from
anywhere. Each callback also pops the bump heap on its way out; everything the SDK answers is
a Lisp object, so it can be kept in a slot across callbacks. `with-buffer`'s address is the
exception: the bytes behind it go with the callback.

`log-*`, `send-local-response`, `http-call` and `set-buffer` are macros, so `#'proxy-wasm:log-info`
is not a function.

## What a choice costs a filter

Measured at `--no-wasi --optimize=size`, gzip in parentheses; `test_plugin` is built without
`--no-wasi`. Instructions are those of one exchange through a wasm interpreter, on a 5-byte
body. The Rust SDK's `http_body` runs 2,342 of them, from a module of 92,163 bytes.

| `http_body` with | Bytes | Instructions, 5 bytes | 64KiB |
| --- | ---: | ---: | ---: |
| `(:on-failure :trap)` | 4,872 (2,563) | 1,116 | 222,976 |
| A handler in the callbacks given, the default | 5,172 (2,662) | 1,160 | 223,056 |
| `(:stream (id root) (make-instance 'my-stream))` | 10,061 (4,719) | 2,287 | 224,307 |
| `get-buffer` and `search`, not `with-buffer` and `search-bytes` | 6,945 (3,537) | 1,443 | 1,967,919 |

- **Errors trap, or are handled.** The handler costs `http_body` 300 bytes raw and 100 gzip, a
  filter that does as much as `test_plugin` 3KB and 1KB, and about 22 instructions per callback
  given. A filter gives it up with `(:on-failure :trap)`. Then an error traps, as an `unwrap` in
  the Rust SDK panics, and no callback answers its default. The VM is gone: Envoy answers 503 to
  that request and to every one after it. All of `sdk-examples/` but `error_log.lisp` do this.
- **The log gives the reason for a failed host call, not for any other error.** Printing a
  condition would cost every filter the printer. A filter that wants the report catches the
  error itself and prints it, and pays for that, 22KB raw and 8KB gzip:
  `(handler-case ... (error (c) (proxy-wasm:log-error "~a" c)))`.
  A failed call is logged where it fails, also when a filter goes on to handle the error.
- **A `log-*` control string is compiled when it is a literal.** A computed one brings the
  run-time `format` interpreter into the module, 82KB raw and 30KB gzip on `http_body`. A literal
  with `~s` brings the printer that knows packages, 22KB raw and 8KB gzip; one with `~a` and `~d`
  44 bytes.
- **A body is searched where the host put it.** `with-buffer` binds its address and size, and
  `search-bytes` looks through it at 3.4 instructions per byte, with no copy. `get-buffer` copies
  it to the engine's heap, and with `search` that is 30 per byte. A loop over `get-buffer-octets`
  is 155.
- **A JSON body is read and edited there too.** `rontolisp:json-span` over `with-buffer`'s address
  and size checks the document and answers a value's byte offsets, about 18 instructions per byte,
  and `set-buffer`'s `:start` and `:size` splice there; `rontolisp:json-value` reads one value.
  `json-parse` and `json-stringify` over `get-buffer` build the whole tree, 102 per byte, and lose
  a body past 256KiB to the default 16MB.
- **Contexts live in a table of 64 buckets**, up to 4KB raw smaller than a hash table, and only in a
  filter that has `:root`, `:stream` or `:on-http-call-response`. A lookup walks 1/64 of the
  live streams of its VM, unless it is for the context the last callback took.

## Smaller filters on the same SDK

`sdk-examples/` holds four more filters: `http_body.lisp` holds a response body and
redacts it, `http_headers.lisp` logs both heads and answers `/hello` itself,
`error_log.lisp` shows what an error does under the SDK's default, and
`test_plugin.lisp` does what the request's path says, for testing a host. It is built
without `--no-wasi`, because one of its paths writes to stdout: the SDK works either way,
and a `--no-wasi` module drops what it writes there.
`sdk-examples/check.sh` builds each one and checks it in a real Envoy.

## Filters of 2KB: `no-gc/`

`no-gc/proxy-wasm.lisp` is the same ABI for the `--no-gc` backend, which also runs on
a host without wasm-GC. A filter is the same form, and a `(:state ...)` clause in the place
of `:root` and `:stream`:

```lisp
(require :proxy-wasm "proxy-wasm.lisp")

(defpackage :my-filter (:use :cl))
(in-package :my-filter)

(proxy-wasm:define-filter
  (:state (path 256))
  (:on-request-headers (stream num-headers end-of-stream)
    (setf (path stream) (or (proxy-wasm:get-header :request ":path") ""))
    (cond ((string= (path stream) "/teapot")
           (proxy-wasm:send-local-response 418 :body "short and stout")
           :pause)
          (t :continue))))
```

Compile with `--no-gc --no-wasi`, and `--optimize=size` for the sizes below. `no-gc/check.sh`
builds the filters there and runs on them the checks of `sdk-examples/check.sh`.

| Filter, at `--optimize=size` | On this SDK: raw / gzip | On `proxy-wasm.lisp` with `(:on-failure :trap)`: raw / gzip |
| --- | ---: | ---: |
| `http_body` | 2,166 / 1,246 | 4,872 / 2,563 |
| `http_headers` | 2,583 / 1,388 | 7,114 / 3,433 |
| `test_plugin` | 6,581 / 3,145 | 31,340 / 13,012 |

What it gives up, for a filter's author:

| | `proxy-wasm.lisp` | `no-gc/proxy-wasm.lisp` |
| --- | --- | --- |
| A context | Its id, or any object you make | Its id, an integer |
| A body's answer | `:continue` or `:pause`, from anywhere in a body or a function it calls | `:continue` or `:pause` where a clause spells it; a number from a function |
| State | Anything, in the object or in a `defvar` | `(:state ...)`: integers, and strings of a size fixed in advance. Every context has all the fields. Setting a string that does not fit answers `nil` and keeps the old one |
| A string | Lives as long as it is reachable | Gone when the callback returns, unless a field holds a copy |
| A body | An address and a size, with `load8` and `search-bytes`, or a string or a byte vector | An address and a size, with `load8` and `search-bytes`, and it can be changed in place. As a string, `char` would walk from the start at each index |
| Headers | An alist | `do-headers`, and `make-pairs` to build a map. A quoted alist of at most 127 bytes per name or value is serialized at compile time |
| Other values | Everything Lisp has | Numbers, strings, a keyword spelled in the SDK's call. No lists, no closures, no `defvar` |
| A failed host call | Signals; logged and the callback answers its default, or traps under `(:on-failure :trap)` | Answers `nil` and the callback goes on, after a log line that gives the status but not the call, or traps under `(:on-failure :trap)`. A refused `http-call` answers `nil` on both |
| `format` | All of it | To `t` or `nil`, a literal control of `~a`, `~d`, `~%`, `~~` |
| Not covered | gRPC, queues, foreign functions, TCP | Those, and the clock, ticks, shared data, setting a property, continuing a paused stream, and finding the stream that made an outgoing call |

Nothing here reaches a filter that does not call it. A callback a filter leaves out is exported
with the answer a filter would give, because Sluice refuses a module without it; the trailers and
`on_http_call_response` are exported only when given. State costs 395 bytes raw and 199 gzip the
first time, and each context's block is linear to find in the contexts alive, which are few per VM.

## What Envoy does that the ABI does not say

All were found by running these filters. The first two reproduce on Envoy v1.39.3 and v1.40.2,
the third was seen on v1.40.2.

- `proxy_on_http_call_response` arrives with the **plugin's** context id, whichever
  stream made the call. The SDK records which context made each call, and calls
  `proxy_set_effective_context` so that the reply's host calls reach that stream.
- A `:pause` from `:on-request-headers` also stops the body callbacks until the
  stream continues. A filter that inspects the body answers `:continue` from the
  headers and `:pause` from `:on-request-body` until the body is complete, as
  `gateway.lisp` does. The upstream has the headers by then, so a refusal at that
  point resets that request.

- During `:on-response-headers` the request's header map answers status 2 and the properties
  read from it (`request.path`) are absent, though `:on-log` reads them again. A filter that
  needs the request there keeps what it read in `:on-request-headers`, as `test_plugin.lisp`
  does.

## How the SDK reaches the host

The ABI's host functions answer through out-pointers: the module passes the
addresses of cells it reserved, and the host writes its answers there. The SDK
reserves and reads them through the reserved import module `"rontolisp:memory"`,
and declares each address, size and host-issued id as `:u32` so that it crosses
exactly for as long as the VM lives. See the
[WASM host boundary guide](../../doc/en/guides/wasm-host-boundary.md#importing-host-functions).
