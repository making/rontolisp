# f53. Clojure: a fn receiver refuses `Comparator`'s default methods (`.reversed`, `.thenComparing`)

Difficulty: Medium

A fn receiver answers only its rows (`ClojureValueMethodLowering.functionRows`: `invoke`,
`applyTo`, `call`, `run`, `compare`); any other method is refused by name, where the
oracle's fn, an `AFunction`, answers every method of `Comparator`, `Runnable` and
`Callable`. Measured 2026-10-11 against clj 1.12.6, interpreter (`c` a fn `(- a b)`):

| program | oracle | here |
|---|---|---|
| `(.compare (.reversed c) 1 2)` | `1` | `IllegalArgumentException`: `Method reversed taking 0 args is not supported for class clojure.lang.AFunction` |
| `(.compare (.thenComparing c c) 1 2)` | `-1` | the same for `thenComparing` taking 1 args |
| `(let [l (java.util.ArrayList. [3 1 2])] (.sort l (.reversed c)) (vec l))` | `[3 2 1]` | the `reversed` refusal |

Where the host is, a fn's Java object is the object the fn is (`%clojure-host-member`, a
`java:handle` of the fn: `Runnable`, `Callable`, `Comparator`), so its default methods are
there to call, as a vector's `java.util.List` methods are reached through its view
(`ClojureValueMethodLowering`'s `HostKind` rows over `%clojure-host-member`).

## What decides the design

- Which methods reach Java: the oracle's `AFunction` also has `IFn`/`AFn` methods
  (`invoke`, `applyTo`, `getMeta`, `withMeta`, ...) the rows answer or refuse; only the
  three interfaces' methods are the function object's.
- Wasm has no host: the refusal stays there, as the other `HostKind` rows keep it.

## Plan

1. `ClojureInteropTest` rows for the table, interpreter and JVM.
2. A fn `HostKind` (or the equivalent) reaching `Comparator`'s, `Runnable`'s and
   `Callable`'s methods on the fn's Java object where the host is.
3. `doc/*/clojure/deviations.md` if a part stays refused.
