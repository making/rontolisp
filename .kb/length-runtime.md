# The generic `length` dispatch is a shared callee, not per-site code

**Invariant: no `length` site emits the sequence-type ladder inline on either compile
path. A site is one call -- `_seq_len` on WASM, `_length` on the JVM -- unless the
argument's representation is already pinned: a packed vector is a direct `array.len`, and
a general one, at the wasm speed levels, the read of its cell's fields with the call
behind it.**
Same lesson as `.kb/wasm-shared-coercion.md`, `.kb/sequence-op-runtimes.md`.

- **JVM**: `JvmLengthCompiler` emits one `invokestatic _length`
  (`JvmLengthRuntimeBuilder`; `_ivLength`/`_fvLength` delegate down to it).
- **WASM**: `_seq_len ((ref null eq)) -> (ref null eq)` --
  `WasmLengthCompiler.buildSeqLenBody`, index `FUNC_SEQ_LEN`, appended after
  `FUNC_ARR_SET` as the new `FX_FUNC_LAST`, type `TYPE_CALLABLE_BASE + 0`,
  returning an i31-boxed count.
- Strings answer through the character-count helper (`_str_char_count` on WASM, `_scount`
  on the JVM), no recount -- `.kb/string-index-cost.md`.
- **The `DeclaredArrayTypes` short-circuit stays at the site**: an argument pinned to a
  packed integer vector compiles to a trapping `ref.cast` + `array.len` and never calls
  (`.kb/declarations-type-checks.md`).
- Both optimize levels emit the call; not a `prefersSizeOverSpeed()` trade, except at a
  site pinned to the general kind (below).
- Marginal cost of one more generic site: single-digit bytes. Rows:
  `size-report/results/wasm-flags.md`.
- Per-site ladders of the same shape (`position`, `assoc`, `reverse`, `elt`, `member`,
  `eql`, `aref`) still inline -- separate decisions.
- A new tier edits `buildSeqLenBody` alone; a hot `_seq_len` gets a fast path, never a
  return to inlining.
- **The order of the arms is by what a length costs there** (2026-11-16): a general
  vector first (its answer is a field read; it sat behind the packed float, three packed
  integer and string tests), then a list (a cons or nil, whose walk dwarfs the tests),
  then the packed arrays and the string. The representations are disjoint, so the order
  decides nothing else. The vector arm reads its header once. A Starlark `xs[i]` asks
  the length of its list's vector on every element (`.kb/starlark-frontend.md`,
  "Against CPython"); what a test costs: `.kb/adjustable-arrays.md`, "What an element
  access costs".

- **A general vector's length is its cell's meta field** (2026-11-16): the cell of a
  rank-1 vector with storage of its own names its `(fillPointer . ..)` cons
  (`.kb/adjustable-arrays.md`, "What an element access costs"), so `_seq_len` answers
  the fill pointer, or the storage's own length, behind one cast and before the header
  is walked: 30M `length` of a vector 0.52 -> 0.35 s. A site whose argument is pinned to
  the `GENERAL` kind -- a declaration, a `(the ..)`, a structure slot's `:type`
  (`.kb/declarations-type-checks.md`) -- reads the same fields inline at the speed levels
  (`WasmLengthCompiler.emitGeneralVectorLength`) and calls `_seq_len` for what has none:
  a view, another rank, a false declaration. 61 B where the call is 3, which is why it is
  not every site: a length is mostly asked of a list or a string. `listidx.star` 1.35 ->
  1.30 s over the Starlark library's sites (its list's `items` slot is typed).

## Tests
- `WasmLispCompilerIntegrationTest#declaredArrayTypesEmitSingleArmAccessorsWithoutChangingResults`
  (a `(vector t)` parameter and slot, a fill pointer, before and after a push grows the
  storage), `ArefRankAndSubscriptChecksFixture.ORDER_SOURCE`'s last rows.
- `WasmLispCompilerTest.aLengthSiteDoesNotCarryItsOwnCopyOfTheSharedDispatch` -- the
  marginal byte budget; nothing else notices.
- Length cases in `WasmLispCompilerIntegrationTest` / `JvmLispCompilerTest`, sequence
  cases in `ci-spec.yaml`, the zlib gunzip check in `ChipzE2eTest`.
