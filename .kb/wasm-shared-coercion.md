# The numeric-to-`f64` coercion is ONE function, not an inlined ladder

**Invariant: no site emits the numeric type ladder inline. Every place that wants an
unboxed `f64` out of a Lisp value emits `call FUNC_AS_F64` and nothing else.**

`_as_f64 ((ref null eq)) -> f64` (`WasmEmitHelper.buildAsF64Body` /
`emitAsF64FromLocal`, function index `FUNC_AS_F64`, type `TYPE_BIG_TO_F64`) dispatches
on the runtime tier: an i31 fixnum converts directly, a `TYPE_BIGNUM` converts its
`i64` field, a limb `TYPE_BIGINT` goes through `_big_to_f64`, a `TYPE_RATIO` divides
numerator by denominator (float contagion), and anything else is cast to `TYPE_FLOAT`
and read. The tiers themselves are `.kb/wasm-bignum.md`.

## Why it is a function

The ladder is a five-way `ref.test` chain: **~80 bytes at every site**, and it grew
there -- it was a three-way chain before the boxed-i64 and limb tiers landed
(`.todo/276`, "every numeric coercion inlines a longer type ladder"). Two things made
that expensive out of proportion to the code that asked for it:

- **it is per OPERAND, not per operation.** `(+ sum (/ sign (+ (* 2.0 i) 1.0)))` --
  five-line `examples/wasm-size/pi_approx` -- coerces ten times, so its top-level body
  was 1,027 bytes of which ~800 were ladders;
- **the ratio runtime carried its own copy** (`WasmRatioRuntimeBuilder.emitLocalToF64`,
  sixteen call sites across `_rat_add`/`_rat_div`/`_rat_rem`/`_rat_cmp`/`_rat_cmp_bits`),
  which is more than half of every copy in a typical float program's module.

Measured on `pi_approx` at `--optimize`: 26 ladders, 2,080 of the module's 4,863-byte
code section (**43%**). Sharing them:

| program | flags | before | after | |
| --- | --- | ---: | ---: | ---: |
| `wasm-size/pi_approx` | `--optimize` | 5,356 | 3,540 | **-33.9%** |
| `wasm-size/pi_approx` | `--optimize=size` | 5,236 | 3,420 | -34.7% |
| `wasm-size/pi_approx` | `--component --optimize=size` | 6,365 | 4,549 | -28.5% |
| `ml/mlp` (float, no `vec:`) | `--optimize` | 152,408 | 136,970 | -10.1% |
| `ml/nn` | `--optimize` | 116,013 | 102,798 | -11.4% |
| `ml/numerical-calculus` | `--optimize` | 271,936 | 254,970 | -6.2% |

It also stops `castFloatGetF64` calling `ctx.allocTemp()` per site. Compile-path temps
are never released (`.todo/276` item 4, the JVM twin is `.todo/137`), so every ladder
used to widen the enclosing body's local vector too.

## Why it is at the DEFAULT optimize level

`.todo/276` proposed making this an `--optimize=size` trade "if the speed cost is
real". Measured, it is not: `ml/mlp`, the float-heaviest example that is not a `vec:`
kernel, went **5.6 s -> 5.4 s** (wasmtime 47.0.2, best of three), and `pi_approx` is
0.12 s either way. A call per coerced operand is cheap next to the `ref.test` chain it
replaces, and the smaller module appears to pay for it. So this is not one of the two
speed/size trades `prefersSizeOverSpeed()` switches (`.kb/wasm-int-fusion.md`,
`.kb/wasm-unboxed-locals.md`) -- both levels emit the call.

## The re-evaluation trigger

If a future tier makes `_as_f64` hot enough to matter, the answer is a FAST PATH
inside the shared function (or an i31-only guard before the call), **not** a return to
inlining -- the measurement above is what would have to be redone, and the "43% of the
code section" number is what an inline ladder costs the moment there is more than one
of it. Adding a tier means editing `emitAsF64FromLocal` alone; that it is the only
copy is the property worth keeping.

`--no-gc` has no ladder at all (a numeric subset with no boxed values,
`.kb/no-gc-scalar-wasm.md`), and the JVM backend's equivalent is the generated
`_dbl` method, which was already out of line.
