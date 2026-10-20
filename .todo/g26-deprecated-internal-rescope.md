# g26. Re-scope the deprecated/internal six (slice of `.todo/g15`)

Difficulty: Small (decision record, not implementation)

`add-classpath`, `primitives-classnames`, `EMPTY-NODE`, `Inst`,
`PrintWriter-on` and `StackTraceElement->vec` are the six of the `.todo/g15`
presence gap that are deprecated or JVM internals in the oracle. Faithfully
reproducing them -- `add-classpath` mutating a class loader that does not
exist on wasm, `EMPTY-NODE` exposing a persistent-vector internals node --
makes no sense on any backend here, so the `g15` DoD ("each of the 17 lowers
and runs") is the wrong requirement for them. This slice settles, per name,
which of three dispositions applies:

1. Lower to the honest equivalent (`EMPTY-NODE` to the empty vector,
   `Inst` to the instant protocol marker, `StackTraceElement->vec` to the
   vector the stacktrace runtime already builds).
2. Keep the refusal but re-grade it as documented (e.g. `add-classpath`:
   no class loader exists here; point at `deps.edn`).
3. Drop from the presence probe with the reason recorded.

## Definition of done

- Each of the six has its disposition implemented (for 1) or recorded (for
  2--3) in `doc/en/clojure/deviations.md` (+ `doc/ja/`) and in the `g15`
  presence probe's expectations, pinned by `clojure-spec.yaml` cases where
  behavior is involved.
- The `g15` DoD is amended to point at this disposition instead of
  "17/17 lower and run".

## Non-goals

- Implementing class-loader mutation or collection internals.
- The other `.todo/g15` names -- each slice has its own item.
