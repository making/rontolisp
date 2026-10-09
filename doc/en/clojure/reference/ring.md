# Ring adapter (ring.adapter.rontolisp)

`ring.adapter.rontolisp/run-server` serves a [Ring](https://github.com/ring-clojure/ring)
handler -- a function of a request map answering a response map -- on the target's own
inbound HTTP transport. It is built in: require it like `clojure.string`. The one source
runs on every transport the Clack backend `:server :rontolisp` serves
([Clack](../../guides/clack.md)): both adapters call the same transport code.

| Target | Build | Transport |
|---|---|---|
| Interpreter | `rontolisp app.clj` | a socket the program binds (`:port`, `:host`) |
| JVM | `-o App.class`, `-o app.jar` | the same socket server |
| Servlet war | `-o app.war` | the container owns the port; `run-server` registers the handler and returns |
| WASM component | `-o app.wasm --component`, run with `wasmtime serve` | the host owns the socket; `run-server` returns at once |
| WASM reactor | `-o app.wasm --no-wasi` | the host calls the `handle-request` export ([the reactor build](../../guides/clack.md#a-host-that-calls-you-the-reactor-build)) |

A plain Preview 1 module (`-o app.wasm`) has no incoming connections: it compiles, and
`run-server` signals that serving requires `--component` when it runs.

| Name | Example | Result |
|---|---|---|
| `ring.adapter.rontolisp/run-server` | `(run-server handler {:port 3000})` | blocks; `:join? false` answers the server |

```console
$ cat app.clj
(ns app (:require [ring.adapter.rontolisp :refer [run-server]]))

(defn handler [{:keys [request-method uri]}]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body (str (name request-method) " " uri "\n")})

(run-server handler {:port 3000 :host "127.0.0.1"})
$ rontolisp app.clj &
$ curl http://127.0.0.1:3000/hello
get /hello
```

## The request map

| Key | Value |
|---|---|
| `:request-method` | the method as a lower-case keyword: `:get`, `:post`, ... |
| `:uri` | the request path as sent (not percent-decoded), without the query |
| `:query-string` | the text after `?`, or `nil` |
| `:headers` | a map of lower-case header names to values; repeated headers joined with `", "` |
| `:server-name`, `:server-port` | from the `Host` header, else the listening address |
| `:remote-addr` | the peer address; `nil` on the component and on a reactor whose host sends none |
| `:scheme` | `:http` or `:https` |
| `:protocol` | `"HTTP/1.1"` |
| `:content-type`, `:content-length` | from the headers, `nil` when absent |
| `:body` | an input stream over the request body, `nil` for a request without one |

Read `:body` with `slurp`, `line-seq` or `clojure.java.io/reader`; a
`java.io.InputStreamReader` (or `BufferedReader`) over it is the stream itself, on
every backend. The stream is buffered, so the read never waits on the network.
`slurp` closes it; a later read answers the end of the body, as under Ring's Jetty
adapter.

## The response map

| Key | Value |
|---|---|
| `:status` | an integer; 200 when absent |
| `:headers` | a map of header names (strings or keywords) to a string, or to a seq of strings sent as one header line each |
| `:body` | a string, a seq whose members are sent through `str`, a reader or input stream (read to its end and closed), a `java.io.File` (its bytes as they are), or `nil` |

A handler that answers anything but a map, or a body of any other kind, signals; the
transport answers 500. So does a `java.io.File` naming no file the program can read: the
WebAssembly serving transports (`wasmtime serve`, the reactor) have no file system, so a file
is served on the interpreter, the JVM and a war; a byte stream over a
[resource](clojure-java-io.md) the program carries is served everywhere. The response
builders, `file-response` and `resource-response` among them, and the parameter middleware
are the built-in [Ring utilities](ring-util.md).

## Cloudflare Workers

A Worker calls the `handle-request` export, so the reactor build deploys as one with no
change to the source. `--emit-js-glue` writes the JavaScript half beside the module:

```console
$ rontolisp app.clj -o src/worker.wasm --no-wasi --optimize=size --emit-js-glue
$ cat src/index.js
import module from "./worker.wasm";
import { worker } from "./worker.js";

export default worker(module);
$ npx wrangler dev      # http://localhost:8787
$ npx wrangler deploy
```

`:port` and `:host` are not read there. A complete project, with `wrangler.jsonc` and a
`build.sh` over the same Ring example the other targets build, is
[`examples/cloudflare-workers/ring-hello-one-source/`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/ring-hello-one-source).
