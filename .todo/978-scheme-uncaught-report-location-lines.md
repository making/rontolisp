# An interpreted Scheme program's uncaught report does not say where it happened

Difficulty: Medium

An interpreted Common Lisp file's uncaught report carries location lines under the report
line (`.kb/source-positions.md` "Phase 4"): the reader answers a `LocatedCons` for each
datum's outermost cons, the package resolver's rewrites keep it through
`SourceProvenance.inherit`, and `eval/ConditionTrace` picks the innermost one up while the
condition unwinds. A `.scm` file prints the report line alone: `SchemeReader` builds plain
conses (it positions its datums in its own identity map for syntax errors), and the
lowering to core forms rebuilds every form through `SchemeExpander`'s host `inherit`, which
only copies offsets between those maps.

Goal: `rontolisp prog.scm` prints the same location lines a `.lisp` file does. The reader
has to hand out `LocatedCons` datums when it reads a named file with no compile scope open
(mind datum labels, as `LispReader.readExpr` does), and the lowering's inherit has to answer
a located copy of each rewritten top cell the way `SourceProvenance.inherit` does. Pin with a
`.scm` twin of `RontoLispCliStreamsTest#anUncaughtConditionNamesTheInnermostFormAndTheFunctionHoldingIt`;
the Scheme tests that compare the whole of standard error (`RontoLispCliStreamsTest`,
`SchemeSpecE2eTest`) then compare the report line.
