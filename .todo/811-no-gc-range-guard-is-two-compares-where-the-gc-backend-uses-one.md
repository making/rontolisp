# `--no-gc`: a narrow boundary value is range-checked with two compares where the GC backend uses one

Difficulty: Low

**Status:** open, prototyped and measured 2026-09-14 against `c972efa5d`.

## The two backends disagree, and the scalar one is the larger

`WasmExportCompiler.emitNarrowIntResult` (the wasm-GC lowering) already says it:

> In range exactly when narrowing to the declared width and widening back is the
> identity: `v != canon(v)` traps, one compare for every width.

`NoGcWasmCompiler.emitRangeChecks` still emits two bound comparisons for a
signed narrow type -- `emitTrapIf(I64_LT_S, range.min())` and
`emitTrapIf(I64_GT_S, range.max())` -- each carrying its bound as an `i64.const`
whose signed LEB128 runs to five bytes at `:s32`.

The canon form carries no constant at all.

## Measured

Bytes of the check itself (between the leading `local.set slot` and the trailing
`local.get slot`, which the peephole turns into a `local.tee`), read off the
emitted wrapper of a probe exporting each width:

| type | now | canon | delta | |
| --- | ---: | ---: | ---: | --- |
| `:s8` | 20 | 10 | **-10** | `i64.extend8_s` |
| `:s16` | 22 | 10 | **-12** | `i64.extend16_s` |
| `:s32` | 26 | 11 | **-15** | `i32.wrap_i64; i64.extend_i32_s` |
| `:u32` | 13 | 11 | -2 | `i32.wrap_i64; i64.extend_i32_u` |
| `:u8` | 10 | 13 | **+3** | keep the single bound compare |
| `:u16` | 11 | 14 | **+3** | keep the single bound compare |
| `:u64` | 9 | -- | 0 | only the sign can be wrong; keep |

So the canon form is taken for `:s8`, `:s16`, `:s32` and `:u32`, and the two
small unsigned widths keep what they have: their one bound is a two- or
three-byte constant and the mask the canon would need costs more than that.

Whole modules, `--no-gc --no-wasi --optimize=size`:

| program | raw | gzip -9 | code |
| --- | ---: | ---: | ---: |
| `.todo/artefacts/810-.../reactor.lisp` (two `:s32` results) | 930 -> **900** | 650 -> 639 | 230 -> **200** |
| `.todo/artefacts/811-.../small.lisp` (one `:s32` result) | 271 -> **256** | 245 -> 236 | 88 -> 73 |
| a probe exporting every width | 448 -> 382 | -- | -- |
| a probe passing every width to imports | 451 -> 412 | -- | -- |

Measure gzip as `gzip -9 -c < file`, never `gzip -9 -c file`: the second form
stores the filename in the header.

## The shape

```
local.set  slot
local.get  slot
local.get  slot
<canon>            ;; extend8_s | extend16_s | wrap+extend_i32_s | wrap+extend_i32_u
i64.ne
if (empty)
  unreachable
end
local.get  slot
```

Keep `emitRangeChecks`'s existing contract -- leading `local.set slot`, trailing
`local.get slot`, `needsBoundaryRangeGuard` answering the same set of crossings.
This changes the SHAPE of the check, not which crossings get one.

`I64_EXTEND32_S` (0xC4) would save one more byte per `:s32` site and is accepted
by `wasm-tools`, Node 24 and wasmtime, but `am.ik.wasm` has no constant for it
and the GC backend spells the same thing `i32.wrap_i64; i64.extend_i32_s`. One
byte a site is not worth the two lowerings diverging again; prefer the GC
spelling.

## Verified in the prototype

- Trap sets identical before and after, and identical between `--optimize=off`
  and `--optimize=size`, across four combinations (export results and import
  arguments x two levels): 130 export calls (66 traps) and 92 import calls (46
  traps), each width driven at its min, max, min-1, max+1, 0, +-1, +-2^bits
  around both ends, and the +-2^63 extremes. Confirmed on a second engine with
  `wasmtime --invoke` (31 cases).
- Interpreter as the oracle: the probe compiled WITH wasi, run under `wasmtime`,
  stdout byte-identical to `java -jar ... probe.lisp`, before and after and at
  both levels.
- The GC backend is untouched: its output for the same programs is `cmp`-identical.
- `-Dtest='NoGc*Test,Wasm*E2eTest,WasmExport*Test,WasmImport*Test,*Boundary*Test'`
  -- 272 run, 0 failures, 0 errors. No test pins the old byte shape.

## The one thing that genuinely moves

`chooseFoldedImports` does NOT compute the guard's size independently -- both
sides of its comparison emit real bytes (`emitImportArgUnbox` for the sites,
`compileImportWrapperBody` for the wrapper), so the sizing follows the emitter
and cannot drift.

But the FOLD DECISION itself legitimately changes: a site's cost carries one
guard per site while the wrapper's saving carries exactly one, so a shorter
guard tilts the comparison toward folding once there are two or more sites. A
`(:string :s32)` import called from N sites, before -> after: N=1 124 -> 109,
**N=2 157 -> 137 (the decision flips from not-folding to folding)**, N=3
169 -> 154, N=4 181 -> 166. Every outcome is smaller, so the flip is a win, but
a test that pins which imports fold would see it.

## On landing

Run `spring-javaformat:apply`, and update the guard's description in
`.kb/no-gc-scalar-wasm.md` so the file states the shape that is emitted.
