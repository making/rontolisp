# 481. `GpuTest`'s free-memory drift bound is flaky under the parallel suite on unified memory

Difficulty: Low

Seen 2026-08-22 in a full `./mvnw test` on the GB10 (JUnit parallelism 16):
`aRunOfGeneratorFillsFreesEveryBufferItAllocates` and
`aRunOfSuccessfulProductsFreesEveryBufferItAllocates` failed with a drift of 1.78 / 1.85 GB
against `DRIFT_BOUND` (1.5 GB); both pass alone (`-Dtest=GpuTest`, 38/38) and nothing in
`am.ik.gpu` had changed. On a unified-memory device `cuMemGetInfo`'s free figure is the
HOST's free memory too, so sixteen sibling test JVMs allocating during the 1000-call loop
move it by more than the bound; `@ResourceLock(DEVICE_MEMORY)` only serializes within one
JVM.

Fix candidates: measure the drift over the test's OWN allocations (the pool's resident
bytes, or `cuMemGetInfo` sampled around each call and the minimum drift taken), or run the
leak tests in the same JVM/fork only, or widen the bound only when the device reports
unified memory. Pin with a run of the full suite, not the class alone.
