# Clojure: three small lowering refusals -- a named `fn` with metadata, `:include-macros`, a `definterface` by its qualified name

Difficulty: Low

Three unrelated one-spot refusals the j07 probe round met; each is a few lines in one lowering
class. One item because each is too small to carry its own.

## 1. Metadata on a `fn`'s name

schema 1.4.2's `s/fn`/`s/defn` (`schema/core.cljc:1285`) expands to
`(clojure.core/fn ^{:schema schema.core/Any} fn2050 ([^{:schema ...} G__2056] ...))`, so every
`s/defn` and `s/defprotocol` stops.

```clojure
(prn ((fn ^:x nm [g] g) 2) ((fn ^{:schema 1} nm ([g] g)) 3))
```

- oracle (2026-11-08, clj 1.12.6.1673): `2 3`
- interpreter and JVM class: `error: f.clj:1:7: a parameter vector takes its bindings in a
  vector: |%with-meta|` -- the name symbol arrives as a `(%with-meta nm {...})` form
  (KB "Vars and metadata": a symbol carrying metadata is a wrapper every symbol verb reads as its
  bare symbol) and `fn`'s name test does not unwrap it, so the name is taken for the parameter
  vector. Metadata on a parameter (`[^:x g]`) already works; check `letfn`/`defn`/`defmacro`/
  `reify` method names for the same miss.

## 2. `:include-macros true` in a libspec

schema `core.cljc:85`: `[schema.spec.core :as spec :include-macros true]` (a ClojureScript
option, outside a reader conditional).

```clojure
(require '[clojure.set :as s :include-macros true])
(prn (s/union #{1} #{2}))
```

- oracle: `#{1 2}` (Clojure ignores the option)
- here: `error: f.clj:1:1: require option :include-macros is not supported yet`
  (`ClojureNamespaceLowering`). Accept and ignore it, in `require` calls and `ns` `:require`
  clauses alike; check `:refer-macros` too (also cljs-only; what does the oracle do with it?
  measure, do not guess).

## 3. A `definterface` named by its fully qualified class name

core.logic 1.1.1, `clojure/core/logic/protocols.clj:191-194`:
`(definterface IEnforceableConstraint)` then
`(instance? clojure.core.logic.protocols.IEnforceableConstraint x)` in the same namespace -- the
load's first stop. Also `:218`/`:221` (`ITreeConstraint`).

```clojure
(ns my.p)
(definterface I)
(deftype U [] I)
(prn (instance? my.p.I (U.)) (instance? my.p.I 1) (instance? I (U.)))
```

- oracle: `true false true`
- interpreter and JVM class: `error: f.clj:4:6: unknown name: my.p.I`

KB "A `definterface` defines an interface with no host class: the name is bound to the binary
name": the short name resolves, the binary name (`my.p.I`, namespace `-` munged to `_`) does not.
It should resolve wherever the short name does (`instance?`, a type body, `extend` refusal, an
`:import` from another namespace -- core.match imports `[clojure.core.match.protocols
IExistentialPattern IPseudoPattern]`, which works today: check the qualified spelling there too).

## Done when

All three repros print the oracle's lines on all four backends (ci-spec cases), schema loads past
`s/defn`, and core.logic past `protocols.clj:194`, without the j07 hunks for these sites.
