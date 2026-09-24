# ci-spec runs ZERO tests in the plain `test` job

Difficulty: Medium

`CiSpecE2eTest` is gated on `-Drontolisp.binary` (`assumeTrue`,
`CiSpecE2eTest.java:232`), so the `test` CI job reports "Tests run: 0" for it and
the whole corpus runs only in the `native-image` legs -- each of which is SKIPPED
whenever the `test` job fails. Consequence measured 2026-09-24: the stream
semantics change of `9a5face40` (.todo/927) regressed two ci-spec expectations
(`pathname-family-and-broadcast-streams`' zero-component broadcast typep and
`gray-stream-output-protocol-widening`'s fresh-line blank) and NOTHING noticed
for a day, because every `native-image` run in that window was skipped by an
unrelated red `test` job -- the corpus's only execution path. The regression was
found only when the arm64 native leg finally ran (CI run 35941824937, 1034
failures, mostly the one-line slice shift cascading from the dropped blank).

## What is needed

A JVM-leg execution of the corpus inside the plain `test` job, so an
interpreter/JVM-level regression reaches CI without a native build. Shapes to
weigh (measure, do not reason):

- run the corpus through a compiled JVM class / the interpreter via a cheap
  always-present "binary" stand-in, keeping the native legs as the full check;
- or split the corpus so the per-backend single-run driver can execute
  interpreter/JVM legs in the `test` job and wasm legs only where wasmtime
  exists (it is installed in the `test` job too -- check).
- Mind the runtime cost: the native leg spends ~7 min on the corpus; a second
  whole pass in `test` pays it again on every push. The `--simd` second axis is
  native-leg-only and need not join the cheap pass.

## Do not confuse with

- The flaky `readSequenceIntoAStringCostsAboutWhatTheSameFileCostsAsBytes`
  bound (fixed by pooling, `e0cf28d43`) -- that one is a loaded-runner timing
  spread, not this gate.
