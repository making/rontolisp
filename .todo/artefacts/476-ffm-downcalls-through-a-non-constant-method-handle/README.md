# 476: what an FFM downcall through a non-constant `MethodHandle` actually costs

Taken 2026-09-06 on **GB10** (aarch64 Cortex-X925, 20 cores, 121 GB, CUDA, GraalVM 25.0.4,
load average 0.00 at the start), base commit `d18b0d6d5` -- `.todo/723` and `.todo/725`
both already in, which is the denominator the umbrella's lane plan (`.todo/670`, closed 2026-09-10) said
had to move first.

**The verdict: the change was refused.** `am.ik.gpu.CudaDriver`'s handles stay `private
final` INSTANCE fields. What the item saw in a profile is 0.7-1.2 ns per downcall in
isolation, zero on this box's default JIT, and zero inside a native image; the 8%-of-a-step
reading was an artefact of which SAMPLER saw the step. Both halves are below.

## 1. The per-call floor, in isolation -- `HandleConstancy.java`

Two million calls of `cuDriverGetVersion(int*)` -- the cheapest thing `libcuda.so.1`
exports, no context and no device, so what is timed is the invoker and the downcall stub
and nothing the driver does. Five arms, steady-state (last three rounds agree to 0.05 ns):

| how the handle is held | GraalVM JIT | C2 (`-XX:-UseJVMCICompiler`) | native image |
| --- | --- | --- | --- |
| `static final MethodHandle` | 8.9-9.1 ns | **9.44 ns** | 2082 ns |
| `final` instance field, receiver a `static final` (**what `CudaDriver` does**) | **8.3-8.9 ns** | 10.13 ns | 2083 ns |
| `final` instance field, receiver a MUTABLE static (the control) | 10.0 ns | 10.2-11.2 ns | 2083 ns |
| `static final`, bound `critical(true)` | 5.3-6.5 ns | 5.43 ns | 2118 ns |
| no downcall at all (the same loop, the same interface) | 3.6 ns | 2.3 ns | 32.8 ns |

Three readings, and only the first is the item's:

1. **On GraalVM the item's premise is false.** Graal constant-folds a `final` instance
   field read from a constant receiver, so a handle reached through a `static final` is
   ALREADY a JIT constant -- 8.3-8.9 ns against `static final`'s own 8.9-9.1. The row that
   does cost is the MUTABLE receiver (10.0 ns), and `CudaDriver` is not reached that way:
   the chain is `Gpu.Probe.DEVICE` (a `static final`) -> `CudaGemm.driver` (final) ->
   `CudaDriver.cuMemcpyHtoD` (final), and Graal folds all three levels.
2. **On C2 the change is worth 0.7 ns a call** (10.13 -> 9.44, 7%). Real, and far too small
   to matter: even at 10^5 driver calls a step -- two orders of magnitude more than the
   step's ~40 copies (`.kb/gpu.md`, the index tier) and its launches -- that is 0.07 ms of
   a **690 ms** step.
3. **Inside a native image every arm is the same 2.08 us**, because no arm can be a
   constant: the handle is created at RUN time from a library lookup, and AOT code was
   compiled before the value existed. `static final` cannot fix an AOT downcall, and
   `critical(true)` -- the fastest arm on both JITs -- is the SLOWEST here. That is
   `.todo/727`, filed off this table.

## 2. The program, before and after nothing -- `gpt-book-shapes-fast.lisp`

`gpt-book-shapes-fast.lisp` (now in the `llm-from-scratch` project's `bench/`; it was in `.todo/artefacts/123-gpu-acceleration/`), compiled
`-o Book.class --class-name Book --gpu --simd` and run on the JVM class output, which is
the shape `.todo/476` profiled.

**The step is 0.69 s** (three pairs of `STEPS=3` / `STEPS=13`: 0.673, 0.707, 0.696).

`STEPS=63` under `-XX:StartFlightRecording=settings=profile`, 36.8 s (Graal) and 37.6 s (C2):

| | Graal | C2 |
| --- | --- | --- |
| `jdk.ExecutionSample` (Java time, 10 ms) | 217 | 279 |
| `jdk.NativeMethodSample` (native time, 20 ms) | 1471 | 1463 |
| `Invokers.checkCustomized` | **1** | **0** |

The one Graal sample is at t+5 s, inside warm-up. Against the item's **79 of ~1000**, this
is gone.

Where the step is instead:

```
native (both JITs)          Java (Graal)                    Java (C2)
1127  ctxSynchronize        22  memcpyHtoD                  43  DeviceResidency.written
 197  memAllocAsync         10  DeviceResidency.recentClaim 31  CudaGemm.materialize
 114  launchKernel           7  Gpu.written                 29  memcpyHtoD
   7  memcpyDtoHPinned       6  DeviceResidency.rememberClean 17 Long.valueOf
```

## 3. Why the 2026-08-22 profile read 8%, and what to do with the next one

**`jdk.ExecutionSample` samples only threads in the JAVA state; a thread inside a downcall
is sampled by `jdk.NativeMethodSample` instead.** So "79 of ~1000 execution samples" was
8% of the step's JAVA half, not 8% of the step -- and on this workload the Java half is
2.2 s of 31.6 s of sampled thread time, **7%**. 8% of 7% is half a percent, which is the
same order as the isolated 0.7 ns/call, and the two readings agree. The item's own Status
line said it: *seen in a profile, not measured in isolation.*

**Under `--gpu`, quote the two sample sets together or neither.** A percentage off
`ExecutionSample` alone silently divides by the Java half of a step that is mostly native
-- here it inflates by 14x.

## Files

- `HandleConstancy.java` -- the five-arm probe. `java HandleConstancy.java`,
  `java -XX:-UseJVMCICompiler HandleConstancy.java`, and for the native arm
  `javac -d classes HandleConstancy.java && native-image -cp classes:meta
  --enable-native-access=ALL-UNNAMED -o handle-constancy HandleConstancy`.
- `meta/META-INF/native-image/probe/reachability-metadata.json` -- the two downcall shapes
  the native arm has to register, plain and critical. Without them the image builds and the
  bind throws.
