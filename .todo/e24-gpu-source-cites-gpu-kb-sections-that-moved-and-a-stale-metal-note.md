# e24. `am.ik.gpu` cites `.kb/gpu.md` sections by names that no longer exist, and keeps a stale Metal note

Difficulty: Low

Two documentation drifts in `src/main/java/am/ik/gpu`, found while purging todo citations:

1. `MetalGemm.java` points at `.kb/gpu.md` sections by title. Several of the titles it quotes
   are not headings in `.kb/gpu.md` any more (e.g. "Asynchronous command buffers on Metal" vs the
   heading "Asynchronous command buffers", "Lazy results and the resident tier on Metal",
   "Layer-norm's affine on Metal"). Check every `{@code .kb/gpu.md}, "..."` citation under
   `src/main/java/am/ik/gpu` (and the rest of the source) against the current headings.
2. `GpuDevice.java`, the javadoc of the FUSED tier (`gelu` ...), says "On CUDA; the Metal half
   declines them all". `.kb/gpu.md` has "The fused tier on Metal"; verify what the Metal backend
   implements today and state that instead.

## Plan

- For each citation, point at the heading that now holds the cited fact; where the fact moved
  out of `.kb/gpu.md` or was dropped, cite the right file or state the fact in place.
- Correct the FUSED-tier javadoc against `MetalGemm`'s implementation and `.kb/gpu.md`.
- Comment-only change: no behavior changes, no test changes beyond what a citation check needs.
  If a cheap guard fits (e.g. `PathCitationTest` checking that a quoted `.kb` section title is a
  heading of that file), consider it.
