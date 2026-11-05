# proxy-wasm: Envoy filters in Lisp

A [Proxy-Wasm](https://github.com/proxy-wasm/spec) SDK written from scratch in
Lisp, and an HTTP filter built on it that runs in Envoy. Use this as a starting
point for extending Envoy, or any other Proxy-Wasm host, with a filter written in
rontolisp. No other language or SDK is involved: the module is the output of
`rontolisp gateway.lisp --no-wasi`.

| File | Purpose |
| --- | --- |
| `proxy-wasm.lisp` | The SDK: the ABI v0.2.1 imports and exports, and the CLOS API over them |
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

A filter subclasses `proxy-wasm:root-context` (one per plugin on each worker) and
`proxy-wasm:http-context` (one per stream), and specializes the callbacks it needs:

```lisp
(require :proxy-wasm "proxy-wasm.lisp")

(defpackage :my-filter (:use :cl))
(in-package :my-filter)

(defclass my-root (proxy-wasm:root-context) ())
(defclass my-stream (proxy-wasm:http-context) ())

(defmethod proxy-wasm:make-http-context ((root my-root) id)
  (make-instance 'my-stream :id id :root root))

(defmethod proxy-wasm:on-request-headers
    ((stream my-stream) num-headers end-of-stream)
  (declare (ignore num-headers end-of-stream))
  (cond ((equal (proxy-wasm:get-header :request ":path") "/teapot")
         (proxy-wasm:send-local-response 418 :body "short and stout")
         :pause)
        (t (proxy-wasm:set-header :request "x-from-lisp" "hello")
           :continue)))

(proxy-wasm:set-root-context (lambda (id) (make-instance 'my-root :id id)))
```

The examples write the package out on every SDK name, so a reader can tell what comes
from the SDK.

Compile with `--no-wasi`, and load the module with Envoy's
`envoy.filters.http.wasm` filter as `envoy.yaml` does.

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
callback answers its default instead of trapping, because a trap would take the VM down.
`proxy-wasm:panic` traps from anywhere. Each callback also pops the bump heap on its way
out; everything the SDK answers is a Lisp object, so it can be kept in a slot across
callbacks.

## What a choice costs a filter

Measured at `--no-wasi --optimize=size`, gzip in parentheses. `test_plugin` is built without
`--no-wasi`.

| Filter | With a handler | With `:proxy-wasm-trap` |
| --- | ---: | ---: |
| `http_body` | 75,097 (27,616) | 59,633 (23,065) |
| `http_headers` | 77,312 (28,346) | 61,385 (23,764) |
| `test_plugin` | 87,791 (32,190) | 70,815 (26,998) |

- **Errors trap, or are handled.** The handler in every callback costs 15-17KB raw and
  4.5-5.2KB gzip. A filter gives it up by declaring the feature `:proxy-wasm-trap` while it
  compiles, either on the command line (`--feature proxy-wasm-trap`) or ahead of its `require`:

  ```lisp
  (eval-when (:compile-toplevel) (pushnew :proxy-wasm-trap *features*))
  (require :proxy-wasm "proxy-wasm.lisp")
  ```

  Then an error traps, as an `unwrap` in the Rust SDK panics, and no callback answers its default.
  The VM is gone: Envoy answers 503 to that request and to every one after it. All of
  `sdk-examples/` but `error_log.lisp` do this.
- **The log gives the reason for a failed host call, not for any other error.** Printing a
  condition would cost every filter about 10KB raw. A filter that wants the report catches the
  error itself and prints it, and pays for that: `(handler-case ... (error (c) (proxy-wasm:log-error "~a" c)))`.
  A failed call is logged where it fails, also when a filter goes on to handle the error.
- **A `log-*` control string is compiled when it is a literal.** One call with a computed control
  string, or one `#'proxy-wasm:log-info`, brings the run-time `format` interpreter into the
  module, which is about 60KB raw and 20KB gzip.
- **Contexts live in a table of 64 buckets**, 4KB raw smaller than a hash table. A lookup walks
  1/64 of the live streams of its VM.

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
a host without wasm-GC. A filter is one form, and the macro writes the exports:

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

| Filter, at `--optimize=size` | On this SDK: raw / gzip | On `proxy-wasm.lisp` with `:proxy-wasm-trap`: raw / gzip |
| --- | ---: | ---: |
| `http_body` | 2,064 / 1,152 | 59,633 / 23,065 |
| `http_headers` | 2,583 / 1,388 | 61,385 / 23,764 |
| `test_plugin` | 6,581 / 3,145 | 70,815 / 26,998 |

What it gives up, for a filter's author:

| | `proxy-wasm.lisp` | `no-gc/proxy-wasm.lisp` |
| --- | --- | --- |
| A context | An object of a class you define | Its id, an integer |
| A callback | A method | A clause of `define-filter`; a body answers `:continue` or `:pause` |
| State | Slots holding anything | `(:state ...)`: integers, and strings of a size fixed in advance. Every context has all the fields. Setting a string that does not fit answers `nil` and keeps the old one |
| A string | Lives as long as it is reachable | Gone when the callback returns, unless a field holds a copy |
| A body | A string or a byte vector | An address and a size, with `load8` and `search-bytes`, and it can be changed in place. As a string, `char` would walk from the start at each index |
| Headers | An alist | `do-headers`, and `make-pairs` to build a map. A quoted alist of at most 127 bytes per name or value is serialized at compile time |
| Other values | Everything Lisp has | Numbers, strings, a keyword spelled in the SDK's call. No lists, no closures, no `defvar` |
| A failed host call | Signals; logged and the callback answers its default, or traps under `:proxy-wasm-trap` | Answers `nil` and the callback goes on, after a log line that gives the status but not the call, or traps under `(:on-failure :trap)`. A refused `http-call` answers `nil` on both |
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
- A `:pause` from `on-request-headers` also stops the body callbacks until the
  stream continues. A filter that inspects the body answers `:continue` from the
  headers and `:pause` from `on-request-body` until the body is complete, as
  `gateway.lisp` does. The upstream has the headers by then, so a refusal at that
  point resets that request.

- During `on-response-headers` the request's header map answers status 2 and the properties
  read from it (`request.path`) are absent, though `on-log` reads them again. A filter that
  needs the request there keeps what it read in `on-request-headers`, as `test_plugin.lisp`
  does.

## How the SDK reaches the host

The ABI's host functions answer through out-pointers: the module passes the
addresses of cells it reserved, and the host writes its answers there. The SDK
reserves and reads them through the reserved import module `"rontolisp:memory"`,
and declares each address, size and host-issued id as `:u32` so that it crosses
exactly for as long as the VM lives. See the
[WASM host boundary guide](../../doc/en/guides/wasm-host-boundary.md#importing-host-functions).
