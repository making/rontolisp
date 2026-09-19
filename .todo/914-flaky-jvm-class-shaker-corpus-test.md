# `JvmClassShakerCorpusTest` fails once in N full suite runs and passes alone

Difficulty: Medium

## The observation (2026-09-20)

A session running a full `./mvnw spring-javaformat:apply test` got exit 1 with
`am.ik.jvm.JvmClassShakerCorpusTest` red. It then ran that class ALONE, both with and
without its change -- both green -- and re-ran the full suite: exit 0, 277 surefire
reports. It treated the failure as unrelated flakiness and landed its change.

That is the reasoning this item exists to remove. A test that fails once per N full runs
and passes in isolation is not noise: it is a guard that has stopped answering, and the
next time it goes red the same reasoning will land a real regression behind it. The
failure text was not captured -- the re-run overwrote
`target/surefire-reports/am.ik.jvm.JvmClassShakerCorpusTest.txt` -- so the first job is to
make the failure reproducible.

## Why this test in particular

`JvmClassShakerCorpusTest` is unusual in three ways at once, and every one of them is a
plausible flake source:

1. **It runs in the project root.** It is the only test that writes there: it snapshots
   the top-level entries (`CorpusFixtures.snapshotTopLevel(Path.of("."))`), stages
   `wpc-sub/`, runs the whole ci-spec corpus in process twice (so dozens of cases write
   scratch files and trees at relative paths), and then DELETES every top-level entry that
   was not in the snapshot. Surefire runs `forkCount=2`: a second JVM process is running
   other test classes in the same working directory for the whole window.
2. **It compares two RUNS, not two byte strings.** The assertion is
   `run(optimized).equals(run(plain))` -- two in-process executions of the corpus in one
   JVM, output captured by swapping `System.out`. Anything whose printed text is a
   function of the machine rather than the program breaks it: elapsed time (`ci-sleep`
   asserts >= 40 internal units after `(sleep 0.05)`), a late thread printing into the
   NEXT capture buffer, the filesystem state the first run left behind for the second.
3. **It is the longest-running class outside the WASM one** (33 s when written, 60-65 s in
   recent full runs), so it overlaps the widest window of whatever else the machine does.

## What to investigate, in order

- **Cross-fork interference in the project root.** `forkCount=2` plus
  `removeNewEntries(Path.of("."), before)`: does anything the other fork does at the
  project root land inside the snapshot window, and does the corpus itself read the root's
  listing anywhere (`directory`, `uiop:` pathname walks) so that a concurrent entry
  changes what the plain run and the optimized run print?
- **State the plain run leaves for the optimized run.** Both executions share one working
  directory and one JVM. A case that probes for a file's absence, lists a directory, or
  appends is order-dependent by construction.
- **A straggler thread.** `run()` swaps `System.out` per execution. Anything still running
  when `main` returns -- an async future's pool, the freshly landed sized worker thread of
  `.todo/911` -- prints into whichever buffer is installed at the time.
- **Timing under load.** The `sleep`/`get-internal-real-time` case, and a
  `StackOverflowError` (the shape a September 8 episode took: `Test._append` recursing)
  whose margin depends on what else the box is doing.
- **Memory pressure**: two forks plus other worktrees' builds on one machine.

Reproduce before fixing: run the class repeatedly, and run the full suite more than once
if that is what it takes. Capture the surefire report, not a guess. Grep `.kb/` first --
`.kb/test-execution.md` is the topic file and already records two measured cases of
"one machine, several builds, one shared constant".

If it cannot be reproduced, that is not a close: record exactly what was ruled out here
and leave the item open.
