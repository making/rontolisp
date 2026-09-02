# Metal has no sibling for the device-gated pins that live only in `GpuTest`

Difficulty: Medium

`am/ik/gpu/GpuTest` is `@EnabledIf("aDoubleCapableGpuIsAvailable")` -- a device that has a
`double`, which means CUDA and not merely "a GPU". On every Mac its **57 tests skip in
their entirety**, because MSL has no `double`. `am/ik/gpu/MetalGpuTest` answers many of the
same claims at `#f` and has 38. The difference is not 19 tests: the names do not
correspond, and nobody has ever put the two lists side by side.

That is the shape this item exists for. A pin placed inside a device gate is not "covered
on machines that have that device" -- it is covered on ONE backend, and the other backend's
suite is the only thing that says whether the claim holds there. Today nobody can say which
of `GpuTest`'s claims Metal is pinned on, which are covered under a different name, and
which are genuinely not applicable.

## What to produce

A row per `GpuTest` test, classified:

- **covered** -- `MetalGpuTest` asserts the same claim under another name (e.g.
  `aStubResultAllocatesNoHostArrayUntilTheHostFirstReadsIt` /
  `aLazyResultStaysOnTheDeviceUntilTheHostFirstReadsIt`).
- **not applicable** -- the mechanism does not exist on this backend. `.kb/gpu.md` already
  says which: the generator fill is not a Metal member at all, the axis fold is not offered
  for its SIZE there, and `anOperandTooBigForOneCriticalCopyIsSplitAndStillAgrees` is about
  a bound unified memory does not have. Each of these needs the reason written next to it,
  not merely the verdict.
- **a gap** -- write the Metal sibling.

## The ones already believed to be gaps (2026-09-03, from the name diff)

Confirm each before writing anything; the classification is the work, not the list.

- The five "with a device present" decline enumerations. `MetalGpuTest` has ONE
  (`everyDeclineConditionStillDeclinesWithADevicePresent`, the product's) plus the GEMV's.
  `GpuTest` has five more: element-wise, strided, fused, resident-tier and batched. The
  device-free half is `GpuDeclineTest` and runs everywhere, so what is missing is
  specifically "declines rather than throws, WITH a Metal device bound".
- `everyStridedOperandIncludingTheResultIsReadFromItsOwnOffset`, `aStridedGatherIsThePermutedCopy`
  and `aBroadcastBinaryOpMatchesTheScalarOdometerWalk` -- the strided tier's offset and
  oracle pins. Metal has `theStridedTierIsBitIdenticalToTheScalarOracle` and it is not
  the same three claims.
- `theIndexTierIsOfferedOnlyOverAResidentOperandAndCopiesTheCpuKernelsBits` and
  `theSumOfSquaresFoldsInBlocksAndIsReproducibleWithinAFewUlpsOfTheSequentialSum`. Both
  tiers became reachable on Metal when todo-495 flipped `lazyResultsPay` there; the
  library-level pins for them are still CUDA-only. (`.kb/gpu.md`'s "What is deliberately
  NOT here" still says "No lazy results on METAL for the interceptors, and so no index
  tier or clip norm there" -- that sentence is now false and is part of this item.)
- `anAxisFoldIsTheDefunsOwnSequentialFold`. Metal declines the fold for its SIZE, but it
  is still a member over a RESIDENT operand, and nothing pins its bits there -- at the
  library level or through the interceptor.
- `aDivisionByAPowerOfTwoIsTheExactReciprocalsMultiplyAtBothWidths`. The rewrite's own
  javadoc argues it is exact on a backend that computes in `float`; that argument is
  unasserted on the backend it is about.
- `theSameProductRepeatedIsTheSameAnswer` and `aDeclinedProductCostsTheDeviceNothing`.
- Three of the five `aRunOf...FreesEveryBufferItAllocates` leak runs: Metal has the
  general one and the GEMV one, and none for the element-wise, strided or generator paths.

## Why now

`.todo/655` is the production-side half of the same question (an accept rule against the
shapes that actually flow through it). Its "tests" half was swept on 2026-09-03 and the
result is in `.kb/gpu.md`, "Tests": four tests were running nothing on Metal and one suite
was failing outright there. That sweep asked "does the shape reach the mechanism"; this
item asks the prior question, "is there a test on this backend at all".
