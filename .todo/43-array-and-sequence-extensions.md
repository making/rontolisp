# Array and sequence extensions (`arrayp`, `array-rank`, `array-dimensions`, `array-total-size`, `array-element-type`, `array-row-major-index`, `row-major-aref`, `adjustable-array-p`, `adjust-array`, `make-array` (done, basic), `vector-push`, `vector-push-extend`, `svref`, `sset`, `simple-array`, `simple-vector`, `simple-bit-vector`, `simple-string`, `simple-string-bounds`, `vectorp`, `stringp` (done), `bit-vector-p`, `stringp` (done), `array-in-bounds-p`, `long-string-p`, `short-string-p`, `long-float` (see #37), `short-float` (see #37), `bit`, `logbit`, `booleanp`, `bool`, `copy-seq`, `fill`, `stable-sort` (see #33), `merge` (see #33), `coerce` (see #35), `count-if-not` (see #33), `position-if-not` (see #33), `substitute-if` (see #33), `substitute-if-not` (see #33), `mismatch` (see #33), `search` (see #33), `tree-equal` (see #33), `set-exclusive-or` (see #33), `count-if` (missing!), `remove-if-not` (done), `delete-if-not` (done))

**Status:** partially implemented (2026-07-03). Done: `vector`, `svref` (incl.
the `setf` place), `array-dimensions` (the one new backend primitive),
`array-rank`, `array-dimension`, `array-total-size` (macro expansions over
`array-dimensions`), and `coerce` for the literal `'list`/`'vector`/`'string`
result types — see `.kb/linalg.md` (which also covers the new numpy-style
`linalg` package built on top). Still missing: `arrayp`/`vectorp` (the WASM
value representation cannot cheaply distinguish an array cell from a hash-table
cell — needs a tag), `row-major-aref`, `array-in-bounds-p`, `copy-seq`,
`fill`, adjustable arrays / fill pointers, bit vectors, and the rest below.

## What's missing

RontoLisp has `make-array` (rank 1 and 2, `:initial-element`), `aref`, and `%aset`. The array introspection and manipulation toolkit is minimal.

### Missing array functions

| Function | Purpose | Difficulty |
|----------|---------|------------|
| `arrayp` | Array predicate | Easy |
| `array-rank` | Number of dimensions | Easy |
| `array-dimensions` | Dimension sizes | Easy |
| `array-total-size` | Total element count | Easy |
| `array-element-type` | Declared element type | Easy |
| `array-row-major-index` | Row-major index | Medium |
| `row-major-aref` | Linear access | Easy |
| `array-in-bounds-p` | Bounds check without signaling | Easy |
| `adjustable-array-p` | Adjustable flag | Easy |
| `adjust-array` | Resize array | Hard |
| `fill-pointer` | Fill pointer | Medium |
| `vector-push` | Push to fill pointer | Easy |
| `vector-push-extend` | Push with auto-extend | Medium |
| `svref` / `sset` | Simple vector access | Easy |
| `vectorp` | Vector (rank-1 array) predicate | Easy |
| `copy-seq` | Copy sequence | Easy |
| `fill` | Fill sequence with value | Easy |
| `count-if` | Count elements satisfying predicate | Easy |

### Missing type predicates

| Function | Purpose |
|----------|---------|
| `simple-array-p` | Simple array predicate |
| `simple-vector-p` | Simple vector predicate |
| `simple-bit-vector-p` | Simple bit vector predicate |
| `simple-string-p` | Simple string predicate |
| `bit-vector-p` | Bit vector predicate |
| `booleanp` | Boolean (0 or 1) predicate |
| `logbit` | Bit access |

### Implementation approach

**Array introspection** (highest ROI):
1. `arrayp`, `array-rank`, `array-dimensions`, `array-total-size` — metadata accessors.
2. `array-in-bounds-p` — safe bounds check.
3. `vectorp`, `svref`, `sset` — vector-specific access.
4. `row-major-aref` — linear access to any array.

**Sequence manipulation**:
5. `copy-seq` — shallow copy of any sequence.
6. `fill` — fill with value (and `:start`/`:end`).
7. `count-if` — count matching elements (note: `count-if` is missing! Only `count` and `count-if` were planned but `count-if` may not be implemented — verify).

**Advanced** (deferred):
8. `adjust-array` — resizable arrays.
9. `vector-push` / `vector-push-extend` — fill pointer semantics.
10. Bit vectors — `bit` type, `bit-vector-p`, `logbit`.

### Related

- `[[33-sequence-and-set-extensions]]` (sequence ops)
- `[[35-type-system]]` (`coerce` to/from arrays)
