# k81. `JavaSpecE2eTest` went from 73 s to 358 s: an interpreter thread spins

Difficulty: Medium

Found by k70 (2026-11-21). On k70's branch `JavaSpecE2eTest` took 73 s before
`git merge origin/develop` and 358 s after it. The merge brought k64 (declared arrays on
wasm, `b089a691d`) and k67 (a static field defined with its default, `58050134d`), and k66,
k65, k62 and k69 before them. In the slow run one interpreter thread of a corpus program that
starts threads spent about 340 s of CPU in a `while` / `handler-case` loop. The test still
passes, so nothing failed; the time is the only sign.

To do:

- Measure `JavaSpecE2eTest` on `bead2626e` and on the parents of the suspect merges, and
  find the first commit that is slow (bisect by the test's time, or by the one case).
- Name the corpus case and the loop. A thread that should block (a wait, a join, a queue
  take, a latch) and instead polls is the likely shape: k67 gave a class's done flag and
  static fields a value where they had none, which changes what a reader sees before the
  class is initialized; k64 changed array reads under declarations.
- Fix with a failing test first if the program's behaviour is wrong; if it is only slow,
  pin the time where a test can (a bound in the case, not a sleep).
