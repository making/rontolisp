# 528. A `(random n)` draw is a WASI host call on wasm and a contended atomic on the JVM

Difficulty: Medium (the wasm half reuses a generator the compiler already emits;
the JVM half is a one-line entropy source swap. The work is deciding what
`*random-state*` means once the draw is module-local, and proving the
cryptographic surface did not move with it)

Child of `.todo/517`, filed from its "measured, understood, not yet filed"
section once `.todo/518`/`519`/`520`/`521`/`522`/`412` had all landed and the
four rows were re-taken on a fixed baseline (2026-08-26). This is the second of
the two rows still outside the parent's 2x target, at **2.9x**.

`.todo/517` filed the wasm half only ("wasm `random` is ~4x the JVM's"). It is
4.2x -- but the row's acceptance is measured against the BEST compiled backend,
and after `.todo/412` that is the JVM at 2.9x SBCL. Fixing wasm alone cannot
close the row, so both halves are here.

## The defect

**wasm: every single draw crosses the host boundary.** `WasmRandomCompiler`
emits `random_get(RANDOM_SCRATCH_ADDR, 8)` per `(random n)` -- eight fresh bytes
from WASI, stored to linear memory and loaded back. A `perf` profile of 10^7
draws under `wasmtime run -W gc --profile=perfmap` puts about **12% of cycles in
wasm code** and the rest on the host side of that call:

```
10.44%  [JIT]      wasm[0]::function[45]        <-- the actual loop
 8.56%  wasmtime   HostResult::maybe_catch_unwind
 8.23%  wasmtime   Vec::spec_from_iter_nested   <-- a Vec allocated per draw
 5.25%  wasmtime   StdRng::try_next_u32
 5.23%  wasmtime   chacha20::backends::avx2::rng_inner
 4.67%  wasmtime   BuildHasher::hash_one        \
 4.44%  wasmtime   Instance::get_export          |  the "memory" export looked
 4.43%  wasmtime   sip::Hasher::write            |  up BY NAME, per draw
 4.38%  wasmtime   BuildHasher::hash_one         |
 4.00%  wasmtime   StringPool::get_atom         /
 4.27%  wasmtime   wasi_snapshot_preview1::random_get
 2.28%  libc       malloc
```

A name hash, a heap allocation, an unwind guard and a ChaCha20 CSPRNG, per draw,
to produce a number CL only promises to be pseudo-random. The `--component`
build is worse: `wasi:random/get-random-u64` through the canonical ABI.

**The compiler already emits the right generator and only uses it when it must.**
`--no-wasi` has no host to call, so `WasmIoRuntimeBuilder` fills the `random_get`
slot with an in-module SplitMix64 over a memory cell, seedable through the
exported `__ronto_seed_random`. Its own comment states the rule this item wants
applied everywhere: *"CL's `random` is a pseudo-random draw from
`*random-state*`, so a fixed start is inside its contract"* -- and it correctly
refuses to let that generator answer `rontolisp::%random-byte`, which does
promise cryptographic entropy.

**JVM: `Math.random()`.** `JvmRandomCompiler` emits
`java/lang/Math.random()`, which is a single process-wide `java.util.Random`
whose 48-bit seed advances by a `compareAndSet` on a shared `AtomicLong` -- a
CAS and a memory fence for a value nothing shares.

## What it costs (2026-08-26, this machine, fixed baseline)

10^7 draws of `(random 1000)` at top level, minus the identical loop with the
draw replaced by `1`:

| entropy source | ns per draw |
| --- | --- |
| SBCL 2.2.9 | **7.5** |
| JVM, `Math.random()` (shared `AtomicLong` CAS) | 24 |
| wasm preview 1, WASI `random_get` per draw | **177** |
| wasm `--no-wasi`, the in-module SplitMix64 the compiler ALREADY emits | **62** |
| wasm `--component`, `wasi:random` through the canonical ABI | ~270 |

The `--no-wasi` row is the finding: switching to a generator that is already
written, already tested and already deemed correct for CL's `random` is a
measured **2.9x** on the wasm draw, and 62 ns still includes the round trip
through `random_get`'s `(buf, len)` signature -- the store to linear memory and
the reload -- which inlining the generator at the call site removes.

On `.todo/517`'s four-row table this is the `random` row (JVM 0.46 s top level
against SBCL's 0.16 s) and half of the `aref` row's baseline.

## What to build

**wasm.** Make `random` draw from the module-local SplitMix64 in EVERY mode, not
only `--no-wasi`; seed it ONCE at `_initialize` from a single `random_get` (or
`wasi:random`) call, so a run is still unpredictable across runs. Keep
`rontolisp::%random-byte` on the host draw exactly as it is -- it is the one
caller that needs real entropy, `WasmExprCompiler` already knows that, and this
change must not let a fixed-seed generator reach it. `--no-wasi` keeps today's
behaviour and its `__ronto_seed_random` export unchanged; `--host-random` still
means the host's bytes.

**JVM.** Replace `Math.random()` with a generator that is not shared -- a
per-thread state, or a `java.util.random.RandomGenerator` instance held in a
static field, or the same SplitMix64 inline. `ThreadLocalRandom` is the obvious
candidate and should be measured against an inline generator before being
chosen. The interpreter shares the emitter's contract and moves with it
(`Environment.createGlobal`), and `.kb/emitted-output-determinism.md` must stay
true: the emitted BYTES are what is deterministic, not the drawn numbers.

Both halves have to answer the same design question: rontolisp has no
random-state objects at all (`LispNames.MAKE_RANDOM_STATE` is lowered to a
nil-returning no-op and `random`'s optional state argument is normalized away).
Decide deliberately whether a module-local seed makes `*random-state*` worth
having, or whether the no-op stays -- and write the decision into a `.kb` file,
because "the draw is seeded once per process" is the kind of invariant a later
change silently breaks.

## Acceptance

- `.todo/517`'s `random` row, TOP-LEVEL spelling, within 2x of SBCL's 0.16 s on
  at least one compiled backend (i.e. <= 0.32 s where the JVM is 0.46 s today).
- The wasm draw is within 2x of the JVM's on the same row -- the 4.2x
  `.todo/517` recorded is gone on both wasm backends, preview 1 and
  `--component`.
- `rontolisp:random-bytes` and `rontolisp::%random-byte` still come from host
  entropy on every WASI build, pinned by a test that fails if the module-local
  generator can reach them; `--no-wasi` still refuses them at call time with
  today's message.
- Two runs of the same wasm module produce different draws, and the
  `random-deterministic-properties` ci-spec case is byte-identical on all four
  backends.
- `ci-spec.yaml` and `ExamplesE2eTest` byte-identical on all four backends.
