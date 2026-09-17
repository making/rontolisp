# Transcendental functions: the digits are each backend's own

**Invariant: `exp log sin cos tan asin acos atan sinh cosh tanh` and float `expt` are NOT
bit-identical across backends, so a cross-backend corpus (`ci-spec.yaml`,
`scheme-spec.yaml`, the SICP corpus harness) must not print their low digits.** Pin exact
anchors (`(exp 0.0)` 1.0, `(atan 1 0)` pi/2, `(log 1.0)` 0.0) or compare within a
tolerance. `sqrt` of a non-negative float is `Math.sqrt` / `f64.sqrt`, correctly rounded,
and IS identical everywhere; so are `+ - * /`.

## Where each lives

- Interpreter (`eval/Environment`) and JVM (`codegen/jvm/JvmMathFnCompiler`): `java.lang.Math`,
  which HotSpot intrinsifies per CPU -- `Math.exp(1.0)` is `2.718281828459045` on x86-64
  and `2.7182818284590455` on AArch64 (`.kb/jvm-complex.md`). The two agree on one machine.
- WASM (no transcendental instruction): software cores emitted per call site --
  `WasmExpCompiler`, `WasmLogCompiler`, `WasmSinCosCompiler`, `WasmAtanCompiler`,
  `WasmSinhCoshCompiler`, `WasmTanhCompiler`, `WasmInverseHypCompiler`, `WasmExptCompiler`
  (`exp(y log x)`) -- MIRRORED constant for constant by the `--simd`/`--no-gc` kernels
  (`WasmVecSimdRuntimeBuilder.emit*F64`) and reused by the complex functions
  (`.kb/wasm-complex.md`), each contract being "bit-identical to the scalar defun".

## Measured (2026-09-17, x86-64 Linux, Java 25.0.4, wasmtime 47)

Seeded random arguments (300 per function: trig on [-10, 10], `exp` on [-30, 30], `log`
of `exp` of [-50, 50], `expt` base [0.1, 10] exponent [-5, 5]), ulps from glibc's
result (near correctly rounded), interpreter = JVM on every argument:

| function | interp/JVM exact / max ulp | wasm exact / max ulp | wasm = JVM |
|---|---|---|---|
| sin | 307 / 1 | 133 / 62,382 | 134 |
| cos | 308 / 0 | 129 / 60,236 | 129 |
| tan | 308 / 0 | 77 / 93,168 | 77 |
| atan | 288 / 1 | 175 / 4 | 173 |
| atan (2 args) | 250 / 1 | 179 / 3 | 158 |
| exp | 307 / 1 | 36 / 18 | 35 |
| log | 308 / 0 | 126 / 247,450 | 126 |
| expt (float) | 300 / 0 | 40 / 398,017 | 40 |
| asin / acos | 279, 284 / 1 | 131, 133 / 4, 3 | 130, 134 |
| sinh / cosh / tanh | 300, 281, 243 / 0-2 | 130, 149, 186 / 154, 2, 28 | 130, 152, 180 |
| sqrt | 308 / 0 | 308 / 0 | 308 |

The large ulp counts are where the answer is near zero (`log` near 1, `sin` near a
multiple of pi): an absolute error of ~1e-11 is many ulps of a tiny result.

`Math` vs `StrictMath` (fdlibm, the only bit-reproducible `java.lang` choice), 10^6
arguments: differ on sin 3.2%, cos 3.3%, tan 3.5%, exp 9.6%, log 0.2%, tanh 5.4%, pow 9.7%;
identical on atan, atan2, asin, sinh. Per call StrictMath costs +0-50% (exp 14.8 -> 21.4 ns,
tanh 18.2 -> 27.9 ns, sin 18.4 -> 22.9 ns; lambda-dispatched loop, indicative only).

SICP corpus: of the 36 samples that call these procedures and exit 0, with every
top-level expression wrapped in `write`, ONE prints different digits on wasm
(`chapter1/section1/subsection8/02.scm`, `(exp (double (log 14)))`: `195.99999999999991`
on the JVM, `196.0000000000001` on wasm); the JVM matched the interpreter on all 36.

## Why it is not unified yet

One set of bits on every backend AND every JVM platform means fdlibm everywhere:
`StrictMath` on the interpreter and the JVM, an fdlibm port for WASM (including
`__kernel_rem_pio2`), replacing every core above and every kernel that mirrors one. It
also fixes the WASM ACCURACY (up to 4e5 ulp today), which is the stronger reason. That is
`.todo/842`, sized beyond the Scheme `(scheme inexact)` item that measured it.
