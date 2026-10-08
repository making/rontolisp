# ring.adapter.rontolisp/run-server

`(run-server handler options)` with `[ring.adapter.rontolisp :refer [run-server]]`

Serves the Ring `handler` -- a one-argument function, or a var naming one -- on the
target's inbound transport ([Ring adapter](ring.md)). The options map:

- `:port` -- the port to listen on, 80 by default (like `ring.adapter.jetty`);
- `:host` (or `:address`) -- the address to bind, every interface by default;
- `:join?` -- block until the server stops, true by default. With `false` it answers
  the server at once and the program keeps running.

The three matter only where the program owns the socket, the interpreter and the JVM; a
war, a component and a reactor ignore them and return at once. `:async? true` is refused:
a three-argument (asynchronous) handler has no respond/raise protocol here. One server per
process: a second `run-server` replaces the first one's handler on the compiled backends.
As a value a two-argument function.

```console
$ cat app.clj
(ns app (:require [ring.adapter.rontolisp :refer [run-server]]))
(defn handler [req] {:status 200 :body (str "hello " (:uri req))})
(run-server handler {:port 3000})
$ rontolisp app.clj -o app.wasm --component && wasmtime serve -W gc=y -W exceptions=y app.wasm
```
