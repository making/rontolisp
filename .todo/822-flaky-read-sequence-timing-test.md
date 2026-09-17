# Flaky `read-sequence`-into-string cost assertion on loaded CI runners

Difficulty: Low

`WasmLispCompilerIntegrationTest#readSequenceIntoAStringCostsAboutWhatTheSameFileCostsAsBytes`
(`src/test/java/am/ik/rontolisp/codegen/wasm/WasmLispCompilerIntegrationTest.java:13815`)
asserts `chars <= 500 + 6 * bytes` for reading 1,048,576 `y` characters off one
file as a string vs as a byte vector. It fails intermittently on CI and passes on
retry with no code change:

| run (ci.yaml) | head | chars | bytes | threshold (`500+6*bytes`) |
|---|---|---|---|---|
| `35069583243` | `393d2e8d8` (2026-09-16, pre-821) | 814 | 36 | 716 -- FAIL |
| `35167013620` job `105030257909` | `794e90a26` (2026-09-17) | 1019 | 49 | 794 -- FAIL |
| `35094394422`, `35082473002`, `35165496998` | neighboring heads | -- | -- | PASS |

CI runs JUnit at parallelism 4 on 4 CPUs with a cold wasmtime; when the byte leg
is fast (~40 ms) the threshold (~740 ms) sits inside the string leg's loaded-machine
spread (~800-1000 ms). Nothing in the measured paths changed around these runs --
the failure is runner noise, not a product regression.

## Constraints for the fix

- Do not weaken the invariant the test guards: the character arm of `read-sequence`
  may not cost one `fd_read` per code point (`.kb/character-sequence-io.md` -- the
  23x `uiop:read-file-string` regression it pins). A looser number must still fail
  if the per-character import comes back; read `.kb/measurement-probes.md` first
  (whether the number answers the question asked).
- Candidate shapes (pick one, measure it): a warmup read before timing, a larger
  fixed term, a ratio-only bound, or quarantine-with-retry. Land the choice with a
  re-run, not by reasoning about it.
- This item is test-only: no product file should change.

## Do not confuse with

- The `native-image (amd64)` failures on the same runs: those were the three stale
  `#*` bit-vector ci-spec expectations (24 legs), fixed by `794e90a26` and verified
  4532/0 locally. No todo needed; the claimed spare number stays a gap.
