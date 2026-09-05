# `random` draws from a generator inside the program, seeded once from the host

`(random n)` is a PSEUDO-random draw from a generator the program CARRIES, on every backend; a
host call per draw is the defect this file prevents. Entropy is a SEPARATE API:
`rontolisp::%random-byte` behind `rontolisp:random-bytes` calls `random_get` (wasm) /
`SecureRandom` (JVM, interpreter) per byte, and answering it from the generator below is a
security regression. No random-state objects exist (`make-random-state` -> `nil`; `random`'s
optional state argument normalized away -- `LispNames.MAKE_RANDOM_STATE`, `LispMacroExpander`).

| backend | generator | seed |
| --- | --- | --- |
| interpreter, JVM | `java.util.concurrent.ThreadLocalRandom` | the JDK's, per thread |
| wasm Preview 1 | inline SplitMix64 over `RANDOM_STATE_ADDR` | 8 bytes of `random_get`, first draw |
| wasm `--component` | same | 8 bytes of `wasi:random` via the adapter's `random_get` |
| wasm `--no-wasi` | same | none -- fixed start, or `__ronto_seed_random` (`.kb/wasm-export-no-wasi.md`) |
| wasm `--no-wasi --host-random` | same | 8 bytes of the `env.random_get` host import (a SEED) |

- **Four JVM sites must agree on the FORMULA** `(long) (current().nextDouble() * limit)`:
  `Environment.createGlobal`, `JvmRandomCompiler`, `JvmNumericRuntimeBuilder.buildRandom`
  (`_random`), `JvmIntFusionCompiler.emitRandomDraw` (`.kb/jvm-int-fusion.md`). Constant pool
  `JvmMathFnCompiler.TLR_CURRENT` / `TLR_NEXT_DOUBLE`.
- Trap: a fused site draws exactly ONCE, in the prologue before any guard, and the fallback only
  READS it -- a drawing fallback re-emits twice for a substituted parameter used twice, so
  `(defun dif (x) (- x x))` over `(dif (random lim))` would stop answering 0.
- wasm emits the SplitMix64 step INLINE (`WasmRandomCompiler`);
  `WasmIoRuntimeBuilder.emitSplitMix64Next` is the ONE implementation, shared with the
  `--no-wasi` `random_get` slot body. Float path masks the low 32 bits to `[0, 2^31)`, integer
  path masks 64 to `[0, 2^63)` then `rem_u limit`; an unknown limit type draws once before
  `ref.test TYPE_FLOAT`.
- Seeding is lazy, once per INSTANCE, self-gating on flag cell `RANDOM_SEEDED_ADDR` (252, the last
  word under `DATA_BASE_OFFSET=256`), then `random_get(RANDOM_SCRATCH_ADDR, 8)` -- at the CALL
  SITE, not a `_start` prologue (which would import `random_get` into modules that have none).
- Invariants: emitted BYTES deterministic while drawn NUMBERS are not
  (`.kb/emitted-output-determinism.md`); a program that never draws emits no seeding and no
  `random_get` import; `(random 1)` is 0 everywhere; limit type decides result type.

## Tests
ci-spec `random-deterministic-properties`; `WasmLispCompilerIntegrationTest`'s four `noWasi*` /
`aWasiBuildDrawsFromTheInModuleGenerator...` cases;
`WasmImportCompilerTest#underHostRandomTheEntropyApiReachesTheHostAndAnUnusedImportIsStillShaken`;
`LispEvaluatorTest` / `JvmLispCompilerTest` `random` cases.
