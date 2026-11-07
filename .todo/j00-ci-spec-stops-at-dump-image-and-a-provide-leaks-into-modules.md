# j00. ci-spec: the concatenated program stops at `uiop:dump-image`, and a `provide` leaks into `*modules*`

Difficulty: Medium

The full native E2E on develop `447524848` (2026-11-07) failed 5994 of 11940 `CiSpecE2eTest`
nodes. Every leg (interpreter, JVM, WASM, component, each with and without `--simd`) produced
5939 of 9849 lines. `./mvnw test` passed (it skips `CiSpecE2eTest`). Log:
`/tmp/claude-1000/-nvme1n1-disk-rontolisp/a232d4c6-52b0-4851-90d5-2fb0596908e6/scratchpad/fulltest-batch47/native-e2e.log`.

1. **`uiop-image-hooks-and-backtraces`** (ci-spec.yaml around line 23953) expects
   `(handler-case (uiop:dump-image "x.img") (uiop:not-implemented-error () :dump-image))` to answer
   `:DUMP-IMAGE`. Since `b6452984a` ("asdf:make" / `rontolisp build`, `uiop:dump-image` now builds),
   the output stops right after that case's `"second"` line on every leg: the call no longer signals
   `not-implemented-error`, and it ends or derails the one concatenated program per backend. The
   i83 worker ran only `restart-system`, not this case. Decide what `dump-image` does inside a
   program run by `rontolisp` (case driver: interpreter `cases:` in one process, compiled programs)
   and inside a compiled program. Make it signal a condition the case can catch, of a documented
   type, without exiting the process. Then fix the case's expectation and the docs.
2. **`esrap-enablement-language-group`** prints `*modules*` and expects `NIL`. It now sees
   `("NRQ-PRESENT")`, left by `nested-require-loads-nothing-when-it-runs` (`(provide :nrq-present)`,
   around line 1738; `cdfdaf422`). Cases share one process on the interpreter leg, and one program on
   the compiled legs. Make the new case leave `*modules*` as it found it, or make the esrap case
   independent of other cases' modules.

Done when: a local native E2E run of `CiSpecE2eTest` passes on every leg.
