# 105 — packed float arrays as `(array (mut v128))`: a GC-managed alternative to the `--simd` linear arena

**Status 2026-07-09: NOT started. Raised after `.todo/101` shipped, and it invalidates 101's
stated premise.** Decide before `.todo/104` (`with-arena`) — if this lands, 104 has almost
nothing left to reclaim.

## The premise 101 inherited, and why it was too narrow

`.todo/101` opened with: *"WASM `v128.load`/`store` address linear memory. wasm-GC packed
float arrays are GC objects ... with no linear-memory address — so there is no `v128.load`
from them."* True, and it led straight to the linear-memory arena that 101 shipped.

But `v128.load` is not the only way to get a `v128` onto the stack. In the GC proposal
`fieldtype ::= storagetype ::= valtype | packedtype`, and `valtype` includes
`vectype = v128`. So **`(array (mut v128))` is a legal type**, and `array.get` on it yields
a `v128` — a 16-byte lane group, straight out of a garbage-collected object.

Verified 2026-07-09 (`.wat` in the scratchpad, reproduced below in words):

- `wasm-tools validate --features=gc,simd` — **valid**.
- `wasmtime run -W gc` — **runs**, correct result.
- Node 22 / V8 (`WebAssembly.validate` + instantiate) — **valid, runs**. So the jco and
  browser paths would be fine too.

So a packed `#d` vector of length *n* could be an `(array (mut v128))` of `ceil(n/2)` lane
groups (`ceil(n/4)` for `#f`), the kernels could run `f64x2.*` over `array.get`/`array.set`,
and **the engine's collector would reclaim it**. No `_vec_alloc`, no `VEC_HEAP_PTR_ADDR`, no
`memory.grow`, no arena to pop — the exact problem `.todo/104` exists to solve, gone for
packed arrays. That is a strictly better memory story than what 101 shipped, and 101 never
evaluated it.

## What it costs (measured / spec-grounded, not guessed)

Not a free win. Three real costs, one of which is structural.

### 1. Kernel loops get ~20% slower (measured, one shape)

Dot over 8192 f64, 20000 iterations, `wasmtime run -W gc`, startup+fill subtracted:

| storage | loop |
|---|---|
| `(array (mut v128))` + `array.get` | 51 ms |
| linear memory + `v128.load` | 43 ms |

Both are ~200x faster than the scalar `vec.lisp` defun, so this delta does not change the
headline. The gap is presumably the per-`array.get` null + bounds check. Unmeasured on V8.

### 2. Scalar element access becomes a lane branch (and a read-modify-write on store)

`f64x2.extract_lane` / `replace_lane` take an **immediate** lane index — confirmed: wasmtime
rejects a runtime `local.get` there ("expected a lane index"). So:

- `(aref v i)` = `array.get (i >> 1)` then `if (i & 1) extract_lane 1 else extract_lane 0`.
- `(setf (aref v i) x)` = `array.get` → `replace_lane` (branch) → `array.set`. A RMW.
- `#f` (4 lanes) needs a 4-way branch, so a `br_table`.

This hits `aref` / `%aset` / `row-major-aref` / `%row-major-aset`, the printer, `vec:aref` /
`aset` / `to-list` / `from-list`, and **all of `linalg:`**, which is nothing but scalar
`aref` over packed arrays. Mitigating context: every one of those already pays i31 unboxing
and a `TYPE_FLOAT` `struct.new` per element, so the added ~10 wasm instructions are a
fraction of the existing cost, not a multiple of it. Needs measuring, not assuming.

### 3. The structural one: group-granular indexing breaks unaligned rank-2 rows

`v128.load` takes a **byte** address, and misalignment is legal (the memarg alignment is a
hint, never a trap). So `vec:matvec` today walks `wrow += n << shift` and reads row *r* of a
*d x n* matrix with plain `v128.load`s even when *n* is odd — row 1 of a 2x3 matrix starts
8 bytes in, mid-group, and it just works (`ci-spec` and the integration test both cover the
odd-*n* case).

`(array (mut v128))` can only be indexed by whole **group**. A row starting at an odd flat
element index cannot be read as groups at all. The fixes, none free:

- **Pad the row stride** to a multiple of the lane count. Then flat row-major indexing is no
  longer the identity, and `row-major-aref` / `array-total-size` / `array-row-major-index` /
  the printer / `copy-array` / every `linalg:` transform must all learn the padded stride.
  That is a far larger blast radius than the five sites 101 touched.
- **Fall back to scalar for `n % lanes != 0`.** Honest, and cheap to implement, but it makes
  GEMV performance depend on a matrix dimension's parity — a nasty surprise.
- Store matrices transposed. Changes the `linalg:` contract.

### 4. The `--no-gc` seam splits

`WasmVecLoops` currently holds one set of loop bodies for both `--no-gc` and wasm-GC
`--simd`, because both walk raw i32 pointers into linear memory. `--no-gc` has no GC types,
so it must keep the `v128.load` bodies; wasm-GC would need `array.get` bodies. Two
implementations of each kernel instead of one.

## Recommendation

Worth a real prototype, because cost 1 is small, cost 2 is probably smaller than it looks,
and the payoff — deleting an entire manual-memory subsystem from a garbage-collected
backend — is large. Cost 3 is the decider: measure how much the padded row stride actually
infects the generic array surface before committing.

Suggested order:

1. Spike `vec:dot` / `vec:sum` / `vec:add` on `(array (mut v128))` behind a second flag, and
   measure against the current `--simd` on `wasmtime` **and** on `jco`/Node. Confirm the ~20%
   figure holds at other sizes and for `f32x4`.
2. Spike `aref`/`aset` with the lane branch and measure `linalg:add` / a `linalg` transform
   against the current `--simd`. This is the number that decides whether `linalg:` regresses.
3. Only then decide rank-2: padded stride vs parity fallback.
4. If it lands: delete `_vec_alloc`, `VEC_HEAP_PTR_ADDR`, the `memory.grow` path, and the
   packed-array half of `.todo/104`; the `-into` kernels stay, demoted to an
   allocation-rate optimization exactly as on the JVM and the interpreter.

## Pointers

- What 101 built (and what this would replace): `.kb/vec.md` "Acceleration layer 3",
  `WasmVecSimdRuntimeBuilder`, `WasmVecLoops`, `WasmArrayCompiler`'s `*Linear` helpers,
  `WasmQuoteCompiler.compileLinearBlock`, `WasmRuntimeBuilder.emitPrintArray`.
- The odd-*n* GEMV that motivates cost 3: `WasmVecSimdRuntimeBuilder.emitMatvecRows`.
- `.todo/104` (`with-arena`) — mostly obsoleted for packed arrays if this lands; the
  string/intern heap (`HEAP_PTR_ADDR = 84`) is a separate question either way.
- `.todo/99` (`--no-gc` rank-2 GEMV) — unaffected; `--no-gc` keeps linear memory regardless.
