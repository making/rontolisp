# `am.ik.gpu`'s downcalls go through the generic `MethodHandle` invoker, and it shows

Filed 2026-08-22 off the third `--gpu --simd` profile of
`examples/llm-from-scratch/chapter03/train-gpt-soseki.lisp` at the notebook's shapes, taken
while todo-474 (device residency) landed (`.kb/gpu.md`, "Device residency").
Difficulty: Low-Medium. Status: open -- seen in a profile, not measured in isolation.

## What the profile shows

Over a 200-step run on the JVM class output (~1000 JFR execution samples),
`java.lang.invoke.Invokers.checkCustomized(MethodHandle)` is the fourth frame: 79 samples,
beside `memcpyHtoD` 110, `laEwFS` + `FloatVector.intoArray` 196 and `laAdamStep` 67 --
about 8% of the step, and it is the same share with residency on or off.

`checkCustomized` is the entry of `MethodHandle.invokeExact` on a handle the JIT cannot
treat as a constant. `am.ik.gpu.CudaDriver` holds its 30-odd downcall handles as `private
final` INSTANCE fields of the one driver object, so every `this.cuMemcpyHtoD.invokeExact(...)`
is a call through a non-constant handle: the generic invoker, the `checkCustomized` guard,
and a `LambdaForm` the JIT compiles on its own rather than inlining the native stub at the
call site. A `static final` handle (or a `@Stable`-equivalent the JDK does not offer) is a
constant to C2, and `invokeExact` on a constant handle inlines down to the downcall stub.
`--blas`'s `LinalgBlasKernels` holds its CBLAS handles the same way and would gain the same.

## What to do

Make the handles constants: a static holder initialized once from the `SymbolLookup` (the
driver is a process singleton in practice -- "Retained once, for the process" -- and the
probe runs once), or the same driver object behind a `static final` field with the
handles read through it, whichever keeps `CudaDriver`'s never-throwing, optional-symbol
construction. Measure the per-call floor before and after (`AllocatorCost.java`'s whole-
product row and `TinySpike.java`), then the program; quote ratios. A downcall that the JIT
inlines also changes where the critical window's safepoint rules bite, so re-read
"Linker.Option.critical takes heap segments here too" in `.kb/gpu.md` before moving a
critical handle.

## Acceptance

`checkCustomized` gone from the profile, the per-call floor measurably lower, every
`GpuTest` / `GpuDeclineTest` assertion unchanged, and the JVM blob still carrying the
whole library (`JvmLinalgGpuAccelCompilerTest` pins the class list).
