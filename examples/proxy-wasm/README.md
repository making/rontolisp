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

The SDK covers logging, the clock, ticks, header maps, body and configuration
buffers, local responses, outgoing HTTP calls, shared data, metrics and
properties. It does not cover gRPC calls, shared queues, foreign functions or the
TCP (network filter) callbacks.

An error signaled in a callback is logged and the callback answers its default
instead of trapping, because a trap would take the VM down. A filter that wants the
trap sets `proxy-wasm:*trap-on-error*`, and `proxy-wasm:panic` traps from anywhere.
A host call answering anything but OK signals, except that a read of something absent
answers `nil`. Each callback also pops the bump heap on its way out; everything the
SDK answers is a Lisp object, so it can be kept in a slot across callbacks.

## Smaller filters on the same SDK

`sdk-examples/` holds three more filters: `http_body.lisp` holds a response body and
redacts it, `http_headers.lisp` logs both heads and answers `/hello` itself, and
`test_plugin.lisp` does what the request's path says, for testing a host. It is built
without `--no-wasi`, because one of its paths writes to stdout: the SDK works either way,
and a `--no-wasi` module drops what it writes there.
`sdk-examples/check.sh` builds each one and checks it in a real Envoy.

## What Envoy does that the ABI does not say

Both were found by running this filter, and reproduce on Envoy v1.39.3 and v1.40.2.

- `proxy_on_http_call_response` arrives with the **plugin's** context id, whichever
  stream made the call. The SDK records which context made each call, and calls
  `proxy_set_effective_context` so that the reply's host calls reach that stream.
- A `:pause` from `on-request-headers` also stops the body callbacks until the
  stream continues. A filter that inspects the body answers `:continue` from the
  headers and `:pause` from `on-request-body` until the body is complete, as
  `gateway.lisp` does. The upstream has the headers by then, so a refusal at that
  point resets that request.

## How the SDK reaches the host

The ABI's host functions answer through out-pointers: the module passes the
addresses of cells it reserved, and the host writes its answers there. The SDK
reserves and reads them through the reserved import module `"rontolisp:memory"`,
and declares each address, size and host-issued id as `:u32` so that it crosses
exactly for as long as the VM lives. See the
[WASM host boundary guide](../../doc/en/guides/wasm-host-boundary.md#importing-host-functions).
