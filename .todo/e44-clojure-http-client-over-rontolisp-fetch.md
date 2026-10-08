# e44. A Clojure-idiomatic HTTP client over `rontolisp:fetch`

Difficulty: High

A Clojure program cannot make an outgoing HTTP request: Clojure has no way to name a Common
Lisp function (`.kb/clojure-frontend.md`, "Ring adapter"), so `rontolisp:fetch` /
`rontolisp:await` (`.kb/fetch-http.md`) are unreachable. Give it a built-in namespace that
LOWERS to them (the `ring.adapter.rontolisp` precedent): no new transport on any backend, the
same four transports (JDK, `wasi:http` component, `--no-wasi --host-fetch`, the `--native`
runner).

## Surface (proposed; settle in step 1)

Follow an existing Clojure client's API rather than invent one: `babashka.http-client`
(`get`/`post`/`put`/`delete`/`head`/`patch`/`request`, `:throw`, `:as`) or hato.
A rontolisp-named namespace (`rontolisp.http-client`) with that API; whether the
`babashka.http-client` name itself also resolves is part of the decision (a claimed name
promises its options).

```clojure
(ns app.core (:require [rontolisp.http-client :as http]))
(:body (http/get "https://example.com" {:headers {"accept" "text/plain"}}))
@(http/get "https://example.com" {:async true})
```

## Gaps

1. Lowering slice (`Clojure*Lowering`) + run-time helpers in `clojure.lisp`; the namespace
   resolves like the other built-ins (`isKnownNamespace`), arity refusals in the chosen
   API's wording, each verb usable as a value.
2. Request conversion: method keyword, header map (keyword or string names) -> the fetch
   `:headers` alist, `:body` string or stream, `:query-params`/`:form-params` encoded
   through the existing Ring codec kernels (`.kb/clojure-frontend.md`, "Ring util namespaces").
3. Response conversion: the plist `(:status :headers :body)` -> `{:status :headers :body}`;
   headers as a string-keyed map (a repeated field: decide join vs vector, the chosen API's
   rule); `:as :string` (default, drained) / `:stream` (the CL stream, which `slurp` and
   the reader wrappers already take). `:as :bytes` has no value kind (`bytes?` is
   constantly false): refuse by name.
4. Futures on every backend: `deref`/`@`, `realized?`, `future?`, timed `deref` today
   read only a HOST `java.util.concurrent.Future` (interpreter/JVM). The fetch future is a
   `LispFuture` / `CompletableFuture` / wasm `TYPE_FUTURE` / `TYPE_P1_FUTURE`; `deref`
   must reach `await` on all four, and a program that never fetches must not grow.
   `:async true` answers the future; the sync verbs are `await` inside.
5. Errors: a non-2xx status throws `ex-info` carrying the response when `:throw` is true
   (the chosen API's default); a transport failure (CL error, or `rontolisp:wit-error` on
   wasm) surfaces as a Clojure exception catchable by `(catch Exception e)`
   (`.kb/clojure-frontend.md`, "Exceptions", "Catching"). Timing stays fetch's: options at
   the call, transport at the deref.
6. Gates: `--no-wasi` without `--host-fetch` and plain P1 keep their compile errors; the
   messages must make sense from a `.clj` file. Check `HostFetchLibrary`'s "program
   references rontolisp:fetch" gate sees the lowered call.
7. Related CL items, not prerequisites: `148` (timeouts; a `:timeout` option waits for it
   or is refused by name), `150` (`*fetch-handler*` interception; the Clojure surface
   should route through it if it lands first).

## Plan

1. Read `.kb/fetch-http.md`, `.kb/async-await.md`, `.kb/clojure-frontend.md` ("Ring adapter",
   "Java interop" host `Future` under `deref`, "Exceptions"). Pick the API (step 1 above).
2. Gap 4 first (deref over a rontolisp future, four backends, byte-identity of a program
   without a future), then 1-3, 5, 6.
3. Tests on all four backends (`.kb/running-backends.md`) against a local server; the
   `--host-fetch` leg under node. A Ring handler proxying an upstream fetch under
   `wrangler dev` is the Worker check (manual, as for e31).
4. `.kb/clojure-frontend.md` section; `doc/en` + `doc/ja` Clojure pages.
