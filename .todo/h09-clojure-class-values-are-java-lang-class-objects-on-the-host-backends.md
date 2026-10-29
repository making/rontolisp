# Clojure: class values are java.lang.Class objects on the interpreter and the JVM

Difficulty: High

On the interpreter and the JVM a Clojure class value is a host `java.lang.Class` only for a
class no Clojure value has (`String`, `Object`, `Class` in value position). `(class x)` of every
value the front end represents natively answers a kind keyword (`:string`, `:keyword`,
`:vector`, `:map`, `:list`, `:nil`, `:java.lang.Long`, `:clojure.lang.BigInt` ...), and `Long`,
`Double` and `clojure.lang.X` in value position read as keywords too (`.kb/clojure-frontend.md`,
"Boxed-integer limits, class designators, number classes ...").

Measured against clj 1.12.6 (2026-10-29): `(= (class "a") String)` false, `(class? (class 1))`
false, `(class nil)` `:nil`, `(prn (class "a"))` `:string`, `(.isAssignableFrom Number Long)` a
cast failure, `supers`/`bases` refused, `ns-map`'s imports symbols. tools.analyzer.jvm refuses
every form holding a string, keyword, collection or fn (`Class not found: :string`: its
`maybe-class` multimethod dispatches on `class`, and `(class :string)` is `:keyword`), an import,
a `Class/member`, `new`, `instance?` and a `catch` -- which blocks core.async's `go`
(`.todo/g87`; the full list is the kb's "core.async and tools.analyzer.jvm").

Direction: on the host targets every class value is a `java.lang.Class` -- a JDK class itself,
and each `clojure.lang` class the oracle's values have (`Keyword`, `Symbol`, `PersistentVector`,
`PersistentArrayMap`, `IPersistentVector`, `AFunction`, `Var`, `Namespace`, a record's or
deftype's class ...) a class the front end defines with the oracle's name and supertypes
(generated through `am.ik.jvm` on the interpreter; embedded in the JVM backend's output, the
`runtime` package rule applying) -- so `class`, `class?`, `instance?`, `isa?`, `supers`/`bases`,
`.getName`/`.isAssignableFrom`/`.isInterface`, `Class/forName`, `ns-map`'s imports and
`clojure.reflect` answer the oracle's. The kind keywords stay the internal dispatch tags
(protocol rows, multimethod dispatch on a class value mapped to its tag), and wasm keeps its
designators. Open: printing a class value (`java.lang.String`, the oracle's), maps keyed by
classes (malli's `class-schemas`), `case` over classes, the pinned `(class x)` outputs of every
backend's corpus, what a native image without the generated classes answers.

Done when `(= (class "a") String)`, `(class? (class 1))` and `(.isAssignableFrom Number Long)`
answer the oracle's on the interpreter and the JVM, and tools.analyzer.jvm analyzes every form
of the kb's measured list.
