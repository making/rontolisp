# `RontoLispCliTest#theDeepProgramDoesNotFitALauncherSizedStack` failed once under parallel load

Difficulty: Medium

Seen once while working `.todo/836` (2026-09-17): the test failed when it ran concurrently
with other Scheme tests, and passed alone and in the full suite. The suspicion is that
the depth a launcher-sized stack reaches depends on JIT state (interpreted frames are
larger than compiled ones), so a fixed depth sits on both sides of the limit.

## To do

1. Reproduce: run the test repeatedly beside the Scheme test classes (and with
   `-Xint` / after a warm-up) and record how the reachable depth varies.
2. Make the assertion independent of JIT state (a depth with a wide margin on both sides,
   or measure the limit in the same JVM first), or isolate the test. Record the numbers in
   `.kb/interpreter-stack.md`.
3. If it does not reproduce in N runs, record that and close.
