# Clojure: tools.reader 1.3.6 loads verbatim

Difficulty: Medium

tools.reader is a common transitive dependency (tools.analyzer.jvm's, and the `go` chain's:
`.kb/clojure-frontend.md`, "core.async and tools.analyzer.jvm"). Measured 2026-10-29, with its
`Closeable` deftypes already taken (host interfaces, g87), three blockers remain, each patched
in a `:local/root` copy to see the next:

1. `impl/inspect.clj`: `(defmethod inspect* clojure.lang.PersistentVector$ChunkedSeq ...)` and
   four more nested or unlisted `clojure.lang` classes as dispatch values are `unknown name`.
   A `clojure.lang` class of the oracle's jar should read as its class keyword (one no value
   here answers, so the method never matches), as listed interfaces and kinds already do.
2. `default_data_readers.clj`: `print-method`/`print-dup` of `java.util.Date`, `Calendar`,
   `java.sql.Timestamp` and `UUID` are refused (the printer consults a print-method of a
   record, deftype or `:type` keyword only). These are the classes `#inst`/`#uuid` values
   answer; decide whether such a method is consulted for them or accepted and shadowed by the
   native printing, measured against clj.
3. Same file: `(format "%1$tFT%1$tT.%1$tL%1$tz" c)` refuses `%t` while lowering, so the
   namespace cannot load even though nothing calls it. Refuse at call time (the g30 rule), or
   implement the date directives.

Done when `(require '[clojure.tools.reader :as r])` loads unpatched and `read-string`,
`syntax-quote` and an indexing reader print the oracle's lines on the interpreter and the
JVM; record the probe row in "Library probes".
