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

More consumers, found by the 2026-11-08 library probe round (`.todo/j07`; each patched past by
hand there, see its `patches.diff`):

- core.match 1.1.1, `clojure/core/match.clj:175-186` (`test-inline ::vector`): `(class? the-tag)`
  of `clojure.lang.IPersistentVector` (a vector pattern's tag) is false here, so every vector
  pattern throws `java.lang.Error: Unsupported tag type:clojure.lang.IPersistentVector` at macro
  time; then `(.isArray ^Class c)`.
- core.logic 1.1.1: no longer a consumer since j07's re-probe (2026-11-09): `.isInstance` of a
  class keyword or designator is `instance?`, so its deftypes' `(.. this getClass (isInstance
  o))` runs and the verbatim probe is `=` on all four backends but the pinned `()`-is-`nil` line.
- schema 1.4.2: `schema/utils.cljc:155` `(assert (class? klass) ...)` in `declare-class-schema!`
  (`Cannot declare class schema for non-class :keyword`): `s/defn` hands it `(class f)`, the
  `:function` keyword. Since j13 (2026-11-09) a record's class is a class value (its
  `(:C%CLASS "ns.Name")` designator, `class?` true, one Java object per class as a host map
  key), so `s/defrecord`, `s/record` and `record*`'s `(class? klass)` run verbatim. Re-probed
  verbatim 2026-11-09 (j07): this is the only stop on the interpreter and the JVM class (the
  first seven lines print, the set-order one pinned); since j38 (2026-11-09) both wasm backends
  load schema and stop here too, the class value being a `:function` keyword there as well.
- core.match 1.1.1 re-probed verbatim 2026-11-09 (j07): the `class?` stop above is the only one
  on all four backends (nothing prints).

A record's or deftype's class value is its dispatch tag since j13: the designator one function
makes (`ClojureProtocolLowering.typeTagForm`), every row of the type keyed by it -- what a
generated class would replace on the host targets. A protocol interface's class value is its
designator too since j14 (2026-11-09: `%clojure-protocol-interface-class`, registered with its
protocol, so `instance?`, `isa?` and `print-method` read the types whose body implements it)
-- what a generated interface would replace.

Related items from the same round: `.todo/j13` (a record name as a value, done 2026-11-09), `.todo/j15`
(`clojure.core$NAME`, done 2026-11-09: every one is the `:function` class keyword, which a class per function would replace), `.todo/j20` (a class value inside a macro's expansion).
