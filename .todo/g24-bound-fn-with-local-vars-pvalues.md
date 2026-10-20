# g24. `bound-fn`/`with-local-vars`/`pvalues` threading cluster (slice of `.todo/g15`)

Difficulty: Medium

`bound-fn`, `with-local-vars` and `pvalues` are the three of the `.todo/g15`
presence gap that share one missing mechanism: thread bindings are not reified
at run time (`bound-fn*` and `get-thread-bindings` are refused on those
grounds today). `bound-fn` captures the current bindings for a fn running on
another thread; `with-local-vars` establishes var cells with thread-local
bindings. There is no thread pool on any backend (`future-call`, `pcalls`,
`seque` are refused for the same reason), so this slice must also settle the
semantics that decision forces:

- `pvalues` evaluates sequentially in order (the `pmap`-is-`map` precedent in
  `doc/en/clojure/deviations.md`), documented as a deviation.
- `bound-fn` captures what the single-threaded binding stack holds; a fn run
  on another thread (a host `Future`'s thread, a Ring handler thread) sees the
  captured roots. Pin whichever is implemented; document the rest as refused.

## Definition of done

- All three lower and run on all four backends to the semantics above, pinned
  by `clojure-spec.yaml` cases, with the sequential-`pvalues` and
  `bound-fn`-capture edges recorded in `doc/en/clojure/deviations.md`
  (+ `doc/ja/`) in the same commit.
- Values (`(def x bound-fn)`) come via `.todo/g16`; if `g16` lands first, pin
  the value here, otherwise leave it to `g16` and note it.

## Non-goals

- A thread pool, or `future`/`promise`/`pcalls`/`seque` themselves -- no
  threads exist on the wasm backends, and that is a deliberate boundary, not
  this slice.
- Full thread-local binding reification (`get-thread-bindings`,
  `push-thread-bindings`): only what these three forms need.
- The other `.todo/g15` names -- each slice has its own item.
