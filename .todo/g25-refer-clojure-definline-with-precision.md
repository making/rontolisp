# g25. `refer-clojure`/`definline`/`with-precision` wrappers (slice of `.todo/g15`)

Difficulty: Small-Medium

`refer-clojure`, `definline` and `with-precision` are the three of the
`.todo/g15` presence gap that wire at lower time with small, independent
lowerings:

- `refer-clojure` (`:only`/`:exclude`/`:rename`) narrows the referred core in
  the current namespace -- the same wiring `refer` and the ns
  `(:refer-clojure ...)` clause use, as a standalone form.
- `definline` is `defn` plus a recorded inline expander. If call-site inlining
  is cheap (reuse the `define-compiler-macro` path), wire it; otherwise lower
  as `defn` and record the inline as a documented no-op, since nothing here
  inlines today.
- `with-precision` establishes a `java.math.MathContext` for arbitrary
  precision arithmetic in its body. Rationals are exact here rather than
  `BigDecimal`, so pin the body scoping (`*math-context*`-shaped dynamic
  extent) and record how the exact-rational answers map onto the oracle's
  `BigDecimal` ones per call.

## Definition of done

- All three lower and run on all four backends, pinned by
  `clojure-spec.yaml` cases; every deliberate no-op or exact-vs-`BigDecimal`
  edge recorded in `doc/en/clojure/deviations.md` (+ `doc/ja/`) in the same
  commit.
- Values (`(def x refer-clojure)`) come via `.todo/g16`; if `g16` lands first,
  pin the value here, otherwise leave it to `g16` and note it.

## Non-goals

- A general inline-expansion framework beyond what `definline` needs.
- `BigDecimal` as a distinct value (`bigdec` stays a plain rational).
- The other `.todo/g15` names -- each slice has its own item.
