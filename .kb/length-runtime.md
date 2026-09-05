# The generic `length` dispatch is a shared callee, not per-site code

**Invariant: no `length` site emits the sequence-type ladder inline on either compile
path. A site is one call -- `_seq_len` on WASM, `_length` on the JVM -- unless the
argument's representation is already pinned, in which case it is a direct `array.len`.**
Same lesson as `.kb/wasm-shared-coercion.md`, `.kb/sequence-op-runtimes.md`.

- **JVM**: `JvmLengthCompiler` emits one `invokestatic _length`
  (`JvmLengthRuntimeBuilder`; `_ivLength`/`_fvLength` delegate down to it).
- **WASM**: `_seq_len ((ref null eq)) -> (ref null eq)` --
  `WasmLengthCompiler.buildSeqLenBody`, index `FUNC_SEQ_LEN`, appended after
  `FUNC_ARR_SET` as the new `FX_FUNC_LAST`, type `TYPE_CALLABLE_BASE + 0`; 310 bytes,
  returns an i31-boxed count.
- Strings answer through the character-count helper (`_str_char_count` on WASM, `_scount`
  on the JVM), no recount -- `.kb/string-index-cost.md`.
- **The `DeclaredArrayTypes` short-circuit stays at the site**: an argument pinned to a
  packed integer vector compiles to a trapping `ref.cast` + `array.len` and never calls
  (`.kb/declarations-type-checks.md`).
- Both optimize levels emit the call; not a `prefersSizeOverSpeed()` trade.
- Marginal cost of one more generic site: ~309 bytes -> 4. Rows:
  `size-report/results/wasm-flags.md`.
- Per-site ladders of the same shape (`position`, `assoc`, `reverse`, `elt`, `member`,
  `eql`, `aref`, 204-451 bytes/site) still inline -- separate decisions.
- A new tier edits `buildSeqLenBody` alone; a hot `_seq_len` gets a fast path, never a
  return to inlining.

## Tests
- `WasmLispCompilerTest.aLengthSiteDoesNotCarryItsOwnCopyOfTheSharedDispatch` -- the
  marginal byte budget; nothing else notices.
- Length cases in `WasmLispCompilerIntegrationTest` / `JvmLispCompilerTest`, sequence
  cases in `ci-spec.yaml`, the zlib gunzip check in `ChipzE2eTest`.
