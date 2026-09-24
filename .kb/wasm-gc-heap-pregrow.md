# `_start` pre-grows the engine's GC heap with one dropped allocation

**Invariant**: the first thing the emitted `_start` body does (Preview 1 and the
component core; NOT `--no-gc`) is allocate and immediately drop a `TYPE_STR_BYTES` byte
array. Size follows the program (`WasmLispCompiler.gcHeapPregrowBytes`):
`GC_HEAP_PREGROW_CODE_FACTOR` (16) x emitted user-function bytes, clamped between
`GC_HEAP_PREGROW_BYTES` (16 MiB floor) and `GC_HEAP_PREGROW_MAX_BYTES` (64 MiB) and then
**quantized UP to a power of two**, so the only sizes a program can ask for are 16, 32 and
64 MiB — except **serve** mode, always `GC_HEAP_PREGROW_SERVE_BYTES` (1 MiB). Emitted at
Pass 2b in `WasmLispCompiler.compile`, pinned by `WasmGcHeapPregrowTest`.

The quantization is not tidiness: an unquantized size is redrawn from the emitted byte
count on every commit, and wasmtime 47.3's copying collector breaks for narrow BANDS of
GC-heap size — see "The size is drawn from a lottery" below. Three values the corpus run
covers beats a fresh draw per build.

**The size is a performance knob** (again, since 2026-09-07): from 2026-08-16 to that date
it was held to be a correctness matter, because a `cast failure` that a larger heap made
disappear was read as the collector losing a reference. The defect was Cranelift's
frontend handing a landing pad a pre-collection reference, and the heap size only decided
whether a collection fell inside the throwing call; the wasm backend now sidesteps it
structurally (`.kb/wasm-landing-pad-refresh.md`), so the floor, ceiling and factor may move
on measurement alone.

- Why: wasmtime's copying collector grows only when a SINGLE allocation cannot fit in the
  space a collection frees (`collect_and_maybe_grow_gc_heap`,
  `crates/wasmtime/src/runtime/store/gc.rs`). The heap never shrinks, so one large
  transient allocation permanently buys headroom; the array is garbage before user code
  runs, so RSS is unchanged.
- Not one constant: the live set follows what the program LOADS. 16 MiB covers
  cl-postgres alone, not `rove` on top; on cl-postgres + rove (3.3 MB of emitted defuns)
  26.5 MiB still collects and 32 MiB does not, so factor 16 gives a ~2x margin.

## The size is drawn from a lottery, and on 2026-09-11 it lost
Measured 2026-09-11/12 (linux-x86-64, 64 cores, wasmtime 47.0.3 `5554cc1a6`). The
`ci-spec` corpus emitted 4,078,697 bytes of user function bodies that day, so the
unquantized size was 16 x that = **65,259,152** bytes. Compiled `--simd` and run under
`wasmtime --wasm gc --wasm exceptions=y`, the corpus died at output line 921 (inside the
`runtime-package-api` case, at the `find-all-symbols` form) with wasmtime's own

```
BUG: there should always be enough room in the active semi-space for objects that
survived collection, since the active space is the same size as the idle space
crates/wasmtime/src/runtime/vm/gc/enabled/copying.rs:540
```

It is **the heap SIZE, not the program**, that decides. Same corpus, same compiler, only
the pre-grow constant varied (a temporary system-property override on
`gcHeapPregrowBytes`):

| pre-grow bytes | verdict |
| --- | --- |
| 0 / 16 (no pre-grow at all) | green |
| 16 Mi / 32 Mi / 64 Mi (the quantized values) | green |
| 65,193,616 / 65,228,384 (−64 KiB / −30 KiB) | green |
| 65,259,152 (**what the formula produced**) | semi-space BUG |
| 65,300,000 | `panicked ... invalid VMGcKind: 0b0` — a ZEROED object header, i.e. the collector walked into never-written memory |
| 65,324,688 / 65,400,000 (+64 KiB / +140 KiB) | green |

The band is a few tens of KB wide and it MOVES WITH THE PROGRAM: 65,300,000 panics on the
whole corpus and is green on the corpus minus one case. Conversely 65,259,152 kills the
corpus minus that case too — so the case contents are irrelevant, and a **prefix bisect of
`ci-spec.yaml` cases is a trap**: it appears to name a culprit case (the 508th,
`gguf-cross-backend`) when all that case did was change the emitted byte count, hence the
size. Do not bisect the corpus for this symptom; sweep the size.

Holding the module fixed and sweeping the engine's own `-O gc-heap-initial-size` instead
gives a razor-sharp threshold — 65,536 / 98,304 / 131,072 all die, 139,264 and everything
above is green. **8 KiB of engine heap is the whole margin**, on a 62 MiB heap. Under
`-C collector=drc` (non-moving) the failing module is green, so it is the copying
collector specifically, and the message says wasmtime does not think this state is
reachable: it is an upstream bug that our heap sizing reaches, the way the 2026-08-16
`cast failure` turned out to belong to Cranelift.

