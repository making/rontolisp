# e28. Ring adapter for the Clojure front end

Difficulty: Medium

Serve a Clojure Ring handler (request map -> response map) on every transport the Clack
`:server :rontolisp` shim serves: interpreter/JVM socket, Servlet war, `--component`,
reactor. Ring's contract needs no library, only an adapter, and the adapter is a
conversion onto the existing Clack protocol (`.kb/http-server.md`, `.kb/clack.md`).

## Measured (2026-10-08, exec jar of 2026-10-03)

A hand-written Common Lisp bridge (`(load "src/app/core.clj")`, env plist -> Clojure map,
response map -> `(status headers body)`, `rontolisp:http-handler 'app ... :raw-body
:buffered`) served a destructuring Ring handler on the interpreter, a `-o app.jar` and a
`--component` module under `wasmtime serve` (198,810 B): GET with query and `user-agent`,
POST, 404. Not tried: war, reactor.

- Clack's `:headers` (equal hash table, lowercased string keys) IS a Clojure map: no
  conversion.
- A keyword is `(:C%KEYWORD "name")`, a map `rontolisp:plist-hash-table ... :test 'equal`.
- `:url-scheme` is not always a symbol (`SYMBOL-NAME expects a symbol, got "http"`): use
  `string`.

## Plan

1. A built-in namespace `ring.adapter.rontolisp` exposing `run-server`
   `(handler opts)` (`:port`, `:host`/`:address`, `:join?`). Resolves like
   `clojure.string` (`ClojureNamespaceLowering`); its call lowers to a spliced
   `rontolisp::%ring-*` runtime. Clojure has no documented way to call a Common Lisp
   function, so the adapter must be built in, not a user library.
2. Do NOT copy the four `#+` transport legs of `clack-handler-rontolisp.lisp`: factor
   them into one internal serve function both the Clack shim and the Ring adapter call
   (the WASI leg's literal quoted `%app` name indirection included).
3. Request map: `:request-method` (lowercased keyword), `:uri` (raw path: `:request-uri`
   up to `?`, NOT the decoded `:path-info`), `:query-string`, `:headers`, `:server-port`,
   `:server-name`, `:remote-addr`, `:scheme` (keyword), `:protocol` (string), `:body`.
4. Response map: `:status`; `:headers` with string names (pass a dotted alist, which the
   normalizer accepts, instead of interning keywords) and a vector value expanding to one
   header line per element; `:body` String (wrap in a list), seq of strings, byte array,
   stream; `File` is refused by the response contract today -- decide (read it, or refuse
   by name).
5. Request body: Ring's `:body` is an `InputStream`; here it is the `:buffered` Lisp
   bivalent stream. Measured failures: `(slurp body)` -> `OPEN expects a pathname
   designator`; JVM `(InputStreamReader. body)` -> no matching constructor. Make `slurp`,
   `clojure.java.io/reader` and `line-seq` accept a Lisp stream on every backend (not a
   JVM-only real `InputStream`, which would split the backends).
6. Async (3-arity) handlers: refuse by name.
7. Example under `examples/clojure/` (one source, the Clack example's transport matrix),
   pinned on all four backends (`.kb/running-backends.md`); docs in `doc/en` + `doc/ja`
   (`doc/*/clojure/`).

## Follow-ups (not this item)

- Ring-compatible util namespaces: e29.
- Wrapping the converted Clack app with `lack:builder` middleware (accesslog, static).
- Loading Clojars dependencies from `deps.edn` `:deps`: low value, ring-core/compojure/
  reitit lean on Java interop and would not run on wasm.
