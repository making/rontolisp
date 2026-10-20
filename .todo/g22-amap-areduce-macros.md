# g22. `amap`/`areduce` array macros (slice of `.todo/g15`)

Difficulty: Small

`amap`/`areduce` are the only two of the `.todo/g15` presence gap that desugar
the way the oracle's macros do, over shapes `ClojureLoopLowering` already uses
for `doseq` (indexed walk over an array, accumulation into the return array /
reduction). No new runtime is needed: both lower to existing loop + `aset`
forms. Typed arrays are plain vectors here (see `doc/en/clojure/deviations.md`),
so the `int`/`long`/etc. return-type hint only selects the element cast, which
the existing unchecked/narrowing verbs already answer.

## Definition of done

- `(amap a idx ret expr)` / `(areduce a idx ret init expr)` lower and run on
  all four backends, oracle-identical on the value (including the returned
  array's contents), pinned by `clojure-spec.yaml` cases.
- Wrong-shape uses refuse in the oracle's wording at lower time, like the
  other loop desugars.
- Values (`(def x amap)`) come via `.todo/g16`; if `g16` lands first, pin the
  value here, otherwise leave it to `g16` and note it.

## Non-goals

- Primitive-array performance: elements stay boxed values; parity, not speed.
- The other `.todo/g15` names -- each slice has its own item.
