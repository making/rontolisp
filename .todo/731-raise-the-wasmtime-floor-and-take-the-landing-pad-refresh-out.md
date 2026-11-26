# 731. Raise the wasmtime floor to the patched releases, and take the landing-pad refresh out

Difficulty: High (the wasm-GC backend's every catching region; the floor moves in README, doc/en, doc/ja)

Filed 2026-09-07 out of `.todo/722` as "report the landing-pad bug upstream, and move the
wasmtime floor". The report is moot: upstream found and fixed the same defect on its own.
Rewritten 2026-11-22 (l24) around what the measurement showed.

## What changed

- GHSA-hw8m-q44c-ggrf ("Rooting for GC values live across `try_call` may be missing, causing
  GC heap corruption", wasmtime #14478), patched in 48.0.4, 49.0.2 and every release since; 47.x
  has no patched release. Measured: `.kb/wasm-landing-pad-refresh.md`'s wat traps on 49.0.0 and
  49.0.1 and runs on 49.0.2, 49.0.5, 49.0.7 and 51.0.0; the semi-space band of
  `.kb/wasm-gc-heap-pregrow.md` was the same defect and ends at the same release.
- On the pin (51.0.0) the discipline is dead weight: a scratch compiler with
  `WasmLandingPad.keepLocalsAlive` pushing nothing printed the four `"fresh"` of the ci-spec case
  `landing-pads-read-fresh-references-after-a-collection` on 51.0.0, Preview 1 and component,
  and `"stale"` then a trap on 49.0.0 (`.kb/wasm-landing-pad-refresh.md`, "Upstream").
- The hosts in use are past the fix: Spin v4.3.0 embeds wasmtime 50.0.1, wasmCloud v2.12.3
  48.0.10. What still takes an unpatched host is our documented floor: `wasmtime 47+` in
  `README.md`, `doc/en/compiling/wasm.md` and the guides, `46+` in others,
  `HostWasmtime.MINIMUM_MAJOR` 46, `ci.yaml`'s `-ge 46`.

## Do

1. Decide the floor and say it everywhere it is said (README, `doc/en/**` and `doc/ja/**` in the
   same commit, `.kb/running-backends.md`, the Dockerfile comment, `HostWasmtime`, `ci.yaml`'s
   check): the patched releases are 48.0.4+ and 49.0.2+, so "50+" is the plain statement.
   Re-read `.kb/wasm-gc-final-types.md` and `.kb/wasi-component.md` for what else the old floor
   rested on.
2. Take out `WasmLandingPad`'s push and refresh, `WasmCarriedLocals`, the region boxing
   (`WasmLandingPad.regionAssignedVars`), the inliner's rule for a body holding a `try_table`
   (`WasmInlinerTest.aBodyHoldingATryTableStaysOutOfLine`) and the `$rt` exception. Keep the
   ci-spec case and `landingPadsReadFreshReferencesAfterACollectionDuringTheUnwind` as the pins:
   the case fails on 49.0.0 without the refresh (measured above), which is what makes it a pin;
   show the same of the integration test before relying on it.
3. Measure what it gave back: module bytes (the size report, the ci-spec corpus), `wasmtime
   compile` time of the hello-ningle Worker,
   and verify on all four backends, `--native` included.

## Do not

- Do not take the refresh out before the floor moves: on an unpatched host a collection inside
  a protected call hands the pad a moved reference, a trap or a wrong answer that comes and goes
  with the program's size (`.todo/722`'s whole content).
