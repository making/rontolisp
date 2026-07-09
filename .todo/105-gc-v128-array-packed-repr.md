# 105 — packed float arrays as `(array (mut v128))`: make wasm-GC `--simd` garbage-collected again

**Replaces the linear-memory arena `.todo/101` shipped.** Same v128 kernels, same speedup, but
the storage becomes a GC object, so `_vec_alloc` / `VEC_HEAP_PTR_ADDR` / `memory.grow` and the
whole "you must watch memory growth" caveat go away on wasm-GC.

**Decision made 2026-07-09** (user): a garbage-collected backend should not ask the user to think
about memory. If the ~20% kernel-loop cost matters, use `--no-gc`, which stays linear-memory and
byte-identical. Do this.

**Status: NOT started. The design below is settled — every open question was measured, not guessed.
Sequence BEFORE `.todo/104` (`with-arena`), which loses its packed-array half once this lands.**

## Why 101's premise was wrong

`.todo/101` opened with *"v128 addresses linear memory ... there is no `v128.load` from [a GC
array]"*. True of `v128.load`, but the GC proposal's `fieldtype ::= storagetype ::= valtype |
packedtype` and `valtype` includes `vectype = v128`. So **`(array (mut v128))` is a legal type and
`array.get` on it yields a `v128`** — a lane group straight out of a collected object.

## Measured, on `wasmtime run -W gc` (M4, startup+fill subtracted)

All four numbers were needed to decide; none was assumed.

| what | today (`--simd`, linear) | proposed (GC `(array (mut v128))`) | today (default, `(array (mut f64))`) |
|---|---|---|---|
| kernel loop (dot, 8192 f64 x 20000) | 43 ms | **51 ms** (+19%) | — (scalar defun, ~200x slower) |
| scalar element READ (163.8M) | 83 ms | **106 ms** | 103 ms |
| scalar element WRITE (163.8M) | — | **195 ms** | 106 ms |

Read: the immediate-lane branch is **+3% over the default backend's `array.get $f64arr`** —
effectively free. Write: the group read-modify-write is **1.85x** the default backend's
`array.set`. Both are wrapped in i31 unboxing + a `TYPE_FLOAT` `struct.new` at every rontolisp call
site, so the end-to-end impact on `linalg:` (which is nothing but `aref`/`%row-major-aset` loops) is
a fraction of that. Kernel loop: the +19% is presumably the per-`array.get` null + bounds check.

## The three things that make it work (all verified on wasmtime AND V8/Node 22)

1. **`(array (mut v128))` validates and runs.** `wasm-tools validate --features=gc,simd` accepts it;
   `wasmtime run -W gc` and V8 (`WebAssembly.validate` + instantiate) both run it. So the jco /
   wasmCloud / browser paths are fine.
2. **`array.new_default` zero-initializes the v128 elements** (verified: summing a fresh array gives
   0). So the tail lanes of the last group are zero and STAY zero under `add`/`sub`/`mul`/`scale`/
   `sum`/`dot` — **every kernel loses its scalar tail loop**. The lowering gets *simpler* than 101's.
   (Only edge case: `vec:scale v s` with `s` infinite makes the padding NaN; but then the real
   elements are already +-inf and any reduction is NaN regardless. Note it, don't code for it.)
3. **A row that starts mid-group is readable with two `array.get`s and one `i8x16.shuffle`.**
   Verified end to end (a 2-lane window across a group boundary returned the right two elements).
   This is what dissolves 101's stated blocker #3 — **no padded row stride, no changed flat
   row-major identity, `row-major-aref` / `linalg:` / the printer all stay as they are.**

Also verified: **declaring `(array (mut v128))` at all requires the SIMD proposal** (`wasmtime
--wasm simd=n` fails to parse the module). So the type must be emitted ONLY under `--simd` — which
is what we want anyway: the default module keeps running on a SIMD-less runtime, and the
`simd=n` dead-flag guard in `WasmLispCompilerIntegrationTest` keeps working unchanged.

## The design

### Types (emitted only under `--simd`, appended after `TYPE_F32ARR`)

```
TYPE_V128ARR = (array (mut v128))
TYPE_VBLOCK  = struct { i32 count, i32 kind, (ref null eq) groups }
```

`TYPE_FARRAY = struct {(ref null eq) dims, (ref null eq) data}` is **unchanged**: `data` holds the
`$vblock` (it is `eq`-typed, as it was for 101's `i31ref` pointer). `kind` 0 = f64 (2 lanes),
1 = f32 (4 lanes) — the runtime width tag that replaces `ref.test $f32arr`, since both widths share
one `(array (mut v128))` type. `count` is the logical element count.
`wrapperTypeIndex = TYPE_F32ARR + 1 + (simd ? 2 : 0)` — the same conditional-index trick
`userFuncBase()` already uses.

`groups` length = `ceil(count / lanes) + 1`. **The `+1` is a zero sentinel group** so a shuffle
window at the last group can always `array.get g+1` without a bounds trap. 16 bytes per array.

### Runtime helpers (replace `_vec_alloc` at `FUNC_VEC_BASE`)

- `_v_new(count i32, kind i32) -> (ref null eq)` — a `$vblock` over `array.new_default $v128arr`.
- `_v_get(arr eq, idx i32) -> f64` — `array.get` group `idx >> laneShift`, then the immediate-lane
  branch (2-way for f64, a `br_table` for f32's 4). Widen f32.
- `_v_set(arr eq, idx i32, v f64)` — `array.get` the group, `replace_lane` (branch), `array.set`.

Centralizing the lane branch in three helpers is what keeps `WasmArrayCompiler` /
`WasmQuoteCompiler` / `WasmRuntimeBuilder` small: their `*Linear` variants become one `call` each.

### Kernels

Same twelve, same `WasmVecSimdRuntimeBuilder` shape, but they walk **group indices** instead of byte
pointers, and **have no tail** (point 2 above). `WasmVecLoops` keeps its linear bodies for `--no-gc`
and gains GC-array bodies; factor the shared skeleton behind a small "load group i / store group i"
emitter interface rather than copying the loops.

`matvec`: `x` is rank-1, so its groups start at element 0. Row *r* of a *d x n* matrix starts at
flat `r*n`, i.e. lane offset `off = (r*n) & (lanes-1)`, which is loop-invariant per row. Branch once
per row:

- `off == 0` — plain `array.get` per group.
- `off == k` — window group *g* and *g+1* with `i8x16.shuffle`, immediate =
  bytes `[k*elemBytes .. 15]` of the first operand followed by `[0 .. k*elemBytes-1]` of the second
  (for f64, `k=1`: `8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23`). One shuffle per group; the
  sentinel group makes the final `g+1` safe.

f64 needs 2 row-loop variants, f32 needs 4.

### Deletions

`_vec_alloc`, `VEC_HEAP_PTR_ADDR` (memory word 160), the `memory.grow` path,
`WasmArrayCompiler`'s `emitVecAlloc`/`emitStoreBlockHeader`/`emitElementAddr`,
`WasmQuoteCompiler.compileLinearBlock`. `-into` stays but is demoted on wasm-GC to what it already
is on the JVM and the interpreter: an allocation-rate optimization, not a memory-safety requirement.

## Docs / kb to revert or amend

- `doc/{en,ja}/guides/simd-acceleration.md` — the memory table's `wasm-GC --simd` row goes back to
  "the WebAssembly GC heap | yes, by the engine's collector"; the "two SIMD-emitting WASM targets"
  prose collapses back to `--no-gc` alone; the `-into` paragraph's wasm-GC measurement goes away.
- `.kb/vec.md` acceleration layer 3, `.kb/wasm-gc-strings.md`'s new `--simd` arena section,
  `CLAUDE.md`'s `--simd`-on-wasm-GC bullet and the `-into` bullet's "two v128-emitting WASM targets".
- `.todo/104` — its wasm-GC half evaporates; the string/intern heap (`HEAP_PTR_ADDR = 84`) remains a
  separate question.
- `WasmLispCompilerTest.simdKeepsTheTypeSectionIdenticalAndAddsExactlyTheVecBlock` — the type section
  no longer stays identical (it gains two types). Assert the two new type entries instead, and keep
  the `+13 -> +N` function-count delta.

## Verification (unchanged bar)

`wasmGcSimdIsByteIdenticalToTheScalarPathOverTheWholeVecSurface` is the contract: both widths, every
former tail configuration (n = 1,2,3,4,5,7,8 — now exercising the zero padding instead of a tail
loop), `-into`, GEMV with **odd** *n* (the shuffle path), the generic packed accessors,
`make-array`, `#d`/`#f` literals, `linalg:`. Plus `--optimize`, `--component`, the `simd=n`
dead-flag guard, and the native `CiSpecE2eTest`. `--no-gc` output must stay byte-identical.

Drop `wasmGcSimdGrowsItsArenaForVectorsLargerThanTheInitialMemory`; replace it with a flatness test
(allocating `vec:add` in a loop keeps a flat RSS, as it does on the scalar wasm-GC path today).

## Pointers

- What this replaces: `.kb/vec.md` "Acceleration layer 3", `WasmVecSimdRuntimeBuilder`,
  `WasmVecLoops`, `WasmArrayCompiler`'s `*Linear` helpers, `WasmQuoteCompiler.compileLinearBlock`,
  `WasmRuntimeBuilder.emitPrintArray`'s `ptrSlot` branch.
- The odd-*n* GEMV that drove the shuffle design: `WasmVecSimdRuntimeBuilder.emitMatvecRows`, and the
  `#d((1.0 2.0 3.0) (4.0 5.0 6.0))` case in the integration test + `ci-spec.yaml`.
- `.todo/99` (`--no-gc` rank-2 GEMV) — unaffected; `--no-gc` keeps linear memory regardless.
