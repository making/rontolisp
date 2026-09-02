# The `--gpu` shape-offer differential cannot run on a GPU-less CI without a stand-in `GpuDevice`

Difficulty: Medium

`codegen/jvm/GpuOfferDifferentialTest` (.todo/654) pins that the interpreter's
`eval/LinalgGpu` and the compiled `codegen/jvm/JvmGpuTemplate` agree on which shapes
`--gpu` accepts. It has two halves and only ONE of them runs where CI runs:

- the member SET differential is device-free and runs everywhere;
- the SHAPE differential -- accept versus decline over a table of boundary shapes, which
  is the half that would have caught `suffixLength` / `softmaxMaskLength` drifting apart
  -- is behind `@EnabledIf("aDeviceIsAvailable")`.

Every machine this project's CI has is GPU-less, so the half that pins the rule is the
half that never runs there. It is a pin that only a developer with a device can trip.

## Why it is gated, which is structural rather than a choice

On BOTH paths a shape decline and a no-device decline are the same answer, `null`:

- `JvmGpuTemplate` fuses the availability test into the shape test's own expression --
  `if (d == null || ... || maskLen < 0 || !Gpu.available()) return null;`
- `LinalgGpu` returns `null` from its shape tests and then calls a kernel that returns
  `null` with no device.

So with no device every shape declines on both paths and every assertion agrees
vacuously. Nothing outside the two files can tell the two declines apart.

Nor can a test stand a device up: `Gpu.available()` reads `Probe.DEVICE`, a static final
in a holder class, and `GpuDevice` is `sealed permits CudaGemm, MetalGemm`.

## The shape of the fix, and what it costs

A stand-in `GpuDevice` that reports itself usable and answers `true` from every kernel
without touching memory would make the shape half's accept/decline exactly the SHAPE
decision on both paths, device-free. It would need:

- a third permitted implementation in `am.ik.gpu` (which imports nothing, and a stand-in
  need not either), plus a way to install it that a normal run can never take;
- an entry in `JvmGpuRuntimeBuilder`'s `GPU_CLASSES`, because every class of that package
  travels inside every compiled `--gpu` program -- so the blob grows and
  `JvmGpuRuntimeBuilder.embeddedGpuClasses()`'s pin has to follow it.

The risk to weigh against that: a device that says yes and writes nothing produces zeros
silently if it is ever switched on outside a test. Whatever installs it has to be
impossible to reach from the CLI, the embedders and the emitted programs -- and the
`--gpu` decline path is exactly the place where "quietly wrong" has been expensive before
(`.kb/gpu.md`, "Declining on error, and the sticky rule").

An alternative worth pricing first: hoist each bridge member's `Gpu.available()` test out
of the shape expression and make the shape decision answerable on its own. That avoids a
fake device entirely, but it means a parallel predicate surface on `JvmGpuTemplate`, whose
bytes travel in every compiled program, so it is a size decision as well as a design one.

## Acceptance

Either the shape half runs on a GPU-less machine and fails there when one path's predicate
is changed alone, or the measurement says the cost is not worth it and `.kb/gpu.md`'s
"The offer is decided twice" section records the numbers that said so.
