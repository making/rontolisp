# 791 item 3: what `wasm-opt -Oz` still finds on the shaken output, decomposed

Numbers and the reading: `.kb/optimize-dead-code-elimination.md`, "What an external
optimizer still finds, and what it is made of". The scripts here reproduce them.

Inputs: a module compiled by the jar (`--optimize=size` unless stated), binaryen 130
(`npx binaryen`'s `bin/wasm-opt`, none being on the PATH here), `wasm-tools print`.
Flags for every wasm-opt run: `--enable-gc --enable-reference-types
--enable-exception-handling --enable-bulk-memory --enable-multivalue --enable-sign-ext
--enable-nontrapping-float-to-int --enable-simd --enable-tail-call`.

- **The tax.** `wasm-opt M -o rt.wasm` with no passes: `rt - M` is binaryen's own
  re-encoding growth (+1,452 B on zlib, +13,080 B on the hello-clack Worker). Every
  "residue" is `M - optimized + tax`.
- **Inlining's share.** `-Oz` against `-Oz --skip-pass=inlining --skip-pass=inlining-optimizing`
  (`--no-inline=*` does nothing on a module without a name section).
- `analyze.py M.wasm sizes.txt survivors.wat` -- per-function call-site census; `sizes.txt`
  is the `-Drontolisp.wasm.debug-func-sizes=1` dump (names by final index), `survivors.wat`
  a `wasm-opt ... -S` output whose `$N` names are the input's function indices, so the
  functions binaryen inlined away are the ones missing from it.
- `perfunc.py rt.wasm base.bwat opt.wasm opt.bwat sizes.txt [n]` -- per-function size delta
  of one pass (`base.bwat` = `wasm-opt M -S`, `opt.bwat` = `wasm-opt --pass M -S`), which is
  how `remove-unused-brs` was traced to the sparse `br_table` ladders and
  `simplify-locals` to the boxed-variable prologue of `%INFLATE-STATE-MACHINE`.
- `fn.py file.bwat N` -- one function's binaryen text, for a `diff` before/after a pass.
- `patterns.py M.wat ...` / `locals.py M.wat ...` -- the shape census over a `wasm-tools
  print` (the table in the kb file): adjacent-instruction patterns, wide local indices,
  accessor operands, box inits.
- `secsizes.py M.wasm ...` -- section sizes.
- `*-census.txt` -- the two censuses the ranking was read from (2026-09-12, before the
  cons readers landed).
