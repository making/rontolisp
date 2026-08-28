# `random` draws from a generator inside the program, seeded once from the host

`(random n)` is a PSEUDO-random draw from a generator the program carries, on every
backend. It is not an entropy API and must never be implemented as one: a host call per
draw is the defect this file exists to keep from coming back.

**The entropy API is `rontolisp::%random-byte`, behind `rontolisp:random-bytes`, and it
is a different code path on every backend.** Nothing in this file applies to it. A change
that lets the generator below answer it is a security regression, not an optimization.

## Why a generator inside the program is inside CL's contract

CL specifies `random` as a draw from `*random-state*`, and a conforming image may start
from a fixed state. rontolisp has no random-state objects at all: `make-random-state`
answers `nil` and `random`'s optional state argument is accepted and normalized away
(`LispNames.MAKE_RANDOM_STATE`, `LispMacroExpander`). **That stays** -- decided
2026-08-26 while making the draw module-local, and it is what makes the draw's
implementation a free choice: no state object is observable, so no program can tell one
generator from another except by the numbers, and the numbers were never promised.

So the only obligations are: the sequence is unpredictable ENOUGH for the uses `random`
has (a temporary file name, a jitter, a shuffle), two runs of the same artifact differ
where a host can make them differ, and nothing cryptographic is served from it.

## What each backend draws from

| backend | generator | seed |
| --- | --- | --- |
| interpreter, JVM | `java.util.concurrent.ThreadLocalRandom` | the JDK's, per thread, from process entropy |
| wasm Preview 1 | inline SplitMix64 over `RANDOM_STATE_ADDR` | 8 bytes of `random_get`, on the first draw |
| wasm `--component` | the same | 8 bytes of `wasi:random` through the adapter's `random_get` |
| wasm `--no-wasi` | the same | none -- fixed start, or `__ronto_seed_random` |
| wasm `--no-wasi --host-random` | the same | 8 bytes of the `env.random_get` host import |

### The JVM half: not `Math.random()`

`Math.random()` is ONE process-wide `java.util.Random` whose 48-bit seed advances by a
`compareAndSet` on a shared `AtomicLong`: a CAS and a memory fence per draw, for state
nothing shares. Measured on the `.todo/528` spike (10^7 draws, this machine):
`Math.random()` 20.6 ns, `ThreadLocalRandom.current().nextDouble()` 3.68 ns, an inline
SplitMix64 over a static field 2.05 ns.

