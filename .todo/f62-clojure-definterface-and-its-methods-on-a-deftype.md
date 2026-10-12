# f62. Clojure: `definterface` and calls of its methods on a deftype (data.json)

Difficulty: High

Found by the library probes (`.kb/clojure-frontend.md`, "Library probes", 2026-10-12):
`org.clojure/data.json` 2.5.2 stops at its first form past `ns`,
`json.clj:22:1: protocols are not supported yet: definterface`.

```clojure
(definterface InternalPBR
  (^int readChar [])
  (^long readChars [^chars buffer ^long start ^long bufflen])
  (^void unreadChar [^int c])
  (^void unreadChars [^chars buffer ^int off ^int bufflen])
  (^java.io.Reader toReader []))
(deftype ReaderPBR [^PushbackReader rdr]
  InternalPBR
  (readChar [_] (.read rdr))
  ...)
;; callers: (.readChar ^InternalPBR pbr), (.unreadChar pbr c)
```

## What decides the design

- A `definterface` has no host class here; its methods are only ever called as `.method` on a
  deftype/reify implementing it. The "Host interfaces" machinery (`ClojureInterfaces`, rows
  per implemented interface, the value-method arm) already dispatches `.m` on a type
  implementing a known interface: an interface the program defines could join it as rows
  keyed by its own name, with `instance?`/`class` answering its name.
- data.json's reader then runs over `java.io.PushbackReader`/`StringReader`/`StringWriter`
  (host objects on the interpreter and the JVM; the wasm side needs the portable reader the
  front end already has for `clojure.java.io`, "clojure.java.io") and `char-array`/`chars`
  (`.todo/f57`'s array constructors).
- Re-probe data.json's `read-str`/`write-str`/`pprint` on all four backends after it.

## Plan

1. clojure-spec rows for a `definterface` + `deftype`/`reify` + `.method` calls, oracle-identical.
2. The lowering; the probe's own `deps.edn` re-run (`write-str`, `read-str` with `:key-fn`,
   big numbers, `pprint`).
3. User docs en + ja (`deviations.md` drops the `definterface` refusal; `semantics.md` table).
