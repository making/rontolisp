# e60. Clojure: `Throwable->map`, and protocols extended to `Throwable`/`IRef`

Difficulty: Medium

`Throwable->map` is an unknown name here, and `extend-protocol` refuses a `Throwable`,
`Exception`, `IRef` or other interface target ("needs a core type", measured 2026-10-08).
So `clojure.datafy` answers an exception as itself (the oracle: `Throwable->map`'s map)
and spells its `IRef` row as a test inside its `Object` row; a library extending a protocol
to `Throwable` or `IRef` fails to lower.

## Plan

1. `Throwable->map` on every backend: `:via` (`:type` the class symbol, `:message`,
   `:data`), `:cause`, `:data`; `:trace`/`:at` have no frames here (a stated deviation, like
   `clojure.stacktrace`'s `[empty stack trace]`).
2. `extend-protocol`/`extend-type`/`extend` to `Throwable`, `Exception` and the other
   throwable classes (the class rows a catch already walks, `ClojureClassBases`), and to
   `IRef`/`IDeref` (atoms, volatiles, refs, agents, delays).
3. `clojure.datafy`'s `Throwable` and `IRef` rows as the oracle's; clojure-spec lines.
