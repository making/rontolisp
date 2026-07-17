# Array and sequence extensions (`arrayp`, `array-in-bounds-p`, `fill`, bit vectors, the `simple-*-p` predicates)

**Status:** partially implemented (2026-07-06). Done: `vector`, `svref` (incl.
the `setf` place), `array-dimensions` (backend primitive), `array-rank`,
`array-dimension`, `array-total-size` (macro expansions over
`array-dimensions`), `coerce` for the literal `'list`/`'vector`/`'string`
result types, and — with rank-n arrays (2026-07-03; the record is
`.kb/linalg.md`, which also covers the numpy-style `linalg` package built on
top) — `row-major-aref` (backend primitive, incl. the `setf` place via
`%row-major-aset`) and `array-row-major-index` (macro expansion, Horner fold
over `array-dimensions`). `make-array`/`aref` support any rank >= 1.

The extended-array surface landed 2026-07-06 on all four backends
(`.kb/adjustable-arrays.md`): `vectorp`, `copy-seq`, `count-if`,
`fill-pointer` (+ its `setf` place via `%set-fill-pointer`),
`array-has-fill-pointer-p`, `adjustable-array-p`, `array-element-type`,
`adjust-array`, `array-displacement`, `vector-push`, `vector-pop`,
`vector-push-extend`, and `make-array`'s `:fill-pointer` / `:adjustable` /
`:displaced-to` (+ `:displaced-index-offset`) options. `--no-gc` rejects that
whole surface with its usual clear compile error.

Still missing: `arrayp` as a public function (only the internal `%arrayp`
exists, `LispNames.java:1638`), `array-in-bounds-p`, `fill`, bit vectors, and
the `simple-*-p` predicates.

## What's missing

RontoLisp has `make-array` (any rank, `:initial-element`, `:fill-pointer`,
`:adjustable`, `:displaced-to`), `aref`, `%aset`, `row-major-aref`, and the
fill-pointer surface. What remains of the introspection toolkit:

### Array functions

| Function | Purpose | Difficulty |
|----------|---------|------------|
| `arrayp` | Array predicate — internal `%arrayp` only | Easy |
| `array-in-bounds-p` | Bounds check without signaling | Easy |
| `fill` | Fill sequence with value | Easy |
| `array-rank` | Number of dimensions | done |
| `array-dimensions` | Dimension sizes | done |
| `array-total-size` | Total element count | done |
| `array-element-type` | Declared element type | done (2026-07-06) |
| `array-row-major-index` | Row-major index | done (2026-07-03) |
| `row-major-aref` | Linear access | done (2026-07-03) |
| `adjustable-array-p` | Adjustable flag | done (2026-07-06) |
| `adjust-array` | Resize array | done (2026-07-06) |
| `array-displacement` | Displaced target + offset | done (2026-07-06) |
| `fill-pointer` | Fill pointer (+ `setf`) | done (2026-07-06) |
| `array-has-fill-pointer-p` | Fill-pointer flag | done (2026-07-06) |
| `vector-push` | Push to fill pointer | done (2026-07-06) |
| `vector-pop` | Pop from fill pointer | done (2026-07-06) |
| `vector-push-extend` | Push with auto-extend | done (2026-07-06) |
| `svref` | Simple vector access | done |
| `vectorp` | Vector (rank-1 array) predicate | done (2026-07-06) |
| `copy-seq` | Copy sequence | done (2026-07-06) |
| `count-if` | Count elements satisfying predicate | done |

### Missing type predicates

| Function | Purpose |
|----------|---------|
| `simple-vector-p` | Simple vector predicate |
| `simple-bit-vector-p` | Simple bit vector predicate |
| `simple-string-p` | Simple string predicate |
| `bit-vector-p` | Bit vector predicate |
| `bit` / `sbit` | Bit-vector element access |
| `logbitp` | Test one bit of an integer |

### Implementation approach

**Array introspection** (highest ROI):
1. `arrayp` — the public predicate over the existing internal `%arrayp`.
2. `array-in-bounds-p` — safe bounds check.

**Sequence manipulation**:
3. `fill` — fill with value (and `:start`/`:end`).

**Advanced** (deferred):
4. Bit vectors — `bit` type, `bit-vector-p`, `bit`/`sbit`, `logbitp`.

### Related

- `[[033-sequence-and-set-extensions]]` (sequence ops)
- `[[035-type-system]]` (`coerce` to/from arrays)
