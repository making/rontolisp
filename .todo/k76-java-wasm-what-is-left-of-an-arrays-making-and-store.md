# k76. Java on wasm: what is left of an array's making and store

Difficulty: Medium

Left by k64 (2026-11-20). A Java array variable is declared of its vector or nil, and a
`long[]`'s element read in arithmetic has no fallback (`.kb/declarations-type-checks.md`,
"Declaration-driven array emission"; `.kb/java-frontend.md`, "Numbers"). Two costs stayed.

- **`new T[n]` is the `make-array` of any rank** (`WasmArrayCompiler.compileMake`): the size
  is a computed form, so the site builds dims of any rank (two `_arr_dims` calls, a rank test,
  `_arr_total`, `_vec_sync`), some 150 B each; `ZoneChanges.region` has five of its 6,975 B.
  `%java-array-length` answers an `int` (or raises), so a size the backend knows is an integer
  could take the rank-1 shape alone. Measure the zone reader, `Sieve` and YAVI `Booking.java`.
- **A pinned store boxes a raw index**: `composite[j] = 1` in `SieveLoop` boxes the exact local
  `j` (`compileSubscript`), tests it for an i31, compares and unboxes it again (some 90 B a
  site, `emitKindedAset1`), where a fused read takes the raw index. A raw-index store of a
  packed kind would be about half. Measure `SieveLoop` (bytes, and its time, which a sieve of
  50,000,000 spends mostly in memory).
- Not this item: a general array's `length` at the speed levels is a 57 B inline arm where
  `_seq_len` is 3 B (nine in `region`); measured worth it (10^8 lengths of a general vector in
  a loop, 0.41-0.44 s against 0.53-0.54 s through the call).
