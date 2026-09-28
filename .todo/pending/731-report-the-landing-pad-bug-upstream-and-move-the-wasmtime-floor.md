# 731. Report the landing-pad exceptional-edge bug upstream, and move the wasmtime floor

Difficulty: Medium (the report is Low; the toolchain bump re-verifies every wasm leg)

Filed 2026-09-07 out of `.todo/722`, which found the trap and worked around it. The
workaround is correct and stays either way -- this item is the two things `722` could not
do from inside the repo.

**The bug is not ours.** `cranelift-frontend`'s SSA builder leaves a catch block holding a
block parameter when a `try_table` body contains a loop with an inner two-predecessor merge
and a call after it; each `try_call` then passes the local as an exceptional-edge ARGUMENT
evaluated before the call, living in a spill slot no stack map lists, so a copying
collection during that call hands the landing pad a pre-move reference. Mechanism, the
30-line rontolisp-free wat reproducer, and the disassembly are `.kb/wasm-landing-pad-refresh.md`.
Measured unfixed in **47.0.3 through 49.0.0** (49.0.0 re-measured 2026-09-24 with the `.kb`
wat: traps under the copying collector, green under `-C collector=drc`).

## Do

1. **File it against `bytecodealliance/wasmtime`.** The report text and the wat are already
   in the `.kb` card; the title is the invariant it breaks -- an exceptional-edge block
   argument is not a stack-mapped value. External submission, so it needs the user's word
   before it is sent. Record the issue number in the `.kb` card when it exists.
2. ~~Move the toolchain off 47.0.3~~ and ~~re-verify the wasm legs~~: done 2026-09-24 --
   every pin (CI, the test image, `rontolisp-native`, the local CLI) is on **49.0.0**, with
   `./mvnw test`, the native `CiSpecE2eTest` (both `--simd` legs) and `ExamplesE2eTest` green
   on it. The documented FLOOR (`wasmtime 47+` in README/doc) did not move: nothing needs 49.
3. **Only if the upstream fix has landed by then**, ask whether `WasmLandingPad` can be
   narrowed -- and answer with the pin, not with the version: the ci-spec case
   `landing-pads-read-fresh-references-after-a-collection` and the integration test must
   still pass with the refresh REMOVED before any narrowing is real. Until that day the
   workaround costs one `br`-discarded push per protected region and nothing at runtime,
   so there is no pressure to remove it.

## Do not

- Do not bump to a prerelease. 49.0.0-rc.1 was measured for the bug, not adopted.
- Do not take the bump as evidence about the trap. The pre-grow scales with emitted code
  bytes, so a version change moves the collection the same way a source edit does: green
  after an upgrade is a coin, which is the whole content of `722`.
