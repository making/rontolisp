# f79. `FetchSpecE2eTest` NATIVE leg prints fewer lines than the spec (triage)

Difficulty: Medium

Found by the parent full suite (2026-10-13, tree `dc585b9cc`, full log
`/tmp/opencode/fulltest-wave3.log` on the orchestrator host): all 21 failures are
the `[3]` (NATIVE) leg — `NATIVE printed a different number of lines than the spec:
Expected size: 261 but was: 191` — while the interpreter/JVM/wasm legs of the same
cases pass. The run shared the machine with three worker suites (load average ~9),
so a under-load native-build flake is plausible, but a size/behavior regression from
waves 1-3 (f57's vector stand-in arrays, f67's chunked namespace defuns) is not ruled
out.

## Plan

1. Re-run `FetchSpecE2eTest` NATIVE leg alone on a quiet machine over the merged tree.
   If green, close with the measurement; if red, bisect against the wave commits and
   fix or file.
2. Full `FetchSpecE2eTest` before closing.
