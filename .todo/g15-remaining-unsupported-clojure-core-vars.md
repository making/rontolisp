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

- Each of the 17 lowers and runs on all four backends, pinned by tests
  alongside the existing Clojure lowering tests.
- `amap`/`areduce`/`bound-fn`/`definline` desugar the way the oracle's macros
  do (the same shapes `ClojureLoopLowering` uses for `doseq`).
- `sync`/`io!` lower to their bodies plus whatever transaction discipline the
  refs section already has; where refs are unsupported the body still runs.
- A re-run of the presence probe (script kept beside this todo's artefacts)
  reports 679/679 in head position and the same as values for the fns.

## Non-goals

- Making core macros first-class VALUES (`(def x when)`) -- that is
  `.todo/g16`, a var-model change, not per-name lowering.
- Behavioral parity of the supported 624 beyond what existing tests pin.
