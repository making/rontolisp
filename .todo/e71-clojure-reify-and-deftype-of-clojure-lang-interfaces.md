# e71. Clojure: `reify`/`deftype` of the `clojure.lang` interfaces the core verbs consult

Difficulty: High

`reify`, `deftype` and `defrecord` implement protocols only; a `clojure.lang` or `java.util`
interface in a body is `No such protocol`. Libraries reach the core verbs that way:

- `(reify clojure.lang.IReduceInit (reduce [_ f init] ...))` is the reducible of next.jdbc's
  `plan` and of `clojure.core/iteration` (with `clojure.lang.Seqable`); `reduce`, `into` and
  `transduce` ask `IReduceInit` ahead of `CollReduce` in the oracle.
- instaparse's `AutoFlattenSeq` and data.priority-map are deftypes of `Counted`, `Seqable`,
  `ILookup`, `IFn`, `java.util.List`/`Map` beside `IKVReduce`, so `count`, `seq`, `get` and a
  call of such a value never reach the type.

`CollReduce`/`IKVReduce` rows already reach `reduce`/`reduce-kv` behind the reducible arms
(`ClojureArms.Family.REDUCIBLE`, `.kb/clojure-frontend.md` "clojure.jar namespaces").

## Plan

1. Measure on the oracle which interfaces the probe libraries (`e43`) implement and what each
   core verb asks (`IReduceInit` vs `IReduce` for the init-less `reduce`, `Counted`,
   `Seqable`, `ILookup`, `IFn`).
2. Model them as built-in protocols the verbs consult behind arms made only by a body naming
   one, so a program implementing none compiles byte-identically; `IReduceInit`/`IReduce`
   first.
3. clojure-spec lines on all four backends; `doc/*/clojure/reference/reify.md`,
   `deftype.md`.