What was NOT established: which object the collector fails to place, and why a few KB of
semi-space decides it. The black-box evidence (the survivor set overflowing by < 8 KiB of
a 62 MiB space) says the ~62 MiB pre-grow array itself is among the survivors of the
collection that fails, though a small program with the same pre-grow demonstrably
collects it (300k retained conses under a 64 MiB pre-grow never grows the heap, peak RSS
110 MB). A minimal `.wat` for an upstream report is still missing; `wasm-tools shrink`
with "trips the semi-space BUG" as the predicate is the tool, at ~30 s per green
iteration.

**Still there on wasmtime 49.0.0** (2026-09-24). The failing module is reproducible
without any override: `a34942ace~` (the parent of the quantization commit) compiles its own
`ci-spec` corpus `--simd` to an 8,986,117-byte module whose pre-grow is 65,259,152; the
`i32.const` is the only `41 <4-byte sleb> fb 07` in the start function (offset 19,161), so
other sizes are a 4-byte patch, not a recompile. Run from a directory holding the
`CorpusFixtures` tree, `--dir . --dir /tmp`:

| pre-grow bytes | 47.0.3 | 49.0.0 |
| --- | --- | --- |
| 65,193,616 / 65,228,384 / 65,324,688 | green | green |
| 65,259,152 | `invalid VMGcKind: 0b0` panic, line 921 | same |
| 65,300,000 | semi-space BUG trap, line 921 | same (`copying.rs:534`) |

`-C collector=drc` is green at both failing sizes on 49. The two symptoms sit at the
opposite sizes from the 2026-09-11 table above; both are the same defect. Today's corpus, by
contrast, has NO band in 64.0-66.5 MB at 8 KiB steps on 47.0.3 (306 sizes, all green), so
a current module is no reproducer -- use the old commit.

Two things follow for anyone changing the size:

- **The corpus does not need the pre-grow at all.** Full `ci-spec` corpus, `--simd`, on
  this box: 21.1 s with no pre-grow, 20.8 s at 64 MiB — the run is dominated by
  wasmtime's cold compile of a 9 MB module, not by collection. The knob's justification
  is still only the cl-postgres + rove measurement above; nothing in the CI corpus
  defends it.
- **Nothing in `./mvnw test` covers this.** `CiSpecE2eTest` runs ZERO tests without
  `-Drontolisp.binary`, so the leg lives only in the native-image CI job, which is where
  this was found (run 34643541970, `ubuntu-24.04-arm`, 3814 tests, 1 failure — the other
  native jobs cancelled as fail-fast peers). The guard against a repeat is
  `WasmGcHeapPregrowTest.pregrowSizeIsAlwaysAPowerOfTwo`: the size can no longer move
  because somebody added a corpus case, only because somebody edited the rule.

## Sibling knob: the LINEAR memory's declared minimum
`WasmLispCompiler.memoryMinPages`. Rule: **static data plus a heap at least as large as
it** (`HEAP_HEADROOM_MIN_PAGES` = 3 floor). The old fixed ~192 KB exhausted mid-load on
cl-unicode, trapping `out of bounds memory access` with an unnamed backtrace. Both
emission sites take it — the Preview 1 / `--no-wasi` memory section and the component's
`mem` import minimum (which drives `WasmComponentBuilder.memModuleFor`). The bump sites
are unguarded, so it must be right up front. Pinned by `WasmLinearMemoryHeadroomTest`.

## The `cast failure` that a bigger heap hid was not the collector's
On **wasmtime 47.0.3** a boxed local's cell read back as another cell during a NON-LOCAL
EXIT and the next unbox trapped uncatchably (`wasm trap: cast failure`); green under
`-C collector=drc` or a larger `-O gc-heap-initial-size`, trapping under the default
copying collector. That pair of runs says "a moved object was read through a stale
reference", not whose fault it is: the reference was a wasm local that Cranelift's frontend
passed into the landing pad as an exceptional-edge argument evaluated before the throwing
call, outside every stack map (`.kb/wasm-landing-pad-refresh.md`, with the 30-line wat).
`drc` never moves, so it cannot show it; a bigger heap only moves the collection out of
that call. The backend's landing pads no longer read locals that way, and the size here
went back to being about pause time.

## Why serve is different
`_start` runs **once per INSTANCE**, and a served component is instantiated many times
(`wasmtime serve --max-instance-reuse-count`, 128 default; Spin inherits it, wasmCloud
`wash dev` uses 1 — `.kb/tcp-sockets.md`). Growth costs ~**1.5 ms per MiB** on wasmtime
47, so in serve mode the pre-grow is request latency. 1 MiB is the compromise: optimal at
the reuse count every real host uses (+30% native / +27% clack over 16 MiB), ~2% mean
throughput on a never-retired instance; dropping it entirely is worse except at reuse=1.
wasmCloud pools the heap mapping, so the reuse=1 column bounds the SHAPE of the cost, not
its size — measure the host.
