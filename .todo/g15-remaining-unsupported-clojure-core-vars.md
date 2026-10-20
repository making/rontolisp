# g15. Remaining unsupported `clojure.core` vars

Difficulty: Medium

A presence probe over every public of `clojure.core` (the 679 names of
`ClojureCoreNames`, taken from `clj` 1.12.6) run against the develop jar on
2026-10-14: each name referenced once in head position `(name)` and once as a
value, `unknown name` from the lowering marking the gap. 624 of 679 work in
head position. The 17 that work in neither:

- array macros: `amap`, `areduce`
- transaction/threading macros: `sync`, `io!`, `pvalues`
- vars/meta macros: `bound-fn`, `refer-clojure`, `with-precision`,
  `definline`, `with-local-vars`, `with-loading-context`
- deprecated/internal: `add-classpath`, `primitives-classnames`,
  `EMPTY-NODE`, `Inst`, `PrintWriter-on`, `StackTraceElement->vec`

The same probe finds every public of `clojure.string`, `clojure.set`,
`clojure.walk`, `clojure.data`, `clojure.zip`, `clojure.repl`, `clojure.pprint`,
`clojure.template`, `clojure.math`, `clojure.edn`, `clojure.instant`,
`clojure.core.reducers`, `clojure.core.protocols`, `clojure.datafy`,
`clojure.stacktrace`, `clojure.xml`, `clojure.main`, `clojure.test`,
`clojure.java.shell` and `clojure.java.io` resolvable, and each namespace
`require`-able, so the core list above is the whole remaining presence gap.

`sync`/`io!` matter beyond their rarity: real `clojure.java.shell`-style and
STM-flavored programs spell them, and `pvalues` is the last parallel-evaluation
form `future`/`pmap` users reach for.

## Definition of done

This item is worked slice by slice; each slice has its own todo and DoD:

- `.todo/g22`: `amap`/`areduce`
- `.todo/g23`: `sync`/`io!`/`with-loading-context`
- `.todo/g24`: `bound-fn`/`with-local-vars`/`pvalues`
- `.todo/g25`: `refer-clojure`/`definline`/`with-precision`
- `.todo/g26`: the deprecated/internal six (`add-classpath`,
  `primitives-classnames`, `EMPTY-NODE`, `Inst`, `PrintWriter-on`,
  `StackTraceElement->vec`): `EMPTY-NODE` and `StackTraceElement->vec` lower
  and run; `add-classpath` and `Inst` keep their documented `unknown name`
  refusal; `PrintWriter-on` and `primitives-classnames` are exempt from the
  probe with reasons recorded. This item's original "17/17 lower and run"
  requirement is amended by that disposition -- see `g26`.

This item is done when all five slices are done and a re-run of the presence
probe (script kept beside this todo's artefacts) reports every name at its
natural position: the lowered names resolve and run (functions in both
positions, non-function values as values), `add-classpath` and `Inst` keep
their documented `unknown name` refusal in both positions, and
`PrintWriter-on`/`primitives-classnames` are exempt with reasons recorded --
the counted gap is exactly the 2 documented refusals.

## Non-goals

- Making core macros first-class VALUES (`(def x when)`) -- that is
  `.todo/g16`, a var-model change, not per-name lowering.
- Behavioral parity of the supported 624 beyond what existing tests pin.