**`ThreadLocalRandom` was chosen over the inline generator** even though it is 1.6 ns
slower: it needs no static field and no `<clinit>` seeding in the emitted class, it is
per-thread by construction (so a threaded program's draws neither contend nor correlate),
and 1.6 ns/draw is 0.016 s on a 10^7-draw row that the change moved by 0.17 s. Re-open
this only if a profile shows the draw itself dominating again.

Four call sites move together and must keep agreeing:
`Environment.createGlobal` (interpreter), `JvmRandomCompiler` (the float-LITERAL path,
which emits `current().nextDouble() * limit` straight),
`JvmNumericRuntimeBuilder.buildRandom` (`_random`, the runtime-typed path) and
`JvmIntFusionCompiler.emitRandomDraw` (the raw leaf inside a fused integer tree,
`.kb/jvm-int-fusion.md`). The constant-pool halves are
`JvmMathFnCompiler.TLR_CURRENT` / `TLR_NEXT_DOUBLE`.

The fused leaf is the reason "agreeing" means the FORMULA, not just the contract: it
computes `(long) (current().nextDouble() * limit)` -- character for character what
`_random` computes for a `Long` limit, including a zero or negative one -- straight into
a raw `long` slot, so the draw a fused site takes and the draw its own fallback would
take are the same expression, and a bail cannot change a program's answers by changing
its generator. It draws exactly once per site whichever path answers -- the draw runs in
the fused method's prologue, before any guard, and the fallback only reads the value it
left, because a fallback re-emits and a substituted parameter used twice re-emits twice
(`(defun dif (x) (- x x))` over `(dif (random lim))` must still be 0). It also reorders
the draw against the site's other operands, which is unobservable precisely because
there is no random-state object to see it with.

### The wasm half: one SplitMix64 step, inlined at the call site

`WasmRandomCompiler` emits the generator step INLINE -- no call, no host boundary, no
round trip through linear memory. One step is: the 8-byte cell at `RANDOM_STATE_ADDR`
advances by the golden-ratio gamma, and the new state is mixed through two
xor-shift-multiply rounds and a final xor-shift. The emitter is
`WasmIoRuntimeBuilder.emitSplitMix64Next`, ONE implementation shared with the `--no-wasi`
`random_get` slot body, parameterized only by how its i64 scratch local is NAMED (a
hand-built body knows its slot number; a `Ctx`-emitted body has to go through the i64
placeholder machinery, because an i64 local's absolute index is not known until the body
is complete).

The float path spends the draw's low 32 bits masked to `[0, 2^31)`; the integer path
masks all 64 to `[0, 2^63)` and takes `rem_u limit`, so a limit beyond the i31 fixnum
range works and the result normalizes through `_int_new`. When the limit's type is not
known at compile time the draw is taken ONCE, before the `ref.test TYPE_FLOAT`, and
parked in an i64 local both branches read -- so a call advances the generator exactly
once either way.

**Seeding is lazy, once per INSTANCE, and self-gating.** The first draw of a module that
has a host to ask reads the flag cell `RANDOM_SEEDED_ADDR` (252, the last word under the
`DATA_BASE_OFFSET=256` headroom; untouched linear memory starts it at 0), sets it, calls
`random_get(RANDOM_SCRATCH_ADDR, 8)` once and stores those eight bytes as the state. Two
runs of the same module therefore draw differently. It is deliberately AT THE CALL SITE
rather than in a `_start` prologue: the prologue is written before the top-level forms
are compiled, so gating it there would need a whole-program "does this reach `random`"
analysis, and getting that wrong in the conservative direction would put a `random_get`
import into every module that has none today. A branch on a cell that is always 1 after
the first draw costs a predicted branch; a wrong import costs the zero-import contract.

`--no-wasi` without `--host-random` emits NO seeding: it has no host to ask, so the state
starts at zero and every instance walks the same sequence, exactly as before this change,
and the exported `__ronto_seed_random` hook stays the way out
(`.kb/wasm-export-no-wasi.md`). The `--no-wasi` `random_get` slot body -- itself a
SplitMix64 over the same cell -- is now reached by nothing there and the tree shaker
drops it; under `--host-random` it is still live, as the seeding call's forwarder.

**`--host-random` changed meaning slightly and deliberately** (2026-08-26): it used to
mean a host call per `random` draw, and now means the module's generator is SEEDED from
the host instead of starting fixed. `rontolisp:random-bytes` is unaffected -- it still
takes the host's bytes one byte at a time, which is the property the flag exists for.
The flag now does automatically what a JS host previously had to do by calling
`__ronto_seed_random`.

## What it bought (2026-08-26, `.todo/528`, this machine)

10^7 draws of `(random 1000)` at top level, minus the identical loop with the draw
replaced by `1`:

| entropy source | before | after |
| --- | --- | --- |
| SBCL 2.2.9, for scale | 11 ns | -- |
| JVM | 24 ns | **6.1 ns** |
| wasm Preview 1 | 177 ns | **29.4 ns** |
| wasm `--component` | ~270 ns | **29.2 ns** |

On `.todo/517`'s `random` row (10^7 x `(+ s (random 1000000))`, top-level spelling,
wall clock including startup, best of five): JVM 0.46 -> **0.29** s, wasm Preview 1
1.86 -> **0.37** s, `--component` 2.70 -> **0.39** s, against SBCL's 0.16 -- the row's
ratio to SBCL went 2.9x -> 1.8x, and wasm's to the JVM 4.0x -> 1.3x.

## The rules a later change must not break

1. **`rontolisp::%random-byte` keeps calling `random_get` (wasm) / `SecureRandom` (JVM,
   interpreter) once per byte.** The generator above may never reach it. On `--no-wasi`
   without `--host-random` it stays a call-time refusal naming the flag.
2. **The emitted BYTES are deterministic; the drawn NUMBERS are not**
   (`.kb/emitted-output-determinism.md`). Seeding happens at RUN time, from a cell and a
   host call, never from anything the compiler knows.
3. **A program that never draws must emit no seeding and no `random_get` import.** That
   falls out of the emission being at the call site; do not move it to a prologue without
   solving the reachability question first.
4. **`(random 1)` is 0 on every backend, an integer limit yields an integer and a float
   limit a float** -- the `random-deterministic-properties` case in `ci-spec.yaml` is the
   cross-backend pin, and it must stay byte-identical on all four.

Pinned by `ci-spec.yaml`'s `random-deterministic-properties`,
`WasmLispCompilerIntegrationTest`'s `noWasiModuleDrawsRandomNumbersFromItsOwnGenerator` /
`noWasiHostSeedReplacesTheGeneratorsStartState` /
`noWasiModuleSignalsForTheClockAndForRealEntropy` /
`aWasiBuildDrawsFromTheInModuleGeneratorAndKeepsTheEntropyApiOnTheHost`,
`WasmImportCompilerTest`'s `underHostRandomTheEntropyApiReachesTheHostAndAnUnusedImportIsStillShaken`,
and `LispEvaluatorTest` / `JvmLispCompilerTest`'s `random` cases.
