# `ancestors` of kind keywords + `clojure.lang.IFn` values for `core.memoize`

Difficulty: Hard

`org.clojure/core.memoize` 1.2.281 fails to LOAD on every backend in the g20
probe: its `check-args` assertion macro expands (under default `*assert*`) to
`(some #{clojure.lang.IFn clojure.lang.AFn java.lang.Runnable
java.util.concurrent.Callable} (ancestors (class f)))`, which needs two things
the class-keyword architecture deliberately lacks:

1. `clojure.lang.IFn`/`AFn` in value position: no class object exists here, so
   the set literal refuses at compile time (`unknown name:
   clojure.lang.IFn`, memoize.clj:385). The "Classes compiled against
   `clojure.lang`" decision (`.kb/clojure-frontend.md`) builds no bridge, and
   mapping them to the `:function` keyword would newly claim
   `(= clojure.lang.IFn (class (fn [])))`, which the oracle denies.
2. `(ancestors :function)` answers `#{:java.lang.Object}` by design (a kind
   stands for several host classes, so its row names no bases and its supers
   are `Object` alone -- `ClojureClassBases` `KINDS`,
   `clojure.lisp` "Class chains"). The check needs the kind's interface
   ancestry (`IFn` among `ancestors` of a function), and giving kinds real
   supers rows would also move `isa?`/`parents` and multimethod
   class-hierarchy dispatch off those rows -- the blast radius the premise
   exists to prevent. A change that big needs its own measurement, not a probe
   fix.

## Oracle vs here (g20 probe, 2026-10-21)

Oracle (`clj` 1.12.6.1673) loads the namespace and memoizes; every backend
here refuses the load:

```
error: .../core.memoize-1.2.281.jar!/clojure/core/memoize.clj:385:4: unknown name: clojure.lang.IFn
```

(`(ancestors (class (fn [])))` is `#{... clojure.lang.IFn ...}` there,
`#{:java.lang.Object}` here; `(class (fn []))` is the fn class there,
`:function` here.)

## What to build

- Either portable `ancestors`/`parents`/`isa?` of kind keywords over the
  `ClojureValueClasses` supers (measured against the `KINDS` premise and the
  dispatch rows that read them), plus a value spelling for `clojure.lang.IFn`
  et al. that keeps `(= clojure.lang.IFn (class (fn [])))` false, or a
  narrower story for assertion macros of this shape. Whichever it is, the
  g20 probe (below) must print the oracle's lines on all four backends, with
  `*assert*` at its default (no `(set! *assert* false)` dodge: expansion order
  differs between the interpreter and compiled backends).
- Pin with a corpus case (`src/test/resources/clojure-spec.yaml`): loading
  `clojure.core.memoize` and a `(memo/memo ...)` call-count check on all four
  backends.

## Non-goals

- `core.cache` itself: green in g20 (1.2.999 + data.priority-map 1.2.1, 12
  calls, all four backends), with only `soft-cache-factory` noted (host
  `SoftReference` construction is a call-time error on wasm). The memoize
  row is the one that stays red.
- Touching the default (checked, promoting) arithmetic or the `*assert*`
  machinery for this.

## Context

Cut from g20 (`.todo/g20-library-probe-batch.md`): greens join the probe
table, reds become their own item. Transitive deps: core.cache 1.2.254 ->
1.2.999 by newest-wins (the probe pins both).
