# 414. The wasm PBKDF2 residual is per-iteration CLOS re-init and allocation, not the compression

Difficulty: High (the residual is spread across the CLOS effective-method
machinery, per-call keyword consing and closure allocation -- no single seam,
and the profiler cannot be trusted inside large functions)

Follow-up to the closed `.todo/413`. That item took the ironclad
`pbkdf2-derive-key :sha256` x4096 derivation on `--component` from ~137 ms to
~87 ms (wasmtime 47.0.3, M4; ±5 ms run-to-run) by closing the fused/boxed
boundary seam -- statement-position effect propagation, inline literal add/sub
checks, declare-tolerant flet fusion, literal `loop` steps, `%replace-bulk`,
packed `:type vector` structs, condition-position raw compares
(`.kb/wasm-int-fusion.md` stage 5). The compression itself now runs at its
fused floor: a faithful standalone update+expand pair measures ~2.6 us/block,
and the in-library share matches it.

## What the 413 profile got wrong (do not re-chase it)

wasmtime 47's guest profiler attributes samples inside a LARGE function to
small callees (the 60k-instruction fused `update-sha256-block` shows ~0 self
while a 30-instruction accessor or `_iv_set` shows 30-66%). 413's "69%
indirect-call dispatcher / 55% keyword parsing" reading was partly that
artifact: after the boundary fixes, no dispatch or plist-walk function has
meaningful SELF time. Verify any suspicious self% against the callee's static
call count, and A/B wall-clock against a standalone reproduction before
believing a profile bucket.

## The residual, by arithmetic

87 ms/derivation - 16384 x 2.6 us (compression floor) ≈ 44 ms ≈ **11 us per
PBKDF2 iteration** of non-compression work. Each iteration runs
`(reinitialize-instance hmac :key passphrase)` + `update-hmac` + `hmac-digest`,
i.e.:

- the CLOS chains: hmac's `reinitialize-instance` method -> inner+outer digest
  `reinitialize-instance` -> `call-next-method` -> system default ->
  `(apply #'shared-initialize ...)` -> the hmac `shared-initialize :after`
  (which re-derives the whole key schedule: 3 fresh 64-byte `make-array`s,
  `replace`, 2 `xor-block`s, 2 compressions). The `_apply`/spread dispatcher
  and the `buildNextChain` lambdas are ~43% INCLUSIVE, and every generic call
  allocates its next-chain closures and keyword tails.
- per-call `&key` consing: `(update-digest d seq :start s :end e)` builds a
  fresh 4-cons keyword tail per call, and the callee's desugared prologue walks
  it (`.kb/lambda-lists.md`); several such calls per iteration.
- small always-on helpers: `_t_sym` (the cached-t helper still costs a call per
  true comparison outside fused-condition positions; eager global init would
  let `emitTrue` be a bare `global.get`), `_str_build`
  (`IRONCLAD:HMAC-DIGEST`/`%UPDATE-DIGEST--m1` build some string per call --
  find what), `ub16ref/be` and `fill-block-ub8-be` leftovers.

Candidate attacks, in rough value order (measure first -- see the profiler
warning above):

1. **Cut per-iteration allocation.** The GC-visible cost of the CLOS chain is
   its allocation: next-chain lambdas (capture-free, could be preallocated
   constants), keyword cons tails at DIRECT call sites with literal keywords
   (bindable to positional slots at compile time -- 413's "compile-time
   keyword resolution" attack, still unimplemented and now correctly sized),
   and the three fresh pad arrays ironclad conses per re-key (ironclad's own
   code; not ours to change).
2. **Eager `_t_sym` init** so `emitTrue` is a `global.get` (needs the t symbol
   built before ANY entry path runs -- `_start`, component lifts, exports).
3. **Fast-path code layout**: the fused emission interleaves each site's dead
   fallback with the hot straight-line path; 64 rounds spread the hot path over
   ~200 KB of machine code. Emitting fallbacks out of line (one cold block per
   function tail) would need the bail branches restructured -- measure I-cache
   pressure first (the 2.6 us standalone floor may already be wasmtime codegen
   bound: 413 recorded 1.5 us on an earlier compiler with plain arrays).

## Acceptance

- A measured share for whichever attack lands, recorded in the matching `.kb`
  file, and the `.kb/asdf.md` number (87 ms) updated.
- `IroncladE2eTest` + `ClPostgresE2eTest` byte-identical on all four backends.
