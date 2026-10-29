# clojure-spec's JVM leg must not compile the whole corpus as a run-time-lowering host

Difficulty: Medium

Since h00 (2026-10-30) the case `the-interpreter-lowers-at-run-time` names `eval` and the
other run-time lowering verbs over computed arguments, so the ONE program
`ClojureSpecE2eTest` compiles for the JVM leg is a host class
(`.kb/jvm-run-time-lowering.md`, "the whole spec program is a host"). Host mode keeps the
whole Clojure library unstripped (every arm family), forces threads mode and the apply tier,
turns raw globals off and makes every defun a shake root. So the JVM leg of all ~2600 cases
now checks host-mode output only: arm stripping, raw globals, non-threads specials and the
tree shaker on an ordinary Clojure program are no longer exercised by the corpus on the JVM.

Fix: keep the corpus program an ordinary (non-host) class and run the run-time lowering
case on its own host program (a per-case isolation in the corpus runner, or the existing
`aClassThatLowersAtRunTimeRunsAloneAsAClassAndAsAJar`-style separate compile). Check that
the JVM leg's compiled corpus is no longer a host (e.g. no `_rtImage` field) and that every
case is still green there. Same check for `CiSpecE2eTest`'s corpus.
