# d06. ci-spec E2E legs run close to the 300 s per-command timeout

Difficulty: Medium

`CiSpecE2eTest` runs the whole concatenated `ci-spec.yaml` program as ONE command per leg, under
a 300 s per-command timeout (`execCapture`). Measured 2026-10-05 with the native binary at
`11939e59b`, same machine (wall clock, `wasmtime run -W gc=y -W exceptions=y`):

| Leg | Load avg | Wall |
|---|---|---|
| P1 scalar | ~10 | 158 s |
| component `--simd` (compiler at `bf4547a9b`) | ~10 | 173 s |
| component `--simd` (compiler at `11939e59b`) | ~7 | 174 s |
| component scalar | ~120-167 | 426 s |

So no regression between the two compilers, but on an idle machine a leg already uses ~55% of
its budget, and under load (other maven runs on the host) the native run at `11939e59b` failed
once: `test-simd.component.wasm ... timed out after 300 seconds`, which also drops that leg's
~600 per-case children (4759 tests instead of 5388). The re-run under low load passed.

Find where the time goes before touching the budget: which cases dominate a leg's run time
(per-case timing of the concatenated program), and whether those cases are pinning behaviour
that needs that much work or are accidentally heavy (a loop sized for a benchmark). Then either
shrink them, or split/raise the timeout with the measurement as the reason. Record the numbers
in `.kb/` (ci-spec / running-backends).
