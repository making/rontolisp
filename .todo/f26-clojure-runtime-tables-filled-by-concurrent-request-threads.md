# f26. The Clojure runtime's other global tables are filled by concurrent request threads

Difficulty: Medium

The `clojure.java.io` tables were fixed (kept at the program's start, or read and written under
`%clojure-io-registry-guard`; `.kb/concurrent-served-requests.md`). `clojure.lisp` has more
process-wide tables of the same shape -- created on first use (`(if (null T) (setq T
(make-hash-table ...)))`) and written where a call runs -- which a Ring handler's threads (one per
request on the interpreter and the JVM) can reach at once:

- `%clojure-meta-table` (`%clojure-put-meta`, `with-meta`/`vary-meta` of a value carrying no slot)
- `%clojure-var-table` (`%clojure-var`, a var interned on first `#'x`)
- `%clojure-key-classes` / `%clojure-key-reps` (`%clojure-key-class`, structural map and set keys)
- `%clojure-typed-kinds` (a deftype key's representative)
- `%clojure-interface-rows` (`%clojure-interface-store`, a `reify` row per evaluation)
- `%clojure-ns-table`

Found while reading the code, not reproduced. A lost table or a put racing a resize loses an entry
(metadata gone, two vars for one name, two representatives of one key).

## Plan

1. Reproduce each reachable one with a burst like `ClojureIoConcurrentRequestsTest`.
2. Per table: what the lowering knows in full moves to the program's start (as the literal
   resources did); what a call must write takes one guard, created eagerly, around every read and
   write. wasm stays single-threaded (`with-mutex` is a no-op there).
