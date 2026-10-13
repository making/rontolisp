# f73. Clojure on wasm: a portable reitit router builds its name lookup over `(HashMap. ...)`

Difficulty: Medium

Found by the f65 re-probe (`.kb/clojure-frontend.md`, "Library probes", 2026-10-13):
with `clojure-trie-compiler`, `(r/router routes {::trie/trie-compiler
(trie/clojure-trie-compiler)})` prints the oracle's lines on the interpreter and the
JVM, a single static route also builds on wasm, but any route with path parameters
fails router construction on wasm with the `java:` call-time error (`The function
JAVA:NEW is undefined`, caught by the program's own `catch`).

The construction is `reitit.impl/fast-map`: `#?(:clj (let [m (or m {})] (HashMap.
^Map m)))`, called by `trie-router` (and `lookup-router`/`mixed-router`) for the
name lookup. `(.get m k)` on it already answers through the value-method row (a map
answers `.get`); only the construction needs the host.

## What decides the design

- Whether `(java.util.HashMap. m)` lowers to the map itself (like `(StringBuilder.)`
  to a string output stream, `.todo/f63`): a `HashMap` is mutable, the value here is
  not -- `.put` on the lowered value must refuse like the oracle's
  `UnsupportedOperationException`, or the construction stays host-only.
- `URLDecoder/decode` and `URLEncoder/encode` in the same namespace are only called
  with query strings; they stay host-only unless a probe needs them.
